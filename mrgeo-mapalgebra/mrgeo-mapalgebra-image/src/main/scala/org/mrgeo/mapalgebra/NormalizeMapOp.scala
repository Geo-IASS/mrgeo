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

import org.apache.spark.{SparkConf, SparkContext}
import org.mrgeo.data.raster.RasterWritable
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.job.JobArguments
import org.mrgeo.mapalgebra.parser.{ParserException, ParserNode}
import org.mrgeo.mapalgebra.raster.RasterMapOp
import org.mrgeo.utils.SparkUtils

object NormalizeMapOp extends MapOpRegistrar {

  override def register:Array[String] = {
    Array[String]("normalize")
  }

  def create(raster:RasterMapOp):MapOp =
    new NormalizeMapOp(Some(raster), None, None)

  def create(raster:RasterMapOp, min:Double, max:Double):MapOp =
    new NormalizeMapOp(Some(raster), Some(min), Some(max))


  override def apply(node:ParserNode, variables:String => Option[ParserNode]):MapOp =
    new NormalizeMapOp(node, variables)
}

class NormalizeMapOp extends RasterMapOp with Externalizable {
  private var rasterRDD:Option[RasterRDD] = None

  private var inputMapOp:Option[RasterMapOp] = None
  private var minVal:Option[Double] = None
  private var maxVal:Option[Double] = None

  override def rdd():Option[RasterRDD] = rasterRDD

  override def getZoomLevel(): Int = {
    inputMapOp.getOrElse(throw new IOException("No raster input specified")).getZoomLevel()
  }

  override def execute(context:SparkContext):Boolean = {

    val input:RasterMapOp = inputMapOp getOrElse (throw new IOException("Input MapOp not valid!"))

    val meta = input.metadata() getOrElse
               (throw new IOException("Can't load metadata! Ouch! " + input.getClass.getName))
    val rdd = input.rdd() getOrElse (throw new IOException("Can't load RDD! Ouch! " + inputMapOp.getClass.getName))

    val zoom = meta.getMaxZoomLevel

    val stats = if (minVal.isEmpty && maxVal.isEmpty) {
      val s = meta.getImageStats(zoom)
      if (s == null) {
        SparkUtils.calculateStats(rdd, meta.getBands, meta.getDefaultValues)
      }
      else {
        s
      }
    }
    else {
      null
    }

    val nodata = meta.getDefaultValue(0)

    val min = minVal match {
      case Some(v) => v
      case _ => stats(0).min
    }

    val max = maxVal match {
      case Some(v) => v
      case _ => stats(0).max
    }

    val range = max - min

    rasterRDD = Some(RasterRDD(rdd.map(tile => {
      val raster = RasterWritable.toMrGeoRaster(tile._2)

      var y:Int = 0
      while (y < raster.height()) {
        var x:Int = 0
        while (x < raster.width()) {
          var b:Int = 0
          while (b < raster.bands()) {
            val v = raster.getPixelDouble(x, y, b)
            if (RasterMapOp.isNotNodata(v, nodata)) {
              raster.setPixel(x, y, b, (v - min) / range)
            }
            b += 1
          }
          x += 1
        }
        y += 1
      }
      (tile._1, RasterWritable.toWritable(raster))
    })))

    metadata(SparkUtils.calculateMetadata(rasterRDD.get, zoom, meta.getDefaultValues,
      bounds = meta.getBounds, calcStats = false))

    true
  }

  override def setup(job:JobArguments, conf:SparkConf):Boolean = true

  override def teardown(job:JobArguments, conf:SparkConf):Boolean = true

  override def readExternal(in:ObjectInput):Unit = {}

  override def writeExternal(out:ObjectOutput):Unit = {}

  private[mapalgebra] def this(raster:Option[RasterMapOp], min:Option[Double], max:Option[Double]) = {
    this()
    inputMapOp = raster
    minVal = min
    maxVal = max
  }

  private[mapalgebra] def this(node:ParserNode, variables:String => Option[ParserNode]) = {
    this()

    if (node.getNumChildren != 1 && node.getNumChildren != 3) {
      throw new ParserException("Usage: normalize(<raster>, [min], [max])")
    }

    inputMapOp = RasterMapOp.decodeToRaster(node.getChild(0), variables)

    if (node.getNumChildren == 3) {
      minVal = MapOp.decodeDouble(node.getChild(1), variables)
      maxVal = MapOp.decodeDouble(node.getChild(2), variables)
    }
  }


}
