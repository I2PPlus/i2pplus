package net.i2p.router.web;

import java.awt.Color;
import java.awt.GradientPaint;
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
 * Plot colours for graph frames, overridable per theme with CSS custom properties.
 *
 * <p>Only the two plot hues and the two plot fills are themeable. Gridlines, axes, tick
 * labels and the canvas stay as they were; a frame is small enough that those are
 * structural rather than decorative.
 *
 * <p>A theme opts in by declaring variables in its {@code console.css}:
 * <pre>
 * :root{
 *   --graph_line_1:#64c8a0;
 *   --graph_path_1:#64c8a05a;
 *   --graph_dash:1.5;
 * }
 * </pre>
 * Anything absent keeps the built-in default, so no theme has to be edited and a typo in
 * one declaration costs that one colour rather than the whole frame.
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
    public static final String VAR_DASH = "--graph_dash";

    /** Text colour: axis labels, tick labels, the legend and the title. */
    public static final String VAR_FONT = "--graph_font";

    /** The two axis rules. */
    public static final String VAR_AXIS = "--graph_axis";

    /** The minor gridlines. */
    public static final String VAR_GRID = "--graph_grid";

    /** The major gridlines. */
    public static final String VAR_MGRID = "--graph_mgrid";

    /** Dot length for the minor gridlines; zero asks for solid gridlines. */
    public static final String VAR_GRID_DASH = "--graph_grid_dash";

    /** Dot length for the major gridlines; zero asks for solid gridlines. */
    public static final String VAR_MGRID_DASH = "--graph_mgrid_dash";

    private static final String VAR_LINE_PREFIX = "--graph_line_";
    private static final String VAR_PATH_PREFIX = "--graph_path_";

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
     * The stroke for each theme and plot: a literal mirror of the {@code --graph_line_*}
     * values the shipped stylesheets declare.
     *
     * <p>Listed rather than derived, because the variables are the canonical statement of a
     * theme's palette. A derivation cannot also be canonical: a stylesheet may declare any
     * colour it likes, and a frame drawn with no stylesheet has to come out the same as the
     * same frame drawn with one.
     *
     * <p>Light, classic and midnight plot the two inks their minigraphs plot, so a frame on a
     * console page reads as the same two series as the sparklines beside it. Dark states its
     * own pair.
     */
    private static final Color[][] PLOT_LINE = {
        { new Color(0x44, 0x88, 0xff), new Color(0x44, 0xaa, 0x88) },  // light, classic
        { new Color(0x37, 0xc8, 0x8e, 0x99), new Color(0xc8, 0xc8, 0x37, 0x99) },  // dark
        { new Color(0xff, 0x55, 0x00, 0x80), new Color(0x88, 0x00, 0x88, 0x80) },  // midnight
    };

    /**
     * The fill for each theme and plot: a literal mirror of the {@code --graph_path_*}
     * values the shipped stylesheets declare, alpha included.
     *
     * <p>The fills are translucent because a frame carries two of them and they overlap
     * wherever the series cross. At the 78-86% the fills used to carry, the crossing was so
     * nearly opaque that neither series read through it - the shape you most want to see was
     * the one you could not.
     *
     * <p>Alpha is the theme's own rather than one value for all of them, and no more than that:
     * light and classic ask for half, dark a quarter, midnight a little under a fifth on the
     * first fill and a little under a third on the second. Midnight's canvas is the darkest of
     * the four, so the same translucency there reads stronger than it does on a light page, and
     * a dark fill drawn on top of it barely reads at all.
     */
    private static final Color[][] PLOT_FILL = {
        { new Color(0x44, 0x88, 0xff, 0x80), new Color(0x44, 0xaa, 0x88, 0x80) },
        { new Color(0x00, 0x48, 0x08, 0x40), new Color(0xc8, 0xc8, 0x37, 0x40) },
        { new Color(0xff, 0x55, 0x00, 0x30), new Color(0x88, 0x00, 0x88, 0x50) },
    };

    /**
     * Built-in stroke for a plot, from {@link #PLOT_LINE}.
     *
     * @param themeIdx 0 for plain, 1 for dark, 2 for midnight
     * @param plot the plot ordinal
     * @return the theme's stroke for that plot
     */
    private static Color defaultLine(int themeIdx, int plot) {
        return PLOT_LINE[themeIdx][clampPlot(plot)];
    }

    /**
     * Built-in fill for a plot, from {@link #PLOT_FILL}.
     *
     * @param themeIdx 0 for plain, 1 for dark, 2 for midnight
     * @param plot the plot ordinal
     * @return the theme's fill for that plot, alpha included
     */
    private static Color defaultPath(int themeIdx, int plot) {
        return PLOT_FILL[themeIdx][clampPlot(plot)];
    }

    /** Series dash on-length per theme; dark draws its series solid, the rest dotted. */
    private static final float[] DEFAULT_DASH = { 1f, 0f, 1f };

    /**
     * Minor gridline dot and gap per theme.
     *
     * <p>Light and classic draw them solid; dark and midnight dash them, so a gridline reads
     * as texture behind the series rather than as a rule competing with them.
     */
    private static final float[][] GRID_DASH_DEFAULT = {
        { 0f, 0f }, { 1f, 2f }, { 1f, 3f },
    };

    /** Major gridline dot and gap per theme; the same pattern as the minor grid's. */
    private static final float[][] MGRID_DASH_DEFAULT = {
        { 0f, 0f }, { 1f, 2f }, { 1f, 3f },
    };

    /** Text colour per theme, each matching the ink its page draws with. */
    private static final Color[] FONT_DEFAULT = {
        new Color(51, 51, 63), new Color(244, 244, 190), new Color(201, 206, 255),
    };

    /** Axis rule colour per theme, matching the text that sits on it. */
    private static final Color[] AXIS_DEFAULT = {
        new Color(51, 51, 63), new Color(244, 244, 190), new Color(201, 206, 255),
    };

    /** Minor gridline colour per theme, faint enough to sit behind the series. */
    private static final Color[] GRID_DEFAULT = {
        new Color(80, 80, 80, 0x32), new Color(244, 244, 190, 0x1e),
        new Color(201, 206, 255, 0x20),
    };

    /**
     * Major gridline colour per theme, the one a theme wants read as a division.
     *
     * <p>Light picks a hue of its own to mark the division; dark and midnight keep the ink
     * of their minor grid and give it twice the weight, which is the whole of the difference.
     */
    private static final Color[] MGRID_DEFAULT = {
        new Color(0xff, 0x5b, 0x5b, 0x6e), new Color(0xc8, 0xc8, 0x00, 0x32),
        new Color(201, 206, 255, 0x40),
    };

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
        String var = VAR_LINE_PREFIX + (plot + 1);
        Color declared = declared(themeDir, theme, var);
        // A theme that picked a colour picked it on purpose, so it is used as written.
        return declared != null ? declared : defaultLine(themeIndex(theme), clampPlot(plot));
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
        String var = VAR_PATH_PREFIX + (clampPlot(plot) + 1);
        Color declared = declaredInk(variables(themeDir, theme).get(var));
        return declared != null ? declared : defaultPath(themeIndex(theme), plot);
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
     * <p>Two stops is what a {@link GradientPaint} takes, so a third in a declaration is not
     * painted; the wash runs between the first two.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first path and 1 for the second
     * @param frameHeight the height of the frame in pixels, which a gradient spans
     * @return the fill paint, never null; falls back to the built-in colour
     * @since 0.9.71+
     */
    public static Paint pathPaint(File themeDir, String theme, int plot, int frameHeight) {
        String var = VAR_PATH_PREFIX + (clampPlot(plot) + 1);
        List<Color> stops = gradientStops(variables(themeDir, theme).get(var));
        if (stops != null && stops.size() > 1) {
            // A gradient whose endpoints coincide would be rejected as a zero-length span,
            // and a frame is always at least one pixel tall.
            double span = Math.max(1d, frameHeight);
            return new GradientPaint(new Point2D.Double(0, 0), stops.get(0),
                                     new Point2D.Double(0, span), stops.get(1));
        }
        return pathColor(themeDir, theme, plot);
    }

    /**
     * The dot ink length for the pattern that distinguishes the second plot from the first.
     *
     * <p>{@code --graph_dash} takes either a bare length or a CSS dash pair:
     * <pre>
     *   --graph_dash:1        a 1px dot, gap derived from the line width
     *   --graph_dash:1 3      a 1px dot followed by 3px of space
     *   --graph_dash:1,3      the same, for a comma-separated habit
     *   --graph_dash:0        no pattern at all: a solid line
     *   --graph_dash:0 3      the same; a gap needs a dot to follow it
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
     * One frame element's colour: the theme's, or the built-in one for that theme.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param var the custom property the element reads
     * @param defaults the built-in colour per theme, indexed by {@link #themeIndex}
     * @return the colour, never null
     */
    private static Color element(File themeDir, String theme, String var, Color[] defaults) {
        String v = variables(themeDir, theme).get(var);
        Color declared = v != null ? SvgColor.parse(v) : null;
        return declared != null ? declared : defaults[themeIndex(theme)];
    }

    /**
     * The text colour: axis labels, tick labels, the legend and the title.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color fontColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_FONT, FONT_DEFAULT);
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
        return element(themeDir, theme, VAR_AXIS, AXIS_DEFAULT);
    }

    /**
     * The minor gridline colour.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color gridColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_GRID, GRID_DEFAULT);
    }

    /**
     * The major gridline colour.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the colour, never null
     * @since 0.9.71+
     */
    public static Color mgridColor(File themeDir, String theme) {
        return element(themeDir, theme, VAR_MGRID, MGRID_DEFAULT);
    }

    /**
     * Dot length for the minor gridlines, or zero for solid ones.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the dot length in pixels
     * @since 0.9.71+
     */
    public static float gridDash(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_GRID_DASH, GRID_DASH_DEFAULT[themeIndex(theme)])[0];
    }

    /**
     * The space after each minor gridline dot, or zero to derive it from the stroke width.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the stated gap in pixels, or zero to derive one
     * @since 0.9.71+
     */
    public static float gridDashGap(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_GRID_DASH, GRID_DASH_DEFAULT[themeIndex(theme)])[1];
    }

    /**
     * Dot length for the major gridlines, or zero for solid ones.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the dot length in pixels
     * @since 0.9.71+
     */
    public static float mgridDash(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_MGRID_DASH, MGRID_DASH_DEFAULT[themeIndex(theme)])[0];
    }

    /**
     * The space after each major gridline dot, or zero to derive it.
     *
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @return the stated gap in pixels, or zero to derive one
     * @since 0.9.71+
     */
    public static float mgridDashGap(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_MGRID_DASH, MGRID_DASH_DEFAULT[themeIndex(theme)])[1];
    }

    /**
     * Parse a dash variable into a dot length and a stated gap.
     *
     * @param fallback the theme's built-in {@code {dot, gap}}, used when the variable is
     *                 absent or unusable
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
     * Parse {@code --graph_dash} into a dot length and a stated gap.
     *
     * @return {@code {dot, gap}}, with a dot of zero meaning "no pattern" and a gap of
     *         zero meaning "derive it"; never null
     */
    private static float[] dash(File themeDir, String theme) {
        return dash(themeDir, theme, VAR_DASH,
                    new float[] { DEFAULT_DASH[themeIndex(theme)], 0f });
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
        if (!text.regionMatches(true, 0, LINEAR_GRADIENT, 0,
                                LINEAR_GRADIENT.length())) {return null;}
        int close = text.lastIndexOf(')');
        if (close < LINEAR_GRADIENT.length()) {return null;}
        List<Color> stops = new ArrayList<>();
        for (String stop : text.substring(LINEAR_GRADIENT.length(), close).split(",")) {
            String term = stop.trim();
            if (term.isEmpty() || GRADIENT_DIRECTION.matcher(term).matches()) {continue;}
            Color c = SvgColor.parse(term.split("\\s+")[0]);
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

    /** Theme ordinal for the built-in tables; anything unrecognised is the plain theme. */
    private static int themeIndex(String theme) {
        if ("dark".equals(theme)) {return 1;}
        if ("midnight".equals(theme)) {return 2;}
        return 0;
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