/*
 * Copyright 2009-2017. DigitalGlobe, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */


package org.mrgeo.mapalgebra

import java.awt.image.DataBuffer
import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}
import java.util

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.mrgeo.data.{DataProviderFactory, ProviderProperties}
import org.mrgeo.data.raster.{MrGeoRaster, RasterWritable}
import org.mrgeo.data.rdd.{RasterRDD, VectorRDD}
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.data.vector.FeatureIdWritable
import org.mrgeo.geometry.{Geometry, GeometryFactory, Point}
import org.mrgeo.job.JobArguments
import org.mrgeo.mapalgebra.parser.{ParserException, ParserNode}
import org.mrgeo.mapalgebra.raster.RasterMapOp
import org.mrgeo.mapalgebra.vector.VectorMapOp
import org.mrgeo.utils.tms._
import org.mrgeo.utils.{LatLng, Logging, SparkUtils, SparkVectorUtils}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Given a friction surface input image, and a source point(s), compute the
  * cost to travel to each pixel from the source point out to an optional
  * maximum cost. The friction surface is single-band where pixel values
  * are seconds/meter. The source point can be either a vector data source
  * of the result of a map algebra function that returns vector data (like
  * InlineCsv).
  *
  * The general algorithm starts at the specified start pixel and computes
  * the cost to traverse from the center of the pixel to the center of each
  * of its eight neighbor pixels. That cost is stored in the output image
  * tile. Each time a neighbor pixel changes, it is added to the processing
  * queue and is eventually processed itself. Pixel values are only changed
  * if the cost from the "center" pixel being processed is smaller than the
  * current cost to reach that pixel. Note that pixels can be revisited
  * many times. The algorithm iterates until there are no more changed pixels
  * in the processing queue.
  *
  * Executing this algorithm on a tiled image adds complexity. The distributed
  * processing is implemented with Spark. Initially, a Spark RDD is constructed
  * that copies the original friction tiles into new tiles where the first
  * bands contain those friction values, and the last band contains the current
  * cost to reach that pixel from the source point. The source point and the tile
  * it resides in are included in the set of changed pixels. The code loops until
  * there are no more changed pixels. Each pass through the loop performs a Spark
  * "map" operation over the tiles. If there are no pixel changes for that
  * tile, the original tile is simply returned. If there are changes, the tile
  * is re-computed based on the algorithm described above to update other pixels
  * within that tile. If any edge pixels in that tile change, they are added to
  * the accumulator. After the map operation completes, the content of the
  * accumulator becomes the list of changed pixels to process during the next
  * pass through the loop. This is continued until there are no more pixels
  * to process. Note that the result of each pass through the loop can result in
  * pixel changes across many tiles (which get processed in the next pass).
  * Note that the same tile can get processed multiple times before the
  * algorithm completes.
  */
object CostDistanceMapOp extends MapOpRegistrar {

  override def register:Array[String] = {
    Array[String]("costDistance", "cd")
  }

  override def apply(node:ParserNode, variables:String => Option[ParserNode]):MapOp =
    new CostDistanceMapOp(node, variables)

  def create(raster:RasterMapOp, maxCost:Double, zoom:Int,
             sourcePoints:Array[Double]):MapOp = {

    new CostDistanceMapOp(raster, maxCost.toFloat, zoom, sourcePoints)
  }
}


class CostDistanceMapOp extends RasterMapOp with Externalizable with Logging {

  var friction:Option[RasterMapOp] = None
  var srcVector:Option[VectorMapOp] = None
  var sourcePoints:Option[Array[Double]] = None
  var frictionZoom:Option[Int] = None
  var numExecutors:Int = -1
  var numCoresPerExecutor:Int = -1
  var maxCost:Float = -1
  var providerProperties:ProviderProperties = _
  private var rasterRDD:Option[RasterRDD] = None

  override def rdd():Option[RasterRDD] = rasterRDD

  override def setup(job:JobArguments, conf:SparkConf):Boolean = {
    numExecutors = conf.getInt("spark.executor.instances", -1)
    numCoresPerExecutor = conf.getInt("spark.executor.cores", -1)
    logInfo("num executors = " + numExecutors)
    logInfo("num cores per executor = " + numCoresPerExecutor)
    providerProperties = ProviderProperties.fromDelimitedString(job.getSetting(MapAlgebra.ProviderProperties, ""))
    true
  }

  override def teardown(job:JobArguments, conf:SparkConf):Boolean = true

  override def getZoomLevel(): Int = {
    frictionZoom.getOrElse(friction.getOrElse(throw new IOException("Raster input was not specified")).getZoomLevel())
  }

