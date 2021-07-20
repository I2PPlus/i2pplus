package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;
import java.text.Collator;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.peermanager.DBHistory;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.sybil.Analysis;
import net.i2p.router.sybil.Pair;
import net.i2p.router.sybil.PersistSybil;
import net.i2p.router.sybil.Points;
import static net.i2p.router.sybil.Util.biLog2;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.util.HashDistance;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

/**
 *  For debugging only.
 *  Parts may later move to router as a periodic monitor.
 *  Adapted from NetDbRenderer.
 *
 *  @since 0.9.24
 *
 */
public class SybilRenderer {

    private final RouterContext _context;
    private final Log _log;
    private final DecimalFormat fmt = new DecimalFormat("#0.00");

    private static final int PAIRMAX = Analysis.PAIRMAX;
    private static final int MAX = Analysis.MAX;
    private static final double MIN_CLOSE = Analysis.MIN_CLOSE;
    private static final double MIN_DISPLAY_POINTS = 20.0;
    private static final int[] HOURS = { 1, 3, 6, 12, 24, 7*24, 30*24, 0 };
    private static final int[] DAYS = { 2, 7, 30, 90, 365, 0 };

    public SybilRenderer(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(SybilRenderer.class);
    }

    /**
     *   Entry point
     *
     *  @param mode what tab to show
     *  @param date only for mode = 12
     */
    public String getNetDbSummary(Writer out, String nonce, int mode, long date) throws IOException {
        renderRouterInfoHTML(out, nonce, mode, date);
        return "";
    }

    private static class PointsComparator implements Comparator<Hash>, Serializable {
         private final Map<Hash, Points> _points;

         public PointsComparator(Map<Hash, Points> points) {
             _points = points;
         }
         public int compare(Hash l, Hash r) {
             // reverse
             return _points.get(r).compareTo(_points.get(l));
        }
    }

    /**
     *  Reverse points, then forward by text
     *  @since 0.9.38
     */
    private static class ReasonComparator implements Comparator<String>, Serializable {
         public int compare(String l, String r) {
             int lc = l.indexOf(':');
             int rc = r.indexOf(':');
             if (lc <= 0 || rc <= 0)
                 return 0;
             double ld, rd;
             try {
                 ld = Double.parseDouble(l.substring(0, lc));
                 rd = Double.parseDouble(r.substring(0, rc));
             } catch (NumberFormatException nfe) {
                 return 0;
             }
             int rv = Double.compare(rd, ld);
             if (rv != 0)
                 return rv;
             return l.compareTo(r);
        }
    }

