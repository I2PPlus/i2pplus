package net.i2p.router.web;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.i2p.I2PAppContext;

import org.jfree.svg.SvgColor;

/**
 * The display values a graph frame draws with, overridable per theme with CSS custom
 * properties.
 *
 * <p>Seventeen variables cover every themed part of a tile: the two plot strokes and fills,
 * the series dot pattern, both stroke widths, the text colour, the axis rules, both grids and
 * their dot patterns, the plot background, the edge shading, the restart rule, and the
 * gridline a tile too small for a minor grid keeps.
 *
 * <p>Fonts are the exception and are not here. A graph reads {@code --monospaced} and
 * {@code --bodyfont} from the console's own font stylesheet, so a second pair here could only
 * disagree with the page it is drawn on.
 *
 * <p><b>The stylesheet is the canonical statement of a theme's look.</b> The values held in
 * this class are one fallback set - light's, because light is the console's default look -
 * reached only when a theme's stylesheet declares nothing at all: an unknown theme name, or a
 * layout with no stylesheet. Neither case has a theme identity to honour, so nothing here is
 * per-theme; anything that genuinely differs between themes differs in the CSS.
 *
 * <p>A theme opts in by declaring variables in its {@code console.css}:
 * <pre>
 * :root{
 *   --graph_plotLine1:#64c8a0;
 *   --graph_plotFill1:#64c8a05a;
 *   --graph_plotDash:1.5;
 *   --graph_plotLineWidthWide:2.5;
 * }
 * </pre>
 * Anything absent keeps the built-in default, so no theme has to be edited and a typo in one
 * declaration costs that one value rather than the whole frame.
 *
 * <p>Not every stroke width is the theme's: {@link GraphRenderer} overrides it for a grouped
 * axis, a crowded tile and the sidebar sparkline, because those are legibility decisions about
 * one layout rather than a look.
 *
 * <h2>Why the file is read server-side</h2>
 * A graph image is served as {@code <img src="/viewstat.jsp?...">}, which makes the SVG an
 * isolated document: page CSS does not reach it and page custom properties are invisible
 * inside. Declaring the variables in the SVG itself would only let a user edit a file they
 * cannot reach, so the theme's own stylesheet is parsed here and the resolved values are
 * handed to the renderer. That also means {@code getComputedStyle} is not involved and the
 * minigraph's client-side path does not apply here.
 *
 * <h2>Caching</h2>
 * One small file per theme, parsed at most once and held in a
 * {@link ConcurrentHashMap}, since every graph tile on a console page asks for the same
 * palette. Themes do not change while the router runs, so there is no invalidation hook;
 * a theme edit takes effect on the next restart, the same as any other installed resource.
 *
 * @since 0.9.71+
 */
public final class GraphThemeColors {

    /**
     * Where the console keeps its themes, relative to the router base directory.
     *
     * <p>{@code docs/themes/console}, which is where the theme picker scans
     * ({@code ConfigUIHelper}) and where an installed router puts them - not under
     * {@code webapps}, which holds the deployed console war. Getting this wrong is silent:
     * every lookup takes the "no stylesheet" path and falls back to the built-in defaults,
     * so a theme edit appears to do nothing at all.
     */
    public static final String THEME_SUBDIR = "docs/themes/console";

    /** Dash length, in pixels, of the multi-plot dot pattern; zero asks for a solid line. */
    public static final String VAR_PLOT_DASH = "--graph_plotDash";

    /** Text colour: axis labels, tick labels, the legend and the title. */
    public static final String VAR_TEXT_COLOR = "--graph_textColor";

    /** The two axis rules. */
    public static final String VAR_AXIS_COLOR = "--graph_axisColor";

    /** The minor gridlines. */
    public static final String VAR_GRID_MINOR = "--graph_gridMinor";

    /** The major gridlines. */
    public static final String VAR_GRID_MAJOR = "--graph_gridMajor";

    /** Dot length for the minor gridlines; zero asks for solid gridlines. */
    public static final String VAR_GRID_MINOR_DASH = "--graph_gridMinorDash";

    /** Dot length for the major gridlines; zero asks for solid gridlines. */
    public static final String VAR_GRID_MAJOR_DASH = "--graph_gridMajorDash";

    /** The plot area's own background, behind the gridlines. */
    public static final String VAR_BACKGROUND = "--graph_background";

    /** The shading band drawn along the bottom and left edges of the plot area. */
    public static final String VAR_EDGE_SHADE = "--graph_edgeShade";

