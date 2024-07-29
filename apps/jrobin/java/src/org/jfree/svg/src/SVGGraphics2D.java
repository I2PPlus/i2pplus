/* ===================================================
 * JFreeSVG : an SVG library for the Java(tm) platform
 * ===================================================
 *
 * (C)opyright 2013-2020, by Object Refinery Limited.  All rights reserved.
 *
 * Project Info:  http://www.jfree.org/jfreesvg/index.html
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * [Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.]
 *
 * If you do not wish to be bound by the terms of the GPL, an alternative
 * commercial license can be purchased.  For details, please see visit the
 * JFreeSVG home page:
 *
 * http://www.jfree.org/jfreesvg
 */

package org.jfree.svg;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.AttributedCharacterIterator;
import java.text.AttributedCharacterIterator.Attribute;
import java.text.AttributedString;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.imageio.ImageIO;
import org.jfree.svg.util.Args;
import org.jfree.svg.util.GradientPaintKey;
import org.jfree.svg.util.GraphicsUtils;
import org.jfree.svg.util.LinearGradientPaintKey;
import org.jfree.svg.util.RadialGradientPaintKey;
import static org.jfree.svg.util.RyuDouble.doubleToString;

/**
 * <p>
 * A {@code Graphics2D} implementation that creates SVG output.  After
 * rendering the graphics via the {@code SVGGraphics2D}, you can retrieve
 * an SVG element (see {@link #getSVGElement()}) or an SVG document (see
 * {@link #getSVGDocument()}) containing your content.
 * </p>
 * <b>Usage</b><br>
 * <p>
 * Using the {@code SVGGraphics2D} class is straightforward.  First,
 * create an instance specifying the height and width of the SVG element that
 * will be created.  Then, use standard Java2D API calls to draw content
 * into the element.  Finally, retrieve the SVG element that has been
 * accumulated.  For example:
 * </p>
 * <pre>{@code SVGGraphics2D g2 = new SVGGraphics2D(300, 200);
 * g2.setPaint(Color.RED);
 * g2.draw(new Rectangle(10, 10, 280, 180));
 * String svgElement = g2.getSVGElement();}</pre>
 * <p>
 * For the content generation step, you can make use of third party libraries,
 * such as <a href="http://www.jfree.org/jfreechart/">JFreeChart</a> and
 * <a href="http://www.object-refinery.com/orsoncharts/">Orson Charts</a>, that
 * render output using standard Java2D API calls.
 * </p>
 * <b>Rendering Hints</b><br>
 * <p>
 * The {@code SVGGraphics2D} supports a couple of custom rendering hints -
 * for details, refer to the {@link SVGHints} class documentation.  Also see
 * the examples in this blog post:
 * <a href="http://www.object-refinery.com/blog/blog-20140509.html">
 * Orson Charts 3D / Enhanced SVG Export</a>.
 * </p>
 * <b>Other Notes</b><br>
 * Some additional notes:
 * <ul>
 * <li>Images are supported, but for methods with an {@code ImageObserver}
 * parameter note that the observer is ignored completely.  In any case, using
 * images that are not fully loaded already would not be a good idea in the
 * context of generating SVG data/files;</li>
 *
 * <li>the {@link #getFontMetrics(java.awt.Font)} and
 * {@link #getFontRenderContext()} methods return values that come from an
 * internal {@code BufferedImage}, this is a short-cut and we don't know
 * if there are any negative consequences (if you know of any, please let us
 * know and we'll add the info here or find a way to fix it);</li>
 *
 * <li>there are settings to control the number of decimal places used to
 * write the coordinates for geometrical elements (default 2dp) and transform
 * matrices (default 6dp).  These defaults may change in a future release.</li>
 *
 * <li>when an HTML page contains multiple SVG elements, the items within
 * the DEFS element for each SVG element must have IDs that are unique across
 * <em>all</em> SVG elements in the page.  We auto-populate the
 * {@code defsKeyPrefix} attribute to help ensure that unique IDs are
 * generated.</li>
 * </ul>
 *
 * <p>
 * For some demos showing how to use this class, look at the JFree-Demos project
 * at GitHub: https://github.com/jfree/jfree-demos.
 * </p>
 */
public final class SVGGraphics2D extends Graphics2D {

    /** The prefix for keys used to identify clip paths. */
    private static final String CLIP_KEY_PREFIX = "clip-";

    private final int width;

    private final int height;

    private final SVGUnits units;

    /**
     * The shape rendering property to set for the SVG element.  Permitted
     * values are "auto", "crispEdges", "geometricPrecision" and
     * "optimizeSpeed".
     */
    private String shapeRendering = "crispEdges";

    /**
     * The text rendering property for the SVG element.  Permitted values
     * are "auto", "optimizeSpeed", "optimizeLegibility" and
     * "geometricPrecision".
     */
    private String textRendering = "optimizeLegibility";

    /** The font size units. */
    private SVGUnits fontSizeUnits = SVGUnits.PX;

    /** Rendering hints (see SVGHints). */
    private final RenderingHints hints;

    /**
     * A flag that controls whether or not the KEY_STROKE_CONTROL hint is
     * checked.
     */
    private boolean checkStrokeControlHint = true;

    /**
     * The number of decimal places to use when writing the matrix values
     * for transformations.
     */
    private int transformDP = 6;

    /**
     * The number of decimal places to use when writing coordinates for
     * geometrical shapes.
     */
    private int geometryDP = 4;

    /** The buffer that accumulates the SVG output. */
    private final StringBuilder sb;

    /**
     * A prefix for the keys used in the DEFS element.  This can be used to
     * ensure that the keys are unique when creating more than one SVG element
     * for a single HTML page.
     */
    private String defsKeyPrefix = "";

    /**
     * A map of all the gradients used, and the corresponding id.  When
     * generating the SVG file, all the gradient paints used must be defined
     * in the defs element.
     */
    private Map<GradientPaintKey, String> gradientPaints = new HashMap<>();

    /**
     * A map of all the linear gradients used, and the corresponding id.  When
     * generating the SVG file, all the linear gradient paints used must be
     * defined in the defs element.
     */
    private Map<LinearGradientPaintKey, String> linearGradientPaints = new HashMap<>();

    /**
     * A map of all the radial gradients used, and the corresponding id.  When
     * generating the SVG file, all the radial gradient paints used must be
     * defined in the defs element.
     */
    private Map<RadialGradientPaintKey, String> radialGradientPaints = new HashMap<>();

    /**
     * A list of the registered clip regions.  These will be written to the
     * DEFS element.
     */
    private List<String> clipPaths = new ArrayList<>();

    /**
     * The filename prefix for images that are referenced rather than
     * embedded but don't have an {@code href} supplied via the
     * {@link SVGHints#KEY_IMAGE_HREF} hint.
     */
    private String filePrefix = "image-";

    /**
     * The filename suffix for images that are referenced rather than
     * embedded but don't have an {@code href} supplied via the
     * {@link SVGHints#KEY_IMAGE_HREF} hint.
     */
    private String fileSuffix = ".png";

    /**
     * A list of images that are referenced but not embedded in the SVG.
     * After the SVG is generated, the caller can make use of this list to
     * write PNG files if they don't already exist.
     */
    private List<ImageElement> imageElements;

    /** The user clip (can be null). */
    private Shape clip;

    /** The reference for the current clip. */
    private String clipRef;

    /** The current transform. */
    private AffineTransform transform = new AffineTransform();

    private Paint paint = Color.BLACK;

    private Color color = Color.BLACK;

    private Composite composite = AlphaComposite.getInstance(
            AlphaComposite.SRC_OVER, 1.0f);

    /** The current stroke. */
    private Stroke stroke = new BasicStroke(1.0f);

    /**
     * The width of the SVG stroke to use when the user supplies a
     * BasicStroke with a width of 0.0 (in this case the Java specification
     * says "If width is set to 0.0f, the stroke is rendered as the thinnest
     * possible line for the target device and the antialias hint setting.")
     */
    private double zeroStrokeWidth;

    /** The last font that was set. */
    private Font font = new Font("SansSerif", Font.PLAIN, 12);

    /**
     * The font render context.  The fractional metrics flag solves the glyph
     * positioning issue identified by Christoph Nahr:
     * http://news.kynosarges.org/2014/06/28/glyph-positioning-in-jfreesvg-orsonpdf/
     */
    private final FontRenderContext fontRenderContext = new FontRenderContext(
            null, false, true);

    /** Maps font family names to alternates (or leaves them unchanged). */
    private FontMapper fontMapper;

    /** The background color, used by clearRect(). */
    private Color background = Color.BLACK;

    /** A hidden image used for font metrics. */
    private BufferedImage fmImage;

    private Graphics2D fmImageG2D;

    /**
     * An instance that is lazily instantiated in drawLine and then
     * subsequently reused to avoid creating a lot of garbage.
     */
    private Line2D line;

    /**
     * An instance that is lazily instantiated in fillRect and then
     * subsequently reused to avoid creating a lot of garbage.
     */
    Rectangle2D rect;

    /**
     * An instance that is lazily instantiated in draw/fillRoundRect and then
     * subsequently reused to avoid creating a lot of garbage.
     */
    private RoundRectangle2D roundRect;

    /**
     * An instance that is lazily instantiated in draw/fillOval and then
     * subsequently reused to avoid creating a lot of garbage.
     */
    private Ellipse2D oval;

    /**
     * An instance that is lazily instantiated in draw/fillArc and then
     * subsequently reused to avoid creating a lot of garbage.
     */
    private Arc2D arc;

    /**
     * If the current paint is an instance of {@link GradientPaint}, this
     * field will contain the reference id that is used in the DEFS element
     * for that linear gradient.
     */
    private String gradientPaintRef = null;

    /**
     * The device configuration (this is lazily instantiated in the
     * getDeviceConfiguration() method).
     */
    private GraphicsConfiguration deviceConfiguration;

    /** A set of element IDs. */
    private final Set<String> elementIDs;

    /**
     * Creates a new instance with the specified width and height.
     *
     * @param width  the width of the SVG element.
     * @param height  the height of the SVG element.
     */
    public SVGGraphics2D(int width, int height) {
        this(width, height, null, new StringBuilder());
    }

    /**
     * Creates a new instance with the specified width and height in the given
     * units.
     *
     * @param width  the width of the SVG element.
     * @param height  the height of the SVG element.
     * @param units  the units for the width and height.
     *
     * @since 3.2
     */
    public SVGGraphics2D(int width, int height, SVGUnits units) {
        this(width, height, units, new StringBuilder());
    }

    /**
     * Creates a new instance with the specified width and height that will
     * populate the supplied StringBuilder instance.  This constructor is
     * used by the {@link #create()} method, but won't normally be called
     * directly by user code.
     *
     * @param width  the width of the SVG element.
     * @param height  the height of the SVG element.
     * @param sb  the string builder ({@code null} not permitted).
     *
     * @since 2.0
     */
    public SVGGraphics2D(int width, int height, StringBuilder sb) {
        this(width, height, null, sb);
    }

    /**
     * Creates a new instance with the specified width and height that will
     * populate the supplied StringBuilder instance.  This constructor is
     * used by the {@link #create()} method, but won't normally be called
     * directly by user code.
     *
     * @param width  the width of the SVG element.
     * @param height  the height of the SVG element.
     * @param units  the units for the width and height above ({@code null}
     *     permitted).
     * @param sb  the string builder ({@code null} not permitted).
     *
     * @since 3.2
     */
    public SVGGraphics2D(int width, int height, SVGUnits units, StringBuilder sb) {
        this.width = width;
        this.height = height;
        this.units = units;
        this.imageElements = new ArrayList<>();
        this.fontMapper = new StandardFontMapper();
        this.zeroStrokeWidth = 0.1;
        this.sb = sb;
        this.hints = new RenderingHints(SVGHints.KEY_IMAGE_HANDLING, SVGHints.VALUE_IMAGE_HANDLING_EMBED);
        this.elementIDs = new HashSet<>();
    }

