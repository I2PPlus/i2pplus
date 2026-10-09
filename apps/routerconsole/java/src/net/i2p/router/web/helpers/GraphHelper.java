package net.i2p.router.web.helpers;

import static net.i2p.router.web.GraphConstants.*;

import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.i2p.data.DataHelper;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.GraphGenerator;
import net.i2p.router.web.GraphGroups;
import net.i2p.router.web.GraphListener;
import net.i2p.router.web.HelperBase;
import net.i2p.stat.Rate;

/**
 * Renders the graphs list (/graphs.jsp) and the single-stat page (/graph.jsp),
 * plus the graph display configuration form.
 *
 * Stateless across requests except for per-request form state set via the
 * {@code setXxx()} accessors; preferences are read from context properties.
 */
public class GraphHelper extends FormHandler {

    /**
     * Leaves the form state unset; setContextId() supplies the context and preferences.
     */
    public GraphHelper() {}

    private int _periodCount;
    private boolean _showEvents;
    private int _width;
    private int _height;
    private int _refreshDelaySeconds;
    private boolean _persistent;
    private boolean _graphHideLegend;
    private boolean _graphHideRestarts;
    private boolean _graphGlow;
    private boolean _graphSmooth;
    private boolean _graphFill;
    private boolean _graphCombine;
    private boolean _useUtc;
    private String _stat;
    private int _end;
    private static final String PROP_X = "routerconsole.graphX";
    private static final String PROP_Y = "routerconsole.graphY";
    private static final String PROP_REFRESH = "routerconsole.graphRefresh";
    private static final String PROP_PERIODS = "routerconsole.graphPeriods";
    private static final String PROP_EVENTS = "routerconsole.graphEvents";
    private static final String PROP_HIDE_LEGEND = "routerconsole.graphHideLegend";
    private static final String PROP_HIDE_RESTARTS = "routerconsole.graphHideRestarts";
    private static final String PROP_GLOW = "routerconsole.graphGlow";
    private static final String PROP_SMOOTH = "routerconsole.graphSmooth";
    private static final String PROP_FILL = "routerconsole.graphFill";
    private static final String PROP_COMBINE = "routerconsole.graphCombine";
    private static final String PROP_UTC = "routerconsole.graphUtc";
    private static final int DEFAULT_REFRESH = 1*60;
    private static final int DEFAULT_PERIODS = 60;
    private static final boolean DEFAULT_HIDE_LEGEND = false;
    private static final boolean DEFAULT_HIDE_RESTARTS = false;
    private static final int MIN_X = 160;
    private static final int MIN_Y = 40;
    private static final int MIN_C = 5; // minimum period (minutes)
    private static final int MAX_C = GraphListener.MAX_ROWS;
    private static final int MIN_REFRESH = 5;