    /** The vertical rule and legend entry marking a router restart. */
    public static final String VAR_RESTART_MARKER = "--graph_restartMarker";

    /**
     * The gridline a tile too small for a minor grid keeps, which is not necessarily either
     * of its two grids: the shipped themes split on which one they would rather drop.
     */
    public static final String VAR_GRID_COMPACT = "--graph_gridCompact";

    /**
     * Stroke width, in pixels, of a plot line on a tile up to {@link #WIDE_WIDTH}.
     *
     * @since 0.9.71+
     */
    public static final String VAR_PLOT_LINE_WIDTH = "--graph_plotLineWidth";

    /**
     * Stroke width, in pixels, of a plot line past {@link #WIDE_WIDTH}, where the series is
     * long enough that a thinner stroke breaks up into dashes.
     *
     * @since 0.9.71+
     */
    public static final String VAR_PLOT_LINE_WIDTH_WIDE = "--graph_plotLineWidthWide";



    /**
     * Frame width, in pixels, past which {@link #VAR_PLOT_LINE_WIDTH_WIDE} replaces
     * {@link #VAR_PLOT_LINE_WIDTH}.
     *
     * <p>A layout constant rather than a theme choice: a tile is drawn wider when the console
     * has room for it, and a longer series shows a thinner stroke's gaps more readily, so the
     * two are the same decision seen from either end.
     *
     * @since 0.9.71+
     */
    public static final int WIDE_WIDTH = 800;

    /** Prefix of the per-plot stroke variables; {@code --graph_plotLine1}, {@code --graph_plotLine2}. */
    static final String VAR_PLOT_LINE_PREFIX = "--graph_plotLine";

    /** Prefix of the per-plot fill variables; {@code --graph_plotFill1}, {@code --graph_plotFill2}. */
    static final String VAR_PLOT_FILL_PREFIX = "--graph_plotFill";

    /** Plots per frame. Two by contract: see {@link GraphGroups#MAX_SERIES}. */
    public static final int PLOTS = 2;

    /** Matches one custom property declaration, anywhere in the stylesheet. */
    private static final Pattern DECL =
        Pattern.compile("--([A-Za-z0-9_-]+)\\s*:\\s*([^;}]+)");

    /** The gradient function a fill variable may use; matched case-insensitively. */
    private static final String LINEAR_GRADIENT = "linear-gradient(";

    /** A gradient's leading direction, which an area under a series does not paint. */
    private static final Pattern GRADIENT_DIRECTION =
        Pattern.compile("to\\s+(?:top|bottom|left|right)|\\d+(?:\\.\\d+)?deg",
                         Pattern.CASE_INSENSITIVE);

    /** theme directory plus theme name to its parsed declarations. */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** Marker for "the stylesheet is not there", which is a cacheable state of its own. */
    private static final long NO_FILE = Long.MIN_VALUE;

    /**
     * How long a parse may be reused even when the timestamp looks unchanged, in ms.
     *
     * <p>The timestamp check is what normally keeps this current, and it means an edit is
     * visible on the very next page load. This interval is the fallback for a filesystem
     * that does not maintain last-modified reliably - a network mount, some container
     * overlays, or a coarse timestamp that hides two saves in the same second. Re-reading
     * on a timer as well costs one small file read per interval, which is nothing next to
     * rendering the graph itself.
     *
     * <p>Not a static final so tests can shorten it instead of sleeping.
     */
    static long maxCacheAgeMs = 30_000L;

    /**
     * A theme's parsed declarations and the timestamps they were parsed from.
     *
     * <p>The timestamp is what makes a theme edit visible without a restart. The minigraph
     * picks up a CSS change on page refresh because the browser re-reads the stylesheet;
     * these colours are resolved on the server, so nothing would re-read them and an edit
     * would otherwise need a router restart to show up.
     */
    private static final class Entry {
        final long modified;
        final long parsedAt;
        final Map<String, String> variables;

        Entry(long modified, long parsedAt, Map<String, String> variables) {
            this.modified = modified;
            this.parsedAt = parsedAt;
            this.variables = variables;
        }

        /** Whether this entry may still be served, given the files' stamps and the clock. */
        boolean usable(long modified, long now) {
            return this.modified == modified && now - this.parsedAt < maxCacheAgeMs;
        }
    }