    /**
     * Creates a new instance that is a child of the supplied parent.
     *
     * @param parent  the parent ({@code null} not permitted).
     */
    private SVGGraphics2D(final SVGGraphics2D parent) {
        this(parent.width, parent.height, parent.units, parent.sb);
        this.shapeRendering = parent.shapeRendering;
        this.textRendering = parent.textRendering;
        this.fontMapper = parent.fontMapper;
        getRenderingHints().add(parent.hints);
        this.checkStrokeControlHint = parent.checkStrokeControlHint;
        setTransformDP(parent.transformDP);
        setGeometryDP(parent.geometryDP);
        this.defsKeyPrefix = parent.defsKeyPrefix;
        this.gradientPaints = parent.gradientPaints;
        this.linearGradientPaints = parent.linearGradientPaints;
        this.radialGradientPaints = parent.radialGradientPaints;
        this.clipPaths = parent.clipPaths;
        this.filePrefix = parent.filePrefix;
        this.fileSuffix = parent.fileSuffix;
        this.imageElements = parent.imageElements;
        this.zeroStrokeWidth = parent.zeroStrokeWidth;
    }

    /**
     * Returns the width for the SVG element, specified in the constructor.
     * This value will be written to the SVG element returned by the
     * {@link #getSVGElement()} method.
     *
     * @return The width for the SVG element.
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * Returns the height for the SVG element, specified in the constructor.
     * This value will be written to the SVG element returned by the
     * {@link #getSVGElement()} method.
     *
     * @return The height for the SVG element.
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * Returns the units for the width and height of the SVG element's
     * viewport, as specified in the constructor.  The default value is
     * {@code null}).
     *
     * @return The units (possibly {@code null}).
     *
     * @since 3.2
     */
    public SVGUnits getUnits() {
        return this.units;
    }

    /**
     * Returns the value of the 'shape-rendering' property that will be
     * written to the SVG element.  The default value is "auto".
     *
     * @return The shape rendering property.
     *
     * @since 2.0
     */
    public String getShapeRendering() {
        return this.shapeRendering;
    }

    /**
     * Sets the value of the 'shape-rendering' property that will be written to
     * the SVG element.  Permitted values are "auto", "crispEdges",
     * "geometricPrecision", "inherit" and "optimizeSpeed".
     *
     * @param value  the new value.
     *
     * @since 2.0
     */
    public void setShapeRendering(String value) {
        if (!value.equals("auto") && !value.equals("crispEdges")
                && !value.equals("geometricPrecision")
                && !value.equals("optimizeSpeed")) {
            throw new IllegalArgumentException("Unrecognised value: " + value);
        }
        this.shapeRendering = value;
    }

    /**
     * Returns the value of the 'text-rendering' property that will be
     * written to the SVG element.  The default value is "auto".
     *
     * @return The text rendering property.
     *
     * @since 2.0
     */
    public String getTextRendering() {
        return this.textRendering;
    }

    /**
     * Sets the value of the 'text-rendering' property that will be written to
     * the SVG element.  Permitted values are "auto", "optimizeSpeed",
     * "optimizeLegibility" and "geometricPrecision".
     *
     * @param value  the new value.
     *
     * @since 2.0
     */
    public void setTextRendering(String value) {
        if (!value.equals("auto") && !value.equals("optimizeSpeed")
                && !value.equals("optimizeLegibility")
                && !value.equals("geometricPrecision")) {
            throw new IllegalArgumentException("Unrecognised value: " + value);
        }
        this.textRendering = value;
    }

    /**
     * Returns the flag that controls whether or not this object will observe
     * the {@code KEY_STROKE_CONTROL} rendering hint.  The default value is
     * {@code true}.
     *
     * @return A boolean.
     *
     * @see #setCheckStrokeControlHint(boolean)
     * @since 2.0
     */
    public boolean getCheckStrokeControlHint() {
        return this.checkStrokeControlHint;
    }

    /**
     * Sets the flag that controls whether or not this object will observe
     * the {@code KEY_STROKE_CONTROL} rendering hint.  When enabled (the
     * default), a hint to normalise strokes will write a {@code stroke-style}
     * attribute with the value {@code crispEdges}.
     *
     * @param check  the new flag value.
     *
     * @see #getCheckStrokeControlHint()
     * @since 2.0
     */
    public void setCheckStrokeControlHint(boolean check) {
        this.checkStrokeControlHint = check;
    }

    /**
     * Returns the prefix used for all keys in the DEFS element.  The default
     * value is {@code "_"+ String.valueOf(System.nanoTime())}.
     *
     * @return The prefix string (never {@code null}).
     *
     * @since 1.9
     */
    public String getDefsKeyPrefix() {
        return this.defsKeyPrefix;
    }

    /**
     * Sets the prefix that will be used for all keys in the DEFS element.
     * If required, this must be set immediately after construction (before any
     * content generation methods have been called).
     *
     * @param prefix  the prefix ({@code null} not permitted).
     *
     * @since 1.9
     */
    public void setDefsKeyPrefix(String prefix) {
        Args.nullNotPermitted(prefix, "prefix");
        this.defsKeyPrefix = prefix;
    }

    /**
     * Returns the number of decimal places used to write the transformation
     * matrices in the SVG output.  The default value is 6.
     * <p>
     * Note that there is a separate attribute to control the number of decimal
     * places for geometrical elements in the output (see
     * {@link #getGeometryDP()}).
     *
     * @return The number of decimal places.
     *
     * @see #setTransformDP(int)
     */
    public int getTransformDP() {
        return this.transformDP;
    }

    /**
     * Sets the number of decimal places used to write the transformation
     * matrices in the SVG output.  Values in the range 1 to 10 will be used
     * to configure a formatter to that number of decimal places, for all other
     * values we revert to the normal {@code String} conversion of
     * {@code double} primitives (approximately 16 decimals places).
     * <p>
     * Note that there is a separate attribute to control the number of decimal
     * places for geometrical elements in the output (see
     * {@link #setGeometryDP(int)}).
     *
     * @param dp  the number of decimal places (normally 1 to 10).
     *
     * @see #getTransformDP()
     */
    public void setTransformDP(final int dp) {this.transformDP = dp;}

    /**
     * Returns the number of decimal places used to write the coordinates
     * of geometrical shapes.  The default value is 2.
     * <p>
     * Note that there is a separate attribute to control the number of decimal
     * places for transform matrices in the output (see
     * {@link #getTransformDP()}).
     *
     * @return The number of decimal places.
     */
    public int getGeometryDP() {return this.geometryDP;}

    /**
     * Sets the number of decimal places used to write the coordinates of
     * geometrical shapes in the SVG output.  Values in the range 1 to 10 will
     * be used to configure a formatter to that number of decimal places, for
     * all other values we revert to the normal String conversion of double
     * primitives (approximately 16 decimals places).
     * <p>
     * Note that there is a separate attribute to control the number of decimal
     * places for transform matrices in the output (see
     * {@link #setTransformDP(int)}).
     *
     * @param dp  the number of decimal places (normally 1 to 10).
     */
    public void setGeometryDP(final int dp) {
        this.geometryDP = dp;
    }

    /**
     * Returns the prefix used to generate a filename for an image that is
     * referenced from, rather than embedded in, the SVG element.
     *
     * @return The file prefix (never {@code null}).
     *
     * @since 1.5
     */
    public String getFilePrefix() {
        return this.filePrefix;
    }

    /**
     * Sets the prefix used to generate a filename for any image that is
     * referenced from the SVG element.
     *
     * @param prefix  the new prefix ({@code null} not permitted).
     *
     * @since 1.5
     */
    public void setFilePrefix(String prefix) {
        Args.nullNotPermitted(prefix, "prefix");
        this.filePrefix = prefix;
    }

    /**
     * Returns the suffix used to generate a filename for an image that is
     * referenced from, rather than embedded in, the SVG element.
     *
     * @return The file suffix (never {@code null}).
     *
     * @since 1.5
     */
    public String getFileSuffix() {
        return this.fileSuffix;
    }

    /**
     * Sets the suffix used to generate a filename for any image that is
     * referenced from the SVG element.
     *
     * @param suffix  the new prefix ({@code null} not permitted).
     *
     * @since 1.5
     */
    public void setFileSuffix(String suffix) {
        Args.nullNotPermitted(suffix, "suffix");
        this.fileSuffix = suffix;
    }

    /**
     * Returns the width to use for the SVG stroke when the AWT stroke
     * specified has a zero width (the default value is {@code 0.1}).  In
     * the Java specification for {@code BasicStroke} it states "If width
     * is set to 0.0f, the stroke is rendered as the thinnest possible
     * line for the target device and the antialias hint setting."  We don't
     * have a means to implement that accurately since we must specify a fixed
     * width.
     *
     * @return The width.
     *
     * @since 1.9
     */
    public double getZeroStrokeWidth() {
        return this.zeroStrokeWidth;
    }

    /**
     * Sets the width to use for the SVG stroke when the current AWT stroke
     * has a width of 0.0.
     *
     * @param width  the new width (must be 0 or greater).
     *
     * @since 1.9
     */
    public void setZeroStrokeWidth(double width) {
        if (width < 0.0) {
            throw new IllegalArgumentException("Width cannot be negative.");
        }
        this.zeroStrokeWidth = width;
    }

