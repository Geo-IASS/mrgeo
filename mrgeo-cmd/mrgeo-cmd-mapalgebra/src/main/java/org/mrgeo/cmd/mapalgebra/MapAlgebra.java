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

package org.mrgeo.cmd.mapalgebra;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.cli.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.mrgeo.aggregators.MeanAggregator;
import org.mrgeo.buildpyramid.BuildPyramid;
import org.mrgeo.cmd.Command;
import org.mrgeo.cmd.MrGeo;
import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.core.MrGeoProperties;
import org.mrgeo.data.DataProviderFactory;
import org.mrgeo.data.DataProviderFactory.AccessMode;
import org.mrgeo.data.ProtectionLevelUtils;
import org.mrgeo.data.ProviderProperties;
import org.mrgeo.data.image.MrsImageDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class MapAlgebra extends Command
{
private static Logger log = LoggerFactory.getLogger(MapAlgebra.class);

@Override
public String getUsage() { return "mapalgebra <options>"; }

@Override
public void addOptions(Options options)
{
  Option expression = new Option("e", "expression", true, "Expression to calculate");
  expression.setRequired(false);
  options.addOption(expression);

  Option output = new Option("o", "output", true, "Output path");
  output.setRequired(true);
  options.addOption(output);

  Option script = new Option("s", "script", true, "Path to the script to execute");
  script.setRequired(false);
  options.addOption(script);

  Option buildPyramids =
      new Option("b", "buildPyramids", false, "Build pyramids on the job output.");
  buildPyramids.setRequired(false);
  options.addOption(buildPyramids);

  Option protectionLevelOption = new Option("pl", "protectionLevel", true, "Protection level");
  // If mrgeo.conf security.classification.required is true and there is no
  // security.classification.default, then the security classification
  // argument is required, otherwise it is not.
  Properties props = MrGeoProperties.getInstance();
  String protectionLevelRequired = props.getProperty(
      MrGeoConstants.MRGEO_PROTECTION_LEVEL_REQUIRED, "false").trim();
  String protectionLevelDefault = props.getProperty(
      MrGeoConstants.MRGEO_PROTECTION_LEVEL_DEFAULT, "");
  if (protectionLevelRequired.equalsIgnoreCase("true") &&
      protectionLevelDefault.isEmpty())
  {
    protectionLevelOption.setRequired(true);
  }
  else
  {
    protectionLevelOption.setRequired(false);
  }
  options.addOption(protectionLevelOption);
}

@Override
@SuppressWarnings("squid:S1166") // Exception caught and error message printed
@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "File used for reading script")
public int run(final CommandLine line, final Configuration conf,
    final ProviderProperties providerProperties) throws ParseException
{
  System.out.println(log.getClass().getName());

  String expression = line.getOptionValue("e");
  String output = line.getOptionValue("o");
  String script = line.getOptionValue("s");

  if (expression == null && script == null)
  {
    throw new ParseException("Either an expression or script must be specified.");
  }

  try
  {
    if (script != null)
    {
      File f = new File(script);
      int total = (int) f.length();
      byte[] buffer = new byte[total];
      int read = 0;
      try (FileInputStream fis = new FileInputStream(f))
      {
        while (read < total)
        {
          read += fis.read(buffer, read, total - read);
        }
        expression = new String(buffer);
      }
    }

    String protectionLevel = line.getOptionValue("pl");

    log.debug("expression: " + expression);
    log.debug("output: " + output);

    Job job = new Job();
    job.setJobName("MapAlgebra");

    MrsImageDataProvider dp =
        DataProviderFactory.getMrsImageDataProvider(output, AccessMode.OVERWRITE, providerProperties);
    String useProtectionLevel = ProtectionLevelUtils.getAndValidateProtectionLevel(dp, protectionLevel);


    boolean valid = org.mrgeo.mapalgebra.MapAlgebra.validate(expression, providerProperties);
    if (valid)
    {
      if (org.mrgeo.mapalgebra.MapAlgebra.mapalgebra(expression, output, conf,
          providerProperties, useProtectionLevel))
      {
        if (line.hasOption("b"))
        {
          System.out.println("Building pyramids...");
          if (!BuildPyramid.build(output, new MeanAggregator(), conf, providerProperties))
          {
            System.out.println("Building pyramids failed. See YARN logs for more information.");
          }
        }
      }
    }
  }
  catch (IOException e)
  {
    System.out.println("Failure while running map algebra " + e.getMessage());
    return -1;
  }

  return 0;
}
}

