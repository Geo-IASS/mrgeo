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

import org.apache.spark.rdd.CoGroupedRDD
import org.apache.spark.{HashPartitioner, SparkConf, SparkContext}
import org.mrgeo.data.raster.{MrGeoRaster, RasterUtils, RasterWritable}
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.job.JobArguments
import org.mrgeo.mapalgebra.parser._
import org.mrgeo.mapalgebra.raster.RasterMapOp
import org.mrgeo.utils.SparkUtils

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.control.Breaks

object ConMapOp extends MapOpRegistrar {
  override def register:Array[String] = {
    Array[String]("con")
  }

  def create(test:RasterMapOp, positiveRaster:RasterMapOp, negativeRaster:RasterMapOp):MapOp =
    new ConMapOp(test, positiveRaster, negativeRaster)

  def create(test:RasterMapOp, positiveConst:Double, negativeRaster:RasterMapOp):MapOp =
    new ConMapOp(test, positiveConst, negativeRaster)

  def create(test:RasterMapOp, positiveRaster:RasterMapOp, negativeConst:Double):MapOp =
    new ConMapOp(test, positiveRaster, negativeConst)

  def create(test:RasterMapOp, positiveConst:Double, negativeConst:Double):MapOp =
    new ConMapOp(test, positiveConst, negativeConst)


  override def apply(node:ParserNode, variables:String => Option[ParserNode]):MapOp =
    new ConMapOp(node, variables)
}

class ConMapOp extends RasterMapOp with Externalizable {

  private val rddMap = mutable.Map.empty[Int, Int]
  // maps input order (key) to cogrouped position (value)
  private val constMap = mutable.Map.empty[Int, Option[Double]]
  // maps input order (key) to constant value
  private var rasterRDD:Option[RasterRDD] = None
  private var inputs:Array[RasterMapOp] = null
  // list of raster inputs
  private var isRdd:Array[Boolean] = null
  // ia the input (order) an RDD (true) or const (false)
  private var nodatas:Array[Double] = null // nodata value for the input data

  override def rdd():Option[RasterRDD] = rasterRDD

  override def getZoomLevel(): Int = {
    var zoom: Option[Int] = None
    inputs.foreach(rmo=> {
      val mapOpZoom = rmo.getZoomLevel()
      zoom match {
        case Some(z) => {
          if (z != mapOpZoom) {
            throw new IOException("Input zoom levels do not match for " +
              this.getClass.getName)
          }
        }
        case None => zoom = Some(mapOpZoom)
      }
    })
    zoom match {
      case Some(z) => z
      case None => throw new IOException("No raster input specified")
    }
  }

