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

import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.apache.spark.rdd.CoGroupedRDD
import org.apache.spark.{HashPartitioner, SparkConf, SparkContext}
import org.mrgeo.data.raster.{MrGeoRaster, RasterWritable}
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.job.JobArguments
import org.mrgeo.mapalgebra.parser.{ParserException, ParserNode}
import org.mrgeo.mapalgebra.raster.RasterMapOp
import org.mrgeo.utils.SparkUtils
import org.mrgeo.utils.tms.Bounds

import scala.collection.mutable

object BandCombineMapOp extends MapOpRegistrar {
  override def register:Array[String] = {
    Array[String]("bandcombine", "bc")
  }

  def create(first:RasterMapOp, others:Array[RasterMapOp]):MapOp =
    new BandCombineMapOp(List(first) ++ others.toList)

  //  @varargs  // create java compatible vararg code
  //  def create(first:RasterMapOp, others:RasterMapOp*):MapOp =
  //    new BandCombineMapOp(List(first) ++ others)

  override def apply(node:ParserNode, variables:String => Option[ParserNode]):MapOp =
    new BandCombineMapOp(node, variables)
}

class BandCombineMapOp extends RasterMapOp with Externalizable {

  private var rasterRDD:Option[RasterRDD] = None
  private var inputs:Array[Option[RasterMapOp]] = _

  override def rdd():Option[RasterRDD] = rasterRDD

  override def getZoomLevel(): Int = {
    var zoom: Option[Int] = None
    inputs.foreach {
      case Some(rmo) =>
        val mapOpZoom = rmo.getZoomLevel()
        zoom match {
          case Some(z) =>
            if (z != mapOpZoom) {
              throw new IOException("Input zoom levels do not match for " +
                                    this.getClass.getName)
            }
          case None => zoom = Some(mapOpZoom)
        }
      case _ =>
    }

    zoom match {
      case Some(z) => z
      case None => throw new IOException("No raster input specified")
    }
  }

  @SuppressFBWarnings(value = Array("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"),
    justification = "tileIdOrdering() - false positivie")
  override def execute(context:SparkContext):Boolean = {

    implicit val tileIdOrdering = new Ordering[TileIdWritable] {
      override def compare(x:TileIdWritable, y:TileIdWritable):Int = x.compareTo(y)
    }

    val pyramids = Array.ofDim[RasterRDD](inputs.length)
    val nodatabuilder = mutable.ArrayBuilder.make[Double]()

    var i:Int = 0
    var zoom:Int = -1
    var tilesize:Int = -1
    var tiletype:Int = -1
    var totalbands:Int = 0

    var bounds:Bounds = null
    // loop through the inputs and load the pyramid RDDs and metadata
    for (input <- inputs) {

      input match {
        case Some(pyramid) =>
          pyramids(i) = pyramid.rdd() getOrElse
                        (throw new IOException("Can't load RDD! Ouch! " + pyramid.getClass.getName))
          val meta = pyramid.metadata() getOrElse
                     (throw new IOException("Can't load metadata! Ouch! " + pyramid.getClass.getName))

          if (bounds == null) {
            bounds = meta.getBounds
          }
          else {
            bounds = bounds.expand(meta.getBounds)
          }

          // check for the same max zooms
          if (zoom < 0) {
            zoom = meta.getMaxZoomLevel
          }
          else if (zoom != meta.getMaxZoomLevel) {
            throw new IllegalArgumentException("All images must have the same max zoom level. " +
                                               "one is " + meta.getMaxZoomLevel + ", others are " + zoom)
          }

          if (tilesize < 0) {
            tilesize = meta.getTilesize
          }
          else if (tilesize != meta.getTilesize) {
            throw new IllegalArgumentException("All images must have the same tilesize. " +
                                               "one is " + meta.getTilesize + ", others are " + tilesize)
          }

          if (tiletype < 0) {
            tiletype = meta.getTileType
          }
          else if (tiletype != meta.getTileType) {
            throw new IllegalArgumentException("All images must have the same tile type. " +
                                               "one is " + meta.getTileType + ", others are " + tiletype)
          }

          var b:Int = 0
          while (b < meta.getBands) {
            nodatabuilder += meta getDefaultValue b
            b += 1
          }

          totalbands += meta.getBands

          i += 1

        case _ =>
      }
    }

    val nodata = nodatabuilder.result()

    // cogroup needs a partitioner, so we'll give one here...
    var maxpartitions = 0
    val partitions = pyramids.foreach(p => {
      if (p.partitions.length > maxpartitions) {
        maxpartitions = p.partitions.length
      }
    })

    val groups = new CoGroupedRDD(pyramids, new HashPartitioner(maxpartitions))

    rasterRDD = Some(RasterRDD(groups.map(group => {

      val dst = MrGeoRaster.createEmptyRaster(tilesize, tilesize, totalbands, tiletype, nodata)
      var startband = 0
      for (wr <- group._2) {
        if (wr != null && wr.nonEmpty) {
          val raster = RasterWritable.toMrGeoRaster(wr.asInstanceOf[Seq[RasterWritable]].head)

          var y:Int = 0
          while (y < raster.height()) {
            var x:Int = 0
            while (x < raster.width()) {
              var b:Int = 0
              while (b < raster.bands()) {
                val v = raster.getPixelDouble(x, y, b)
                dst.setPixel(x, y, startband + b, v)
                b += 1
              }
              x += 1
            }
            y += 1
          }
          startband += raster.bands()
        }
      }

      //      val t = TMSUtils.tileid(group._1.get(), 11)
      //      GDALUtils.saveRaster(dst, "/data/export/landsat/tile" + t.ty + "-" + t.tx, t.tx, t.ty, 11, 512, 0.0)

      (group._1, RasterWritable.toWritable(dst))
    })))

    metadata(SparkUtils.calculateMetadata(rasterRDD.get, zoom, nodata, bounds = bounds, calcStats = false))

    true
  }

  override def readExternal(in:ObjectInput):Unit = {}

  override def writeExternal(out:ObjectOutput):Unit = {}

  override def setup(job:JobArguments, conf:SparkConf):Boolean = true

  override def teardown(job:JobArguments, conf:SparkConf):Boolean = true

  private[mapalgebra] def this(rasters:Seq[RasterMapOp]) = {
    this()

    inputs = rasters.map(Some(_)).toArray
  }

  private[mapalgebra] def this(node:ParserNode, variables:String => Option[ParserNode]) = {
    this()

    if (node.getNumChildren < 2) {
      throw new ParserException(node.getName + " requires at least two arguments")
    }

    val inputBuilder = Array.newBuilder[Option[RasterMapOp]]
    for (i <- 0 until node.getNumChildren) {
      inputBuilder += RasterMapOp.decodeToRaster(node.getChild(i), variables)
    }

    inputs = inputBuilder.result()
  }

}