    /**
     * Returns the device configuration associated with this
     * {@code Graphics2D}.
     *
     * @return The graphics configuration.
     */
    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        if (this.deviceConfiguration == null) {
            this.deviceConfiguration = new SVGGraphicsConfiguration(this.width,
                    this.height);
        }
        return this.deviceConfiguration;
    }

    /**
     * Creates a new graphics object that is a copy of this graphics object
     * (except that it has not accumulated the drawing operations).  Not sure
     * yet when or why this would be useful when creating SVG output.  Note
     * that the {@code fontMapper} object ({@link #getFontMapper()}) is shared
     * between the existing instance and the new one.
     *
     * @return A new graphics object.
     */
    @Override
    public Graphics create() {
        SVGGraphics2D copy = new SVGGraphics2D(this);
        copy.setRenderingHints(getRenderingHints());
        copy.setTransform(getTransform());
        copy.setClip(getClip());
        copy.setPaint(getPaint());
        copy.setColor(getColor());
        copy.setComposite(getComposite());
        copy.setStroke(getStroke());
        copy.setFont(getFont());
        copy.setBackground(getBackground());
        copy.setFilePrefix(getFilePrefix());
        copy.setFileSuffix(getFileSuffix());
        return copy;
    }

    /**
     * Returns the paint used to draw or fill shapes (or text).  The default
     * value is {@link Color#BLACK}.
     *
     * @return The paint (never {@code null}).
     *
     * @see #setPaint(java.awt.Paint)
     */
    @Override
    public Paint getPaint() {
        return this.paint;
    }

    /**
     * Sets the paint used to draw or fill shapes (or text).  If
     * {@code paint} is an instance of {@code Color}, this method will
     * also update the current color attribute (see {@link #getColor()}). If
     * you pass {@code null} to this method, it does nothing (in
     * accordance with the JDK specification).
     *
     * @param paint  the paint ({@code null} is permitted but ignored).
     *
     * @see #getPaint()
     */
    @Override
    public void setPaint(Paint paint) {
        if (paint == null) {
            return;
        }
        this.paint = paint;
        this.gradientPaintRef = null;
        if (paint instanceof Color) {
            setColor((Color) paint);
        } else if (paint instanceof GradientPaint) {
            GradientPaint gp = (GradientPaint) paint;
            GradientPaintKey key = new GradientPaintKey(gp);
            String ref = this.gradientPaints.get(key);
            if (ref == null) {
                int count = this.gradientPaints.keySet().size();
                String id = this.defsKeyPrefix + "gp" + count;
                this.elementIDs.add(id);
                this.gradientPaints.put(key, id);
                this.gradientPaintRef = id;
            } else {
                this.gradientPaintRef = ref;
            }
        } else if (paint instanceof LinearGradientPaint) {
            LinearGradientPaint lgp = (LinearGradientPaint) paint;
            LinearGradientPaintKey key = new LinearGradientPaintKey(lgp);
            String ref = this.linearGradientPaints.get(key);
            if (ref == null) {
                int count = this.linearGradientPaints.keySet().size();
                String id = this.defsKeyPrefix + "lgp" + count;
                this.elementIDs.add(id);
                this.linearGradientPaints.put(key, id);
                this.gradientPaintRef = id;
            }
        } else if (paint instanceof RadialGradientPaint) {
            RadialGradientPaint rgp = (RadialGradientPaint) paint;
            RadialGradientPaintKey key = new RadialGradientPaintKey(rgp);
            String ref = this.radialGradientPaints.get(key);
            if (ref == null) {
                int count = this.radialGradientPaints.keySet().size();
                String id = this.defsKeyPrefix + "rgp" + count;
                this.elementIDs.add(id);
                this.radialGradientPaints.put(key, id);
                this.gradientPaintRef = id;
            }
        }
    }

    /**
     * Returns the foreground color.  This method exists for backwards
     * compatibility in AWT, you should use the {@link #getPaint()} method.
     *
     * @return The foreground color (never {@code null}).
     *
     * @see #getPaint()
     */
    @Override
    public Color getColor() {
        return this.color;
    }

    /**
     * Sets the foreground color.  This method exists for backwards
     * compatibility in AWT, you should use the
     * {@link #setPaint(java.awt.Paint)} method.
     *
     * @param c  the color ({@code null} permitted but ignored).
     *
     * @see #setPaint(java.awt.Paint)
     */
    @Override
    public void setColor(Color c) {
        if (c == null) {
            return;
        }
        this.color = c;
        this.paint = c;
    }

    /**
     * Returns the background color.  The default value is {@link Color#BLACK}.
     * This is used by the {@link #clearRect(int, int, int, int)} method.
     *
     * @return The background color (possibly {@code null}).
     *
     * @see #setBackground(java.awt.Color)
     */
    @Override
    public Color getBackground() {
        return this.background;
    }

    /**
     * Sets the background color.  This is used by the
     * {@link #clearRect(int, int, int, int)} method.  The reference
     * implementation allows {@code null} for the background color so
     * we allow that too (but for that case, the clearRect method will do
     * nothing).
     *
     * @param color  the color ({@code null} permitted).
     *
     * @see #getBackground()
     */
    @Override
    public void setBackground(Color color) {
        this.background = color;
    }

    /**
     * Returns the current composite.
     *
     * @return The current composite (never {@code null}).
     *
     * @see #setComposite(java.awt.Composite)
     */
    @Override
    public Composite getComposite() {
        return this.composite;
    }

    /**
     * Sets the composite (only {@code AlphaComposite} is handled).
     *
     * @param comp  the composite ({@code null} not permitted).
     *
     * @see #getComposite()
     */
    @Override
    public void setComposite(Composite comp) {
        if (comp == null) {
            throw new IllegalArgumentException("Null 'comp' argument.");
        }
        this.composite = comp;
    }

    /**
     * Returns the current stroke (used when drawing shapes).
     *
     * @return The current stroke (never {@code null}).
     *
     * @see #setStroke(java.awt.Stroke)
     */
    @Override
    public Stroke getStroke() {
        return this.stroke;
    }

    /**
     * Sets the stroke that will be used to draw shapes.
     *
     * @param s  the stroke ({@code null} not permitted).
     *
     * @see #getStroke()
     */
    @Override
    public void setStroke(Stroke s) {
        if (s == null) {
            throw new IllegalArgumentException("Null 's' argument.");
        }
        this.stroke = s;
    }

    /**
     * Returns the current value for the specified hint.  See the
     * {@link SVGHints} class for information about the hints that can be
     * used with {@code SVGGraphics2D}.
     *
     * @param hintKey  the hint key ({@code null} permitted, but the
     *     result will be {@code null} also).
     *
     * @return The current value for the specified hint
     *     (possibly {@code null}).
     *
     * @see #setRenderingHint(java.awt.RenderingHints.Key, java.lang.Object)
     */
    @Override
    public Object getRenderingHint(RenderingHints.Key hintKey) {
        return this.hints.get(hintKey);
    }

    /**
     * Sets the value for a hint.  See the {@link SVGHints} class for
     * information about the hints that can be used with this implementation.
     *
     * @param hintKey  the hint key ({@code null} not permitted).
     * @param hintValue  the hint value.
     *
     * @see #getRenderingHint(java.awt.RenderingHints.Key)
     */
    @Override
    public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) {
        if (hintKey == null) {
            throw new NullPointerException("Null 'hintKey' not permitted.");
        }
        // KEY_BEGIN_GROUP and KEY_END_GROUP are handled as special cases that
        // never get stored in the hints map...
        if (SVGHints.isBeginGroupKey(hintKey)) {
            String groupId = null;
            String ref = null;
            List<Entry> otherKeysAndValues = null;
            if (hintValue instanceof String) {
                groupId = (String) hintValue;
             } else if (hintValue instanceof Map) {
                Map hintValueMap = (Map) hintValue;
                groupId = (String) hintValueMap.get("id");
                ref = (String) hintValueMap.get("ref");
                for (final Object obj: hintValueMap.entrySet()) {
                   final Entry e = (Entry) obj;
                   final Object key = e.getKey();
                   if ("id".equals(key) || "ref".equals(key)) {continue;}
                   if (otherKeysAndValues == null) {otherKeysAndValues = new ArrayList<>();}
                   otherKeysAndValues.add(e);
                }
            }
            this.sb.append("<g");
            if (groupId != null) {
                if (this.elementIDs.contains(groupId)) {
                    throw new IllegalArgumentException("The group id (" + groupId + ") is not unique.");
                } else {
                    this.sb.append(" id=\"").append(groupId).append("\"");
                    this.elementIDs.add(groupId);
                }
            }
            if (ref != null) {
                this.sb.append(" jfreesvg:ref=\"");
                this.sb.append(SVGUtils.escapeForXML(ref)).append("\"");
            }
            if (otherKeysAndValues != null) {
               for (final Entry e: otherKeysAndValues) {
                    this.sb.append(" ").append(e.getKey()).append("=\"")
                           .append(SVGUtils.escapeForXML(String.valueOf(e.getValue()))).append("\"");
               }
            }
            this.sb.append(">");
        } else if (SVGHints.isEndGroupKey(hintKey)) {
            this.sb.append("</g>\n");
        } else if (SVGHints.isElementTitleKey(hintKey) && (hintValue != null)) {
            this.sb.append("<title>").append(SVGUtils.escapeForXML(String.valueOf(hintValue))).append("</title>");
        } else {
            this.hints.put(hintKey, hintValue);
        }
    }

    /**
     * Returns a copy of the rendering hints.  Modifying the returned copy
     * will have no impact on the state of this {@code Graphics2D} instance.
     *
     * @return The rendering hints (never {@code null}).
     *
     * @see #setRenderingHints(java.util.Map)
     */
    @Override
    public RenderingHints getRenderingHints() {
        return (RenderingHints) this.hints.clone();
    }

    /**
     * Sets the rendering hints to the specified collection.
     *
     * @param hints  the new set of hints ({@code null} not permitted).
     *
     * @see #getRenderingHints()
     */
    @Override
    public void setRenderingHints(Map<?, ?> hints) {
        this.hints.clear();
        addRenderingHints(hints);
    }

    /**
     * Adds all the supplied rendering hints.
     *
     * @param hints  the hints ({@code null} not permitted).
     */
    @Override
    public void addRenderingHints(Map<?, ?> hints) {
        this.hints.putAll(hints);
    }

    /**
     * A utility method that appends an optional element id if one is
     * specified via the rendering hints.
     *
     * @param sb  the string builder ({@code null} not permitted).
     */
    private void appendOptionalElementIDFromHint(StringBuilder sb) {
        String elementID = (String) this.hints.get(SVGHints.KEY_ELEMENT_ID);
        if (elementID != null) {
            this.hints.put(SVGHints.KEY_ELEMENT_ID, null); // clear it
            if (this.elementIDs.contains(elementID)) {
                throw new IllegalStateException("The element id " + elementID + " is already used.");
            } else {
                this.elementIDs.add(elementID);
            }
            this.sb.append("id=\"").append(elementID).append("\" ");
        }
    }

    /**
     * Draws the specified shape with the current {@code paint} and
     * {@code stroke}.  There is direct handling for {@code Line2D},
     * {@code Rectangle2D}, {@code Ellipse2D} and {@code Path2D}.  All other
     * shapes are mapped to a {@code GeneralPath} and then drawn (effectively
     * as {@code Path2D} objects).
     *
     * @param s  the shape ({@code null} not permitted).
     *
     * @see #fill(java.awt.Shape)
     */
    @Override
    public void draw(Shape s) {
        // if the current stroke is not a BasicStroke then it is handled as
        // a special case
        if (!(this.stroke instanceof BasicStroke)) {
            fill(this.stroke.createStrokedShape(s));
            return;
        }
        if (s instanceof Line2D) {
            Line2D l = (Line2D) s;
            String x1 = geomDP(l.getX1());
            String x2 = geomDP(l.getX2());
            String y1 = geomDP(l.getY1());
            String y2 = geomDP(l.getY2());
            if (!strokeStyle().contains("stroke-opacity:0;") &&
                !strokeStyle().contains("stroke-width:0.1;")) {
                this.sb.append("<line ");
                appendOptionalElementIDFromHint(this.sb);
                this.sb.append("x1=\"").append(x1).append("\" y1=\"").append(y1)
                       .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
                       .append("\" style=\"").append(strokeStyle()).append("\" ");
                if (!this.transform.isIdentity()) {
                   this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
                }
                this.sb.append(getClipPathRef()).append("/>");
            }
        } else if (s instanceof Rectangle2D) {
            Rectangle2D r = (Rectangle2D) s;
            if (!strokeStyle().contains("fill-opacity:0\"")) {
                this.sb.append("<rect ");
                appendOptionalElementIDFromHint(this.sb);
                this.sb.append("x=\"").append(geomDP(r.getX()))
                       .append("\" y=\"").append(geomDP(r.getY()))
                       .append("\" width=\"").append(geomDP(r.getWidth()))
                       .append("\" height=\"").append(geomDP(r.getHeight()))
                       .append("\" ")
                       .append("style=\"").append(strokeStyle())
                       .append("fill:none").append("\" ");
                if (!this.transform.isIdentity()) {
                    this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
                }
                this.sb.append(getClipPathRef());
                this.sb.append("/>");
            }
        } else if (s instanceof Ellipse2D) {
            Ellipse2D e = (Ellipse2D) s;
            this.sb.append("<ellipse ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("cx=\"").append(geomDP(e.getCenterX()))
                   .append("\" cy=\"").append(geomDP(e.getCenterY()))
                   .append("\" rx=\"").append(geomDP(e.getWidth() / 2.0))
                   .append("\" ry=\"").append(geomDP(e.getHeight() / 2.0))
                   .append("\" ")
                   .append("style=\"").append(strokeStyle())
                   .append("fill:none").append("\" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append(getClipPathRef());
            this.sb.append("/>");
        } else if (s instanceof Path2D) {
            Path2D path = (Path2D) s;
            this.sb.append("<g ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("style=\"").append(strokeStyle()).append("fill:none").append("\" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append(getClipPathRef()).append(">").append("<path ").append(getSVGPathData(path))
                   .append("/>").append("</g>");
        } else {
            draw(new GeneralPath(s)); // handled as a Path2D next time through
        }
    }

    /**
     * Fills the specified shape with the current {@code paint}.  There is
     * direct handling for {@code Rectangle2D}, {@code Ellipse2D} and
     * {@code Path2D}.  All other shapes are mapped to a {@code GeneralPath}
     * and then filled.
     *
     * @param s  the shape ({@code null} not permitted).
     *
     * @see #draw(java.awt.Shape)
     */
    @Override
    public void fill(Shape s) {
        if (s instanceof Rectangle2D) {
            Rectangle2D r = (Rectangle2D) s;
            if (r.isEmpty()) {return;}
            this.sb.append("<rect ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("x=\"").append(geomDP(r.getX()))
                   .append("\" y=\"").append(geomDP(r.getY()))
                   .append("\" width=\"").append(geomDP(r.getWidth()))
                   .append("\" height=\"").append(geomDP(r.getHeight()))
                   .append("\" ");
            this.sb.append("style=\"").append(getSVGFillStyle()).append("\" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append(getClipPathRef()).append("/>");
        } else if (s instanceof Ellipse2D) {
            Ellipse2D e = (Ellipse2D) s;
            this.sb.append("<ellipse ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("cx=\"").append(geomDP(e.getCenterX()))
                   .append("\" cy=\"").append(geomDP(e.getCenterY()))
                   .append("\" rx=\"").append(geomDP(e.getWidth() / 2.0))
                   .append("\" ry=\"").append(geomDP(e.getHeight() / 2.0))
                   .append("\" ");
            this.sb.append("style=\"").append(getSVGFillStyle()).append("\" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append(getClipPathRef());
            this.sb.append("/>");
        } else if (s instanceof Path2D) {
            Path2D path = (Path2D) s;
            this.sb.append("<g ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("style=\"").append(getSVGFillStyle()).append(";stroke:none").append("\" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append(getClipPathRef()).append(">").append("<path ").append(getSVGPathData(path))
                   .append("/>").append("</g>");
        }  else {
            fill(new GeneralPath(s));  // handled as a Path2D next time through
        }
    }

    /**
     * Creates an SVG path string for the supplied Java2D path.
     *
     * @param path  the path ({@code null} not permitted).
     *
     * @return An SVG path string.
     */

    private String getSVGPathData(Path2D path) {
        StringBuilder b = new StringBuilder("d=\"");
        float[] coords = new float[6];
        boolean first = true;
        float prevX = 0;
        float prevY = 0;
        PathIterator iterator = path.getPathIterator(null);
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coords);
            switch (type) {
            case (PathIterator.SEG_MOVETO):
                if (first) {
                    b.append("M").append((int)coords[0]).append(" ").append((int)coords[1]);
                    prevX = coords[0];
                    prevY = coords[1];
                    first = false;
                } else {
                    b.append("m").append((int)(coords[0] - prevX)).append(" ").append((int)(coords[1] - prevY));
                    prevX = coords[0];
                    prevY = coords[1];
                }
                break;
            case (PathIterator.SEG_LINETO):
                b.append("l").append((int)(coords[0] - prevX)).append(" ").append((int)(coords[1] - prevY));
                prevX = coords[0];
                prevY = coords[1];
                break;
            case (PathIterator.SEG_QUADTO):
                b.append("q").append((int)(coords[0] - prevX))
                 .append(" ").append((int)(coords[1] - prevY))
                 .append(" ").append((int)(coords[2] - coords[0]))
                 .append(" ").append((int)(coords[3] - coords[1]));
                prevX = coords[2];
                prevY = coords[3];
                break;
            case (PathIterator.SEG_CUBICTO):
                b.append("c").append((int)(coords[0] - prevX)).append(" ")
                 .append((int)(coords[1] - prevY)).append(" ")
                 .append((int)(coords[2] - coords[0])).append(" ")
                 .append((int)(coords[3] - coords[1])).append(" ")
                 .append((int)(coords[4] - coords[2])).append(" ")
                 .append((int)(coords[5] - coords[3]));
                prevX = coords[4];
                prevY = coords[5];
                break;
            case (PathIterator.SEG_CLOSE):
                if (!first) {
                    b.append("L").append((int)coords[0]).append(" ").append((int)coords[1]);
                }
                b.append("Z");
                break;
            default:
                break;
            }
            iterator.next();
        }
        return b.append("\"").toString();
    }

    /**
     * Returns the current alpha (transparency) in the range 0.0 to 1.0.
     * If the current composite is an {@link AlphaComposite} we read the alpha
     * value from there, otherwise this method returns 1.0.
     *
     * @return The current alpha (transparency) in the range 0.0 to 1.0.
     */
    private float getAlpha() {
       float alpha = 1.0f;
       if (this.composite instanceof AlphaComposite) {
           AlphaComposite ac = (AlphaComposite) this.composite;
           alpha = ac.getAlpha();
       }
       return alpha;
    }

    /**
     * Returns an SVG color string based on the current paint.  To handle
     * {@code GradientPaint} we rely on the {@code setPaint()} method
     * having set the {@code gradientPaintRef} attribute.
     *
     * @return An SVG color string.
     */
    private String svgColorStr() {
        String result = "black;";
        if (this.paint instanceof Color) {
            return rgbColorStr((Color) this.paint);
        } else if (this.paint instanceof GradientPaint ||
                   this.paint instanceof LinearGradientPaint ||
                   this.paint instanceof RadialGradientPaint) {
            return "url(#" + this.gradientPaintRef + ")";
        }
        return result;
    }

    /**
     * Returns the SVG RGB color string for the specified color.
     *
     * @param c  the color ({@code null} not permitted).
     *
     * @return The SVG RGB color string.
     */
    private String rgbColorStr(Color c) {
        StringBuilder b = new StringBuilder("rgb(");
        b.append(c.getRed()).append(",").append(c.getGreen()).append(",").append(c.getBlue()).append(")");
        return b.toString();
    }

    /**
     * Returns a string representing the specified color in RGBA format.
     *
     * @param c  the color ({@code null} not permitted).
     *
     * @return The SVG RGBA color string.
     */
    private String rgbaColorStr(Color c) {
        StringBuilder b = new StringBuilder("rgba(");
        double alphaPercent = c.getAlpha() / 255.0;
        b.append(c.getRed()).append(",").append(c.getGreen()).append(",").append(c.getBlue());
        b.append(",").append(transformDP(alphaPercent));
        b.append(")");
        return b.toString();
    }

    private static final String DEFAULT_STROKE_CAP = "butt";
    private static final String DEFAULT_STROKE_JOIN = "miter";
    private static final float DEFAULT_MITER_LIMIT = 4.0f;

    /**
     * Returns a stroke style string based on the current stroke and
     * alpha settings.
     *
     * @return A stroke style string.
     */
    private String strokeStyle() {
        double strokeWidth = 1.0f;
        String strokeCap = DEFAULT_STROKE_CAP;
        String strokeJoin = DEFAULT_STROKE_JOIN;
        float miterLimit = DEFAULT_MITER_LIMIT;
        float[] dashArray = new float[0];
        DecimalFormat df = new DecimalFormat("#.##");
        if (this.stroke instanceof BasicStroke) {
            BasicStroke bs = (BasicStroke) this.stroke;
            strokeWidth = bs.getLineWidth() > 0.0 ? bs.getLineWidth() : this.zeroStrokeWidth;
            switch (bs.getEndCap()) {
                case BasicStroke.CAP_ROUND:
                    strokeCap = "round";
                    break;
                case BasicStroke.CAP_SQUARE:
                    strokeCap = "square";
                    break;
                case BasicStroke.CAP_BUTT:
                default:
                    // already set to "butt"
            }
            switch (bs.getLineJoin()) {
                case BasicStroke.JOIN_BEVEL:
                    strokeJoin = "bevel";
                    break;
                case BasicStroke.JOIN_ROUND:
                    strokeJoin = "round";
                    break;
                case BasicStroke.JOIN_MITER:
                default:
                    // already set to "miter"
            }
            miterLimit = bs.getMiterLimit();
            dashArray = bs.getDashArray();
        }
        StringBuilder b = new StringBuilder();
        if (strokeWidth != 1.0f) {
            b.append("stroke-width:").append(strokeWidth).append(";");
        }
        if (!stroke.equals("rgb(0,0,0)")) {
            b.append("stroke:").append(svgColorStr()).append(";");
        }
        if (!df.format(getColorAlpha() * getAlpha()).equals("1")) {
            b.append("stroke-opacity:").append(df.format(getColorAlpha() * getAlpha())).append(";");
        }
        if (!strokeCap.equals(DEFAULT_STROKE_CAP)) {
            b.append("stroke-linecap:").append(strokeCap).append(";");
        }
        if (!strokeJoin.equals(DEFAULT_STROKE_JOIN)) {
            b.append("stroke-linejoin:").append(strokeJoin).append(";");
        }
        if (Math.abs(DEFAULT_MITER_LIMIT - miterLimit) < 0.001) {
            b.append("stroke-miterlimit:").append(geomDP(miterLimit));
        }
        if (dashArray != null && dashArray.length != 0) {
            b.append("stroke-dasharray:");
            for (int i = 0; i < dashArray.length; i++) {
                if (i != 0) b.append(",");
                b.append(dashArray[i]);
            }
            b.append(";");
        }
        if (this.checkStrokeControlHint) {
            Object hint = getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
            if (RenderingHints.VALUE_STROKE_NORMALIZE.equals(hint) && !this.shapeRendering.equals("crispEdges")) {
                b.append("shape-rendering:crispEdges;");
            }
            if (RenderingHints.VALUE_STROKE_PURE.equals(hint) && !this.shapeRendering.equals("geometricPrecision")) {
                b.append("shape-rendering:geometricPrecision;");
            }
        }
        return b.toString();
    }

    /**
     * Returns the alpha value of the current {@code paint}, or {@code 1.0f} if
     * it is not an instance of {@code Color}.
     *
     * @return The alpha value (in the range {@code 0.0} to {@code 1.0}.
     */
    private float getColorAlpha() {
        if (this.paint instanceof Color) {
            Color c = (Color) this.paint;
            return c.getAlpha() / 255.0f;
        }
        return 1f;
    }

    /**
     * Returns a fill style string based on the current paint and
     * alpha settings.
     *
     * @return A fill style string.
     */
    private String getSVGFillStyle() {
        DecimalFormat df = new DecimalFormat("#.##");
        StringBuilder b = new StringBuilder();
        b.append("fill:").append(svgColorStr()).append(";");
        b.append("fill-opacity:").append(df.format(getColorAlpha() * getAlpha()));
        return b.toString();
    }

    /**
     * Returns the current font used for drawing text.
     *
     * @return The current font (never {@code null}).
     *
     * @see #setFont(java.awt.Font)
     */
    @Override
    public Font getFont() {
        return this.font;
    }

    /**
     * Sets the font to be used for drawing text.
     *
     * @param font  the font ({@code null} is permitted but ignored).
     *
     * @see #getFont()
     */
    @Override
    public void setFont(Font font) {
        if (font == null) {return;}
        this.font = font;
    }

    /**
     * Returns the font mapper (an object that optionally maps font family
     * names to alternates).  The default mapper will convert Java logical
     * font names to the equivalent SVG generic font name, and leave all other
     * font names unchanged.
     *
     * @return The font mapper (never {@code null}).
     *
     * @see #setFontMapper(org.jfree.svg.FontMapper)
     * @since 1.5
     */
    public FontMapper getFontMapper() {
        return this.fontMapper;
    }

    /**
     * Sets the font mapper.
     *
     * @param mapper  the font mapper ({@code null} not permitted).
     *
     * @since 1.5
     */
    public void setFontMapper(FontMapper mapper) {
        Args.nullNotPermitted(mapper, "mapper");
        this.fontMapper = mapper;
    }

    /**
     * Returns the font size units.  The default value is {@code SVGUnits.PX}.
     *
     * @return The font size units.
     *
     * @since 3.4
     */
    public SVGUnits getFontSizeUnits() {
        return this.fontSizeUnits;
    }

    /**
     * Sets the font size units.  In general, if this method is used it should
     * be called immediately after the {@code SVGGraphics2D} instance is
     * created and before any content is generated.
     *
     * @param fontSizeUnits  the font size units ({@code null} not permitted).
     *
     * @since 3.4
     */
    public void setFontSizeUnits(SVGUnits fontSizeUnits) {
        Args.nullNotPermitted(fontSizeUnits, "fontSizeUnits");
        this.fontSizeUnits = fontSizeUnits;
    }

    /**
     * Returns a string containing font style info.
     *
     * @return A string containing font style info.
     */
    private String getSVGFontStyle() {
        DecimalFormat df = new DecimalFormat("#.##");
        StringBuilder b = new StringBuilder();
        b.append("fill:").append(svgColorStr()).append(";");
        b.append("fill-opacity:").append(df.format(getColorAlpha() * getAlpha())).append(";");
        String fontFamily = this.fontMapper.mapFont(this.font.getFamily());
        b.append("font-family:").append(fontFamily).append(";");
        b.append("font-size:").append(this.font.getSize()).append(this.fontSizeUnits).append(";");
        if (this.font.isBold()) {
            b.append(" font-weight:bold;");
        }
        if (this.font.isItalic()) {
            b.append(" font-style:italic;");
        }
        return b.toString();
    }

    /**
     * Returns the font metrics for the specified font.
     *
     * @param f  the font.
     *
     * @return The font metrics.
     */
    @Override
    public FontMetrics getFontMetrics(Font f) {
        if (this.fmImage == null) {
            this.fmImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            this.fmImageG2D = this.fmImage.createGraphics();
            this.fmImageG2D.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }
        return this.fmImageG2D.getFontMetrics(f);
    }

    /**
     * Returns the font render context.
     *
     * @return The font render context (never {@code null}).
     */
    @Override
    public FontRenderContext getFontRenderContext() {
        return this.fontRenderContext;
    }

    /**
     * Draws a string at {@code (x, y)}.  The start of the text at the
     * baseline level will be aligned with the {@code (x, y)} point.
     * <br><br>
     * Note that you can make use of the {@link SVGHints#KEY_TEXT_RENDERING}
     * hint when drawing strings (this is completely optional though).
     *
     * @param str  the string ({@code null} not permitted).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     *
     * @see #drawString(java.lang.String, float, float)
     */
    @Override
    public void drawString(String str, int x, int y) {
        drawString(str, (float) x, (float) y);
    }

    /**
     * Draws a string at {@code (x, y)}. The start of the text at the
     * baseline level will be aligned with the {@code (x, y)} point.
     * <br><br>
     * Note that you can make use of the {@link SVGHints#KEY_TEXT_RENDERING}
     * hint when drawing strings (this is completely optional though).
     *
     * @param str  the string ({@code null} not permitted).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     */
    @Override
    public void drawString(String str, float x, float y) {
        if (str == null) {throw new NullPointerException("Null 'str' argument.");}
        if (str.isEmpty()) {return;}
        if (!SVGHints.VALUE_DRAW_STRING_TYPE_VECTOR.equals(this.hints.get(SVGHints.KEY_DRAW_STRING_TYPE))) {
            this.sb.append("<g ");
            appendOptionalElementIDFromHint(this.sb);
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\"");
            }
            this.sb.append("style=\"").append(getSVGFontStyle()).append("\"")
                   .append(" ").append(getClipPathRef()).append(">").append("<text ");
            if (str.endsWith("UTC")) {this.sb.append("id=\"date\" ");}
            this.sb.append("x=\"").append(geomDP(x)).append("\" y=\"").append(geomDP(y)).append("\">")
                   .append(SVGUtils.escapeForXML(str)
                   .replace("Min:", "<tspan class=\"bold\">Min:</tspan>")
                   .replace("Avg:", "<tspan class=\"bold\">Avg:</tspan>")
                   .replace("Max:", "<tspan class=\"bold\">Max:</tspan>")
                   .replace("Now:", "<tspan class=\"bold\">Now:</tspan>"));
            this.sb.append("</text>").append("</g>");
        } else {
            AttributedString as = new AttributedString(str, this.font.getAttributes());
            drawString(as.getIterator(), x, y);
        }
    }

    /**
     * Draws a string of attributed characters at {@code (x, y)}.  The
     * call is delegated to
     * {@link #drawString(AttributedCharacterIterator, float, float)}.
     *
     * @param iterator  an iterator for the characters.
     * @param x  the x-coordinate.
     * @param y  the x-coordinate.
     */
    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) {
        drawString(iterator, (float) x, (float) y);
    }

    /**
     * Draws a string of attributed characters at {@code (x, y)}.
     *
     * @param iterator  an iterator over the characters ({@code null} not
     *     permitted).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     */
    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        Set<Attribute> s = iterator.getAllAttributeKeys();
        if (!s.isEmpty()) {
            TextLayout layout = new TextLayout(iterator, getFontRenderContext());
            layout.draw(this, x, y);
        } else {
            StringBuilder strb = new StringBuilder();
            iterator.first();
            for (int i = iterator.getBeginIndex(); i < iterator.getEndIndex(); i++) {
                strb.append(iterator.current());
                iterator.next();
            }
            drawString(strb.toString(), x, y);
        }
    }

    /**
     * Draws the specified glyph vector at the location {@code (x, y)}.
     *
     * @param g  the glyph vector ({@code null} not permitted).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     */
    @Override
    public void drawGlyphVector(GlyphVector g, float x, float y) {
        fill(g.getOutline(x, y));
    }

    /**
     * Applies the translation {@code (tx, ty)}.  This call is delegated
     * to {@link #translate(double, double)}.
     *
     * @param tx  the x-translation.
     * @param ty  the y-translation.
     *
     * @see #translate(double, double)
     */
    @Override
    public void translate(int tx, int ty) {
        translate((double) tx, (double) ty);
    }

    /**
     * Applies the translation {@code (tx, ty)}.
     *
     * @param tx  the x-translation.
     * @param ty  the y-translation.
     */
    @Override
    public void translate(double tx, double ty) {
        AffineTransform t = getTransform();
        t.translate(tx, ty);
        setTransform(t);
    }

    /**
     * Applies a rotation (anti-clockwise) about {@code (0, 0)}.
     *
     * @param theta  the rotation angle (in radians).
     */
    @Override
    public void rotate(double theta) {
        AffineTransform t = getTransform();
        t.rotate(theta);
        setTransform(t);
    }

    /**
     * Applies a rotation (anti-clockwise) about {@code (x, y)}.
     *
     * @param theta  the rotation angle (in radians).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     */
    @Override
    public void rotate(double theta, double x, double y) {
        translate(x, y);
        rotate(theta);
        translate(-x, -y);
    }

    /**
     * Applies a scale transformation.
     *
     * @param sx  the x-scaling factor.
     * @param sy  the y-scaling factor.
     */
    @Override
    public void scale(double sx, double sy) {
        AffineTransform t = getTransform();
        t.scale(sx, sy);
        setTransform(t);
    }

    /**
     * Applies a shear transformation. This is equivalent to the following
     * call to the {@code transform} method:
     * <br><br>
     * <ul><li>
     * {@code transform(AffineTransform.getShearInstance(shx, shy));}
     * </ul>
     *
     * @param shx  the x-shear factor.
     * @param shy  the y-shear factor.
     */
    @Override
    public void shear(double shx, double shy) {
        transform(AffineTransform.getShearInstance(shx, shy));
    }

    /**
     * Applies this transform to the existing transform by concatenating it.
     *
     * @param t  the transform ({@code null} not permitted).
     */
    @Override
    public void transform(AffineTransform t) {
        AffineTransform tx = getTransform();
        tx.concatenate(t);
        setTransform(tx);
    }

    /**
     * Returns a copy of the current transform.
     *
     * @return A copy of the current transform (never {@code null}).
     *
     * @see #setTransform(java.awt.geom.AffineTransform)
     */
    @Override
    public AffineTransform getTransform() {
        return (AffineTransform) this.transform.clone();
    }

    /**
     * Sets the transform.
     *
     * @param t  the new transform ({@code null} permitted, resets to the
     *     identity transform).
     *
     * @see #getTransform()
     */
    @Override
    public void setTransform(AffineTransform t) {
        if (t == null) {
            this.transform = new AffineTransform();
        } else {
            this.transform = new AffineTransform(t);
        }
        this.clipRef = null;
    }

    /**
     * Returns {@code true} if the rectangle (in device space) intersects
     * with the shape (the interior, if {@code onStroke} is {@code false},
     * otherwise the stroked outline of the shape).
     *
     * @param rect  a rectangle (in device space).
     * @param s the shape.
     * @param onStroke  test the stroked outline only?
     *
     * @return A boolean.
     */
    @Override
    public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
        Shape ts;
        if (onStroke) {
            ts = this.transform.createTransformedShape(this.stroke.createStrokedShape(s));
        } else {
            ts = this.transform.createTransformedShape(s);
        }
        if (!rect.getBounds2D().intersects(ts.getBounds2D())) {
            return false;
        }
        Area a1 = new Area(rect);
        Area a2 = new Area(ts);
        a1.intersect(a2);
        return !a1.isEmpty();
    }

    /**
     * Does nothing in this {@code SVGGraphics2D} implementation.
     */
    @Override
    public void setPaintMode() {
        // do nothing
    }

    /**
     * Does nothing in this {@code SVGGraphics2D} implementation.
     *
     * @param c  ignored
     */
    @Override
    public void setXORMode(Color c) {
        // do nothing
    }

    /**
     * Returns the bounds of the user clipping region.
     *
     * @return The clip bounds (possibly {@code null}).
     *
     * @see #getClip()
     */
    @Override
    public Rectangle getClipBounds() {
        if (this.clip == null) {
            return null;
        }
        return getClip().getBounds();
    }

    /**
     * Returns the user clipping region.  The initial default value is
     * {@code null}.
     *
     * @return The user clipping region (possibly {@code null}).
     *
     * @see #setClip(java.awt.Shape)
     */
    @Override
    public Shape getClip() {
        if (this.clip == null) {return null;}
        AffineTransform inv;
        try {
            inv = this.transform.createInverse();
            return inv.createTransformedShape(this.clip);
        } catch (NoninvertibleTransformException ex) {return null;}
    }

    /**
     * Sets the user clipping region.
     *
     * @param shape  the new user clipping region ({@code null} permitted).
     *
     * @see #getClip()
     */
    @Override
    public void setClip(Shape shape) {
        // null is handled fine here...
        this.clip = this.transform.createTransformedShape(shape);
        this.clipRef = null;
    }

    /**
     * Registers the clip so that we can later write out all the clip
     * definitions in the DEFS element.
     *
     * @param clip  the clip (ignored if {@code null})
     */
    private String registerClip(Shape clip) {
        if (clip == null) {
            this.clipRef = null;
            return null;
        }
        // generate the path
        String pathStr = getSVGPathData(new Path2D.Double(clip));
        int index = this.clipPaths.indexOf(pathStr);
        if (index < 0) {
            this.clipPaths.add(pathStr);
            index = this.clipPaths.size() - 1;
        }
        //return this.defsKeyPrefix + CLIP_KEY_PREFIX + index;
        return CLIP_KEY_PREFIX + index;
    }

    private String transformDP(final double d) {
        return doubleToString(d, transformDP);
    }

    private String geomDP(final double d) {
        return doubleToString(d, geometryDP);
    }

    private String getSVGTransform(AffineTransform t) {
        StringBuilder b = new StringBuilder("matrix(");
        b.append(transformDP(t.getScaleX())).append(",")
         .append(transformDP(t.getShearY())).append(",")
         .append(transformDP(t.getShearX())).append(",")
         .append(transformDP(t.getScaleY())).append(",")
         .append(transformDP(t.getTranslateX())).append(",")
         .append(transformDP(t.getTranslateY())).append(")");
        return b.toString();
    }

    /**
     * Clips to the intersection of the current clipping region and the
     * specified shape.
     *
     * According to the Oracle API specification, this method will accept a
     * {@code null} argument, but there is an open bug report (since 2004)
     * that suggests this is wrong:
     * <p>
     * <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6206189">
     * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6206189</a>
     *
     * @param s  the clip shape ({@code null} not permitted).
     */
    @Override
    public void clip(Shape s) {
        if (s instanceof Line2D) {
            s = s.getBounds2D();
        }
        if (this.clip == null) {
            setClip(s);
            return;
        }
        Shape ts = this.transform.createTransformedShape(s);
        if (!ts.intersects(this.clip.getBounds2D())) {
            setClip(new Rectangle2D.Double());
        } else {
          Area a1 = new Area(ts);
          Area a2 = new Area(this.clip);
          a1.intersect(a2);
          this.clip = new Path2D.Double(a1);
        }
        this.clipRef = null;
    }

    /**
     * Clips to the intersection of the current clipping region and the
     * specified rectangle.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     */
    @Override
    public void clipRect(int x, int y, int width, int height) {
        setRect(x, y, width, height);
        clip(this.rect);
    }

    /**
     * Sets the user clipping region to the specified rectangle.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     *
     * @see #getClip()
     */
    @Override
    public void setClip(int x, int y, int width, int height) {
        setRect(x, y, width, height);
        setClip(this.rect);
    }

    /**
     * Draws a line from {@code (x1, y1)} to {@code (x2, y2)} using
     * the current {@code paint} and {@code stroke}.
     *
     * @param x1  the x-coordinate of the start point.
     * @param y1  the y-coordinate of the start point.
     * @param x2  the x-coordinate of the end point.
     * @param y2  the x-coordinate of the end point.
     */
    @Override
    public void drawLine(int x1, int y1, int x2, int y2) {
        if (this.line == null) {
            this.line = new Line2D.Double(x1, y1, x2, y2);
        } else {
            this.line.setLine(x1, y1, x2, y2);
        }
        draw(this.line);
    }

    /**
     * Fills the specified rectangle with the current {@code paint}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the rectangle width.
     * @param height  the rectangle height.
     */
    @Override
    public void fillRect(int x, int y, int width, int height) {
        setRect(x, y, width, height);
        fill(this.rect);
    }

    /**
     * Clears the specified rectangle by filling it with the current
     * background color.  If the background color is {@code null}, this
     * method will do nothing.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     *
     * @see #getBackground()
     */
    @Override
    public void clearRect(int x, int y, int width, int height) {
        if (getBackground() == null) {
            return;  // we can't do anything
        }
        Paint saved = getPaint();
        setPaint(getBackground());
        fillRect(x, y, width, height);
        setPaint(saved);
    }

    /**
     * Draws a rectangle with rounded corners using the current
     * {@code paint} and {@code stroke}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     * @param arcWidth  the arc-width.
     * @param arcHeight  the arc-height.
     *
     * @see #fillRoundRect(int, int, int, int, int, int)
     */
    @Override
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        setRoundRect(x, y, width, height, arcWidth, arcHeight);
        draw(this.roundRect);
    }

    /**
     * Fills a rectangle with rounded corners using the current {@code paint}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     * @param arcWidth  the arc-width.
     * @param arcHeight  the arc-height.
     *
     * @see #drawRoundRect(int, int, int, int, int, int)
     */
    @Override
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        setRoundRect(x, y, width, height, arcWidth, arcHeight);
        fill(this.roundRect);
    }

    /**
     * Draws an oval framed by the rectangle {@code (x, y, width, height)}
     * using the current {@code paint} and {@code stroke}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     *
     * @see #fillOval(int, int, int, int)
     */
    @Override
    public void drawOval(int x, int y, int width, int height) {
        setOval(x, y, width, height);
        draw(this.oval);
    }

    /**
     * Fills an oval framed by the rectangle {@code (x, y, width, height)}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     *
     * @see #drawOval(int, int, int, int)
     */
    @Override
    public void fillOval(int x, int y, int width, int height) {
        setOval(x, y, width, height);
        fill(this.oval);
    }

    /**
     * Draws an arc contained within the rectangle
     * {@code (x, y, width, height)}, starting at {@code startAngle}
     * and continuing through {@code arcAngle} degrees using
     * the current {@code paint} and {@code stroke}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     * @param startAngle  the start angle in degrees, 0 = 3 o'clock.
     * @param arcAngle  the angle (anticlockwise) in degrees.
     *
     * @see #fillArc(int, int, int, int, int, int)
     */
    @Override
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        setArc(x, y, width, height, startAngle, arcAngle);
        draw(this.arc);
    }

    /**
     * Fills an arc contained within the rectangle
     * {@code (x, y, width, height)}, starting at {@code startAngle}
     * and continuing through {@code arcAngle} degrees, using
     * the current {@code paint}.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     * @param startAngle  the start angle in degrees, 0 = 3 o'clock.
     * @param arcAngle  the angle (anticlockwise) in degrees.
     *
     * @see #drawArc(int, int, int, int, int, int)
     */
    @Override
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        setArc(x, y, width, height, startAngle, arcAngle);
        fill(this.arc);
    }

    /**
     * Draws the specified multi-segment line using the current
     * {@code paint} and {@code stroke}.
     *
     * @param xPoints  the x-points.
     * @param yPoints  the y-points.
     * @param nPoints  the number of points to use for the polyline.
     */
    @Override
    public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {
        GeneralPath p = GraphicsUtils.createPolygon(xPoints, yPoints, nPoints, false);
        draw(p);
    }

    /**
     * Draws the specified polygon using the current {@code paint} and
     * {@code stroke}.
     *
     * @param xPoints  the x-points.
     * @param yPoints  the y-points.
     * @param nPoints  the number of points to use for the polygon.
     *
     * @see #fillPolygon(int[], int[], int)      */
    @Override
    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        GeneralPath p = GraphicsUtils.createPolygon(xPoints, yPoints, nPoints, true);
        draw(p);
    }

    /**
     * Fills the specified polygon using the current {@code paint}.
     *
     * @param xPoints  the x-points.
     * @param yPoints  the y-points.
     * @param nPoints  the number of points to use for the polygon.
     *
     * @see #drawPolygon(int[], int[], int)
     */
    @Override
    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        GeneralPath p = GraphicsUtils.createPolygon(xPoints, yPoints, nPoints, true);
        fill(p);
    }

    /**
     * Returns the bytes representing a PNG format image.
     *
     * @param img  the image to encode ({@code null} not permitted).
     *
     * @return The bytes representing a PNG format image.
     */
    private byte[] getPNGBytes(Image img) {
        Args.nullNotPermitted(img, "img");
        RenderedImage ri;
        if (img instanceof RenderedImage) {
            ri = (RenderedImage) img;
        } else {
            BufferedImage bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = bi.createGraphics();
            g2.drawImage(img, 0, 0, null);
            ri = bi;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(ri, "png", baos);
        } catch (IOException ex) {
          System.err.println("IOException while writing PNG data (" + ex.getMessage() + ")");
        }
        return baos.toByteArray();
    }

    /**
     * Draws an image at the location {@code (x, y)}.  Note that the
     * {@code observer} is ignored.
     *
     * @param img  the image ({@code null} permitted...method will do nothing).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param observer  ignored.
     *
     * @return {@code true} if there is no more drawing to be done.
     */
    @Override
    public boolean drawImage(Image img, int x, int y, ImageObserver observer) {
        if (img == null) {
            return true;
        }
        int w = img.getWidth(observer);
        if (w < 0) {
            return false;
        }
        int h = img.getHeight(observer);
        if (h < 0) {
            return false;
        }
        return drawImage(img, x, y, w, h, observer);
    }

    /**
     * Draws the image into the rectangle defined by {@code (x, y, w, h)}.
     * Note that the {@code observer} is ignored (it is not useful in this
     * context).
     *
     * @param img  the image ({@code null} permitted...draws nothing).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param w  the width.
     * @param h  the height.
     * @param observer  ignored.
     *
     * @return {@code true} if there is no more drawing to be done.
     */
    @Override
    public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver observer) {
        if (img == null) {return true;}
        // the rendering hints control whether the image is embedded or
        // referenced...
        Object hint = getRenderingHint(SVGHints.KEY_IMAGE_HANDLING);
        if (SVGHints.VALUE_IMAGE_HANDLING_EMBED.equals(hint)) {
            this.sb.append("<image ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("preserveAspectRatio=\"none\" ").append("href=\"data:image/png;base64,")
                   .append(Base64.getEncoder().encodeToString(getPNGBytes(img))).append("\" ")
                   .append(getClipPathRef()).append(" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append("x=\"").append(geomDP(x)).append("\" y=\"").append(geomDP(y)).append("\" ")
                   .append("width=\"").append(geomDP(w)).append("\" height=\"").append(geomDP(h)).append("\"/>\n");
            return true;
        } else { // here for SVGHints.VALUE_IMAGE_HANDLING_REFERENCE
            int count = this.imageElements.size();
            String href = (String) this.hints.get(SVGHints.KEY_IMAGE_HREF);
            if (href == null) {href = this.filePrefix + count + this.fileSuffix;}
            else {this.hints.put(SVGHints.KEY_IMAGE_HREF, null);} // KEY_IMAGE_HREF value is for a single use...
            ImageElement imageElement = new ImageElement(href, img);
            this.imageElements.add(imageElement);
            // write an SVG element for the img
            this.sb.append("<image ");
            appendOptionalElementIDFromHint(this.sb);
            this.sb.append("href=\"").append(href).append("\" ").append(getClipPathRef()).append(" ");
            if (!this.transform.isIdentity()) {
                this.sb.append("transform=\"").append(getSVGTransform(this.transform)).append("\" ");
            }
            this.sb.append("x=\"").append(geomDP(x)).append("\" y=\"").append(geomDP(y)).append("\" ")
                   .append("width=\"").append(geomDP(w)).append("\" height=\"").append(geomDP(h)).append("\"/>\n");
            return true;
        }
    }

    /**
     * Draws an image at the location {@code (x, y)}.  Note that the
     * {@code observer} is ignored.
     *
     * @param img  the image ({@code null} permitted...draws nothing).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param bgcolor  the background color ({@code null} permitted).
     * @param observer  ignored.
     *
     * @return {@code true} if there is no more drawing to be done.
     */
    @Override
    public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer) {
        if (img == null) {return true;}
        int w = img.getWidth(null);
        if (w < 0) {return false;}
        int h = img.getHeight(null);
        if (h < 0) {return false;}
        return drawImage(img, x, y, w, h, bgcolor, observer);
    }

    /**
     * Draws an image to the rectangle {@code (x, y, w, h)} (scaling it if
     * required), first filling the background with the specified color.  Note
     * that the {@code observer} is ignored.
     *
     * @param img  the image.
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param w  the width.
     * @param h  the height.
     * @param bgcolor  the background color ({@code null} permitted).
     * @param observer  ignored.
     *
     * @return {@code true} if the image is drawn.
     */
    @Override
    public boolean drawImage(Image img, int x, int y, int w, int h, Color bgcolor, ImageObserver observer) {
        Paint saved = getPaint();
        setPaint(bgcolor);
        fillRect(x, y, w, h);
        setPaint(saved);
        return drawImage(img, x, y, w, h, observer);
    }

    /**
     * Draws part of an image (defined by the source rectangle
     * {@code (sx1, sy1, sx2, sy2)}) into the destination rectangle
     * {@code (dx1, dy1, dx2, dy2)}.  Note that the {@code observer} is ignored.
     *
     * @param img  the image.
     * @param dx1  the x-coordinate for the top left of the destination.
     * @param dy1  the y-coordinate for the top left of the destination.
     * @param dx2  the x-coordinate for the bottom right of the destination.
     * @param dy2  the y-coordinate for the bottom right of the destination.
     * @param sx1 the x-coordinate for the top left of the source.
     * @param sy1 the y-coordinate for the top left of the source.
     * @param sx2 the x-coordinate for the bottom right of the source.
     * @param sy2 the y-coordinate for the bottom right of the source.
     *
     * @return {@code true} if the image is drawn.
     */
    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2,
            int sx1, int sy1, int sx2, int sy2, ImageObserver observer) {
        int w = dx2 - dx1;
        int h = dy2 - dy1;
        BufferedImage img2 = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img2.createGraphics();
        g2.drawImage(img, 0, 0, w, h, sx1, sy1, sx2, sy2, null);
        return drawImage(img2, dx1, dy1, null);
    }

    /**
     * Draws part of an image (defined by the source rectangle
     * {@code (sx1, sy1, sx2, sy2)}) into the destination rectangle
     * {@code (dx1, dy1, dx2, dy2)}.  The destination rectangle is first
     * cleared by filling it with the specified {@code bgcolor}. Note that
     * the {@code observer} is ignored.
     *
     * @param img  the image.
     * @param dx1  the x-coordinate for the top left of the destination.
     * @param dy1  the y-coordinate for the top left of the destination.
     * @param dx2  the x-coordinate for the bottom right of the destination.
     * @param dy2  the y-coordinate for the bottom right of the destination.
     * @param sx1 the x-coordinate for the top left of the source.
     * @param sy1 the y-coordinate for the top left of the source.
     * @param sx2 the x-coordinate for the bottom right of the source.
     * @param sy2 the y-coordinate for the bottom right of the source.
     * @param bgcolor  the background color ({@code null} permitted).
     * @param observer  ignored.
     *
     * @return {@code true} if the image is drawn.
     */
    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1,
                             int sy1, int sx2, int sy2, Color bgcolor, ImageObserver observer) {
        Paint saved = getPaint();
        setPaint(bgcolor);
        fillRect(dx1, dy1, dx2 - dx1, dy2 - dy1);
        setPaint(saved);
        return drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer);
    }

    /**
     * Draws the rendered image.  If {@code img} is {@code null} this method
     * does nothing.
     *
     * @param img  the image ({@code null} permitted).
     * @param xform  the transform.
     */
    @Override
    public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
        if (img == null) {return;}
        BufferedImage bi = GraphicsUtils.convertRenderedImage(img);
        drawImage(bi, xform, null);
    }

    /**
     * Draws the renderable image.
     *
     * @param img  the renderable image.
     * @param xform  the transform.
     */
    @Override
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
        RenderedImage ri = img.createDefaultRendering();
        drawRenderedImage(ri, xform);
    }

    /**
     * Draws an image with the specified transform. Note that the
     * {@code observer} is ignored.
     *
     * @param img  the image.
     * @param xform  the transform ({@code null} permitted).
     * @param obs  the image observer (ignored).
     *
     * @return {@code true} if the image is drawn.
     */
    @Override
    public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
        AffineTransform savedTransform = getTransform();
        if (xform != null) {transform(xform);}
        boolean result = drawImage(img, 0, 0, obs);
        if (xform != null) {setTransform(savedTransform);}
        return result;
    }

    /**
     * Draws the image resulting from applying the {@code BufferedImageOp}
     * to the specified image at the location {@code (x, y)}.
     *
     * @param img  the image.
     * @param op  the operation ({@code null} permitted).
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     */
    @Override
    public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {
        BufferedImage imageToDraw = img;
        if (op != null) {imageToDraw = op.filter(img, null);}
        drawImage(imageToDraw, new AffineTransform(1f, 0f, 0f, 1f, x, y), null);
    }

    /**
     * This method does nothing.  The operation assumes that the output is in
     * bitmap form, which is not the case for SVG, so we silently ignore
     * this method call.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width of the area.
     * @param height  the height of the area.
     * @param dx  the delta x.
     * @param dy  the delta y.
     */
    @Override
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {
        // do nothing, this operation is silently ignored.
    }

    /**
     * This method does nothing, there are no resources to dispose.
     */
    @Override
    public void dispose() {
        // nothing to do
    }

    /**
     * Returns the SVG element that has been generated by calls to this
     * {@code Graphics2D} implementation.
     *
     * @return The SVG element.
     */
    public String getSVGElement() {
        return getSVGElement(null);
    }

    /**
     * Returns the SVG element that has been generated by calls to this
     * {@code Graphics2D} implementation, giving it the specified {@code id}.
     * If {@code id} is {@code null}, the element will have no {@code id}
     * attribute.
     *
     * @param id  the element id ({@code null} permitted).
     *
     * @return A string containing the SVG element.
     *
     * @since 1.8
     */
    public String getSVGElement(String id) {
        return getSVGElement(id, true, null, null, null);
    }

    /**
     * Returns the SVG element that has been generated by calls to this
     * {@code Graphics2D} implementation, giving it the specified {@code id}.
     * If {@code id} is {@code null}, the element will have no {@code id}
     * attribute.  This method also allows for a {@code viewBox} to be defined,
     * along with the settings that handle scaling.
     *
     * @param id  the element id ({@code null} permitted).
     * @param includeDimensions  include the width and height attributes?
     * @param viewBox  the view box specification (if {@code null} then no
     *     {@code viewBox} attribute will be defined).
     * @param preserveAspectRatio  the value of the {@code preserveAspectRatio}
     *     attribute (if {@code null} then not attribute will be defined).
     * @param meetOrSlice  the value of the meetOrSlice attribute.
     *
     * @return A string containing the SVG element.
     *
     * @since 3.2
     */

    public String getSVGElement(String id, boolean includeDimensions, ViewBox viewBox,
        PreserveAspectRatio preserveAspectRatio, MeetOrSlice meetOrSlice) {
        StringBuilder svg = new StringBuilder("<svg ");
        String unitStr = this.units != null ? this.units.toString() : "";
        svg.append("xmlns=\"http://www.w3.org/2000/svg\" ");
        boolean isLarge = false;
        if (this.width >= 600 && this.height >= 200) {isLarge = true;}
        int axisStrokeWidth = isLarge ? 2 : 1;
        if (includeDimensions) {
            svg.append("width=\"").append(this.width).append(unitStr)
               .append("\" height=\"").append(this.height).append(unitStr)
               .append("\" ")
               .append("viewBox=\"0 0 ") // let's put the viewBox here
               .append(this.width).append(" ")
               .append(this.height).append("\"");
        }
        svg.append(">");

        StringBuilder defs = new StringBuilder("<defs>");
        for (int i = 0; i < this.clipPaths.size(); i++) {
            if (i != 1) { // no clipPath for entire graph
                StringBuilder b = new StringBuilder("<clipPath id=\"").append(CLIP_KEY_PREFIX).append(i).append("\">");
                b.append("<path ").append(this.clipPaths.get(i)).append("/>").append("</clipPath>");
                defs.append(b.toString());
            }
        }
        if (this.sb.indexOf("rgb(0,72,8)") != -1) { // dark
            defs.append("<linearGradient id=\"dark\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"100%\">")
                .append("<stop offset=\"0\" style=\"stop-color:#00660add\"/>")
                .append("<stop offset=\"100%\" style=\"stop-color:#003d06dd\"/>")
                .append("</linearGradient>");
        } else if (this.sb.indexOf("rgb(100,160,200)") != -1) { //light
            defs.append("<linearGradient id=\"light\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"100%\">")
                .append("<stop offset=\"0\" style=\"stop-color:#a1c5ded0\"/>")
                .append("<stop offset=\"100%\" style=\"stop-color:#7baed1d0\"/>")
                .append("</linearGradient>");
        } else if (this.sb.indexOf("rgb(0,72,160)") != -1) { // midnight
            defs.append("<linearGradient id=\"midnight\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"100%\">")
                .append("<stop offset=\"0\" style=\"stop-color:#338fffd0\"/>")
                .append("<stop offset=\"100%\" style=\"stop-color:#0050b3d0\"/>")
                .append("</linearGradient>");
        }
        defs.append("<link xmlns=\"http://www.w3.org/1999/xhtml\" rel=\"stylesheet\" type=\"text/css\" href=\"/themes/fonts/FiraCode.css\"/>");
        defs.append("<style>text{font-weight:600;text-rendering:optimizeLegibility;white-space:pre}")
            .append("line,path,rect{shape-rendering:crispEdges;vector-effect:non-scaling-stroke}")
            .append("line,rect,text{clip-path:url(#clip-2)}")
            .append(".axis{stroke-width:").append(axisStrokeWidth).append(";stroke-linecap:round}")
            .append(".bg{fill:#000b}")
            .append(".bold,.sans.s12 text,.sans.s13 text,.sans.s14 text{font-weight:700}")
            .append(".dash{stroke-opacity:.2;stroke-linecap:round;stroke-linejoin:round;stroke-dasharray:1,1}")
            .append(".line{stroke-opacity:.2;stroke-linecap:square}")
            .append(".mono{font-family:FiraCode,monospace;font-weight:500}")
            .append(".restart{stroke:#dc1030}")
            .append(".sans{font-family:Open Sans,Segoe UI,Noto Sans,sans-serif}")
            .append(".s10{font-size:10px}")
            .append(".s11{font-size:11px}")
            .append(".s12{font-size:12px}")
            .append(".s13{font-size:13px}")
            .append(".s14{font-size:14px}")
            .append("#date{font-style:italic}");
        if (this.sb.indexOf("rgb(0,72,8)") != -1) { // dark
            defs.append(".major{stroke:#f4f4be30}")
                .append(".minor{stroke:#c8c80028}");
        } else if (this.sb.indexOf("201,206,255") != -1) { // midnight
            defs.append(".major{stroke:#d260bf30}")
                .append(".minor{stroke:#c9ceff28}")
                .append("#graph{stroke:#1a81ffbb}");
        }
        defs.append("</style></defs>");
        svg.append(defs);
        svg.append(this.sb);
        svg.append("</svg>");
        String svgOut = svg.toString()
            .replace(";fill-opacity:1", "")
            .replace("stroke-dasharray:1.0,1.0", "stroke-dasharray:1,1")
            .replace("<g >", "<g>")
            .replace(":0.", ":.")
            .replace(";\"", "\"")
            .replace(".0 ", " ")
            .replace(".0\"", "\"")
            .replace(";stroke-opacity:.2;stroke-linecap:round;stroke-linejoin:round;stroke-dasharray:1,1\"", "\" class=\"dash\"")
            .replace(";stroke-opacity:.2;stroke-linecap:square\"", "\" class=\"line\"")
            .replace("fill-opacity:0\" /><g style=\"fill", "fill-opacity:0\"/><g id=\"graph\" style=\"fill")
            .replace("style=\"fill:rgb(0,72,8);fill-opacity:.86;stroke:none\"", "fill=\"url(#dark)\"")
            .replace("style=\"fill:rgb(100,160,200);fill-opacity:.78;stroke:none\"", "fill=\"url(#light)\"")
            .replace("style=\"fill:rgb(0,72,160);fill-opacity:.78;stroke:none\"", "fill=\"url(#midnight)\"")
            .replace("stroke:rgb(0,72,8)", "stroke:#00800da0")
            .replace("stroke:rgb(0,30,110)", "stroke:#003de6a0;fill:none")
            .replace("stroke:rgb(100,160,200);stroke-opacity:.78;", "stroke:#4da3cb99;")
            .replaceAll("/></g><g (style=\"stroke:.*?\").*?</g>", " $1/></g>")
            .replace("stroke-linecap:square;fill:none", "stroke-linecap:square")
            .replace("stroke-opacity:.86;stroke-linecap:square", "stroke-linecap:square")
            .replace(");stroke-linecap:square", ");stroke-linecap:square;fill:none")
            .replace("stroke-width:3.0", "stroke-width:3;fill:none")
            .replaceAll(" style=\"stroke-width:5.*?\"", " class=\"axis\"")
            .replace("stroke:rgb(100,200,160);stroke-linecap:square", "stroke:#64c8a0cc;stroke-linecap:square;fill:none")
            .replace("stroke:#00800da0;stroke-linecap:square\" /></g><g style=\"stroke-width:3", "stroke:none;stroke-linecap:square\"/></g><g style=\"stroke-width:3")
            .replace("<rect x=\"0\" y=\"0\" width=\"250\" height=\"50\" style=\"fill:rgb(0,0,0);fill-opacity:0\" />", "")
            .replace(";stroke-linecap:square", "")
            .replace("fill:rgb(0,0,0);fill-opacity:0;stroke:none", "opacity:0")
            .replace("fill:none;fill:none", "fill:none")
            .replace(" style=\"stroke:rgb(0,0,0);stroke-opacity:0\"", "")
            .replace(";stroke-linecap:round;stroke-linejoin:round;stroke-dasharray:1,1\"", "\" class=\"dash\"")
            .replace("style=\"stroke:rgb(220,16,48);fill:none\"", "class=\"restart\"")
            .replace(" clip-path=\"url(#clip-2)\"", "")
            .replace(" L ", "L");
        if (svgOut.indexOf("80,80,80") != -1) { // light/classic
            svgOut = svgOut.replace(".axis{", ".axis{stroke:#33333f;")
                           .replace(".dash{", ".dash{stroke:#444;")
                           .replace(".line{", ".line{stroke:#444;")
                           .replace("text{", "text{fill:#33333f;")
                           .replace("style=\"stroke:rgb(80,80,80)\" ", "")
                           .replace("fill:rgb(51,51,63);", "");

        } else if (svgOut.indexOf("244,244,190") != -1) { // dark
            svgOut = svgOut.replace(".axis{", ".axis{stroke:#beca95;")
                           .replace(".dash{", ".dash{stroke:#f4f4be;")
                           .replace(".line{", ".line{stroke:#f4f4be;")
                           .replace("text{", "text{fill:#f4f4be;")
                           .replace("style=\"stroke:rgb(244,244,190)\" ", "")
                           .replace("fill:rgb(244,244,190);", "")
                           .replaceAll("<g style=\"opacity:0\".*?</g>", "")
                           .replace(" clip-path=\"url(#clip-1)\"", "")
                           .replace(" style=\"stroke:rgb(244,244,190);stroke-opacity:.12\" class=\"dash\"", " class=\"dash minor\"")
                           .replace(" style=\"stroke:rgb(200,200,0)\" class=\"dash\"", " class=\"dash major\"")
                           .replace("fill:rgb(0,0,0);fill-opacity:0\"", "opacity:0\"")
                           .replace("style=\"fill:rgb(0,0,0);fill-opacity:.75\"", "class=\"bg\"")
                           .replace("style=\"stroke:rgb(244,244,190);stroke-opacity:.78\"", "class=\"axis\"")
                           .replace("stroke:#f4f4be;stroke-opacity:.2", "stroke:#f4f4be30");
        } else if (svgOut.indexOf("201,206,255") != -1) { // midnight
            svgOut = svgOut.replace(".axis{", ".axis{stroke:#a6b3e8;")
                           .replace("</style>", "#path2{stroke-width:2;stroke:#5af2f2aa;fill:none}</style>")
                           .replace(" style=\"stroke:rgb(0,72,160);stroke-opacity:.78\"", "")
                           .replace(" style=\"stroke-width:2.0;stroke:rgb(128,180,212);fill:none\"", " id=\"path2\"")
                           .replace(" style=\"stroke:rgb(201,206,255)\" class=\"dash\"", " class=\"dash minor\"")
                           .replace(" style=\"stroke:rgb(240,32,192);stroke-opacity:.43\" class=\"dash\"", " class=\"dash major\"");
        }
        if (svgOut.indexOf("font-family:monospace") != -1) {
            svgOut = svgOut.replace("style=\"font-family:monospace;", "class=\"mono\" style=\"");
            svgOut = svgOut.replace(" class=\"mono\" style=\"font-size:10px\"", " class=\"mono s10\"");
            svgOut = svgOut.replace(" class=\"mono\" style=\"font-size:11px\"", " class=\"mono s11\"");
            svgOut = svgOut.replace("</text></g><g class=\"mono s11\"><text", "</text><text");
        }
        if (svgOut.indexOf("font-family:sans-serif") != -1) {
            svgOut = svgOut.replace(" style=\"font-family:sans-serif;font-size:11px\"", " class=\"sans s11\"");
            svgOut = svgOut.replace(" style=\"font-family:sans-serif;font-size:12px\"", " class=\"sans s12\"");
            svgOut = svgOut.replace(" style=\"font-family:sans-serif;font-size:13px\"", " class=\"sans s13\"");
            svgOut = svgOut.replace(" style=\"font-family:sans-serif;font-size:14px\"", " class=\"sans s14\"");
            svgOut = svgOut.replace("</text></g><g class=\"sans s11\"><text", "</text><text");
        }
        svgOut = svgOut.replace("</text></g><g class=\"mono s11\" clip-path=\"url(#clip-2)\"><text", "</text><text")
                       .replace("</text></g><g class=\"sans s11\" clip-path=\"url(#clip-2)\"><text", "</text><text")
                       .replace("</text></g><g class=\"sans s12\" clip-path=\"url(#clip-2)\"><text", "</text><text")
                       .replace("</text></g><g class=\"mono s10\"><text", "</text><text")
                       .replace("</text></g><g class=\"mono s11\"><text", "</text><text")
                       .replace("</text></g><g class=\"sans s11\"><text", "</text><text")
                       .replace("</text></g><g class=\"sans s12\"><text", "</text><text");
        return svgOut;
    }

    /**
     * Returns an SVG document (this contains the content returned by the
     * {@link #getSVGElement()} method, prepended with the required document
     * header).
     *
     * @return An SVG document.
     */
    public String getSVGDocument() {
        StringBuilder b = new StringBuilder();
        //b.append("<?xml version=\"1.0\"?>\n");
        //b.append("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.0//EN\" ");
        //b.append("\"http://www.w3.org/TR/2001/REC-SVG-20010904/DTD/svg10.dtd\">\n");
        b.append(getSVGElement());
        return b.append("\n").toString();
    }

    /**
     * Returns the list of image elements that have been referenced in the
     * SVG output but not embedded.  If the image files don't already exist,
     * you can use this list as the basis for creating the image files.
     *
     * @return The list of image elements.
     *
     * @see SVGHints#KEY_IMAGE_HANDLING
     */
    public List<ImageElement> getSVGImages() {
        return this.imageElements;
    }

    /**
     * Returns a new set containing the element IDs that have been used in
     * output so far.
     *
     * @return The element IDs.
     *
     * @since 1.5
     */
    public Set<String> getElementIDs() {
        return new HashSet<>(this.elementIDs);
    }

    /**
     * Returns an element to represent a linear gradient.  All the linear
     * gradients that are used get written to the DEFS element in the SVG.
     *
     * @param id  the reference id.
     * @param paint  the gradient.
     *
     * @return The SVG element.
     */
    private String getLinearGradientElement(String id, GradientPaint paint) {
        StringBuilder b = new StringBuilder("<linearGradient id=\"").append(id).append("\" ");
        Point2D p1 = paint.getPoint1();
        Point2D p2 = paint.getPoint2();
        b.append("x1=\"").append(geomDP(p1.getX())).append("\" ")
         .append("y1=\"").append(geomDP(p1.getY())).append("\" ")
         .append("x2=\"").append(geomDP(p2.getX())).append("\" ")
         .append("y2=\"").append(geomDP(p2.getY())).append("\" ")
         .append("gradientUnits=\"userSpaceOnUse\">");
        Color c1 = paint.getColor1();
        b.append("<stop offset=\"0%\" stop-color=\"").append(rgbColorStr(c1)).append("\"");
        if (c1.getAlpha() < 255) {
            double alphaPercent = c1.getAlpha() / 255.0;
            b.append(" stop-opacity=\"").append(transformDP(alphaPercent)).append("\"");
        }
        b.append("/>");
        Color c2 = paint.getColor2();
        b.append("<stop offset=\"100%\" stop-color=\"").append(rgbColorStr(c2)).append("\"");
        if (c2.getAlpha() < 255) {
            double alphaPercent = c2.getAlpha() / 255.0;
            b.append(" stop-opacity=\"").append(transformDP(alphaPercent)).append("\"");
        }
        b.append("/>");
        return b.append("</linearGradient>").toString();
    }

    /**
     * Returns an element to represent a linear gradient.  All the linear
     * gradients that are used get written to the DEFS element in the SVG.
     *
     * @param id  the reference id.
     * @param paint  the gradient.
     *
     * @return The SVG element.
     */
    private String getLinearGradientElement(String id,
            LinearGradientPaint paint) {
        StringBuilder b = new StringBuilder("<linearGradient id=\"").append(id).append("\" ");
        Point2D p1 = paint.getStartPoint();
        Point2D p2 = paint.getEndPoint();
        b.append("x1=\"").append(geomDP(p1.getX())).append("\" ");
        b.append("y1=\"").append(geomDP(p1.getY())).append("\" ");
        b.append("x2=\"").append(geomDP(p2.getX())).append("\" ");
        b.append("y2=\"").append(geomDP(p2.getY())).append("\" ");
        if (!paint.getCycleMethod().equals(CycleMethod.NO_CYCLE)) {
            String sm = paint.getCycleMethod().equals(CycleMethod.REFLECT) ? "reflect" : "repeat";
            b.append("spreadMethod=\"").append(sm).append("\" ");
        }
        b.append("gradientUnits=\"userSpaceOnUse\">");
        for (int i = 0; i < paint.getFractions().length; i++) {
            Color c = paint.getColors()[i];
            float fraction = paint.getFractions()[i];
            b.append("<stop offset=\"").append(geomDP(fraction * 100)).append("%\" stop-color=\"").append(rgbColorStr(c)).append("\"");
            if (c.getAlpha() < 255) {
                double alphaPercent = c.getAlpha() / 255.0;
                b.append(" stop-opacity=\"").append(transformDP(alphaPercent)).append("\"");
            }
            b.append("/>");
        }
        return b.append("</linearGradient>").toString();
    }

    /**
     * Returns an element to represent a radial gradient.  All the radial
     * gradients that are used get written to the DEFS element in the SVG.
     *
     * @param id  the reference id.
     * @param rgp  the radial gradient.
     *
     * @return The SVG element.
     */
    private String getRadialGradientElement(String id, RadialGradientPaint rgp) {
        StringBuilder b = new StringBuilder("<radialGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" ");
        Point2D center = rgp.getCenterPoint();
        Point2D focus = rgp.getFocusPoint();
        float radius = rgp.getRadius();
        b.append("cx=\"").append(geomDP(center.getX())).append("\" ")
         .append("cy=\"").append(geomDP(center.getY())).append("\" ")
         .append("r=\"").append(geomDP(radius)).append("\" ")
         .append("fx=\"").append(geomDP(focus.getX())).append("\" ")
         .append("fy=\"").append(geomDP(focus.getY())).append("\">");

        Color[] colors = rgp.getColors();
        float[] fractions = rgp.getFractions();
        for (int i = 0; i < colors.length; i++) {
            Color c = colors[i];
            float f = fractions[i];
            b.append("<stop offset=\"").append(geomDP(f * 100)).append("%\" ")
             .append("stop-color=\"").append(rgbColorStr(c)).append("\"");
            if (c.getAlpha() < 255) {
                double alphaPercent = c.getAlpha() / 255.0;
                b.append(" stop-opacity=\"").append(transformDP(alphaPercent)).append("\"");
            }
            b.append("/>");
        }
        return b.append("</radialGradient>").toString();
    }

    /**
     * Returns a clip path reference for the current user clip.  This is
     * written out on all SVG elements that draw or fill shapes or text.
     *
     * @return A clip path reference.
     */
    private String getClipPathRef() {
        if (this.clip == null) {return "";}
        if (this.clipRef == null) {this.clipRef = registerClip(getClip());}
        StringBuilder b = new StringBuilder();
        b.append("clip-path=\"url(#").append(this.clipRef).append(")\"");
        return b.toString();
    }

    /**
     * Sets the attributes of the reusable {@link Rectangle2D} object that is
     * used by the {@link SVGGraphics2D#drawRect(int, int, int, int)} and
     * {@link SVGGraphics2D#fillRect(int, int, int, int)} methods.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     */
    private void setRect(int x, int y, int width, int height) {
        if (this.rect == null) {this.rect = new Rectangle2D.Double(x, y, width, height);}
        else {this.rect.setRect(x, y, width, height);}
    }

    /**
     * Sets the attributes of the reusable {@link RoundRectangle2D} object that
     * is used by the {@link #drawRoundRect(int, int, int, int, int, int)} and
     * {@link #fillRoundRect(int, int, int, int, int, int)} methods.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     * @param arcWidth  the arc width.
     * @param arcHeight  the arc height.
     */
    private void setRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        if (this.roundRect == null) {
            this.roundRect = new RoundRectangle2D.Double(x, y, width, height, arcWidth, arcHeight);
        } else {
            this.roundRect.setRoundRect(x, y, width, height, arcWidth, arcHeight);
        }
    }

    /**
     * Sets the attributes of the reusable {@link Arc2D} object that is used by
     * {@link #drawArc(int, int, int, int, int, int)} and
     * {@link #fillArc(int, int, int, int, int, int)} methods.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     * @param startAngle  the start angle in degrees, 0 = 3 o'clock.
     * @param arcAngle  the angle (anticlockwise) in degrees.
     */
    private void setArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        if (this.arc == null) {
            this.arc = new Arc2D.Double(x, y, width, height, startAngle, arcAngle, Arc2D.PIE);
        } else {
            this.arc.setArc(x, y, width, height, startAngle, arcAngle, Arc2D.PIE);
        }
    }

    /**
     * Sets the attributes of the reusable {@link Ellipse2D} object that is
     * used by the {@link #drawOval(int, int, int, int)} and
     * {@link #fillOval(int, int, int, int)} methods.
     *
     * @param x  the x-coordinate.
     * @param y  the y-coordinate.
     * @param width  the width.
     * @param height  the height.
     */
    private void setOval(int x, int y, int width, int height) {
        if (this.oval == null) {this.oval = new Ellipse2D.Double(x, y, width, height);}
        else {this.oval.setFrame(x, y, width, height);}
    }

}
