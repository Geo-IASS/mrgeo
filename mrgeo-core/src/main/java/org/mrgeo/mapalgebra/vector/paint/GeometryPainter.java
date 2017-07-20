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

package org.mrgeo.mapalgebra.vector.paint;

import org.mrgeo.geometry.*;
import org.mrgeo.geometry.Point;
import org.mrgeo.geometry.Polygon;
import org.mrgeo.utils.FloatUtils;
import org.mrgeo.utils.tms.Bounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.WritableRaster;

/**
 * This class is not thread safe or re-entrant. The final is here to help out the compiler.
 *
 * @author jason.surratt
 */
public final class GeometryPainter
{
@SuppressWarnings("unused")
private static final Logger log = LoggerFactory.getLogger(GeometryPainter.class);

// Image bounds is in pixels and should not be confused with the world bounds.
// Rectangle bounds;
private Graphics2D gr;

private WritableRaster raster;

private Color fillColor;
private Color backgroundColor;
private AffineTransform transform = new AffineTransform();

public GeometryPainter(Graphics2D gr, WritableRaster raster, Color fillColor,
    Color backgroundColor)
{
  this.gr = gr;

  this.gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
  this.gr.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
  this.gr.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
  this.gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
      RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
  this.gr.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
      RenderingHints.VALUE_STROKE_NORMALIZE);

  this.raster = raster;

  this.fillColor = fillColor;
  this.backgroundColor = backgroundColor;
}

public Color getBackgroundColor()
{
  return backgroundColor;
}

public Color getFillColor()
{
  return fillColor;
}

public void setFillColor(Color color)
{
  fillColor = color;
}

public AffineTransform getTransform()
{
  return transform;
}

public void paint(Geometry g)
{
  if (g instanceof Polygon)
  {
    paint((Polygon) g);
  }
  else if (g instanceof Point)
  {
    paint((Point) g);
  }
  else if (g instanceof LineString)
  {
    paint((LineString) g);
  }
  else if (g instanceof GeometryCollection)
  {
    paint((GeometryCollection) g);
  }
  else
  {
    throw new IllegalArgumentException("Geometry type not implemented " + g.getClass().toString());
  }
}

public void paint(GeometryCollection gc)
{
  for (int i = 0; i < gc.getNumGeometries(); i++)
  {
    Geometry g = gc.getGeometry(i);
    paint(g);
  }
}

public void paint(LineString ls)
{
  Path2D.Double path = new Path2D.Double();
  Point2D.Double dst = new Point2D.Double();

  Point c = ls.getPoint(0);

  transform.transform(new Point2D.Double(c.getX(), c.getY()), dst);
  path.moveTo(dst.x, dst.y);

  // System.out.println("line x: " + c.x + " y: " + c.y + " xform: x: " + dst.x + " y: " +dst.y);

  for (int i = 1; i < ls.getNumPoints(); i++)
  {
    c = ls.getPoint(i);

    transform.transform(new Point2D.Double(c.getX(), c.getY()), dst);
    path.lineTo(dst.x, dst.y);

    // System.out.println("     x: " + c.x + " y: " + c.y + " xform: x: " + dst.x + " y: "
    // +dst.y);
  }

  gr.setColor(fillColor);
  gr.setStroke(new BasicStroke(1));
  gr.draw(path);

}

public void paint(Point p)
{
  gr.setColor(fillColor);
  gr.setStroke(new BasicStroke(1));

  Point2D.Double dst = new Point2D.Double();
  transform.transform(new Point2D.Double(p.getX(), p.getY()), dst);

  // System.out.println("p: x: " + p.getX() + " y: " + p.getY() + " xform: x: " + (int)dst.x +
  // " y: " +(int) dst.y);

  // need to make a "line" 1 pixel long
  gr.drawLine((int) dst.x, (int) dst.y, (int) dst.x, (int) dst.y);
}

public void paint(Polygon polygon)
{
  Path2D.Double path = new Path2D.Double();

  LineString ring = polygon.getExteriorRing();

  buildRing(path, ring);

  gr.setStroke(new BasicStroke(1));
  gr.setColor(fillColor);
  gr.fill(path);

  if (polygon.getNumInteriorRings() > 0)
  {
    gr.setColor(backgroundColor);
    for (int r = 0; r < polygon.getNumInteriorRings(); r++)
    {
      ring = polygon.getInteriorRing(r);
      path = new Path2D.Double();

      buildRing(path, ring);
      gr.fill(path);
    }
  }
}

public void paintRings(GeometryCollection gc)
{
  if (gc.getNumGeometries() != 2)
  {
    throw new IllegalArgumentException(
        "The Polygon does not have interior ring and exterior ring");
  }
  for (int i = 0; i < gc.getNumGeometries(); i++)
  {
    Geometry g = gc.getGeometry(i);
    if (g instanceof Polygon)
    {
      Path2D.Double path = new Path2D.Double();
      Polygon polygon = (Polygon) g;
      LineString ring = polygon.getExteriorRing();

      buildRing(path, ring);

      // draw exterior ring with white color
      if (i == 0)
      {
        gr.setColor(new Color(1, 1, 1));
        gr.fill(path);
      }
      else
      // then draw interior ring on the top of the exterior ring with black color
      {
        gr.setColor(new Color(0, 0, 0));
        gr.fill(path);
      }
    }
  }
}

public void paintEllipse(Point center, double major, double minor, double orientation)
{
  gr.setColor(fillColor);
  gr.setStroke(new BasicStroke(1));

  double width = major * transform.getScaleX();
  double height = minor * -transform.getScaleY();

  Point2D.Double dst = new Point2D.Double();

  transform.transform(new Point2D.Double(center.getX(), center.getY()), dst);

  if (!FloatUtils.isEqual(orientation, 0.0))
  {
    gr.rotate(-orientation, dst.getX(), dst.getY());
  }

  Ellipse2D.Double ellipse = new Ellipse2D.Double(dst.getX() - (width / 2), dst.getY() - (height / 2), width, height);
  gr.fill(ellipse);

  // rotate back
  if (!FloatUtils.isEqual(orientation, 0.0))
  {
    gr.rotate(orientation, dst.getX(), dst.getY());
  }
}

public void setBackGroundColor(Color color)
{
  backgroundColor = color;
}

/**
 * Set the real world boundary (e.g. lat/lng) of the image that is being painted. This
 * reconfigures the matrix of the graphics object to make painting in real coordinates work.
 */
public void setBounds(Bounds b)
{
  Rectangle r = raster.getBounds();

  double scaleX = r.getWidth() / b.width();
  double scaleY = r.getHeight() / b.height();

  double xlateX = -scaleX * b.w;
  double xlateY = scaleY * b.s + r.getHeight();

  // the -1 in scaleY mirrors the values, so 0,0 is the upper left corner, not the lower left.
  transform = new AffineTransform(scaleX, 0.0, 0.0, -scaleY, xlateX, xlateY);

  // System.out.println("xform: " + transform);
}

private void buildRing(Path2D.Double path, LineString ring)
{
  Point2D.Double dst = new Point2D.Double();

  Point c = ring.getPoint(0);

  transform.transform(new Point2D.Double(c.getX(), c.getY()), dst);
  path.moveTo(dst.x, dst.y);

  for (int i = 1; i < ring.getNumPoints(); i++)
  {
    c = ring.getPoint(i);
    transform.transform(new Point2D.Double(c.getX(), c.getY()), dst);
    path.lineTo(dst.x, dst.y);

  }

  path.closePath();
}
}
