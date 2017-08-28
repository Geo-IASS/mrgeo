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

package org.mrgeo.ingest

import java.io._
import java.util

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.SequenceFile
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{AccumulatorParam, SparkConf, SparkContext}
import org.gdal.gdal.{Dataset, gdal}
import org.gdal.gdalconst.gdalconstConstants
import org.mrgeo.data
import org.mrgeo.data.DataProviderFactory.AccessMode
import org.mrgeo.data.image.MrsImageDataProvider
import org.mrgeo.data.raster.{MrGeoRaster, RasterWritable}
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.data.{DataProviderFactory, ProtectionLevelUtils, ProviderProperties}
import org.mrgeo.hdfs.utils.HadoopFileUtils
import org.mrgeo.image.MrsPyramidMetadata
import org.mrgeo.job.{JobArguments, MrGeoDriver, MrGeoJob}
import org.mrgeo.mapalgebra.MapAlgebra
import org.mrgeo.utils._
import org.mrgeo.utils.tms.{Bounds, TMSUtils}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class NodataArray extends Externalizable with Logging {
  var nodata:Array[Double] = _

  def this(nodataValues:Array[Double]) {
    this()
    nodata = nodataValues
  }

  override def readExternal(in:ObjectInput):Unit = {
    val len = in.readInt()
    nodata = Array.ofDim[Double](len)
    var i:Int = 0
    while (i < len) {
      nodata(i) = in.readDouble()
      i += 1
    }
  }

  override def writeExternal(out:ObjectOutput):Unit = {
    out.writeInt(nodata.length)
    nodata.foreach(out.writeDouble)
  }
}

object NodataAccumulator extends AccumulatorParam[NodataArray] with Logging {
  override def addInPlace(r1:NodataArray,
                          r2:NodataArray):NodataArray = {
    if (r1 == null) {
      new NodataArray(r2.nodata)
    }
    else if (r2 == null) {
      new NodataArray(r1.nodata)
    }
    else if (r1.nodata.length != r2.nodata.length) {
      logInfo("Nodata lengths differ nd 1 len: " + r1.nodata.length + " nd 2 len: " + r2.nodata.length)

      if (r1.nodata.length > r2.nodata.length) {
        new NodataArray(r1.nodata)
      }
      else {
        new NodataArray(r2.nodata)
      }
    }
    else {
      if (log.isInfoEnabled) {
        for (i <- r1.nodata.indices) {
          if (FloatUtils.isEqual(r1.nodata(i), r2.nodata(i))) {
            logInfo("Nodata values differ (band " + i + ") nd 1: " + r1.nodata(i) + " nd 2: " + r2.nodata(i))
          }
        }
      }
      new NodataArray(r1.nodata)
    }
    //    val result = if (r1 == null) { new NodataArray(r2.nodata) } else { r1 }
    //    result
  }

  override def zero(initialValue:NodataArray):NodataArray = {
    null
  }
}

object IngestImage extends MrGeoDriver with Externalizable {

  private val Inputs = "inputs"
  private val Output = "output"
  private val Bounds = "bounds"
  private val Zoom = "zoom"
  private val Tilesize = "tilesize"
  private val NoData = "nodata"
  private val Tiletype = "tiletype"
  private val Bands = "bands"
  private val Categorical = "categorical"
  private val SkipCategoryLoad = "skipCategoryLoad"
  private val Tags = "tags"
  private val Protection = "protection"
  private val ProviderProperties = "provider.properties"

  def ingest(inputs:Array[String], output:String,
             categorical:Boolean, skipCategoryLoad:Boolean, conf:Configuration, bounds:Bounds,
             zoomlevel:Int, tilesize:Int, nodata:Array[Double], bands:Int, tiletype:Int,
             tags:java.util.Map[String, String], protectionLevel:String,
             providerProperties:ProviderProperties):Boolean = {

    val name = "IngestImage"

    val args = setupParams(inputs.mkString(","), output, categorical, skipCategoryLoad, bounds,
      zoomlevel, tilesize, nodata, bands, tiletype, tags, protectionLevel, providerProperties)

    run(name, classOf[IngestImage].getName, args.toMap, conf)

    true
  }