    /**
     *  The whole thing
     *
     *  @param mode what tab to show
     *  @param date only for mode = 12
     */
    private void renderRouterInfoHTML(Writer out, String nonce, int mode, long date) throws IOException {
        Hash us = _context.routerHash();
        Analysis analysis = Analysis.getInstance(_context);
        List<RouterInfo> ris = null;
        if (mode != 0 && mode < 12) {
            if (mode >= 2 && mode <= 6) {
                // review all routers for family and IP analysis
                ris = analysis.getAllRouters(us);
            } else {
                ris = analysis.getFloodfills(us);
            }
            if (ris.isEmpty()) {
                out.write("<h3 class=\"sybils\">" + _t("No known routers") + "</h3>\n");
                return;
            }
        }

        StringBuilder buf = new StringBuilder(4*1024);
        buf.append("<div id=\"sybilnav\">\n<ul>\n" +
                   "<li id=\"reviewStored\"><a href=\"netdb?f=3\">" + _t("Review stored analysis") + "</a></li>\n" +
                   "<li id=\"runNew\"><a href=\"netdb?f=3&amp;m=14\">" + _t("Run new analysis") + "</a></li>\n" +
                   "<li id=\"configurePeriodic\"><a href=\"netdb?f=3&amp;m=15\">" + _t("Configure periodic analysis") + "</a></li>\n" +
                   "<li id=\"banlisted\"><a href=\"/profiles?f=3\">" + _t("Review current bans") + "</a></li>\n" +
                   "<li id=\"floodfillSummary\"><a href=\"netdb?f=3&amp;m=1\">" + _t("Floodfill Summary") + "</a></li>\n" +
                   "<li id=\"sameFamily\"><a href=\"netdb?f=3&amp;m=2\">" + _t("Same Family") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=3\">" + _t("IP close to us") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=4\">" + _t("Same IP") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=5\">" + _t("Same /24") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=6\">" + _t("Same /16") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=7\">" + _t("Pair distance") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=8\">" + _t("Close to us") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=9\">" + _t("Close to us tomorrow") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=10\">" + _t("DHT neighbors") + "</a></li>\n" +
                   "<li><a href=\"netdb?f=3&amp;m=11\">" + _t("Close to our destinations") + "</a></li>\n" +
                   "</ul>\n</div>\n");
        writeBuf(out, buf);

        double avgMinDist = 0;
        if (mode == 1 || mode == 8 || mode == 9 || mode == 10 || mode == 11) {
            avgMinDist = analysis.getAvgMinDist(ris);
        }
        Map<Hash, Points> points = new HashMap<Hash, Points>(64);

        if (mode == 0) {
            renderOverview(out, buf, nonce, analysis);
        } else if (mode == 1) {
            renderFFSummary(out, buf, ris, avgMinDist);
        } else if (mode == 2) {
            renderFamilySummary(out, buf, analysis, ris, points);
        } else if (mode == 3) {
            renderIPUsSummary(out, buf, analysis, ris, points);
        } else if (mode == 4) {
            renderIP32Summary(out, buf, analysis, ris, points);
        } else if (mode == 5) {
            renderIP24Summary(out, buf, analysis, ris, points);
        } else if (mode == 6) {
            renderIP16Summary(out, buf, analysis, ris, points);
        } else if (mode == 7) {
            renderPairSummary(out, buf, analysis, ris, points);
        } else if (mode == 8) {
            renderCloseSummary(out, buf, analysis, avgMinDist, ris, points);
        } else if (mode == 9) {
            renderCloseTmrwSummary(out, buf, analysis, us, avgMinDist, ris, points);
        } else if (mode == 10) {
            renderDHTSummary(out, buf, analysis, us, avgMinDist, ris, points);
        } else if (mode == 11) {
            renderDestSummary(out, buf, analysis, avgMinDist, ris, points);
        } else if (mode == 12) {
            // load stored analysis
            PersistSybil ps = analysis.getPersister();
            try {
                points = ps.load(date);
            } catch (IOException ioe) {
                _log.error("Failed to load stored sybil analysis for: " + date, ioe);
                out.write("<b>Failed to load analysis for " + DataHelper.formatTime(date).replace("-", " ") + "</b>: " +
                          DataHelper.escapeHTML(ioe.toString()));
                return;
            }
            if (points.isEmpty()) {
                _log.error("Empty stored sybil analysis or bad file format for: " + date);
                out.write("<b>Corrupt analysis file for " +  DataHelper.formatTime(date).replace("-", " ") + "</b>");
            } else {
                renderThreatsHTML(out, buf, date, points);
            }
        } else if (mode == 13 || mode == 16) {
            // run analysis and store it
            long now = _context.clock().now();
            points = analysis.backgroundAnalysis(mode == 16);
            if (!points.isEmpty()) {
                PersistSybil ps = analysis.getPersister();
                try {
                    ps.store(now, points);
                } catch (IOException ioe) {
                    out.write("<b>Failed to store analysis: " + ioe + "</b>");
                }
            }
            renderThreatsHTML(out, buf, now, points);
        } else if (mode == 14) {
            // show run form
            out.write("<div class=\"infohelp\" id=\"sybilanalysis\">\n");
            renderRunForm(out, buf, nonce);
            out.write("</div>\n");
        } else if (mode == 15) {
            // show background form
            renderBackgroundForm(out, buf, nonce);
        } else {
            out.write("<div class=\"infohelp\" id=\"sybilanalysis\">\n<b><i>");
            out.write("Unknown mode " + mode);
            out.write("</i></b></div>\n");
        }
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38
     */
    private void renderOverview(Writer out, StringBuilder buf, String nonce, Analysis analysis) throws IOException {
        PersistSybil ps = analysis.getPersister();
        List<Long> dates = ps.load();
        out.write("<p class=\"sybilinfo experimental\"><b>");
        out.write(_t("This is an experimental network database tool for debugging and analysis. " +
                   "Do not panic even if you see warnings indicated. " +
                   "Possible \"threats\" are summarized, however these are unlikely to be real threats. " +
                   "If you see anything you would like to discuss with the devs, contact us on IRC #i2p-dev."));
        out.write("</b></p>\n");
        out.write("<div class=\"infohelp\" id=\"sybilanalysis\">\n");
        if (dates.isEmpty()) {
            out.write("<b><i>");
            out.write(_t("No stored analysis"));
            out.write("</b></i></div>");
        } else {
            buf.append("\n<form action=\"netdb\" method=\"POST\">\n" +
                       "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                       "<input type=\"hidden\" name=\"m\" value=\"12\">\n" +
                       "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n<b>")
               .append(_t("Select stored analysis")).append(":</b> \n" +
                       "<select name=\"date\">\n");
            boolean first = true;
            for (Long date : dates) {
                buf.append("<option value=\"").append(date).append('\"');
                if (first) {
                    buf.append(HelperBase.SELECTED);
                    first = false;
                }
                buf.append('>').append(DataHelper.formatTime(date.longValue()).replace("-", " ")).append("</option>\n");
            }
            buf.append("</select>\n" +
                       "<input type=\"submit\" name=\"action\" class=\"go\" value=\"Review\" />\n" +
                       "</form>\n</div>\n");
        }
//        renderRunForm(out, buf, nonce);
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38
     */
    private static void renderRunForm(Writer out, StringBuilder buf, String nonce) throws IOException {
        buf.append("<form class=\"sybilScan\" action=\"netdb\" method=\"POST\">\n" +
                   "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                   "<input type=\"hidden\" name=\"m\" value=\"13\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n" +
                   "<table>\n<tr><td align=\"left\"><b>")
           .append(_x("Run analysis on Floodfills only"))
           .append(":</b></td><td align=\"right\"><input type=\"submit\" name=\"action\" class=\"go\" value=\"Start Scan\" /> "+
                   "</td></tr>\n</table>\n</form>\n<hr>\n");
        buf.append("<form class=\"sybilScan\" action=\"netdb\" method=\"POST\">\n" +
                   "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                   "<input type=\"hidden\" name=\"m\" value=\"16\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n" +
                   "<table><tr><td align=\"left\"><b>")
           .append(_x("Run analysis on all routers in NetDb"))
           .append(":</b></td><td align=\"right\"><input type=\"submit\" name=\"action\" class=\"go\" value=\"Start Scan\" />" +
                   "</td></tr>\n</table>\n</form>\n");
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38
     */
    private void renderBackgroundForm(Writer out, StringBuilder buf, String nonce) throws IOException {
        long freq = _context.getProperty(Analysis.PROP_FREQUENCY, Analysis.DEFAULT_FREQUENCY);
        buf.append("<form action=\"netdb\" method=\"POST\">\n" +
                   "<input type=\"hidden\" name=\"f\" value=\"3\">\n" +
                   "<input type=\"hidden\" name=\"m\" value=\"15\">\n" +
                   "<input type=\"hidden\" name=\"nonce\" value=\"").append(nonce).append("\" >\n<p class=\"sybilinfo\"><b>")
           .append(_t("The sybil detection routine can be configured to scan the Network Database on a regular interval, " +
                      "with the option to block peers meeting the configured threshold. " +
                      "Blocking peers prevents your router from building tunnels with them for the duration configured, or until the router is restarted."))
           .append("</b></p>\n<table id=\"sybilTask\">\n<tr><th>").append(_t("Configure Background Analysis"))
           .append("</th></tr>\n<tr><td>\n<div class=\"optionlist\">\n<span class=\"nowrap\"><b>").append(_t("Run task every")).append(":</b>\n" +
                   "<select name=\"runFrequency\">\n");
        for (int i = 0; i < HOURS.length; i++) {
            buf.append("<option value=\"");
            buf.append(HOURS[i]);
            buf.append('"');
            long time = HOURS[i] * 60*60*1000L;
            if (time == freq)
                buf.append(HelperBase.SELECTED);
            buf.append('>');
            if (HOURS[i] > 0)
                buf.append(DataHelper.formatDuration2(time));
            else
                buf.append(_t("Never"));
            buf.append("</option>\n");
        }
        boolean auto = _context.getProperty(Analysis.PROP_BLOCK, Analysis.DEFAULT_BLOCK);
        boolean nonff = _context.getBooleanProperty(Analysis.PROP_NONFF);
        String thresh = _context.getProperty(Analysis.PROP_THRESHOLD, Double.toString(Analysis.DEFAULT_BLOCK_THRESHOLD));
        long days = _context.getProperty(Analysis.PROP_BLOCKTIME, Analysis.DEFAULT_BLOCK_TIME) / (24*60*60*1000L);
        buf.append("</select>\n")
           .append("</span><br>\n<span class=\"nowrap\"><b>")
           .append(_t("Delete scans older than")).append(":</b>\n<select name=\"deleteAge\">\n");
        long age = _context.getProperty(Analysis.PROP_REMOVETIME, Analysis.DEFAULT_REMOVE_TIME);
        for (int i = 0; i <DAYS.length; i++) {
            buf.append("<option value=\"");
            buf.append(DAYS[i]);
            buf.append('"');
            long time = DAYS[i] * 24*60*60*1000L;
            if (time == age)
                buf.append(HelperBase.SELECTED);
            buf.append('>');
            if (DAYS[i] > 0)
                buf.append(DataHelper.formatDuration2(time).replace("-", " "));
            else
                buf.append(_t("Never"));
            buf.append("</option>\n");
        }
        buf.append("</select>\n</span><br>\n<span class=\"nowrap\"><b>")
           .append(_t("Automatic blocking")).append(":</b><label><input type=\"checkbox\" class=\"optbox\" value=\"1\" name=\"block\" ");
        if (auto)
            buf.append(HelperBase.CHECKED);
        buf.append(">").append(_t("Add detected sybils to banlist")).append("</label>")
           .append("</span><br>\n<span class=\"nowrap\"><b>")
           .append(_t("Block all detected sybils")).append(":</b><label><input type=\"checkbox\" class=\"optbox\" value=\"1\" name=\"nonff\" ");
        if (nonff)
            buf.append(HelperBase.CHECKED);
        buf.append(">").append(_t("Include non-floodfill routers")).append("</label>")
           .append("</span><br>\n<span class=\"nowrap\"><b>")
           .append(_t("Minimum threshold for block")).append(":</b><input type=\"text\" name=\"threshold\" value=\"")
           .append(thresh).append("\">").append(_t("threat points")).append("</span><br>\n<span class=\"nowrap\"><b>")
           .append(_t("Enforce block for")).append(":</b><input type=\"text\" name=\"days\" value=\"")
           .append(days).append("\">").append(_t("days")).append("</span><br>\n</td></tr>\n")
           .append("<tr><td class=\"optionsave\" align=\"right\"><input type=\"submit\" name=\"action\" class=\"accept\" value=\"Save\" />\n</div>\n</td></tr>\n</table>\n</form>\n");
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderFFSummary(Writer out, StringBuilder buf, List<RouterInfo> ris, double avgMinDist) throws IOException {
        renderRouterInfo(buf, _context.router().getRouterInfo(), null, true, false);
        buf.append("<h3 id=\"known\" class=\"sybils\">").append(_t("Known Floodfills")).append(": ").append(ris.size()).append("</h3>\n");
        buf.append("<div id=\"sybils_summary\">\n" +
                   "<b>").append(_t("Average closest floodfill distance")).append(":</b> ").append(fmt.format(avgMinDist)).append("<br>\n" +
//                   "<b>").append(_t("Routing Data")).append(":</b> \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getModData()))
                   "<b>").append(_t("Routing Data")).append(":</b> ").append(DataHelper.formatTime(_context.routerKeyGenerator().getLastChanged())).append("<br>\n" +
//           .append("\"<br>\n<b>").append(_t("Last Changed")).append(":</b> ").append(DataHelper.formatTime(_context.routerKeyGenerator().getLastChanged())).append("<br>\n" +
//                   "<b>").append(_t("Next Routing Data")).append(":</b> \"").append(DataHelper.getUTF8(_context.routerKeyGenerator().getNextModData()))
                   "<b>").append(_t("Rotates in")).append(":</b> ").append(DataHelper.formatDuration(_context.routerKeyGenerator().getTimeTillMidnight())).append("\n" +
                   "</div>\n");
        writeBuf(out, buf);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderFamilySummary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<String, List<RouterInfo>> fmap = analysis.calculateIPGroupsFamily(ris, points);
        renderIPGroupsFamily(out, buf, fmap);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIPUsSummary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        List<RouterInfo> ri32 = new ArrayList<RouterInfo>(4);
        List<RouterInfo> ri24 = new ArrayList<RouterInfo>(4);
        List<RouterInfo> ri16 = new ArrayList<RouterInfo>(4);
        analysis.calculateIPGroupsUs(ris, points, ri32, ri24, ri16);
        renderIPGroupsUs(out, buf, ri32, ri24, ri16);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIP32Summary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<Integer, List<RouterInfo>> map = analysis.calculateIPGroups32(ris, points);
        renderIPGroups32(out, buf, map);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIP24Summary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<Integer, List<RouterInfo>> map = analysis.calculateIPGroups24(ris, points);
        renderIPGroups24(out, buf, map);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderIP16Summary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        Map<Integer, List<RouterInfo>> map = analysis.calculateIPGroups16(ris, points);
        renderIPGroups16(out, buf, map);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderPairSummary(Writer out, StringBuilder buf, Analysis analysis, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Pairwise distance analysis
        List<Pair> pairs = new ArrayList<Pair>(PAIRMAX);
        double avg = analysis.calculatePairDistance(ris, points, pairs);
        renderPairDistance(out, buf, pairs, avg);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderCloseSummary(Writer out, StringBuilder buf, Analysis analysis, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Distance to our router analysis
        buf.append("<h3 id=\"ritoday\" class=\"sybils\">").append(_t("Closest Floodfills to Our Routing Key (Where we Store our RI)")).append("</h3>\n");
        buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil\">See all</a></p>\n");
        Hash ourRKey = _context.router().getRouterInfo().getRoutingKey();
        analysis.calculateRouterInfo(ourRKey, "our rkey", ris, points);
        renderRouterInfoHTML(out, buf, ourRKey, avgMinDist, ris);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderCloseTmrwSummary(Writer out, StringBuilder buf, Analysis analysis, Hash us, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Distance to our router analysis
        RouterKeyGenerator rkgen = _context.routerKeyGenerator();
        Hash nkey = rkgen.getNextRoutingKey(us);
        buf.append("<h3 id=\"ritmrw\" class=\"sybils\">").append(_t("Closest Floodfills to Tomorrow's Routing Key (Where we will Store our RI)")).append("</h3>\n");
        buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil\">See all</a></p>\n");
        analysis.calculateRouterInfo(nkey, "our rkey (tomorrow)", ris, points);
        renderRouterInfoHTML(out, buf, nkey, avgMinDist, ris);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderDHTSummary(Writer out, StringBuilder buf, Analysis analysis, Hash us, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        buf.append("<h3 id=\"dht\" class=\"sybils\">").append(_t("Closest Floodfills to Our Router Hash (DHT Neighbors if we are Floodfill)")).append("</h3>\n");
        analysis.calculateRouterInfo(us, "our router", ris, points);
        renderRouterInfoHTML(out, buf, us, avgMinDist, ris);
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderDestSummary(Writer out, StringBuilder buf, Analysis analysis, double avgMinDist, List<RouterInfo> ris, Map<Hash, Points> points) throws IOException {
        // Distance to our published destinations analysis
        RouterKeyGenerator rkgen = _context.routerKeyGenerator();
        buf.append("<h3 id=\"dest\" class=\"sybils\">").append(_t("Floodfills Close to Our Destinations")).append("</h3>\n");
        Map<Hash, TunnelPool> clientInboundPools = _context.tunnelManager().getInboundClientPools();
        List<Hash> destinations = new ArrayList<Hash>(clientInboundPools.keySet());
        for (Iterator<Hash> iter = destinations.iterator(); iter.hasNext(); ) {
            Hash client = iter.next();
            if (!_context.clientManager().isLocal(client) ||
                !_context.clientManager().shouldPublishLeaseSet(client) ||
                _context.netDb().lookupLeaseSetLocally(client) == null) {
                iter.remove();
            }
        }
        if (destinations.isEmpty()) {
            buf.append("<p class=\"notfound\">").append(_t("None")).append("</p>\n");
            writeBuf(out, buf);
            return;
        }
        for (Hash client : destinations) {
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(client);
            if (ls == null)
                continue;
            Hash rkey = ls.getRoutingKey();
            TunnelPool in = clientInboundPools.get(client);
            String name = (in != null) ? DataHelper.escapeHTML(in.getSettings().getDestinationNickname()) : client.toBase64().substring(0,4);
            buf.append("<h3 class=\"sybils\">").append(_t("Closest floodfills to the Routing Key for"))
               .append(" " + name + " (" + _t("where we store our LS") + ")</h3>");
            buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil=" + ls.getHash().toBase64() + "\">See all</a></p>\n");
            analysis.calculateRouterInfo(rkey, name, ris, points);
            renderRouterInfoHTML(out, buf, rkey, avgMinDist, ris);
            Hash nkey = rkgen.getNextRoutingKey(ls.getHash());
            buf.append("<h3 class=\"sybils\">").append(_t("Closest floodfills to Tomorrow's Routing Key for"))
               .append(" " + name + " (" + _t("where we will store our LS") + ")</h3>");
            buf.append("<p class=\"sybil_info\"><a href=\"/netdb?caps=f&amp;sybil=" + ls.getHash().toBase64() + "\">See all</a></p>\n");
            analysis.calculateRouterInfo(nkey, name + " (tomorrow)", ris, points);
            renderRouterInfoHTML(out, buf, nkey, avgMinDist, ris);
        }
    }

    /**
     *  @since 0.9.38 split out from renderRouterInfoHTML()
     */
    private void renderThreatsHTML(Writer out, StringBuilder buf, long date, Map<Hash, Points> points) throws IOException {
        double threshold = Analysis.DEFAULT_BLOCK_THRESHOLD;
        try {
            threshold = Double.parseDouble(_context.getProperty(Analysis.PROP_THRESHOLD, Double.toString(threshold)));
            if (threshold < Analysis.MIN_BLOCK_POINTS)
                threshold = Analysis.MIN_BLOCK_POINTS;
        } catch (NumberFormatException nfe) {}
        final double minDisplay = Math.min(threshold, MIN_DISPLAY_POINTS);
        if (!points.isEmpty()) {
            List<Hash> warns = new ArrayList<Hash>(points.keySet());
            Collections.sort(warns, new PointsComparator(points));
            ReasonComparator rcomp = new ReasonComparator();
            buf.append("<h3 id=\"threats\" class=\"sybils\">").append(_t("Routers with Most Threat Points"))
               .append("<span style=\"float: right; display: inline-block;\">")
               .append(DataHelper.formatTime(date).replace("-", " ")).append("</span></h3>\n");
            for (Hash h : warns) {
                Points pp = points.get(h);
                double p = pp.getPoints();
                if (p < minDisplay)
                    break;  // sorted
                if (p >= 100)
                    buf.append("<p class=\"threatpoints hot\"><b>");
                else
                    buf.append("<p class=\"threatpoints\"><b>");
                buf.append(_t("Threat Points")).append(": " + fmt.format(p).replace(".00", "") + "</b></p>\n<ul>\n");
                List<String> reasons = pp.getReasons();
                if (reasons.size() > 1)
                    Collections.sort(reasons, rcomp);
                for (String s : reasons) {
                    int c = s.indexOf(':');
                    if (c <= 0)
                        continue;
                    buf.append("<li><b>").append(s, 0, c+1).append("</b>").append(s, c+1, s.length()).append("</li>\n");
                }
                buf.append("</ul>\n");
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
                if (ri != null) {
                    renderRouterInfo(buf, ri, null, false, false);
                } else {
                    String hash = h.toBase64();
                    buf.append("<a name=\"").append(hash, 0, 6).append("\"></a>\n<table class=\"sybil_routerinfo\">\n<tr>" +
                               "<th><b>" + _t("Router") + ":</b> <code>").append(hash).append("</code></th>" +
                               "<th colspan=\"2\"><b style=\"float: right;\">" + _t("Router info not available") +
                               "</b></th></tr>\n</table>\n");
                }
            }
        }
        writeBuf(out, buf);
    }

    /**
     *  @param pairs sorted
     */
    private void renderPairDistance(Writer out, StringBuilder buf, List<Pair> pairs, double avg) throws IOException {
        buf.append("<h3 class=\"sybils\">").append(_t("Average Floodfill Distance is")).append(" ").append(fmt.format(avg)).append("</h3>\n" +
                   "<h3 id=\"pairs\" class=\"sybils\">").append(_t("Closest Floodfill Pairs by Hash")).append("</h3>\n");

        for (Pair p : pairs) {
            double distance = biLog2(p.dist);
            double point = MIN_CLOSE - distance;
            // limit display
            if (point < 2)
                break;  // sorted;
            buf.append("<p class=\"hashdist\"><b>").append(_t("Hash Distance")).append(": ").append(fmt.format(distance)).append(": </b>" +
                       "</p>\n");
            renderRouterInfo(buf, p.r1, null, false, false);
            renderRouterInfo(buf, p.r2, null, false, false);
        }
        writeBuf(out, buf);
    }

    private static class FooComparator implements Comparator<Integer>, Serializable {
         private final Map<Integer, List<RouterInfo>> _o;
         public FooComparator(Map<Integer, List<RouterInfo>> o) { _o = o;}
         public int compare(Integer l, Integer r) {
             // reverse by count
             int rv = _o.get(r).size() - _o.get(l).size();
             if (rv != 0)
                 return rv;
             // foward by IP
             return l.intValue() - r.intValue();
        }
    }

    private static class FoofComparator implements Comparator<String>, Serializable {
         private final Map<String, List<RouterInfo>> _o;
         private final Collator _comp = Collator.getInstance();
         public FoofComparator(Map<String, List<RouterInfo>> o) { _o = o;}
         public int compare(String l, String r) {
             // reverse by count
             int rv = _o.get(r).size() - _o.get(l).size();
             if (rv != 0)
                 return rv;
             // foward by name
             return _comp.compare(l, r);
        }
    }

    /**
     *
     */
    private void renderIPGroupsUs(Writer out, StringBuilder buf, List<RouterInfo> ri32,
                                  List<RouterInfo> ri24, List<RouterInfo> ri16) throws IOException {
        buf.append("<h3 id=\"ourIP\" class=\"sybils\">").append(_t("Routers close to Our IP")).append("</h3>\n");
        boolean found = false;
        for (RouterInfo info : ri32) {
             buf.append("<p class=\"sybil_info\"><b>");
             buf.append(_t("Same IP as us"));
             buf.append(":</b></p>\n");
             renderRouterInfo(buf, info, null, false, false);
             found = true;
        }
        for (RouterInfo info : ri24) {
             buf.append("<p class=\"sybil_info\"><b>");
             buf.append(_t("Same /24 as us"));
             buf.append(":</b></p>\n");
             renderRouterInfo(buf, info, null, false, false);
             found = true;
        }
        for (RouterInfo info : ri16) {
             buf.append("<p class=\"sybil_info\"><b>");
             buf.append(_t("Same /16 as us"));
             buf.append(":</b></p>\n");
             renderRouterInfo(buf, info, null, false, false);
             found = true;
        }
        if (!found)
            buf.append("<p class=\"notfound\">").append(_t("None")).append("</p>\n");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroups32(Writer out, StringBuilder buf, Map<Integer, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"sameIP\" class=\"sybils\">").append(_t("Routers with the Same IP")).append("</h3>\n");
        List<Integer> foo = new ArrayList<Integer>(map.keySet());
        Collections.sort(foo, new FooComparator(map));
        boolean found = false;
        for (Integer ii : foo) {
            List<RouterInfo> ris = map.get(ii);
            int count = ris.size();
            int i = ii.intValue();
            int i0 = (i >> 24) & 0xff;
            int i1 = (i >> 16) & 0xff;
            int i2 = (i >> 8) & 0xff;
            int i3 = i & 0xff;
            String sip = i0 + "." + i1 + '.' + i2 + '.' + i3;
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" ").append(_t("routers with IP")).append(" <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a>:</b></p>\n");
            for (RouterInfo info : ris) {
                found = true;
                renderRouterInfo(buf, info, null, false, false);
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">").append(_t("None")).append("</p>\n");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroups24(Writer out, StringBuilder buf, Map<Integer, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"same24\" class=\"sybils\">").append(_t("Routers in the Same /24 (2 minimum)")).append("</h3>\n");
        List<Integer> foo = new ArrayList<Integer>(map.keySet());
        Collections.sort(foo, new FooComparator(map));
        boolean found = false;
        for (Integer ii : foo) {
            List<RouterInfo> ris = map.get(ii);
            int count = ris.size();
            int i = ii.intValue();
            int i0 = i >> 16;
            int i1 = (i >> 8) & 0xff;
            int i2 = i & 0xff;
            String sip = i0 + "." + i1 + '.' + i2 + ".0/24";
            buf.append("<p class=\"sybil_info\"><b>").append(count).append(" ").append(_t("routers with IP")).append(" <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a>:</b></p>\n");
            for (RouterInfo info : ris) {
                found = true;
                renderRouterInfo(buf, info, null, false, false);
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">").append(_t("None")).append("</p>\n");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroups16(Writer out, StringBuilder buf, Map<Integer, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"same16\" class=\"sybils\">").append(_t("Routers in the Same /16 (4 minimum)")).append("</h3>\n");
        List<Integer> foo = new ArrayList<Integer>(map.keySet());
        Collections.sort(foo, new FooComparator(map));
        boolean found = false;
        for (Integer ii : foo) {
            List<RouterInfo> ris = map.get(ii);
            int count = ris.size();
            int i = ii.intValue();
            int i0 = i >> 8;
            int i1 = i & 0xff;
            String sip = i0 + "." + i1 + ".0.0/16";
            buf.append("<p class=\"sybil_info\"><b> ").append(count).append(" ").append(_t("routers with IP")).append(" <a href=\"/netdb?ip=")
               .append(sip).append("&amp;sybil\">").append(sip)
               .append("</a></b></p>\n");
            for (RouterInfo info : ris) {
                found = true;
                renderRouterInfo(buf, info, null, false, false);
            }
        }
        if (!found)
            buf.append("<p class=\"notfound\">").append(_t("None")).append("</p>\n");
        writeBuf(out, buf);
    }

    /**
     *
     */
    private void renderIPGroupsFamily(Writer out, StringBuilder buf, Map<String, List<RouterInfo>> map) throws IOException {
        buf.append("<h3 id=\"samefamily\" class=\"sybils\">").append(_t("Routers in the same Family"))
           .append("</h3><div class=\"sybil_container\">\n");
        List<String> foo = new ArrayList<String>(map.keySet());
        Collections.sort(foo, new FoofComparator(map));
        FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
        String ourFamily = fkc != null ? fkc.getOurFamilyName() : null;
        boolean found = false;
        for (String s : foo) {
            List<RouterInfo> list = map.get(s);
            int count = list.size();
            String ss = DataHelper.escapeHTML(s);
            if (count > 1) {
                buf.append("<p class=\"family\"><b>").append(count).append(' ').append(_t("routers in family"))
                   .append(":</b> &nbsp;<wbr><a href=\"/netdb?fam=").append(ss).append("&amp;sybil\">").append(ss).append("</a></p>\n");
                found = true;
            }
            //for (RouterInfo info : ris) {
                // limit display
                //renderRouterInfo(buf, info, null, false, false);
            //}
        }
        if (!found)
            buf.append("<p class=\"notfound\">").append(_t("None")).append("</p>\n");
        buf.append("</div>");
        writeBuf(out, buf);
    }

    /**
     *  Render routers closer than MIN_CLOSE up to MAX routers
     *  @param ris sorted, closest first
     *  @param usName HTML escaped
     */
    private void renderRouterInfoHTML(Writer out, StringBuilder buf, Hash us, double avgMinDist,
                                      List<RouterInfo> ris) throws IOException {
        double min = 256;
        double max = 0;
        double tot = 0;
        double median = 0;
        int count = Math.min(MAX, ris.size());
        boolean isEven = (count % 2) == 0;
        int medIdx = isEven ? (count / 2) - 1 : (count / 2);
        for (int i = 0; i < count; i++) {
            RouterInfo ri = ris.get(i);
            double dist = renderRouterInfo(buf, ri, us, false, false);
            if (dist < MIN_CLOSE)
                break;
            if (dist < avgMinDist) {
                if (i == 0) {
                    //buf.append("<p><b>Not to worry, but above router is closer than average minimum distance " + fmt.format(avgMinDist) + "</b></p>\n");
                } else if (i == 1) {
                    buf.append("<p class=\"sybil_info\"><b>" + _t("Not to worry, but above routers are closer than average minimum distance") +
                               " " + fmt.format(avgMinDist) + "</b></p>\n");
                } else if (i == 2) {
                    buf.append("<p class=\"sybil_info\"><b>" + _t("Possible Sybil Warning - above routers are closer than average minimum distance") +
                               " " + fmt.format(avgMinDist) + "</b></p>\n");
                } else {
                    buf.append("<p class=\"sybil_info\"><b>" + _t("Major Sybil Warning - above router is closer than average minimum distance") +
                               " " + fmt.format(avgMinDist) + "</b></p>\n");
                }
            }
            // this is dumb because they are already sorted
            if (dist < min)
                min = dist;
            if (dist > max)
                max = dist;
            tot += dist;
            if (i == medIdx)
                median = dist;
            else if (i == medIdx + 1 && isEven)
                median = (median + dist) / 2;
        }
        double avg = tot / count;
        buf.append("<p id=\"sybil_totals\"><b>" + _t("Totals for") + " " + count + " " + _t("floodfills") +
                   ": &nbsp;</b><span class=\"netdb_name\">" + _t("MIN") + ":</span > " + fmt.format(min) +
                   "&nbsp; <span class=\"netdb_name\">" + _t("AVG") + ":</span> " + fmt.format(avg) +
                   "&nbsp; <span class=\"netdb_name\">" + _t("MEDIAN") + ":</span> " + fmt.format(median) +
                   "&nbsp; <span class=\"netdb_name\">" + _t("MAX") + ":</span> " + fmt.format(max) + "</p>\n");
        writeBuf(out, buf);
    }

    private static void writeBuf(Writer out, StringBuilder buf) throws IOException {
        out.write(buf.toString());
        out.flush();
        buf.setLength(0);
    }

    /**
     * Countries now in a separate bundle
     * @param code two-letter country code
     * @since 0.9.9
     */
    private String getTranslatedCountry(String code) {
        String name = _context.commSystem().getCountryName(code);
        return Translate.getString(name, _context, Messages.COUNTRY_BUNDLE_NAME);
    }

    /**
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     *
     *  @param us ROUTING KEY or null
     *  @param full ignored
     *  @return distance to us if non-null, else 0
     */
    private double renderRouterInfo(StringBuilder buf, RouterInfo info, Hash us, boolean isUs, boolean full) {
        String hash = info.getIdentity().getHash().toBase64();
        buf.append("<a name=\"").append(hash, 0, 6).append("\"></a>\n<table class=\"sybil_routerinfo\">\n");
        double distance = 0;
        if (isUs) {
            buf.append("<tr id=\"sybil_ourinfo\"><th><a name=\"our-info\" ></a><b>" + _t("Our info") + ":</b> <code>").append(hash)
               .append("</code>");
        } else {
            buf.append("<tr><th><b>" + _t("Router") + ":</b> <code>").append(hash).append("</code>");
        }
        buf.append("</th><th>");
//        Hash h = info.getHash();
//        if (_context.banlist().isBanlisted(h)) {
//            buf.append("<a href=\"/profiles?f=3\" title=\"").append(_t("Router is banlisted"))
//               .append("<img src=\"/themes/console/images/info/blocked.png\" width=\"16\" height=\"16\"></a>");
//        }
        String tooltip = "\" title=\"" + _t("Show all routers with this capability in the NetDb") + "\"><span";
        String caps = DataHelper.stripHTML(info.getCapabilities())
           .replace("XO", "X")
           .replace("PO", "P")
           .replace("Kf", "fK")
           .replace("Lf", "fL")
           .replace("Mf", "fM")
           .replace("Nf", "fN")
           .replace("Of", "fO")
           .replace("Pf", "fP")
           .replace("Xf", "fX")
           .replace("f", "<a href=\"/netdb?caps=f\"><span class=\"ff\">F</span></a>")
           .replace("B", "<a href=\"/netdb?caps=B\"><span class=\"testing\">B</span></a>") // not shown?
           .replace("C", "<a href=\"/netdb?caps=C\"><span class=\"ssuintro\">C</span></a>") // not shown?
           .replace("H", "<a href=\"/netdb?caps=H\"><span class=\"hidden\">H</span></a>") // not shown?
           .replace("R", "<a href=\"/netdb?caps=R\"><span class=\"reachable\">R</span></a>")
           .replace("U", "<a href=\"/netdb?caps=U\"><span class=\"unreachable\">U</span></a>")
           .replace("K", "<a href=\"/netdb?caps=K\"><span class=\"tier\">K</span></a>")
           .replace("L", "<a href=\"/netdb?caps=L\"><span class=\"tier\">L</span></a>")
           .replace("M", "<a href=\"/netdb?caps=M\"><span class=\"tier\">M</span></a>")
           .replace("N", "<a href=\"/netdb?caps=N\"><span class=\"tier\">N</span></a>")
           .replace("O", "<a href=\"/netdb?caps=O\"><span class=\"tier\">O</span></a>")
           .replace("P", "<a href=\"/netdb?caps=P\"><span class=\"tier\">P</span></a>")
           .replace("X", "<a href=\"/netdb?caps=X\"><span class=\"tier\">X</span></a>")
           .replace("\"><span", tooltip);
        buf.append(caps);
        buf.append("<a href=\"/netdb?v=").append(DataHelper.stripHTML(info.getVersion())).append("\">")
            .append("<span class=\"version\" title=\"").append(_t("Show all routers with this version in the NetDb"))
            .append("\">").append(DataHelper.stripHTML(info.getVersion())).append("</span></a>");
        if (!isUs) {
           buf.append("<span class=\"netdb_header\">");
           String family = info.getOption("family");
           if (family != null) {
               buf.append("<a class=\"familysearch\" href=\"/netdb?fam=").append(family).append("\" title=\"").append(_t("Show all routers for this family in NetDb"))
                  .append("\">").append(_t("Family")).append("</a>");
           }
           buf.append("<a class=\"viewprofile\" href=\"/viewprofile?peer=").append(hash).append("\" title=\"").append(_t("View profile"))
              .append("\">").append(_t("Profile")).append("</a>")
              .append("<a class=\"configpeer\" href=\"/configpeer?peer=").append(hash).append("\" title=\"").append(_t("Configure peer"))
              .append("\">").append(_t("Edit")).append("</a>");
           String country = _context.commSystem().getCountry(info.getIdentity().getHash());
           if(country != null) {
               buf.append("<a href=\"/netdb?c=").append(country).append("\">")
                  .append("<img height=\"12\" width=\"16\" alt=\"").append(country.toUpperCase(Locale.US)).append('\"')
                  .append(" title=\"").append(getTranslatedCountry(country)).append('\"')
                  .append(" src=\"/flags.jsp?c=").append(country).append("\"> ").append("</a>");
           } else {
               buf.append("<img height=\"12\" width=\"16\" alt=\"??\"").append(" title=\"unknown\"").append(" src=\"/flags.jsp?c=a0\"></a>");
           }
        }
        buf.append("</span>");
        if (_context.portMapper().isRegistered("imagegen"))
            buf.append("<img class=\"identicon\" src=\"/imagegen/id?s=32&amp;c=" + hash.replace("=", "%3d") + "\" height=\"28\" width=\"28\">");
        buf.append("</th></tr>\n");
        buf.append("<tr><td class=\"sybilinfo_params\" colspan=\"3\">\n<div class=\"sybilinfo_container\">\n");
        if (us != null) {
           BigInteger dist = HashDistance.getDistance(us, info.getHash());
           distance = biLog2(dist);
           buf.append("<p><b>").append(_t("Hash Distance")).append(":</b> ").append(fmt.format(distance)).append("</p>\n");
        }
        // added to header so no need here
        //buf.append("<p><b>").append(_t("Version")).append(":</b> ").append(DataHelper.stripHTML(info.getVersion())).append("</p>\n");
        //buf.append("<p><b>Caps:</b> ").append(DataHelper.stripHTML(info.getCapabilities())).append("</p>\n");
        String kr = info.getOption("netdb.knownRouters");
        if (kr != null) {
            buf.append("<p><b>").append(_t("Routers")).append(":</b> ").append(DataHelper.stripHTML(kr)).append("</p>\n");
//        } else {
//            buf.append("<p class=\"sybil_filler\"><b>").append(_t("Routers")).append(":</b> ").append(_t("n/a")).append("</p>\n");
        }
        String kls = info.getOption("netdb.knownLeaseSets");
        if (kls != null) {
            buf.append("<p class=\"sybilinfo_leasesets\"><b>").append(_t("LeaseSets")).append(":</b> ").append(DataHelper.stripHTML(kls)).append("</p>\n");
//        } else {
//            buf.append("<p class=\"sybilinfo_leasesets filler\"><b>").append(_t("LeaseSets")).append(":</b> ").append(_t("n/a")).append("</p>\n");
        }
        String fam = info.getOption("family");
        if (fam != null) {
            buf.append("<p><b>").append(_t("Family")).append(":</b> <span class=\"sybilinfo_familyname\">").append(DataHelper.escapeHTML(fam)).append("</span></p>\n");
        }
//        buf.append("</div>\n<hr>\n<div class=\"sybilinfo_container\">\n");
        long now = _context.clock().now();
        if (!isUs) {
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(info.getHash());
            if (prof != null) {
                long heard = prof.getFirstHeardAbout();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>").append(_t("First heard about")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>").append(_t("First heard about")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                }
                heard = prof.getLastHeardAbout();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>").append(_t("Last heard about")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last heard about")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                }
                heard = prof.getLastHeardFrom();
                if (heard > 0) {
                    long age = Math.max(now - heard, 1);
                    buf.append("<p><b>").append(_t("Last heard from")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                } else {
                    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last heard from")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                }
                DBHistory dbh = prof.getDBHistory();
                if (dbh != null) {
                    heard = dbh.getLastLookupSuccessful();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last lookup successful")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last lookup successful")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                    }
                    heard = dbh.getLastLookupFailed();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last lookup failed")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last lookup failed")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                    }
                    heard = dbh.getLastStoreSuccessful();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last store successful")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last store successful")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                    }
                    heard = dbh.getLastStoreFailed();
                    if (heard > 0) {
                        long age = Math.max(now - heard, 1);
                        buf.append("<p><b>").append(_t("Last store failed")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
                    //} else {
                    //    buf.append("<p class=\"sybil_filler\"><b>").append(_t("Last store failed")).append(":</b> ").append(_t("n/a")).append("</p>\n");
                    }
                }
                // any other profile stuff?
            }
        }
        long age = Math.max(now - info.getPublished(), 1);
        if (isUs && _context.router().isHidden()) {
            buf.append("<p><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b> ")
               .append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
        } else {
            buf.append("<p><b>").append(_t("Published")).append(":</b> ").append(_t("{0} ago", DataHelper.formatDuration2(age))).append("</p>\n");
        }

        buf.append("<p class=\"sybil_filler\">&nbsp;</p>\n" +
                   "</div>\n</td></tr>\n<tr><td class=\"sybil_addresses\" colspan=\"3\">\n<table>\n<tr><td><b>" + _t("Addresses") + ":</b></td><td>");
        Collection<RouterAddress> addrs = info.getAddresses();
        if (addrs.size() > 1) {
            // addrs is unmodifiable
            List<RouterAddress> laddrs = new ArrayList<RouterAddress>(addrs);
            Collections.sort(laddrs, new NetDbRenderer.RAComparator());
            addrs = laddrs;
        }
        for (RouterAddress addr : addrs) {
            String style = addr.getTransportStyle();
            buf.append("<br><b class=\"netdb_transport\">").append(DataHelper.stripHTML(style)).append(":</b> ");
            Map<Object, Object> p = addr.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                String name = (String) e.getKey();
                if (name.equals("key") || name.startsWith("ikey") || name.startsWith("itag") ||
                    name.startsWith("iport") || name.equals("mtu"))
                    continue;
                String val = (String) e.getValue();
                buf.append(" <span class=\"nowrap\"><span class=\"netdb_name\">").append(_t(DataHelper.stripHTML(name))).append(":</span> <span class=\"netdb_info\">");
                buf.append(DataHelper.stripHTML(val));
                buf.append("</span></span>&nbsp;\n");
            }
        }
        buf.append("</table>\n</td></tr>\n" +
                   "</table>\n");
        return distance;
    }