  override def execute(context:SparkContext):Boolean = {

    val t = calculateLargestType()
    val datatype:Int = t._2
    val dataindex:Int = t._1

    // our metadata is the same as the index raster?
    val input = inputs(rddMap(dataindex))
    val meta = input.metadata() getOrElse
               (throw new IOException("Can't load metadata! Ouch! " + input.getClass.getName))

    // gather all the RDDs and cogroup them
    val rddBuilder = mutable.ArrayBuilder.make[RasterRDD]
    var maxpartitions = 0
    inputs.foreach(mapop => {
      rddBuilder += (mapop.rdd() match {
        case Some(r) =>
          if (r.partitions.length > maxpartitions) {
            maxpartitions = r.partitions.length
          }
          r
        case _ => throw new IOException("Can't load RDD! Ouch! " + mapop.getClass.getName)
      })
    })

    // cogroup needs a partitioner, so we'll give one here...
    val groups = new CoGroupedRDD(rddBuilder.result(), new HashPartitioner(maxpartitions))

    // copy these here to avoid serializing the whole mapop
    nodatas = Array.fill[Double](isRdd.length)(Double.NaN)
    rddMap.foreach(e => {
      val m = inputs(e._2).metadata() getOrElse (throw new IOException())
      nodatas(e._1) = m.getDefaultValue(0)
    }
    )
    val nodata = RasterUtils.getDefaultNoDataForType(meta.getTileType)
    val tilesize = meta.getTilesize
    val bands = meta.getBands

    rasterRDD = Some(RasterRDD(groups.flatMap(tile => {

      val termCount = isRdd.length

      val rawInputs = tile._2
      val rasterInputs = Array.fill[Option[MrGeoRaster]](rawInputs.length)(None)

      val raster = MrGeoRaster.createEmptyRaster(tilesize, tilesize, bands, datatype, nodata)

      def getValue(i:Int, x:Int, y:Int, b:Int):Option[Double] = {
        if (isRdd(i)) {
          val mapped = rddMap(i)

          val ri = rasterInputs(mapped) orElse {
            val a = rawInputs(mapped)
            val r = if (a.nonEmpty) {
              a.head match {
                case rw:RasterWritable =>
                  Some(RasterWritable.toMrGeoRaster(rw))
                case _ =>
                  None
              }
            }
            else {
              None
            }
            rasterInputs(mapped) = r
            r
          }

          ri match {
            case Some(r:MrGeoRaster) =>
              Some(r.getPixelDouble(x, y, b))
            case _ =>
              None
          }
        }
        else {
          constMap(i) match {
            case Some(nan) if nan.isNaN => Some(nodata)
            case notnan => notnan
          }
        }
      }

      val done = new Breaks

      var hasdata = false

      //NOTE:  the raster is already filled with nodata, no need to do do any setting of resultant pixels with nodata
      var y:Int = 0
      while (y < raster.height()) {
        var x:Int = 0
        while (x < raster.width()) {
          var b:Int = 0
          while (b < raster.bands()) {
            done.breakable {
              var i:Int = 0
              while (i < termCount - 1) {
                // get the conditional value, either from the rdd or constant
                val v = getValue(i, x, y, b) match {
                  case Some(d) => d
                  case _ => done.break()
                }

                // check for nodata
                if (RasterMapOp.isNodata(v, nodatas(i))) {
                  done.break()
                }
                // greater than 0, so take the true case
                else if (!RasterMapOp.nearZero(v)) {
                  getValue(i + 1, x, y, b) match {
                    case Some(d) =>
                      if (RasterMapOp.isNotNodata(d, nodatas(i + 1))) {
                        raster.setPixel(x, y, b, d)
                        hasdata = true
                      }
                    case _ =>
                  }
                  done.break()
                }
                i += 2
              }

              // didn't find one in the loop, take the last entry (the else)
              getValue(termCount - 1, x, y, b) match {
                case Some(d) => {
                  if (RasterMapOp.isNotNodata(d, nodatas(termCount - 1))) {
                    raster.setPixel(x, y, b, d)
                    hasdata = true
                  }
                }
                case _ =>
              }
            }
            b += 1
          }
          x += 1
        }
        y += 1
      }

      if (hasdata) {
        Array((tile._1, RasterWritable.toWritable(raster))).iterator
      }
      else {
        Array.empty[(TileIdWritable, RasterWritable)].iterator
      }
    })))

    val outputNodatas = new Array[Double](meta.getBands)
    for (i <- outputNodatas.indices) {
      outputNodatas(i) = nodata
    }
    metadata(SparkUtils.calculateMetadata(rasterRDD.get, meta.getMaxZoomLevel, outputNodatas,
      bounds = meta.getBounds, calcStats = false))

    true
  }

  override def setup(job:JobArguments, conf:SparkConf):Boolean = true

  override def teardown(job:JobArguments, conf:SparkConf):Boolean = true

  override def readExternal(in:ObjectInput):Unit = {
    rddMap.clear()
    var cnt = in.readInt()
    var i:Int = 0
    while (i < cnt) {
      rddMap.put(in.readInt(), in.readInt())
      i += 1
    }

    constMap.clear()
    cnt = in.readInt()
    i = 0
    while (i < cnt) {
      val hasValue = in.readBoolean()
      if (hasValue) {
        constMap.put(in.readInt(), Some(in.readDouble()))
      }
      else {
        constMap.put(in.readInt(), None)
      }
      i += 1
    }

    cnt = in.readInt()
    nodatas = Array.ofDim[Double](cnt)
    i = 0
    while (i < cnt) {
      nodatas(i) = in.readDouble()
      i += 1
    }

    cnt = in.readInt()
    isRdd = Array.ofDim[Boolean](cnt)
    i = 0
    while (i < cnt) {
      isRdd(i) = in.readBoolean()
      i += 1
    }

  }

