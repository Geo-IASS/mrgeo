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

package org.mrgeo.resources.wms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.mrgeo.colorscale.ColorScale;
import org.mrgeo.colorscale.ColorScaleManager;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.mrgeo.image.MrsPyramid;
import org.mrgeo.image.MrsPyramidMetadata;
import org.mrgeo.services.Version;
import org.mrgeo.utils.LongRectangle;
import org.mrgeo.utils.XmlUtils;
import org.mrgeo.utils.tms.Bounds;
import org.mrgeo.utils.tms.TMSUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Generates XML output for OGC WMS DescribeTiles request
 */
public class DescribeTilesDocumentGenerator
{
private static final Logger log = LoggerFactory.getLogger(DescribeTilesDocumentGenerator.class);

/*
 * Writes scale information for a layer
 */
private static void writeScaleInfo(MrsPyramidMetadata metadata, int zoom, Element matrixSet)
{

  double scale = TMSUtils.resolution(zoom, metadata.getTilesize());
  Element tileMatrix = XmlUtils.createElement(matrixSet, "TileMatrix");
  tileMatrix.setAttribute("scale", Double.toString(scale));

  Bounds bounds = metadata.getBounds();
  XmlUtils.createTextElement2(tileMatrix, "gml:Point",
      String.format("%f %f", bounds.w, bounds.n));

  LongRectangle tb = metadata.getTileBounds(zoom);

  XmlUtils.createTextElement2(tileMatrix, "MatrixWidth", String.format("%d", tb.getWidth()));
  XmlUtils.createTextElement2(tileMatrix, "MatrixHeight", String.format("%d", tb.getHeight()));
}

/**
 * @throws IOException
 */
public Document generateDoc(Version version, String requestUrl,
    MrsImageDataProvider[] providers) throws IOException
{
  Document doc = XmlUtils.createDocument();

  Element dtr = doc.createElement("WMS_DescribeTilesResponse");
  dtr.setAttribute("version", version.toString());

  // 1.4.0 isn't out yet, but it does have a preliminary protocol for tiling.
  // We're using that.
  if (version.isEqual("1.4.0"))
  {
    XmlUtils.createComment(dtr,
        "1.4.0 isn't out yet, but it does have a preliminary protocol for tiling.");
    XmlUtils.createComment(dtr,
        "See http://www.opengeospatial.org/standards/wms for details.");
    dtr.setAttribute("xmlns", "http://www.opengis.net/wms");
    dtr.setAttribute("xmlns:xlink", "http://www.w3.org/1999/xlink");
    dtr.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
    dtr.setAttribute("xmlns:gml", "http://www.opengis.net/gml");
    dtr.setAttribute("xsi:schemaLocation",
        "http://www.opengis.net/wms http://schemas.opengis.net/wms/1.4.0/capabilities_1_4_0.xsd");
  }
  addLayersToDescribeTiles(dtr, version, providers);
  doc.appendChild(dtr);

  return doc;
}

  /*
   * Adds data layers to the DescribeTiles response
   */

@SuppressWarnings("squid:S1166") // Exception caught and handled
private void addLayersToDescribeTiles(Element dt, Version version,
    MrsImageDataProvider[] providers)
{
  Document doc = dt.getOwnerDocument();

  // sort providers based on resource name
  Arrays.sort(providers, new MrsImageComparator());

  for (MrsImageDataProvider provider : providers)
  {
    // we'll add the layer to dt later...
    Element layer = doc.createElement("Layer");

    layer.setAttribute("name", provider.getResourceName());

    try
    {
      MrsPyramid pyramid = MrsPyramid.open(provider);
      MrsPyramidMetadata metadata = pyramid.getMetadata();

      Element formats = XmlUtils.createElement(layer, "TiledFormats");
      XmlUtils.createTextElement2(formats, "Value", WmsGenerator.PNG_MIME_TYPE);
      XmlUtils.createTextElement2(formats, "Value", WmsGenerator.JPEG_MIME_TYPE);
      XmlUtils.createTextElement2(formats, "Value", WmsGenerator.TIFF_MIME_TYPE);

      Element crs = XmlUtils.createElement(layer, "TiledCrs");
      crs.setAttribute("name", "CRS:84");

      Element matrixSet = XmlUtils.createElement(crs, "TiledMatrixSet");
      XmlUtils.createTextElement2(
          matrixSet, "TileWidth", Integer.toString(metadata.getTilesize()));
      XmlUtils.createTextElement2(
          matrixSet, "TileHeight", Integer.toString(metadata.getTilesize()));

      if (pyramid.hasPyramids())
      {
        MrsPyramidMetadata.ImageMetadata[] imeta = metadata.getImageMetadata();
        for (int zoom = pyramid.getMaximumLevel(); zoom > 0; zoom--)
        {
          if (imeta[zoom].name != null)
          {
            writeScaleInfo(metadata, zoom, matrixSet);
          }
          else
          {
            log.warn("No image exists at zoom level " + zoom + " for layer " + provider.getResourceName());
          }
        }
      }
      else
      {
        writeScaleInfo(metadata, metadata.getMaxZoomLevel(), matrixSet);
      }

      Element styles = XmlUtils.createElement(layer, "TiledStyles");
      XmlUtils.createTextElement2(styles, "Value", "default");

      int bands = pyramid.getMetadata().getBands();
      // Add the colorscale styles if there are only 1 band...
      if (bands == 1)
      {

        ColorScale[] scales = ColorScaleManager.getColorScaleList();
        for (ColorScale scale : scales)
        {
          XmlUtils.createTextElement2(styles, "Value", scale.getName());
        }
      }
      else if (bands >= 3)
      {
        XmlUtils.createTextElement2(styles, "Value", "BandR,G,B");
      }

      //only add this layer to the XML document if everything else was successful
      dt.appendChild(layer);
    }
    catch (IOException e)
    {
      // suck up the exception, there may be a bad file in the images directory...
    }

  }
}

@SuppressFBWarnings(value = "SE_COMPARATOR_SHOULD_BE_SERIALIZABLE", justification = "Do not need serialization")
private static class MrsImageComparator implements Comparator<MrsImageDataProvider>
{
  @Override
  public int compare(MrsImageDataProvider o1, MrsImageDataProvider o2)
  {
    return o1.getResourceName().compareTo(o2.getResourceName());
  }
}
}
