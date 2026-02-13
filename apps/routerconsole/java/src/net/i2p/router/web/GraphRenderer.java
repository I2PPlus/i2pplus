package net.i2p.router.web;

import static net.i2p.router.web.GraphConstants.*;

import eu.bengreen.data.utility.LargestTriangleThreeBucketsTime;
import net.i2p.stat.RateConstants;
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
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
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
    private static final Color FRAME_COLOR_DARK = new Color(0 , 0, 0, 0);
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
    String DEFAULT_FONT_NAME = IS_WIN ? "Lucida Console" : "Monospaced";
    String DEFAULT_TITLE_FONT_NAME = "Dialog";
    String DEFAULT_LEGEND_FONT_NAME = "Dialog";
    private static final String PROP_FONT_MONO = "routerconsole.graphFont.unit";
    private static final String PROP_FONT_LEGEND = "routerconsole.graphFont.legend";
    private static final String PROP_FONT_TITLE = "routerconsole.graphFont.title";
    private static final int SIZE_MONO = 10;
    private static final int SIZE_LEGEND = 11;
    private static final int SIZE_TITLE = 12;
    private static final long[] RATES = RateConstants.BASIC_RATES;
    private static final Stroke GRID_STROKE = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {1, 1}, 0);

    // Static initialization for font data - these don't change per instance
    private static final GraphicsEnvironment GRAPHICS_ENV = GraphicsEnvironment.getLocalGraphicsEnvironment();
    private static final String[] SYS_FONTS = GRAPHICS_ENV.getAvailableFontFamilyNames();
    private static final List<String> FONT_LIST = Arrays.asList(SYS_FONTS);
    
    // ThreadLocal SimpleDateFormat cache - SimpleDateFormat is expensive to create and not thread-safe
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT_CACHE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("dd MMM HH:mm");
        }
    };

    public GraphRenderer(I2PAppContext ctx, GraphListener lsnr) {
        _log = ctx.logManager().getLog(GraphRenderer.class);
        _listener = lsnr;
        _context = ctx;
        ctx.statManager().createRateStat("graph.renderTime", "Time to render graphs (ms)", "Router", RATES);
    }

    /**
     * Render the stats as determined by the specified JRobin xml config,
     * but note that this doesn't work on stock jvms, as it requires
     * DOM level 3 load and store support.  Perhaps we can bundle that, or
     * specify who can get it from where, etc.
     *
     * @deprecated unused
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public static synchronized void render(I2PAppContext ctx, OutputStream out, String filename) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void render(OutputStream out) throws IOException { render(out, DEFAULT_X, DEFAULT_Y,
                                                                     false, false, false, false, -1, 0, false); }

    /**
     *  Single graph.
     *
     *  @param endp number of periods before now
     */
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle,
                       boolean showEvents, int periodCount, int endp, boolean showCredit) throws IOException {
        render(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, endp, showCredit, null, null);
    }

    /**
     *  Single or two-data-source graph.
     *
     *  @param lsnr2 2nd data source to plot on same graph, or null. Not recommended for events.
     *  @param titleOverride If non-null, overrides the title
     *  @since 0.9.6 consolidated from GraphGenerator for bw.combined
     */
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle,
                       boolean showEvents, int periodCount, int endp, boolean showCredit, GraphListener lsnr2,
                       String titleOverride) throws IOException {
        long begin = System.currentTimeMillis();
        // prevent NaNs if we are skewed ahead of system time
        long end = Math.min(_listener.now(), begin - 75*1000);
        long period = _listener.getRate().getPeriod();
        if (endp > 0) {end -= period * endp;}
        // Memory-aware period limiting to prevent OOM
        int maxPeriods = getMaxPeriodsForMemory();
        if (periodCount > maxPeriods) {periodCount = maxPeriods;}
        if (periodCount <= 0 || periodCount > _listener.getRows()) {periodCount = _listener.getRows();}
        long start = end - (period * periodCount);
        String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);

        try {
            RrdGraphDef def = new RrdGraphDef(start/1000, end/1000);
            if (periodCount >= 10080) {def.setDownsampler(new LargestTriangleThreeBucketsTime(100));} // 1 week
            else if (periodCount >= 2880) {def.setDownsampler(new LargestTriangleThreeBucketsTime(200));} // 2 days
            else if (periodCount >= 1440) {def.setDownsampler(new LargestTriangleThreeBucketsTime(500));} // 1 day
            // sidebar minigraph
            if ((width == 250 && height == 50 && hideTitle && hideLegend && hideGrid) ||
                (width == 2000 && height == 160 && hideTitle && hideLegend && hideGrid)) {
                def.setColor(ElementsNames.xaxis, TRANSPARENT);
                def.setColor(ElementsNames.yaxis, TRANSPARENT);
                def.setColor(ElementsNames.frame, TRANSPARENT);
            // Override defaults (dark themes)
            } else if (theme.equals("midnight")) {
                def.setColor(ElementsNames.font, FONT_COLOR_MIDNIGHT);
                def.setColor(ElementsNames.xaxis, AXIS_COLOR_MIDNIGHT);
                def.setColor(ElementsNames.yaxis, AXIS_COLOR_MIDNIGHT);
            } else if (theme.equals("dark")) {
                def.setColor(ElementsNames.font, FONT_COLOR_DARK);
                def.setColor(ElementsNames.xaxis, AXIS_COLOR_DARK);
                def.setColor(ElementsNames.yaxis, AXIS_COLOR_DARK);
            }
            if (theme.equals("midnight") || theme.equals("dark")) {
                    def.setColor(ElementsNames.back, BACK_COLOR_DARK);
                    def.setColor(ElementsNames.canvas,TRANSPARENT);
            } else {def.setColor(ElementsNames.back, BACK_COLOR);}
            if (theme.equals("midnight") || theme.equals("dark")) {
                def.setColor(ElementsNames.shadea, TRANSPARENT);
                def.setColor(ElementsNames.shadeb, TRANSPARENT);
                if (theme.equals("dark")) {
                  def.setColor(ElementsNames.grid, GRID_COLOR_DARK2);
                  def.setColor(ElementsNames.mgrid, MGRID_COLOR_DARK);
                } else if (theme.equals("midnight")) {
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

            if (width < 400 || height < 200 || periodCount < 120) {
                def.setColor(ElementsNames.grid, GRID_COLOR_HIDDEN);
                if (theme.equals("midnight")) {def.setColor(ElementsNames.mgrid, GRID_COLOR_MIDNIGHT);}
                else if (theme.equals("dark")) {def.setColor(ElementsNames.mgrid, GRID_COLOR_DARK);}
                else {def.setColor(ElementsNames.mgrid, GRID_COLOR);}
            }

            String lang = Messages.getLanguage(_context);
            if (lang == null) {lang = "en";}

            // improve text legibility
            int smallSize = SIZE_MONO;
            int legendSize = SIZE_LEGEND;
            int largeSize = SIZE_TITLE;
            if ("ar".equals(lang) || "ja".equals(lang) || ("zh".equals(lang) && !IS_WIN)) {
                smallSize += 2;
                legendSize += 2;
                largeSize += 3;
            } else if (width >= 800) {
                smallSize += 1;
                legendSize += 1;
                largeSize += 2;
            }

            /* CJK support */
            if ("zh".equals(Messages.getLanguage(_context))) {
                if (FONT_LIST.contains("Noto Sans SC")) {DEFAULT_TITLE_FONT_NAME = "Noto Sans SC";}
                else if (FONT_LIST.contains("Noto Sans CJK SC")) {DEFAULT_TITLE_FONT_NAME = "Noto Sans CJK SC";}
                else if (FONT_LIST.contains("Source Han Sans SC")) {DEFAULT_TITLE_FONT_NAME = "Source Han Sans SC";}
                else {DEFAULT_TITLE_FONT_NAME = "Dialog";}
                if (FONT_LIST.contains("Noto Sans Mono SC")) {
                    DEFAULT_FONT_NAME = "Noto Sans Mono SC";
                    DEFAULT_LEGEND_FONT_NAME = "Noto Sans Mono SC";
                } else if (FONT_LIST.contains("Noto Sans Mono CJK SC")) {
                    DEFAULT_FONT_NAME = "Noto Sans Mono CJK SC";
                    DEFAULT_LEGEND_FONT_NAME = "Noto Sans Mono CJK SC";
                } else {
                    DEFAULT_FONT_NAME = "Monospaced";
                    DEFAULT_LEGEND_FONT_NAME = "Monospaced";
                }
            } else if ("jp".equals(Messages.getLanguage(_context))) {
                if (FONT_LIST.contains("Noto Sans JP")) {DEFAULT_TITLE_FONT_NAME = "Noto Sans JP";}
                else if (FONT_LIST.contains("Noto Sans CJK JP")) {DEFAULT_TITLE_FONT_NAME = "Noto Sans CJK JP";}
                else if (FONT_LIST.contains("Source Han Sans JP")) {DEFAULT_TITLE_FONT_NAME = "Source Han Sans JP";}
                else {DEFAULT_TITLE_FONT_NAME = "Dialog";}
                if (FONT_LIST.contains("Noto Sans Mono JP")) {
                    DEFAULT_FONT_NAME = "Noto Sans Mono JP";
                    DEFAULT_LEGEND_FONT_NAME = "Noto Sans Mono JP";
                } else if (FONT_LIST.contains("Noto Sans Mono CJK JP")) {
                    DEFAULT_FONT_NAME = "Noto Sans Mono CJK JP";
                    DEFAULT_LEGEND_FONT_NAME = "Noto Sans Mono CJK JP";
                } else {
                    DEFAULT_FONT_NAME = "Monospaced";
                    DEFAULT_LEGEND_FONT_NAME = "Monospaced";
                }
            } else if ("ko".equals(Messages.getLanguage(_context))) {
                if (FONT_LIST.contains("Noto Sans KO")) {DEFAULT_TITLE_FONT_NAME = "Noto Sans KO";}
                else if (FONT_LIST.contains("Noto Sans CJK KO")) {DEFAULT_TITLE_FONT_NAME = "Noto Sans CJK KO";}
                else if (FONT_LIST.contains("Source Han Sans KO")) {DEFAULT_TITLE_FONT_NAME = "Source Han Sans KO";}
                else {DEFAULT_TITLE_FONT_NAME = "Dialog";}
                if (FONT_LIST.contains("Noto Sans Mono KO")) {
                    DEFAULT_FONT_NAME = "Noto Sans Mono KO";
                    DEFAULT_LEGEND_FONT_NAME = "Noto Sans Mono KO";
                } else if (FONT_LIST.contains("Noto Sans Mono CJK KO")) {
                    DEFAULT_FONT_NAME = "Noto Sans Mono CJK KO";
                    DEFAULT_LEGEND_FONT_NAME = "Noto Sans Mono CJK KO";
                } else {
                    DEFAULT_FONT_NAME = "Monospaced";
                    DEFAULT_LEGEND_FONT_NAME = "Monospaced";
                }
            } else {
                // let's handle the fonts in the svg file
                DEFAULT_FONT_NAME = "Monospaced";
                DEFAULT_LEGEND_FONT_NAME = "Monospaced";
                DEFAULT_TITLE_FONT_NAME = "SansSerif";
            }
            String ssmall = _context.getProperty(PROP_FONT_MONO, DEFAULT_FONT_NAME);
            String slegend = _context.getProperty(PROP_FONT_TITLE, DEFAULT_TITLE_FONT_NAME);
            String stitle = _context.getProperty(PROP_FONT_TITLE, DEFAULT_TITLE_FONT_NAME);
            Font small = new Font(ssmall, Font.PLAIN, smallSize);
            Font legnd = new Font(slegend, Font.PLAIN, legendSize);
            Font large = new Font(stitle, Font.PLAIN, largeSize);
            def.setFont(RrdGraphDef.FONTTAG_DEFAULT, small); // DEFAULT is unused since we set all the others
            def.setFont(RrdGraphDef.FONTTAG_AXIS, small); // AXIS is unused, we do not set any axis labels
            // rrd4j sets UNIT = AXIS in RrdGraphConstants, may be bug, maybe not, no use setting them different here
            def.setFont(RrdGraphDef.FONTTAG_UNIT, small);
            def.setFont(RrdGraphDef.FONTTAG_LEGEND, legnd);
            def.setFont(RrdGraphDef.FONTTAG_TITLE, large);
            def.setMinValue(0d);

            String name = _listener.getRate().getRateStat().getName();
            String graphTitle = name;
            if (name.startsWith("tunnel.participatingTunnels")) {graphTitle = graphTitle.replaceAll("tunnel.participatingTunnels", "[Transit] Tunnel Count");}
            if (name.startsWith("tunnel.participatingMessage")) {graphTitle = graphTitle.replaceAll("tunnel.participatingMessage", "[Transit] Message");}
            else if (name.startsWith("tunnel.participating")) {graphTitle = graphTitle.replaceAll("tunnel.participating", "[Transit]");}
            else if (name.startsWith("Tunnel.participating")) {graphTitle = graphTitle.replaceAll("Tunnel.participating", "[Transit]");}
            if (name.startsWith("router.")) {graphTitle = graphTitle.replaceAll("router.", "[Router] ");}
            if (name.startsWith("bw.")) {graphTitle = graphTitle.replaceAll("bw.", "[Router] ");}
            if (name.startsWith("Bandwidth usage")) {graphTitle = graphTitle.replaceAll("Bandwidth usage", "[Router] Bandwidth Usage");}
            if (name.startsWith("tunnel.buildRatio.exploratory.")) {graphTitle = graphTitle.replaceAll("tunnel.buildRatio.exploratory.", "[Exploratory] Build Ratio");}
            if (name.startsWith("tunnel.buildExploratory")) {graphTitle = graphTitle.replaceAll("tunnel.buildExploratory", "[Exploratory] Build");}
            if (name.startsWith("tunnel.buildClient")) {graphTitle = graphTitle.replaceAll("tunnel.buildClient", "[Tunnel] BuildClient");}
            else if (name.startsWith("tunnel.build")) {graphTitle = graphTitle.replaceAll("tunnel.build", "[Tunnel] Build");}
            else if (name.startsWith("tunnel.")) {graphTitle = graphTitle.replaceAll("tunnel.", "[Tunnel] ");}
            if (name.contains("MessageCountAvg")) {graphTitle = graphTitle.replaceAll("MessageCountAvg", "Messsage Count Average");}
            if (name.startsWith("netDb.")) {graphTitle = graphTitle.replaceAll("netDb.", "[NetDb] ");}
            if (name.startsWith("jobQueue.")) {graphTitle = graphTitle.replaceAll("jobQueue.", "[JobQueue] ");}
            if (name.startsWith("udp.")) {graphTitle = graphTitle.replaceAll("udp.", "[UDP] ");}
            if (name.startsWith("ntcp.")) {graphTitle = graphTitle.replaceAll("ntcp.", "[NTCP] ");}
            if (name.startsWith("transport.")) {graphTitle = graphTitle.replaceAll("transport.", "[Transport] ");}
            if (name.startsWith("client.")) {graphTitle = graphTitle.replaceAll("client.", "[Client] ");}
            if (name.startsWith("peer.")) {graphTitle = graphTitle.replaceAll("peer.", "[Peer] ");}
            if (name.startsWith("prng.")) {graphTitle = graphTitle.replaceAll("prng.", "[Crypto] pnrg.");}
            if (name.startsWith("crypto.")) {graphTitle = graphTitle.replaceAll("crypto.", "[Crypto] ");}
            if (name.startsWith("bwLimiter.")) {graphTitle = graphTitle.replaceAll("bwLimiter.", "[BWLimiter] ");}
            if (name.startsWith("pbq.")) {graphTitle = graphTitle.replaceAll("pbq.", "[Router] PBQ.");}
            if (name.startsWith("codel.")) {graphTitle = graphTitle.replaceAll("codel.", "[Router] CODEL.");}
            if (name.startsWith("SDSCache.")) {graphTitle = graphTitle.replaceAll("SDSCache.", "[Router] SDSCache.");}
            if (name.startsWith("byteCache.memory.")) {graphTitle = graphTitle.replaceAll("byteCache.memory.", "[Router] ByteCache:");}
            if (name.startsWith("stream.")) {graphTitle = graphTitle.replaceAll("stream.", "[Stream] ");}
            if (name.equals("clock.skew")) {graphTitle = graphTitle.replaceAll("clock.skew", "[Router] Clock Skew");}
            if (name.endsWith("InBps")) {graphTitle = graphTitle.replaceAll("InBps", "Inbound B/s");}
            if (name.endsWith("OutBps")) {graphTitle = graphTitle.replaceAll("OutBps", "Outbound B/s");}
            if (name.endsWith("Bps")) {graphTitle = graphTitle.replaceAll("Bps", "B/s");}

            boolean singleDecimalPlace = true;
            boolean noDecimalPlace = false;
            graphTitle = CSSHelper.StringFormatter.capitalizeWord(graphTitle);
            graphTitle = graphTitle.replace("[Tunnel] Tunnel", "[Tunnel]")
                                   .replace("Tunnel.participating", "[Transit]")
                                   .replace("[Tunnel] Participating Tunnels", "[Transit] Tunnel Count")
                                   .replace("Cpu", "CPU")
                                   .replace("CPULoad", "CPU Load")
                                   .replace(" Avg", " Average")
                                   .replace("[Tunnel]Build", "[Tunnel] Build");

            // heuristic to set K=1024
            if ((name.toLowerCase().indexOf("size") >= 0 || name.toLowerCase().indexOf("memory") >= 0 ||
                name.toLowerCase().indexOf("b/s") >= 0 || name.toLowerCase().indexOf("bps") >= 0 ||
                name.toLowerCase().indexOf("bandwidth") >= 0 || name.toLowerCase().indexOf("bytecache") >= 0)
                && !showEvents) {
                def.setBase(1024);
                singleDecimalPlace = false;
            }

            if (titleOverride != null) {def.setTitle(titleOverride);}
            else if (!hideTitle) {
                String title;
                String p;

                // we want the formatting and translation of formatDuration2(), except not zh, and not the &nbsp;
                if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {p = DataHelper.formatDuration(period);}
                else {p = DataHelper.formatDuration2(period).replace("&nbsp;", " ");}
                if (showEvents) {title = graphTitle + ' ' + _t("events in {0}", p);}
                title = graphTitle.replaceAll("(?<=[a-z])([A-Z])", " $1");
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
            String path = _listener.getData().getPath();
            String dsNames[] = _listener.getData().getDsNames();
            String plotName;
            String descr;
            if (showEvents) {
                plotName = dsNames[1]; // include the average event count on the plot
                descr = _t("Events per period");
            } else {
                plotName = dsNames[0]; // include the average value
                // The descriptions are not tagged in the createRateStat calls (there are over 500 of them)
                // but the descriptions for the default graphs are tagged in Strings.java
                descr = _t(_listener.getRate().getRateStat().getDescription());
            }
            def.datasource(plotName, path, plotName, GraphListener.CF, _listener.getBackendFactory());
            if (width == 2000 && height == 160 && hideTitle && hideLegend && hideGrid) {def.area(plotName, AREA_COLOR_NEUTRAL);}
            else if (theme.equals("dark")) {
                if (descr.length() > 0) {def.area(plotName, AREA_COLOR_DARK, descr + "\\l");}
                else {def.area(plotName, AREA_COLOR_DARK);}
            } else if (theme.equals("midnight")) {
                if (descr.length() > 0) {def.area(plotName, AREA_COLOR_MIDNIGHT, descr + "\\l");}
                else {def.area(plotName, AREA_COLOR_MIDNIGHT);}
            } else {
                if (descr.length() > 0) {def.area(plotName, AREA_COLOR, descr + "\\l");}
                else {def.area(plotName, AREA_COLOR);}
            }

            String numberFormat = noDecimalPlace ? "%.0f%s" : singleDecimalPlace ? "%.1f%s" : "%.2f%s";

            if (!hideLegend) {
                Variable var = new Variable.MIN();
                def.datasource("min", plotName, var);
                def.gprint("min", " " + _t("Min") + ": " + numberFormat);
                var = new Variable.MAX();
                def.datasource("max", plotName, var);
                def.gprint("max", " " + _t("Max") + ": " + numberFormat);
                var = new Variable.AVERAGE();
                def.datasource("avg", plotName, var);
                def.gprint("avg", " " + _t("Avg") + ": " + numberFormat);
                var = new Variable.LAST();
                def.datasource("last", plotName, var);
                def.gprint("last", " " + _t("Now") + ": " + numberFormat + "\\l");
            }
            String plotName2 = null;
            if (lsnr2 != null) {
                String dsNames2[] = lsnr2.getData().getDsNames();
                plotName2 = dsNames2[0];
                String path2 = lsnr2.getData().getPath();
                String descr2 = _t(lsnr2.getRate().getRateStat().getDescription());
                def.datasource(plotName2, path2, plotName2, GraphListener.CF, lsnr2.getBackendFactory());
                int linewidth = 2;
                // sidebar graph
                if (width == 250 && height == 50 && hideTitle && hideLegend && hideGrid) {linewidth = 3;}
                else if (periodCount >= 720 || (periodCount >= 480 && width <= 600)) {linewidth = 1;}
                if (theme.equals("midnight")) {def.line(plotName2, LINE_COLOR_MIDNIGHT, descr2 + "\\l", linewidth);}
                else if (theme.equals("dark")) {def.line(plotName2, LINE_COLOR_DARK, descr2 + "\\l", linewidth);}
                else {def.line(plotName2, LINE_COLOR, descr2 + "\\l", linewidth);}

                if (!hideLegend) {
                    Variable var = new Variable.MAX();
                    def.datasource("max2", plotName2, var);
                    def.gprint("max2", " " + _t("Max") + ": " + numberFormat + " ");
                    var = new Variable.MIN();
                    def.datasource("min2", plotName, var);
                    def.gprint("min2", " " + _t("Min") + ": " + numberFormat + " ");
                    var = new Variable.AVERAGE();
                    def.datasource("avg2", plotName2, var);
                    def.gprint("avg2", " " + _t("Avg") + ": " + numberFormat + " ");
                    var = new Variable.LAST();
                    def.datasource("last2", plotName2, var);
                    def.gprint("last2", " " + _t("Now") + ": " + numberFormat + "\\l");
                }
            }

            // Use cached SimpleDateFormat to avoid expensive object creation
            SimpleDateFormat sdf = DATE_FORMAT_CACHE.get();
            int count = 0;
            Color RESTART_COLOR = theme.equals("midnight") || theme.equals("dark") ? RESTART_BAR_COLOR_DARK : RESTART_BAR_COLOR;

            if (!hideLegend) {
                // '07 Jul 21:09' with month name in the system locale
                // TODO: Fix Arabic time display
                Map<Long, String> events = ((RouterContext)_context).router().eventLog().getEvents(EventLog.STARTED, start);
                String prev = null;
                String now = null;
                for (Map.Entry<Long, String> event : events.entrySet()) {
                    long started = event.getKey().longValue();
                    if (started >= end) {break;}
                    String legend;
                    if (count < 1) {legend = _t("Router restarted") + "\\l";}
                    else {legend = null;}
                    def.vrule(started / 1000, RESTART_COLOR, legend, 1.0f);
                    count ++;
                }
                def.comment(sdf.format(new Date(start)) + " — " + sdf.format(new Date(end)) + " UTC\\r");
            }
            if (!showCredit) {def.setShowSignature(false);}
            else if (hideLegend) {
                if (height > 65) {def.setSignature("    " + sdf.format(new Date(end)) + " UTC");}
                else {def.setSignature(sdf.format(new Date(end)) + " UTC");}
            }
            if (hideLegend) {def.setNoLegend(true);}
            if (hideGrid) {
                def.setDrawXGrid(false);
                def.setDrawYGrid(false);
            }
            def.setAntiAliasing(false);
            def.setTextAntiAliasing(true);
            def.setGridStroke(GRID_STROKE);
            def.setWidth(width);
            def.setHeight(height);
            def.setImageFormat("PNG");
            def.setLazy(true);
            def.setPoolUsed(true);
            def.setAltYMrtg(true);
            if (width < 400 || height < 200) {
                def.setNoMinorGrid(true);
                def.setAltYMrtg(false);
            }

            // render unembellished graph if we're on the sidebar or snark
            if ((width == 250 && height == 50 && hideTitle && hideLegend && hideGrid) ||
                (width == 2000 && height == 160 && hideTitle && hideLegend && hideGrid)) {
                def.setOnlyGraph(true);
                def.setColor(RrdGraphDef.COLOR_CANVAS, TRANSPARENT);
                def.setColor(RrdGraphDef.COLOR_BACK, TRANSPARENT);
            }
            // Create graph - only instantiate once to avoid wasting memory
            final RrdGraph graph;
            try {
                graph = new RrdGraph(def, new SVGImageWorker(width + 8, height));
            } catch (NullPointerException npe) {
                _log.error("Error rendering graph", npe);
                GraphGenerator.setDisabled(_context);
                throw new IOException("Error rendering - disabling graph generation.");
            } catch (Error e) {
                // Docker InternalError see Gitlab #383
                _log.error("Error rendering graph", e);
                GraphGenerator.setDisabled(_context);
                throw new IOException("Error rendering - disabling graph generation.");
            }
            out.write(graph.getRrdGraphInfo().getBytes());
            _context.statManager().addRateData("graph.renderTime", System.currentTimeMillis() - begin);
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getLocalizedMessage());
        } catch (IOException ioe) {
            // typically org.mortbay.jetty.EofException extends java.io.EOFException
            if (_log.shouldWarn()) {_log.warn("Error rendering", ioe);}
            throw ioe;
        } catch (OutOfMemoryError oom) {
            _log.error("Error rendering", oom);
            throw new IOException("Error plotting: " + oom.getLocalizedMessage());
        }
    }

    /** translate a string */
    private String _t(String s) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {return s;}
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     */
    private String _t(String s, String o) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {return s.replace("{0}", o);}
        return Messages.getString(s, o, _context);
    }

    /**
     *  Get the maximum number of periods to graph based on available memory.
     *  This prevents OOM errors when rendering large graphs on memory-constrained systems.
     *
     *  @return max periods: 2 days for <2GB, 1 week for 2-4GB, unlimited for 4GB+
     *  @since 0.9.65
     */
    private static int getMaxPeriodsForMemory() {
        long maxMem = SystemVersion.getMaxMemory();
        if (maxMem < 2048*1024*1024L) {
            return 2 * 24 * 60; // 2 days
        } else if (maxMem < 4096*1024*1024L) {
            return 7 * 24 * 60; // 1 week
        } else {
            return Integer.MAX_VALUE; // No limit for systems with 4GB+
        }
    }

}