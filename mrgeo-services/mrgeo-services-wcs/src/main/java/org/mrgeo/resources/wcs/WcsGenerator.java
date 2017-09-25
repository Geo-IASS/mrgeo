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

package org.mrgeo.resources.wcs;

import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.core.MrGeoProperties;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.raster.MrGeoRaster;
import org.mrgeo.services.SecurityUtils;
import org.mrgeo.services.Version;
import org.mrgeo.services.mrspyramid.rendering.ImageHandlerFactory;
import org.mrgeo.services.mrspyramid.rendering.ImageRenderer;
import org.mrgeo.services.mrspyramid.rendering.ImageRendererException;
import org.mrgeo.services.mrspyramid.rendering.ImageResponseWriter;
import org.mrgeo.services.utils.DocumentUtils;
import org.mrgeo.services.utils.RequestUtils;
import org.mrgeo.services.wcs.DescribeCoverageDocumentGenerator;
import org.mrgeo.services.wcs.WcsCapabilities;
import org.mrgeo.utils.XmlUtils;
import org.mrgeo.utils.tms.Bounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/wcs")
@SuppressWarnings("squid:S2696")
// The local statics are used in the singular case where we need a WCS cache, usually when using S3 and you need fast loading
public class WcsGenerator
{

private static final Logger log = LoggerFactory.getLogger(WcsGenerator.class);
private static final String WCS_VERSION = "1.1.0";
private static final String WCS_SERVICE = "wcs";

// Package Private for testing only
static Map<Version, Document> capabilities = new HashMap<>();
private static String baseURI = null;

static
{
  if (MrGeoProperties.getInstance().getProperty(MrGeoConstants.MRGEO_WCS_CAPABILITIES_CACHE, "true").equals("true"))
  {
    new Thread()
    {
      public void run()
      {
        long sleeptime = 60L * 1000L *
            Integer.parseInt(
                MrGeoProperties.getInstance().getProperty(MrGeoConstants.MRGEO_WCS_CAPABILITIES_REFRESH, "5"));

        boolean stop = false;
        while (!stop)
        {
          try
          {
            for (Version version : capabilities.keySet())
            {
              try
              {
                log.info("refreshing capabilities for version {}", version);
                ProviderProperties providerProperties = SecurityUtils.getProviderProperties();
                Document doc = generateCapabilities(version, baseURI, providerProperties);
                capabilities.put(version, doc);
              }
              catch (ParserConfigurationException | IOException e)
              {
                log.error("Exception thrown", e);
              }
            }
            Thread.sleep(sleeptime);
          }
          catch (InterruptedException e)
          {
            log.error("Thread Inturrupted...  stopping {}", e);
            stop = true;
          }
        }
      }
    }.start();
  }

}

private Version version = new Version(WCS_VERSION);

private static Document generateCapabilities(Version version, String baseURI, ProviderProperties providerProperties)
    throws IOException, ParserConfigurationException, InterruptedException
{
  final WcsCapabilities docGen = new WcsCapabilities();

  return docGen.generateDoc(version, baseURI + "?", RequestUtils.getPyramidFilesList(providerProperties));
}


@GET
public Response doGet(@Context UriInfo uriInfo, @Context HttpHeaders headers)
{
  log.info("headers:");
  for (MultivaluedMap.Entry header: headers.getRequestHeaders().entrySet())
  {
    log.info("  {}: {}", header.getKey(), header.getValue());
  }

  log.info("GET URI: {}", uriInfo.getRequestUri());
  return handleRequest(uriInfo, headers);
}

@POST
public Response doPost(@Context UriInfo uriInfo, @Context HttpHeaders headers)
{
  log.info("POST URI: {}", uriInfo.getRequestUri());
  return handleRequest(uriInfo, headers);
}

private Response handleRequest(UriInfo uriInfo, HttpHeaders headers)
{
  long start = System.currentTimeMillis();

  String uri = RequestUtils.buildBaseURI(uriInfo, headers);
  baseURI = uri;

  MultivaluedMap<String, String> allParams = uriInfo.getQueryParameters();
  String request = getQueryParam(allParams, "request", "GetCapabilities");
  ProviderProperties providerProperties = SecurityUtils.getProviderProperties();

  try
  {
    String serviceName = getQueryParam(allParams, "service");
    if (serviceName == null)
    {
      return writeError(Response.Status.BAD_REQUEST, "Missing required SERVICE parameter. Should be set to \"WCS\"");
    }
    if (!serviceName.equalsIgnoreCase("wcs"))
    {
      return writeError(Response.Status.BAD_REQUEST, "Invalid SERVICE parameter. Should be set to \"WCS\"");
    }

    if (request.equalsIgnoreCase("getcapabilities"))
    {
      return getCapabilities(uri, allParams, providerProperties);
    }
    else if (request.equalsIgnoreCase("describecoverage"))
    {
      return describeCoverage(uri, allParams, providerProperties);
    }
    else if (request.equalsIgnoreCase("getcoverage"))
    {
      return getCoverage(allParams, providerProperties);
    }

    return writeError(Response.Status.BAD_REQUEST, "Invalid request");
  }
  finally
  {
    //if (log.isDebugEnabled())
    log.info("WCS request time: {}ms", (System.currentTimeMillis() - start));
    // this can be resource intensive.
    System.gc();
    final Runtime rt = Runtime.getRuntime();
    log.info(String.format("WMS request memory: %.1fMB / %.1fMB%n", (rt.totalMemory() - rt
        .freeMemory()) / 1e6, rt.maxMemory() / 1e6));
  }
}

/**
 * Returns the value for the specified paramName case-insensitively. If the
 * parameter does not exist, it returns null.
 */
private String getQueryParam(MultivaluedMap<String, String> allParams, String paramName)
{
  for (Map.Entry<String, List<String>> es : allParams.entrySet())
  {
    if (es.getKey().equalsIgnoreCase(paramName))
    {
      if (es.getValue().size() == 1)
      {
        return es.getValue().get(0);
      }
    }
  }

  return null;
}

private Response describeCoverage(String baseURI,
    MultivaluedMap<String, String> allParams,
    final ProviderProperties providerProperties)
{
  String versionStr = getQueryParam(allParams, "version", WCS_VERSION);
  version = new Version(versionStr);
  String[] layers;
  if (version.isLess("1.1.0"))
  {
    String layer = getQueryParam(allParams, "coverage");
    if (layer == null)
    {
      return writeError(Response.Status.BAD_REQUEST, "Missing required COVERAGE parameter");
    }
    layers = new String[]{layer};
  }
  else
  {
    String layerStr = getQueryParam(allParams, "identifiers");
    if (layerStr == null)
    {
      return writeError(Response.Status.BAD_REQUEST, "Missing required IDENTIFIERS parameter");
    }
    layers = layerStr.split(",");
  }

  try
  {
    final DescribeCoverageDocumentGenerator docGen = new DescribeCoverageDocumentGenerator();
    final Document doc = docGen.generateDoc(version, baseURI, layers);

    ByteArrayOutputStream xmlStream = new ByteArrayOutputStream();
    final PrintWriter out = new PrintWriter(xmlStream);
    // DocumentUtils.checkForErrors(doc);
    DocumentUtils.writeDocument(doc, version, WCS_SERVICE, out);
    out.close();
    return Response.ok(xmlStream.toString()).type(MediaType.APPLICATION_XML).build();
  }
  catch (TransformerException | IOException e)
  {
    log.error("Exception thrown", e);
    return writeError(Response.Status.BAD_REQUEST, e.getMessage());
  }
}

private Response getCapabilities(String baseURI, MultivaluedMap<String, String> allParams,
    ProviderProperties providerProperties)
{
  // The versionParamName will be null if the request did not include the
  // version parameter.
  String acceptVersions = getQueryParam(allParams, "acceptversions", null);
  version = null;
  if (acceptVersions != null)
  {
    String[] versions = acceptVersions.split(",");

    for (String ver : versions)
    {
      if (version == null)
      {
        version = new Version(ver);
      }
      else if (version.isLess(ver))
      {
        version = new Version(ver);
      }
    }
  }
  else
  {
    version = new Version(getQueryParam(allParams, "version", WCS_VERSION));
  }

  try
  {
    Document doc;
    if (capabilities.containsKey(version))
    {
      log.warn("*** cached!");
      doc = capabilities.get(version);
    }
    else
    {
      log.warn("*** NOT cached!");

      doc = generateCapabilities(version, baseURI, providerProperties);
      capabilities.put(version, doc);
    }

    ByteArrayOutputStream xmlStream = new ByteArrayOutputStream();
    final PrintWriter out = new PrintWriter(xmlStream);
    // DocumentUtils.checkForErrors(doc);
    DocumentUtils.writeDocument(doc, version, WCS_SERVICE, out);
    out.close();
    return Response.ok(xmlStream.toString()).type(MediaType.APPLICATION_XML).build();
  }
  catch (InterruptedException | TransformerException | ParserConfigurationException | IOException e)
  {
    log.error("Exception thrown", e);
    return writeError(Response.Status.BAD_REQUEST, e.getMessage());
  }


//    return writeError(Response.Status.BAD_REQUEST, "Not Implemented");
}

private Response getCoverage(MultivaluedMap<String, String> allParams,
    ProviderProperties providerProperties)
{
  // Get all of the query parameter values needed and validate them
  String versionStr = getQueryParam(allParams, "version", WCS_VERSION);
  version = new Version(versionStr);

  String layer;
  if (version.isLess("1.1.0"))
  {
    layer = getQueryParam(allParams, "coverage");
  }
  else
  {
    layer = getQueryParam(allParams, "identifier");
  }

  if (layer == null)
  {
    return writeError(Response.Status.BAD_REQUEST, "Missing required COVERAGE parameter");
  }


  String crs;
  Bounds bounds;
  try
  {
    if (version.isLess("1.1.0"))
    {
      bounds = getBoundsParam(allParams, "bbox", null);
    }
    else
    {
      bounds = getBoundsParam(allParams, "boundingbox", null);
    }
    crs = getCrsParam(allParams);
  }
  catch (Exception e)
  {
    log.error("Exception thrown", e);
    return writeError(Response.Status.BAD_REQUEST, e.getMessage());
  }

  String format = getQueryParam(allParams, "format");
  if (format == null)
  {
    return writeError(Response.Status.BAD_REQUEST, "Missing required FORMAT parameter");
  }

  int width = getQueryParamAsInt(allParams, "width", -1);
  if (width < 0)
  {
    return writeError(Response.Status.BAD_REQUEST, "Missing required WIDTH parameter");
  }
  else if (width == 0)
  {
    return writeError(Response.Status.BAD_REQUEST, "WIDTH parameter must be greater than 0");
  }

  int height = getQueryParamAsInt(allParams, "height", -1);
  if (height < 0)
  {
    return writeError(Response.Status.BAD_REQUEST, "Missing required HEIGHT parameter");
  }
  else if (height == 0)
  {
    return writeError(Response.Status.BAD_REQUEST, "HEIGHT parameter must be greater than 0");
  }

  ImageRenderer renderer;
  try
  {
    renderer = (ImageRenderer) ImageHandlerFactory.getHandler(format, ImageRenderer.class);
  }
  catch (Exception e)
  {
    log.error("Exception thrown", e);
    return writeError(Response.Status.BAD_REQUEST, e.getMessage());
  }

  // Return the resulting image
  try
  {
    log.info("Rendering " + layer);
    MrGeoRaster result = renderer.renderImage(layer, bounds, width, height, providerProperties, crs);

    log.info("Generating response");
    Response.ResponseBuilder builder = ((ImageResponseWriter) ImageHandlerFactory
        .getHandler(format, ImageResponseWriter.class))
        .write(result, layer, bounds);

    log.info("Building and returning response");
    return builder.build();
  }
  catch (IllegalAccessException | InstantiationException | ImageRendererException e)
  {
    log.error("Unable to render the image in getCoverage", e);
    return writeError(Response.Status.BAD_REQUEST, e.getMessage());
  }
}

/**
 * Returns the value for the specified paramName case-insensitively. If the
 * parameter does not exist, it returns defaultValue.
 */

private String getQueryParam(MultivaluedMap<String, String> allParams,
    String paramName,
    String defaultValue)
{
  String value = getQueryParam(allParams, paramName);
  if (value != null)
  {
    return value;
  }
  return defaultValue;
}

/**
 * Returns the int value for the specified paramName case-insensitively. If
 * the parameter value exists, but is not an int, it throws a NumberFormatException.
 * If it does not exist, it returns defaultValue.
 */
private int getQueryParamAsInt(MultivaluedMap<String, String> allParams,
    String paramName,
    int defaultValue)
    throws NumberFormatException
{
  for (Map.Entry<String, List<String>> es : allParams.entrySet())
  {
    if (es.getKey().equalsIgnoreCase(paramName))
    {
      if (es.getValue().size() == 1)
      {
        return Integer.parseInt(es.getValue().get(0));
      }
    }
  }
  return defaultValue;
}

//private String getActualQueryParamName(MultivaluedMap<String, String> allParams,
//    String paramName)
//{
//  for (String key: allParams.keySet())
//  {
//    if (key.equalsIgnoreCase(paramName))
//    {
//      return key;
//    }
//  }
//  return null;
//}

/**
 * Returns the int value for the specified paramName case-insensitively. If
 * the parameter value exists, but is not an int, it throws a NumberFormatException.
 * If it does not exist, it returns defaultValue.
 */
//private double getQueryParamAsDouble(MultivaluedMap<String, String> allParams,
//    String paramName,
//    double defaultValue)
//    throws NumberFormatException
//{
//  for (String key: allParams.keySet())
//  {
//    if (key.equalsIgnoreCase(paramName))
//    {
//      List<String> value = allParams.get(key);
//      if (value.size() == 1)
//      {
//        return Double.parseDouble(value.get(0));
//      }
//    }
//  }
//  return defaultValue;
//}
private Bounds getBoundsParam(MultivaluedMap<String, String> allParams, String paramName, Bounds bounds)
    throws WcsGeneratorException
{
  String bbox = getQueryParam(allParams, paramName);
  if (bbox == null)
  {
    throw new WcsGeneratorException("Missing required " + paramName.toUpperCase() + " parameter");
  }
  String[] bboxComponents = bbox.split(",");
  if (!(bboxComponents.length == 5 || bboxComponents.length == 4))
  {
    throw new WcsGeneratorException(
        "Invalid \" + paramName.toUpperCase() + \" parameter. Should contain minX, minY, maxX, maxY");
  }

  double[] bboxValues = new double[4];
  for (int index = 0; index < bboxComponents.length; index++)
  {
    try
    {
      bboxValues[index] = Double.parseDouble(bboxComponents[index]);
    }
    catch (NumberFormatException nfe)
    {
      log.error("Exception thrown", nfe);
      throw new WcsGeneratorException("Invalid BBOX value: " + bboxComponents[index]);
    }
  }

  if (bounds == null)
  {
    return new Bounds(bboxValues[0], bboxValues[1], bboxValues[2], bboxValues[3]);
  }

  return bounds.expand(bboxValues[0], bboxValues[1], bboxValues[2], bboxValues[3]);
}

private String getCrsParam(MultivaluedMap<String, String> allParams)
{
  String crs = getQueryParam(allParams, "crs");
  if (crs == null || crs.isEmpty())
  {
    // CRS can also be buried in bbox (in earlier versions of the spec)
    String bbox = getQueryParam(allParams, "bbox");
    if (bbox != null)
    {
      String[] bboxComponents = bbox.split(",");
      if (bboxComponents.length == 5)
      {
        return bboxComponents[4];
      }
    }
    return null;
  }
  else
  {
    return crs;
  }
}

/*
 * Writes OGC spec error messages to the response
 */
@SuppressWarnings("squid:S1166") // Exception caught and handled
private Response writeError(Response.Status httpStatus, final String msg)
{
  try
  {
    Document doc;
    final DocumentBuilderFactory dBF = DocumentBuilderFactory.newInstance();
    final DocumentBuilder builder = dBF.newDocumentBuilder();
    doc = builder.newDocument();

    final Element ser = doc.createElement("ServiceExceptionReport");
    doc.appendChild(ser);
    ser.setAttribute("version", version.toString());
    final Element se = XmlUtils.createElement(ser, "ServiceException");
    CDATASection msgNode = doc.createCDATASection(msg);
    se.appendChild(msgNode);
    final ByteArrayOutputStream xmlStream = new ByteArrayOutputStream();
    final PrintWriter out = new PrintWriter(xmlStream);
    DocumentUtils.writeDocument(doc, version, WCS_SERVICE, out);
    out.close();
    return Response
        .status(httpStatus)
        .header("Content-Type", MediaType.TEXT_XML)
        .entity(xmlStream.toString())
        .build();
  }
  catch (ParserConfigurationException | TransformerException ignored)
  {
  }
  // Fallback in case there is an XML exception above
  return Response.status(httpStatus).entity(msg).build();
}

public static class WcsGeneratorException extends IOException
{
  private static final long serialVersionUID = 1L;

  WcsGeneratorException()
  {
    super();
  }

  WcsGeneratorException(final String msg)
  {
    super(msg);
  }

  WcsGeneratorException(final String msg, final Throwable cause)
  {
    super(msg, cause);
  }

  WcsGeneratorException(final Throwable cause)
  {
    super(cause);
  }
}

}
