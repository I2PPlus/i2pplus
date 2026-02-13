package net.i2p.router.web.helpers;

import static net.i2p.router.web.GraphConstants.*;

import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.i2p.data.DataHelper;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.GraphGenerator;
import net.i2p.router.web.GraphListener;
import net.i2p.router.web.HelperBase;
import net.i2p.stat.Rate;
import net.i2p.util.SystemVersion;

/**
 *  /graphs.jsp, including form, and /graph.jsp
 */
public class GraphHelper extends FormHandler {
    private int _periodCount;
    private boolean _showEvents;
    private int _width;
    private int _height;
    private int _refreshDelaySeconds;
    private boolean _persistent;
    private boolean _graphHideLegend;
    private String _stat;
    private int _end;
    private static final String PROP_X = "routerconsole.graphX";
    private static final String PROP_Y = "routerconsole.graphY";
    private static final String PROP_REFRESH = "routerconsole.graphRefresh";
    private static final String PROP_PERIODS = "routerconsole.graphPeriods";
    private static final String PROP_EVENTS = "routerconsole.graphEvents";
    private static final String PROP_HIDE_LEGEND = "routerconsole.graphHideLegend";
    private static final int DEFAULT_REFRESH = 1*60;
    private static final int DEFAULT_PERIODS = 60;
    private static final boolean DEFAULT_HIDE_LEGEND = false;
    private static final int MIN_X = 160;
    private static final int MIN_Y = 40;
    //private static final int MIN_C = 20;
    private static final int MIN_C = 5; // minimum period (minutes)
    /**
     *  Maximum period count is dynamically limited based on available memory.
     *  Large period counts consume significant memory during graph rendering,
     *  which can cause OOM on systems with limited RAM.
     *
     *  Memory thresholds for effective maximum:
     *  - < 2GB: 2,880 periods (2 days)
     *  - 2GB - 4GB: 10,080 periods (1 week)
     *  - 4GB+: 131,040 periods (91 days, full MAX_ROWS)
     */
    private static final int MAX_C;
    static {
        long maxMem = SystemVersion.getMaxMemory();
        if (maxMem < 2048*1024*1024L) {
            MAX_C = 2 * 24 * 60; // 2,880 periods = 2 days
        } else if (maxMem < 4096*1024*1024L) {
            MAX_C = 7 * 24 * 60; // 10,080 periods = 1 week
        } else {
            MAX_C = GraphListener.MAX_ROWS; // 131,040 periods = 91 days
        }
    }
    private static final int MIN_REFRESH = 5;

    // Cached common constant substrings for URL building (optimization)
    private static final String AMP = "&amp;";
    private static final String SHOW_EVENTS_PARAM = AMP + "showEvents=";
    private static final String HIDE_LEGEND_PARAM = AMP + "hideLegend=";
    private static final String PERIOD_COUNT_PARAM = AMP + "periodCount=";
    private static final String WIDTH_PARAM = AMP + "width=";
    private static final String HEIGHT_PARAM = AMP + "height=";
    private static final String TIME_PARAM = AMP + "time=";
    private static final String STAT_PARAM = "/viewstat.jsp?stat=";
    private static final String GRAPH_HREF = "/graph?stat=";

