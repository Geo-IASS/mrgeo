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

package org.mrgeo.data;

import org.apache.hadoop.mapreduce.*;
import org.mrgeo.data.DataProviderFactory.AccessMode;
import org.mrgeo.data.image.ImageInputFormatContext;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.mrgeo.data.image.MrsImageInputFormatProvider;
import org.mrgeo.data.raster.RasterWritable;
import org.mrgeo.data.tile.TileIdWritable;
import org.mrgeo.image.MrsPyramid;
import org.mrgeo.mapreduce.splitters.MrsPyramidInputSplit;
import org.mrgeo.mapreduce.splitters.TiledInputSplit;
import org.mrgeo.utils.tms.TMSUtils;
import org.mrgeo.utils.tms.TileBounds;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * This class is the base class for the Hadoop InputFormat classes that are configured
 * into Hadoop jobs submitted by MrGeo for processing pyramid data. Map/reduce jobs
 * on pyramid input data allow multiple inputs. In order to make that work, this class
 * ensures that each split contains the bounds of the splits that are ordered before
 * them and the bounds of the splits that come after them. This way, the RecordReader
 * classes for pyramid data can use that information to ensure that tiles are only
 * ever read once, regardless of which input pyramids contain data in that tile.
 */
public class MrsPyramidInputFormat extends InputFormat<TileIdWritable, RasterWritable>
{
public MrsPyramidInputFormat()
{
}

public RecordReader<TileIdWritable, RasterWritable> createRecordReader(InputSplit inputSplit,
    TaskAttemptContext context) throws IOException, InterruptedException
{
  return new MrsPyramidRecordReader();
}

/**
 * Returns the list of MrsPyramidInputSplit objects for the input pyramid.
 * Sub-classes should have no need to override this method. It
 * contains logic required by all input formats (described in the overview
 * for this class).
 */
@Override
public List<InputSplit> getSplits(JobContext context) throws IOException, InterruptedException
{
  // Get the ImageInputFormatContext from the JobContext
  ImageInputFormatContext ifContext = ImageInputFormatContext.load(context.getConfiguration());
  String input = ifContext.getInput();

  MrsPyramid p = MrsPyramid.open(input, context.getConfiguration());
  String pyramid = p.getName();
  List<TiledInputSplit> nativeSplits = getNativeSplits(context, ifContext, pyramid);
  List<TiledInputSplit> filteredSplits = filterInputSplits(ifContext,
      nativeSplits,
      p.getTileSize());

  List<InputSplit> results = new LinkedList<>();
  // remove the current bounds from the post bounds list.
  // Loop the native splits from this input, and create a new MrsPyramidInputSplit
  // that wraps it and includes the pre/post bounds.
  for (TiledInputSplit tiledSplit : filteredSplits)
  {
    MrsPyramidInputSplit mpsplit = new MrsPyramidInputSplit(tiledSplit, p.getName());
    results.add(mpsplit);
  }

  return results;
}

/**
 * Return native splits from the data provider for the passed in input.
 * It ensures that the native splits returned from the data provider are
 * instances of TiledInputSplit.
 */
protected List<TiledInputSplit> getNativeSplits(final JobContext context,
    final ImageInputFormatContext ifContext,
    final String input) throws IOException, InterruptedException
{
  MrsImageDataProvider dp = DataProviderFactory.getMrsImageDataProvider(input,
      AccessMode.READ, context.getConfiguration());
  MrsImageInputFormatProvider ifProvider = dp.getImageInputFormatProvider(ifContext);
  List<InputSplit> splits = ifProvider.getInputFormat(input).getSplits(context);
  // In order to work with MrGeo and input bounds cropping, the splits must be
  // of type TiledInputSplit.
  List<TiledInputSplit> result = new ArrayList<>(splits.size());
  for (InputSplit split : splits)
  {
    if (split instanceof TiledInputSplit)
    {
      result.add((TiledInputSplit) split);
    }
    else
    {
      throw new IOException("ERROR: native input splits must be instances of" +
          "TiledInputSplit. Received " + split.getClass().getCanonicalName());
    }
  }
  return result;
}

/**
 * Performs cropping of input splits to the bounds specified in the ifContext. This
 * logic is common to all pyramid input formats, regardless of the data provider,
 * so there should be no need to override it in sub-classes.
 */
List<TiledInputSplit> filterInputSplits(final ImageInputFormatContext ifContext,
    final List<TiledInputSplit> splits,
    final int tileSize)
{
  // If there are no splits or no crop region, just return the splits
  if (splits.size() == 0 || ifContext.getBounds() == null)
  {
    return splits;
  }
  List<TiledInputSplit> result = new ArrayList<>();
  TileBounds cropBounds = TMSUtils.boundsToTile(ifContext.getBounds(),
      ifContext.getZoomLevel(), tileSize);

  SplitIterator splitIter = new SplitIterator(splits, new RegionSplitVisitor(cropBounds));
  TiledInputSplit split = splitIter.next();
  while (split != null)
  {
    result.add(split);
    split = splitIter.next();
  }
  return result;
}
}
