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

package org.mrgeo.data.accumulo.image;

import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.mrgeo.data.DataProviderException;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.accumulo.input.image.AccumuloMrsPyramidInputFormatProvider;
import org.mrgeo.data.accumulo.metadata.AccumuloMrsPyramidMetadataReader;
import org.mrgeo.data.accumulo.metadata.AccumuloMrsPyramidMetadataWriter;
import org.mrgeo.data.accumulo.output.image.AccumuloMrsPyramidOutputFormatProvider;
import org.mrgeo.data.accumulo.utils.AccumuloConnector;
import org.mrgeo.data.accumulo.utils.AccumuloUtils;
import org.mrgeo.data.accumulo.utils.MrGeoAccumuloConstants;
import org.mrgeo.data.image.*;
import org.mrgeo.data.raster.RasterWritable;
import org.mrgeo.data.tile.TileIdWritable;
import org.mrgeo.data.accumulo.utils.Base64Utils;
import org.mrgeo.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Properties;


/**
 * This is the class that provides the input and output formats for
 * storing and retrieving data from Accumulo.  Under this implementation,
 * the indexing scheme implemented is the TMS tiling scheme.  This is the
 * same indexing being done by the HDFS implementation.  There is one
 * addition to the scheme for this implementation.  The key used is:<br>
 * <br>
 * rowID: [bytes of long which is tile id]<br>
 * --cf: [zoom level of tile]<br>
 * ----cq: [string value of tile id]<br>
 * ------vis: [what is passed]<br>
 * --------value: [bytes of raster]<br>
 */
public class AccumuloMrsImageDataProvider extends MrsImageDataProvider
{

// logging
private static Logger log = LoggerFactory.getLogger(AccumuloMrsImageDataProvider.class);
// the classes that are used for metadata
private AccumuloMrsPyramidMetadataReader metaReader = null;
private MrsPyramidMetadataWriter metaWriter = null;
// table we are connecting to for data
private String table;
// The following should store the original resource name and any Accumulo
// property settings required to access that resource. Internally, this
// data provider should make use of the resolved resource name. It should
// never use this property directly, but instead call
private String resolvedResourceName;
// output controller
private AccumuloMrsPyramidOutputFormatProvider outFormatProvider = null;
// properties coming in from the queries and command line arguments
private Properties queryProps;

// when working with a map reduce job, configuration is needed
private Configuration conf;

// keep track of the visibility of the data - can be empty visibility
private ColumnVisibility cv;

// this translates to the column visibility
private String pl; // protection level

/**
 * Base constructor for the provider.
 *
 * @param resourceName - the table being utilized.  The input may be encoded or may not be.
 *                     The encoded resource name has all the information needed to connect to Accumulo.
 */
public AccumuloMrsImageDataProvider(final String resourceName)
{
  this(new ProviderProperties(), resourceName);

  // Determine if the resourceName is resolved or not. If it is, then call
  // setResourceName. If not, then we'll need to resolve it on demand - see
  // getResolvedResourceName().
  boolean nameIsResolved = resourceName.startsWith(MrGeoAccumuloConstants.MRGEO_ACC_ENCODED_PREFIX);

  if (nameIsResolved)
  {
    resolvedResourceName = resourceName;
  }
  else
  {
    setResourceName(resourceName);
  }

  // initialize the query properties if needed
  if (queryProps == null)
  {
    queryProps = new Properties();
  }

//    if(queryProps.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_AUTHS) == null){
//    	queryProps.setProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_AUTHS, MrGeoAccumuloConstants.MRGEO_ACC_NOAUTHS);
//    }

} // end constructor


/**
 * This constructor is used for WMS/TMS queries
 *
 * @param props        has properties from the query.  This will include authorizations for scans
 * @param resourceName is the name of the table to use.  It is possible that there is encoded
 *                     information in the resourceName.  The encoded information will have all the information
 *                     needed to connect to Accumulo.
 */
public AccumuloMrsImageDataProvider(ProviderProperties props, String resourceName)
{
  super();

  this.providerProperties = props;
  // check if the resourceName is encoded
  boolean nameIsResolved = resourceName.startsWith(MrGeoAccumuloConstants.MRGEO_ACC_ENCODED_PREFIX);

  // get the name of the resource we are using
  if (nameIsResolved)
  {
    resolvedResourceName = resourceName;
  }
  else
  {
    setResourceName(resourceName);
  }

  // it is possible that we are being called for the first time
  if (queryProps == null)
  {
    queryProps = new Properties();
  }

	  /* 
     * get the authorizations for reading from Accumulo
	   */
  // set the default of no authorizations
  String auths = null;// = MrGeoAccumuloConstants.MRGEO_ACC_NOAUTHS;

  // check for auths in the query
  if (props != null)
  {
    List<String> roles = props.getRoles();
    if (roles != null && roles.size() > 0)
    {
      auths = StringUtils.join(roles, ",");
    }
  }

  // set the authorizations in the query properties
  if (auths != null)
  {
    queryProps.setProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_AUTHS, auths);
  }