  def ingest(context:SparkContext, inputs:Array[String], zoom:Int, skipPreprocessing:Boolean,
             tilesize:Int, categorical:Boolean, skipCategoryLoad:Boolean, nodata:Array[Double],
             protectionLevel:String) = {
    var firstCategories:Map[Int, util.Vector[_]] = null
    var categoriesMatch:Boolean = false

    if (categorical && !skipCategoryLoad) {
      val checkResult = checkAndLoadCategories(context, inputs)
      categoriesMatch = checkResult._1
      firstCategories = checkResult._2
    }

    // force 1 partition per file, this will keep the size of each ingest task as small as possible, so we
    // won't eat up too much memory
    val in = context.parallelize(inputs, inputs.length)
    val nodataAccum = context.accumulator(null.asInstanceOf[NodataArray])(NodataAccumulator)
    val rawtiles = in.flatMap(input => {
      val (tile, actualnodata) = IngestImage.makeTiles(input, zoom, tilesize, categorical, nodata)
      if (tile.nonEmpty) {
        nodataAccum.add(new NodataArray(actualnodata))
      }
      tile
    }).persist(StorageLevel.MEMORY_AND_DISK)


    // Need to materialize rawtiles in order for the accumulator to work
    rawtiles.count()

    val actualNodata = if (nodataAccum.value != null) {
      nodataAccum.value.nodata
    }
    else {
      val nd = Array.ofDim[Double](1)

      nd
    }

    val tiles = RasterRDD(new PairRDDFunctions(rawtiles).reduceByKey((r1, r2) => {
      val src = RasterWritable.toMrGeoRaster(r1)
      val dst = RasterWritable.toMrGeoRaster(r2)

      dst.mosaic(src, actualNodata)

      RasterWritable.toWritable(dst)
    }))


    val meta = SparkUtils.calculateMetadata(tiles, zoom, actualNodata, bounds = null, calcStats = false)
    meta.setClassification(if (categorical) {
      MrsPyramidMetadata.Classification.Categorical
    }
    else {
      MrsPyramidMetadata.Classification.Continuous
    })
    meta.setProtectionLevel(protectionLevel)
    // Store categories in metadata if needed
    if (categorical && !skipCategoryLoad && categoriesMatch) {
      setMetadataCategories(meta, firstCategories)
    }

    rawtiles.unpersist()

    // repartition, because chances are the RDD only has 1 partition (ingest a single file)
    val numExecutors = math.max(context.getConf.getInt("spark.executor.instances", 0),
      math.max(tiles.partitions.length, meta.getTileBounds(zoom).getHeight.toInt))

    val repartitioned = if (numExecutors > 0) {
      logInfo("Repartitioning to " + numExecutors + " partitions")
      tiles.repartition(numExecutors)
    }
    else {
      //      logInfo("No need to repartition")
      tiles
    }

    (RasterRDD(repartitioned), meta)
  }

