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

    public StatsGenerator(RouterContext context) {_context = context;}

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
            buf.append("\" id=\"");
            buf.append(group.replace(" ", "_").replace("[", "").replace("]", ""));
            buf.append("\" for=\"toggle_");
            buf.append(group.replace(" ", "_").replace("[", "").replace("]", ""));
            buf.append("\">");
            buf.append(_t(group));
            buf.append("</label>");
            out.write(buf.toString());
            buf.setLength(0);
        }
        buf.append("</div>");
        buf.append("<p id=gatherstats>");
        buf.append(_t("Statistics gathered during this router's uptime")).append(" (");
        long uptime = _context.router().getUptime();
        buf.append(DataHelper.formatDuration2(uptime));
        buf.append(").  ").append( _t("The data gathered is quantized over a 1 minute period, so should just be used as an estimate."));
        buf.append(' ').append( _t("These statistics are primarily used for development and debugging."));
        buf.append(' ').append("<a href=\"/configstats\">[").append(_t("Configure")).append("]</a>");
        buf.append("</p>");
        buf.append("<div id=statsWrap>\n");
        out.write(buf.toString());
        buf.setLength(0);

        for (Map.Entry<String, Set<String>> entry : groups.entrySet()) {
            String group = entry.getKey();
            Set<String> stats = entry.getValue();
            buf.append("<input name=\"statgroup\" type=radio class=toggle_input id=\"toggle_")
               .append(group.replace(" ", "_").replace("[", "").replace("]", "")).append("\"");
            if (group.equals(_t("Router"))) {buf.append(" checked=checked");}
            buf.append(" hidden>\n");
            buf.append("<h3>").append(group).append("</h3>\n");
            buf.append("<ul class=statlist>");
            out.write(buf.toString());
            buf.setLength(0);
            for (String stat : stats) {
                buf.append("<li class=statsName id=\"").append(stat.replace(" ", "_").replace("[", "").replace("]", ""))
                   .append("\"><b>").append(stat).append("</b> ");
                if (_context.statManager().isFrequency(stat)) {renderFrequency(stat, buf);}
                else {renderRate(stat, buf, showAll);}
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
            buf.append("<ul><li class=noevents>").append(_t("No lifetime events")).append("</li></ul>\n");
            return;
        }
        long uptime = _context.router().getUptime();
        long periods[] = freq.getPeriods();
        Arrays.sort(periods);
        buf.append("<ul>");
        for (int i = 0; i < periods.length; i++) {
            if (periods[i] > uptime) {break;}
            buf.append("<li>");
            renderPeriod(buf, periods[i], _t("frequency"));
            Frequency curFreq = freq.getFrequency(periods[i]);
            buf.append(DataHelper.formatDuration2(Math.round(curFreq.getAverageInterval())));
            buf.append(" &bullet; <span class=nowrap>").append(_t("Rolling average events per period"))
               .append(": <span class=statvalue>").append(num(curFreq.getAverageEventsPerPeriod())).append("</span> &bullet; ")
               .append("</span> <span class=nowrap>").append(_t("Highest events per period")).append(": <span class=statvalue>")
               .append(num(curFreq.getMaxAverageEventsPerPeriod()))
               .append("</span></span> <span class=bullet>&bullet;</span> <br><span class=nowrap><span class=statvalue>")
               .append(_t("Lifetime average events per period")).append(": ")
               .append(num(curFreq.getStrictAverageEventsPerPeriod()))
               .append("</span></span></li>\n");
        }
        // Display the strict average
        buf.append("<li><b>").append(_t("Lifetime average frequency")).append(":</b> <span class=statvalue>")
           .append(DataHelper.formatDuration2(freq.getFrequency())).append("</span> (")
           .append(ngettext("{0} event", "{0} events", (int) freq.getEventCount()))
           .append(")</li></ul><br>\n");
    }

    private void renderRate(String name, StringBuilder buf, boolean showAll) {
        RateStat rate = _context.statManager().getRate(name);
        String d = rate.getDescription();
        if (! "".equals(d)) {buf.append("<span class=statsLongName><i>").append(d).append("</i></span><br>");}
        if (rate.getLifetimeEventCount() <= 0) {
            buf.append("<ul><li class=noevents>").append(_t("No lifetime events")).append("</li></ul>\n");
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
                buf.append("<span class=nowrap>").append(_t("Average")).append(": <span class=statvalue>")
                   .append(num(curRate.getAverageValue())).append("</span> &bullet; ").append(_t("Highest average"))
                   .append(": <span class=statvalue>").append(num(curRate.getExtremeAverageValue())).append("</span>");

                if (showAll) {
                    buf.append(" &bullet; ").append(_t("Highest total in a period")).append(": <span class=statvalue>")
                       .append(num(curRate.getExtremeTotalValue())).append("</span>");
                }
                buf.append("</span>");

                // Saturation stats, which nobody understands, even when it isn't meaningless
                // Don't bother to translate
                if (showAll && curRate.getLifetimeTotalEventTime() > 0) {
                    buf.append(" <br><span class=nowrap>").append("Saturation: <span class=statvalue>")
                       .append(pct(curRate.getLastEventSaturation()))
                       .append("</span> &bullet; Saturated limit: <span class=statvalue>")
                       .append(num(curRate.getLastSaturationLimit()))
                       .append("</span> &bullet; Peak saturation: <span class=statvalue>")
                       .append(pct(curRate.getExtremeEventSaturation()))
                       .append("</span> &bullet; Peak saturated limit: <span class=statvalue>")
                       .append(num(curRate.getExtremeSaturationLimit()))
                       .append("</span></span>");
                }

            long numPeriods = curRate.getLifetimePeriods();
            if (numPeriods > 0) {
                double avgFrequency = curRate.getLifetimeEventCount() / (double)numPeriods;
                buf.append(" &bullet; ").append(_t("Average event count")).append(": <span class=statvalue>")
                   .append(num(avgFrequency)).append("</span><br><span class=nowrap>")
                   .append(_t("Events in peak period")).append(": <span class=statvalue>")
                   .append(curRate.getExtremeEventCount()) // Not really the highest event count, but the event count during period with highest total value.
                   .append("</span> &bullet; ");
                } else {buf.append("</span>");}

                buf.append(_t("Events in this period"))
                   .append(" (").append(_t("ended {0} ago", DataHelper.formatDuration2(now - curRate.getLastCoalesceDate()))).append("): ")
                   .append("<span class=statvalue>").append((int)curRate.getLastEventCount()).append("</span>").append("</span>");
            } else {buf.append(" <i>").append(_t("No events")).append(" </i>");}

            if (curRate.getSummaryListener() != null) {
                buf.append("<br><span class=statsViewGraphs><a class=graphstat href=\"graph?stat=").append(name.replace(" ", "%20"))
                   .append('.').append(periods[i]).append("&amp;w=600&amp;h=200\">").append(_t("Graph Data")).append("</a> ")
                   .append(" <a class=graphstat href=\"graph?stat=").append(name.replace(" ", "%20")).append('.').append(periods[i])
                   .append("&amp;w=600&amp;h=200&amp;showEvents=true\">").append(_t("Graph Event Count")).append("</a> ")
                   .append(" <a class=graphstat href=\"/viewstat.jsp?stat=").append(name.replace(" ", "%20")).append("&amp;period=").append(periods[i])
                   .append("&amp;format=xml\" download=\"graphdata_").append(name.replace(".", "_").replace("%20", "s")).append(".xml\">")
                   .append(_t("Export Data as XML")).append("</a>").append("</span>");
            }
            buf.append("</li>\n");
        }
        // Display the strict average
        buf.append("<li><b>").append(_t("Lifetime average value").replace(" value", "")).append(":</b> <span class=statvalue>")
           .append(num(rate.getLifetimeAverageValue())).append("</span> (")
           .append(ngettext("1 event", "{0} events", (int) rate.getLifetimeEventCount()))
           .append(")<br></li></ul><br>\n");
    }

    private static void renderPeriod(StringBuilder buf, long period, String name) {
        buf.append("<b>").append(DataHelper.formatDuration2(period)).append(" ").append(name).append(":</b> ");
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

            // put the Router sections at the top of the page
            boolean lrouter = lname.equals("Router");
            boolean rrouter = rname.equals("Router");
            if (lrouter && !rrouter) {return -1;}
            if (rrouter && !lrouter) {return 1;}
            lrouter = lname.startsWith("Router");
            rrouter = rname.startsWith("Router");
            if (lrouter && !rrouter) {return -1;}
            if (rrouter && !lrouter) {return 1;}

            return Collator.getInstance().compare(lname, rname);
        }
    }

    /** translate a string */
    private String _t(String s) {return Messages.getString(s, _context);}

    /** translate a string */
    private String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /** translate a string */
    private String ngettext(String s, String p, int n) {return Messages.getString(n, s, p, _context);}

}
