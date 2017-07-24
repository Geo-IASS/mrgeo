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


import org.mrgeo.utils.tms.Bounds;

public class ImageOutputFormatContext
{
private String output;
private int zoomlevel;
private int tilesize;
private int bands;
private Bounds bounds;
private int tiletype;
private String protectionLevel;

/**
 * This constructor is used when producing a single image output.
 *
 * @param output
 */
public ImageOutputFormatContext(String output, Bounds bounds, int zoomlevel, int tilesize, String protectionLevel)
{
  this.output = output;
  if (bounds != null)
  {
    this.bounds = bounds.clone();
  }
  this.zoomlevel = zoomlevel;
  this.tilesize = tilesize;
  this.protectionLevel = protectionLevel;
}

public ImageOutputFormatContext(String output, Bounds bounds, int zoomlevel, int tilesize,
    String protectionLevel, int tiletype, int bands)
{
  this.output = output;
  if (bounds != null)
  {
    this.bounds = bounds.clone();
  }
  this.bands = bands;
  this.zoomlevel = zoomlevel;
  this.tilesize = tilesize;
  this.tiletype = tiletype;
  this.protectionLevel = protectionLevel;
}


public String getOutput()
{
  return output;
}

public int getZoomLevel()
{
  return zoomlevel;
}

public Bounds getBounds()
{
  return bounds;
}

public int getBands()
{
  return bands;
}

public int getTileSize()
{
  return tilesize;
}

public int getTileType()
{
  return tiletype;
}

public String getProtectionLevel()
{
  return protectionLevel;
}
}