  def localingest(context:SparkContext, inputs:Array[String], zoom:Int, skipPreprocessing:Boolean,
                  tilesize:Int, categorical:Boolean, skipCategoryLoad:Boolean, nodata:Array[Double],
                  protectionLevel:String) = {
    var firstCategories:Map[Int, util.Vector[_]] = null
    var categoriesMatch:Boolean = false

    if (categorical && !skipCategoryLoad) {
      val checkResult = checkAndLoadCategories(context, inputs)
      categoriesMatch = checkResult._1
      firstCategories = checkResult._2
    }

    val tmpname = HadoopFileUtils.createUniqueTmpPath()
    val writer = SequenceFile.createWriter(context.hadoopConfiguration,
      SequenceFile.Writer.file(tmpname),
      SequenceFile.Writer.keyClass(classOf[TileIdWritable]),
      SequenceFile.Writer.valueClass(classOf[RasterWritable]),
      SequenceFile.Writer.compression(SequenceFile.CompressionType.BLOCK)
    )

    val nodataAccum = context.accumulator(null.asInstanceOf[NodataArray])(NodataAccumulator)
    inputs.foreach(input => {
      val (tile, actualnodata) = IngestImage.makeTiles(input, zoom, tilesize, categorical, nodata)

      if (tile.nonEmpty) {
        nodataAccum.add(new NodataArray(actualnodata))

        var cnt = 0
        tile.foreach(kv => {
          writer.append(new TileIdWritable(kv._1.get()), kv._2)

          cnt += 1
          if (cnt % 1000 == 0) {
            writer.hflush()
          }
        })
      }
    })

    writer.close()
    val actualNodata = if (nodataAccum.value != null) {
      nodataAccum.value.nodata
    }
    else {
      null
    }

    val input = inputs(0) // there is only 1 input here...

    val format = new SequenceFileInputFormat[TileIdWritable, RasterWritable]

    val job:Job = Job.getInstance(HadoopUtils.createConfiguration())


    val seqtiles = context.sequenceFile(tmpname.toString, classOf[TileIdWritable], classOf[RasterWritable])

    // yuck!  The sequence file reuses the key/value objects, so we need to clone them here
    val rawtiles = seqtiles.map(tile => {
      (new TileIdWritable(tile._1.get()), tile._2.copy)
    })

    val mergedTiles = RasterRDD(new PairRDDFunctions(rawtiles).reduceByKey((r1, r2) => {
      val src = RasterWritable.toMrGeoRaster(r1)
      val dst = RasterWritable.toMrGeoRaster(r2)

      dst.mosaic(src, actualNodata)

      RasterWritable.toWritable(dst)
    }))

    val meta = SparkUtils.calculateMetadata(mergedTiles, zoom, nodata, bounds = null, calcStats = false)
    meta.setClassification(if (categorical) {
      MrsPyramidMetadata.Classification.Categorical
    }
    else {
      MrsPyramidMetadata.Classification.Continuous
    })
    meta.setProtectionLevel(protectionLevel)
    if (categorical && !skipCategoryLoad && categoriesMatch) {
      setMetadataCategories(meta, firstCategories)
    }

    (mergedTiles, meta)
  }

  def localIngest(inputs:Array[String], output:String,
                  categorical:Boolean, skipCategoryLoad:Boolean, config:Configuration, bounds:Bounds,
                  zoomlevel:Int, tilesize:Int, nodata:Array[Double], bands:Int, tiletype:Int,
                  tags:java.util.Map[String, String], protectionLevel:String,
                  providerProperties:ProviderProperties):Boolean = {

    var conf:Configuration = config
    if (conf == null) {
      conf = HadoopUtils.createConfiguration
    }

    val args = setupParams(inputs.mkString(","), output, categorical, skipCategoryLoad, bounds, zoomlevel, tilesize,
      nodata, bands, tiletype,
      tags, protectionLevel,
      providerProperties)

    val name = "IngestImageLocal"
    run(name, classOf[IngestLocal].getName, args.toMap, conf)
    true
  }

  override def readExternal(in:ObjectInput) {}

  override def writeExternal(out:ObjectOutput) {}

  override def setup(job:JobArguments):Boolean = {
    job.isMemoryIntensive = true

    true
  }

  private def equalCategories(c1:Map[Int, util.Vector[_]],
                              c2:Map[Int, util.Vector[_]]):Boolean = {
    if (c1.size != c2.size) {
      return false
    }
    c1.foreach(c1Entry => {
      val c1Band = c1Entry._1
      val c1Cats = c1Entry._2
      val c2Value = c2.get(c1Band)
      c2Value match {
        case Some(c2Cats) =>
          if (c1Cats.size() != c2Cats.size()) {
            return false
          }
          // The values stored in c1Cats and c2Cats are strings (from GDAL). Do a case
          // insensitive comparison of them and return false if any don't match.
          c1Cats.zipWithIndex.foreach(c1Entry => {
            if (!c1Entry._1.toString.equalsIgnoreCase(c2Cats.get(c1Entry._2).toString)) {
              return false
            }
          })
        case None =>
          return false
      }
    })
    true
  }

  private def loadCategories(input:String):Map[Int, util.Vector[_]] = {
    var src:Dataset = null
    try {
      src = GDALUtils.open(input)

      var result:scala.collection.mutable.Map[Int, util.Vector[_]] = scala.collection.mutable.Map()
      if (src != null) {
        val bands = src.GetRasterCount()
        var b:Integer = 1
        while (b <= bands) {
          val band = src.GetRasterBand(b)
          val categoryNames = band.GetCategoryNames()
          val c:Int = 0
          result += ((b - 1) -> categoryNames)
          b += 1
        }
      }
      result.toMap
    }
    finally {
      if (src != null) {
        GDALUtils.close(src)
      }
    }
  }

