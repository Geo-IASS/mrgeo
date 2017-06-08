/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

package org.mrgeo.data.accumulo.image;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import junit.framework.Assert;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.hadoop.conf.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.accumulo.AccumuloDefs;
import org.mrgeo.data.accumulo.metadata.AccumuloMrsPyramidMetadataWriter;
import org.mrgeo.data.accumulo.utils.AccumuloConnector;
import org.mrgeo.data.accumulo.utils.MrGeoAccumuloConstants;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.mrgeo.image.MrsPyramidMetadata;
import org.mrgeo.image.MrsPyramidMetadata.Classification;
import org.mrgeo.junit.UnitTest;
import org.mrgeo.utils.HadoopUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

@SuppressWarnings("all") // Test code, not included in production
public class AccumuloMrsImageDataProviderFactoryTest
{

private static String junk = "junk";
private static String badTable = "badTable";
private static Connector conn = null;
private Configuration conf;
private File file;
private String originalFileStr;
private String originalMeta;
private AccumuloMrsImageDataProviderFactory factory;
private AccumuloMrsImageDataProvider provider;
private AccumuloMrsPyramidMetadataWriter writer;
private ProviderProperties providerProperties;

@BeforeClass
public static void setup()
{

} // end setup

@SuppressFBWarnings(value = "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD", justification = "Test")
@Before
public void init() throws Exception
{

  conf = HadoopUtils.createConfiguration();
  providerProperties = new ProviderProperties();
//    conn = AccumuloConnector.getMockConnector(AccumuloDefs.INSTANCE, AccumuloDefs.USER, AccumuloDefs.PASSWORDBLANK);
  conn = AccumuloConnector.getConnector();
  try
  {
    conn.tableOperations().create(junk);
  }
  catch (TableExistsException te)
  {

  }
  factory = new AccumuloMrsImageDataProviderFactory();

//    System.setProperty("mock", "true");
//    System.setProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_INSTANCE, AccumuloDefs.INSTANCE);
//    System.setProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_USER, AccumuloDefs.USER);
//    System.setProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_PASSWORD, AccumuloDefs.PASSWORDBLANK);
//    System.setProperty(MrGeoAccumuloConstants.MRGEO_ACC_KEY_ZOOKEEPERS, AccumuloDefs.ZOOKEEPERS);


  MrsPyramidMetadata metadata = new MrsPyramidMetadata();

  provider = new AccumuloMrsImageDataProvider(junk);

  String fstr =
      AccumuloDefs.CWD + AccumuloDefs.INPUTDIR + AccumuloDefs.INPUTMETADATADIR + AccumuloDefs.INPUTMETADATAFILE;
  file = new File(fstr);

  writer = (AccumuloMrsPyramidMetadataWriter) provider.getMetadataWriter();
  writer.setConnector(conn);

  //System.out.println(file.exists());

  FileInputStream fis = new FileInputStream(file);
  long length = file.length();
  byte[] b = new byte[(int) length];
  fis.read(b);

  originalFileStr = new String(b);
  //System.out.println(originalFileStr);
  fis.close();

  ByteArrayInputStream bis = new ByteArrayInputStream(b);
  metadata = MrsPyramidMetadata.load(bis);
  bis.close();

  ByteArrayOutputStream bos = new ByteArrayOutputStream();
  metadata.save(bos);
  originalMeta = new String(bos.toByteArray());
  bos.close();

  // set a couple values in meta.
  metadata.setClassification(Classification.Categorical);
  metadata.setMaxZoomLevel(100);
  metadata.setTilesize(100);
  writer.write(metadata);

} // end init

@After
public void teardown()
{
//    try{
//      conn.tableOperations().delete(junk);
//    } catch(Exception e){
//      e.printStackTrace();
//    }
} // end finalizeExternalSave


@Test
@Category(UnitTest.class)
public void testGetPrefix() throws Exception
{
  Assert.assertEquals("Bad prefix", "accumulo", factory.getPrefix());
} // end testGetPrefix

@Test
@Category(UnitTest.class)
public void testCreateMrsImageDataProvider() throws Exception
{

  String name = "bar";
  MrsImageDataProvider provider = factory.createMrsImageDataProvider(name, conf, providerProperties);
  Assert.assertNotNull("Provider not created!", provider);
  Assert.assertEquals("Name not set properly!", name, provider.getResourceName());

  String name2 = "foo";
  String name3 = MrGeoAccumuloConstants.MRGEO_ACC_PREFIX + name2;
  MrsImageDataProvider provider2 = factory.createMrsImageDataProvider(name3, conf, providerProperties);
  Assert.assertNotNull("Provider not created!", provider2);
  Assert.assertEquals("Name not set properly!", name2, provider2.getResourceName());

} // end testCreateMrsImageDataProvider

@Test
@Category(UnitTest.class)
public void testCanOpen() throws Exception
{
  String ds = MrGeoAccumuloConstants.MRGEO_ACC_PREFIX + junk;
  Assert.assertTrue("Can not open image!", factory.canOpen(ds, conf, providerProperties));
} // end testCanOpen


@Test
@Category(UnitTest.class)
public void testCanOpenMissing() throws Exception
{
  Assert.assertFalse("Can not open image!", factory.canOpen("missing", conf, providerProperties));
} // end testCanOpenMissing


@Test
@Category(UnitTest.class)
public void testCanOpenBadUri() throws Exception
{
  String bad = "abcd:bad-name";
  Assert.assertFalse("", factory.canOpen(bad, conf, providerProperties));
} // end testCanOpenBadUri


@Test(expected = NullPointerException.class)
@Category(UnitTest.class)
public void testCanOpenNull() throws Exception
{
  factory.canOpen(null, conf, providerProperties);
} // end testCanOpenNull


@Test
@Category(UnitTest.class)
public void testExists() throws Exception
{
  Assert.assertTrue("Can not open file!", factory.exists(junk, conf, providerProperties));
} // end testExists


} // end AccumuloMrsImageDataProviderFactoryTest
