package net.i2p.router.web;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import static net.i2p.router.web.GraphConstants.*;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

import org.rrd4j.ConsolFun;
import org.rrd4j.core.RrdException;
import org.rrd4j.data.Variable;
import org.rrd4j.graph.ElementsNames;
import org.rrd4j.graph.RrdGraph;
import org.rrd4j.graph.RrdGraphDef;

import net.i2p.router.web.helpers.GraphHelper;


// enumerate system fonts
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 *  Generate the RRD graph png images,
 *  including the combined rate graph.
 *
 *  @since 0.6.1.13
 */
class SummaryRenderer {
    private final Log _log;
    private final SummaryListener _listener;
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
    private static final Color GRID_COLOR = new Color(100, 100, 100, 75);
    private static final Color GRID_COLOR_DARK = new Color(244, 244, 190, 50);
    private static final Color GRID_COLOR_DARK2 = new Color(244, 244, 190, 30);
    private static final Color GRID_COLOR_MIDNIGHT = new Color(201, 206, 255, 50);
    private static final Color GRID_COLOR_HIDDEN = new Color(0, 0, 0, 0);
    private static final Color MGRID_COLOR = new Color(255, 91, 91, 110);
    private static final Color MGRID_COLOR_DARK = new Color(200, 200, 0, 50);
    private static final Color MGRID_COLOR_MIDNIGHT = new Color(240, 32, 192, 110);
    private static final Color MGRID_COLOR_DARK_HIDPI = new Color(255, 91, 91, 160);
    private static final Color FONT_COLOR = new Color(51, 51, 63);
    private static final Color FONT_COLOR_DARK = new Color(244, 244, 190);
    private static final Color FONT_COLOR_MIDNIGHT = new Color(201, 206, 255);
    private static final Color AXIS_COLOR_DARK = new Color(244, 244, 190, 200);
    private static final Color AXIS_COLOR_MIDNIGHT = new Color(201, 206, 255, 200);
    //private static final Color FRAME_COLOR = new Color(51, 51, 63);
    private static final Color FRAME_COLOR = new Color(0, 0, 0, 0);
    //private static final Color FRAME_COLOR_DARK = new Color(16, 16, 16);
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
    //private static final Color RESTART_BAR_COLOR_DARK = new Color(160, 160, 0, 220);
    private static final Color RESTART_BAR_COLOR_DARK = new Color(220, 16, 48, 220);
/**
    private static final Color DEFAULT_XAXIS_COLOR = new Color(0 , 0, 0);
    private static final Color DEFAULT_YAXIS_COLOR = new Color(0 , 0, 0);
    private static final Color DEFAULT_XAXIS_COLOR_DARK = new Color(0 , 0, 0);
    private static final Color DEFAULT_YAXIS_COLOR_DARK = new Color(0 , 0, 0);
    private static final Color DEFAULT_XAXIS_COLOR_MIDNIGHT = new Color(0 , 0, 0);
    private static final Color DEFAULT_YAXIS_COLOR_MIDNIGHT = new Color(0 , 0, 0);
**/