  // get the connection information
  if (props != null)
  {
    Properties p = AccumuloUtils.providerPropertiesToProperties(props);
    queryProps.putAll(p);
  }
  else
  {
    // ???????
    try
    {
      queryProps.putAll(AccumuloConnector.getAccumuloProperties());
    }
    catch (DataProviderException e)
    {
      log.error("Unable to get Accumulo connection properties", e);
    }
  }

} // end constructor


@Override
public void setupJob(final Job job) throws DataProviderException
{
  setupConfig(job.getConfiguration());
}

@Override
public Configuration setupSparkJob(final Configuration conf) throws DataProviderException
{
  setupConfig(conf);
  return conf;
}

/**
 * If the resourceName is null, then this method should extract/compute it
 * from the resolvedResourceName and return it.
 *
 * @return The name of the resource being utilized.
 */
@Override
@SuppressWarnings("squid:S1166") // Exception caught and handled
public String getResourceName()
{
  try
  {
    String result = super.getResourceName();
    if (result == null)
    {

      // decode the properties
      Properties props = AccumuloConnector.decodeAccumuloProperties(resolvedResourceName);

      // get the resource
      result = props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_RESOURCE);

      setResourceName(result);
    }

    return result;
  }
  catch (ClassNotFoundException | IOException e)
  {
    log.error("Exception thrown", e);
  }

  return null;
} // end getResourceName

@Override
public String getSimpleResourceName() throws IOException
{
  return getResourceName();
}

/**
 * If the resolvedResourceName is null, then it computes it based on the
 * resourceName and the Accumulo configuration properties. The
 * resolvedResourceName consists of the resourceName and the properties encoded
 * together into a single String which is only used internally to this
 * plugin.
 *
 * @return The encoded elements used to connect to Accumulo.
 */
public String getResolvedName() throws DataProviderException
{
  if (resolvedResourceName == null)
  {
    resolvedResourceName = AccumuloConnector.encodeAccumuloProperties(this.getResourceName());
  }
  return resolvedResourceName;
} // end getResolvedName

/**
 * Used for retrieving the name of the table in use.
 *
 * @return The name of table being used.
 */
public String getTable()
{
  if (table == null)
  {
    table = this.getResourceName();
  }
  return table;
} // end getTable

/**
 * This will return the class that handles the input classes for a map reduce job.
 *
 * @param context - is the image context for the input for the job.
 * @return An instance of AccumuloMrsPyramidInputFormatProvider is returned.
 */
@Override
public MrsImageInputFormatProvider getImageInputFormatProvider(ImageInputFormatContext context)
{
  return new AccumuloMrsPyramidInputFormatProvider(context);
} // end getImageInputFormatProvider

/**
 * This will return the class that handles the classes for output for a map reduce job.
 *
 * @param context - is the image context for the output of the job.
 * @return An instance of AccumuloMrsPyramidOutputFormatProvider.
 */
@Override
public MrsImageOutputFormatProvider getTiledOutputFormatProvider(ImageOutputFormatContext context)
{
  return new AccumuloMrsPyramidOutputFormatProvider(this, context, cv);
} // end getTiledOutputFormatProvider

/**
 * Delete is not implemented.  Administration of what is in Accumulo
 * is not handled in this code base.  It is left to the Data Administrator
 * to delete data.
 */
@Override
public void delete()
{
  // TODO: Need to implement
  log.info("Asked to delete the resource " + table + ".  Not deleting anything!!!");
  //throw new NotImplementedException();
} // end delete

/**
 * Delete is not implemented.  Administration of what is in Accumulo
 * is not handled in this code base.  It is left to the Data Administrator
 * to delete data.
 *
 * @param zoomLevel - the zoom level to delete.
 */
@Override
public void delete(int zoomLevel)
{
  // TODO: Need to implement
  log.info("Asked to delete " + zoomLevel + ".  Not deleting anything!!!");
  //throw new NotImplementedException();
} // end delete

/**
 * Move is not implemented.  dministration of what is in Accumulo
 * is not handled in this code base.  It is left to the Data Administrator
 * to move data.
 *
 * @param toResource - the destination for the move.
 */
@Override
public void move(final String toResource)
{
  // TODO: Need to implement
  throw new NotImplementedException("move() not implemented");
} // end move

@Override
public boolean validateProtectionLevel(final String protectionLevel)
{
  return AccumuloUtils.validateProtectionLevel(protectionLevel);
}