    /**
     *  Called from NetDbRenderer
     *
     *  @since 0.9.28
     */
    public static void renderSybilHTML(Writer out, RouterContext ctx, List<Hash> sybils, String victim) throws IOException {
        if (sybils.isEmpty())
            return;
        final DecimalFormat fmt = new DecimalFormat("#0.00");
        XORComparator<Hash> xor = new XORComparator<Hash>(Hash.FAKE_HASH);
        out.write("<h3 class=\"tabletitle\">Group Distances</h3><table class=\"sybil_distance\"><tr><th>Hash<th>Distance from previous</tr>\n");
        Collections.sort(sybils, xor);
        Hash prev = null;
        for (Hash h : sybils) {
            String hh = h.toBase64();
            out.write("<tr><td><a href=\"#" + hh.substring(0, 6) + "\"><tt>" + hh + "</tt></a><td>");
            if (prev != null) {
                BigInteger dist = HashDistance.getDistance(prev, h);
                writeDistance(out, fmt, dist);
            }
            prev = h;
            out.write("</tr>\n");
        }
        out.write("</table>\n");
        out.flush();

        RouterKeyGenerator rkgen = ctx.routerKeyGenerator();
        long now = ctx.clock().now();
        final int start = -3;
        now += start * 24*60*60*1000L;
        final int days = 10;
        Hash from = ctx.routerHash();
        if (victim != null) {
            Hash v = ConvertToHash.getHash(victim);
            if (v != null)
                from = v;
        }
        out.write("<h3>" + _x("Distance to ") + "<span style=\"text-transform: none !important;\">" + from.toBase64() + "</span></h3>\n");
        prev = null;
        final int limit = Math.min(10, sybils.size());
        DateFormat utcfmt = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (int i = start; i <= days; i++) {
            out.write("<h3 class=\"tabletitle\">" + _x("Distance for") + ' ' + utcfmt.format(new Date(now)) +
                      "</h3><table class=\"sybil_distance\"><tr><th>Hash<th>Distance<th>Distance from previous</tr>\n");
            Hash rkey = rkgen.getRoutingKey(from, now);
            xor = new XORComparator<Hash>(rkey);
            Collections.sort(sybils, xor);
            for (int j = 0; j < limit; j++) {
                Hash h = sybils.get(j);
                String hh = h.toBase64();
                out.write("<tr><td><a href=\"#" + hh.substring(0, 6) + "\"><tt>" + hh + "</tt></a><td>");
                BigInteger dist = HashDistance.getDistance(rkey, h);
                writeDistance(out, fmt, dist);
                out.write("<td>");
                if (prev != null) {
                    dist = HashDistance.getDistance(prev, h);
                    writeDistance(out, fmt, dist);
                }
                prev = h;
                out.write("</tr>\n");
            }
            out.write("</table>\n");
            out.flush();
            now += 24*60*60*1000;
            prev = null;
        }
    }

    /** @since 0.9.28 */
    private static void writeDistance(Writer out, DecimalFormat fmt, BigInteger dist) throws IOException {
        double distance = biLog2(dist);
        if (distance < MIN_CLOSE)
            out.write("<font color=\"red\">");
        out.write(fmt.format(distance));
        if (distance < MIN_CLOSE)
            out.write("</font>");
    }

    /** translate a string */
    private String _t(String s) {
        return Messages.getString(s, _context);
    }

    /** tag only */
    private static final String _x(String s) {
        return s;
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    private String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }
}