    /**
     * Fallback stroke for each plot, used only when a theme declares no {@code --graph_plotLine<N>}.
     *
     * <p>Light's pair, because light is the console's default look. A theme with a palette of
     * its own states it in its own {@code console.css}, which is where the palette belongs: a
     * derivation here could not also be canonical, since a stylesheet may declare any colour
     * it likes.
     */
    private static final Color[] PLOT_LINE = {
        new Color(0x44, 0x88, 0xff, 0xaa), new Color(0x16, 0xc5, 0x99, 0xaa),
    };

    /**
     * Fallback fill for each plot, used only when a theme declares no {@code --graph_plotFill<N>}.
     *
     * <p>Light's pair, alpha included, for the reason given on {@link #PLOT_LINE}.
     *
     * <p>The fills are translucent because a frame carries two of them and they overlap
     * wherever the series cross. At the 78-86% the fills used to carry, the crossing was so
     * nearly opaque that neither series read through it - the shape you most want to see was
     * the one you could not.
     */
    private static final Color[] PLOT_FILL = {
        new Color(0x44, 0x88, 0xff, 0x60), new Color(0x16, 0xc5, 0x99, 0x40),
    };

    /**
     * Fallback stroke for a plot.
     *
     * @param plot the plot ordinal
     * @return the built-in stroke, used when the theme declares no {@code --graph_plotLine<N>}
     */
    private static Color defaultLine(int plot) {
        return PLOT_LINE[clampPlot(plot)];
    }

    /**
     * Fallback fill for a plot.
     *
     * @param plot the plot ordinal
     * @return the built-in fill, used when the theme declares no {@code --graph_plotFill<N>}
     */
    private static Color defaultPath(int plot) {
        return PLOT_FILL[clampPlot(plot)];
    }

    /**
     * Fallback series dot length, used only when a theme declares no {@code --graph_plotDash}.
     *
     * <p>Zero asks for a solid line, which is what a frame with no theme to be should draw.
     * Every shipped theme asks for a solid series of its own, so none of them relies on it.
     */
    private static final float DEFAULT_DASH = 0f;

    /**
     * Fallback minor-gridline dot and gap, used only when a theme declares no
     * {@code --graph_gridMinorDash}. A zero dot is a solid gridline.
     */
    private static final float[] GRID_MINOR_DASH_DEFAULT = { 0f, 0f };

    /**
     * Fallback major-gridline dot and gap, used only when a theme declares no
     * {@code --graph_gridMajorDash}. A zero dot is a solid gridline.
     */
    private static final float[] GRID_MAJOR_DASH_DEFAULT = { 0f, 0f };

    /** Fallback text colour, used only when a theme declares no {@code --graph_textColor}. */
    private static final Color TEXT_COLOR_DEFAULT = new Color(51, 51, 63);

    /**
     * Fallback axis rule colour, used only when a theme declares no {@code --graph_axisColor}.
     *
     * <p>The text ink, since an axis carries the labels that sit along it.
     */
    private static final Color AXIS_COLOR_DEFAULT = new Color(51, 51, 63);

    /**
     * Fallback minor gridline colour, used only when a theme declares no {@code --graph_gridMinor}.
     * Faint enough to sit behind the series.
     */
    private static final Color GRID_MINOR_DEFAULT = new Color(0xff, 0x5b, 0x5b, 0x33);

    /**
     * Fallback major gridline colour, used only when a theme declares no {@code --graph_gridMajor}.
     * Twice the minor grid's weight, which is the whole of the difference from it.
     */
    private static final Color GRID_MAJOR_DEFAULT = new Color(0xff, 0x5b, 0x5b, 0x66);

    /**
     * Fallback plot background, used only when a theme declares no {@code --graph_background}.
     * Opaque, since it is what a frame with no stylesheet sits on.
     */
    private static final Color BACKGROUND_DEFAULT = new Color(255, 255, 255);

    /** Fallback edge shading, used only when a theme declares no {@code --graph_edgeShade}. */
    private static final Color EDGE_SHADE_DEFAULT = new Color(255, 255, 255);

    /** Fallback restart rule colour, used only when a theme declares no {@code --graph_restartMarker}. */
    private static final Color RESTART_MARKER_DEFAULT = new Color(223, 13, 13);

    /**
     * Fallback gridline for a tile too small for a minor grid, used only when a theme declares
     * no {@code --graph_gridCompact}. Light's, which is its major grid.
     */
    private static final Color GRID_COMPACT_DEFAULT = new Color(0xff, 0x5b, 0x5b, 0x66);

