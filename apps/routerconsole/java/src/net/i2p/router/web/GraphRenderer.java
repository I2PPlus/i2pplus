package net.i2p.router.web;

import java.io.File;
import static net.i2p.router.web.GraphConstants.*;

import eu.bengreen.data.utility.LargestTriangleThreeBucketsTime;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Paint;
import java.awt.Stroke;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import org.rrd4j.core.FetchData;
import org.rrd4j.core.RrdDb;
import org.rrd4j.core.RrdException;
import org.rrd4j.data.Variable;
import org.rrd4j.graph.ElementsNames;
import org.rrd4j.graph.RrdGraph;
import org.rrd4j.graph.RrdGraphConstants;
import org.rrd4j.graph.RrdGraphDef;
import org.rrd4j.graph.SVGImageWorker;

/**
 *  Generate the RRD graph png images,
 *  including the combined rate graph.
 *
 *  @since 0.6.1.13
 */
class GraphRenderer {
    private final Log _log;
    private final GraphListener _listener;
    private final I2PAppContext _context;
    private static final String PROP_THEME_NAME = "routerconsole.theme";


    private static final String DEFAULT_THEME = "dark";

    /**
     * Nothing drawn: the canvas margin, the chrome-less sidebar tiles' axes and the grid a
     * tile too small for one cannot show anything against whatever the page puts behind.
     */
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    /** Minor grid colour for a tile with no room for a grid, which hides it outright. */
    private static final Color GRID_COLOR_HIDDEN = new Color(0, 0, 0, 0);

    /** The plot border, which no theme draws; the gridlines carry the frame instead. */
    private static final Color FRAME_COLOR = new Color(0, 0, 0, 0);

    /**
     * Fill for the wide, chrome-less sidebar sparkline.
     *
     * <p>Neutral on purpose: that tile carries no legend and no labels, so its colour must
     * not read as a first plot from the themed palette.
     */
    private static final Color SPARKLINE_AREA_COLOR = new Color(128, 128, 128, 128);

    /**
     * Width of the outline drawn over a filled plot, as a fraction of the plot line width.
     *
     * <p>Thinner than the line mode on purpose: the outline's job is to give the fill an edge
     * it can be read against, not to compete with the fill as a series of its own. A fraction
     * rather than a constant so it tracks the theme - a theme that draws heavier plots gets a
     * proportionally heavier edge, which is what makes the outline controllable at all.
     *
     * @since 0.9.71+
     */
    private static final float FILLED_LINE_WIDTH_RATIO = 0.5f;

    /**
     * The outline width for a filled plot on a frame.
     *
     * <p>Bypasses {@link #lineWidth}'s layout overrides deliberately: a fill is a deliberate
     * choice by the theme-enabled {@code graphFill} mode, so a crowded tile thins the series
     * itself and has no reason to thin the edge that keeps it legible.
     */
    private float filledLineWidth(GraphRenderConfig cfg) {
        float base = cfg.width > GraphThemeColors.WIDE_WIDTH
                   ? GraphThemeColors.plotLineWidthWide(GraphThemeColors.installedThemeDir(),
                                                         cfg.theme)
                   : GraphThemeColors.plotLineWidth(GraphThemeColors.installedThemeDir(),
                                                    cfg.theme);
        return Math.max(MIN_LINE_WIDTH, base * FILLED_LINE_WIDTH_RATIO);
    }

    /** Narrowest stroke a theme may ask for; below this a plot disappears. */
    private static final float MIN_LINE_WIDTH = 0.5f;

    /**
     * Stroke width when one axis carries a whole group of series.
     *
     * <p>A layout decision, not a theme choice: too heavy and the grouped lines merge into
     * one mass, which defeats the point of grouping them. See {@link #lineWidth}.
     */
    private static final float ALL_LINES_WIDTH = 1.5f;

    /**
     * Stroke width for the sidebar sparkline.
     *
     * <p>A legibility decision: at 250x50 with nothing else drawn, a hairline nearly vanishes.
     * See {@link #lineWidth}.
     */
    private static final float SPARKLINE_LINE_WIDTH = 3f;

    /**
     * Stroke width for a tile carrying enough periods that a heavier line would merge them.
     *
     * <p>A legibility decision, and the reason a theme's width is not obeyed unconditionally.
     * See {@link #lineWidth}.
     */
    private static final float CROWDED_LINE_WIDTH = 1f;

    /** Whether to render every series as a filled path under a thin line. @since 0.9.71+ */
    private static final String PROP_FILL = "routerconsole.graphFill";

    /**
     * The arrow on an axis break, which no theme draws.
     *
     * <p>Already rrd4j's own default, so setting it is stating the intent rather than
     * changing anything - which is the point: it used to be set for two of the four themes
     * only, implying the arrow was a dark-theme decision when it never was.
     */
    private static final Color ARROW_COLOR = new Color(0, 0, 0, 0);