  override def writeExternal(out:ObjectOutput):Unit = {
    // rddmap
    out.writeInt(rddMap.size)
    rddMap.foreach(e => {
      out.writeInt(e._1)
      out.writeInt(e._2)
    })
    // constmap
    out.writeInt(constMap.size)
    constMap.foreach(e => {
      val hasValue = e._2.nonEmpty
      out.writeBoolean(hasValue)
      out.writeInt(e._1)
      if (hasValue) {
        out.writeDouble(e._2.get)
      }
    })

    // nodatas
    out.writeInt(nodatas.length)
    nodatas.foreach(out.writeDouble)

    // isrdd
    out.writeInt(isRdd.length)
    isRdd.foreach(out.writeBoolean)
  }

  private[mapalgebra] def this(test:RasterMapOp, positive:RasterMapOp, negative:RasterMapOp) = {
    this()

    inputs = Array.ofDim[RasterMapOp](3)
    inputs(0) = test
    inputs(1) = positive
    inputs(2) = negative

    rddMap.put(0, 0)
    rddMap.put(1, 1)
    rddMap.put(2, 2)

    //constMap.put(0, 0)

    isRdd = Array.ofDim[Boolean](3)
    isRdd(0) = true
    isRdd(1) = true
    isRdd(2) = true
  }

  private[mapalgebra] def this(test:RasterMapOp, positive:Double, negative:RasterMapOp) = {
    this()

    inputs = Array.ofDim[RasterMapOp](2)
    inputs(0) = test
    inputs(1) = negative

    rddMap.put(0, 0)
    rddMap.put(2, 1)

    constMap.put(1, Some(positive))

    isRdd = Array.ofDim[Boolean](3)
    isRdd(0) = true
    isRdd(1) = false
    isRdd(2) = true
  }

  private[mapalgebra] def this(test:RasterMapOp, positive:RasterMapOp, negative:Double) = {
    this()

    inputs = Array.ofDim[RasterMapOp](2)
    inputs(0) = test
    inputs(1) = positive

    rddMap.put(0, 0)
    rddMap.put(1, 1)

    constMap.put(2, Some(negative))

    isRdd = Array.ofDim[Boolean](3)
    isRdd(0) = true
    isRdd(1) = true
    isRdd(2) = false
  }

  private[mapalgebra] def this(test:RasterMapOp, positive:Double, negative:Double) = {
    this()

    inputs = Array.ofDim[RasterMapOp](2)
    inputs(0) = test

    rddMap.put(0, 0)

    constMap.put(1, Some(positive))
    constMap.put(2, Some(negative))

    isRdd = Array.ofDim[Boolean](3)
    isRdd(0) = true
    isRdd(1) = false
    isRdd(2) = false
  }

  private[mapalgebra] def this(node:ParserNode, variables:String => Option[ParserNode]) = {
    this()

    if (node.getNumChildren < 3) {
      throw new ParserException(node.getName + " requires at least three arguments")
    }

    if (node.getNumChildren % 2 == 0) {
      throw new ParserException(node.getName + " requires an odd number of arguments")
    }

    val inputBuilder = mutable.ArrayBuilder.make[RasterMapOp]
    val isBuilder = mutable.ArrayBuilder.make[Boolean]

    val children = node.getChildren
    var inputCnt = 0
    for (i <- 0 until children.size()) {
      val child = children(i)
      val a = decodeChild(child, variables)
      decodeChild(child, variables) match {
        case mapop:RasterMapOp =>
          inputBuilder += mapop
          isBuilder += true
          rddMap.put(i, inputCnt)
          inputCnt += 1
        case const:java.lang.Double =>
          isBuilder += false
          constMap.put(i, if (const.doubleValue().isNaN) {
            None
          }
          else {
            Some(const.doubleValue())
          })
        case _ =>
      }
    }

    inputs = inputBuilder.result()
    isRdd = isBuilder.result()

  }

