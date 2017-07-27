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

package org.mrgeo.services.mrspyramid.rendering;

import org.apache.commons.lang3.NotImplementedException;
import org.mrgeo.colorscale.ColorScale;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.utils.tms.Bounds;

import javax.activation.MimetypesFileTypeMap;
import javax.ws.rs.core.Response;
import java.io.IOException;

/**
 * Builds an HTTP response for KML data
 */
public class KmlResponseBuilder implements RasterResponseBuilder
{

public KmlResponseBuilder()
{
}

@Override
public String getFormatSuffix()
{
  return "kml";
}

@Override
public String getMimeType()
{
  return "application/vnd.google-earth.kml+xml";
}

@Override
@SuppressWarnings("squid:S1166") // Exception caught and handled
public Response getResponse(String pyrName, Bounds bounds, int width,
    int height, ColorScale cs, String reqUrl, int zoomLevel,
    ProviderProperties providerProperties)
{
  throw new NotImplementedException("KML has been disabled!");
//  try
//  {
//    String kmlBody = ImageRendererAbstract
//        .asKml(pyrName, bounds, reqUrl, providerProperties);
//    String type = new MimetypesFileTypeMap().getContentType(getFormatSuffix());
//    String headerInfo = "attachment; filename=" + pyrName + "." + getFormatSuffix();
//    return Response.ok()
//        .entity(kmlBody)
//        .encoding(type)
//        .header("Content-Disposition", headerInfo)
//        .header("Content-type", getMimeType()).build();
//  }
//  catch (IOException e)
//  {
//    if (e.getMessage() != null)
//    {
//      return Response.serverError().entity(e.getMessage()).build();
//    }
//    return Response.serverError().entity("Internal Error").build();
//  }
}
}