    // Cached common constant substrings for URL building (optimization)
    private static final String AMP = "&amp;";
    private static final String SHOW_EVENTS_PARAM = AMP + "showEvents=";
    private static final String HIDE_LEGEND_PARAM = AMP + "hideLegend=";
    private static final String HIDE_RESTARTS_PARAM = AMP + "hideRestarts=";
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
        _graphHideLegend = _context.getProperty(PROP_HIDE_LEGEND, DEFAULT_HIDE_LEGEND);
        _graphHideRestarts = Boolean.parseBoolean(_context.getProperty(PROP_HIDE_RESTARTS,
                                                   Boolean.toString(DEFAULT_HIDE_RESTARTS)));
        _persistent = _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT);
        _graphGlow = _context.getBooleanPropertyDefaultTrue(PROP_GLOW);
        // Default on: a bezier curve reads better than a staircase at every density the
        // graphs page offers. Opt out with routerconsole.graphSmooth=false.
        _graphSmooth = _context.getBooleanPropertyDefaultTrue(PROP_SMOOTH);
        _graphFill = _context.getBooleanProperty(PROP_FILL);
        _graphCombine = _context.getBooleanProperty(PROP_COMBINE);
        _useUtc = _context.getBooleanPropertyDefaultTrue(PROP_UTC);

    }

    /**
     * This must be output in the jsp since &lt;meta&gt; must be in the &lt;head&gt;
     * @return the refresh meta
     * @since 0.8.7
     */
    public String getRefreshMeta() {
        if (_refreshDelaySeconds <= 8 ||
            ConfigRestartBean.getRestartTimeRemaining() < (1000 * (_refreshDelaySeconds + 30)))
            return "";
        // shorten the refresh by 3 seconds so we beat the iframe
        return "<noscript><meta http-equiv=refresh content=" + (_refreshDelaySeconds - 3) + "></noscript>";
    }

    /**
     * The refresh delay the current render should actually use, in seconds.
     *
     * @return the delay in seconds, or -1 when graph generation is unavailable
     */
    public int getRefreshValue() {
        return effectiveRefresh(_refreshDelaySeconds, GraphGenerator.isDisabled(_context));
    }

    /**
     * The auto-refresh delay a page render should actually use.
     *
     * <p>Refreshing is pointless while graph generation is unavailable, and hammering a console
     * that cannot render is worse than showing nothing, so the delay is suppressed for that one
     * render. It is deliberately not persisted: graph generation can come back, and writing the
     * suppression into the config made it permanent, so once graphing had been unavailable for any
     * reason the graphs page would freeze on its first paint and never refresh again, even after
     * the router had recovered.
     *
     * @param saved the configured delay in seconds
     * @param graphingDisabled whether graph generation is currently unavailable
     * @return the delay to use for this render, or -1 to suppress refreshing
     * @since 0.9.71
     */
    static int effectiveRefresh(int saved, boolean graphingDisabled) {
        return graphingDisabled ? -1 : saved;
    }

    /**
     * setPeriodCount.
     *
     * @param str period count in minutes; non-numeric text is ignored and numbers
     * are clamped to MIN_C..MAX_C
     */
    public void setPeriodCount(String str) {
        setC(str);
    }

    /**
     * Ends the view window that many periods before the most recent one.
     *
     * @param str how many periods back to end, so 0 ends at the latest period;
     * non-numeric text is ignored
     */
    public void setE(String str) {
        try {_end = Math.max(0, Integer.parseInt(str));}
        catch (NumberFormatException nfe) { /* ignored */ }
    }

    /**
     * Shorter form of {@link #setPeriodCount} bound to the c= URL parameter.
     *
     * @param str period count in minutes; non-numeric text is ignored and numbers
     * are clamped to MIN_C..MAX_C
     */
    public void setC(String str) {
        try {_periodCount = Math.max(MIN_C, Math.min(Integer.parseInt(str), MAX_C));}
        catch (NumberFormatException nfe) { /* ignored */ }
    }

    /**
     * setShowEvents.
     *
     * @param b true to plot individual events, false to plot averages; an absent,
     * empty or unrecognised value leaves the current mode in force
     */
    public void setShowEvents(String b) {
        _showEvents = resolveShowEvents(b, _showEvents);
    }

    /**
     * Resolves the requested events mode against the mode in force.
     *
     * <p>An absent or empty parameter means "not specified", and the mode already in force -
     * which came from {@code routerconsole.graphEvents} - stands. It must not be read as a
     * request for events: the enlarged-graph link only emits {@code showEvents} when events
     * are on, so a time-mode link omits it, and the old {@code !"false".equals(b)} turned that
     * omission into {@code true}. Opening the larger view of a time graph silently switched it
     * to events and contradicted the configured default. The two URL-building sites disagreed
     * about whether the parameter is always present; this makes the reader tolerant of its
     * absence instead.
     *
     * <p>The value is also parsed symmetrically with the way it is written. One link emits
     * {@code showEvents=1} and the image sources emit {@code showEvents=true|false}, but the
     * reader recognised only the literal string {@code "false"} - so {@code showEvents=0}, a
     * perfectly ordinary way to spell it, was read as a request for <em>events</em>.
     *
     * @param param the request parameter, may be null or empty
     * @param current the mode currently in force
     * @return the mode to use
     */
    static boolean resolveShowEvents(String param, boolean current) {
        if (param == null || param.isEmpty()) {return current;}
        String v = param.trim();
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "no".equalsIgnoreCase(v)) {return false;}
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v)) {return true;}
        return current;
    }

    /**
     * setHeight.
     *
     * @param str image height in pixels; non-numeric text is ignored and numbers
     * are clamped to MIN_Y..MAX_Y
     */
    public void setHeight(String str) {
        setH(str);
    }

    /**
     * Shorter form of {@link #setHeight} bound to the h= URL parameter.
     *
     * @param str image height in pixels; non-numeric text is ignored and numbers
     * are clamped to MIN_Y..MAX_Y
     */
    public void setH(String str) {
        try {_height = Math.max(MIN_Y, Math.min(Integer.parseInt(str), MAX_Y));}
        catch (NumberFormatException nfe) { /* ignored */ }
    }

    /**
     * setWidth.
     *
     * @param str image width in pixels; non-numeric text is ignored and numbers
     * are clamped to MIN_X..MAX_X
     */
    public void setWidth(String str) {
        setW(str);
    }

    /**
     * Shorter form of {@link #setWidth} bound to the w= URL parameter.
     *
     * @param str image width in pixels; non-numeric text is ignored and numbers
     * are clamped to MIN_X..MAX_X
     */
    public void setW(String str) {
        try {_width = Math.max(MIN_X, Math.min(Integer.parseInt(str), MAX_X));}
        catch (NumberFormatException nfe) { /* ignored */ }
    }

    /**
     * setRefreshDelay.
     *
     * @param str refresh delay in seconds; a positive value is raised to
     * MIN_REFRESH, zero or negative selects never refresh, and
     * non-numeric text is ignored
     */
    public void setRefreshDelay(String str) {
        try {
            int rds = Integer.parseInt(str);
            if (rds > 0) {_refreshDelaySeconds = Math.max(rds, MIN_REFRESH);}
            else {_refreshDelaySeconds = -1;}
        } catch (NumberFormatException nfe) { /* ignored */ }
    }

    /**
     * Enables storing graph data on disk; the submitted value is not examined.
     *
     * @param foo the persistent checkbox value, ignored
     * @since 0.8.7
     */
    public void setPersistent(String foo) {_persistent = true;}

    /**
     * Sets the glow effect drawn around graph lines.
     *
     * @param foo only the exact text {@code false} turns the glow off; any other
     * value enables it
     * @since 0.9.70+
     */
    public void setGraphGlow(String foo) {_graphGlow = !"false".equals(foo);}

    /**
     * Sets bezier curves in place of a staircase plot.
     *
     * @param foo only the exact text {@code false} turns smoothing off; any other
     * value enables it
     */
    public void setGraphSmooth(String foo) {_graphSmooth = !"false".equals(foo);}

    /**
     * Sets whether graphs are drawn with filled areas.
     *
     * @param foo only the exact text {@code false} turns filling off; any other
     * value enables it
     */
    public void setGraphFill(String foo) {_graphFill = !"false".equals(foo);}

    /**
     * Sets whether related stats are merged into one overlaid plot.
     *
     * @param foo only the exact text {@code false} turns combining off; any other
     * value enables it
     * @since 0.9.71+
     */
    public void setGraphCombine(String foo) {_graphCombine = !"false".equals(foo);}

    /**
     * Sets whether graph axes are labelled in UTC instead of local time.
     *
     * @param foo only the exact text {@code false} turns UTC off; any other
     * value enables it
     * @since 0.9.70+
     */
    public void setUseUtc(String foo) {_useUtc = !"false".equals(foo);}

    /**
     * Sets whether the legend is suppressed on graphs.
     *
     * @param foo {@code true} hides the legend and {@code false} shows it; any
     * other value leaves the current setting alone
     * @since 0.9.32
     */
    public void setHideLegend(String foo) {
        if ("true".equalsIgnoreCase(foo)) {
            _graphHideLegend = true;
            _hideLegend = true;
        } else if ("false".equalsIgnoreCase(foo)) {
            _graphHideLegend = false;
            _hideLegend = false;
        }
    }

    /**
     * Sets whether restart markers are suppressed on graphs.
     *
     * @param foo {@code true} suppresses them and {@code false} restores them;
     * any other value leaves the current setting alone
     * @since 0.9.70+
     */
    public void setHideRestarts(String foo) {
        if ("true".equalsIgnoreCase(foo)) {
            _graphHideRestarts = true;
        } else if ("false".equalsIgnoreCase(foo)) {
            _graphHideRestarts = false;
        }
    }

    /**
     * For single stat page
     * @param stat the rate name to plot, or a group id when combining is on
     */
    public void setStat(String stat) {_stat = stat;}

    /**
     * The thumbnail list for the graphs page, one anchor-wrapped image per enabled
     * stat plus a tile per active group.
     *
     * @return the images
     */
    public String getImages() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) {return "";}

        List<GraphListener> listeners = ss.getListeners();
        boolean hideLegend = _context.getProperty(PROP_HIDE_LEGEND, DEFAULT_HIDE_LEGEND);
        boolean hideRestarts = _graphHideRestarts;

        // Sort listeners once for stable display order
        TreeSet<GraphListener> ordered = new TreeSet<>(new AlphaComparator());
        ordered.addAll(listeners);

        // Capture current time once for all image URLs (cache-busting)
        long now = System.currentTimeMillis();

        // Detect combined bandwidth availability
        boolean hasTx = false;
        boolean hasRx = false;
        for (GraphListener lsnr : ordered) {
            String title = lsnr.getRate().getRateStat().getName();
            if ("bw.sendRate".equals(title)) {hasTx = true; break;}
            else if ("bw.recvRate".equals(title)) {hasRx = true;}
        }
        boolean combined = hasTx && hasRx && !_showEvents;

        StringBuilder buf = new StringBuilder(512 * listeners.size());

        if (combined) {
            buf.append("<span class=graphContainer><a href=\"/graph?stat=bw.combined")
               .append(AMP).append("c=").append(3 * _periodCount)
               .append(AMP).append("w=1000").append(AMP).append("h=280\">");

            String title = _t("Combined bandwidth graph");
            buf.append("<img class=statimage src=\"").append(STAT_PARAM).append("bw.combined")
               .append(PERIOD_COUNT_PARAM).append(_periodCount).append(WIDTH_PARAM).append(_width);
            if (!hideLegend) {buf.append(HEIGHT_PARAM).append(_height - 26);}
            else {buf.append(HEIGHT_PARAM).append(_height);}
            title = title.replace("&nbsp;", "");
            buf.append(HIDE_LEGEND_PARAM).append(hideLegend)
               .append(HIDE_RESTARTS_PARAM).append(hideRestarts)
               .append(TIME_PARAM).append(now)
               .append("\" alt=\"").append(title).append("\" title=\"").append(title).append("\"></a></span>\n");
        }

        // Enabled stats, and those a combined graph already covers.
        Set<String> enabledStats = new HashSet<>();
        for (GraphListener lsnr : ordered) {
            enabledStats.add(lsnr.getRate().getRateStat().getName());
        }
        Set<String> covered = GraphGroups.suppressedStats(enabledStats, _graphCombine, _showEvents);

        // Iterate excluding bw.sendRate or bw.recvRate if combined graph is shown
        for (GraphListener lsnr : ordered) {
            Rate r = lsnr.getRate();
            String rName = r.getRateStat().getName();
            if (combined &&
                ("bw.sendRate".equals(rName) || "bw.recvRate".equals(rName))) {
                // Skip individual tx/rx graphs if combined is shown
                continue;
            }
            // The group's combined plot already carries these.
            if (covered.contains(rName)) {
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
               .append(HIDE_RESTARTS_PARAM).append(hideRestarts)
               .append(TIME_PARAM).append(now)
               .append("\" alt=\"")
               .append(title)
               .append("\" title=\"")
               .append(title)
               .append("\"></a></span>\n");
        }

        buf.append(renderGroupTiles(enabledStats, hideLegend, hideRestarts, now));
        return buf.toString();
    }

    /**
     * Emit one tile per active group, each plotting its members as overlaid lines.
     *
     * <p>A group appears only when at least two members are enabled and carry data, so
     * enabling a single stat is never silently turned into a combined plot.
     *
     * @return markup for every qualifying group, empty when none qualify
     * @since 0.9.71+
     */
    private String renderGroupTiles(Set<String> enabledStats, boolean hideLegend,
                                    boolean hideRestarts, long now) {
        if (!_graphCombine || _showEvents) {
            return "";
        }
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(512);
        // allGroupIds, not groupIds: the In/Out pairs a pool's rates form are generated
        // from the enabled set rather than declared, and they tile exactly like a
        // registry group does.
        for (String groupId : GraphGroups.allGroupIds(enabledStats)) {
            List<GraphListener> members = ss.getGroupListeners(groupId, enabledStats);
            if (members.size() < 2) {
                continue;
            }
            GraphListener primary = members.get(0);
            Rate r = primary.getRate();
            // Two placeholders only: _t has no three-argument form, and a third "{2}"
            // would be emitted literally.
            String displayName = GraphGroups.displayPrefixOf(groupId) + _t(GraphGroups.titleOf(groupId)) + " (" + members.size() + ")";
            String title = _t("{0} for {1}", displayName,
                              DataHelper.formatDuration2(_periodCount * r.getPeriod()));
            title = title.replace("&nbsp;", "");
            // A generated pair id carries the pool's name verbatim, and that name may
            // hold spaces; registry ids never do.
            String encId = groupId.replace(" ", "%20");
            out.append("<span class=graphContainer><a href=\"").append(GRAPH_HREF)
               .append(encId)
               .append(AMP).append("c=").append(3 * _periodCount)
               .append(AMP).append("w=1000").append(AMP).append("h=280")
               .append("\">")
               .append("<img class=statimage border=0 src=\"").append(STAT_PARAM)
               .append(encId)
               .append(AMP).append("period=").append(r.getPeriod())
               .append(PERIOD_COUNT_PARAM).append(_periodCount)
               .append(WIDTH_PARAM).append(_width)
               .append(HEIGHT_PARAM).append(hideLegend ? _height : _height - 26)
               .append(HIDE_LEGEND_PARAM).append(hideLegend)
               .append(HIDE_RESTARTS_PARAM).append(hideRestarts)
               .append(TIME_PARAM).append(now)
               .append("\" alt=\"").append(title).append("\" title=\"").append(title)
               .append("\"></a></span>\n");
        }
        return out.toString();
    }

    /**
     * The stat names the user has switched on for graphing.
     *
     * @param ss the graph generator
     * @return enabled stat names, without period suffixes
     * @since 0.9.71+
     */
    private static Set<String> enabledStatNames(GraphGenerator ss) {
        Set<String> names = new HashSet<>();
        for (GraphListener lsnr : ss.getListeners()) {
            names.add(lsnr.getRate().getRateStat().getName());
        }
        return names;
    }

    /**
     * How many graphs the configured listeners will draw.
     *
     * @return the number of configured graph listeners, or 0 if graphs are disabled
     */
    public int countGraphs() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) {return 0;}
        else {return ss.countGraphs();}
    }

    /**
     * For single stat page;
     * stat = "bw.combined" treated specially
     *
     * @return the single stat
     */
    public String getSingleStat() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) return "";

        StringBuilder buf = new StringBuilder(2 * 1024);

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
            displayName = "[" + _t("Router") + "] " + _t("Bandwidth Usage");
        } else if (GraphGroups.groupIds().contains(_stat) || GraphGroups.isPairId(_stat)) {
            // A group id is not a stat, so it never reaches parseSpecs. Resolve it here or
            // the click-through page would report the group as "not enabled for graphing".
            if (!_graphCombine) {
                buf.append("<p class=infohelp>").append(_t("Graph combining is not enabled")).append("</p>");
                return buf.toString();
            }
            List<GraphListener> members = ss.getGroupListeners(_stat, enabledStatNames(ss));
            if (members.size() < 2) {
                buf.append("<p class=infohelp>").append(_t("Not enough stats enabled to combine")).append("</p>");
                return buf.toString();
            }
            period = members.get(0).getRate().getPeriod();
            name = _stat;
            displayName = GraphGroups.displayPrefixOf(_stat) + _t(GraphGroups.titleOf(_stat)) + " (" + members.size() + ")";
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
           .append(HIDE_LEGEND_PARAM).append(_hideLegend)
           .append(HIDE_RESTARTS_PARAM).append(_graphHideRestarts)
           .append(TIME_PARAM).append(now)
           .append("\"></a></span>\n</div>\n<p id=graphopts>\n");

        if (_width < MAX_X && _height < MAX_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height * 3 / 2, _hideLegend, _graphHideRestarts));
            buf.append(_t("Larger")).append("</a> - ");
        }
        if (_width > MIN_X && _height > MIN_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height * 2 / 3, _hideLegend, _graphHideRestarts));
            buf.append(_t("Smaller")).append("</a> - ");
        }
        if (_height < MAX_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width, _height * 3 / 2, _hideLegend, _graphHideRestarts));
            buf.append(_t("Taller")).append("</a> - ");
        }
        if (_height > MIN_Y) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width, _height * 2 / 3, _hideLegend, _graphHideRestarts));
            buf.append(_t("Shorter")).append("</a> - ");
        }
        if (_width < MAX_X) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height, _hideLegend, _graphHideRestarts));
            buf.append(_t("Wider")).append("</a> - ");
        }
        if (_width > MIN_X) {
            buf.append(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height, _hideLegend, _graphHideRestarts));
            buf.append(_t("Narrower")).append("</a>");
        }
        buf.append("<br>");
        if (_periodCount < MAX_C) {
            buf.append(link(_stat, _showEvents, _periodCount * 2, _end, _width, _height, _hideLegend, _graphHideRestarts));
            buf.append(_t("Larger interval")).append("</a> - ");
        }
        if (_periodCount > MIN_C) {
            buf.append(link(_stat, _showEvents, _periodCount / 2, _end, _width, _height, _hideLegend, _graphHideRestarts));
            buf.append(_t("Smaller interval")).append("</a> - ");
        }
        if (_periodCount < MAX_C) {
            buf.append(link(_stat, _showEvents, _periodCount, _end + _periodCount, _width, _height, _hideLegend, _graphHideRestarts));
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
            buf.append(link(_stat, _showEvents, _periodCount, end, _width, _height, _hideLegend, _graphHideRestarts));
            buf.append(_t("Next interval")).append("</a> ");
        }
        if (!"bw.combined".equals(_stat)) {
            buf.append(" - ");
            buf.append(link(_stat, !_showEvents, _periodCount, _end, _width, _height, _hideLegend, _graphHideRestarts));
            buf.append(_showEvents ? _t("Plot averages") : _t("plot events"));
            buf.append("</a>");
        }
        buf.append(" - ");
        buf.append(link(_stat, _showEvents, _periodCount, _end, _width, _height, _hideLegend, _graphHideRestarts));
        buf.append(_hideLegend ? _t("Show Legend") : _t("Hide Legend"));
        buf.append("</a>\n</p>\n");
        return buf.toString();
    }

    private boolean _hideLegend;

    /** @since 0.9 */
    private static String link(String stat, boolean showEvents, int periodCount, int end, int width, int height,
                               boolean hideLegend, boolean hideRestarts) {
        return
               "<a href=\"/graph?stat="
               + stat.replace(" ", "%20")
               + AMP + "c=" + periodCount
               + AMP + "w=" + width
               + AMP + "h=" + height
               + (end > 0 ? AMP + "e=" + end : "")
               + (showEvents ? AMP + "showEvents=1" : "")
               + (hideLegend ? AMP + "hideLegend=false" : AMP + "hideLegend=true")
               + (hideRestarts ? AMP + "hideRestarts=true" : AMP + "hideRestarts=false")
               + "\">";
    }

    private static final int[] times = { 5, 10, 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    /**
     * The graph display configuration form posted to /graphs.
     *
     * @return the form
     */
    public String getForm() {
        GraphGenerator ss = GraphGenerator.instance(_context);
        if (ss == null) return "";

        // Reuse cached preferences already loaded in setContextId()
        boolean hideLegend = _graphHideLegend;
        boolean persistent = _persistent;

        String nonce = _session != null ? CSSHelper.getNonce(_session) : CSSHelper.getNonce();

        StringBuilder buf = new StringBuilder(3 * 1024);
        buf.append("<br><input type=checkbox id=toggleSettings hidden><label for=toggleSettings><h3 id=graphdisplay tabindex=0>")
           .append(_t("Configure Graph Display"))
           .append("</h3></label><form id=gform action=/graphs method=POST>\n<table>\n<tr><td><div class=optionlist>\n<input type=hidden name=action value=Save>\n")
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
            if (times[i] > 0) {buf.append(DataHelper.formatDuration2((long) times[i] * 1000));}
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
        if (hideLegend) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Do not show legend on graphs"))
           .append("</label><input type=hidden name=hideLegend value=false></span><br><span class=\"nowrap")
           .append(hideLegend ? " disabled\"" : "\"")
           .append(" title=\"")
           .append(_t("Disabled while legend is hidden"))
           .append("\">\n<b>")
           .append(_t("Hide restarts"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=hideRestarts");
        if (_graphHideRestarts) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Suppress restart lines on graphs"))
           .append("</label><input type=hidden name=hideRestarts value=false></span><br><span class=nowrap>\n<b>")
           .append(_t("Persistence"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=persistent");
        if (persistent) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Store graph data on disk"))
           .append("</label><input type=hidden name=persistent value=false></span><br><span class=nowrap>\n<b>")
           .append(_t("UTC time"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=useUtc");
        if (_useUtc) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Display time in UTC on graph axes"))
           .append("</label><input type=hidden name=useUtc value=false></span><br><span class=nowrap>\n<b>")
           .append(_t("Glow effect"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=graphGlow");
        if (_graphGlow) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Add a glow effect to graph lines"))
           .append("</label><input type=hidden name=graphGlow value=false></span><br><span class=nowrap>\n<b>")
           .append(_t("Smooth lines"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=graphSmooth");
        if (_graphSmooth) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Use bezier curves to plot graphs"))
           .append("</label><input type=hidden name=graphSmooth value=false></span><br><span class=nowrap>\n<b>")
           .append(_t("Filled paths"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=graphFill");
        if (_graphFill) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Use filled areas for graphs"))
           .append("</label><input type=hidden name=graphFill value=false></span><br><span class=nowrap>\n<b>")
           .append(_t("Combine graphs"))
           .append(":</b> <label><input type=checkbox class=\"optbox slider\" value=true name=graphCombine");
        if (_graphCombine) {
            buf.append(HelperBase.CHECKED);
        }
        buf.append(">")
           .append(_t("Merge related graphs into a plot"))
           .append("</label><input type=hidden name=graphCombine value=false></span><br>")
           .append("\n</div>\n</td></tr>\n</table>\n<hr>\n<div class=formaction id=graphing><a class=fakebutton href=/configstats>")
           .append(_t("Select Stats"))
           .append("</a> <input type=submit class=accept value=\"")
           .append(_t("Save settings and redraw graphs"))
           .append("\"></div>\n</form>\n");
        return buf.toString();
    }

    /**
     * We have to do this here because processForm() isn't called unless the nonces are good
     * @return the all messages
     * @since 0.8.7
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
        }
        return super.getAllMessages();
    }

    /**
     * This was a HelperBase but now it's a FormHandler
     * @since 0.8.2
     */
    @Override
    protected void processForm() {
        if ("Save".equals(_action)) {
            saveSettings();
        }
    }

    /**
     * Silently save settings if changed, no indication of success or failure
     * @since 0.7.10
     */
    private void saveSettings() {
        if (_width != _context.getProperty(PROP_X, DEFAULT_X) ||
            _height != _context.getProperty(PROP_Y, DEFAULT_Y) ||
            _periodCount != _context.getProperty(PROP_PERIODS, DEFAULT_PERIODS) ||
            _refreshDelaySeconds != _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH) ||
            _showEvents != _context.getBooleanProperty(PROP_EVENTS) ||
            _graphHideLegend != _context.getProperty(PROP_HIDE_LEGEND, DEFAULT_HIDE_LEGEND) ||
            _graphHideRestarts != Boolean.parseBoolean(_context.getProperty(PROP_HIDE_RESTARTS,
                                                       Boolean.toString(DEFAULT_HIDE_RESTARTS))) ||
            _persistent != _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT) ||
            _graphGlow != _context.getBooleanPropertyDefaultTrue(PROP_GLOW) ||
            _graphSmooth != _context.getBooleanPropertyDefaultTrue(PROP_SMOOTH) ||
            _graphFill != _context.getBooleanProperty(PROP_FILL) ||
            _graphCombine != _context.getBooleanProperty(PROP_COMBINE) ||
            _useUtc != _context.getBooleanPropertyDefaultTrue(PROP_UTC)) {
            Map<String, String> changes = new HashMap<>();
            changes.put(PROP_X, Integer.toString(_width));
            changes.put(PROP_Y, Integer.toString(_height));
            changes.put(PROP_PERIODS, Integer.toString(_periodCount));
            changes.put(PROP_REFRESH, Integer.toString(_refreshDelaySeconds));
            changes.put(PROP_EVENTS, Boolean.toString(_showEvents));
            changes.put(PROP_HIDE_LEGEND, Boolean.toString(_graphHideLegend));
            // property is "hide" semantics
            changes.put(PROP_HIDE_RESTARTS, Boolean.toString(_graphHideRestarts));
            changes.put(GraphListener.PROP_PERSISTENT, Boolean.toString(_persistent));
            changes.put(PROP_GLOW, Boolean.toString(_graphGlow));
            changes.put(PROP_SMOOTH, Boolean.toString(_graphSmooth));
            changes.put(PROP_FILL, Boolean.toString(_graphFill));
            changes.put(PROP_COMBINE, Boolean.toString(_graphCombine));
            changes.put(PROP_UTC, Boolean.toString(_useUtc));
            boolean warn = _persistent != _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT);
            _context.router().saveConfig(changes, null);
            addFormNotice(_t("Graph settings saved") + ".", true);
            if (warn) {
                addFormError(_t("Restart required to take effect"));
            }
        } else {
            addFormNotice(_t("Graph settings unchanged") + ".");
        }
    }

    /**
     * Orders graphs for display: "Router" group first, then remaining groups
     * alphabetically, then by stat name and period.
     */
    private static class AlphaComparator implements Comparator<GraphListener>, Serializable {
        /**
         * compare.
         */
        @Override
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