  private def decodeChild(child:ParserNode, variables:String => Option[ParserNode]) = {
    child match {
      case const:ParserConstantNode => MapOp.decodeDouble(const) match {
        case Some(d) => d
        case _ => throw new ParserException("Term \"" + child + "\" can not be decoded as a double")
      }
      case func:ParserFunctionNode => func.getMapOp match {
        case raster:RasterMapOp => raster
        case _ => throw new ParserException("Term \"" + child + "\" is not a raster input")
      }
      case variable:ParserVariableNode =>
        MapOp.decodeVariable(variable, variables).get match {
          case const:ParserConstantNode => MapOp.decodeDouble(const) match {
            case Some(d) => d
            case _ => throw new ParserException("Term \"" + child + "\" can not be decoded as a double")
          }
          case func:ParserFunctionNode => func.getMapOp match {
            case raster:RasterMapOp => raster
            case _ => throw new ParserException("Term \"" + child + "\" is not a raster input")
          }
        }
    }

  }

  private def calculateLargestType() = {
    var d = calculateType()

    // this means all the outputs are constant, so we'll need to check the input conditionals
    if (d._1 < 0) {
      d = calculateType(false)
      if (d._1 < 0) {
        throw new IOException("Error!  There are no raster datasets in the con(...)")
      }

      // since all the outputs are constant, we need to use float outputs
      (d._1, DataBuffer.TYPE_FLOAT)
    }
    else {
      d
    }

  }

  private def calculateType(useOutputs:Boolean = true) = {
    // even inputs, except the last one, are conditionals, odd, and the last one, are outputs..
    var index = -1
    var datatype = -1

    var i:Int = if (useOutputs) {
      1
    }
    else {
      0
    }
    while (i < isRdd.length) {
      if (isRdd(i)) {
        // look up the rdd in the input->rdd map
        val input = inputs(rddMap(i))
        var replaceLargest:Boolean = false
        val dt:Int = input.metadata()
            .getOrElse(throw new IOException("Can't load metadata! Ouch! " + inputs(i).getClass.getName)).getTileType

        // When choosing the largest data type, unfortunately we can't just use a numeric
        // comparison of the dataType values because the value of USHORT is less than SHORT.
        // And it's probably better
        if (datatype >= 0) {
          if (dt != datatype) {
            if (dt == DataBuffer.TYPE_UNDEFINED || datatype == DataBuffer.TYPE_UNDEFINED) {
              if (DataBuffer.getDataTypeSize(dt) > DataBuffer.getDataTypeSize(datatype)) {
                replaceLargest = true
              }
            }
            else {
              datatype match {
                case DataBuffer.TYPE_BYTE =>
                  replaceLargest = true
                case DataBuffer.TYPE_SHORT =>
                  if (dt == DataBuffer.TYPE_USHORT || dt == DataBuffer.TYPE_INT ||
                      dt == DataBuffer.TYPE_FLOAT || dt == DataBuffer.TYPE_DOUBLE) {
                    replaceLargest = true
                  }
                case DataBuffer.TYPE_USHORT =>
                  if (dt == DataBuffer.TYPE_INT || dt == DataBuffer.TYPE_FLOAT ||
                      dt == DataBuffer.TYPE_DOUBLE) {
                    replaceLargest = true
                  }
                case DataBuffer.TYPE_INT =>
                  if (dt == DataBuffer.TYPE_FLOAT || dt == DataBuffer.TYPE_DOUBLE) {
                    replaceLargest = true
                  }
                case DataBuffer.TYPE_FLOAT =>
                  if (dt == DataBuffer.TYPE_DOUBLE) {
                    replaceLargest = true
                  }
              }
            }
          }
        }
        else {
          replaceLargest = true
        }

        if (replaceLargest) {
          index = i
          datatype = dt
        }
      }
      // Make sure we process the last element, which is always the "else" element
      i += (if (i == isRdd.length - 2) {
        1
      }
      else {
        2
      })
    }

    (index, datatype)
  }
}