    /** set the defaults after we have a context */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _width = _context.getProperty(PROP_X, DEFAULT_X);
        _height = _context.getProperty(PROP_Y, DEFAULT_Y);
        _periodCount = _context.getProperty(PROP_PERIODS, DEFAULT_PERIODS);
        _refreshDelaySeconds = _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
        _showEvents = _context.getBooleanProperty(PROP_EVENTS);
    }

    /**
     *  This must be output in the jsp since &lt;meta&gt; must be in the &lt;head&gt;
     *  @since 0.8.7
     */
    public String getRefreshMeta() {
        if (_refreshDelaySeconds <= 8 ||
            ConfigRestartBean.getRestartTimeRemaining() < (1000 * (_refreshDelaySeconds + 30)))
            return "";
        // shorten the refresh by 3 seconds so we beat the iframe
        return "<noscript><meta http-equiv=refresh content=" + (_refreshDelaySeconds - 3) + "></noscript>";
    }

    public int getRefreshValue() {
        return _refreshDelaySeconds;
    }

    public void setPeriodCount(String str) {
        setC(str);
    }

    /** @since 0.9 */
    public void setE(String str) {
        try {_end = Math.max(0, Integer.parseInt(str));}
        catch (NumberFormatException nfe) {}
    }

    /** @since 0.9 shorter parameter */
    public void setC(String str) {
        try {_periodCount = Math.max(MIN_C, Math.min(Integer.parseInt(str), MAX_C));}
        catch (NumberFormatException nfe) {}
    }

    public void setShowEvents(String b) {
        _showEvents = !"false".equals(b);
    }

    public void setHeight(String str) {
        setH(str);
    }

    /** @since 0.9 shorter parameter */
    public void setH(String str) {
        try {_height = Math.max(MIN_Y, Math.min(Integer.parseInt(str), MAX_Y));}
        catch (NumberFormatException nfe) {}
    }

    public void setWidth(String str) {
        setW(str);
    }

    /** @since 0.9 shorter parameter */
    public void setW(String str) {
        try {_width = Math.max(MIN_X, Math.min(Integer.parseInt(str), MAX_X));}
        catch (NumberFormatException nfe) {}
    }

    public void setRefreshDelay(String str) {
        try {
            int rds = Integer.parseInt(str);
            if (rds > 0) {_refreshDelaySeconds = Math.max(rds, MIN_REFRESH);}
            else {_refreshDelaySeconds = -1;}
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.8.7 */
    public void setPersistent(String foo) {_persistent = true;}

    /** @since 0.9.32 */
    public void setHideLegend(String foo) {
        if ("true".equalsIgnoreCase(foo)) {
            _graphHideLegend = true;
            setSingleHideLegend(true);
        } else if ("false".equalsIgnoreCase(foo)) {
            _graphHideLegend = false;
            setSingleHideLegend(false);
        }
    }

    /**
     *  For single stat page
     *  @since 0.9
     */
    public void setStat(String stat) {_stat = stat;}

    public String getImages() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) {return "";}

        List<GraphListener> listeners = ss.getListeners();
        // Cache hideLegend once before use
        boolean hideLegend = _context.getProperty(PROP_HIDE_LEGEND, DEFAULT_HIDE_LEGEND);

        // Build and cache a sorted TreeSet once
        TreeSet<GraphListener> ordered = new TreeSet<>(new AlphaComparator());
        ordered.addAll(listeners);

        // Cache current time once for all uses
        long now = System.currentTimeMillis();

        // Flags for combined bandwidth presence
        boolean hasTx = false;
        boolean hasRx = false;

        for (GraphListener lsnr : ordered) {
            String title = lsnr.getRate().getRateStat().getName();
            if ("bw.sendRate".equals(title)) hasTx = true;
            else if ("bw.recvRate".equals(title)) hasRx = true;
        }

        StringBuilder buf = new StringBuilder(512 * listeners.size());

        if (hasTx && hasRx && !_showEvents) {
            // Instead of iterator.remove on TreeSet, use a filtered approach to avoid mutation overhead
            buf.append("<span class=graphContainer><a href=\"/graph?stat=bw.combined")
               .append(AMP).append("c=").append(3 * _periodCount)
               .append(AMP).append("w=1000").append(AMP).append("h=280\">");

            String title = _t("Combined bandwidth graph");
            buf.append("<img class=statimage src=\"").append(STAT_PARAM).append("bw.combined")
               .append(PERIOD_COUNT_PARAM).append(_periodCount).append(WIDTH_PARAM).append(_width);
            if (!hideLegend) {buf.append(HEIGHT_PARAM).append(_height - 26);}
            else {buf.append(HEIGHT_PARAM).append(_height);}
            title = title.replace("&nbsp;", "");
            buf.append(HIDE_LEGEND_PARAM).append(hideLegend).append(TIME_PARAM).append(now)
               .append("\" alt=\"").append(title).append("\" title=\"").append(title).append("\"></a></span>\n");
        }

        // Iterate excluding bw.sendRate or bw.recvRate if combined graph is shown
        for (GraphListener lsnr : ordered) {
            Rate r = lsnr.getRate();
            String rName = r.getRateStat().getName();
            if (hasTx && hasRx && !_showEvents &&
                ("bw.sendRate".equals(rName) || "bw.recvRate".equals(rName))) {
                // Skip individual tx/rx graphs if combined is shown
                continue;
            }
            String title = _t("{0} for {1}", rName, DataHelper.formatDuration2(_periodCount * r.getPeriod()));
            title = title.replace("&nbsp;", "");
            buf.append("<span class=graphContainer><a href=\"")
               .append(GRAPH_HREF)
               .append(rName.replace(" ", "%20"))
               .append(".")
               .append(r.getPeriod())
               .append(AMP)
               .append("c=").append(3 * _periodCount)
               .append(AMP)
               .append("w=1000").append(AMP).append("h=280");
            if (_showEvents) buf.append(AMP).append("showEvents=1");
            buf.append("\">");

            // Build img src url with cached constants
            buf.append("<img class=statimage border=0 src=\"")
               .append(STAT_PARAM)
               .append(rName.replace(" ", "%20"))
               .append(SHOW_EVENTS_PARAM).append(_showEvents)
               .append(AMP).append("period=").append(r.getPeriod())
               .append(PERIOD_COUNT_PARAM).append(_periodCount)
               .append(WIDTH_PARAM).append(_width)
               .append(HEIGHT_PARAM).append(_height)
               .append(HIDE_LEGEND_PARAM).append(hideLegend)
               .append(TIME_PARAM).append(now)
               .append("\" alt=\"")
               .append(title)
               .append("\" title=\"")
               .append(title)
               .append("\"></a></span>\n");
        }
        return buf.toString();
    }

    public int countGraphs() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) {return 0;}
        else {return ss.countGraphs();}
    }

    /**
     *  For single stat page;
     *  stat = "bw.combined" treated specially
     *
     *  @since 0.9
     */
    public String getSingleStat() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) return "";

        StringBuilder buf = new StringBuilder(2 * 1024);
        boolean hideLegend = false; // default false no longer read from context here for simplicity,
                                    // but could cache from context here if desired

        if (_stat == null) {
            buf.append("<p class=infohelp>").append(_t("Nothing to display - no stat specified!")).append("</p>");
            return buf.toString();
        }
        long period;
        String name;
        String displayName;

        if ("bw.combined".equals(_stat)) {
            period = 60000;
            name = _stat;
            displayName = "[" + _t("Router") + "] " + _t("Bandwidth usage").replace("usage", "Usage");
        } else {
            Set<Rate> rates = ss.parseSpecs(_stat);
            if (rates.size() != 1) {
                buf.append("<p class=infohelp>").append("Graphs not enabled for ").append(_stat)
                   .append(" or the tunnel or service isn't currently running.</p>");
                return buf.toString();
            }
            Rate r = rates.iterator().next();
            period = r.getPeriod();
            name = r.getRateStat().getName();
            displayName = name;
        }

        long now = System.currentTimeMillis();

        buf.append("<h3 id=graphinfo>");
        buf.append(_t("{0} for {1}", displayName, DataHelper.formatDuration2(_periodCount * period)));
        if (_end > 0) {buf.append(' ').append(_t("ending {0} ago", DataHelper.formatDuration2(_end * period)));}
        buf.append("&nbsp;<a href=/graphs>").append(_t("Return to main graphs page")).append("</a></h3>\n")
           .append("<div class=graphspanel id=single>\n<span class=graphContainer><a class=singlegraph href=/graphs title=\"")
           .append(_t("Return to main graphs page"))
           .append("\"><img class=statimage id=graphSingle border=0 src=\"")
           .append(STAT_PARAM).append(name.replace(" ", "%20"))
           .append(SHOW_EVENTS_PARAM).append(_showEvents)
           .append(AMP).append("period=").append(period)
           .append(PERIOD_COUNT_PARAM).append(_periodCount)
           .append(AMP).append("end=").append(_end)
           .append(WIDTH_PARAM).append(_width)
           .append(HEIGHT_PARAM).append(_height)
           .append(HIDE_LEGEND_PARAM).append(getSingleHideLegend())
           .append(TIME_PARAM).append(now)
           .append("\"></a></span>\n</div>\n<p id=graphopts>\n");

        // Cache getSingleHideLegend once here for multiple calls:
        boolean singleHideLegend = getSingleHideLegend();

        if (_width < MAX_X && _height < MAX_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height * 3 / 2, singleHideLegend));
            buf.append(_t("Larger")).append("</a> - ");
        }
        if (_width > MIN_X && _height > MIN_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height * 2 / 3, singleHideLegend));
            buf.append(_t("Smaller")).append("</a> - ");
        }
        if (_height < MAX_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width, _height * 3 / 2, singleHideLegend));
            buf.append(_t("Taller")).append("</a> - ");
        }
        if (_height > MIN_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width, _height * 2 / 3, singleHideLegend));
            buf.append(_t("Shorter")).append("</a> - ");
        }
        if (_width < MAX_X) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height, singleHideLegend));
            buf.append(_t("Wider")).append("</a> - ");
        }
        if (_width > MIN_X) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height, singleHideLegend));
            buf.append(_t("Narrower")).append("</a>");
        }
        buf.append("<br>");
        if (_periodCount < MAX_C) {
            buf.append(link(_stat, _showEvents, _periodCount * 2, _end, _width, _height, singleHideLegend));
            buf.append(_t("Larger interval")).append("</a> - ");
        }
        if (_periodCount > MIN_C) {
            buf.append(link(_stat, _showEvents, _periodCount / 2, _end, _width, _height, singleHideLegend));
            buf.append(_t("Smaller interval")).append("</a> - ");
        }
        if (_periodCount < MAX_C) {
            buf.append(link(_stat, _showEvents, _periodCount, _end + _periodCount, _width, _height, singleHideLegend));
            buf.append(_t("Previous interval")).append("</a>");
        }
        if (_end > 0) {
            int end = _end - _periodCount;
            if (end <= 0) {
                end = 0;
            }
            if (_periodCount < MAX_C) {
                buf.append(" - ");
            }
            buf.append(link(_stat, _showEvents, _periodCount, end, _width, _height, singleHideLegend));
            buf.append(_t("Next interval")).append("</a> ");
        }
        if (!"bw.combined".equals(_stat)) {
            buf.append(" - ");
            buf.append(link(_stat, !_showEvents, _periodCount, _end, _width, _height, singleHideLegend));
            buf.append(_showEvents ? _t("Plot averages") : _t("plot events"));
            buf.append("</a>");
        }
        buf.append(" - ");
        buf.append(link(_stat, _showEvents, _periodCount, _end, _width, _height, singleHideLegend));
        buf.append(singleHideLegend ? _t("Show Legend") : _t("Hide Legend"));
        buf.append("</a>\n</p>\n");
        return buf.toString();
    }

    private static boolean _singleHideLegend;

    private static boolean getSingleHideLegend() {
        return _singleHideLegend;
    }

    private static void setSingleHideLegend(boolean hideLegend) {
        _singleHideLegend = hideLegend;
    }

    /** @since 0.9 */
    private static String link(String stat, boolean showEvents, int periodCount, int end, int width, int height, boolean singleHideLegend) {
        return
               "<a href=\"/graph?stat="
               + stat.replace(" ", "%20")
               + AMP + "c=" + periodCount
               + AMP + "w=" + width
               + AMP + "h=" + height
               + (end > 0 ? AMP + "e=" + end : "")
               + (showEvents ? AMP + "showEvents=1" : "")
               + (singleHideLegend ? AMP + "hideLegend=false" : AMP + "hideLegend=true")
               + "\">";
    }

    private static final int[] times = { 5, 10, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    public String getForm() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) return "";

        // Cache properties once before use
        boolean hideLegend = _context.getProperty(PROP_HIDE_LEGEND, DEFAULT_HIDE_LEGEND);
        boolean persistent = _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT);

        String nonce = CSSHelper.getNonce();

        StringBuilder buf = new StringBuilder(3 * 1024);
        buf.append("<br><input type=checkbox id=toggleSettings hidden><label for=toggleSettings><h3 id=graphdisplay tabindex=0>")
           .append(_t("Configure Graph Display"))
           .append("</h3></label><form id=gform action=/updategraphs method=POST>\n<table>\n<tr><td><div class=optionlist>\n<input type=hidden name=action value=Save>\n")
           .append("<input type=hidden name=nonce value=")
           .append(nonce)
           .append(">\n<span class=nowrap title=\"")
           .append(_t("Note: Dimensions are for graph only (excludes title, labels and legend)."))
           .append("\"><b>")
           .append(_t("Graph size"))
           .append(":</b>&nbsp; <input id=gwidth size=4 type=text name=width value=\"")
           .append(_width)
           .append("\">")
           .append(_t("pixels wide"))
           .append("&nbsp;&nbsp;&nbsp;<input size=4 type=text name=height value=\"")
           .append(_height)
           .append("\">")
           .append(_t("pixels high"))
           .append("</span><br>\n<span class=nowrap>\n<b>")
           .append(_t("Display period"))
           .append(":</b> <input size=5 type=text name=periodCount value=\"")
           .append(_periodCount).append("\">")
           .append(_t("minutes"))
           .append("</span><br>\n<span class=nowrap>\n<b>")
           .append(_t("Refresh delay"))
           .append(":</b> <select name=refreshDelay>");
        for (int i = 0; i < times.length; i++) {
            buf.append("<option value=\"").append(times[i]).append('"');
            if (times[i] == _refreshDelaySeconds) {buf.append(HelperBase.SELECTED);}
            buf.append('>');
            if (times[i] > 0) {buf.append(DataHelper.formatDuration2(times[i] * 1000));}
            else {buf.append(_t("Never"));}
            buf.append("</option>\n");
        }
        buf.append("</select></span><br>\n<span class=nowrap>\n<b>")
           .append(_t("Plot type"))
           .append(":</b> <label><input type=radio class=optbox name=\"showEvents\" value=false ")
           .append((_showEvents ? "" : HelperBase.CHECKED))
           .append(">")
           .append(_t("Averages"))
           .append("</label>&nbsp;&nbsp;&nbsp;<label><input type=radio class=optbox name=\"showEvents\" value=true ")
           .append((_showEvents ? HelperBase.CHECKED : ""))
           .append(">")
           .append(_t("Events"))
           .append("</label></span><br>\n<span class=nowrap>\n<b>")
           .append(_t("Hide legend"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=hideLegend");
        if (hideLegend) buf.append(HelperBase.CHECKED);
        buf.append(">")
           .append(_t("Do not show legend on graphs"))
           .append("</label></span><br><span class=nowrap>\n<b>")
           .append(_t("Persistence"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=persistent");
        if (persistent) buf.append(HelperBase.CHECKED);
        buf.append(">")
           .append(_t("Store graph data on disk"))
           .append("</label></span>\n</div>\n</td></tr>\n</table>\n<hr>\n<div class=formaction id=graphing><a class=fakebutton href=/configstats>")
           .append(_t("Select Stats"))
           .append("</a> <input type=submit class=accept value=\"")
           .append(_t("Save settings and redraw graphs"))
           .append("\"></div>\n</form>\n");
        return buf.toString();
    }

    /**
     *  We have to do this here because processForm() isn't called unless the nonces are good
     *  @since 0.8.7
     */
    @Override
    public String getAllMessages() {
        if (GraphGenerator.isDisabled(_context)) {
            addFormError("Either the router hasn't initialized yet, or graph generation is not supported with this JVM or OS.");
            addFormNotice("JVM: " + System.getProperty("java.vendor") + ' ' +
                                    System.getProperty("java.version") + " (" +
                                    System.getProperty("java.runtime.name") + ' ' +
                                    System.getProperty("java.runtime.version") + ')');
            addFormNotice("OS: " +  System.getProperty("os.name") + ' ' +
                                    System.getProperty("os.arch") + ' ' +
                                    System.getProperty("os.version"));
            addFormNotice("Check logs for more information.");
            if (_context.getProperty(PROP_REFRESH, 0) >= 0) {
                _context.router().saveConfig(PROP_REFRESH, "-1");
            }
        }
        return super.getAllMessages();
    }

    /**
     *  This was a HelperBase but now it's a FormHandler
     *  @since 0.8.2
     */
    @Override
    protected void processForm() {
        if ("Save".equals(_action))
            saveSettings();
    }

    /**
     *  Silently save settings if changed, no indication of success or failure
     *  @since 0.7.10
     */
    private void saveSettings() {
        if (_width != _context.getProperty(PROP_X, DEFAULT_X) ||
            _height != _context.getProperty(PROP_Y, DEFAULT_Y) ||
            _periodCount != _context.getProperty(PROP_PERIODS, DEFAULT_PERIODS) ||
            _refreshDelaySeconds != _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH) ||
            _showEvents != _context.getBooleanProperty(PROP_EVENTS) ||
            _graphHideLegend != _context.getProperty(PROP_HIDE_LEGEND, DEFAULT_HIDE_LEGEND) ||
            _persistent != _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT)) {
            Map<String, String> changes = new HashMap<>();
            changes.put(PROP_X, Integer.toString(_width));
            changes.put(PROP_Y, Integer.toString(_height));
            changes.put(PROP_PERIODS, Integer.toString(_periodCount));
            changes.put(PROP_REFRESH, Integer.toString(_refreshDelaySeconds));
            changes.put(PROP_EVENTS, Boolean.toString(_showEvents));
            changes.put(PROP_HIDE_LEGEND, Boolean.toString(_graphHideLegend));
            changes.put(GraphListener.PROP_PERSISTENT, Boolean.toString(_persistent));
            boolean warn = _persistent != _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT);
            _context.router().saveConfig(changes, null);
            addFormNotice(_t("Graph settings saved") + ".");
            if (warn) {
                addFormError(_t("Restart required to take effect"));
            }
        } else {
            addFormNotice(_t("Graph settings unchanged") + ".");
        }
    }

    private static class AlphaComparator implements Comparator<GraphListener>, Serializable {
        public int compare(GraphListener l, GraphListener r) {
            // sort by group name
            String lGName = l.getRate().getRateStat().getGroupName();
            String rGName = r.getRate().getRateStat().getGroupName();
            boolean lrouter = lGName.equals("Router");
            boolean rrouter = rGName.equals("Router");
            if (lrouter && !rrouter)
                return -1;
            if (rrouter && !lrouter)
                return 1;
            lrouter = lGName.startsWith("Router");
            rrouter = rGName.startsWith("Router");
            if (lrouter && !rrouter)
                return -1;
            if (rrouter && !lrouter)
                return 1;
            int sort = lGName.compareTo(rGName);
            if (sort != 0)
                return sort;
            // sort by stat name
            String lName = l.getRate().getRateStat().getName();
            String rName = r.getRate().getRateStat().getName();
            int rv = lName.compareTo(rName);
            if (rv != 0)
                return rv;
            return (int) (l.getRate().getPeriod() - r.getRate().getPeriod());
        }
    }
}
