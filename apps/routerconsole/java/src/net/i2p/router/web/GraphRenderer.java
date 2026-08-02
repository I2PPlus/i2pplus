package net.i2p.router.web;

import static net.i2p.router.web.GraphConstants.*;

import eu.bengreen.data.utility.LargestTriangleThreeBucketsTime;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Stroke;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
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
import org.rrd4j.core.RrdException;
import org.rrd4j.data.Variable;
import org.rrd4j.graph.ElementsNames;
import org.rrd4j.graph.RrdGraph;
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
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    private static final Color CANVAS_COLOR_DARK = new Color(0, 0, 0);
    private static final Color BACK_COLOR = new Color(255, 255, 255);
    private static final Color BACK_COLOR_DARK = new Color(0, 0, 0, 192);
    private static final Color SHADEA_COLOR = new Color(255, 255, 255);
    private static final Color SHADEA_COLOR_DARK = new Color(0, 0, 0);
    private static final Color SHADEB_COLOR = new Color(255, 255, 255);
    private static final Color SHADEB_COLOR_DARK = new Color(0, 0, 0);
    private static final Color GRID_COLOR = new Color(80, 80, 80, 50);
    private static final Color GRID_COLOR_DARK = new Color(244, 244, 190, 50);
    private static final Color GRID_COLOR_DARK2 = new Color(244, 244, 190, 30);
    private static final Color GRID_COLOR_MIDNIGHT = new Color(201, 206, 255, 50);
    private static final Color GRID_COLOR_HIDDEN = new Color(0, 0, 0, 0);
    private static final Color MGRID_COLOR = new Color(255, 91, 91, 110);
    private static final Color MGRID_COLOR_DARK = new Color(200, 200, 0, 50);
    private static final Color MGRID_COLOR_MIDNIGHT = new Color(240, 32, 192, 110);
    private static final Color FONT_COLOR = new Color(51, 51, 63);
    private static final Color FONT_COLOR_DARK = new Color(244, 244, 190);
    private static final Color FONT_COLOR_MIDNIGHT = new Color(201, 206, 255);
    private static final Color AXIS_COLOR_DARK = new Color(244, 244, 190, 200);
    private static final Color AXIS_COLOR_MIDNIGHT = new Color(201, 206, 255, 200);
    private static final Color FRAME_COLOR = new Color(0, 0, 0, 0);
    private static final Color FRAME_COLOR_DARK = new Color(0, 0, 0, 0);
    private static final Color AREA_COLOR = new Color(100, 160, 200, 200);
    private static final Color AREA_COLOR_DARK = new Color(0, 72, 8, 220);
    private static final Color AREA_COLOR_MIDNIGHT = new Color(0, 72, 160, 200);
    private static final Color AREA_COLOR_NEUTRAL = new Color(128, 128, 128, 128);
    private static final Color LINE_COLOR = new Color(0, 30, 110, 255);
    private static final Color LINE_COLOR_DARK = new Color(100, 200, 160);
    private static final Color LINE_COLOR_MIDNIGHT = new Color(128, 180, 212);
    private static final Color ARROW_COLOR_DARK = new Color(0, 0, 0, 0);
    private static final Color RESTART_BAR_COLOR = new Color(223, 13, 13, 255);
    private static final Color RESTART_BAR_COLOR_DARK = new Color(220, 16, 48, 220);

    private static final boolean IS_WIN = SystemVersion.isWindows();
    private static final String DEFAULT_FONT_NAME = IS_WIN ? "Lucida Console" : "Monospaced";
    private static final String DEFAULT_TITLE_FONT_NAME = "Dialog";
    private static final String DEFAULT_LEGEND_FONT_NAME = "Dialog";
    private static final String PROP_FONT_MONO = "routerconsole.graphFont.unit";
    private static final String PROP_FONT_LEGEND = "routerconsole.graphFont.legend";
    private static final String PROP_FONT_TITLE = "routerconsole.graphFont.title";
    private static final int SIZE_MONO = 10;
    private static final int SIZE_LEGEND = 11;
    private static final int SIZE_TITLE = 12;
    private static final long[] RATES = RateConstants.BASIC_RATES;
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
                null,
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
                showEvents, periodCount, endp, showCredit, lsnr2, titleOverride, showRestarts);
        RrdGraphDef def = new RrdGraphDef(cfg.start / 1000, cfg.end / 1000);
        configureDownsampler(def, cfg.periodCount);
        configureTimeZone(def, cfg.useUtc);
        applyTheme(def, cfg);
        configureFonts(def, cfg);
        configureBaseAndDecimals(cfg);
        configureTitle(def, cfg);
        configureDataSources(def, cfg);
        configureLegend(def, cfg);
        if (cfg.lsnr2 != null) {
            configureSecondDataSource(def, cfg);
        }
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
            boolean showCredit, GraphListener lsnr2, String titleOverride, boolean showRestarts) {
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
                .lsnr2(lsnr2)
                .useUtc(useUtc)
                .lang(lang)
                .listener(_listener)
                .build();
    }

    private void configureDownsampler(RrdGraphDef def, int periodCount) {
        if (periodCount >= 10080) {
            def.setDownsampler(new LargestTriangleThreeBucketsTime(100));
        } else if (periodCount >= 2880) {
            def.setDownsampler(new LargestTriangleThreeBucketsTime(200));
        } else if (periodCount >= 1440) {
            def.setDownsampler(new LargestTriangleThreeBucketsTime(500));
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
        def.setMinValue(0d);
    }

    private void configureBaseAndDecimals(GraphRenderConfig cfg) {
        String name = cfg.rate.getRateStat().getName();
        cfg.derivedTitle = deriveTitle(name);

        cfg.singleDecimalPlace = true;
        cfg.noDecimalPlace = false;

        // heuristic to set K=1024
        if ((name.toLowerCase().indexOf("size") >= 0
                        || name.toLowerCase().indexOf("memory") >= 0
                        || name.toLowerCase().indexOf("b/s") >= 0
                        || name.toLowerCase().indexOf("bps") >= 0
                        || name.toLowerCase().indexOf("bandwidth") >= 0
                        || name.toLowerCase().indexOf("bytecache") >= 0)
                && !cfg.showEvents) {
            cfg.base = 1024;
            cfg.singleDecimalPlace = false;
        } else if (!cfg.noDecimalPlace) {
            cfg.base = 1000;
        }

        // Percentages need no fractional precision
        if (!cfg.noDecimalPlace) {
            String lname = name.toLowerCase();
            if (lname.indexOf("percent") >= 0 || lname.indexOf("%") >= 0) {
                cfg.noDecimalPlace = true;
            }
        }

        // Integer/count metrics
        if (!cfg.noDecimalPlace) {
            String lname = name.toLowerCase();
            boolean isByteOrRate = lname.indexOf("b/s") >= 0 || lname.indexOf("bps") >= 0
                    || lname.indexOf("bandwidth") >= 0 || lname.indexOf("byte") >= 0
                    || lname.indexOf("memory") >= 0;
            boolean isCountSize = lname.indexOf("keyset") >= 0 || lname.indexOf("keysize") >= 0;
            if (!isByteOrRate || isCountSize) {
                if (lname.endsWith("rate")) {
                    // not an integer metric
                } else if (lname.endsWith("count") || lname.indexOf(".threads") >= 0
                        || lname.indexOf("thread") >= 0 || lname.indexOf("queuedepth") >= 0
                        || lname.indexOf("queuesize") >= 0 || lname.indexOf("peercount") >= 0
                        || lname.indexOf("profilecount") >= 0 || lname.indexOf("tunnelcount") >= 0
                        || lname.indexOf("activepeers") >= 0 || lname.indexOf("fastpeer") >= 0
                        || lname.indexOf("highcap") >= 0 || lname.indexOf("qualitypeer") >= 0
                        || lname.indexOf("tunnels") >= 0 || lname.indexOf("numtunnels") >= 0
                        || lname.indexOf("concurrent") >= 0 || lname.indexOf("handshakes") >= 0
                        || lname.indexOf("builds") >= 0 || lname.indexOf("reject") >= 0
                        || lname.indexOf("refused") >= 0 || lname.indexOf("dropped") >= 0
                        || lname.indexOf("expired") >= 0 || lname.indexOf("failed") >= 0
                        || lname.indexOf("timeouts") >= 0 || lname.indexOf("messages") >= 0
                        || lname.indexOf("jobs") >= 0 || lname.indexOf("loops") >= 0
                        || lname.indexOf("keyset") >= 0
                        || lname.indexOf("inboundconn") >= 0 || lname.indexOf("outboundconn") >= 0
                        || lname.indexOf("participating") >= 0 || lname.indexOf("clienttunnels") >= 0
                        || lname.indexOf("servertunnels") >= 0 || lname.indexOf("exploratory") >= 0
                        || lname.indexOf("peer") >= 0) {
                    cfg.noDecimalPlace = true;
                }
            }
        }

        cfg.numberFormat = cfg.noDecimalPlace ? "%.0f" : cfg.singleDecimalPlace ? "%.1f%s" : "%.2f%s";
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
        configureArea(def, cfg);
    }

    private void configureArea(RrdGraphDef def, GraphRenderConfig cfg) {
        if (cfg.width == 2000 && cfg.height == 160 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid) {
            def.area(cfg.plotName, AREA_COLOR_NEUTRAL);
        } else if (cfg.theme.equals("dark")) {
            if (!cfg.descr.isEmpty()) {
                def.area(cfg.plotName, AREA_COLOR_DARK, cfg.descr + "\\l");
            } else {
                def.area(cfg.plotName, AREA_COLOR_DARK);
            }
        } else if (cfg.theme.equals("midnight")) {
            if (!cfg.descr.isEmpty()) {
                def.area(cfg.plotName, AREA_COLOR_MIDNIGHT, cfg.descr + "\\l");
            } else {
                def.area(cfg.plotName, AREA_COLOR_MIDNIGHT);
            }
        } else {
            if (!cfg.descr.isEmpty()) {
                def.area(cfg.plotName, AREA_COLOR, cfg.descr + "\\l");
            } else {
                def.area(cfg.plotName, AREA_COLOR);
            }
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

    private void configureSecondDataSource(RrdGraphDef def, GraphRenderConfig cfg) throws IOException {
        try {
            cfg.dsNames2 = cfg.lsnr2.getData().getDsNames();
        } catch (IOException ioe) {
            throw new IOException("Failed to get second datasource names", ioe);
        }
        cfg.plotName2 = cfg.dsNames2[0];
        cfg.path2 = cfg.lsnr2.getData().getPath();
        cfg.descr2 = _t(cfg.lsnr2.getRate().getRateStat().getDescription());
        def.datasource(cfg.plotName2, cfg.path2, cfg.plotName2, GraphListener.CF, cfg.lsnr2.getBackendFactory());
        cfg.linewidth = 2;
        if (cfg.width == 250 && cfg.height == 50 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid) {
            cfg.linewidth = 3;
        } else if (cfg.periodCount >= 720 || (cfg.periodCount >= 480 && cfg.width <= 600)) {
            cfg.linewidth = 1;
        }
        if (cfg.theme.equals("midnight")) {
            def.line(cfg.plotName2, LINE_COLOR_MIDNIGHT, cfg.descr2 + "\\l", cfg.linewidth);
        } else if (cfg.theme.equals("dark")) {
            def.line(cfg.plotName2, LINE_COLOR_DARK, cfg.descr2 + "\\l", cfg.linewidth);
        } else {
            def.line(cfg.plotName2, LINE_COLOR, cfg.descr2 + "\\l", cfg.linewidth);
        }

        if (!cfg.hideLegend) {
            Variable var = new Variable.MAX();
            def.datasource("max2", cfg.plotName2, var);
            def.gprint("max2", " " + _t("Max") + ": " + cfg.numberFormat + " ");
            var = new Variable.MIN();
            def.datasource("min2", cfg.plotName2, var);
            def.gprint("min2", " " + _t("Min") + ": " + cfg.numberFormat + " ");
            var = new Variable.AVERAGE();
            def.datasource("avg2", cfg.plotName2, var);
            def.gprint("avg2", " " + _t("Avg") + ": " + cfg.numberFormat + " ");
            var = new Variable.LAST();
            def.datasource("last2", cfg.plotName2, var);
            def.gprint("last2", " " + _t("Now") + ": " + cfg.numberFormat + "\\l");
        }
    }

    private void configureRestartMarkers(RrdGraphDef def, GraphRenderConfig cfg) {
        if (!cfg.hideLegend && cfg.showRestarts) {
            cfg.timeLabel = cfg.useUtc ? " UTC" : "";
            cfg.legendSdf = cfg.useUtc ? UTC_DATE_FMT.get() : LOCAL_DATE_FMT.get();
            int count = 0;
            cfg.restartColor = cfg.theme.equals("midnight") || cfg.theme.equals("dark") ? RESTART_BAR_COLOR_DARK : RESTART_BAR_COLOR;

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
        def.setGridStroke(GRID_STROKE);
        def.setWidth(cfg.width);
        def.setHeight(cfg.height);
        def.setLazy(true);
        def.setPoolUsed(true);
        def.setAltYMrtg(!cfg.noDecimalPlace);
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
                    _context.getBooleanPropertyDefaultTrue("routerconsole.graphGlow")));
        } catch (NullPointerException npe) {
            _log.error("Error rendering graph", npe);
            GraphGenerator.setDisabled(_context);
            throw new IOException("Error rendering - disabling graph generation.");
        } catch (Error e) {
            _log.error("Error rendering graph", e);
            GraphGenerator.setDisabled(_context);
            throw new IOException("Error rendering - disabling graph generation.");
        }
out.write(graph.getRrdGraphInfo().getBytes());
    }

    /**
     *  Apply the theme-specific colors to the graph definition.
     *  Extracted from render() to keep that method manageable.
     */
    private static void applyTheme(RrdGraphDef def, GraphRenderConfig cfg) {
        // sidebar minigraph
        if ((cfg.width == 250 && cfg.height == 50 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid)
                || (cfg.width == 2000 && cfg.height == 160 && cfg.hideTitle && cfg.hideLegend && cfg.hideGrid)) {
            def.setColor(ElementsNames.xaxis, TRANSPARENT);
            def.setColor(ElementsNames.yaxis, TRANSPARENT);
            def.setColor(ElementsNames.frame, TRANSPARENT);
        // Override defaults (dark themes)
        } else if (cfg.theme.equals("midnight")) {
            def.setColor(ElementsNames.font, FONT_COLOR_MIDNIGHT);
            def.setColor(ElementsNames.xaxis, AXIS_COLOR_MIDNIGHT);
            def.setColor(ElementsNames.yaxis, AXIS_COLOR_MIDNIGHT);
        } else if (cfg.theme.equals("dark")) {
            def.setColor(ElementsNames.font, FONT_COLOR_DARK);
            def.setColor(ElementsNames.xaxis, AXIS_COLOR_DARK);
            def.setColor(ElementsNames.yaxis, AXIS_COLOR_DARK);
        }
        if (cfg.theme.equals("midnight") || cfg.theme.equals("dark")) {
            def.setColor(ElementsNames.back, BACK_COLOR_DARK);
            def.setColor(ElementsNames.canvas, TRANSPARENT);
        } else {
            def.setColor(ElementsNames.back, BACK_COLOR);
        }
        if (cfg.theme.equals("midnight") || cfg.theme.equals("dark")) {
            def.setColor(ElementsNames.shadea, TRANSPARENT);
            def.setColor(ElementsNames.shadeb, TRANSPARENT);
            if (cfg.theme.equals("dark")) {
                def.setColor(ElementsNames.grid, GRID_COLOR_DARK2);
                def.setColor(ElementsNames.mgrid, MGRID_COLOR_DARK);
            } else if (cfg.theme.equals("midnight")) {
                def.setColor(ElementsNames.grid, GRID_COLOR_MIDNIGHT);
                def.setColor(ElementsNames.mgrid, MGRID_COLOR_MIDNIGHT);
            }
            def.setColor(ElementsNames.frame, FRAME_COLOR_DARK);
            def.setColor(ElementsNames.arrow, ARROW_COLOR_DARK);
        } else {
            // Override defaults (light themes)
            def.setColor(ElementsNames.shadea, SHADEA_COLOR);
            def.setColor(ElementsNames.shadeb, SHADEB_COLOR);
            def.setColor(ElementsNames.grid, GRID_COLOR);
            def.setColor(ElementsNames.mgrid, MGRID_COLOR);
            def.setColor(ElementsNames.font, FONT_COLOR);
            def.setColor(ElementsNames.frame, FRAME_COLOR);
        }

        if (cfg.width < 400 || cfg.height < 200 || cfg.periodCount < 120) {
            def.setColor(ElementsNames.grid, GRID_COLOR_HIDDEN);
            if (cfg.theme.equals("midnight")) {
                def.setColor(ElementsNames.mgrid, GRID_COLOR_MIDNIGHT);
            } else if (cfg.theme.equals("dark")) {
                def.setColor(ElementsNames.mgrid, GRID_COLOR_DARK);
            } else {
                def.setColor(ElementsNames.mgrid, GRID_COLOR);
            }
        }
    }

    /**
     *  Derive a human-readable graph title from the rate stat name.
     *  Pure function — no side effects — extracted from render() so the
     *  (large) prefix/substring rewriting is isolated and testable.
     */
    private static String deriveTitle(String name) {
        String graphTitle = name;
        if (name.startsWith("tunnel.participatingTunnels")) {
            graphTitle = graphTitle.replace("tunnel.participatingTunnels", "[Transit] Tunnel Count");
        }
        if (name.startsWith("tunnel.participatingMessage")) {
            graphTitle = graphTitle.replace("tunnel.participatingMessage", "[Transit] Message");
        } else if (name.startsWith("tunnel.participating")) {
            graphTitle = graphTitle.replace("tunnel.participating", "[Transit]");
        } else if (name.startsWith("Tunnel.participating")) {
            graphTitle = graphTitle.replace("Tunnel.participating", "[Transit]");
        }
        if (name.startsWith("router.")) {
            graphTitle = graphTitle.replace("router.", "[Router] ");
        }
        if (name.startsWith("bw.")) {
            graphTitle = graphTitle.replace("bw.", "[Router] ");
        }
        if (name.startsWith("Bandwidth usage")) {
            graphTitle = graphTitle.replace("Bandwidth usage", "[Router] Bandwidth Usage");
        }
        if (name.startsWith("tunnel.buildRatio.exploratory.")) {
            graphTitle = graphTitle.replace("tunnel.buildRatio.exploratory.", "[Exploratory] Build Ratio");
        }
        if (name.startsWith("tunnel.buildExploratory")) {
            graphTitle = graphTitle.replace("tunnel.buildExploratory", "[Exploratory] Build");
        }
        if (name.startsWith("tunnel.buildClient")) {
            graphTitle = graphTitle.replace("tunnel.buildClient", "[Tunnel] BuildClient");
        } else if (name.startsWith("tunnel.build")) {
            graphTitle = graphTitle.replace("tunnel.build", "[Tunnel] Build");
        } else if (name.startsWith("tunnel.")) {
            graphTitle = graphTitle.replace("tunnel.", "[Tunnel] ");
        }
        if (name.contains("MessageCountAvg")) {
            graphTitle = graphTitle.replace("MessageCountAvg", "Messsage Count Average");
        }
        if (name.startsWith("netDb.")) {
            graphTitle = graphTitle.replace("netDb.", "[NetDb] ");
        }
        if (name.startsWith("jobQueue.")) {
            graphTitle = graphTitle.replace("jobQueue.", "[JobQueue] ");
        }
        if (name.startsWith("udp.")) {
            graphTitle = graphTitle.replace("udp.", "[UDP] ");
        }
        if (name.startsWith("ntcp.")) {
            graphTitle = graphTitle.replace("ntcp.", "[NTCP] ");
        }
        if (name.startsWith("transport.")) {
            graphTitle = graphTitle.replace("transport.", "[Transport] ");
        }
        if (name.startsWith("client.")) {
            graphTitle = graphTitle.replace("client.", "[Client] ");
        }
        if (name.startsWith("peer.")) {
            graphTitle = graphTitle.replace("peer.", "[Peer] ");
        }
        if (name.startsWith("prng.")) {
            graphTitle = graphTitle.replace("prng.", "[Crypto] pnrg.");
        }
        if (name.startsWith("crypto.")) {
            graphTitle = graphTitle.replace("crypto.", "[Crypto] ");
        }
        if (name.startsWith("bwLimiter.")) {
            graphTitle = graphTitle.replace("bwLimiter.", "[BWLimiter] ");
        }
        if (name.startsWith("codel.")) {
            graphTitle = graphTitle.replace("codel.", "[Router] CODEL.");
        }
        if (name.startsWith("stream.")) {
            graphTitle = graphTitle.replace("stream.", "[Stream] ");
        }
        if (name.equals("clock.skew")) {
            graphTitle = graphTitle.replace("clock.skew", "[Router] Clock Skew");
        }
        if (name.endsWith("InBps")) {
            graphTitle = graphTitle.replace("InBps", "Inbound B/s");
        }
        if (name.endsWith("OutBps")) {
            graphTitle = graphTitle.replace("OutBps", "Outbound B/s");
        }
        if (name.endsWith("Bps")) {
            graphTitle = graphTitle.replace("Bps", "B/s");
        }
        graphTitle = CSSHelper.StringFormatter.capitalizeWord(graphTitle);
        graphTitle = graphTitle
                .replace("[Tunnel] Tunnel", "[Tunnel]")
                .replace("Tunnel.participating", "[Transit]")
                .replace("[Tunnel] Participating Tunnels", "[Transit] Tunnel Count")
                .replace("Cpu", "CPU")
                .replace("CPULoad", "CPU Load")
                .replace(" Avg", " Average")
                .replace("[Tunnel]Build", "[Tunnel] Build");
        return graphTitle;
    }

    /**
     *  Per-language font family selection for graph rendering.
     *  Pure function — no side effects — so it is safe to call from any thread.
     *
     *  @param lang the active UI language code (e.g. "en", "zh", "jp", "ko")
     *  @return resolved mono / legend / title family names for the current platform
     */
    private static FontNames selectFontNames(String lang) {
        String mono = DEFAULT_FONT_NAME;
        String legend = DEFAULT_LEGEND_FONT_NAME;
        String title = DEFAULT_TITLE_FONT_NAME;
        if ("zh".equals(lang)) {
            if (FONTLIST.contains("Noto Sans SC")) {
                title = legend = "Noto Sans SC";
            } else if (FONTLIST.contains("Noto Sans CJK SC")) {
                title = legend = "Noto Sans CJK SC";
            } else if (FONTLIST.contains("Source Han Sans SC")) {
                title = legend = "Source Han Sans SC";
            } else {
                title = legend = "Dialog";
            }
            if (FONTLIST.contains("Noto Sans Mono SC")) {
                mono = "Noto Sans Mono SC";
            } else if (FONTLIST.contains("Noto Sans Mono CJK SC")) {
                mono = "Noto Sans Mono CJK SC";
            } else {
                mono = "Monospaced";
            }
        } else if ("jp".equals(lang)) {
            if (FONTLIST.contains("Noto Sans JP")) {
                title = legend = "Noto Sans JP";
            } else if (FONTLIST.contains("Noto Sans CJK JP")) {
                title = legend = "Noto Sans CJK JP";
            } else if (FONTLIST.contains("Source Han Sans JP")) {
                title = legend = "Noto Sans CJK JP";
            } else {
                title = legend = "Dialog";
            }
            if (FONTLIST.contains("Noto Sans Mono JP")) {
                mono = "Noto Sans Mono JP";
            } else if (FONTLIST.contains("Noto Sans Mono CJK JP")) {
                mono = "Noto Sans Mono CJK JP";
            } else {
                mono = "Monospaced";
            }
        } else if ("ko".equals(lang)) {
            if (FONTLIST.contains("Noto Sans KO")) {
                title = legend = "Noto Sans KO";
            } else if (FONTLIST.contains("Noto Sans CJK KO")) {
                title = legend = "Noto Sans CJK KO";
            } else if (FONTLIST.contains("Source Han Sans KO")) {
                title = legend = "Noto Sans CJK KO";
            } else {
                title = legend = "Dialog";
            }
            if (FONTLIST.contains("Noto Sans Mono KO")) {
                mono = "Noto Sans Mono KO";
            } else if (FONTLIST.contains("Noto Sans Mono CJK KO")) {
                mono = "Noto Sans Mono CJK KO";
            } else {
                mono = "Monospaced";
            }
        } else {
            // fall back to generic family names; the renderer selects the
            // concrete face per output format. Legend uses a sans-serif face,
            // while the unit/axis metric text stays monospaced for alignment.
            mono = "Monospaced";
            legend = "SansSerif";
            title = "SansSerif";
        }
        return new FontNames(mono, legend, title);
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
        final GraphListener lsnr2;
        final boolean useUtc;
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
            this.lsnr2 = b.lsnr2;
            this.useUtc = b.useUtc;
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
            GraphListener lsnr2;
            boolean useUtc;
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
            Builder lsnr2(GraphListener v) { lsnr2 = v; return this; }
            Builder useUtc(boolean v) { useUtc = v; return this; }
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
