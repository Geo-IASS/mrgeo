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

package org.mrgeo.job;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "URF_UNREAD_FIELD", justification = "TODO:  This class needs to be expanded")
public class JobResults
{
private boolean started;
private boolean finished;
private boolean failed;
private String failureMessage;

public void starting()
{
  started = true;
}

public void succeeded()
{
  finished = true;
  failed = false;
}

public void failed(String failureMessage)
{
  finished = true;
  failed = true;
  this.failureMessage = failureMessage;
}
}
