package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.Messages;
import net.i2p.stat.Frequency;
import net.i2p.stat.FrequencyStat;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Dump the stats to the web admin interface
 */
public class StatsGenerator {
    private final RouterContext _context;

    public StatsGenerator(RouterContext context) {
        _context = context;
    }

    public void generateStatsPage(Writer out, boolean showAll) throws IOException {
        StringBuilder buf = new StringBuilder(16*1024);

        buf.append("<div class=\"confignav\">");

        Map<String, SortedSet<String>> unsorted = _context.statManager().getStatsByGroup();
        Map<String, Set<String>> groups = new TreeMap<String, Set<String>>(new AlphaComparator());
        groups.putAll(unsorted);

        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            String group = entry.getKey();
            Set<String> stats = entry.getValue();
            buf.append("<label class=\"togglestat tab");
//            if (group.equals(_t("Router")))
//                buf.append(" tab2");
            buf.append("\" id=\"");
            buf.append(group);
            buf.append("\" for=\"toggle_");
            buf.append(group);
            buf.append("\">");
            buf.append(_t(group));
            buf.append("</label>");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("</div>");

        buf.append("<div class=\"joblog\">\n");
        buf.append("<p id=\"gatherstats\">");
        buf.append(_t("Statistics gathered during this router's uptime")).append(" (");
        long uptime = _context.router().getUptime();
        buf.append(DataHelper.formatDuration2(uptime));
        buf.append(").  ").append( _t("The data gathered is quantized over a 1 minute period, so should just be used as an estimate."));
        buf.append(' ').append( _t("These statistics are primarily used for development and debugging."));
        buf.append(' ').append("<a href=\"/configstats\">[").append(_t("Configure")).append("]</a>");
        buf.append("</p>");

/**
        buf.append("<form action=\"\"><b>");
        buf.append(_t("Jump to section")).append(":</b> <select name=\"go\" onChange='location.href=this.value'>");
        out.write(buf.toString());
        buf.setLength(0);

        Map<String, SortedSet<String>> unsorted = _context.statManager().getStatsByGroup();
        Map<String, Set<String>> groups = new TreeMap<String, Set<String>>(new AlphaComparator());
        groups.putAll(unsorted);
        for (String group : groups.keySet()) {
            buf.append("<option value=\"#").append(group).append("\">");
            buf.append(_t(group)).append("</option>\n");
            // let's just do the groups
            //Set stats = (Set)entry.getValue();
            //for (Iterator statIter = stats.iterator(); statIter.hasNext(); ) {
            //    String stat = (String)statIter.next();
            //    buf.append("<option value=\"/stats.jsp#");
            //    buf.append(stat);
            //    buf.append("\">...");
            //    buf.append(stat);
            //    buf.append("</option>\n");
            //}
            //out.write(buf.toString());
            //buf.setLength(0);
        }
        buf.append("</select> <input type=\"submit\" value=\"").append(_t("GO")).append("\" />");
        buf.append("</form>");
**/

//        buf.append("<script charset=\"utf-8\" type=\"text/javascript\" src=\"/js/clearSelected.js\"></script>");

        out.write(buf.toString());
        buf.setLength(0);

        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            String group = entry.getKey();
            Set<String> stats = entry.getValue();
            buf.append("<input name=\"statgroup\" type=\"radio\" class=\"toggle_input\" id=\"toggle_").append(group)
               .append("\"");
            if (group.equals(_t("Router")))
                buf.append(" checked");
//            buf.append(" onclick='clearSelected();document.getElementById(\"").append(group).append("\").classList.add(\"tab2\")'>\n");
            buf.append(" hidden>\n");
            buf.append("<h3>").append(group).append("</h3>\n");
            buf.append("<ul class=\"statlist\">");
            out.write(buf.toString());
            buf.setLength(0);
            for (String stat : stats) {
                buf.append("<li class=\"statsName\" id=\"");
                buf.append(stat);
                buf.append("\"><b>");
                buf.append(stat);
                buf.append("</b> ");
                if (_context.statManager().isFrequency(stat))
                    renderFrequency(stat, buf);
                else
                    renderRate(stat, buf, showAll);
                out.write(buf.toString());
                buf.setLength(0);
            }
            out.write("</ul><br>\n");
        }
        out.write("</div>");
        out.flush();
    }

    private void renderFrequency(String name, StringBuilder buf) {
        FrequencyStat freq = _context.statManager().getFrequency(name);
        buf.append("<i>");
        buf.append(freq.getDescription());
        buf.append("</i><br>");
        if (freq.getEventCount() <= 0) {
            buf.append("<ul><li class=\"noevents\">").append(_t("No lifetime events")).append("</li></ul>\n");
            return;
        }
        long uptime = _context.router().getUptime();
        long periods[] = freq.getPeriods();
        Arrays.sort(periods);
        buf.append("<ul>");
        for (int i = 0; i < periods.length; i++) {
            if (periods[i] > uptime)
                break;
            buf.append("<li>");
            renderPeriod(buf, periods[i], _t("frequency"));
            Frequency curFreq = freq.getFrequency(periods[i]);
            buf.append(DataHelper.formatDuration2(Math.round(curFreq.getAverageInterval())));
            buf.append(" &bullet; ");
            buf.append("<span class=\"nowrap\">");
            buf.append(_t("Rolling average events per period"));
            buf.append(": <span class=\"statvalue\">");
            buf.append(num(curFreq.getAverageEventsPerPeriod()));
            buf.append("</span> &bullet; ");
            buf.append("</span> <span class=\"nowrap\">");
            buf.append(_t("Highest events per period"));
            buf.append(": <span class=\"statvalue\">");
            buf.append(num(curFreq.getMaxAverageEventsPerPeriod()));
            //if (showAll && (curFreq.getMaxAverageEventsPerPeriod() > 0) && (curFreq.getAverageEventsPerPeriod() > 0) ) {
            //    buf.append("(current is ");
            //    buf.append(pct(curFreq.getAverageEventsPerPeriod()/curFreq.getMaxAverageEventsPerPeriod()));
            //    buf.append(" of max)");
            //}
            //buf.append(" <i>avg interval between updates:</i> (").append(num(curFreq.getAverageInterval())).append("ms, min ");
            //buf.append(num(curFreq.getMinAverageInterval())).append("ms)");
            buf.append("</span></span> <span class=\"bullet\">&bullet;</span> <br></span><span class=\"nowrap\"><span class=\"statvalue\">");
            buf.append(_t("Lifetime average events per period")).append(": ");
            buf.append(num(curFreq.getStrictAverageEventsPerPeriod()));
            buf.append("</span></span></li>\n");
        }
        // Display the strict average
        buf.append("<li><b>").append(_t("Lifetime average frequency")).append(":</b> <span class=\"statvalue\">");
        buf.append(DataHelper.formatDuration2(freq.getFrequency()));
        buf.append("</span> (");
        buf.append(ngettext("{0} event", "{0} events", (int) freq.getEventCount()));
        buf.append(")</li></ul><br>\n");
    }

    private void renderRate(String name, StringBuilder buf, boolean showAll) {
        RateStat rate = _context.statManager().getRate(name);
        String d = rate.getDescription();
        if (! "".equals(d)) {
            buf.append("<span class=\"statsLongName\"><i>");
            buf.append(d);
            buf.append("</i></span><br>");
        }
        if (rate.getLifetimeEventCount() <= 0) {
            buf.append("<ul><li class=\"noevents\">").append(_t("No lifetime events")).append("</li></ul>\n");
            return;
        }
        long now = _context.clock().now();
        long periods[] = rate.getPeriods();
        Arrays.sort(periods);
        buf.append("<ul>");
        for (int i = 0; i < periods.length; i++) {
            Rate curRate = rate.getRate(periods[i]);
            if (curRate.getLastCoalesceDate() <= curRate.getCreationDate())
                break;
            buf.append("<li>");
            renderPeriod(buf, periods[i], _t("rate"));
            if (curRate.getLastEventCount() > 0) {
                buf.append("<span class=\"nowrap\">");
                buf.append(_t("Average")).append(": <span class=\"statvalue\">");
                buf.append(num(curRate.getAverageValue()));
                    buf.append("</span> &bullet; ");
                buf.append(_t("Highest average"));
                buf.append(": <span class=\"statvalue\">");
                buf.append(num(curRate.getExtremeAverageValue()));
                buf.append("</span>");

                // This is rarely interesting
                // Don't bother to translate
                if (showAll) {
                    buf.append(" &bullet; ");
                    buf.append("Highest total in a period: <span class=\"statvalue\">");
                    buf.append(num(curRate.getExtremeTotalValue()));
                    buf.append("</span>");
                    }

                // Saturation stats, which nobody understands, even when it isn't meaningless
                // Don't bother to translate
                if (showAll && curRate.getLifetimeTotalEventTime() > 0) {
                    buf.append(" <br><span class=\"nowrap\">");
                    buf.append("Saturation: <span class=\"statvalue\">");
                    buf.append(pct(curRate.getLastEventSaturation()));
                    buf.append("</span> &bullet; Saturated limit: <span class=\"statvalue\">");
                    buf.append(num(curRate.getLastSaturationLimit()));
                    buf.append("</span> &bullet; Peak saturation: <span class=\"statvalue\">");
                    buf.append(pct(curRate.getExtremeEventSaturation()));
                    buf.append("</span> &bullet; Peak saturated limit: <span class=\"statvalue\">");
                    buf.append(num(curRate.getExtremeSaturationLimit()));
                    buf.append("</span></span>");
                }

            long numPeriods = curRate.getLifetimePeriods();
            if (numPeriods > 0) {
                double avgFrequency = curRate.getLifetimeEventCount() / (double)numPeriods;
                buf.append(" &bullet; ").append(_t("Average event count")).append(": <span class=\"statvalue\">");
                buf.append(num(avgFrequency));
                buf.append("</span></span> <br><span class=\"nowrap\">");
                buf.append(_t("Events in peak period")).append(": <span class=\"statvalue\">");
                // This isn't really the highest event count, but the event count during the period with the highest total value.
                buf.append(curRate.getExtremeEventCount());
                buf.append("</span> &bullet; ");
                } else {
                    buf.append("</span>");
                }

                buf.append(_t("Events in this period")).append(" (").append(_t("ended {0} ago", DataHelper.formatDuration2(now - curRate.getLastCoalesceDate())));
                buf.append("): <span class=\"statvalue\">").append((int)curRate.getLastEventCount()).append("</span>");
                buf.append("</span>");
            } else {
                buf.append(" <i>").append(_t("No events")).append(" </i>");
            }

            if (curRate.getSummaryListener() != null) {
                buf.append("<br><span class=\"statsViewGraphs\"><a class=\"graphstat\" href=\"graph?stat=").append(name)
                   .append('.').append(periods[i]);
                buf.append("&amp;w=600&amp;h=200\">").append(_t("Graph Data")).append("</a> ");
                buf.append(" <a class=\"graphstat\" href=\"graph?stat=").append(name)
                   .append('.').append(periods[i]);
                buf.append("&amp;w=600&amp;h=200&amp;showEvents=true\">").append(_t("Graph Event Count")).append("</a> ");
                // This can really blow up your browser if you click on it
                // added download attribute to force download instead of loading inline
                buf.append(" <a class=\"graphstat\" href=\"viewstat.jsp?stat=").append(name);
                buf.append("&amp;period=").append(periods[i]);
                buf.append("&amp;format=xml\" download=\"graphdata.xml\">").append(_t("Export Data as XML")).append("</a>");
                buf.append("</span>");
            }
            buf.append("</li>\n");
        }
        // Display the strict average
        buf.append("<li><b>").append(_t("Lifetime average value").replace(" value", "")).append(":</b> <span class=\"statvalue\">");
        buf.append(num(rate.getLifetimeAverageValue()));
        buf.append("</span> (");
        buf.append(ngettext("1 event", "{0} events", (int) rate.getLifetimeEventCount()));
        buf.append(")<br></li>" +
                   "</ul>" +
                   "<br>\n");
    }

    private static void renderPeriod(StringBuilder buf, long period, String name) {
        buf.append("<b>");
        buf.append(DataHelper.formatDuration2(period));
        buf.append(" ");
        buf.append(name);
        buf.append(":</b> ");
    }

    private final static DecimalFormat _fmt = new DecimalFormat("###,##0.0##");
    private final static String num(double num) { synchronized (_fmt) { return _fmt.format(num); } }

    private final static DecimalFormat _pct = new DecimalFormat("#0.00%");
    private final static String pct(double num) { synchronized (_pct) { return _pct.format(num); } }

    /**
     *  Translated sort
     *  Inner class, can't be Serializable
     *  @since 0.9.3
     */
    private class AlphaComparator implements Comparator<String> {
        public int compare(String lhs, String rhs) {
            String lname = _t(lhs);
            String rname = _t(rhs);

            // put the router sections at the top of the page
            boolean lrouter = lname.equals("Router");
            boolean rrouter = rname.equals("Router");
            if (lrouter && !rrouter)
                return -1;
            if (rrouter && !lrouter)
                return 1;
            lrouter = lname.startsWith("Router");
            rrouter = rname.startsWith("Router");
            if (lrouter && !rrouter)
                return -1;
            if (rrouter && !lrouter)
                return 1;

            return Collator.getInstance().compare(lname, rname);
        }
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** translate a string */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /** translate a string */
    private String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
    }
}
