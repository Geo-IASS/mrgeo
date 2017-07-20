package org.mrgeo.hdfs.utils;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mrgeo.data.tile.TileIdWritable;
import org.mrgeo.hdfs.image.HdfsMrsImageReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HdfsMrsImageReaderBuilder
{
private HdfsMrsImageReader hdfsMrsImageReader;
private List<HadoopFileUtils.MapFileReaderWrapper> mapFileReaderWrappers = new ArrayList<>();
private boolean canBeCached;
private int zoom;

public HdfsMrsImageReaderBuilder()
{
  this.hdfsMrsImageReader = mock(HdfsMrsImageReader.class);
}

public HdfsMrsImageReaderBuilder mapFileReader(HadoopFileUtils.MapFileReaderWrapper mapFileReader)
{
  // each call adds a new map file reader to the list
  this.mapFileReaderWrappers.add(mapFileReader);

  return this;
}

public HdfsMrsImageReaderBuilder canBeCached(boolean canBeCached)
{
  this.canBeCached = canBeCached;

  return this;
}

public HdfsMrsImageReaderBuilder zoom(int zoom)
{
  this.zoom = zoom;

  return this;
}

public HdfsMrsImageReader build() throws IOException
{
  // Return the MapFile.Readers for the specified index
  when(hdfsMrsImageReader.getReaderWrapper(anyInt())).thenAnswer(new Answer<HadoopFileUtils.MapFileReaderWrapper>()
  {
    @Override
    public HadoopFileUtils.MapFileReaderWrapper answer(InvocationOnMock invocationOnMock) throws Throwable
    {
      int index = (Integer) invocationOnMock.getArguments()[0];
      return mapFileReaderWrappers.get(index);
    }
  });

  // Return the number of MapFile.Reader that have been configured
  when(hdfsMrsImageReader.getMaxPartitions()).thenReturn(mapFileReaderWrappers.size());

  // Always start at 0 for mock
  when(hdfsMrsImageReader.getPartitionIndex(any(TileIdWritable.class))).thenReturn(0);

  when(hdfsMrsImageReader.canBeCached()).thenReturn(canBeCached);

  when(hdfsMrsImageReader.getZoomlevel()).thenReturn(zoom);

  return hdfsMrsImageReader;
}
}