/**
 * The class that provides reading metadata from the Accumulo table is returned.
 *
 * @param context - is the context of what is to be read.
 * @return An instance of AccumuloMrsPyramidMetadataReader that is able to read from Accumulo.
 */
@Override
public MrsPyramidMetadataReader getMetadataReader(
    MrsPyramidMetadataReaderContext context)
{

  //TODO: get Authorizations for reading from the context

  // check if the metadata reader has been created
  if (metaReader == null)
  {
    metaReader = new AccumuloMrsPyramidMetadataReader(this, context);
  }

  return metaReader;
} // end getMetadataReader

/**
 * This class will return the class that write the metadata information to
 * the Accumulo table.
 *
 * @param context - is the context of what is to be written.
 * @return An instance of AccumuloMrsPyramidMetadataWriter that is able to write to Accumulo.
 */
@Override
public MrsPyramidMetadataWriter getMetadataWriter(
    MrsPyramidMetadataWriterContext context)
{

  // check to see if the metadata writer exists already
  if (metaWriter == null)
  {
    // get the metadata writer ready
    metaWriter = new AccumuloMrsPyramidMetadataWriter(this, context);
//    if(outFormatProvider != null && outFormatProvider.bulkJob()){
//
//      //TODO: think about working with file output - big jobs may need to work that way
//      metaWriter = new AccumuloMrsPyramidMetadataWriter(this, context);
//
//    } else {
//
//      // get the metadata writer ready
//      metaWriter = new AccumuloMrsPyramidMetadataWriter(this, context);
//
//    }
  }

  return metaWriter;
} // end getMetadataWriter

/**
 * This is the method to get a tile reader for Accumulo.
 *
 * @param context - is the context for reading tiles.
 * @return An instance of AccumuloMrsImageReader is returned.
 * @throws IOException if there is a problem connecting to or reading from Accumulo.
 */
@Override
public MrsImageReader getMrsTileReader(MrsPyramidReaderContext context)
    throws IOException
{
  return new AccumuloMrsImageReader(queryProps, this, context);
} // end getMrsTileReader

/**
 * This is the method to get a tile writer for Accumulo.
 *
 * @param context - is the context for writing tiles.
 * @return An instance of AccumuloMrsImageWriter.
 */
@Override
public MrsImageWriter getMrsTileWriter(MrsPyramidWriterContext context) throws IOException
{
  // get the protection level set within the metadata of the tabe
  return new AccumuloMrsImageWriter(this, context, context.getProtectionLevel());
} // end getMrsTileWriter

/**
 * This will instantiate a record reader that can be used in a map reduce job.
 *
 * @return An instance of a RecordReader that can pull from Accumulo and prepare the correct keys and values.
 */
@Override
public RecordReader<TileIdWritable, RasterWritable> getRecordReader()
{
  log.info("trying to load record reader.");

  return AccumuloMrsPyramidInputFormat.makeRecordReader();
} // end getRecordReader

/**
 * This is not implemented at this time.
 */
@Override
public RecordWriter<TileIdWritable, RasterWritable> getRecordWriter()
{
  log.info("failing to load record writer.");
  return null;
} // end getRecordWriter

/**
 * @return the column visibility of this instance
 */
public ColumnVisibility getColumnVisibility()
{

  if (cv != null)
  {
    return cv;
  }

  // set the cv if needed
  if (pl != null)
  {
    cv = new ColumnVisibility(pl);
  }
  else
  {
    cv = new ColumnVisibility();
  }

  return cv;
} // end getColumnVisibility

public Properties getQueryProperties()
{
  return queryProps;
}

private void setupConfig(final Configuration conf) throws DataProviderException
{
  Properties props = AccumuloConnector.getAccumuloProperties();
  conf.set(MrGeoAccumuloConstants.MRGEO_ACC_KEY_INSTANCE,
      props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_INSTANCE));
  conf.set(MrGeoAccumuloConstants.MRGEO_ACC_KEY_ZOOKEEPERS,
      props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_ZOOKEEPERS));
  // username and password
  conf.set(MrGeoAccumuloConstants.MRGEO_ACC_KEY_USER,
      props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_USER));

  // make sure the password is set with Base64Encoding
  //String pw = props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PASSWORD);
  String isEnc = props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PWENCODED64, "false");

  if (isEnc.equalsIgnoreCase("true"))
  {
    conf.set(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PASSWORD,
        props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PASSWORD));
  }
  else
  {
    try
    {
      String s = Base64Utils.encodeObject(props.getProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PASSWORD));

      conf.set(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PASSWORD, s);
      conf.set(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PWENCODED64, "true");
    }
    catch (IOException e)
    {
      throw new DataProviderException("Error Base64 encoding", e);
    }
  }
}


} // end AccumuloMrsImageDataProvider