    /**
     * Fallback plot-line stroke width, used only when a theme declares no
     * {@code --graph_plotLineWidth}.
     *
     * <p>Light's, and what every shipped theme declares today. The renderer has separate
     * reasons to draw a thinner or thicker line - a dense tile, the sidebar sparkline - and
     * those stay in {@code GraphRenderer} rather than here: they are legibility decisions
     * about one layout, not something a theme picks.
     */
    private static final float PLOT_LINE_WIDTH_DEFAULT = 2f;

    /**
     * Fallback plot-line stroke width past {@link #WIDE_WIDTH}, used only when a theme
     * declares no {@code --graph_plotLineWidthWide}.
     *
     * <p>Equal to {@link #PLOT_LINE_WIDTH_DEFAULT}, which is what the width rule did before
     * either variable existed. The two are separate so a theme can weight a long series more
     * heavily without changing the rest of the console.
     */
    private static final float PLOT_LINE_WIDTH_WIDE_DEFAULT = 2.5f;

    /**
     * Bounds on a declared stroke width: a hairline is not a line, and a width past the point
     * of covering the data is not a theme's to choose.
     */
    private static final float MIN_LINE_WIDTH = 0.1f;
    private static final float MAX_LINE_WIDTH = 16f;

    /** Longest dash array accepted: one dot and one gap. */
    private static final int MAX_DASH_VALUES = 2;

    private GraphThemeColors() {}

    /**
     * The stroke colour for a plot on a frame.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first line and 1 for the second
     * @return the colour, never null; falls back to the built-in value
     */
    public static Color lineColor(File themeDir, String theme, int plot) {
        String var = VAR_PLOT_LINE_PREFIX + (plot + 1);
        // Read for its ink rather than as a plain colour, so a plot line declaring a list of
        // stops reports its first one. That is the colour a legend swatch needs: a gradient
        // cannot be shown in a swatch, and without this a themed list would silently leave the
        // legend in the built-in colour instead of the theme's own.
        Color declared = declaredInk(variables(themeDir, theme).get(var));
        // A theme that picked a colour picked it on purpose, so it is used as written.
        return declared != null ? declared : defaultLine(plot);
    }

    /**
     * The stops that shade plot lines by their value, or null when lines are drawn flat.
     *
     * <p>A plot line's declaration is read for this as well as for {@link #lineColor}, so a
     * theme opts in by naming two or more colours where it used to name one:
     *
     * <pre>
     *   --graph_plotLine1:#2ec23e40 #f0000008 #d0000008;
     * </pre>
     *
     * <p>The line is then drawn with a vertical gradient across the plot area, so the colour at
     * a point is the colour for the value at that point - height <em>is</em> the value. The
     * declared single colour still does its other jobs: it is the legend swatch, and it is what
     * is drawn when the plot area is too short to span.
     *
     * <p>Read per plot, so the two lines on one axis are shaded independently like every other
     * plot colour. A theme can shade one line by value and leave the other flat.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first line and 1 for the second
     * @return bottom-to-top stops, or null when the theme named one colour or fewer
     * @since 0.9.71+
     */
    public static Color[] lineValueShade(File themeDir, String theme, int plot) {
        String var = VAR_PLOT_LINE_PREFIX + (clampPlot(plot) + 1);
        List<Color> stops = gradientStops(variables(themeDir, theme).get(var));
        if (stops == null || stops.size() < 2) {
            return null;
        }
        return stops.toArray(new Color[stops.size()]);
    }

    /**
     * The fill colour for a plot on a frame.
     *
     * <p>A {@code linear-gradient()} declaration has no single colour, so its first stop is
     * reported here: that is the ink the fill is made of. {@link #pathPaint} returns the whole
     * wash.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first path and 1 for the second
     * @return the colour, never null; falls back to the built-in value
     */
    public static Color pathColor(File themeDir, String theme, int plot) {
        String var = VAR_PLOT_FILL_PREFIX + (clampPlot(plot) + 1);
        Color declared = declaredInk(variables(themeDir, theme).get(var));
        return declared != null ? declared : defaultPath(plot);
    }

