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

package org.mrgeo.hdfs.input.image;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.data.DataProviderException;
import org.mrgeo.data.image.ImageInputFormatContext;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.mrgeo.data.image.MrsImageInputFormatProvider;
import org.mrgeo.data.raster.RasterWritable;
import org.mrgeo.data.tile.TileIdWritable;
import org.mrgeo.hdfs.image.HdfsMrsImageDataProvider;
import org.mrgeo.image.MrsPyramid;
import org.mrgeo.image.MrsPyramidMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class HdfsMrsPyramidInputFormatProvider extends MrsImageInputFormatProvider
{
private static final Logger log = LoggerFactory.getLogger(HdfsMrsPyramidInputFormatProvider.class);

public HdfsMrsPyramidInputFormatProvider(ImageInputFormatContext context)
{
  super(context);
}

@Override
public InputFormat<TileIdWritable, RasterWritable> getInputFormat(final String input)
{
  return new HdfsMrsPyramidInputFormat(input);
}

@Override
public Configuration setupSparkJob(final Configuration conf, MrsImageDataProvider provider)
    throws DataProviderException
{
  try
  {
    Configuration conf1 = super.setupSparkJob(conf, provider);
    Job job = new Job(conf1);
    setupConfig(job);
    return job.getConfiguration();
  }
  catch (IOException e)
  {
    throw new DataProviderException("Failure configuring map/reduce job with HDFS input info", e);
  }
}

@Override
public void setupJob(Job job,
    final MrsImageDataProvider provider) throws DataProviderException
{
  super.setupJob(job, provider);
  setupConfig(job);
}

@Override
public void teardown(Job job) throws DataProviderException
{
}

private void setupConfig(Job job) throws DataProviderException
{
  Configuration conf = job.getConfiguration();
  String strBasePath = HdfsMrsImageDataProvider.getBasePath(conf).toString();
  conf.set("hdfs." + MrGeoConstants.MRGEO_HDFS_IMAGE, strBasePath);

  String input = context.getInput();
  // first calculate the actual filename for the input (including zoom)
  HdfsMrsImageDataProvider dp = new HdfsMrsImageDataProvider(job.getConfiguration(),
      input, null);
  String image = HdfsMrsPyramidInputFormat.getZoomName(dp, context.getZoomLevel());
  // if we don't have this zoom level, use the max, then we'll decimate/subsample that one
  if (image == null)
  {
    log.error("Could not get image in setupJob() at zoom level " +
        context.getZoomLevel() + " for " + input);

    try
    {
      MrsPyramid pyramid;
      try
      {
        pyramid = MrsPyramid.open(dp);
      }
      catch (IOException e)
      {
        throw new DataProviderException("Failure opening input image pyramid: " + input, e);
      }
      final MrsPyramidMetadata metadata = pyramid.getMetadata();

      log.debug("In setupJob(), loading pyramid for " + input +
          " pyramid instance is " + pyramid + " metadata instance is " + metadata);

      image = HdfsMrsPyramidInputFormat.getZoomName(dp, metadata.getMaxZoomLevel());
    }
    catch (IOException e)
    {
      throw new DataProviderException("Failure opening input image: " + input, e);
    }
  }

  String zoomInput = image;

  try
  {
    HdfsMrsPyramidInputFormat.setInputInfo(job, zoomInput);
  }
  catch (IOException e)
  {
    throw new DataProviderException("Failure configuring map/reduce job with HDFS input info", e);
  }
}
}