  override def execute(context:SparkContext):Boolean = {
    // val t0 = System.nanoTime()

    val inputFriction:RasterMapOp = friction getOrElse (throw new IOException("Input MapOp not valid!"))
    val sourcePointsRDD = srcVector match {
      case Some(mapOp) => mapOp.rdd().getOrElse(throw new IOException("Missing source points"))
      case None =>
        sourcePoints match {
          case Some(pointsList) =>
            // Convert the array of lon/let pairs to a VectorRDD
            var recordData = new ListBuffer[(FeatureIdWritable, Geometry)]()
            for (i <- pointsList.indices by 2) {
              val geom = GeometryFactory.createPoint(pointsList(i).toFloat, pointsList(i + 1).toFloat)
              recordData += ((new FeatureIdWritable(i / 2), geom))
            }
            VectorRDD(context.parallelize(recordData))
          case None => throw new IOException("Missing source points")
        }
    }
    val frictionMeta = inputFriction.metadata() getOrElse
                       (throw new IOException("Can't load metadata! Ouch! " + inputFriction.getClass.getName))
    val frictionNoDatas = frictionMeta.getDefaultValuesFloat

    val zoom = frictionZoom match {
      case Some(z) =>
        if (frictionMeta.getMaxZoomLevel < z) {
          throw new IOException("The image has a maximum zoom level of " + frictionMeta.getMaxZoomLevel)
        }
        z
      case _ => frictionMeta.getMaxZoomLevel
    }

    val outputBounds = {
      if (maxCost < 0) {
        frictionMeta.getBounds
      }
      else {
        // Find the number of tiles beyond the MBR that need to be included to
        // cover the maxCost. Pixel values are in meters/sec and the maxCost is
        // in seconds. We use a worst-case scenario here to ensure that we include
        // at least as many tiles, and we accomplish that by using the minimum
        // pixel value.
        //
        // NOTE: Currently, we only work with a single band - in other words cost
        // distance is computed using a single friction value regardless of the
        // direction of travel. If we ever modify the algorithm to support different
        // friction values per direction, then this computation must change as well.
        val stats = frictionMeta.getImageStats(zoom, 0)
        if (stats == null) {
          frictionMeta.getBounds
        }
        else {
          logInfo("Calculating tile bounds for maxCost " + maxCost + ", min = " + stats.min + " and bounds " +
                  frictionMeta.getBounds)
          if (stats.min == Double.MaxValue) {
            throw new IllegalArgumentException("Invalid stats for the friction surface: " + frictionMeta.getPyramid +
                                               ". You will need to specify a maxCost in the CostDistance call")
          }
          calculateBoundsFromCost(maxCost, sourcePointsRDD, stats.min, frictionMeta.getBounds)
        }
      }
    }

    log.debug("outputBounds = " + outputBounds)
    val tilesize = frictionMeta.getTilesize

    val tileBounds = {
      TMSUtils.boundsToTile(outputBounds, zoom, tilesize)
    }
    if (tileBounds == null) {
      throw new IllegalArgumentException("No tile bounds for " + frictionMeta.getPyramid + " at zoom level " + zoom)
    }

    log.info("tileBounds = " + tileBounds)
    val prelimRDD = inputFriction.rdd(zoom) getOrElse
      (throw new IOException("Can't load RDD! Ouch! " + inputFriction.getClass.getName))
    val frictionRDD = prelimRDD.filter(U => {
      val t = TMSUtils.tileid(U._1.get, zoom)
      tileBounds.contains(t, true)
    })

    // val width:Short = tilesize.toShort
    // val height:Short = tilesize.toShort
    val res = TMSUtils.resolution(zoom, tilesize)

    // Create a hash map lookup where the key is the tile id and the value is
    // the set of source (starting) pixels in that tile.
    val startPts = mutable.Map.empty[Long, mutable.Set[(Pixel, Point)]]

    sourcePointsRDD.map(startGeom => {
      if (startGeom == null || startGeom._2 == null || !startGeom._2.isInstanceOf[Point]) {
        throw new IOException("Invalid starting point, expected a point geometry: " + startGeom)
      }

      val startPt = startGeom._2.asInstanceOf[Point]
      val tile:Tile = TMSUtils.latLonToTile(startPt.getY.toFloat, startPt.getX.toFloat, zoom,
        tilesize)
      val startTileId = TMSUtils.tileid(tile.tx, tile.ty, zoom)

      val startPixel = TMSUtils.latLonToTilePixelUL(startPt.getY.toFloat, startPt.getX.toFloat, tile.tx, tile.ty,
        zoom, tilesize)
      (startTileId, (startPixel, startPt))
    }).collect().foreach(pixel => {
      if (!startPts.contains(pixel._1)) {
        startPts.put(pixel._1, mutable.Set.empty[(Pixel, Point)])
      }
      startPts(pixel._1) += pixel._2
    })


    // After some experimentation with different values for repartitioning, the
    // best performance seems to come from using the number of executors for this
    // job.
    val numTasks = numExecutors * numCoresPerExecutor
    val isDynamicAlloc = context.getConf.getBoolean("spark.dynamicAllocation.enabled", defaultValue = false)
    val repartitioned = if (!isDynamicAlloc && numTasks > 1) {
      logInfo("Repartitioning to " + numTasks + " partitions")
      val partitions = frictionRDD.partitions.length
      if (partitions < numTasks) {
        frictionRDD.repartition(numTasks)
      }
      else if (partitions > numTasks) {
        frictionRDD.coalesce(numTasks)
      }
      else {
        frictionRDD
      }
    }
    else {
      //      logInfo("No need to repartition")
      frictionRDD
    }

    val pixelSizeMeters = (res * LatLng.METERS_PER_DEGREE).toFloat


    var costs = makeRasters(repartitioned)
    var changes = buildInitialPoints(costs, frictionNoDatas, startPts, context, pixelSizeMeters)

    // Force the RDD to materialize
    costs.count()


    // Process changes until there aren't any more
    var counter:Long = 0
    do {
//      val t0 = System.currentTimeMillis()
      // Use an accumulator to capture changes for the neighbor tiles as we process.
      val changesAccum = context.accumulator(new NeighborChangedPoints)(NeighborChangesAccumulator)

      val previous = costs

      var mapAccum = context.accumulator(0, "Tiles Mapped")
      var processedAccum = context.accumulator(0, "Tiles Processed")

      //      costs.foreach(tile => {
      //        val raster = RasterWritable.toRaster(tile._2)
      //
      //        val t = TMSUtils.tileid(tile._1.get, zoom)
      //        GDALUtils.saveRasterTile(raster, "/data/export/costs/tile-" + tile._1.get,
      //          t.tx, t.ty, zoom, Float.NaN)
      //      })

      costs = previous.map(tile => {
        mapAccum += 1
        val tileid = tile._1.get
//        val t2 = System.currentTimeMillis()
        val tileChanges = changes.get(tileid)
//        logError("Looking up changes took " + (System.currentTimeMillis() - t2))
        if (tileChanges != null) {
          processedAccum += 1
          //          changesToProcess.dump(zoomLevel)

          // This tile has changes to process. Update the pixel values within the
          // tile accordingly while accumulating changes to this tile's neighbors.
          val raster = RasterWritable.toMrGeoRaster(tile._2)

//          val t1 = System.currentTimeMillis()
          processTile(tileid, raster, frictionNoDatas, tileChanges, changesAccum, zoom, pixelSizeMeters, tileBounds)
//          logError("Time for processTile on " + tileid + " is " + (System.currentTimeMillis() - t1))

          (tile._1, RasterWritable.toWritable(raster))
        }
        else {
          (tile._1, tile._2.copy())
        }
      }).persist(StorageLevel.MEMORY_AND_DISK_SER)

      // Force the rdd to materialize
      costs.count()

      previous.unpersist()

      //      costs.foreach(tile => {
      //        val raster = RasterWritable.toRaster(tile._2)
      //
      //        val t = TMSUtils.tileid(tile._1.get, zoom)
      //        GDALUtils.saveRasterTile(raster, "/data/export/costs/tile-" + tile._1.get,
      //          t.tx, t.ty, zoom, Float.NaN)
      //      })

      changes = changesAccum.value


      logInfo("Changes after iteration " + counter + ": " + changes.totalCount())
      counter += 1
//      logError("Processing time for loop iteration is " + (System.currentTimeMillis() - t0))
    } while (changes.size() > 0)


    rasterRDD = Some(RasterRDD(costs.map(tile => {
      val sourceRaster = RasterWritable.toMrGeoRaster(tile._2)

      // Need to convert our raster to a single band raster for output.
      val h = sourceRaster.height()
      val w = sourceRaster.width()
      val singleBandRaster = MrGeoRaster.createEmptyRaster(w, h, 1, DataBuffer.TYPE_FLOAT)
      val totalCostBand = sourceRaster.bands() - 1
      var y:Int = 0
      while (y < h) {
        var x:Int = 0
        while (x < w) {
          val s:Float = sourceRaster.getPixelFloat(x, y, totalCostBand)
          singleBandRaster.setPixel(x, y, 0, s)
          x += 1
        }
        y += 1
      }

      (new TileIdWritable(tile._1), RasterWritable.toWritable(singleBandRaster))
    })))

    rasterRDD.get.count()

    metadata(SparkUtils.calculateMetadata(rasterRDD.get, zoom, Float.NaN, bounds = outputBounds, calcStats = false))
    true
  }