    /**
     * The fill for a plot as something to paint with: a colour, or a vertical gradient.
 *
     * <p>A gradient needs a length to span, and the area under a series is a vertical wash,
     * so the frame's height is the caller's to supply. Only the CSS default direction - top
     * to bottom, which is what the minigraph's own fill variables use - is painted; a stated
     * direction this class cannot honour is treated as no opinion rather than a guess, and
     * the fill comes out as its ink.
     *
     * <p>Two stops are painted as a {@link GradientPaint} and three or more as a
     * {@link LinearGradientPaint}, which is the only one of the two that holds an arbitrary
     * number. Either way the stops are spread evenly down the span, so a third stop lands a
     * third of the way down rather than wherever the declaration happened to say.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first path and 1 for the second
     * @param frameHeight the height of the frame in pixels, which a gradient spans
     * @return the fill paint, never null; falls back to the built-in colour
     * @since 0.9.71+
     */
    public static Paint pathPaint(File themeDir, String theme, int plot, int frameHeight) {
        String var = VAR_PLOT_FILL_PREFIX + (clampPlot(plot) + 1);
        List<Color> stops = gradientStops(variables(themeDir, theme).get(var));
        if (stops == null || stops.size() < 2) {
            return pathColor(themeDir, theme, plot);
        }
        // A gradient whose endpoints coincide would be rejected as a zero-length span,
        // and a frame is always at least one pixel tall.
        double span = Math.max(1d, frameHeight);
        if (stops.size() == 2) {
            return new GradientPaint(new Point2D.Double(0, 0), stops.get(0),
                                     new Point2D.Double(0, span), stops.get(1));
        }
        // Three or more stops need LinearGradientPaint: GradientPaint holds exactly two, so
        // truncating would drop the stops a theme wrote in order to shape the falloff - the
        // point of naming three is usually to fade out faster at the top.
        float[] fractions = new float[stops.size()];
        for (int i = 0; i < fractions.length; i++) {
            fractions[i] = (float) i / (fractions.length - 1);
        }
        return new LinearGradientPaint(new Point2D.Double(0, 0),
                                       new Point2D.Double(0, span), fractions,
                                       stops.toArray(new Color[stops.size()]));
    }

    /**
     * The dot ink length for the pattern that distinguishes the second plot from the first.
     *
     * <p>{@code --graph_plotDash} takes either a bare length or a CSS dash pair:
     * <pre>
     *   --graph_plotDash:1        a 1px dot, gap derived from the line width
     *   --graph_plotDash:1 3      a 1px dot followed by 3px of space
     *   --graph_plotDash:1,3      the same, for a comma-separated habit
     *   --graph_plotDash:0        no pattern at all: a solid line
     *   --graph_plotDash:0 3      the same; a gap needs a dot to follow it
     * </pre>
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the dot length in pixels, or zero when the stylesheet asked for a solid line
     */
    public static float dashLength(File themeDir, String theme) {
        return dash(themeDir, theme)[0];
    }

    /**
     * The space after each dot, as stated by the theme, or zero when the renderer should
     * derive it from the line width.
     *
     * <p>A stated value is not a promise: {@link org.rrd4j.graph.RrdGraphConstants} raises
     * anything too tight to keep the dots apart, because with round caps each dot lays down
     * {@code dot + width} of ink and a gap at or below the width merges them into a solid
     * line. Reporting the raw value keeps this class a plain reader of the stylesheet.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the stated gap in pixels, or zero to derive one
     * @since 0.9.71
     */
    public static float dashGap(File themeDir, String theme) {
        return dash(themeDir, theme)[1];
    }

    /**
     * One frame element's colour: the theme's, or the built-in fallback.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param var the custom property the element reads
     * @param fallback the built-in colour, used when the theme declares nothing usable
     * @return the colour, never null
     */
    private static Color element(File themeDir, String theme, String var, Color fallback) {
        Color declared = declared(themeDir, theme, var);
        return declared != null ? declared : fallback;
    }