    private static final boolean IS_WIN = SystemVersion.isWindows();
    private static final String PROP_SMOOTH = "routerconsole.graphSmooth";
    /**
     *  Keep the historical zero-floored y-axis even when the window's data lives in a
     *  narrow band far from zero. Off by default, so the data-driven floor applies.
     *
     *  @since 0.9.71+
     */
    private static final String PROP_ZERO_BASE = "routerconsole.graphZeroBase";
    private static final String PROP_FONT_MONO = "routerconsole.graphFont.unit";
    private static final String PROP_FONT_LEGEND = "routerconsole.graphFont.legend";
    private static final String PROP_FONT_TITLE = "routerconsole.graphFont.title";
    private static final int SIZE_MONO = 10;
    private static final int SIZE_LEGEND = 11;
    private static final int SIZE_TITLE = 12;
    /**
     *  Headroom left under the data minimum, as a fraction of the window's data range.
     *
     *  <p>Proportional rather than absolute, so it behaves the same for a rate that
     *  hovers around 0.05 and one that sits at 100,000.
     *
     *  @since 0.9.71+
     */
    private static final double AXIS_FLOOR_MARGIN = 0.1d;
    private static final Stroke GRID_STROKE =
            new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {1, 1}, 0);
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<=[a-z])([A-Z])");

    /**
     *  SimpleDateFormats are expensive to construct and not thread-safe, so
     *  cache one per thread for each timezone variant instead of allocating
     *  new ones on every render() call.
     */
    private static final ThreadLocal<SimpleDateFormat> LOCAL_DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("dd MMM HH:mm", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> UTC_DATE_FMT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf;
    });

    private static final GraphicsEnvironment GE = GraphicsEnvironment.getLocalGraphicsEnvironment();
    private static final List<String> FONTLIST = Arrays.asList(GE.getAvailableFontFamilyNames());

    /**
     * GraphRenderer.
     */
    public GraphRenderer(I2PAppContext ctx, GraphListener lsnr) {
        _log = ctx.logManager().getLog(GraphRenderer.class);
        _listener = lsnr;
        _context = ctx;
    }

    /**
     * render.
     */
    public void render(OutputStream out) throws IOException {
        render(out, DEFAULT_X, DEFAULT_Y, false, false, false, false, -1, 0, false);
    }

    /**
     *  Single graph.
     *
     *  @param endp number of periods before now
     */
    public void render(
            OutputStream out,
            int width,
            int height,
            boolean hideLegend,
            boolean hideGrid,
            boolean hideTitle,
            boolean showEvents,
            int periodCount,
            int endp,
            boolean showCredit)
            throws IOException {
        render(
                out,
                width,
                height,
                hideLegend,
                hideGrid,
                hideTitle,
                showEvents,
                periodCount,
                endp,
                showCredit,
                (java.util.List<GraphListener>) null,
                null,
                true);
    }

    /**
     *  Single or two-data-source graph.
     *
     *  @param lsnr2 2nd data source to plot on same graph, or null. Not recommended for events.
     *  @param titleOverride If non-null, overrides the title
     *  @param showRestarts if true, draw the vertical restart lines and "Router restarted" label
     *  @since 0.9.6 consolidated from GraphGenerator for bw.combined
     */
    public void render(
            OutputStream out,
            int width,
            int height,
            boolean hideLegend,
            boolean hideGrid,
            boolean hideTitle,
            boolean showEvents,
            int periodCount,
            int endp,
            boolean showCredit,
            GraphListener lsnr2,
            String titleOverride,
            boolean showRestarts)
            throws IOException {
        GraphRenderConfig cfg = buildRenderConfig(width, height, hideLegend, hideGrid, hideTitle,
                showEvents, periodCount, endp, showCredit,
                (lsnr2 != null ? java.util.Collections.singletonList(lsnr2) : null),
                titleOverride, showRestarts);
        RrdGraphDef def = new RrdGraphDef(cfg.start / 1000, cfg.end / 1000);
        configureDownsampler(def, cfg);
        configureTimeZone(def, cfg.useUtc);
        applyTheme(def, cfg);
        configureFonts(def, cfg);
        configureBaseAndDecimals(cfg);
        // The only range setting that depends on the data; rrd4j derives the
        // ceiling and rounds the ticks itself.
        resolveAxisRange(cfg);
        def.setMinValue(axisFloor(cfg.dataMin, cfg.dataMax, cfg.forceZero));
        configureTitle(def, cfg);
        configureDataSources(def, cfg);
        configureLegend(def, cfg);
        configureExtraDataSources(def, cfg);
        configureRestartMarkers(def, cfg);
        configureCommentsAndSignature(def, cfg);
        configureGridAndRendering(def, cfg);
        renderGraph(def, out, cfg);
    }

    /**
     * Render a graph with any number of extra series overlaid. All series share
     * one axis, so they must measure the same thing; see {@link GraphGroups}.
     * The primary is drawn first and supplies the axis range; each extra is
     * drawn as a line, never an area, and is named in the legend.
     *
     * @param out where the SVG is written
     * @param extras extra series in legend order, or null or empty for none
     * @param titleOverride title to draw, or null for the primary stat description
     * @throws IOException if the graph cannot be produced
     * @since 0.9.71+
     */
    public void render(
            OutputStream out,
            int width,
            int height,
            boolean hideLegend,
            boolean hideGrid,
            boolean hideTitle,
            boolean showEvents,
            int periodCount,
            int endp,
            boolean showCredit,
            List<GraphListener> extras,
            String titleOverride,
            boolean showRestarts)
            throws IOException {
        GraphRenderConfig cfg = buildRenderConfig(width, height, hideLegend, hideGrid, hideTitle,
                showEvents, periodCount, endp, showCredit, extras, titleOverride, showRestarts);
        RrdGraphDef def = new RrdGraphDef(cfg.start / 1000, cfg.end / 1000);
        configureDownsampler(def, cfg);
        configureTimeZone(def, cfg.useUtc);
        applyTheme(def, cfg);
        configureFonts(def, cfg);
        configureBaseAndDecimals(cfg);
        resolveAxisRange(cfg);
        def.setMinValue(axisFloor(cfg.dataMin, cfg.dataMax, cfg.forceZero));
        configureTitle(def, cfg);
        configureDataSources(def, cfg);
        configureLegend(def, cfg);
        configureExtraDataSources(def, cfg);
        configureRestartMarkers(def, cfg);
        configureCommentsAndSignature(def, cfg);
        configureGridAndRendering(def, cfg);
        renderGraph(def, out, cfg);
    }

    /**
     * Render a graph with every series, including the primary, drawn as a line.
     *
     * <p>Used for combined graphs. Filling the primary would put a solid block behind the
     * other series and hide any that cross it, which defeats the point of overlaying them.
     *
     * @see #render(OutputStream, int, int, boolean, boolean, boolean, boolean, int, int, boolean, List, String, boolean)
     * @since 0.9.71+
     */
    public void renderLines(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                            boolean hideTitle, boolean showEvents, int periodCount, int endp,
                            boolean showCredit, List<GraphListener> extras, String titleOverride,
                            boolean showRestarts) throws IOException {
        GraphRenderConfig cfg = buildRenderConfig(width, height, hideLegend, hideGrid, hideTitle,
                showEvents, periodCount, endp, showCredit, extras, titleOverride, showRestarts, true);
        RrdGraphDef def = new RrdGraphDef(cfg.start / 1000, cfg.end / 1000);
        configureDownsampler(def, cfg);
        configureTimeZone(def, cfg.useUtc);
        applyTheme(def, cfg);
        configureFonts(def, cfg);
        configureBaseAndDecimals(cfg);
        resolveAxisRange(cfg);
        def.setMinValue(axisFloor(cfg.dataMin, cfg.dataMax, cfg.forceZero));
        configureTitle(def, cfg);
        configureDataSources(def, cfg);
        configureLegend(def, cfg);
        configureExtraDataSources(def, cfg);
        configureRestartMarkers(def, cfg);
        configureCommentsAndSignature(def, cfg);
        configureGridAndRendering(def, cfg);
        renderGraph(def, out, cfg);
    }

    /**
     * Builds the immutable render configuration from parameters and context.
     */
    private GraphRenderConfig buildRenderConfig(int width, int height, boolean hideLegend,
            boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, int endp,
            boolean showCredit, List<GraphListener> extras, String titleOverride, boolean showRestarts) {
        return buildRenderConfig(width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                                 endp, showCredit, extras, titleOverride, showRestarts, false);
    }

    /**
     * @param allLines draw the primary as a line rather than a filled area
     */
    private GraphRenderConfig buildRenderConfig(int width, int height, boolean hideLegend,
            boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, int endp,
            boolean showCredit, List<GraphListener> extras, String titleOverride, boolean showRestarts,
            boolean allLines) {
        long begin = System.currentTimeMillis();
        long end = Math.min(_listener.now(), begin - GraphListener.GRAPH_END_OFFSET_SECONDS * 1000);
        long period = _listener.getRate().getPeriod();
        if (endp > 0) {
            end -= period * endp;
        }
        if (periodCount <= 0 || periodCount > _listener.getRows()) {
            periodCount = _listener.getRows();
        }
        long start = end - (period * periodCount);
        String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
        boolean useUtc = _context.getBooleanProperty("routerconsole.graphUtc");
        // Opt-in: default off keeps the staircase rendering
        boolean smooth = _context.getBooleanProperty(PROP_SMOOTH);
        // Opt-in: default off lets the y-axis follow the data instead of zero
        boolean forceZero = _context.getBooleanProperty(PROP_ZERO_BASE);
        String lang = Messages.getLanguage(_context);
        if (lang == null) {
            lang = "en";
        }

        return GraphRenderConfig.builder()
                .start(start)
                .end(end)
                .period(period)
                .width(width)
                .height(height)
                .periodCount(periodCount)
                .theme(theme)
                .hideLegend(hideLegend)
                .hideGrid(hideGrid)
                .hideTitle(hideTitle)
                .showEvents(showEvents)
                .showCredit(showCredit)
                .showRestarts(showRestarts)
                .titleOverride(titleOverride)
                .rate(_listener.getRate())
                .extras(extras)
                .allLines(allLines)
                .fillSeries(_context.getBooleanProperty(PROP_FILL))
                .useUtc(useUtc)
                .smooth(smooth)
                .forceZero(forceZero)
                .lang(lang)
                .listener(_listener)
                .build();
    }

    /**
     * Caps how many points are plotted so that each has room to be drawn.
     *
     * <p>Uncapped, the renderer is handed roughly one point per pixel, and once
     * points are closer together than a pixel a value change falls inside a
     * single column with no width to draw across. The cap is derived from the
     * width instead, and only applied when smoothing is on, so the default
     * rendering is untouched.
     *
     * @param def the graph definition
     * @param cfg the render configuration
     */
    private void configureDownsampler(RrdGraphDef def, GraphRenderConfig cfg) {
        if (!cfg.smooth) {
            return;
        }
        // The processed series is about min(periods, width) points long, so compare against that
        // rather than the period count alone. Half a pixel per point is the point at which
        // transitions stop being interpolatable, so aim for two pixels and keep a margin.
        int plotted = Math.min(cfg.periodCount, cfg.width);
        int budget = Math.max(2, cfg.width / 2);
        if (plotted > budget) {
            def.setDownsampler(new LargestTriangleThreeBucketsTime(budget));
        }
    }

    private void configureTimeZone(RrdGraphDef def, boolean useUtc) {
        if (useUtc) {
            def.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
    }

    private void configureFonts(RrdGraphDef def, GraphRenderConfig cfg) {
        int smallSize = SIZE_MONO;
        int legendSize = SIZE_LEGEND;
        int largeSize = SIZE_TITLE;
        if ("ar".equals(cfg.lang) || "ja".equals(cfg.lang) || ("zh".equals(cfg.lang) && !IS_WIN)) {
            smallSize += 2;
            legendSize += 2;
            largeSize += 3;
        } else if (cfg.width >= 800) {
            smallSize += 1;
            legendSize += 1;
            largeSize += 2;
        }

        FontNames fonts = selectFontNames(cfg.lang);
        String ssmall = _context.getProperty(PROP_FONT_MONO, fonts.mono);
        String slegend = _context.getProperty(PROP_FONT_LEGEND, fonts.legend);
        String stitle = _context.getProperty(PROP_FONT_TITLE, fonts.title);
        cfg.small = new Font(ssmall, Font.PLAIN, smallSize);
        cfg.legend = new Font(slegend, Font.PLAIN, legendSize);
        cfg.title = new Font(stitle, Font.PLAIN, largeSize);
        def.setFont(RrdGraphDef.FONTTAG_DEFAULT, cfg.small);
        def.setFont(RrdGraphDef.FONTTAG_AXIS, cfg.small);
        def.setFont(RrdGraphDef.FONTTAG_UNIT, cfg.small);
        def.setFont(RrdGraphDef.FONTTAG_LEGEND, cfg.legend);
        def.setFont(RrdGraphDef.FONTTAG_TITLE, cfg.title);
    }

    private void configureBaseAndDecimals(GraphRenderConfig cfg) {
        String name = cfg.rate.getRateStat().getName();
        cfg.derivedTitle = deriveTitle(name);

        // Look up explicit metadata for this stat; fall back to heuristics if unknown
        StatMeta meta = STAT_META.get(name);
        if (meta != null) {
            cfg.base = meta.base;
            cfg.noDecimalPlace = meta.noDecimalPlace;
            cfg.singleDecimalPlace = meta.singleDecimalPlace;
        } else {
            // Legacy heuristic fallback (should rarely trigger)
            cfg.singleDecimalPlace = true;
            cfg.noDecimalPlace = false;
            boolean isByteOrRate = name.toLowerCase().contains("b/s") || name.toLowerCase().contains("bps")
                    || name.toLowerCase().contains("bandwidth") || name.toLowerCase().contains("byte")
                    || name.toLowerCase().contains("memory");
            boolean isSize = name.toLowerCase().contains("size") || name.toLowerCase().contains("memory")
                    || name.toLowerCase().contains("bytecache");
            if (isSize && !cfg.showEvents) {
                cfg.base = 1024;
                cfg.singleDecimalPlace = false;
            } else {
                cfg.base = 1000;
            }
            if (name.toLowerCase().contains("percent") || name.contains("%")) {
                cfg.noDecimalPlace = true;
            } else if (!isByteOrRate || name.toLowerCase().contains("keyset") || name.toLowerCase().contains("keysize")) {
                // Count-like metrics
                if (!name.toLowerCase().endsWith("rate")) {
                    cfg.noDecimalPlace = true;
                }
            }
        }

        cfg.numberFormat = cfg.noDecimalPlace ? "%.0f" : cfg.singleDecimalPlace ? "%.1f%s" : "%.2f%s";
    }

    /**
     * Choose the y-axis floor for a window, so a series that lives in a narrow band far
     * from zero is not flattened against a zero baseline.
     *
     * <p>Only the floor is ever returned. The ceiling and the tick rounding are left to
     * rrd4j, which snaps the floor down to cover any value that does not fit and
     * otherwise rounds both ends to its own idea of a sensible label value.
     *
     * <p>Pure, so the decision can be unit tested without a router, an RRD or a clock.
     *
     * @param dataMin  smallest finite value in the window, NaN if none
     * @param dataMax  largest finite value in the window, NaN if none
     * @param forceZero true to keep the historical zero-floored axis
     * @return the floor to pass to RrdGraphDef.setMinValue
     * @since 0.9.71+
     */
    static double axisFloor(double dataMin, double dataMax, boolean forceZero) {
        if (forceZero || !Double.isFinite(dataMin) || !Double.isFinite(dataMax)) {
            return 0;
        }
        // A flat window has no range to scale, and a window reaching below zero is
        // measured against its own sign - both keep the historical axis.
        if (dataMin >= dataMax || dataMin < 0) {
            return 0;
        }
        double floor = dataMin - (dataMax - dataMin) * AXIS_FLOOR_MARGIN;
        if (!Double.isFinite(floor) || floor < 0) {
            return 0;
        }
        return floor;
    }

/**
     *  Measure the window's data range so {@link #axisFloor} has something to scale.
     *
     *  <p>The values cannot be taken off the definition before the graph is built:
     *  rrd4j computes them internally, while rendering. So this reads the same window
     *  from the listener's already-open RRD - the read {@link RrdGraph} performs a
     *  moment later anyway - and reduces it in one pass.
     *
     *  <p>Skipped entirely when a floor could not take effect anyway: {@code forceZero}
     *  asks for the historical axis, and {@link #usesMrtgScaling} means rrd4j rebuilds
     *  the range itself.
     *
     *  @param cfg the render configuration, whose range fields are filled in
     *  @since 0.9.71+
     */
    private void resolveAxisRange(GraphRenderConfig cfg) {
        cfg.dataMin = Double.NaN;
        cfg.dataMax = Double.NaN;
        if (cfg.forceZero || usesMrtgScaling(cfg)) {
            return;
        }
        long start = cfg.start / 1000;
        long end = cfg.end / 1000;
        // Events graphs plot the event count rather than the stat itself, matching the
        // datasource configureDataSources() picks for cfg.plotName.
        GraphListener lsnr = cfg.listener;
        accumulateRange(cfg, fetchWindow(lsnr, cfg.showEvents ? lsnr.getEventName() : lsnr.getName(),
                start, end));
        if (cfg.extras != null) {
            for (GraphListener extra : cfg.extras) {
                accumulateRange(cfg, fetchWindow(extra, extra.getName(), start, end));
            }
        }
    }

    /**
     *  Read one datasource over the render window.
     *
     *  @param lsnr the listener holding the RRD, or null
     *  @param dsName datasource to read
     *  @param startSec window start, in seconds
     *  @param endSec window end, in seconds
     *  @return the archived values, or null if they could not be read
     * @since 0.9.71+
     */
    private double[] fetchWindow(GraphListener lsnr, String dsName, long startSec, long endSec) {
        if (lsnr == null || dsName == null) {
            return null;
        }
        RrdDb db = lsnr.getData();
        if (db == null || db.isClosed()) {
            return null;
        }
        try {
            FetchData data = db.createFetchRequest(GraphListener.CF, startSec, endSec).fetchData();
            return data.getValues(dsName);
        } catch (IOException | RuntimeException e) {
            // Benign, and worth no more than a debug line: without a range the axis
            // simply falls back to the zero floor this whole change replaces.
            if (_log.shouldDebug()) {
                _log.debug("Error reading the window range of " + dsName, e);
            }
            return null;
        }
    }

    /**
     *  Fold one series into the running range, ignoring missing and infinite points.
     *
     *  <p>One pass over a primitive array: no boxing, no sorting, nothing
     *  allocated per point.
     *
     *  @param cfg the render configuration, updated in place
     *  @param values archived values, or null if unavailable
     *  @since 0.9.71+
     */
    private static void accumulateRange(GraphRenderConfig cfg, double[] values) {
        if (values == null) {
            return;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (!Double.isFinite(v)) {
                continue;
            }
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        if (Double.isInfinite(max)) {
            return;
        }
        if (Double.isNaN(cfg.dataMin) || min < cfg.dataMin) {
            cfg.dataMin = min;
        }
        if (Double.isNaN(cfg.dataMax) || max > cfg.dataMax) {
            cfg.dataMax = max;
        }
    }

    /**
     *  Whether rrd4j scales the value axis itself, ignoring a floor set on the definition.
     *
     *  <p>With {@link RrdGraphDef#setAltYMrtg(boolean)} on, rrd4j's MRTG range expansion
     *  rebuilds the y range from the maximum alone and pins the floor at zero, so a floor
     *  set on the definition cannot move it. Small graphs turn the option off, which is why
     *  they are the ones the data-driven floor helps.
     *
     *  <p>Mirrors the decision in {@link #configureGridAndRendering} so the two cannot
     *  drift apart, and is only read after {@link #configureBaseAndDecimals} has filled in
     *  {@code noDecimalPlace}.
     *
     *  @param cfg the render configuration
     *  @return true if rrd4j overwrites the range, so {@link #axisFloor} cannot take effect
     *  @since 0.9.71+
     */
    private static boolean usesMrtgScaling(GraphRenderConfig cfg) {
        return !cfg.noDecimalPlace && cfg.width >= 400 && cfg.height >= 200;
    }

    /**
     * Explicit metadata for stat rendering behavior.
     * Replaces fragile string-index heuristics with typed lookups.
     */
    private static final class StatMeta {
        final int base;              // 1000 or 1024
        final boolean noDecimalPlace; // true for integer-only metrics
        final boolean singleDecimalPlace; // true for single decimal, false for two

        StatMeta(int base, boolean noDecimalPlace, boolean singleDecimalPlace) {
            this.base = base;
            this.noDecimalPlace = noDecimalPlace;
            this.singleDecimalPlace = singleDecimalPlace;
        }
    }

    /** Known stat metadata — add entries for new stats instead of extending heuristics. */
    private static final Map<String, StatMeta> STAT_META;
    static {
        Map<String, StatMeta> m = new java.util.HashMap<>();
        // Byte-based metrics (base 1024, fractional)
        m.put("bw.sendRate", new StatMeta(1024, false, false));
        m.put("bw.recvRate", new StatMeta(1024, false, false));
        m.put("router.memoryUsed", new StatMeta(1024, false, false));
        m.put("router.memoryMax", new StatMeta(1024, false, false));
        m.put("router.byteCacheSize", new StatMeta(1024, false, false));
        m.put("tunnel.participatingBytesIn", new StatMeta(1024, false, false));
        m.put("tunnel.participatingBytesOut", new StatMeta(1024, false, false));

        // Count/integer metrics (base 1000, no decimals)
        m.put("router.activePeers", new StatMeta(1000, true, false));
        m.put("router.cpuLoad", new StatMeta(1000, false, true));
        m.put("router.clockSkew", new StatMeta(1000, false, true));
        m.put("router.uptime", new StatMeta(1000, true, false));
        m.put("tunnel.participatingTunnels", new StatMeta(1000, true, false));
        m.put("tunnel.buildSuccessAvg", new StatMeta(1000, false, true));
        m.put("tunnel.buildSuccessRate", new StatMeta(1000, false, true));
        m.put("tunnel.testSuccessTime", new StatMeta(1000, false, true));
        m.put("jobQueue.jobLag", new StatMeta(1000, false, true));
        m.put("jobQueue.jobs", new StatMeta(1000, true, false));
        m.put("netDb.knownRouters", new StatMeta(1000, true, false));
        m.put("netDb.knownLeaseSets", new StatMeta(1000, true, false));
        m.put("ntcp.pumperLoopsPerSecond", new StatMeta(1000, true, false));
        m.put("ntcp.pumperIdleLoops", new StatMeta(1000, true, false));
        m.put("ntcp.pumperKeySetSize", new StatMeta(1000, true, false));
        m.put("ntcp.inboundConn", new StatMeta(1000, true, false));
        m.put("ntcp.inboundEstablishFailed", new StatMeta(1000, true, false));
        m.put("udp.sendRate", new StatMeta(1024, false, false));
        m.put("udp.recvRate", new StatMeta(1024, false, false));
        m.put("transport.sendRate", new StatMeta(1024, false, false));
        m.put("transport.recvRate", new StatMeta(1024, false, false));

        // Percentage metrics (base 1000, no decimals)
        m.put("tunnel.buildSuccessPercent", new StatMeta(1000, true, false));
        m.put("router.cpuPercent", new StatMeta(1000, true, false));
        m.put("router.memoryPercent", new StatMeta(1000, true, false));
        STAT_META = Collections.unmodifiableMap(m);
    }

    private void configureTitle(RrdGraphDef def, GraphRenderConfig cfg) {
        if (cfg.titleOverride != null) {
            def.setTitle(cfg.titleOverride);
        } else if (!cfg.hideTitle) {
            String p;
            if (IS_WIN && "zh".equals(cfg.lang)) {
                p = DataHelper.formatDuration(cfg.period);
            } else {
                p = DataHelper.formatDuration2(cfg.period).replace("&nbsp;", " ");
            }
            String title = cfg.derivedTitle;
            if (cfg.showEvents) {
                title = title + ' ' + _t("events in {0}", p);
            }
            title = CAMEL_CASE_PATTERN.matcher(title).replaceAll(" $1");
            title = title.substring(0, 1).toUpperCase() + title.substring(1);
            title = title.replace("[Tunnel] [Tunnel]", "[Tunnel]")
                    .replace("Uild Success Avg", "Build Success Average")
                    .replace(" Avg", "Average")
                    .replace(".drop", " Drop")
                    .replace(".delay", " Delay")
                    .replace("Participating", "Transit")
                    .replace("RILookup", "RouterInfo Lookup")
                    .replace(" Per Second", "/s");
            def.setTitle(title);
        }
    }

    private void configureDataSources(RrdGraphDef def, GraphRenderConfig cfg) throws IOException {
        cfg.path = cfg.listener.getData().getPath();
        try {
            cfg.dsNames = cfg.listener.getData().getDsNames();
        } catch (IOException ioe) {
            throw new IOException("Failed to get datasource names", ioe);
        }
        if (cfg.showEvents) {
            cfg.plotName = cfg.dsNames[1];
            cfg.descr = _t("Events per period");
        } else {
            cfg.plotName = cfg.dsNames[0];
            cfg.descr = _t(cfg.rate.getRateStat().getDescription());
        }
        def.datasource(cfg.plotName, cfg.path, cfg.plotName, GraphListener.CF, cfg.listener.getBackendFactory());
        if (cfg.fillSeries) {
            // Filled-path mode: a translucent area with a thin solid line on top, so the
            // outline still reads where the series crosses another one. Only the line
            // carries the legend: a legend row is emitted per plot element that has one, so
            // giving both the same text put the series name in the legend twice.
            def.area(cfg.plotName, plotPathPaint(cfg.theme, 0, cfg.height));
            def.line(cfg.plotName, paletteColor(cfg.theme, 0), cfg.descr + "\\l",
                     filledLineWidth(cfg), plotLineShade(cfg.theme, 0));
        } else if (cfg.allLines) {
            def.line(cfg.plotName, paletteColor(cfg.theme, 0), cfg.descr + "\\l", lineWidth(cfg),
                     plotLineShade(cfg.theme, 0));
        } else {
            configureArea(def, cfg);
        }
    }

    /**
     * Draw the primary as a filled area, which is how a single-value graph has always
     * been rendered.
     *
     * <p>The one exception is the wide, chrome-less sparkline used in the sidebar, which
     * stays a neutral grey: it is drawn small and unlabelled, so it takes a colour from
     * neither the theme nor the plot palette and must not imply a second series.
     */
    private void configureArea(RrdGraphDef def, GraphRenderConfig cfg) {
        if (cfg.width == 2000 && cfg.height == 160 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid) {
            def.area(cfg.plotName, SPARKLINE_AREA_COLOR);
            return;
        }
        Paint fill = plotPathPaint(cfg.theme, 0, cfg.height);
        if (!cfg.descr.isEmpty()) {
            def.area(cfg.plotName, fill, cfg.descr + "\\l");
        } else {
            def.area(cfg.plotName, fill);
        }
    }

    private void configureLegend(RrdGraphDef def, GraphRenderConfig cfg) {
        if (!cfg.hideLegend) {
            Variable var = new Variable.MIN();
            def.datasource("min", cfg.plotName, var);
            def.gprint("min", " " + _t("Min") + ": " + cfg.numberFormat);
            var = new Variable.MAX();
            def.datasource("max", cfg.plotName, var);
            def.gprint("max", " " + _t("Max") + ": " + cfg.numberFormat);
            var = new Variable.AVERAGE();
            def.datasource("avg", cfg.plotName, var);
            def.gprint("avg", " " + _t("Avg") + ": " + cfg.numberFormat);
            var = new Variable.LAST();
            def.datasource("last", cfg.plotName, var);
            def.gprint("last", " " + _t("Now") + ": " + cfg.numberFormat + "\\l");
        }
    }

/**
     * Render a combined graph of several stats as overlaid lines.
     *
     * @param out where the SVG is written
     * @param showEvents unused; event mode is not supported for combined graphs
     * @param primary the series drawn first; also supplies the axis range
     * @param extras the remaining series, in legend order
     * @return true if a graph was written
     * @throws IOException if the graph cannot be produced
     * @since 0.9.71+
     */
    public boolean renderGroup(OutputStream out, int width, int height, boolean hideLegend,
                               boolean hideGrid, boolean hideTitle, boolean showEvents,
                               int periodCount, int end, boolean showCredit,
                               GraphListener primary, List<GraphListener> extras,
                               String titleOverride, boolean showRestarts) throws IOException {
        if (primary == null) {
            throw new IOException("No primary datasource for combined graph");
        }
        render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
               periodCount, end, showCredit, extras, titleOverride, showRestarts);
        return true;
    }

    /**
     *  Declare and plot every extra series of a combined graph.
     *
     *  <p>Each member becomes its own datasource drawn as a line, never an area,
     *  with a per-series colour and its description in the legend. Areas are
     *  reserved for the primary series: filling several overlaid series hides
     *  whichever one is behind.
     *
     *  <p>The max/min/avg/now summary block is emitted for the first extra
     *  series only when there is exactly one, matching the long-standing
     *  combined-bandwidth graph; with more, four legend lines per series would
     *  bury the plot.
     */
    private void configureExtraDataSources(RrdGraphDef def, GraphRenderConfig cfg) throws IOException {
        List<GraphListener> extras = cfg.extras;
        if (extras == null || extras.isEmpty()) {
            return;
        }
        boolean summary = extras.size() == 1 && !cfg.hideLegend;
        for (int i = 0; i < extras.size(); i++) {
            GraphListener lsnr = extras.get(i);
            String[] dsNames;
            try {
                dsNames = lsnr.getData().getDsNames();
            } catch (IOException ioe) {
                throw new IOException("Failed to get datasource names for "
                                      + GraphListener.statName(lsnr.getName()), ioe);
            }
            String plotName = dsNames[0];
            String descr = _t(lsnr.getRate().getRateStat().getDescription());
            def.datasource(plotName, lsnr.getData().getPath(), plotName,
                           GraphListener.CF, lsnr.getBackendFactory());
            Color color = extraSeriesColor(cfg.theme, cfg.allLines, i);
            if (cfg.fillSeries) {
                // Legend on the line only; see configureDataSources. Shaded like the primary's
                // outline, so a theme's value shading applies in this mode too.
                def.area(plotName, plotPathPaint(cfg.theme, extraPlot(i), cfg.height));
                def.line(plotName, color, descr + "\\l", filledLineWidth(cfg),
                         plotLineShade(cfg.theme, extraPlot(i)));
            } else {
                def.line(plotName, color, descr + "\\l", lineWidth(cfg),
                         plotLineShade(cfg.theme, extraPlot(i)));
            }
            if (summary) {
                // Distinct ids: configureLegend already defines min/max/avg/last on the
                // primary, and a graph definition cannot declare the same id twice.
                addSummaryPrints(def, plotName, Integer.toString(i + 1), cfg.numberFormat);
            }
        }
    }

    /** Emit the max/min/avg/now legend block for one series. */
    private void addSummaryPrints(RrdGraphDef def, String plotName, String suffix, String numberFormat) {
        Variable var = new Variable.MAX();
        def.datasource("max" + suffix, plotName, var);
        def.gprint("max" + suffix, " " + _t("Max") + ": " + numberFormat + " ");
        var = new Variable.MIN();
        def.datasource("min" + suffix, plotName, var);
        def.gprint("min" + suffix, " " + _t("Min") + ": " + numberFormat + " ");
        var = new Variable.AVERAGE();
        def.datasource("avg" + suffix, plotName, var);
        def.gprint("avg" + suffix, " " + _t("Avg") + ": " + numberFormat + " ");
        var = new Variable.LAST();
        def.datasource("last" + suffix, plotName, var);
        def.gprint("last" + suffix, " " + _t("Now") + ": " + numberFormat + "\\l");
    }

    /**
     * Stroke width for a plotted series.
     *
     * <p>Three cases are decided here rather than by the theme, because each is a fact about
     * one layout rather than a look the console wants:
     *
     * <ul>
     *   <li>Grouped graphs stack up to six lines on one axis and are drawn thinner so they
     *       stay separable and the dense case does not fill in.</li>
     *   <li>The sidebar sparkline is drawn thick because it is small and unlabelled, and a
     *       hairline nearly vanishes at that size.</li>
     *   <li>A tile carrying many periods is drawn thinner for the same reason the grouped
     *       case is: the series is crowded.</li>
     * </ul>
     *
     * <p>Everything else - an ordinary plot - takes its weight from the theme, which is why
     * {@link #ALL_LINES_WIDTH} and its two siblings are constants here and not fallbacks in
     * {@link GraphThemeColors}: a theme states how heavy its plots look, and the renderer
     * keeps the three cases where too heavy would be wrong.
     *
     * @param cfg the render configuration
     * @return stroke width in pixels
     */
    private float lineWidth(GraphRenderConfig cfg) {
        if (cfg.allLines) {
            return ALL_LINES_WIDTH;
        }
        if (isSparkline(cfg)) {
            return SPARKLINE_LINE_WIDTH;
        }
        if (isCrowdedTile(cfg)) {
            return CROWDED_LINE_WIDTH;
        }
        return cfg.width > GraphThemeColors.WIDE_WIDTH
             ? GraphThemeColors.plotLineWidthWide(GraphThemeColors.installedThemeDir(), cfg.theme)
             : GraphThemeColors.plotLineWidth(GraphThemeColors.installedThemeDir(), cfg.theme);
    }

    /**
     * Whether a frame is the wide, chrome-less sidebar sparkline.
     *
     * <p>Identified by its exact size with every other element hidden, which is the only
     * combination the console requests and the only one drawn unlabelled.
     */
    private static boolean isSparkline(GraphRenderConfig cfg) {
        return cfg.width == 250 && cfg.height == 50
               && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid;
    }

    /**
     * Whether a tile is carrying enough periods that a thicker line would merge them.
     *
     * <p>A wide tile keeps the thicker line because it has room to show the crowding; the
     * narrow form is what needs thinning.
     */
    private static boolean isCrowdedTile(GraphRenderConfig cfg) {
        return cfg.periodCount >= 720 || (cfg.periodCount >= 480 && cfg.width <= 600);
    }

/**
     * The colour for an extra series: plot ordinal 1, i.e. the theme's second hue.
 *
 * <p>A frame carries at most two plots ({@link GraphGroups#MAX_SERIES}), so
 * "which colour is the extra series" has exactly one answer and it is a
 * themeable one. The two-series bandwidth graph predates grouping and keeps the
 * exact colour it always shipped with.
 *
 * @param theme console theme name
 * @param allLines whether the primary is drawn as a line rather than a filled area
 * @param extraIndex zero-based position among the extra series
 * @return the colour for that series
 */
    static Color extraSeriesColor(String theme, boolean allLines, int extraIndex) {
        return paletteColor(theme, extraPlot(extraIndex));
    }

    /**
     * Which plot slot an extra series occupies.
     *
     * <p>The primary always takes slot 0, so extras start at 1 and, since a frame carries at
     * most two plots, every extra past the first clamps onto slot 1.
     *
     * <p>Every per-plot lookup for an extra series goes through here — its stroke colour, its
     * fill and its value shading. They used to compute the ordinal separately and one of them
     * disagreed, which gave a series one plot's colour and another plot's gradient.
     *
     * @param extraIndex zero-based position among the extra series
     * @return the plot ordinal, never less than 1
     */
    private static int extraPlot(int extraIndex) {
        return Math.max(1, extraIndex);
    }

    /**
     * Which plot slot an extra series occupies. Package-visible so a test can pin it.
     *
     * @param extraIndex zero-based position among the extra series
     * @return the plot ordinal, never less than 1
     */
    static int extraPlotForTest(int extraIndex) {
        return extraPlot(extraIndex);
    }

    /**
     * The value-shade stops an extra series would be drawn with.
     *
     * <p>Exposed so a test can check that the extra series asks for its own plot's stops rather
     * than a neighbouring plot's. Comparing colours could not catch that, because every shipped
     * theme draws both plots flat and a flat plot has no stops to compare.
     *
     * @param theme console theme name
     * @param extraIndex zero-based position among the extra series
     * @return bottom-to-top stops, or null when that plot is flat
     */
    static Color[] extraLineShadeForTest(String theme, int extraIndex) {
        return plotLineShade(theme, extraPlot(extraIndex));
    }


    /**
     * The stroke colour for a plot on a frame, from the theme's CSS variables or the
     * built-in palette.
     *
     * @param theme console theme name
     * @param plot the plot ordinal; a frame carries at most two
     * @return the colour, never null
     * @since 0.9.71+
     */
    private static Color paletteColor(String theme, int plot) {
        return GraphThemeColors.lineColor(GraphThemeColors.installedThemeDir(), theme, plot);
    }

    /**
     * The fill for a plot as something to paint with: the theme's colour, or a gradient
     * between two of them.
     *
     * @param theme console theme name
     * @param plot the plot ordinal; a frame carries at most two
     * @param frameHeight the frame's height in pixels, which a gradient spans
     * @return the paint, never null
     * @since 0.9.71+
     */
    private static Paint plotPathPaint(String theme, int plot, int frameHeight) {
        return GraphThemeColors.pathPaint(GraphThemeColors.installedThemeDir(), theme, plot,
                                          frameHeight);
    }

    /**
     * The stops that shade one plot line by its value, or null for a flat line.
     *
     * <p>Passed per line rather than set once on the definition, so two plots on one axis are
     * shaded independently - the same independence their colours have.
     *
     * @param theme the console theme name
     * @param plot the plot ordinal, 0 for the first line and 1 for the second
     * @return bottom-to-top stops, or null when the theme named one colour or fewer
     */
    private static Color[] plotLineShade(String theme, int plot) {
        return GraphThemeColors.lineValueShade(GraphThemeColors.installedThemeDir(), theme, plot);
    }

    /** Package-visible accessor so tests can compare a series against the primary. */
    static Color paletteColorForTest(String theme) {
        return paletteColor(theme, 0);
    }

    private void configureRestartMarkers(RrdGraphDef def, GraphRenderConfig cfg) {
        if (!cfg.hideLegend && cfg.showRestarts) {
            cfg.timeLabel = cfg.useUtc ? " UTC" : "";
            cfg.legendSdf = cfg.useUtc ? UTC_DATE_FMT.get() : LOCAL_DATE_FMT.get();
            int count = 0;
            cfg.restartColor = GraphThemeColors.restartMarkerColor(
                    GraphThemeColors.installedThemeDir(), cfg.theme);

            Map<Long, String> events = ((RouterContext) _context).router().eventLog().getEvents(EventLog.STARTED, cfg.start);
            for (Map.Entry<Long, String> event : events.entrySet()) {
                long started = event.getKey().longValue();
                if (started >= cfg.end) {
                    break;
                }
                String legend = (count < 1) ? _t("Router restarted") + "\\l" : null;
                def.vrule(started / 1000, cfg.restartColor, legend, 1.0f);
                count++;
            }
        }
    }

    private void configureCommentsAndSignature(RrdGraphDef def, GraphRenderConfig cfg) {
        if (!cfg.hideLegend) {
            // Small vertical space (\s advances by small leading only) to
            // separate the date line from the legend rows above it
            def.comment("\\s");
            cfg.legendSdf = cfg.useUtc ? UTC_DATE_FMT.get() : LOCAL_DATE_FMT.get();
            def.comment(cfg.legendSdf.format(new Date(cfg.start)) + " \u2014 " + cfg.legendSdf.format(new Date(cfg.end)) + cfg.timeLabel + "\\r");
        }
        if (!cfg.showCredit) {
            def.setShowSignature(false);
        } else if (cfg.hideLegend) {
            cfg.legendSdf = cfg.useUtc ? UTC_DATE_FMT.get() : LOCAL_DATE_FMT.get();
            if (cfg.height > 65) {
                def.setSignature("    " + cfg.legendSdf.format(new Date(cfg.end)) + cfg.timeLabel);
            } else {
                def.setSignature(cfg.legendSdf.format(new Date(cfg.end)) + cfg.timeLabel);
            }
        }
        if (cfg.hideLegend) {
            def.setNoLegend(true);
        }
    }

    private void configureGridAndRendering(RrdGraphDef def, GraphRenderConfig cfg) {
        if (cfg.hideGrid) {
            def.setDrawXGrid(false);
            def.setDrawYGrid(false);
        }
        def.setAntiAliasing(false);
        def.setTextAntiAliasing(true);
        def.setSmoothing(cfg.smooth);
        def.setGridStroke(GRID_STROKE);
        def.setWidth(cfg.width);
        def.setHeight(cfg.height);
        def.setLazy(true);
        def.setPoolUsed(true);
        def.setAltYMrtg(usesMrtgScaling(cfg));
        if (cfg.width < 400 || cfg.height < 200) {
            def.setNoMinorGrid(true);
            def.setAltYMrtg(false);
        }

        if ((cfg.width == 250 && cfg.height == 50 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid)
                || (cfg.width == 2000 && cfg.height == 160 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid)) {
            def.setOnlyGraph(true);
            def.setColor(RrdGraphDef.COLOR_CANVAS, TRANSPARENT);
            def.setColor(RrdGraphDef.COLOR_BACK, TRANSPARENT);
        }
    }

    private void renderGraph(RrdGraphDef def, OutputStream out, GraphRenderConfig cfg) throws IOException {
        RrdGraph graph;
        try {
            graph = new RrdGraph(def, new SVGImageWorker(0, 0,
                    _context.getBooleanPropertyDefaultTrue("routerconsole.graphGlow"),
                    cfg.smooth, cfg.theme));
        } catch (NullPointerException npe) {
            _log.error("Error rendering graph (not disabling — transient)", npe);
            throw new IOException("Error rendering graph", npe);
        } catch (Error e) {
            _log.error("Error rendering graph (not disabling — transient)", e);
            throw new IOException("Error rendering graph", e);
        }
out.write(graph.getRrdGraphInfo().getBytes());
    }

    /**
     * Hand the theme's dot pattern to the renderer.
     *
     * <p>A theme may state only the dot length, in which case jrobin derives the gap from
     * the stroke width. When it states a gap too, that value is used unless it would merge
     * the dots, which is decided in {@code RrdGraphConstants.minimumDashGap}. A dot length
     * of zero, from {@code --graph_plotDash:0}, asks for a solid line and leaves no gap to set.
     *
     * @since 0.9.71+
     */
    private static void applyDash(RrdGraphDef def, GraphRenderConfig cfg) {
        File dir = GraphThemeColors.installedThemeDir();
        float dot = GraphThemeColors.dashLength(dir, cfg.theme);
        def.setSeriesDash(dot);
        if (dot > 0f) {
            def.setSeriesDashGap(GraphThemeColors.dashGap(dir, cfg.theme));
        }
    }

    /**
     * Apply the theme's colours to the graph definition.
     *
     * <p>Every one of them is the theme's to state in its own {@code console.css}; this class
     * holds only the fallbacks for a stylesheet that declares nothing, so a frame reads as
     * part of the page it is drawn on.
     */
    private static void applyTheme(RrdGraphDef def, GraphRenderConfig cfg) {
        applyDash(def, cfg);
        File dir = GraphThemeColors.installedThemeDir();
        Color font = GraphThemeColors.textColor(dir, cfg.theme);
        Color axis = GraphThemeColors.axisColor(dir, cfg.theme);
        Color grid = GraphThemeColors.gridMinorColor(dir, cfg.theme);
        Color mgrid = GraphThemeColors.gridMajorColor(dir, cfg.theme);
        def.setGridStroke(gridStroke(GraphThemeColors.gridMinorDash(dir, cfg.theme),
                                     GraphThemeColors.gridMinorDashGap(dir, cfg.theme)));
        def.setMajorGridStroke(gridStroke(GraphThemeColors.gridMajorDash(dir, cfg.theme),
                                          GraphThemeColors.gridMajorDashGap(dir, cfg.theme)));
        def.setColor(ElementsNames.font, font);
        // sidebar minigraph
        if ((cfg.width == 250 && cfg.height == 50 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid)
                || (cfg.width == 2000 && cfg.height == 160 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid)) {
            def.setColor(ElementsNames.xaxis, TRANSPARENT);
            def.setColor(ElementsNames.yaxis, TRANSPARENT);
            def.setColor(ElementsNames.frame, TRANSPARENT);
        } else {
            def.setColor(ElementsNames.xaxis, axis);
            def.setColor(ElementsNames.yaxis, axis);
        }
        def.setColor(ElementsNames.back, GraphThemeColors.backgroundColor(dir, cfg.theme));
        // The canvas is the margin ring around the plot, not the plot. It takes the same
        // colour the plot does: in-page the console's graph container supplies the page behind
        // the ring, and a standalone SVG has nothing else to draw an opaque ring on, so
        // leaving it to rrd4j's default would make the same graph look different depending on
        // how it was reached.
        def.setColor(ElementsNames.canvas, GraphThemeColors.backgroundColor(dir, cfg.theme));
        Color shade = GraphThemeColors.edgeShadeColor(dir, cfg.theme);
        def.setColor(ElementsNames.shadea, shade);
        def.setColor(ElementsNames.shadeb, shade);
        def.setColor(ElementsNames.grid, grid);
        def.setColor(ElementsNames.mgrid, mgrid);
        def.setColor(ElementsNames.frame, FRAME_COLOR);
        def.setColor(ElementsNames.arrow, ARROW_COLOR);

        if (cfg.width < 400 || cfg.height < 200 || cfg.periodCount < 120) {
            // Too small for a grid to register: the minor grid goes and the labelled lines are
            // left, in the theme's own compact gridline rather than either of its two grids.
            // Which one that is differs per theme, so it is stated in the stylesheet; changing
            // the asymmetry altered how every small tile on the console looked.
            def.setColor(ElementsNames.grid, GRID_COLOR_HIDDEN);
            def.setColor(ElementsNames.mgrid,
                         GraphThemeColors.compactGridColor(dir, cfg.theme));
        }
    }

    /**
     * The stroke gridlines are drawn with, from a theme's stated dot and gap.
     *
     * @param dot ink length of one dot; zero asks for solid gridlines
     * @param gap space after the dot, or zero to derive it from the stroke width
     * @return the stroke
     */
    private static Stroke gridStroke(float dot, float gap) {
        return RrdGraphConstants.gridStroke(1f, dot, gap);
    }

    /**
     * Title replacement rules, ordered by key length descending (longest first)
     * so that longer prefixes are matched before their shorter ancestors.
     * Each entry: key -> replacement.
     */
    private static final List<Map.Entry<String, String>> TITLE_REPLACEMENTS;
    static {
        List<Map.Entry<String, String>> list = new java.util.ArrayList<>();
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.participatingTunnels", "[Transit] Tunnel Count"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.participatingMessage", "[Transit] Message"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.buildRatio.exploratory.", "[Exploratory] Build Ratio"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.buildExploratory", "[Exploratory] Build"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.buildClient", "[Tunnel] BuildClient"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.participating", "[Transit]"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("Tunnel.participating", "[Transit]"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("router.", "[Router] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("bw.", "[Router] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("Bandwidth usage", "[Router] Bandwidth Usage"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.build", "[Tunnel] Build"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("tunnel.", "[Tunnel] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("netDb.", "[NetDb] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("jobQueue.", "[JobQueue] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("udp.", "[UDP] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("ntcp.", "[NTCP] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("transport.", "[Transport] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("client.", "[Client] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("peer.", "[Peer] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("prng.", "[Crypto] pnrg."));
        list.add(new java.util.AbstractMap.SimpleEntry<>("crypto.", "[Crypto] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("bwLimiter.", "[BWLimiter] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("codel.", "[Router] CODEL."));
        list.add(new java.util.AbstractMap.SimpleEntry<>("stream.", "[Stream] "));
        list.add(new java.util.AbstractMap.SimpleEntry<>("MessageCountAvg", "Message Count Average"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("clock.skew", "[Router] Clock Skew"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("InBps", "Inbound B/s"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("OutBps", "Outbound B/s"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("Bps", "B/s"));
        TITLE_REPLACEMENTS = Collections.unmodifiableList(list);
    }

    /**
     * Post-processing replacements applied after prefix rules.
     * These fix artifacts created by earlier replacements (e.g., "[Tunnel] [Tunnel]").
     */
    private static final List<Map.Entry<String, String>> TITLE_POST_REPLACEMENTS;
    static {
        List<Map.Entry<String, String>> list = new java.util.ArrayList<>();
        list.add(new java.util.AbstractMap.SimpleEntry<>("[Tunnel] Tunnel", "[Tunnel]"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("Tunnel.participating", "[Transit]"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("[Tunnel] Participating Tunnels", "[Transit] Tunnel Count"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("Cpu", "CPU"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("CPULoad", "CPU Load"));
        list.add(new java.util.AbstractMap.SimpleEntry<>(" Avg", " Average"));
        list.add(new java.util.AbstractMap.SimpleEntry<>("[Tunnel]Build", "[Tunnel] Build"));
        TITLE_POST_REPLACEMENTS = Collections.unmodifiableList(list);
    }

    /**
     * Derive a human-readable graph title from the rate stat name.
     * Pure function — no side effects — uses ordered prefix map for deterministic results.
     */
    private static String deriveTitle(String name) {
        String graphTitle = name;
        for (Map.Entry<String, String> entry : TITLE_REPLACEMENTS) {
            if (entry.getKey().endsWith(".") || entry.getKey().endsWith(" ")) {
                if (graphTitle.startsWith(entry.getKey())) {
                    graphTitle = entry.getValue().concat(graphTitle.substring(entry.getKey().length()));
                    break; // longest prefix matched, stop
                }
            } else {
                if (graphTitle.equals(entry.getKey()) || graphTitle.startsWith(entry.getKey())) {
                    graphTitle = graphTitle.replace(entry.getKey(), entry.getValue());
                }
            }
        }
        // Special handling for exact match
        if (name.equals("clock.skew")) {
            graphTitle = "[Router] Clock Skew";
        }
        for (Map.Entry<String, String> entry : TITLE_POST_REPLACEMENTS) {
            graphTitle = graphTitle.replace(entry.getKey(), entry.getValue());
        }
        graphTitle = CSSHelper.StringFormatter.capitalizeWord(graphTitle);
        return graphTitle;
    }

    /**
     * Font family configuration for a specific language.
     * Ordered by preference: first available font in the list is used.
     */
    private static final class FontConfig {
        final List<String> titleCandidates;
        final List<String> monoCandidates;
        final String fallbackTitle;
        final String fallbackMono;

        FontConfig(List<String> titleCandidates, List<String> monoCandidates,
                   String fallbackTitle, String fallbackMono) {
            this.titleCandidates = titleCandidates;
            this.monoCandidates = monoCandidates;
            this.fallbackTitle = fallbackTitle;
            this.fallbackMono = fallbackMono;
        }
    }

    /** Per-language font configuration. Legend uses title candidates. */
    private static final Map<String, FontConfig> FONT_CONFIGS;
    static {
        Map<String, FontConfig> m = new java.util.HashMap<>();
        m.put("zh", new FontConfig(
            Arrays.asList("Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC"),
            Arrays.asList("Noto Sans Mono SC", "Noto Sans Mono CJK SC"),
            "Dialog", "Monospaced"));
        m.put("jp", new FontConfig(
            Arrays.asList("Noto Sans JP", "Noto Sans CJK JP", "Source Han Sans JP"),
            Arrays.asList("Noto Sans Mono JP", "Noto Sans Mono CJK JP"),
            "Dialog", "Monospaced"));
        m.put("ko", new FontConfig(
            Arrays.asList("Noto Sans KO", "Noto Sans CJK KO", "Source Han Sans KO"),
            Arrays.asList("Noto Sans Mono KO", "Noto Sans Mono CJK KO"),
            "Dialog", "Monospaced"));
        FONT_CONFIGS = Collections.unmodifiableMap(m);
    }

    /**
     * Per-language font family selection for graph rendering.
     * Pure function — no side effects — so it is safe to call from any thread.
     *
     * @param lang the active UI language code (e.g. "en", "zh", "jp", "ko")
     * @return resolved mono / legend / title family names for the current platform
     */
    private static FontNames selectFontNames(String lang) {
        FontConfig config = FONT_CONFIGS.get(lang);
        if (config != null) {
            String title = config.fallbackTitle;
            for (String candidate : config.titleCandidates) {
                if (FONTLIST.contains(candidate)) {
                    title = candidate;
                    break;
                }
            }
            String mono = config.fallbackMono;
            for (String candidate : config.monoCandidates) {
                if (FONTLIST.contains(candidate)) {
                    mono = candidate;
                    break;
                }
            }
            return new FontNames(mono, title, title); // legend == title
        }
        // fall back to generic family names; the renderer selects the
        // concrete face per output format. Legend uses a sans-serif face,
        // while the unit/axis metric text stays monospaced for alignment.
        return new FontNames("Monospaced", "SansSerif", "SansSerif");
    }

    /** Resolved font family names for a render pass. */
    private static final class FontNames {
        final String mono;
        final String legend;
        final String title;

        FontNames(String mono, String legend, String title) {
            this.mono = mono;
            this.legend = legend;
            this.title = title;
        }
    }

    /**
     * Immutable configuration for a single graph render pass.
     * Built by {@link #buildRenderConfig} and consumed by the various configure* methods.
     */
    private static final class GraphRenderConfig {
        final long start;
        final long end;
        final long period;
        final int width;
        final int height;
        final int periodCount;
        final String theme;
        final boolean hideLegend;
        final boolean hideGrid;
        final boolean hideTitle;
        final boolean showEvents;
        final boolean showCredit;
        final boolean showRestarts;
        final String titleOverride;
        final Rate rate;
        /** Extra series of a combined graph, in legend order; empty for a single-stat graph. */
        final List<GraphListener> extras;

        /**
         * Draw the primary series as a line rather than a filled area. Set for combined
         * graphs: a filled primary hides whichever series crosses it, so every member of a
         * group is drawn the same way.
         */
        final boolean allLines;
        /** Render every series as a translucent filled path under a thin line. @since 0.9.71+ */
        final boolean fillSeries;
        final boolean useUtc;
        final boolean smooth;
        /** True to keep the historical zero-floored y-axis ({@link #PROP_ZERO_BASE}). */
        final boolean forceZero;
        final String lang;
        final GraphListener listener;

        // Computed fields (filled by builders)
        String derivedTitle;
        boolean noDecimalPlace;
        boolean singleDecimalPlace;
        int base; // 1000 or 1024
        Font small;
        Font legend;
        Font title;
        String numberFormat;
        Color restartColor;
        SimpleDateFormat legendSdf;
        /** Date suffix for the legend/signature; " UTC" when rendering in UTC.
         *  Derived from useUtc here so every path prints a consistent label,
         *  never a literal "null".
         */
        String timeLabel;
        String plotName;
        String descr;
        String path;
        String[] dsNames;
        String plotName2;
        String descr2;
        String path2;
        String[] dsNames2;
        int linewidth;
        /** Smallest finite value in the plotted window, NaN until resolved. */
        double dataMin;
        /** Largest finite value in the plotted window, NaN until resolved. */
        double dataMax;

        private GraphRenderConfig(Builder b) {
            this.start = b.start;
            this.end = b.end;
            this.period = b.period;
            this.width = b.width;
            this.height = b.height;
            this.periodCount = b.periodCount;
            this.theme = b.theme;
            this.hideLegend = b.hideLegend;
            this.hideGrid = b.hideGrid;
            this.hideTitle = b.hideTitle;
            this.showEvents = b.showEvents;
            this.showCredit = b.showCredit;
            this.showRestarts = b.showRestarts;
            this.titleOverride = b.titleOverride;
            this.rate = b.rate;
            this.extras = b.extras;
            this.allLines = b.allLines;
            this.fillSeries = b.fillSeries;
            this.useUtc = b.useUtc;
            this.smooth = b.smooth;
            this.forceZero = b.forceZero;
            // Derived here so every legend/signature path prints a
            // consistent date suffix, never a literal "null"
            this.timeLabel = b.useUtc ? " UTC" : "";
            this.lang = b.lang;
            this.listener = b.listener;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            long start;
            long end;
            long period;
            int width;
            int height;
            int periodCount;
            String theme;
            boolean hideLegend;
            boolean hideGrid;
            boolean hideTitle;
            boolean showEvents;
            boolean showCredit;
            boolean showRestarts;
            String titleOverride;
            Rate rate;
            List<GraphListener> extras;
            boolean allLines;
            boolean fillSeries;
            boolean useUtc;
            boolean smooth;
            boolean forceZero;
            String lang;
            GraphListener listener;

            Builder start(long v) { start = v; return this; }
            Builder end(long v) { end = v; return this; }
            Builder period(long v) { period = v; return this; }
            Builder width(int v) { width = v; return this; }
            Builder height(int v) { height = v; return this; }
            Builder periodCount(int v) { periodCount = v; return this; }
            Builder theme(String v) { theme = v; return this; }
            Builder hideLegend(boolean v) { hideLegend = v; return this; }
            Builder hideGrid(boolean v) { hideGrid = v; return this; }
            Builder hideTitle(boolean v) { hideTitle = v; return this; }
            Builder showEvents(boolean v) { showEvents = v; return this; }
            Builder showCredit(boolean v) { showCredit = v; return this; }
            Builder showRestarts(boolean v) { showRestarts = v; return this; }
            Builder titleOverride(String v) { titleOverride = v; return this; }
            Builder rate(Rate v) { rate = v; return this; }

            /**
             * @param v extra series to plot on the same graph, or null/empty for none
             * @return this builder
             * @since 0.9.71+
             */
            Builder extras(List<GraphListener> v) { extras = v; return this; }

            /**
             * @param v true to draw the primary as a line instead of a filled area
             * @return this builder
             * @since 0.9.71+
             */
            Builder allLines(boolean v) { allLines = v; return this; }

            /**
             * @param v render every series as a filled path under a thin line
             * @return this builder
             * @since 0.9.71+
             */
            Builder fillSeries(boolean v) { fillSeries = v; return this; }

            /**
             * @param v single extra series to plot on the same graph, or null
             * @return this builder
             */
            Builder lsnr2(GraphListener v) {
                extras = (v == null) ? null : java.util.Collections.singletonList(v);
                return this;
            }
            Builder useUtc(boolean v) { useUtc = v; return this; }
            Builder smooth(boolean v) { smooth = v; return this; }
            Builder forceZero(boolean v) { forceZero = v; return this; }
            Builder lang(String v) { lang = v; return this; }
            Builder listener(GraphListener v) { listener = v; return this; }

            GraphRenderConfig build() {
                return new GraphRenderConfig(this);
            }
        }
    }

    /** translate a string */
    private String _t(String s) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {
            return s;
        }
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     */
    private String _t(String s, String o) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {
            return s.replace("{0}", o);
        }
        return Messages.getString(s, o, _context);
    }
}