  /**
    * Given one or more source points, compute a Bounds surrounding those points that
    * extends out to maxCost above, below, and the left and right of the MBR of those
    * points. This will encompass the region in which the cost distance algorithm
    * will run.
    *
    * The maxCost is in seconds, and the minPixelValue is in meters/second. This method
    * first computes the distance in meters, and then determines how many pixels are
    * required to cover that distance, both vertically and horizontally. It then adds
    * that number of pixels to the top, bottom, left and right of the MBR of the source
    * points to find the pixel bounds. Finally, it converts those pixels to
    * lat/lng in order to construct bounds from those coordinates.
    *
    * The returned Bounds will not extend beyond world bounds.
    *
    * @param maxCost         How far the cost distance algorithm should go in computing distances.
    *                        Measured in seconds.
    * @param sourcePointsRDD One or more points from which the cost distance algorithm will
    *                        compute minimum distances to all other pixels.
    * @param minPixelValue   The smallest value assigned to a pixel in the friction surface
    *                        being used for the cost distance. Measure in seconds/meter.
    * @return
    */
  def calculateBoundsFromCost(maxCost:Double, sourcePointsRDD:VectorRDD,
                              minPixelValue:Double, imageBounds:Bounds):Bounds = {
    // Locate the MBR of all the source points
    val bounds = SparkVectorUtils.calculateBounds(sourcePointsRDD)
    val distanceInMeters = maxCost / minPixelValue

    // Since we want the distance along the 45 deg diagonal (equal distance above and
    // to the right) of the top right corner of the source points MBR, we can compute
    // the point at a 45 degree bearing from that corner, and use pythagorean for the
    // diagonal distance to use.
    val diagonalDistanceInMeters = Math.sqrt(2) * distanceInMeters
    // Find the coordinates of the point that is distance meters to right and distance
    // meters above the top right corner of the sources points MBR.
    val tr = new LatLng(bounds.n, bounds.e)
    val trExpanded = LatLng.calculateCartesianDestinationPoint(tr, diagonalDistanceInMeters, 45.0)

    // Find the coordinates of the point that is distance meters to left and distance
    // meters below the bottom left corner of the sources points MBR.
    val bl = new LatLng(bounds.s, bounds.w)
    val blExpanded = LatLng.calculateCartesianDestinationPoint(bl, diagonalDistanceInMeters, 225.0)

    // Make sure the returned bounds does not extend beyond the image bounds itself.
    new Bounds(Math.max(blExpanded.getLng, imageBounds.w),
      Math.max(blExpanded.getLat, imageBounds.s),
      Math.min(trExpanded.getLng, imageBounds.e),
      Math.min(trExpanded.getLat, imageBounds.n))
  }

  def isNoData(value: Float, nodataValue: Float): Boolean = {
    if (nodataValue.isNaN) {
      value.isNaN
    }
    else {
      value == nodataValue
    }
  }