    /**
     * The text colour: axis labels, tick labels, the legend and the title.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color textColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_TEXT_COLOR, TEXT_COLOR_DEFAULT);
    }

    /**
     * The axis rule colour.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color axisColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_AXIS_COLOR, AXIS_COLOR_DEFAULT);
    }

    /**
     * The minor gridline colour.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color gridMinorColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_GRID_MINOR, GRID_MINOR_DEFAULT);
    }

    /**
     * The major gridline colour.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color gridMajorColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_GRID_MAJOR, GRID_MAJOR_DEFAULT);
    }

    /**
     * Dot length for the minor gridlines, or zero for solid ones.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the dot length in pixels
     * @since 0.9.71+
     */
    public static float gridMinorDash(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_GRID_MINOR_DASH, GRID_MINOR_DASH_DEFAULT)[0];
    }

    /**
     * The space after each minor gridline dot, or zero to derive it from the stroke width.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the stated gap in pixels, or zero to derive one
     * @since 0.9.71+
     */
    public static float gridMinorDashGap(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_GRID_MINOR_DASH, GRID_MINOR_DASH_DEFAULT)[1];
    }

    /**
     * Dot length for the major gridlines, or zero for solid ones.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the dot length in pixels
     * @since 0.9.71+
     */
    public static float gridMajorDash(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_GRID_MAJOR_DASH, GRID_MAJOR_DASH_DEFAULT)[0];
    }

    /**
     * The space after each major gridline dot, or zero to derive it.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the stated gap in pixels, or zero to derive one
     * @since 0.9.71+
     */
    public static float gridMajorDashGap(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_GRID_MAJOR_DASH, GRID_MAJOR_DASH_DEFAULT)[1];
    }

    /**
     * The plot area's own background: the rectangle behind the gridlines.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color backgroundColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_BACKGROUND, BACKGROUND_DEFAULT);
    }

    /**
     * The shading band along the bottom and left edges of the plot area.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null; fully transparent where a theme drops the band
     * @since 0.9.71+
     */
    public static Color edgeShadeColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_EDGE_SHADE, EDGE_SHADE_DEFAULT);
    }

    /**
     * The vertical rule and legend entry marking a router restart.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color restartMarkerColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_RESTART_MARKER, RESTART_MARKER_DEFAULT);
    }

    /**
     * The gridline a tile too small for a minor grid keeps in place of the minor grid.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color compactGridColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_GRID_COMPACT, GRID_COMPACT_DEFAULT);
    }

    /**
     * Stroke width of a plot line on a tile no wider than {@link #WIDE_WIDTH}.
     *
     * <p>A renderer decision outranks this one - see {@link GraphRenderer} for the dense-tile
     * and sparkline widths - so a theme sets the weight of an ordinary plot, not every plot.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the width in pixels, never zero or negative
     * @since 0.9.71+
     */
    public static float plotLineWidth(File themeDir, String theme) {
        return length(themeDir, theme, VAR_PLOT_LINE_WIDTH, PLOT_LINE_WIDTH_DEFAULT);
    }

    /**
     * Stroke width of a plot line on a tile wider than {@link #WIDE_WIDTH}.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the width in pixels, never zero or negative
     * @see #plotLineWidth(File, String)
     * @since 0.9.71+
     */
    public static float plotLineWidthWide(File themeDir, String theme) {
        return length(themeDir, theme, VAR_PLOT_LINE_WIDTH_WIDE, PLOT_LINE_WIDTH_WIDE_DEFAULT);
    }



    /**
     * One declared length, bounded, or the built-in fallback.
     *
     * <p>An absent variable and an unusable one are the same case on purpose: both mean the
     * theme has no opinion, so both fall back rather than failing the render.
     *
     * @param fallback the built-in width, used when the variable is absent or out of range
     * @return the width in pixels, within {@link #MIN_LINE_WIDTH} and {@link #MAX_LINE_WIDTH}
     */
    private static float length(File themeDir, String theme, String var, float fallback) {
        String v = variables(themeDir, theme).get(var);
        if (v == null) {return fallback;}
        float parsed;
        try {
            parsed = Float.parseFloat(v.trim());
        } catch (NumberFormatException nfe) {
            return fallback;
        }
        if (Float.isNaN(parsed) || parsed < MIN_LINE_WIDTH || parsed > MAX_LINE_WIDTH) {
            return fallback;
        }
        return parsed;
    }


    /**
     * Parse a dash variable into a dot length and a stated gap.
     *
     * @param fallback the built-in {@code {dot, gap}}, used when the variable is absent or
     *                 unusable
     * @return {@code {dot, gap}}, with a dot of zero meaning "no pattern" and a gap of
     *         zero meaning "derive it"; never null
     */
    private static float[] dash(File themeDir, String theme, String var, float[] fallback) {
        String v = variables(themeDir, theme).get(var);
        if (v == null) {return fallback.clone();}
        float[] parsed = parseDashList(v);
        if (parsed == null || parsed.length == 0) {return fallback.clone();}
        float dot = parsed[0];
        // Zero is a request rather than a typo: no dot ink at all is a solid line.
        if (dot < 0f || dot >= 64f || Float.isNaN(dot)) {return fallback.clone();}
        float gap = parsed.length > 1 ? parsed[1] : 0f;
        if (Float.isNaN(gap) || gap < 0f || gap >= 256f) {gap = 0f;}
        return new float[] { dot, gap };
    }

    /**
     * Parse {@code --graph_plotDash} into a dot length and a stated gap.
     *
     * @return {@code {dot, gap}}, with a dot of zero meaning "no pattern" and a gap of
     *         zero meaning "derive it"; never null
     */
    private static float[] dash(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_PLOT_DASH,
                    new float[] { DEFAULT_DASH, 0f });
    }

    /**
     * Read one or two whitespace- or comma-separated lengths.
     *
     * <p>Longer lists are refused rather than truncated. CSS would cycle an odd-length
     * {@code stroke-dasharray} to make it even, but the minimum-gap rule below is stated
     * for a single dot and gap, and silently dropping the rest would render something the
     * stylesheet never asked for.
     *
     * @return the values, or null when the text is not a usable dash list
     */
    private static float[] parseDashList(String value) {
        String[] parts = value.trim().split("[,\\s]+");
        if (parts.length == 0 || parts.length > MAX_DASH_VALUES) {return null;}
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i].trim();
            if (t.isEmpty()) {return null;}
            try {
                out[i] = Float.parseFloat(t);
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        return out;
    }

    private static int clampPlot(int plot) {
        return plot < 0 ? 0 : (plot >= PLOTS ? PLOTS - 1 : plot);
    }

    /**
     * The colour a theme declared, or null when it declared nothing usable.
     *
     * <p>An absent variable and an unparseable one are the same case on purpose: both mean
     * "this theme has no opinion about this slot", so both fall back to the default.
     */
    private static Color declared(File themeDir, String theme, String var) {
        String v = variables(themeDir, theme).get(var);
        return v != null ? SvgColor.parse(v) : null;
    }

    /**
     * The ink a declaration names: a colour, or the first stop of a gradient.
     *
     * <p>A gradient is a wash rather than a colour, so a caller that wants one colour - a
     * legend swatch, an alpha check - gets the ink the wash is made of.
     */
    private static Color declaredInk(String value) {
        if (value == null) {return null;}
        List<Color> stops = gradientStops(value);
        return stops != null ? stops.get(0) : SvgColor.parse(value);
    }

    /**
     * The colour stops of a {@code linear-gradient()} declaration, in the order written.
     *
     * <p>A leading direction is skipped rather than obeyed: an area under a series is a
     * vertical wash, so only the endpoints say anything, and a direction this class cannot
     * paint is a no-op rather than a guess. A stop's stated position is dropped for the same
     * reason - it moves where the middle of the wash sits, not which inks it is between.
     *
     * @return the stops, or null when the value is not a gradient this class paints
     */
    private static List<Color> gradientStops(String value) {
        if (value == null) {return null;}
        String text = value.trim();
        if (text.regionMatches(true, 0, LINEAR_GRADIENT, 0, LINEAR_GRADIENT.length())) {
            int close = text.lastIndexOf(')');
            if (close < LINEAR_GRADIENT.length()) {return null;}
            return parseStops(splitGradientStops(text.substring(LINEAR_GRADIENT.length(), close)),
                              true);
        }
        // A bare list of two or more colours is a gradient, so a theme can shape a falloff
        // without wrapping it. Space-separated rather than comma-separated because a colour
        // may itself be a comma-separated function - rgba(0,0,0,.5) - and splitting on commas
        // would cut one in half.
        String[] terms = text.split("\\s+");
        if (terms.length < 2) {return null;}
        return parseStops(terms, false);
    }

    /**
     * Split a gradient's own stop list on its commas, ignoring commas inside parentheses.
     *
     * <p>A plain split would cut {@code rgba(0,0,0,.5)} into three fragments that parse as
     * nothing, which discarded the whole declaration and quietly drew a flat colour instead of
     * the gradient that was asked for. Splitting on top-level commas only keeps a colour
     * function intact, so the two spellings accept the same set of colours.
     *
     * @param body the text between {@code linear-gradient(} and its closing bracket
     * @return the stop expressions, which still need their colour parsed
     */
    private static String[] splitGradientStops(String body) {
        List<String> terms = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                terms.add(body.substring(from, i));
                from = i + 1;
            }
        }
        terms.add(body.substring(from));
        return terms.toArray(new String[terms.size()]);
    }

    /**
     * Colours from already-split terms, dropping a direction keyword where one is allowed.
     *
     * @param gradient true for a {@code linear-gradient()} stop list, where a leading
     *        direction keyword is allowed and has to be skipped
     * @return the colours, or null when any term is unusable or nothing remains
     */
    private static List<Color> parseStops(String[] terms, boolean gradient) {
        List<Color> stops = new ArrayList<>();
        for (String term : terms) {
            String t = term.trim();
            // Inside linear-gradient() a leading "to bottom" or "45deg" is a direction, and a
            // bare list has no room for one, so it is only skipped in the former case.
            if (t.isEmpty() || (gradient && GRADIENT_DIRECTION.matcher(t).matches())) {
                continue;
            }
            Color c = SvgColor.parse(t.split("\\s+")[0]);
            // One unusable stop makes the whole declaration unusable: a wash with a hole in it
            // would render as something the stylesheet never asked for.
            if (c == null) {return null;}
            stops.add(c);
        }
        return stops.isEmpty() ? null : stops;
    }

    /**
     * The directory the console is installed into, taken from the running context.
     *
     * @return the theme directory, or null when there is no context (unit tests, shutdown)
     */
    public static File installedThemeDir() {
        try {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            if (ctx == null) {return null;}
            return new File(ctx.getBaseDir(), THEME_SUBDIR);
        } catch (RuntimeException re) {
            return null;
        }
    }

    /**
     * The theme's declarations, re-parsed whenever the stylesheet changes.
     *
     * <p>A cached entry is reused while the file's last-modified stamp is unchanged and the
     * entry is younger than {@link #maxCacheAgeMs}, so the cost of a warm lookup is one
     * stat() rather than a re-read. The stamp is checked before the cache is consulted for a
     * second reason: a stylesheet that did not exist on an earlier lookup must not be
     * remembered as empty forever, or a theme deployed after startup would never take
     * effect.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the declarations, empty when the stylesheets are absent
     */
    private static Map<String, String> variables(File themeDir, String theme) {
        String key = (themeDir != null ? themeDir.getAbsolutePath() : "")
                     + '\u0000' + (theme != null ? theme : "");
        File css = themeFile(themeDir, theme);
        long modified = stamp(css);
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        if (cached != null && cached.usable(modified, now)) {return cached.variables;}
        // Two threads can race here and both parse. That is deliberate: computeIfAbsent
        // would hold a lock across the file read, and since both reads see the same bytes
        // the loser simply discards an identical result. Stamping parsedAt before returning
        // also coalesces the tiles of one page, so a page of twenty graphs re-reads the file
        // once rather than twenty times.
        Map<String, String> parsed = parse(css);
        CACHE.put(key, new Entry(modified, now, parsed));
        return parsed;
    }

    /**
     * Read a file's stamp, treating an absent file as its own cacheable state.
     *
     * @return the last-modified time, or {@link #NO_FILE}
     */
    private static long stamp(File file) {
        return (file != null && file.isFile()) ? file.lastModified() : NO_FILE;
    }

    /**
     * Read a theme's stylesheet and collect its custom properties.
     *
     * @return the declarations, empty when the file is absent or unreadable, which is the
     *         normal case in a source checkout where the theme lives under installer/
     */
    private static Map<String, String> parse(File css) {
        Map<String, String> out = new HashMap<>();
        if (css == null || !css.isFile()) {return out;}
        String text;
        try {
            text = new String(Files.readAllBytes(css.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            return out;
        }
        Matcher m = DECL.matcher(text);
        while (m.find()) {
            // Later declarations win, matching the cascade within one file.
            out.put("--" + m.group(1), m.group(2));
        }
        return out;
    }

    /**
     * Locate a theme's {@code console.css}.
     *
     * @return the file, or null when there is no theme directory to look in
     */
    private static File themeFile(File themeDir, String theme) {
        if (themeDir == null || theme == null || theme.isEmpty()) {return null;}
        return new File(new File(themeDir, theme), "console.css");
    }

    /**
     * Forget every parsed theme.
     *
     * <p>Not needed in normal operation: both the timestamp and the interval already pick
     * up edits. This exists so a caller that has just rewritten a stylesheet can force a
     * re-read without waiting.
     */
    static void clearCache() {CACHE.clear();}
}
