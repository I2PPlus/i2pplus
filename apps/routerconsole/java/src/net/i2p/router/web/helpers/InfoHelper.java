package net.i2p.router.web.helpers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.SortedMap;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.GeoIP;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;
import net.i2p.util.SystemVersion;

/**
 * Helper for router information page rendering and form processing.
 * @since 0.9.33
 */
public class InfoHelper extends HelperBase {
    private boolean _full;

    public InfoHelper() {}

    public void setFull(String f) {_full = f != null && f.length() > 0;}

    public String getConsole() {
        try {
            if (_out != null) {
                renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(2*1024);
                renderStatusHTML(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
                try {
                    return baos.toString(StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e) {
                    return baos.toString(StandardCharsets.UTF_8.name());
                }
            }
        } catch (IOException ioe) {return "<b>" + _t("Error displaying the info page.") + "</b>";}
    }

    public String getStats() {
        StatsGenerator gen = new StatsGenerator(_context);
        try {
            if (_out != null) {
                gen.generateStatsPage(_out, _full);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                gen.generateStatsPage(new OutputStreamWriter(baos, StandardCharsets.UTF_8), _full);
                try {
                    return baos.toString(StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e) {
                    return baos.toString(StandardCharsets.UTF_8.name());
                }
            }
        } catch (IOException ioe) {return "<b>" + _t("Error displaying the info page.") + "</b>";}
    }

    /** @return host or "unknown" */
    public String getUdpIP() {
        String rv = _context.getProperty(UDPTransport.PROP_IP);
        if (rv != null) {return rv;}
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr != null) {
            rv = addr.getHost();
            if (rv != null) {return rv;}
        }
        addr = _context.router().getRouterInfo().getTargetAddress("NTCP");
        if (addr != null) {
            rv = addr.getHost();
            if (rv != null) {return rv;}
        }
        return _t("unknown");
    }

    public String lastCountry() {return _context.getProperty("i2np.lastCountry");}
    public String getUdpPort() {return _context.getProperty("i2np.udp.port");}
    public String firstInstalled() {return _context.getProperty("router.firstInstalled");}
    public String firstVersion() {return _context.getProperty("router.firstVersion");}
    public String lastUpdated() {return _context.getProperty("router.updateLastInstalled");}
    public String updatePolicy() {return _context.getProperty("router.updatePolicy");}
    public String updateDevSU3() {return _context.getProperty("router.updateDevSU3");}

    public String updateUnsigned() {
        if (_context.getProperty("router.updateUnsigned") != null) {
            return _context.getProperty("router.updateUnsigned");
        } else {return "true";}
    }

    public boolean isRouterSlow() {
        if (SystemVersion.isSlow()) {return true;}
        else {return false;}
    }

    public String getCoreCount() {return Integer.toString(SystemVersion.getCores());}

    public String bwIn() {
        String in = _context.getProperty("i2np.bandwidth.inboundKBytesPerSecond");
        if (in != null) {return in;}
        else {return "1024";}
    }

    public String bwOut() {
        String out = _context.getProperty("i2np.bandwidth.outboundKBytesPerSecond");
        if (out != null) {return out;}
        else {return "512";}
    }

    public String bwShare() {
        String share = _context.getProperty("router.sharePercentage");
        if (share != null) {return share;}
        else {return "80";}
    }

    public String codelInterval() {
        String interval = _context.getProperty("router.codelInterval");
        if (interval != null) {return interval;}
        else {return "100";}
    }

    public String codelTarget() {
        String target = _context.getProperty("router.codelTarget");
        if (target != null) {return target;}
        else {return "10";}
    }

    public String getFamily() {
        RouterInfo ri = _context.router().getRouterInfo();
        String family = ri.getOption("family");
        if (family != null) {return family;}
        else {return null;}
    }

    public String getGeoIPBuildInfo() {
      GeoIP db = new GeoIP(_context);
      return db.getGeoIPBuildInfo();
    }

    private void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        RouterInfo ri = _context.router().getRouterInfo();
        Hash h = _context.routerHash();
        Date installDate = new Date();
        if (installDate != null) {installDate.setTime(Long.parseLong(firstInstalled()));}
        Date lastUpdate = new Date();
        lastUpdate.setTime(Long.parseLong(lastUpdated()));
        float bwO = Integer.parseInt(bwOut());
        float bwS = Integer.parseInt(bwShare());
        float shared = (bwO / 100 * bwS);
        int shareBW = (int) shared;
        long maxMem = SystemVersion.getMaxMemory();
        long maxMemMB = maxMem / 1024 / 1024;
        String slash = System.getProperty("file.separator");
        String appDir = _context.getProperty("i2p.dir.base") + slash;
        String configDir = _context.getProperty("i2p.dir.config") + slash;
        Boolean isAdvanced = _context.getBooleanProperty("routerconsole.advanced");
        Boolean rdnsEnabled = _context.getBooleanProperty("routerconsole.enableReverseLookups");
        String congestionCap = ri.getCongestionCap();

        // basic router information
        buf.append("<table>\n");
        if (h != null) {
            buf.append("<tr><td><b>").append(_t("Identity")).append(":</b></td><td><code><a href=\"/netdb?r=.\" title =\"")
               .append(_t("Network Database entry")).append("\">").append(h.toBase64()).append("</a></code>");
            if (getFamily() != null) {buf.append("&ensp;<b>").append(_t("Family")).append(":</b> ").append(getFamily());}
            buf.append("</td></tr>\n");
        }
        if (getUdpIP() != null && getUdpPort() != null) {
            buf.append("<tr><td><b>").append(_t("IP Address")).append(":</b></td><td class=ajax>").append(getUdpIP());
            if (lastCountry() != null) {
                buf.append(" &ensp;<img width=20 height=15 src=\"/flags.jsp?c=").append(lastCountry()).append("\">");
            }
            buf.append(" &ensp;<b>").append(_t("UDP Port")).append(":</b> ").append(getUdpPort())
               .append(" &ensp;<b>").append(_t("Status")).append(":</b> ").append(_t(_context.commSystem().getStatus().toStatusString()))
               .append(" &ensp;<b>").append(_t("Floodfill Role")).append(":</b> ");
            if (_context.netDb().floodfillEnabled()) {buf.append(_t("Active"));}
            else {buf.append(_t("Inactive"));}
            buf.append("&ensp;<a href=\"/configadvanced\">").append(_t("Configure")).append("</a></td></tr>\n");
        }
        if (bwIn() != null && bwOut() != null && bwShare() != null) {
            buf.append("<tr><td><b>").append(_t("Bandwidth")).append(":</b></td><td class=ajax><b>").append( _t("Inbound")).append(":</b> ")
               .append(bwIn()).append("KB/s &ensp;<b>").append(_t("Outbound")).append(":</b> ").append(bwOut()).append("KB/s &ensp;<b>")
               .append(_t("Shared")).append(":</b> ").append(bwShare()).append("% (").append(shareBW).append("KB/s) &ensp;<b>")
               .append(_t("Tier")).append(":</b> ").append(ri.getBandwidthTier());
            if (congestionCap != null && !congestionCap.equals("Unknown")) {
                buf.append("&ensp;<b>").append(_t("Congestion Cap")).append(":</b> ").append(congestionCap);
                if (congestionCap.equals("D")) {buf.append(" (").append(_t("Moderate")).append(")");}
                else if (congestionCap.equals("E")) {buf.append(" (").append(_t("Severe")).append(")");}
                else if (congestionCap.equals("G")) {buf.append(" (").append(_t("No transit tunnels")).append(")");}
            }
        }
        buf.append("&ensp;<a href=\"/config\">").append(_t("Configure")).append("</a></td></tr>\n")
           .append("<tr><td><b>").append(_t("Performance")).append(":</b></td><td><b>").append(_t("Available CPU Cores")).append(":</b> ")
           .append(getCoreCount()).append("&ensp;<b>").append(_t("Maximum available RAM")).append(":</b> ").append(maxMemMB + "MB")
           .append("&ensp;<b>").append(_t("Classified as slow")).append(":</b>");
        if (isRouterSlow()) {buf.append(" <span class=\"yes\">").append(_t("Yes")).append("</span>");}
        else {buf.append(" <span class=\"no\">").append(_t("No")).append("</span>");}
        buf.append("</td></tr>\n");
        buf.append("<tr><td><b>").append(_t("GeoIP Db")).append(":</b></td><td>").append(getGeoIPBuildInfo()).append("</td></tr>");
        if (isAdvanced) {
            buf.append("<tr><td><b>CoDel:</b></td><td><b>").append(_t("Target")).append(":</b> ").append(codelTarget())
               .append("ms &ensp;<b>").append(_t("Interval")).append(":</b> ").append(codelInterval()).append("ms</td></tr>\n");
        }
        if (rdnsEnabled) {
            int rdnsCacheSize = CommSystemFacadeImpl.countRdnsCacheEntries();
            String rdnsFileSize = (_context.router().getUptime() > 5 * 1000 ? CommSystemFacadeImpl.rdnsCacheSize() : _t("initializing") + "&hellip;");
            buf.append("<tr><td><b>RDNS Cache:</b></td><td class=ajax>").append(rdnsCacheSize).append(" / ")
               .append((maxMem < 512*1024*1024 ? "8000" : "16000")).append(" ").append(_t("entries")).append("&ensp;<b>")
               .append(_t("Cache file")).append(":</b> ").append(configDir).append("rdnscache.txt (").append(rdnsFileSize).append(")</td></tr>\n");
        }
        if (firstInstalled() != null && firstVersion() != null && lastUpdated() != null) {
            buf.append("<tr><td><b>").append(_t("Installed")).append(":</b></td><td>").append(installDate).append(" (")
               .append(firstVersion()).append(")").append(" &ensp;<span class=nowrap><b>").append(_t("Location")).append(":</b> ")
               .append(appDir.toString()).append("</span>").append(" &ensp;<span class=nowrap><b>").append(_t("Config Dir"))
               .append(":</b> ").append(configDir).append("</span></td></tr>\n")
               .append("<tr><td><b>").append(_t("Updated")).append(":</b></td><td>").append(lastUpdate);
            if (updatePolicy() != null) {
                buf.append(" &ensp;<b>").append(_t("Update Policy")).append(":</b> ").append(updatePolicy());
            }
            if ((updateUnsigned() != null && updateUnsigned().contains("true")) || (updateDevSU3() !=null && updateDevSU3().contains("true"))) {
                buf.append(" (").append(_t("Development updates enabled")).append(")");
            }
            buf.append(" &ensp;<a href=\"/configupdate\">").append(_t("Configure")).append("</a></td></tr>\n");
        }
        buf.append("<tr><td><b>").append(_t("Started")).append(":</b></td><td class=ajax>").append(new Date(_context.router().getWhenStarted()))
           .append(" &ensp;<b>").append(_t("Uptime")).append(":</b> ").append(DataHelper.formatDuration(_context.router().getUptime()))
           .append(" &ensp;<b>").append(_t("Clock Skew")).append(":</b> ").append(_context.clock().getOffset()).append("ms</td></tr>\n")
           .append("</table>\n</div>\n");

        buf.append("<div class=tablewrap><h3 id=transports>").append(_t("Router Transport Addresses"))
           .append("</h3>\n<pre id=activetransports class=ajax>\n");
        SortedMap<String, Transport> transports = _context.commSystem().getTransports();
        if (!transports.isEmpty()) {
            for (Transport t : transports.values()) {
                if (t.hasCurrentAddress()) {
                    for (RouterAddress ra : t.getCurrentAddresses()) {
                        buf.append(ra.toString()).append("\n\n");
                    }
                } else {buf.append(_t("{0} is used for outbound connections only", t.getStyle())).append("\n\n");}
            }
        } else {buf.append(_t("none"));}
        buf.append("</pre>\n</div>");
        out.append(buf);
        // UPnP Status
        _context.commSystem().renderStatusHTML(_out);
        out.flush();
    }

}