  def processTile(tileid:Long,
                  raster:MrGeoRaster,
                  frictionNoData: Array[Float],
                  changes:List[CostPoint],
                  changesAccum:Accumulator[NeighborChangedPoints],
                  zoom:Int,
                  pixelsize:Float,
                  tileBounds:TileBounds):Unit = {
    val startTime = System.nanoTime()

    val tile = TMSUtils.tileid(tileid, zoom)

    // length of the pixel diagonal
    val pixelsizediag = Math.sqrt(2.0 * pixelsize * pixelsize).toFloat

    val costBand = raster.bands() - 1
    // cost band is the last band in the raster
    val multiband = costBand > 1 // if there are more than 2 bands, it is multiband friction

    val width = raster.width()
    val height = raster.height()

    @SuppressFBWarnings(value = Array("FE_FLOATING_POINT_EQUALITY"), justification = "Scala generated code")
    @SuppressFBWarnings(value = Array("UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS"),
      justification = "Scala generated code")
    case class NeighborData(dx:Int, dy:Int, multibandNdx:Int, dist:Float)

    val UP_LEFT = 0
    val UP = 1
    val UP_RIGHT = 2
    val LEFT = 3
    val RIGHT = 4
    val DOWN_LEFT = 5
    val DOWN = 6
    val DOWN_RIGHT = 7

    val neighborData = Array[NeighborData](
      NeighborData(-1, -1, 7, pixelsizediag), // UP_LEFT),
      NeighborData(0, -1, 0, pixelsize), // UP),
      NeighborData(1, -1, 1, pixelsizediag), // UP_RIGHT),
      NeighborData(-1, 0, 6, pixelsize), // LEFT),
      NeighborData(1, 0, 2, pixelsize), // RIGHT),
      NeighborData(-1, 1, 5, pixelsizediag), // DOWN_LEFT),
      NeighborData(0, 1, 4, pixelsize), // DOWN),
      NeighborData(1, 1, 3, pixelsizediag) // DOWN_RIGHT)
    )

    def isSmaller(newcost:Float, oldcost:Float):Boolean = {
      !newcost.isNaN && (oldcost.isNaN || newcost < (oldcost - 1e-7)) // Allow for floating point math inaccuracy
    }

    def isSmallerMaxCost(newcost:Float, oldcost:Float):Boolean = {
      (maxCost <= 0.0 || newcost <= (maxCost + 1e-8)) &&
      !newcost.isNaN && (oldcost.isNaN || newcost < (oldcost - 1e-7)) // Allow for floating point math inaccuracy
    }

    def calculateCostPoint(px:Int, py:Int, direction:Int, cost:Float): Option[CostPoint] = {
      val (diag, dist) = direction match {
        case UP | LEFT | DOWN | RIGHT => (false, pixelsize)
        case _ => (true, pixelsizediag)
      }

      val pixelcost = if (multiband) {
        val f = raster.getPixelFloat(px, py, neighborData(direction).multibandNdx)
        if (isNoData(f, frictionNoData(neighborData(direction).multibandNdx))) {
          return None
        }
        f * dist
      }
      else {
        val f = raster.getPixelFloat(px, py, 0)
        if (isNoData(f, frictionNoData(0))) {
          return None
        }
        f * dist * 0.5f
      }

      var x = px + neighborData(direction).dx
      if (x < 0) {
        x += width
      }
      else if (x >= width) {
        x = width - x
      }

      var y = py + neighborData(direction).dy
      if (y < 0) {
        y += height
      }
      else if (y >= height) {
        y = height - y
      }

      Some(new CostPoint(x.toShort, y.toShort, cost, pixelcost, diag))
    }


    // Store the edge values in the raster before processing it so we can compare
    // after processing to see which edge pixels changed
    val origTopEdgeValues:Array[Float] = new Array[Float](width)
    val origBottomEdgeValues:Array[Float] = new Array[Float](width)
    val origLeftEdgeValues:Array[Float] = new Array[Float](height)
    val origRightEdgeValues:Array[Float] = new Array[Float](height)

    val preStart:Double = System.nanoTime()

    var px:Int = 0
    while (px < width) {
      origTopEdgeValues(px) = raster.getPixelFloat(px, 0, costBand)
      origBottomEdgeValues(px) = raster.getPixelFloat(px, height - 1, costBand)
      px += 1
    }

    var py:Int = 0
    while (py < height) {
      origLeftEdgeValues(py) = raster.getPixelFloat(0, py, costBand)
      origRightEdgeValues(py) = raster.getPixelFloat(width - 1, py, costBand)
      py += 1
    }

    // apply the incoming values...
    val queue = new java.util.PriorityQueue[CostPoint]()
    changes.foreach(pt => {
      // we'll do the cost check here since maintaining the priority queue is expensive
      val currentCost = raster.getPixelFloat(pt.px, pt.py, costBand)

      // in the single band friction, the edge point (point.pixelCost) has 1/2 the total cost,
      // calculate the rest.  Add the other part...
      if (multiband) {
        val newCost = pt.cost + pt.pixelCost

        if (isSmallerMaxCost(newCost, currentCost)) {
          queue.add(pt)
        }
      }
      else {
        val friction = raster.getPixelFloat(pt.px, pt.py, 0)
        if (!isNoData(friction, frictionNoData(0))) {
          pt.pixelCost += (friction * (if (pt.diagonal) {
            pixelsizediag
          }
          else {
            pixelsize
          }) * 0.5f)
          val newCost = pt.cost + pt.pixelCost

          if (isSmallerMaxCost(newCost, currentCost)) {
            queue.add(pt)
          }
        }
      }
    })

    // Now process each element from the priority queue until empty

    // For each element, check to see if the cost is smaller than the
    // current cost in of the raster. If it is, then compute the cost
    // to each of its neighbors, check the new cost to see if it's less
    // than the neighbor's current cost, and add a new entry to the queue
    // for the neighbor point. If a point around the perimeter of the tile
    // changes, then add an entry to local changedPoints.

    // val preProcessingTime:Double = System.nanoTime() - preStart
    var totalEnqueue:Double = 0.0
    var totalDequeue:Double = 0.0
    // var counter:Long = 0L

    val costPointPool = new mutable.Queue[CostPoint]()
//    var reuseCount:Long = 0
//    var newCount:Long = 0
    // Process the queue of changed points until it is empty
    while (!queue.isEmpty) {
      //      counter += 1
      //      println("pass: " + counter + " queue size: " + queue.size())
      //      val q = new java.util.PriorityQueue[CostPoint]()
      //      for (p <- queue.toArray) {
      //        p match {
      //        case cp: CostPoint => q.add(cp)
      //        case _ =>
      //        }
      //      }
      //
      //      var cnt = 0
      //
      //      while (!q.isEmpty) {
      //        val cp = q.poll()
      //        println("  " + cnt + " x: " + (cp.px - 74) + " y: " + (cp.py - 197) + " cost: " +
      //            cp.cost + " pc: " + cp.pixelCost + " tot: " + (cp.cost + cp.pixelCost))
      ////        println("%2d\t%2d\t%6.3f".format(cp.px - 74, cp.py - 197, cp.cost + cp.pixelCost))
      //        cnt += 1
      //      }

      var t0 = System.nanoTime()

      val point = queue.poll()

      totalDequeue = totalDequeue + (System.nanoTime() - t0)

      val newCost = point.cost + point.pixelCost
      val currentCost = raster.getPixelFloat(point.px, point.py, costBand)

      // check for a lower cost
      if (isSmallerMaxCost(newCost, currentCost)) {
        raster.setPixel(point.px, point.py, costBand, newCost)

        // Since this point has a new cost, check to see if the cost to each
        // of its neighbors is smaller than the current cost assigned to those
        // neighbors. If so, add those neighbor points to the queue.
        // We do the lower cost check here because keeping the priority queue in order
        // expensive, so we don't whant to put values in there if we don't need to...
        var neighbor = 0
        while (neighbor < neighborData.length) {
          val direction = neighborData(neighbor)

          val pxNeighbor = (point.px + direction.dx).toShort
          val pyNeighbor = (point.py + direction.dy).toShort

          // check for edge of the image
          if (pxNeighbor >= 0 && pxNeighbor < width && pyNeighbor >= 0 && pyNeighbor < height) {
            // compute the new cost to the neighbor
            val friction = if (multiband) {
              // multiband friction
              val f = raster.getPixelFloat(point.px, point.py, direction.multibandNdx)
              if (isNoData(f, frictionNoData(direction.multibandNdx))) {
                None
              }
              else {
                Some(f)
              }
            }
            else {
              val neighborFriction = raster.getPixelFloat(pxNeighbor, pyNeighbor, 0)
              if (isNoData(neighborFriction, frictionNoData(0))) {
                None
              }
              else {
                Some((raster.getPixelFloat(point.px, point.py, 0) +
                  neighborFriction) * 0.5f)
              }
            }

            friction match {
              case Some(f) =>
                val currentNeighborCost = raster.getPixelFloat(pxNeighbor, pyNeighbor, costBand)
                val pixelCost = f * direction.dist
                val neighborCost = newCost + pixelCost

                if (isSmallerMaxCost(neighborCost, currentNeighborCost)) {
                  // the costpoint contains the current friction and cost to get to the neighbor
                  val neighborPoint = {
                    if (costPointPool.isEmpty) {
                      //                    newCount += 1
                      new CostPoint(pxNeighbor, pyNeighbor, newCost, pixelCost)
                    }
                    else {
                      //                    reuseCount += 1
                      val cp = costPointPool.dequeue()
                      cp.px = pxNeighbor
                      cp.py = pyNeighbor
                      cp.cost = newCost
                      cp.pixelCost = pixelCost
                      cp.diagonal = false
                      cp
                    }
                  }

                  t0 = System.nanoTime()

                  queue.add(neighborPoint)

                  totalEnqueue = totalEnqueue + (System.nanoTime() - t0)
                }
              case None => // do nothing
            }
          }
          neighbor += 1
        }
        costPointPool.enqueue(point)
      }
    }


//    logError("reuseCount = " + reuseCount)
//    logError("newCount = " + newCount)
//    logError("totalDequeue = " + totalDequeue)
//    logError("totalEnqueue = " + totalEnqueue)
    val t0 = System.nanoTime()
    val edgePoints = new NeighborChangedPoints

    val neighborTileIds = Array.ofDim[Long](neighborData.length)
    for (i <- neighborTileIds.indices) {
      val t = new Tile(tile.tx + neighborData(i).dx, tile.ty - neighborData(i).dy)
      neighborTileIds(i) = TMSUtils.tileid(t.tx, t.ty, zoom)
    }

    // Find edge pixels that have changed so we know how to send messages
    px = 0
    while (px < width) {
      val currentTopCost = raster.getPixelFloat(px, 0, costBand)
      val oldTopCost = origTopEdgeValues(px)

      if (isSmaller(currentTopCost, oldTopCost)) {

        // look at the top of the tile for changes (ignore if top of image)
        if (tile.ty < tileBounds.n) {
          val l = calculateCostPoint(px, 0, UP_LEFT, currentTopCost)
          val c = calculateCostPoint(px, 0, UP, currentTopCost)
          val r = calculateCostPoint(px, 0, UP_RIGHT, currentTopCost)

          // if leftmost pixel (corner, x = 0), so the left goes to the upper left tile
          if (px == 0) {
            l match {
              case Some(lval) => edgePoints.add(neighborTileIds(UP_LEFT), lval)
              case None => // do nothing
            }
          }
          else {
            l match {
              case Some(lval) => edgePoints.add(neighborTileIds(UP), lval)
              case None => // do nothing
            }
          }
          c match {
            case Some(cval) => edgePoints.add(neighborTileIds(UP), cval)
            case None => // do nothing
          }

          // if rightmost pixel (corner, x = width -1), so the right goes to the upper right tile
          if (px == width - 1) {
            r match {
              case Some(rval) => edgePoints.add(neighborTileIds(UP_RIGHT), rval)
              case None => // do nothing
            }
          }
          else {
            r match {
              case Some(rval) => edgePoints.add(neighborTileIds(UP), rval)
              case None => // do nothing
            }
          }
        }
      }

      val currentBottomCost = raster.getPixelFloat(px, height - 1, costBand)
      val oldBottomCost = origBottomEdgeValues(px)

      if (isSmaller(currentBottomCost, oldBottomCost)) {

        if (tile.ty > tileBounds.s) {
          val l = calculateCostPoint(px, height - 1, DOWN_LEFT, currentBottomCost)
          val c = calculateCostPoint(px, height - 1, DOWN, currentBottomCost)
          val r = calculateCostPoint(px, height - 1, DOWN_RIGHT, currentBottomCost)

          // if leftmost pixel (corner, x = 0), so the left goes to the lower left tile
          if (px == 0) {
            l match {
              case Some(lval) => edgePoints.add(neighborTileIds(DOWN_LEFT), lval)
              case None => // do nothing
            }
          }
          else {
            l match {
              case Some(lval) => edgePoints.add(neighborTileIds(DOWN), lval)
              case None => // do nothing
            }
          }
          c match {
            case Some(cval) => edgePoints.add(neighborTileIds(DOWN), cval)
            case None => // do nothing
          }

          // if rightmost pixel (corner, x = width -1), so the right goes to the lower right tile
          if (px == width - 1) {
            r match {
              case Some(rval) => edgePoints.add(neighborTileIds(DOWN_RIGHT), rval)
              case None => // do nothing
            }
          }
          else {
            r match {
              case Some(rval) => edgePoints.add(neighborTileIds(DOWN), rval)
              case None => // do nothing
            }
          }
        }
      }
      px += 1
    }

    // Don't process corner pixels again (already handled as part of top/bottom processing)
    py = 0
    while (py < height) {
      val currentLeftCost = raster.getPixelFloat(0, py, costBand)
      val oldLeftCost = origLeftEdgeValues(py)

      if (isSmaller(currentLeftCost, oldLeftCost)) {

        if (tile.tx > tileBounds.w) {
          val t = calculateCostPoint(0, py, UP_LEFT, currentLeftCost)
          val c = calculateCostPoint(0, py, LEFT, currentLeftCost)
          val b = calculateCostPoint(0, py, DOWN_LEFT, currentLeftCost)

          if (py != 0) {
            t match {
              case Some(tval) => edgePoints.add(neighborTileIds(LEFT), tval)
              case None => // do nothing
            }
          }
          c match {
            case Some(cval) => edgePoints.add(neighborTileIds(LEFT), cval)
            case None => // do nothing
          }

          if (py != height - 1) {
            b match {
              case Some(bval) => edgePoints.add(neighborTileIds(LEFT), bval)
              case None => // do nothing
            }
          }
        }
      }

      val currentRightCost = raster.getPixelFloat(width - 1, py, costBand)
      val oldRightCost = origRightEdgeValues(py)

      if (isSmaller(currentRightCost, oldRightCost)) {

        if (tile.tx < tileBounds.e) {
          val t = calculateCostPoint(width - 1, py, UP_RIGHT, currentRightCost)
          val c = calculateCostPoint(width - 1, py, RIGHT, currentRightCost)
          val b = calculateCostPoint(width - 1, py, DOWN_RIGHT, currentRightCost)

          if (py != 0) {
            t match {
              case Some(tval) => edgePoints.add(neighborTileIds(RIGHT), tval)
              case None => // do nothing
            }
          }
          c match {
            case Some(cval) => edgePoints.add(neighborTileIds(RIGHT), cval)
            case None => // do nothing
          }

          if (py != height - 1) {
            b match {
              case Some(bval) => edgePoints.add(neighborTileIds(RIGHT), bval)
              case None => // do nothing
            }
          }
        }
      }
      py += 1
    }

    if (log.isDebugEnabled) {
      logDebug("Changes post: " + edgePoints.totalCount() + " " + tile)
      edgePoints.tileCount().foreach(t => {
        logDebug(TMSUtils.tileid(t._1, zoom) + " " + t._2)
      })
    }

    if (edgePoints.size() > 0) {
      changesAccum.add(edgePoints)
    }

    // val postProcessingTime:Double = System.nanoTime() - t0
    // val totalTime:Double = System.nanoTime() - startTime
  }