  private def checkAndLoadCategories(context:SparkContext, inputs:Array[String]) = {
    var firstCategories:Map[Int, util.Vector[_]] = null
    var categoryMatchResult:(String, String) = null
    val inputsHead :: inputsTail = inputs.toList
    val firstInput = inputs(0)
    firstCategories = IngestImage.loadCategories(firstInput)
    // Initialize the value being aggregated with the name of the first input from
    // which the baseline categories were read. The second element will be assigned
    // during aggregation to whichever other input does not match those baseline
    // categories (or will be left blank if all the inputs' categories match.
    val zero = (firstInput, "")
    val inTail = context.parallelize(inputsTail, inputs.length)
    categoryMatchResult = inTail.aggregate(zero)((combinedValue, input) => {
      if (combinedValue._2.isEmpty) {
        // Compare the categories of the first input with those of the current
        // input being aggregated. If they are different, then return this input
        // as the one that differs
        val cats = IngestImage.loadCategories(input)
        if (!equalCategories(firstCategories, cats)) {
          (combinedValue._1, input)
        }
        else {
          combinedValue
        }
      }
      else {
        // Return the inputs with mismatched categories that we already found
        combinedValue
      }
    }, {
      // When merging just return a value that specifies a difference (i.e.
      // the second element of the tuple is not empty.
      (result1, result2) => {
        if (result1._2.length > 0) {
          result1
        }
        else if (result2._2.length > 0) {
          result2
        }
        else {
          result1
        }
      }
    })
    if (!categoryMatchResult._2.isEmpty) {
      throw new Exception(
        "Categories from input " + categoryMatchResult._1 + " are not the same as categories from input " +
        categoryMatchResult._2)
    }
    (categoryMatchResult._2.isEmpty, firstCategories)
  }

  private def setMetadataCategories(meta:MrsPyramidMetadata,
                                    categoryMap:Map[Int, util.Vector[_]]):Unit = {
    categoryMap.foreach(catEntry => {
      val categories = new Array[String](catEntry._2.size())
      catEntry._2.zipWithIndex.foreach(cat => {
        categories(cat._2) = cat._1.toString
      })
      meta.setCategories(catEntry._1, categories)
    })
  }

  private def setupParams(input:String, output:String, categorical:Boolean, skipCategoryLoad:Boolean,
                          bounds:Bounds, zoomlevel:Int, tilesize:Int, nodata:Array[Double],
                          bands:Int, tiletype:Int, tags:util.Map[String, String],
                          protectionLevel:String,
                          providerProperties:ProviderProperties):mutable.Map[String, String] = {

    val args = mutable.Map[String, String]()

    args += Inputs -> input
    args += Output -> output
    if (bounds != null) {
      args += Bounds -> bounds.toCommaString
    }
    args += Zoom -> zoomlevel.toString
    args += Tilesize -> tilesize.toString
    if (nodata != null) {
      args += NoData -> nodata.mkString(" ")
    }
    args += Bands -> bands.toString
    args += Tiletype -> tiletype.toString
    args += Categorical -> categorical.toString
    args += SkipCategoryLoad -> skipCategoryLoad.toString

    var t:String = ""
    tags.foreach(kv => {
      if (t.length > 0) {
        t += ","
      }
      t += kv._1 + "=" + kv._2
    })

    args += Tags -> t
    val dp:MrsImageDataProvider = DataProviderFactory.getMrsImageDataProvider(output,
      AccessMode.OVERWRITE, providerProperties)
    args += Protection -> ProtectionLevelUtils.getAndValidateProtectionLevel(dp, protectionLevel)

    //var p: String = ""
    if (providerProperties != null) {
      args += ProviderProperties -> data.ProviderProperties.toDelimitedString(providerProperties)
    }
    else {
      args += ProviderProperties -> ""
    }

    args
  }