    private static final boolean IS_WIN = SystemVersion.isWindows();
//    private static final String DEFAULT_FONT_NAME = System.getProperty("os.name").toLowerCase().contains("windows") ?
//            "Lucida Console" : "Monospaced";
    String DEFAULT_FONT_NAME = IS_WIN ? "Lucida Console" : "Monospaced";
    String DEFAULT_TITLE_FONT_NAME = "Dialog";
    String DEFAULT_LEGEND_FONT_NAME = "Dialog";
    private static final String PROP_FONT_MONO = "routerconsole.graphFont.unit";
    private static final String PROP_FONT_LEGEND = "routerconsole.graphFont.legend";
    private static final String PROP_FONT_TITLE = "routerconsole.graphFont.title";
    private static final int SIZE_MONO = 10;
    private static final int SIZE_LEGEND = 11;
    private static final int SIZE_TITLE = 12;
    private static final long[] RATES = new long[] { 60*1000, 60*60*1000 };
    // dotted line
//    private static final Stroke GRID_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[] {1, 1}, 0);
    private static final Stroke GRID_STROKE = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {1, 1}, 0);

    GraphicsEnvironment e = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] sysfonts = e.getAvailableFontFamilyNames();
        List<String> fontlist = Arrays.asList(sysfonts);

    public SummaryRenderer(I2PAppContext ctx, SummaryListener lsnr) {
        _log = ctx.logManager().getLog(SummaryRenderer.class);
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
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                       boolean hideTitle, boolean showEvents, int periodCount,
                       int endp, boolean showCredit) throws IOException {
        render(out, width, height, hideLegend, hideGrid, hideTitle,
               showEvents, periodCount, endp, showCredit, null, null);
    }

    /**
     *  Single or two-data-source graph.
     *
     *  @param lsnr2 2nd data source to plot on same graph, or null. Not recommended for events.
     *  @param titleOverride If non-null, overrides the title
     *  @since 0.9.6 consolidated from StatSummarizer for bw.combined
     */
    public void render(OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid,
                       boolean hideTitle, boolean showEvents, int periodCount,
                       int endp, boolean showCredit, SummaryListener lsnr2, String titleOverride) throws IOException {
        long begin = System.currentTimeMillis();
        // prevent NaNs if we are skewed ahead of system time
        long end = Math.min(_listener.now(), begin - 75*1000);
        long period = _listener.getRate().getPeriod();
        if (endp > 0)
            end -= period * endp;
        if (periodCount <= 0 || periodCount > _listener.getRows())
            periodCount = _listener.getRows();
        long start = end - (period * periodCount);
        ImageOutputStream ios = null;
        String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);

        try {
            RrdGraphDef def = new RrdGraphDef(start/1000, end/1000);
            boolean hiDPI = _context.getBooleanProperty("routerconsole.graphHiDpi");
            if ((width == 250 && height == 50 && hideTitle && hideLegend && hideGrid) ||
                (width == 2000 && height == 160 && hideTitle && hideLegend && hideGrid)) {
                def.setColor(ElementsNames.xaxis, TRANSPARENT);
                def.setColor(ElementsNames.yaxis, TRANSPARENT);
                def.setColor(ElementsNames.frame, TRANSPARENT);
            // Override defaults (dark themes)
            } else if (theme.equals("midnight")) {
                def.setColor(ElementsNames.font,    FONT_COLOR_MIDNIGHT);
                def.setColor(ElementsNames.xaxis,   AXIS_COLOR_MIDNIGHT);
                def.setColor(ElementsNames.yaxis,   AXIS_COLOR_MIDNIGHT);
            } else if (theme.equals("dark")) {
                def.setColor(ElementsNames.font,    FONT_COLOR_DARK);
                def.setColor(ElementsNames.xaxis,   AXIS_COLOR_DARK);
                def.setColor(ElementsNames.yaxis,   AXIS_COLOR_DARK);
            }
            if (theme.equals("midnight") || theme.equals("dark")) {
                if (hideLegend) {
                    def.setColor(ElementsNames.back,   TRANSPARENT);
                    def.setColor(ElementsNames.canvas, TRANSPARENT);
                } else {
                    def.setColor(ElementsNames.back,   BACK_COLOR_DARK);
                    def.setColor(ElementsNames.canvas, TRANSPARENT);
                }
            } else {
                def.setColor(ElementsNames.back,   BACK_COLOR);
            }
            if (theme.equals("midnight") || theme.equals("dark")) {
                def.setColor(ElementsNames.shadea, TRANSPARENT);
                def.setColor(ElementsNames.shadeb, TRANSPARENT);
                if (theme.equals("dark")) {
                  def.setColor(ElementsNames.grid,   GRID_COLOR_DARK2);
                  def.setColor(ElementsNames.mgrid,  MGRID_COLOR_DARK);
                } else if (theme.equals("midnight")) {
                    def.setColor(ElementsNames.grid, GRID_COLOR_MIDNIGHT);
                    def.setColor(ElementsNames.mgrid, MGRID_COLOR_MIDNIGHT);
                }
                def.setColor(ElementsNames.frame,  FRAME_COLOR_DARK);
                def.setColor(ElementsNames.arrow,  ARROW_COLOR_DARK);
            } else {
                // Override defaults (light themes)
                def.setColor(ElementsNames.shadea, SHADEA_COLOR);
                def.setColor(ElementsNames.shadeb, SHADEB_COLOR);
                def.setColor(ElementsNames.grid,   GRID_COLOR);
                def.setColor(ElementsNames.mgrid,  MGRID_COLOR);
                def.setColor(ElementsNames.font,   FONT_COLOR);
                def.setColor(ElementsNames.frame,  FRAME_COLOR);
            }

            if (width < 400 || height < 200 || periodCount < 120) {
                def.setColor(ElementsNames.grid, GRID_COLOR_HIDDEN);
                if (theme.equals("midnight"))
                  def.setColor(ElementsNames.mgrid, GRID_COLOR_MIDNIGHT);
                else if (theme.equals("dark"))
                  def.setColor(ElementsNames.mgrid, GRID_COLOR_DARK);
                else
                  def.setColor(ElementsNames.mgrid, GRID_COLOR);
            }

            // improve text legibility
            String lang = Messages.getLanguage(_context);
            if (lang == null)
                lang = "en";

            int smallSize = SIZE_MONO;
            int legendSize = SIZE_LEGEND;
            int largeSize = SIZE_TITLE;
            if ("ar".equals(lang) || "ja".equals(lang) || ("zh".equals(lang) && !IS_WIN)) {
                smallSize += 2;
                legendSize += 2;
                largeSize += 3;
            }
            if (hiDPI) {
                def.setTimeAxis(RrdGraphDef.MINUTE, 15, RrdGraphDef.HOUR, 1, RrdGraphDef.HOUR, 1, 0, "%H:00");
                if (width >= 1600) {
                    smallSize = 18;
                    legendSize = 18;
                    largeSize = 20;
                } else {
                    smallSize = 16;
                    legendSize = 16;
                    largeSize = 18;
                }
            } else if (width >= 800) {
                    smallSize += 1;
                    legendSize += 1;
                    largeSize += 1;
            }

/**
    private static String DEFAULT_FONT_NAME = IS_WIN ? "Lucida Console" : "Monospaced";
    private static String DEFAULT_TITLE_FONT_NAME = "Dialog";
    private static String DEFAULT_LEGEND_FONT_NAME = "Dialog";
**/
            if  (fontlist.contains("Droid Sans")) {
                DEFAULT_TITLE_FONT_NAME = "Droid Sans";
            } else if (fontlist.contains("Open Sans")) {
                DEFAULT_TITLE_FONT_NAME = "Open Sans";
            } else if (fontlist.contains("Noto Sans")) {
                DEFAULT_TITLE_FONT_NAME = "Noto Sans";
            } else if (fontlist.contains("Ubuntu")) {
                DEFAULT_TITLE_FONT_NAME = "Ubuntu";
            } else if (fontlist.contains("Segoe UI")) {
                DEFAULT_TITLE_FONT_NAME = "Segoe UI";
            } else if (fontlist.contains("Bitstream Vera Sans")) {
                DEFAULT_TITLE_FONT_NAME = "Bitstream Vera Sans";
            } else if (fontlist.contains("DejaVu Sans")) {
                DEFAULT_TITLE_FONT_NAME = "DejaVu Sans";
            } else if (fontlist.contains("Verdana")) {
                DEFAULT_TITLE_FONT_NAME = "Verdana";
            } else if (fontlist.contains("Lucida Grande")) {
                DEFAULT_TITLE_FONT_NAME = "Lucida Grande";
            } else if (fontlist.contains("Helvetica")) {
                DEFAULT_TITLE_FONT_NAME = "Helvetica";
            } else {
                DEFAULT_TITLE_FONT_NAME = "Dialog";
            }

            if  (fontlist.contains("Droid Sans Mono")) {
                DEFAULT_FONT_NAME = "Droid Sans Mono";
                DEFAULT_LEGEND_FONT_NAME = "Droid Sans Mono";
            } else if (fontlist.contains("Noto Mono")) {
                DEFAULT_FONT_NAME = "Noto Mono";
                DEFAULT_LEGEND_FONT_NAME = "Noto Mono";
            } else if (fontlist.contains("DejaVu Sans Mono")) {
                DEFAULT_FONT_NAME = "DejaVu Sans Mono";
                DEFAULT_LEGEND_FONT_NAME = "DejaVu Sans Mono";
            } else if (fontlist.contains("Lucida Console")) {
                DEFAULT_FONT_NAME = "Lucida Console";
                DEFAULT_LEGEND_FONT_NAME = "Lucida Console";
            } else {
                DEFAULT_FONT_NAME = "Monospaced";
            }


            String ssmall = _context.getProperty(PROP_FONT_MONO, DEFAULT_FONT_NAME);
//            String slegend = _context.getProperty(PROP_FONT_LEGEND, DEFAULT_LEGEND_FONT_NAME);
            String slegend = _context.getProperty(PROP_FONT_TITLE, DEFAULT_TITLE_FONT_NAME);
            String stitle = _context.getProperty(PROP_FONT_TITLE, DEFAULT_TITLE_FONT_NAME);
            Font small = new Font(ssmall, Font.PLAIN, smallSize);
            Font legnd = new Font(slegend, Font.PLAIN, legendSize);
            Font large = new Font(stitle, Font.PLAIN, largeSize);
            // DEFAULT is unused since we set all the others
            def.setFont(RrdGraphDef.FONTTAG_DEFAULT, small);
            // AXIS is unused, we do not set any axis labels
            def.setFont(RrdGraphDef.FONTTAG_AXIS, small);
            // rrd4j sets UNIT = AXIS in RrdGraphConstants, may be bug, maybe not, no use setting them different here
            def.setFont(RrdGraphDef.FONTTAG_UNIT, small);
            def.setFont(RrdGraphDef.FONTTAG_LEGEND, legnd);
            def.setFont(RrdGraphDef.FONTTAG_TITLE, large);


            def.setMinValue(0d);
            String name = _listener.getRate().getRateStat().getName();

            if (name.startsWith("tunnel.participatingTunnels"))
                name = name.replace("tunnel.participatingTunnels", "[Participating] Tunnel Count");
            if (name.startsWith("tunnel.participatingMessage"))
                name = name.replace("tunnel.participatingMessage", "[Participating] Message");
            else if (name.startsWith("tunnel.participating"))
                name = name.replace("tunnel.participating", "[Participating]");
            if (name.startsWith("router."))
                name = name.replace("router.", "[Router] ");
            if (name.startsWith("bw."))
                name = name.replace("bw.", "[Router] ");
            if (name.startsWith("Bandwidth usage"))
                name = name.replace("Bandwidth usage", "[Router] Bandwidth Usage");
            if (name.startsWith("tunnel.buildRatio.exploratory."))
                name = name.replace("tunnel.buildRatio.exploratory.", "[Exploratory] BuildRatio");
            if (name.startsWith("tunnel.buildExploratory"))
                name = name.replace("tunnel.buildExploratory", "[Exploratory] Build");
            if (name.startsWith("tunnel.buildClient"))
                name = name.replace("tunnel.buildClient", "[Tunnel] BuildClient");
            else if (name.startsWith("tunnel.build"))
                name = name.replace("tunnel.build", "[Tunnel] Build");
            else if (name.startsWith("tunnel."))
                name = name.replace("tunnel.", "[Tunnel] ");
            if (name.contains("MessageCountAvg"))
                name = name.replace("MessageCountAvg", "MsgCountAvg");
            if (name.startsWith("netDb."))
                name = name.replace("netDb.", "[NetDb] ");
            if (name.startsWith("jobQueue."))
                name = name.replace("jobQueue.", "[JobQueue] ");
            if (name.startsWith("udp."))
                name = name.replace("udp.", "[UDP] ");
            if (name.startsWith("ntcp."))
                name = name.replace("ntcp.", "[NTCP] ");
            if (name.startsWith("transport."))
                name = name.replace("transport.", "[Transport] ");
            if (name.startsWith("client."))
                name = name.replace("client.", "[Client] ");
            if (name.startsWith("peer."))
                name = name.replace("peer.", "[Peer] ");
            if (name.startsWith("prng."))
                name = name.replace("prng.", "[Crypto] pnrg.");
            if (name.startsWith("crypto."))
                name = name.replace("crypto.", "[Crypto] ");
            if (name.startsWith("bwLimiter."))
                name = name.replace("bwLimiter.", "[BWLimiter] ");
            if (name.startsWith("pbq."))
                name = name.replace("pbq.", "[Router] pbq.");
            if (name.startsWith("codel."))
                name = name.replace("codel.", "[Router] codel.");
            if (name.startsWith("SDSCache."))
                name = name.replace("SDSCache.", "[Router] SDSCache.");
            if (name.startsWith("byteCache.memory."))
                name = name.replace("byteCache.memory.", "[Router] ByteCache:");
            if (name.startsWith("stream."))
                name = name.replace("stream.", "[Stream] ");
            if (name.equals("clock.skew"))
                name = name.replace("clock.skew", "[Router] Clock Skew");
            if (name.endsWith("InBps"))
                name = name.replace("InBps", "Inbound B/s");
            if (name.endsWith("OutBps"))
                name = name.replace("OutBps", "Outbound B/s");
            name = CSSHelper.StringFormatter.capitalizeWord(name);
            // heuristic to set K=1024
            //if ((name.startsWith("bw.") || name.indexOf("Size") >= 0 || name.indexOf("Bps") >= 0 || name.indexOf("memory") >= 0)

            if ((name.indexOf("Size") >= 0 || name.indexOf("memory") >= 0) || name.contains("B/s") || name.contains("Bandwidth")
                && !showEvents)
                def.setBase(1024);
            if (titleOverride != null) {
                def.setTitle(titleOverride);
            } else if (!hideTitle) {
                String title;
                String p;

                // we want the formatting and translation of formatDuration2(), except not zh, and not the &nbsp;
                if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
                    p = DataHelper.formatDuration(period);
                else
                    p = DataHelper.formatDuration2(period).replace("&nbsp;", " ");
                if (showEvents)
                    title = name + ' ' + _t("events in {0}", p);
                else
//                    title = name + ' ' + _t("{0} avg", p);
                    // hide the '60 sec avg' since everything is now averaged to 60s
                    title = name.replaceAll("(?<=[a-z])([A-Z])", " $1");
                    title = title.substring(0, 1).toUpperCase() + title.substring(1);
                def.setTitle(title);
            }
            String path = _listener.getData().getPath();
            String dsNames[] = _listener.getData().getDsNames();
            String plotName;
            String descr;
            if (showEvents) {
                // include the average event count on the plot
                plotName = dsNames[1];
                descr = _t("Events per period");
            } else {
                // include the average value
                plotName = dsNames[0];
                // The descriptions are not tagged in the createRateStat calls
                // (there are over 500 of them)
                // but the descriptions for the default graphs are tagged in
                // Strings.java
                descr = _t(_listener.getRate().getRateStat().getDescription());
            }

            //long started = ((RouterContext)_context).router().getWhenStarted();
            //if (started > start && started < end)
            //    def.vrule(started / 1000, RESTART_BAR_COLOR, _t("Restart"), 4.0f);

            def.datasource(plotName, path, plotName, SummaryListener.CF, _listener.getBackendFactory());
            if (width == 2000 && height == 160 && hideTitle && hideLegend && hideGrid) {
                def.area(plotName, AREA_COLOR_NEUTRAL);
            } else if (theme.equals("dark")) {
                if (descr.length() > 0) {
                    def.area(plotName, AREA_COLOR_DARK, descr + "\\l");
                } else {
                    def.area(plotName, AREA_COLOR_DARK);
                }
            } else if (theme.equals("midnight")) {
                if (descr.length() > 0) {
                    def.area(plotName, AREA_COLOR_MIDNIGHT, descr + "\\l");
                } else {
                    def.area(plotName, AREA_COLOR_MIDNIGHT);
                }
            } else {
                if (descr.length() > 0) {
                    def.area(plotName, AREA_COLOR, descr + "\\l");
                } else {
                    def.area(plotName, AREA_COLOR);
                }
            }
            if (!hideLegend) {
                Variable var = new Variable.MAX();
                def.datasource("max", plotName, var);
                def.gprint("max", " " + _t("Max") + ": %.2f%S");
                var = new Variable.MIN();
                def.datasource("min", plotName, var);
                def.gprint("min", "  " + _t("Min") + ": %.2f%S");
                var = new Variable.AVERAGE();
                def.datasource("avg", plotName, var);
                def.gprint("avg", _t("Avg") + ": %.2f%s");
                var = new Variable.LAST();
                def.datasource("last", plotName, var);
                def.gprint("last", "  " + _t("Now") + ": %.2f%S\\l");
            }
            String plotName2 = null;
            if (lsnr2 != null) {
                String dsNames2[] = lsnr2.getData().getDsNames();
                plotName2 = dsNames2[0];
                String path2 = lsnr2.getData().getPath();
                String descr2 = _t(lsnr2.getRate().getRateStat().getDescription());
                def.datasource(plotName2, path2, plotName2, SummaryListener.CF, lsnr2.getBackendFactory());
                int linewidth = 2;
                if (hiDPI) {
                    if (periodCount >= 720 || (periodCount >= 480 && width <= 800))
                        linewidth = 2;
                    // sidebar graph
                    else if (width == 250 && height == 50 && hideTitle && hideLegend && hideGrid)
                        linewidth = 3;
                } else {
                    if (periodCount >= 720 || (periodCount >= 480 && width <= 600))
                        linewidth = 1;
                    // sidebar graph
                    else if (width == 250 && height == 50 && hideTitle && hideLegend && hideGrid)
                        linewidth = 3;
                }
                if (theme.equals("midnight"))
                    def.line(plotName2, LINE_COLOR_MIDNIGHT, descr2 + "\\l", linewidth);
                else if (theme.equals("dark"))
                    def.line(plotName2, LINE_COLOR_DARK, descr2 + "\\l", linewidth);
                else
                    def.line(plotName2, LINE_COLOR, descr2 + "\\l", linewidth);
                if (!hideLegend) {
                    Variable var = new Variable.MAX();
                    def.datasource("max2", plotName2, var);
                    def.gprint("max2", " " + _t("Max") + ": %.2f%S");
                    var = new Variable.MIN();
                    def.datasource("min2", plotName, var);
                    def.gprint("min2", "  " + _t("Min") + ": %.2f%S");
                    var = new Variable.AVERAGE();
                    def.datasource("avg2", plotName2, var);
                    def.gprint("avg2", _t("Avg") + ": %.2f%s");
                    var = new Variable.LAST();
                    def.datasource("last2", plotName2, var);
                    def.gprint("last2", "  " + _t("Now") + ": %.2f%S\\l");
                }
            }
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM HH:mm");
            if (!hideLegend) {
                // '07 Jul 21:09' with month name in the system locale
                // TODO: Fix Arabic time display
                Map<Long, String> events = ((RouterContext)_context).router().eventLog().getEvents(EventLog.STARTED, start);
                for (Map.Entry<Long, String> event : events.entrySet()) {
                    long started = event.getKey().longValue();
                    if (started > start && started < end) {
                        // String legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " UTC " + event.getValue() + "\\l";
                        String legend;
                        if (theme.equals("midnight") || theme.equals("dark")) {
                            // RTL languages
                            if (Messages.isRTL(lang)) {
                                legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " - " + event.getValue() + "\\l";
                                def.vrule(started / 1000, RESTART_BAR_COLOR_DARK, legend, 1.0f);
                            } else {
                                legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " [" + event.getValue() + "]\\l";
                                def.vrule(started / 1000, RESTART_BAR_COLOR_DARK, legend, 1.0f);
                            }
                        } else {
                            // RTL languages
                            if (Messages.isRTL(lang)) {
                                legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " - " + event.getValue() + "\\l";
                                def.vrule(started / 1000, RESTART_BAR_COLOR, legend, 2.0f);
                            } else {
                                legend = _t("Restart") + ' ' + sdf.format(new Date(started)) + " [" + event.getValue() + "]\\l";
                                def.vrule(started / 1000, RESTART_BAR_COLOR, legend, 2.0f);
                            }
                        }
                    }
                }
                def.comment(sdf.format(new Date(start)) + " — " + sdf.format(new Date(end)) + " UTC\\r");
            }

            if (!showCredit) {
                def.setShowSignature(false);
            } else if (hideLegend) {
                if (height > 65)
                    def.setSignature("    " + sdf.format(new Date(end)) + " UTC");
                else
                    def.setSignature(sdf.format(new Date(end)) + " UTC");
            }
            /*
            // these four lines set up a graph plotting both values and events on the same chart
            // (but with the same coordinates, so the values may look pretty skewed)
                def.datasource(dsNames[0], path, dsNames[0], "AVERAGE", "MEMORY");
                def.datasource(dsNames[1], path, dsNames[1], "AVERAGE", "MEMORY");
                def.area(dsNames[0], AREA_COLOR, _listener.getRate().getRateStat().getDescription());
                def.line(dsNames[1], LINE_COLOR, "Events per period");
            */
            if (hideLegend)
                def.setNoLegend(true);
            if (hideGrid) {
                def.setDrawXGrid(false);
                def.setDrawYGrid(false);
            }
            //System.out.println("rendering: path=" + path + " dsNames[0]=" + dsNames[0] + " dsNames[1]=" + dsNames[1] + " lsnr.getName=" + _listener.getName());
            def.setAntiAliasing(false);
            def.setTextAntiAliasing(true);
            def.setGridStroke(GRID_STROKE);
            //System.out.println("Rendering: \n" + def.exportXmlTemplate());
            //System.out.println("*****************\nData: \n" + _listener.getData().dump());
            def.setWidth(width);
            def.setHeight(height);
            def.setImageFormat("PNG");
            def.setLazy(true);
            def.setPoolUsed(true);
            def.setAltYMrtg(true);
            if (width < 400 || height < 200)
              def.setNoMinorGrid(true);

            if (hiDPI) {
                if (width < 800 || height < 400)
                    def.setAltYMrtg(false);
            } else {
                if (width < 400 || height < 200)
                    def.setAltYMrtg(false);
            }

            // render unembellished graph if we're on the sidebar or snark
            if ((width == 250 && height == 50 && hideTitle && hideLegend && hideGrid) ||
                (width == 2000 && height == 160 && hideTitle && hideLegend && hideGrid)) {
                def.setOnlyGraph(true);
                //if (theme.equals("classic") || theme.equals("light"))
                def.setColor(RrdGraphDef.COLOR_CANVAS, TRANSPARENT);
                def.setColor(RrdGraphDef.COLOR_BACK, TRANSPARENT);
            }
            RrdGraph graph;
            try {
                // NPE here if system is missing fonts - see ticket #915
                graph = new RrdGraph(def);
            } catch (NullPointerException npe) {
                _log.error("Error rendering", npe);
                StatSummarizer.setDisabled(_context);
                throw new IOException("Error rendering - disabling graph generation. Missing font? See http://trac.i2p2.i2p/ticket/915");
            }
            int totalWidth = graph.getRrdGraphInfo().getWidth();
            int totalHeight = graph.getRrdGraphInfo().getHeight();
//            BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_USHORT_565_RGB);
            BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics gfx = img.getGraphics();
            graph.render(gfx);
            ios = new MemoryCacheImageOutputStream(out);
            ImageIO.write(img, "png", ios);
            _context.statManager().addRateData("graph.renderTime", System.currentTimeMillis() - begin);
        } catch (RrdException re) {
            _log.error("Error rendering", re);
            throw new IOException("Error plotting: " + re.getLocalizedMessage());
        } catch (IOException ioe) {
            // typically org.mortbay.jetty.EofException extends java.io.EOFException
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error rendering", ioe);
            throw ioe;
        } catch (OutOfMemoryError oom) {
            _log.error("Error rendering", oom);
            throw new IOException("Error plotting: " + oom.getLocalizedMessage());
        } finally {
            // this does not close the underlying stream
            if (ios != null) try {ios.close();} catch (IOException ioe) {}
        }
    }

    /** translate a string */
    private String _t(String s) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
            return s;
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     */
    private String _t(String s, String o) {
        // the RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context)))
            return s.replace("{0}", o);
        return Messages.getString(s, o, _context);
    }
}