  override def writeExternal(out:ObjectOutput):Unit = {
    out.writeFloat(maxCost)
  }

  override def readExternal(in:ObjectInput):Unit = {
    maxCost = in.readFloat()
  }

  override def registerClasses():Array[Class[_]] = {
    GeometryFactory.getClasses ++ Array[Class[_]](classOf[FeatureIdWritable], classOf[Pixel])
  }

  def buildInitialPoints(frictionRDD:RDD[(TileIdWritable, RasterWritable)],
                         nodataValues: Array[Float],
                         startingPts:mutable.Map[Long, mutable.Set[(Pixel, Point)]],
                         context:SparkContext, pixelsize:Float):NeighborChangedPoints = {

    val initialChangesAccum = context.accumulator(new NeighborChangedPoints)(NeighborChangesAccumulator)

//    val dp = DataProviderFactory.getMrsImageDataProvider(imageName, DataProviderFactory.AccessMode.READ,
//      providerProperties)
//    val tileReader= dp.getMrsTileReader(zoom)
//    val ncp = new NeighborChangedPoints
//    startingPts.foreach(U => {
//      val raster = tileReader.get(new TileIdWritable(U._1))
//      val pointsInTile = startingPts.get(U._1).get
//      for (startPixel <- pointsInTile) {
//        val pixelcost = if (raster.bands() > 2) {
//          0.0f
//        }
//        else {
//          // add a negative 1/2 friction, so the start point cost calculatation will be zero
//          -raster.getPixelFloat(startPixel.px.toInt, startPixel.py.toInt, 0) * pixelsize * 0.5f
//        }
//
//        // starting pixel has no initial cost and no friction to get to that point
//        logInfo("Start tile " + U._1 + " and point " + startPixel.px + ", " + startPixel.py)
//        ncp.add(U._1, new CostPoint(startPixel.px.toShort, startPixel.py.toShort, 0.0f, pixelcost))
//      }
//    })
//    ncp
    frictionRDD.foreach(tile => {
      val tileid = tile._1.get()
      if (startingPts.contains(tileid)) {
        val raster = RasterWritable.toMrGeoRaster(tile._2)
        val pointsInTile = startingPts(tileid)

        val costPoints = new ListBuffer[CostPoint]()
        for (startPair <- pointsInTile) {
          val startPixel = startPair._1
          if (raster.bands() > 2) {
            // starting pixel has no initial cost and no friction to get to that point
            logInfo("Start tile " + tileid + " and point " + startPixel.px + ", " + startPixel.py)
            costPoints += new CostPoint(startPixel.px.toShort, startPixel.py.toShort, 0.0f, 0.0f)
          }
          else {
            // add a negative 1/2 friction, so the start point cost calculatation will be zero
            val startPixelFriction = raster.getPixelFloat(startPixel.px.toInt, startPixel.py.toInt, 0)
            if (!isNoData(startPixelFriction, nodataValues(0))) {
              val pixelcost = -startPixelFriction * pixelsize * 0.5f
              // starting pixel has no initial cost and no friction to get to that point
              logInfo("Start tile " + tileid + " and point " + startPixel.px + ", " + startPixel.py)
              costPoints += new CostPoint(startPixel.px.toShort, startPixel.py.toShort, 0.0f, pixelcost)
            }
            else {
              log.warn("Skipping initial point " + startPair._2.toString() + " which is on a nodata friction cell")
            }
          }
        }
        val ncp = new NeighborChangedPoints
        ncp.addPoints(tileid, costPoints.toList)
        initialChangesAccum.add(ncp)
      }
    })

    initialChangesAccum.value
  }