  private def makeTiles(image:String, zoom:Int, tilesize:Int, categorical:Boolean,
                        nodata:Array[Double]):(TraversableOnce[(TileIdWritable, RasterWritable)], Array[Double]) = {

    val result = ListBuffer[(TileIdWritable, RasterWritable)]()
    var actualNoData:Array[Double] = null

    //val start = System.currentTimeMillis()

    // open the image
    try {
      var src = GDALUtils.open(image)

      if (src != null) {
        val datatype = src.GetRasterBand(1).getDataType
        val datasize = gdal.GetDataTypeSize(datatype) / 8

        val bands = src.GetRasterCount()
        actualNoData = Array.ofDim[Double](bands)
        // The number of nodata values has to match the number of bands in the source image or
        // there can be only one nodata value in which case it will be used for all the bands
        if (nodata.length == 1) {
          for (i <- 0 until bands) {
            actualNoData(i) = nodata(0)
          }
        }
        else if (nodata.length < bands) {
          throw new Exception(f"There are too few nodata values (${
            nodata.length
          }) compared to the number of bands in $image%s ($bands%d)")
        }
        else {
          log.warn(f"There are more nodata values (${nodata.length}) than bands ($bands) in $image%s")
          for (i <- 0 until bands) {
            actualNoData(i) = nodata(i)
          }
        }

        // force the nodata values...
        for (i <- 1 to bands) {
          val band = src.GetRasterBand(i)
          band.SetNoDataValue(actualNoData(i - 1))
        }

        val imageBounds = GDALUtils.getBounds(src)
        val tiles = TMSUtils.boundsToTile(imageBounds, zoom, tilesize)
        val tileBounds = TMSUtils.tileBounds(imageBounds, zoom, tilesize)

        val w = tiles.width() * tilesize
        val h = tiles.height() * tilesize

        val res = TMSUtils.resolution(zoom, tilesize)

        //val scaledsize = w * h * bands * datasize

        if (log.isDebugEnabled) {
          logDebug("Image info:  " + image)
          logDebug("  bands:  " + bands)
          logDebug("  data type:  " + datatype)
          logDebug("  width:  " + src.getRasterXSize)
          logDebug("  height:  " + src.getRasterYSize)
          logDebug("  bounds:  " + imageBounds)
          logDebug("  tiles:  " + tiles)
          logDebug("  tile width:  " + w)
          logDebug("  tile height:  " + h)
        }

        val scaled = GDALUtils.createEmptyDiskBasedRaster(src, w.toInt, h.toInt)

        if (scaled == null) {
          throw new java.lang.OutOfMemoryError(
            s"Not enough system memory available to create a memory-based image of size $w x $h with $bands bands for reprojecting $image to WGS84 at zoom $zoom")
        }

        try {
          val xform = Array.ofDim[Double](6)

          xform(0) = tileBounds.w /* top left x */
          xform(1) = res /* w-e pixel resolution */
          xform(2) = 0 /* 0 */
          xform(3) = tileBounds.n /* top left y */
          xform(4) = 0 /* 0 */
          xform(5) = -res /* n-s pixel resolution (negative value) */

          scaled.SetGeoTransform(xform)
          scaled.SetProjection(GDALUtils.EPSG4326)


          val resample =
            if (categorical) {
              // use gdalconstConstants.GRA_Mode for categorical, which may not exist in earlier versions of gdal,
              // in which case we will use GRA_NearestNeighbour
              try {
                val mode = classOf[gdalconstConstants].getDeclaredField("GRA_Mode")
                mode.getInt()
              }
              catch {
                case _:RuntimeException | _:Exception => gdalconstConstants.GRA_NearestNeighbour
              }
            }
            else {
              gdalconstConstants.GRA_Bilinear
            }

          try {
            gdal.ReprojectImage(src, scaled, src.GetProjection(), GDALUtils.EPSG4326, resample)
          }
          finally {
            // close the image
            GDALUtils.close(src)
            src = null
          }

          //    val time = System.currentTimeMillis() - start
          //    println("scale: " + time)

          //    val band = scaled.GetRasterBand(1)
          //    val minmax = Array.ofDim[Double](2)
          //    band.ComputeRasterMinMax(minmax, 0)

          //GDALUtils.saveRaster(scaled, "/data/export/scaled.tif")

          var dty:Int = 0
          while (dty < tiles.height.toInt) {
            var dtx:Int = 0
            while (dtx < tiles.width.toInt) {

              val tx:Long = dtx + tiles.w
              val ty:Long = tiles.n - dty

              val x:Int = dtx * tilesize
              val y:Int = dty * tilesize

              //val start = System.currentTimeMillis()
              val raster = MrGeoRaster.fromDataset(scaled, x, y, tilesize, tilesize)

              val writable = RasterWritable.toWritable(raster)

              // save the tile...
              //        GDALUtils.saveRaster(RasterWritable.toRaster(writable),
              //          "/data/export/tiles/tile-" + ty + "-" + tx, tx, ty, zoom, tilesize, GDALUtils.getnodata(scaled))

              result.append((new TileIdWritable(TMSUtils.tileid(tx, ty, zoom)), writable))


              //val time = System.currentTimeMillis() - start
              //println(tx + ", " + ty + ", " + time)
              dtx += 1
            }
            dty += 1
          }
        }
        finally {
          GDALUtils.delete(scaled)
        }
      }
      else {
        //if (log.isDebugEnabled) {
        logError("Could not open " + image)
        //}
      }
    }
    catch {
      case ioe:IOException =>
        ioe.printStackTrace() // no op, this can happen in "skip preprocessing" mode
    }

