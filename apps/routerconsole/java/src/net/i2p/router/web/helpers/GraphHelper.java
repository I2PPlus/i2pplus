package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.data.DataHelper;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.FormHandler;
import static net.i2p.router.web.GraphConstants.*;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.NavHelper;
import net.i2p.router.web.StatSummarizer;
import net.i2p.router.web.SummaryListener;
import net.i2p.stat.Rate;
import net.i2p.util.SystemVersion;

import java.util.Date;

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
    private static final String PROP_LEGEND = "routerconsole.graphHideLegend";
    private static final String PROP_HIDPI = "routerconsole.graphHiDpi";
//    private static final int DEFAULT_REFRESH = 5*60;
    private static final int DEFAULT_REFRESH = 1*60;
    private static final int DEFAULT_PERIODS = 60;
    private static final boolean DEFAULT_LEGEND = false;
    private static final boolean DEFAULT_HIDPI = false;
//    private static final int MIN_X = 200;
    private static final int MIN_X = 160;
//    private static final int MIN_Y = 60;
    private static final int MIN_Y = 40;
    private static final int MIN_C = 20;
    private static final int MAX_C = SummaryListener.MAX_ROWS;
    private static final int MIN_REFRESH = 15;

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
     *  This must be output in the jsp since <meta> must be in the <head>
     *  @since 0.8.7
     */
    public String getRefreshMeta() {
        if (_refreshDelaySeconds <= 8 ||
            ConfigRestartBean.getRestartTimeRemaining() < (1000 * (_refreshDelaySeconds + 30)))
            return "";
        // shorten the refresh by 3 seconds so we beat the iframe
        return "<noscript><meta http-equiv=\"refresh\" content=\"" + (_refreshDelaySeconds - 3) + "\"></noscript>";
    }

    public int getRefreshValue() {
        return _refreshDelaySeconds;
    }

    public boolean getGraphHiDpi() {
        return _context.getBooleanProperty(PROP_HIDPI);
    }

    public void setPeriodCount(String str) {
        setC(str);
    }

    /** @since 0.9 */
    public void setE(String str) {
        try {
            _end = Math.max(0, Integer.parseInt(str));
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9 shorter parameter */
    public void setC(String str) {
        try {
            _periodCount = Math.max(MIN_C, Math.min(Integer.parseInt(str), MAX_C));
        } catch (NumberFormatException nfe) {}
    }

    public void setShowEvents(String b) { _showEvents = !"false".equals(b); }

    public void setHeight(String str) {
        setH(str);
    }

    /** @since 0.9 shorter parameter */
    public void setH(String str) {
        try {
            _height = Math.max(MIN_Y, Math.min(Integer.parseInt(str), MAX_Y));
        } catch (NumberFormatException nfe) {}
    }

    public void setWidth(String str) {
        setW(str);
    }

    /** @since 0.9 shorter parameter */
    public void setW(String str) {
        try {
            _width = Math.max(MIN_X, Math.min(Integer.parseInt(str), MAX_X));
        } catch (NumberFormatException nfe) {}
    }

    public void setRefreshDelay(String str) {
        try {
            int rds = Integer.parseInt(str);
            if (rds > 0)
                _refreshDelaySeconds = Math.max(rds, MIN_REFRESH);
            else
                _refreshDelaySeconds = -1;
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.8.7 */
    public void setPersistent(String foo) { _persistent = true; }

    /** @since 0.9.32 */
    public void setHideLegend(String foo) { _graphHideLegend = true; }

    /**
     *  For single stat page
     *  @since 0.9
     */
    public void setStat(String stat) {
        _stat = stat;
    }

    public String getImages() {
        StatSummarizer ss = StatSummarizer.instance(_context);
        if (ss == null)
            return "";
        try {
            List<SummaryListener> listeners = ss.getListeners();
            TreeSet<SummaryListener> ordered = new TreeSet<SummaryListener>(new AlphaComparator());
            ordered.addAll(listeners);

            // go to some trouble to see if we have the data for the combined bw graph
            boolean hasTx = false;
            boolean hasRx = false;
            for (SummaryListener lsnr : ordered) {
                String title = lsnr.getRate().getRateStat().getName();
                if (title.equals("bw.sendRate")) hasTx = true;
                else if (title.equals("bw.recvRate")) hasRx = true;
            }
            boolean hideLegend = _context.getProperty(PROP_LEGEND, DEFAULT_LEGEND);
            boolean hiDPI = _context.getProperty(PROP_HIDPI, DEFAULT_HIDPI);

            if (hasTx && hasRx && !_showEvents) {
                // remove individual tx/rx graphs if displaying combined
                for (Iterator<SummaryListener> iter = ordered.iterator(); iter.hasNext(); ) {
                    SummaryListener lsnr = iter.next();
                    String title = lsnr.getRate().getRateStat().getName();
                    if (title.equals("bw.sendRate") || title.equals("bw.recvRate"))
                        iter.remove();
                }
                if (hiDPI)
                    _out.write("<span class=\"graphContainer\" id=\"hidpi\">");
                else
                    _out.write("<span class=\"graphContainer\">");
                _out.write("<a href=\"graph?stat=bw.combined"
                           + "&amp;c=" + (3 * _periodCount )
                           + "&amp;w=1000&amp;h=280"
                           //+ "&amp;w=" + (3 * _width)
                           //+ "&amp;h=" + (3 * _height)
                           + "\">");
                String title = _t("Combined bandwidth graph");
                if (hiDPI) {
                    _out.write("<img class=\"statimage\""
                               + " src=\"viewstat.jsp?stat=bw.combined"
                               + "&amp;periodCount=" + _periodCount
                               + "&amp;width=" + (_width * 2));
                } else {
                    _out.write("<img class=\"statimage\""
                               + " src=\"viewstat.jsp?stat=bw.combined"
                               + "&amp;periodCount=" + _periodCount
                               + "&amp;width=" + _width);
                }
                if (!hideLegend) {
                    if (hiDPI)
                        // bw.combined graph has two entries in its legend
                        // -26 pixels equalizes its height with the other images (standard dpi)
                        _out.write("&amp;height=" + ((_height * 2) - 52));
                    else
                        _out.write("&amp;height=" + (_height - 26));
                } else {
                    if (hiDPI)
                        // no legend, no height difference needed
                        _out.write("&amp;height=" + (_height * 2));
                    else
                        _out.write("&amp;height=" + (_height));
                }
                _out.write("&amp;hideLegend=" + hideLegend + "&amp;time=" + System.currentTimeMillis()
                           + "\" alt=\"" + title + "\" title=\"" + title + "\"></a></span>\n");
            }

            for (SummaryListener lsnr : ordered) {
                Rate r = lsnr.getRate();
                // e.g. "statname for 60m"
                String title = _t("{0} for {1}", r.getRateStat().getName(), DataHelper.formatDuration2(_periodCount * r.getPeriod()));
                if (hiDPI)
                    _out.write("<span class=\"graphContainer\" id=\"hidpi\">");
                else
                    _out.write("<span class=\"graphContainer\">");
                _out.write("<a href=\"graph?stat="
                           + r.getRateStat().getName()
                           + '.' + r.getPeriod()
                           + "&amp;c=" + (3 * _periodCount ));
                           // let's set width & height predictably and reduce chance of downscaling
                if (hiDPI)
                    _out.write("&amp;w=2000&amp;h=560");
                else
                    _out.write("&amp;w=1000&amp;h=280");
                           //+ "&amp;w=" + (3 * _width)
                           //+ "&amp;h=" + (3 * _height)
                _out.write((_showEvents ? "&amp;showEvents=1" : "")
                           + "\">");
                _out.write("<img class=\"statimage\" border=\"0\""
                           + " src=\"viewstat.jsp?stat="
                           + r.getRateStat().getName()
                           + "&amp;showEvents=" + _showEvents
                           + "&amp;period=" + r.getPeriod()
                           + "&amp;periodCount=" + _periodCount);
                if (hiDPI) {
                    _out.write("&amp;width=" + (_width * 2)
                           + "&amp;height=" + (_height * 2));
                } else {
                    _out.write("&amp;width=" + _width
                           + "&amp;height=" + _height);
                }
                _out.write("&amp;hideLegend=" + hideLegend
                           + "&amp;time=" + System.currentTimeMillis()
                           + "\" alt=\"" + title
                           + "\" title=\"" + title + "\"></a></span>\n");
            }

            // FIXME jrobin doesn't support setting the timezone, will have to mod TimeAxis.java
            // 0.9.1 - all graphs currently state UTC on them, so this text blurb is unnecessary,
            //_out.write("<p><i>" + _t("All times are UTC.") + "</i></p>\n");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  For single stat page;
     *  stat = "bw.combined" treated specially
     *
     *  @since 0.9
     */
    public String getSingleStat() {
        StatSummarizer ss = StatSummarizer.instance(_context);
        if (ss == null)
            return "";
        try {
            if (_stat == null) {
                _out.write("No stat specified");
                return "";
            }
            long period;
            String name, displayName;
            if (_stat.equals("bw.combined")) {
                period = 60000;
                name = _stat;
                displayName = ("[" + _t("Router") + "] " + _t("Bandwidth usage").replace("usage", "Usage"));
            } else {
                Set<Rate> rates = ss.parseSpecs(_stat);
                if (rates.size() != 1) {
                    _out.write("<p class=\"infohelp\">Graphs not enabled for " + _stat + " or the tunnel or service isn't currently running.</p>");
                    return "";
                }
                Rate r = rates.iterator().next();
                period = r.getPeriod();
                name = r.getRateStat().getName();
                displayName = name;
            }
            _out.write("<h3 id=\"graphinfo\">");
            _out.write(_t("{0} for {1}", displayName, DataHelper.formatDuration2(_periodCount * period)));
            if (_end > 0)
                _out.write(' ' + _t("ending {0} ago", DataHelper.formatDuration2(_end * period)));

            boolean hideLegend = _context.getProperty(PROP_LEGEND, DEFAULT_LEGEND);
            boolean hiDPI = _context.getProperty(PROP_HIDPI, DEFAULT_HIDPI);
            _out.write("&nbsp;<a href=\"/graphs\">" + _t("Return to main graphs page") + "</a></h3>\n"
                       + "<div class=\"graphspanel\">\n");
            if (hiDPI)
                _out.write("<span class=\"graphContainer\" id=\"hidpi\">");
            else
                _out.write("<span class=\"graphContainer\">");
            _out.write("<a class=\"singlegraph\" href=\"/graphs\" title=\""
                       + _t("Return to main graphs page") + "\"><img class=\"statimage\" id=\"graphSingle\" border=\"0\""
                       + " src=\"viewstat.jsp?stat="
                       + name
                       + "&amp;showEvents=" + _showEvents
                       + "&amp;period=" + period
                       + "&amp;periodCount=" + _periodCount
                       + "&amp;end=" + _end);
            if (hiDPI) {
                _out.write("&amp;width=" + (_width * 2)
                       + "&amp;height=" + (_height * 2));
            } else {
                _out.write("&amp;width=" + _width
                       + "&amp;height=" + _height);
            }
            _out.write("&amp;hideLegend=" + hideLegend
                       + "&amp;time=" + System.currentTimeMillis()
                       + "\"></a></span>\n</div><p id=\"graphopts\">\n");

            if (_width < MAX_X && _height < MAX_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height * 3 / 2));
                _out.write(_t("Larger"));
                _out.write("</a> - ");
            }

            if (_width > MIN_X && _height > MIN_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height * 2 / 3));
                _out.write(_t("Smaller"));
                _out.write("</a> - ");
            }

            if (_height < MAX_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width, _height * 3 / 2));
                _out.write(_t("Taller"));
                _out.write("</a> - ");
            }

            if (_height > MIN_Y) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width, _height * 2 / 3));
                _out.write(_t("Shorter"));
                _out.write("</a> - ");
            }

            if (_width < MAX_X) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 3 / 2, _height));
                _out.write(_t("Wider"));
                _out.write("</a> - ");
            }

            if (_width > MIN_X) {
                _out.write(link(_stat, _showEvents, _periodCount, _end, _width * 2 / 3, _height));
                _out.write(_t("Narrower"));
                _out.write("</a>");
            }

            _out.write("<br>");
            if (_periodCount < MAX_C) {
                _out.write(link(_stat, _showEvents, _periodCount * 2, _end, _width, _height));
                _out.write(_t("Larger interval"));
                _out.write("</a> - ");
            }

            if (_periodCount > MIN_C) {
                _out.write(link(_stat, _showEvents, _periodCount / 2, _end, _width, _height));
                _out.write(_t("Smaller interval"));
                _out.write("</a> - ");
            }

            if (_periodCount < MAX_C) {
                _out.write(link(_stat, _showEvents, _periodCount, _end + _periodCount, _width, _height));
                _out.write(_t("Previous interval"));
                _out.write("</a>");
            }

            if (_end > 0) {
                int end = _end - _periodCount;
                if (end <= 0)
                    end = 0;
                if (_periodCount < MAX_C)
                    _out.write(" - ");
                _out.write(link(_stat, _showEvents, _periodCount, end, _width, _height));
                _out.write(_t("Next interval"));
                _out.write("</a> ");
            }

            _out.write(" - ");
            _out.write(link(_stat, !_showEvents, _periodCount, _end, _width, _height));
            if (!_stat.equals("bw.combined"))
                _out.write(_showEvents ? _t("Plot averages") : _t("plot events"));
            _out.write("</a>");

            _out.write("</p><p><i>" + _t("All times are UTC.") + "</i></p>\n");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /** @since 0.9 */
    private static String link(String stat, boolean showEvents,
                               int periodCount, int end,
                               int width, int height) {
        return
               "<a href=\"graph?stat="
               + stat
               + "&amp;c=" + periodCount
               + "&amp;w=" + width
               + "&amp;h=" + height
               + (end > 0 ? "&amp;e=" + end : "")
               + (showEvents ? "&amp;showEvents=1" : "")
               + "\">";
    }

    private static final int[] times = { 15, 30, 60, 2*60, 5*60, 10*60, 30*60, 60*60, -1 };

    public String getForm() {
        StatSummarizer ss = StatSummarizer.instance(_context);
        if (ss == null)
            return "";
        // too hard to use the standard formhandler.jsi / FormHandler.java session nonces
        // since graphs.jsp needs the refresh value in its <head>.
        // So just use the "shared/console nonce".
        String nonce = CSSHelper.getNonce();
        try {
            _out.write("<br><h3 id=\"graphdisplay\">" + _t("Configure Graph Display") + " <a href=\"configstats\">" + _t("Select Stats") + "</a></h3>");
            _out.write("<form action=\"/updategraphs\" method=\"POST\">\n" +
                       "<table>\n<tr><td><div class=\"optionlist\">\n<input type=\"hidden\" name=\"action\" value=\"save\">\n" +
                       "<input type=\"hidden\" name=\"nonce\" value=\"" + nonce + "\" >\n");
            _out.write("<span class=\"nowrap\" title=\"" +
                       _t("Note: Dimensions are for graph only (excludes title, labels and legend).") +"\"><b>");
            _out.write(_t("Graph size") + ":</b>&nbsp; <input size=\"4\" style=\"text-align: right;\" type=\"text\" name=\"width\" value=\"" + _width
                       + "\">" + _t("pixels wide") + "&nbsp;&nbsp;&nbsp;<input size=\"4\" style=\"text-align: right;\" type=\"text\" name=\"height\" value=\"" + _height
                       + "\">" + _t("pixels high") + "</span><br>\n<span class=\"nowrap\">\n<b>");
            _out.write(_t("Display period") + ":</b> <input size=\"5\" style=\"text-align: right;\" type=\"text\" name=\"periodCount\" value=\""
                       + _periodCount + "\">" + _t("minutes") + "</span><br>\n<span class=\"nowrap\">\n<b>");
            _out.write(_t("Refresh delay") + ":</b> <select name=\"refreshDelay\">");
            for (int i = 0; i < times.length; i++) {
                _out.write("<option value=\"");
                _out.write(Integer.toString(times[i]));
                _out.write('"');
                if (times[i] == _refreshDelaySeconds)
                    _out.write(HelperBase.SELECTED);
                _out.write('>');
                if (times[i] > 0)
                    _out.write(DataHelper.formatDuration2(times[i] * 1000));
                else
                    _out.write(_t("Never"));
                _out.write("</option>\n");
            }
            _out.write("</select></span><br>\n<span class=\"nowrap\">\n<b>");

            _out.write(_t("Plot type") + ":</b> ");
            _out.write("<label><input type=\"radio\" class=\"optbox\" name=\"showEvents\" value=\"false\" " + (_showEvents ? "" : HelperBase.CHECKED) + ">" + _t("Averages") + "</label>&nbsp;&nbsp;&nbsp;");
            _out.write ("<label><input type=\"radio\" class=\"optbox\" name=\"showEvents\" value=\"true\" "+ (_showEvents ? HelperBase.CHECKED : "") + ">" + _t("Events") + "</label></span><br>\n<span class=\"nowrap\">\n<b>");
            _out.write(_t("Hide legend") + ":</b> ");
            _out.write("<label><input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"hideLegend\"");
            boolean hideLegend = _context.getProperty(PROP_LEGEND, DEFAULT_LEGEND);
            if (hideLegend)
                _out.write(HelperBase.CHECKED);
            _out.write(">" + _t("Do not show legend on graphs") + "</label></span><br><span class=\"nowrap\">\n<b>");
            _out.write(_t("Persistence") +
                       ":</b> <label><input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"persistent\"");
            boolean persistent = _context.getBooleanPropertyDefaultTrue(SummaryListener.PROP_PERSISTENT);
            if (persistent)
                _out.write(HelperBase.CHECKED);
            _out.write(">" + _t("Store graph data on disk") + "</label></span>\n</div>\n</td></tr>\n</table>\n" +
                       "<hr>\n<div class=\"formaction\" id=\"graphing\"><input type=\"submit\" class=\"accept\" value=\"" + _t("Save settings and redraw graphs") + "\"></div>\n</form>\n");
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    /**
     *  We have to do this here because processForm() isn't called unless the nonces are good
     *  @since 0.8.7
     */
    @Override
    public String getAllMessages() {
        if (StatSummarizer.isDisabled(_context)) {
            addFormError("Either the router hasn't initalized yet, or graph generation is not supported with this JVM or OS.");
            addFormNotice("JVM: " + System.getProperty("java.vendor") + ' ' +
                         System.getProperty("java.version") + " (" +
                         System.getProperty("java.runtime.name") + ' ' +
                         System.getProperty("java.runtime.version") + ')');
            addFormNotice("OS: " + System.getProperty("os.name") + ' ' +
                           System.getProperty("os.arch") + ' ' +
                           System.getProperty("os.version"));
            if (!SystemVersion.isMac() && !SystemVersion.isWindows())
                addFormNotice("Installing the fonts-dejavu package or the DroidSans and DroidSansMono fonts and then restarting I2P may resolve the issue.");
            addFormNotice("Check logs for more information.");
            if (_context.getProperty(PROP_REFRESH, 0) >= 0) {
                // force no refresh, save silently
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
        if ("save".equals(_action))
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
            _graphHideLegend != _context.getProperty(PROP_LEGEND, DEFAULT_LEGEND) ||
            _persistent != _context.getBooleanPropertyDefaultTrue(SummaryListener.PROP_PERSISTENT)) {
            Map<String, String> changes = new HashMap<String, String>();
            changes.put(PROP_X, Integer.toString(_width));
            changes.put(PROP_Y, Integer.toString(_height));
            changes.put(PROP_PERIODS, Integer.toString(_periodCount));
            changes.put(PROP_REFRESH, Integer.toString(_refreshDelaySeconds));
            changes.put(PROP_EVENTS, Boolean.toString(_showEvents));
            changes.put(PROP_LEGEND, Boolean.toString(_graphHideLegend));
            changes.put(SummaryListener.PROP_PERSISTENT, Boolean.toString(_persistent));
            _context.router().saveConfig(changes, null);
            addFormNotice(_t("Graph settings saved") + ".");
        } else {
            addFormNotice(_t("Graph settings unchanged") + ".");
        }
    }

    private static class AlphaComparator implements Comparator<SummaryListener>, Serializable {
        public int compare(SummaryListener l, SummaryListener r) {
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