  def makeRasters(frictionRDD:RDD[(TileIdWritable, RasterWritable)]):RDD[(TileIdWritable, RasterWritable)] = {
    frictionRDD.map(tile => {
      val tileid = tile._1.get()
      val raster = RasterWritable.toMrGeoRaster(tile._2)

      (new TileIdWritable(tileid), RasterWritable.toWritable(addCostBand(raster)))
    })
  }

  def addCostBand(raster:MrGeoRaster):MrGeoRaster = {
    val srcBands = raster.bands()
    val dstBands = srcBands + 1

    val height = raster.height()
    val width = raster.width()

    val dstRaster = MrGeoRaster.createEmptyRaster(width, height, dstBands, DataBuffer.TYPE_FLOAT)

    val totalCostBand = dstBands - 1
    var y:Int = 0
    while (y < height) {
      var x:Int = 0
      while (x < width) {
        // copy the bands...
        var b:Int = 0
        while (b < srcBands) {
          // read all the bands into an array
          val v = raster.getPixelFloat(x, y, b)
          dstRaster.setPixel(x, y, b, v)
          b += 1
        }
        // initial the total cost to NaN
        dstRaster.setPixel(x, y, totalCostBand, Float.NaN)
        x += 1
      }
      y += 1
    }
    dstRaster
  }