    if (log.isDebugEnabled) {
      logDebug("Ingested " + result.length + " tiles from " + image)
    }
    (result.iterator, actualNoData)
  }
}

class IngestImage extends MrGeoJob with Externalizable {
  private[ingest] var inputs:Array[String] = _
  private[ingest] var output:String = _
  private[ingest] var bounds:Bounds = _
  private[ingest] var zoom:Int = -1
  private[ingest] var bands:Int = -1
  private[ingest] var tiletype:Int = -1
  private[ingest] var tilesize:Int = -1
  private[ingest] var nodata:Array[Double] = _
  private[ingest] var categorical:Boolean = false
  private[ingest] var skipCategoryLoad:Boolean = false
  private[ingest] var skipPreprocessing:Boolean = false
  private[ingest] var providerproperties:ProviderProperties = _
  private[ingest] var protectionlevel:String = _


  override def registerClasses():Array[Class[_]] = {
    val classes = Array.newBuilder[Class[_]]

    classes += classOf[TileIdWritable]
    classes += classOf[RasterWritable]

    classes += classOf[Array[String]]

    classes.result()
  }

  override def setup(job:JobArguments, conf:SparkConf):Boolean = {

    job.isMemoryIntensive = true

    inputs = job.getSetting(IngestImage.Inputs).split(",")
    // This setting can use lots of memory, so we we'll set it to null here to clean up memory.
    // WARNING!  This definately can have side-effects
    job.setSetting(IngestImage.Inputs, null)
    output = job.getSetting(IngestImage.Output)

    val boundstr = job.getSetting(IngestImage.Bounds, null)
    if (boundstr != null) {
      bounds = Bounds.fromCommaString(boundstr)
    }

    zoom = job.getSetting(IngestImage.Zoom).toInt
    bands = job.getSetting(IngestImage.Bands).toInt
    tiletype = job.getSetting(IngestImage.Tiletype).toInt
    tilesize = job.getSetting(IngestImage.Tilesize).toInt
    if (job.hasSetting(IngestImage.NoData)) {
      nodata = job.getSetting(IngestImage.NoData).split(" ").map(_.toDouble)
    }
    else {
      nodata = Array.fill[Double](bands)(Double.NaN)
    }
    categorical = job.getSetting(IngestImage.Categorical).toBoolean
    skipCategoryLoad = job.getSetting(IngestImage.SkipCategoryLoad).toBoolean

    protectionlevel = job.getSetting(IngestImage.Protection)
    if (protectionlevel == null) {
      protectionlevel = ""
    }

    providerproperties = ProviderProperties.fromDelimitedString(job.getSetting(IngestImage.ProviderProperties))

    true
  }


  override def execute(context:SparkContext):Boolean = {

    val ingested = IngestImage.ingest(context, inputs, zoom, skipPreprocessing, tilesize,
      categorical, skipCategoryLoad, nodata, protectionlevel)

    val dp = DataProviderFactory.getMrsImageDataProvider(output, AccessMode.OVERWRITE, providerproperties)
    SparkUtils.saveMrsPyramid(ingested._1, dp, ingested._2, zoom, context.hadoopConfiguration, providerproperties)

    true
  }


  override def teardown(job:JobArguments, conf:SparkConf):Boolean = {
    true
  }

  override def readExternal(in:ObjectInput) {
  }

  override def writeExternal(out:ObjectOutput) {
  }
}
