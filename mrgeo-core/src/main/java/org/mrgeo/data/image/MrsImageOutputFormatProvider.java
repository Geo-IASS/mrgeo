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

package org.mrgeo.data.image;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.data.DataProviderException;
import org.mrgeo.data.ProtectionLevelValidator;
import org.mrgeo.data.raster.RasterWritable;
import org.mrgeo.data.rdd.RasterRDD;
import org.mrgeo.data.tile.TileIdWritable;

import java.io.IOException;

/**
 * Data plugins that wish to provide storage for image pyramids must
 * include a sub-class of this class.
 */
public abstract class MrsImageOutputFormatProvider implements ProtectionLevelValidator
{
protected ImageOutputFormatContext context;

public MrsImageOutputFormatProvider(ImageOutputFormatContext context)
{
  this.context = context;
}

public abstract void save(RasterRDD raster, Configuration conf);

public abstract void finalizeExternalSave(Configuration conf) throws DataProviderException;

/**
 * For any additional Spark configuration besides setting
 * the actual output format class (see getOutputFormatClass method in
 * this interface), place that initialization code in this method.
 */
protected Configuration setupOutput(Configuration conf) throws DataProviderException
{
  try
  {
    Job job = new Job(conf);

    job.setOutputKeyClass(TileIdWritable.class);
    job.setOutputValueClass(RasterWritable.class);
    job.setOutputFormatClass(getOutputFormat().getClass());
    if (context.getProtectionLevel() != null)
    {
      job.getConfiguration().set(MrGeoConstants.MRGEO_PROTECTION_LEVEL, context.getProtectionLevel());
    }
    job.getConfiguration().setBoolean("mapreduce.fileoutputcommitter.marksuccessfuljobs", false);

    return job.getConfiguration();
  }
  catch (IOException e)
  {
    throw new DataProviderException("Error configuring a spark job ", e);
  }
}

protected abstract OutputFormat<WritableComparable<?>, Writable> getOutputFormat();
}