  private[mapalgebra] def this(friction:RasterMapOp, maxCost:Float, zoom:Int,
                               sourcePoints:Array[Double]) = {
    this()

    this.friction = Some(friction)
    this.maxCost = maxCost
    this.sourcePoints = Some(sourcePoints)
    this.srcVector = None
    this.frictionZoom = if (zoom > 0) {
      Some(zoom)
    }
    else {
      None
    }
  }

  private[mapalgebra] def this(node:ParserNode, variables:String => Option[ParserNode]) = {
    this()

    val usage:String = "CostDistance takes the following arguments " +
                       "(source points, [friction zoom level], friction raster, [maxCost], [minX, minY, maxX, maxY])"

    val numChildren = node.getNumChildren
    if (numChildren < 2 || numChildren > 8) {
      throw new ParserException(usage)
    }

    var nodeIndex:Int = 0
    srcVector = VectorMapOp.decodeToVector(node.getChild(nodeIndex), variables)
    sourcePoints = None
    nodeIndex += 1

    // Check for optional friction zoom level
    MapOp.decodeInt(node.getChild(nodeIndex)) match {
      case Some(i) =>
        frictionZoom = Some(i)
        nodeIndex += 1
      case _ =>
    }

    // Check that there are enough required arguments left
    if (numChildren <= nodeIndex) {
      throw new ParserException(usage)
    }

    friction = RasterMapOp.decodeToRaster(node.getChild(nodeIndex), variables)
    nodeIndex += 1

    // Check for optional max cost.
    if ((numChildren == (nodeIndex + 1)) || (numChildren == (nodeIndex + 5))) {
      MapOp.decodeDouble(node.getChild(nodeIndex), variables) match {
        case Some(d) =>
          maxCost = if (d < 0) {
            -1
          }
          else {
            d.toFloat
          }
          nodeIndex += 1
        case _ =>
      }
    }
  }
}


