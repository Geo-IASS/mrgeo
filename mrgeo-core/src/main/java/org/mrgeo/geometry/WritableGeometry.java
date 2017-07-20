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

package org.mrgeo.geometry;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * @author jason.surratt
 */
public interface WritableGeometry extends Geometry
{
/**
 * Applies the point filter to every point in the geometry.
 *
 * @param pf
 */
void filter(PointFilter pf);

void setAttribute(String key, String value);

void setAttributes(Map<String, String> attrs);

void read(DataInputStream stream) throws IOException;

void readAttributes(DataInputStream stream) throws IOException;

}
