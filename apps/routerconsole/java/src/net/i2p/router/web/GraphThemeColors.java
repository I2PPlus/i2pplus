package net.i2p.router.web;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
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

    private static final String VAR_LINE_PREFIX = "--graph_line_";
    private static final String VAR_PATH_PREFIX = "--graph_path_";

    /** Plots per frame. Two by contract: see {@link GraphGroups#MAX_SERIES}. */
    public static final int PLOTS = 2;

    /** Matches one custom property declaration, anywhere in the stylesheet. */
    private static final Pattern DECL =
        Pattern.compile("--([A-Za-z0-9_-]+)\\s*:\\s*([^;}]+)");

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
     * A parsed stylesheet and the timestamp it was parsed from.
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

        /** Whether this entry may still be served, given the file's stamp and the clock. */
        boolean usable(long modified, long now) {
            return this.modified == modified && now - this.parsedAt < maxCacheAgeMs;
        }
    }

    /**
     * The colour each theme's first line is built from, before vividising.
     *
     * <p>These are the long-standing primary line colours. The second line is not listed
     * because it is derived from the first: see {@link #SECOND_PLOT_HUE}.
     */
    private static final Color[] PRIMARY_BASE = {
        new Color(0, 30, 110, 255),
        new Color(100, 200, 160),
        new Color(128, 180, 212),
    };

    /**
     * Opacity of a plot fill, as a fraction of full: 50%.
     *
     * <p>A frame can carry two fills, and they overlap wherever the series cross. At the
     * 78-86% the fills used to carry, the overlap was so nearly opaque that neither series
     * read through it - the shape you most want to see was the one you could not. Half
     * keeps each fill clearly present on its own while letting the other show through.
     *
     * <p>One knob for both plots, so the pair cannot drift apart.
     */
    private static final int PATH_ALPHA = 128;

    /** The fill under the first line, per theme; the alpha comes from {@link #PATH_ALPHA}. */
    private static final Color[] PRIMARY_FILL = {
        new Color(100, 160, 200),
        new Color(0, 72, 8),
        new Color(0, 72, 160),
    };

    /**
     * Absolute hue, in degrees, for the second plot: 60 is yellow.
     *
     * <p>The second line is the primary's saturation and brightness at this fixed hue, so
     * the pair reads as two lines of equal weight rather than one line and a highlight.
     * This is derived rather than listed per theme because a theme that changed only its
     * primary would otherwise silently get an unrelated second colour.
     */
    private static final float SECOND_PLOT_HUE = 60f;

    /**
     * Built-in stroke for a plot.
     *
     * @param themeIdx 0 for plain, 1 for dark, 2 for midnight
     * @param plot the plot ordinal
     * @return the final colour, already vividised
     */
    private static Color defaultLine(int themeIdx, int plot) {
        Color primary = GraphRenderer.electric(PRIMARY_BASE[themeIdx]);
        return plot <= 0 ? primary : rotateHue(primary, SECOND_PLOT_HUE);
    }

    /**
     * Built-in fill for a plot.
     *
     * @param themeIdx 0 for plain, 1 for dark, 2 for midnight
     * @param plot the plot ordinal
     * @return the final colour
     */
    private static Color defaultPath(int themeIdx, int plot) {
        if (plot <= 0) {return withPathAlpha(PRIMARY_FILL[themeIdx]);}
        Color c = rotateHue(GraphRenderer.electric(PRIMARY_BASE[themeIdx]), SECOND_PLOT_HUE);
        return withPathAlpha(c);
    }

    /** Apply {@link #PATH_ALPHA}, discarding whatever alpha the source colour carried. */
    private static Color withPathAlpha(Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), PATH_ALPHA);
    }

    /**
     * Rebuild a colour at a fixed hue, keeping saturation and brightness.
     *
     * <p>Absolute rather than relative, which is what makes {@link #SECOND_PLOT_HUE} mean
     * "yellow" for every theme regardless of what its primary happens to be.
     */
    private static Color rotateHue(Color base, float hueDegrees) {
        float[] hsb = java.awt.Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        int rgb = java.awt.Color.HSBtoRGB(hueDegrees / 360f, hsb[1], hsb[2]);
        return new Color(rgb | (base.getAlpha() << 24), true);
    }

    /** Dash on-length per theme; 1px everywhere so far, but a theme may widen it. */
    private static final float[] DEFAULT_DASH = { 1f, 1f, 1f };

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
     * @param themeDir the directory holding {@code <theme>/console.css} trees
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first path and 1 for the second
     * @return the colour, never null; falls back to the built-in value
     */
    public static Color pathColor(File themeDir, String theme, int plot) {
        String var = VAR_PATH_PREFIX + (plot + 1);
        Color declared = declared(themeDir, theme, var);
        return declared != null ? declared : defaultPath(themeIndex(theme), clampPlot(plot));
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
     * Parse {@code --graph_dash} into a dot length and a stated gap.
     *
     * @return {@code {dot, gap}}, with a dot of zero meaning "no pattern" and a gap of
     *         zero meaning "derive it"; never null
     */
    private static float[] dash(File themeDir, String theme) {
        float[] fallback = { DEFAULT_DASH[themeIndex(theme)], 0f };
        String v = variables(themeDir, theme).get(VAR_DASH);
        if (v == null) {return fallback;}
        float[] parsed = parseDashList(v);
        if (parsed == null || parsed.length == 0) {return fallback;}
        float dot = parsed[0];
        // Zero is a request rather than a typo: no dot ink at all is a solid line.
        if (dot < 0f || dot >= 64f || Float.isNaN(dot)) {return fallback;}
        float gap = parsed.length > 1 ? parsed[1] : 0f;
        if (Float.isNaN(gap) || gap < 0f || gap >= 256f) {gap = 0f;}
        return new float[] { dot, gap };
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
     * @return the declarations, empty when there is no stylesheet
     */
    private static Map<String, String> variables(File themeDir, String theme) {
        String key = (themeDir != null ? themeDir.getAbsolutePath() : "")
                     + '\u0000' + (theme != null ? theme : "");
        File css = themeFile(themeDir, theme);
        long modified = (css != null && css.isFile()) ? css.lastModified() : NO_FILE;
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