//Stores information about points whose cost has changed during processing.

// px: destination pixel x
// py: destination pixel y
// cost: total source pixel cost
// pixelcost: cost for the single pixel (friction * pixel size in meters)
//    OR       if an edge pixel and single band friction, it is the 1/2 cost
// diag: is the direction a diagonal (true) or straight (false).  Only relevent for edge pixels
class CostPoint(var px:Short, var py:Short, var cost:Float, var pixelCost:Float,
                var diagonal:Boolean = false) extends Externalizable with Ordered[CostPoint] {
  def this() = {
    this(-1, -1, 0.0f, 0.0f)
  }

  override def writeExternal(out:ObjectOutput):Unit = {
    out.writeShort(px)
    out.writeShort(py)
    out.writeFloat(cost)
    out.writeFloat(pixelCost)
    out.writeBoolean(diagonal)
  }

  override def readExternal(in:ObjectInput):Unit = {
    px = in.readShort()
    py = in.readShort()
    cost = in.readFloat()
    pixelCost = in.readFloat()
    diagonal = in.readBoolean()
  }


  override def compare(that:CostPoint):Int = {
    val tc = cost + pixelCost
    val thattc = that.cost + that.pixelCost

    if (thattc > tc) {
      -1
    }
    else if (thattc < tc) {
      1
    }
    else {
      0
    }
  }

  override def equals(obj:scala.Any):Boolean = {
    obj match {
      case cp:CostPoint => compare(cp) == 0
      case _ => false
    }
  }

  override def hashCode:Int = {
    new HashCodeBuilder(29, 5).append(px).append(py).append(cost).append(pixelCost).append(diagonal).toHashCode
  }
}

// Stores points from a source tile that changed value and forced
// the target tile to be recomputed. The key in the hash map is the
// direction from the source tile to the target tile.
class NeighborChangedPoints extends Externalizable with Logging {
  // The collection of point changes is keyed on tile id. The value is a
  // a pair containing the direction and the list of changed points. The
  // direction is from the neighbor tile toward the tile in the key.
  private val changes = new util.HashMap[Long, Seq[CostPoint]]()

  def size():Int = changes.size()

  def dump(zoomLevel:Int):Unit = {
    val iter = changes.keySet().iterator()
    while (iter.hasNext) {
      val tileId = iter.next()
      logDebug("tile id: " + TMSUtils.tileid(tileId, zoomLevel))
      val values = changes.get(tileId)
      for (value <- values) {
        logDebug("  " + value)
      }
    }
  }

  def tileCount():Map[Long, Int] = {

    val map = mutable.HashMap.empty[Long, Int]
    changes.foreach(value => map.put(value._1, value._2.length))

    map.toMap
  }

  def totalCount():Int = {
    var cnt:Int = 0

    changes.values().foreach(value => cnt += value.length)

    cnt
  }

  def put(tileId:Long, changedPoints:Seq[CostPoint]):Unit = {
    val value = changes.get(tileId)
    if (value == null) {
      changes.put(tileId, changedPoints)
    }
    else {
      changes.put(tileId, changes.get(tileId) ++ changedPoints)
    }

  }

  def add(tileId:Long, cost:CostPoint):Unit = {
    val value = changes.get(tileId)
    if (value == null) {
      changes.put(tileId, Array[CostPoint](cost))
    }
    else {
      changes.put(tileId, changes.get(tileId) ++ Array[CostPoint](cost))
    }
  }

  def addPoints(tileId:Long, points:Seq[CostPoint]):Unit = {
    put(tileId, points)
  }

  def keySet():util.Set[Long] = changes.keySet()

  def get(tileId:Long):List[CostPoint] = {
    if (changes.containsKey(tileId)) {
      changes.get(tileId).toList
    }
    else {
      null
    }
  }


  def +=(other:NeighborChangedPoints):NeighborChangedPoints = {
    other.changes.foreach(change => {
      put(change._1, change._2)
    })
    this
  }

  override def readExternal(in:ObjectInput):Unit = {
    val tiles = in.readInt()

    var tile = 0
    while (tile < tiles) {
      val tileid = in.readLong()

      val changes = in.readInt()

      val costs = Array.ofDim[CostPoint](changes)
      var change = 0
      while (change < changes) {
        val cp = new CostPoint()
        cp.readExternal(in)

        costs(change) = cp
        change += 1
      }

      put(tileid, costs)
      tile += 1
    }
  }

  override def writeExternal(out:ObjectOutput):Unit = {
    out.writeInt(changes.size())
    changes.foreach(change => {
      out.writeLong(change._1)
      out.writeInt(change._2.length)
      change._2.foreach(pt => {
        pt.writeExternal(out)
      })
    })
  }

  override def equals(obj:scala.Any):Boolean = {
    obj match {
      case that:NeighborChangedPoints =>
        if (that.size() == 0 && size() == 0) {
          true
        }
        else {
          false
        }
      case _ => false
    }
  }
}

// An accumulator used within Spark to accumulate changes to all of the tiles
// processed during a single map pass of the cost distance algorithm.
object NeighborChangesAccumulator extends AccumulatorParam[NeighborChangedPoints] {
  override def addInPlace(r1:NeighborChangedPoints,
                          r2:NeighborChangedPoints):NeighborChangedPoints = {
    r1 += r2
  }

  override def zero(initialValue:NeighborChangedPoints):NeighborChangedPoints = {
    new NeighborChangedPoints
  }
}
