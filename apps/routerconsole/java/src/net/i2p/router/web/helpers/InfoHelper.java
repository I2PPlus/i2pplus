package net.i2p.router.web.helpers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;

import net.i2p.CoreVersion;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterVersion;
import net.i2p.router.web.HelperBase;

import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.helpers.ConfigAdvancedHelper;

import java.util.SortedMap;
import net.i2p.router.transport.Transport;

public class InfoHelper extends HelperBase {
    private boolean _full;

    public InfoHelper() {}

    public void setFull(String f) {
        _full = f != null && f.length() > 0;
    }

    public String getConsole() {
        try {
            if (_out != null) {
                renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(2*1024);
                renderStatusHTML(new OutputStreamWriter(baos));
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error displaying the console.</b>";
        }
    }

    public String getStats() {
        StatsGenerator gen = new StatsGenerator(_context);
        try {
            if (_out != null) {
                gen.generateStatsPage(_out, _full);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                gen.generateStatsPage(new OutputStreamWriter(baos), _full);
                return baos.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error displaying the console.</b>";
        }
    }

    /** @return host or "unknown" */
    public String getUdpIP() {
        String rv = _context.getProperty(UDPTransport.PROP_IP);
        if (rv != null)
            return rv;
        RouterAddress addr = _context.router().getRouterInfo().getTargetAddress("SSU");
        if (addr != null) {
            rv = addr.getHost();
            if (rv != null)
                return rv;
        }
        addr = _context.router().getRouterInfo().getTargetAddress("NTCP");
        if (addr != null) {
            rv = addr.getHost();
            if (rv != null)
                return rv;
        }
        return _t("unknown");
    }

    public String lastCountry() {
        return _context.getProperty("i2np.lastCountry");
    }

    public String getUdpPort() {
        return _context.getProperty("i2np.udp.port");
    }

    public String firstInstalled() {
        return _context.getProperty("router.firstInstalled");
    }

    public String firstVersion() {
        return _context.getProperty("router.firstVersion");
    }

    public String lastUpdated() {
        return _context.getProperty("router.updateLastInstalled");
    }

    public String updatePolicy() {
        return _context.getProperty("router.updatePolicy");
    }

    public String updateDevSU3() {
        return _context.getProperty("router.updateDevSU3");
    }

    public String updateUnsigned() {
        if (_context.getProperty("router.updateUnsigned") != null)
            return _context.getProperty("router.updateUnsigned");
        else
            return "true";
    }

    public String bwIn() {
        String in = _context.getProperty("i2np.bandwidth.inboundKBytesPerSecond");
        if (in != null)
            return in;
        else
//            return "512";
            return "1024";
    }

    public String bwOut() {
        String out = _context.getProperty("i2np.bandwidth.outboundKBytesPerSecond");
        if (out != null)
            return out;
        else
//            return "48";
            return "512";
    }

    public String bwShare() {
        String share = _context.getProperty("router.sharePercentage");
        if (share != null)
            return share;
        else
            return "80";
    }

    public String codelInterval() {
        String interval = _context.getProperty("router.codelInterval");
        if (interval != null)
            return interval;
        else
            return "1000";
    }

    public String codelTarget() {
        String target = _context.getProperty("router.codelTarget");
        if (target != null)
            return target;
        else
            return "50";
    }

    private void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        Hash h = _context.routerHash();
        Date installDate = new Date();
        if (installDate != null)
            installDate.setTime(Long.parseLong(firstInstalled()));
        Date lastUpdate = new Date();
        lastUpdate.setTime(Long.parseLong(lastUpdated()));
        float bwO = Integer.parseInt(bwOut());
        float bwS = Integer.parseInt(bwShare());
        float shared = (bwO / 100 * bwS);
        int shareBW = (int) shared;
        String slash = System.getProperty("file.separator");
        String appDir = _context.getProperty("i2p.dir.base") + slash;
        String configDir = _context.getProperty("i2p.dir.config") + slash;
        // basic router information
        buf.append("<table>\n");
        if (h != null)
            buf.append("<tr><td><b>" + _t("Identity") + ":</b></td><td><code><a href=\"/netdb?r=.\" title =\"" +
                       _t("Network Database entry") + "\">" + h.toBase64() + "</a></code></td></tr>\n");
        if (getUdpIP() != null && getUdpPort() != null) {
            buf.append("<tr><td><b>" + _t("IP Address") + ":</b></td><td>" + getUdpIP());
            if (lastCountry() != null)
                buf.append(" &ensp;<img width=20 height=15 src=\"/flags.jsp?c=" + lastCountry() + "\">");
            buf.append(" &ensp;<b>" + _t("UDP Port") + ":</b> " + getUdpPort() + " &ensp;<b>" + _t("Status") +
                       ":</b> " + _t(_context.commSystem().getStatus().toStatusString()));
            buf.append(" &ensp;<b>" + _t("Floodfill Role") + ":</b> ");
            if (_context.netDb().floodfillEnabled())
                buf.append(_t("Active"));
            else
                buf.append(_t("Inactive"));
            Boolean hiddenFF = _context.getBooleanProperty("router.hideFloodfillParticipant");
            if (hiddenFF != null && hiddenFF)
                buf.append(" (" + _t("unpublished") + ")");
            buf.append("&ensp;<a href=\"/configadvanced\">" + _t("Configure") + "</a></td></tr>\n");
        }
        if (bwIn() != null && bwOut() != null && bwShare() != null) {
            buf.append("<tr><td><b>" + _t("Bandwidth") + ":</b></td><td><b>" +  _t("Inbound") + ":</b> " + bwIn() +
                       "KB/s &ensp;<b>" + _t("Outbound") + ":</b> " + bwOut() + "KB/s &ensp;<b>" +
                       _t("Shared") + ":</b> " + bwShare() + "% (" + shareBW + "KB/s) &ensp;<a href=\"/config\">" +
                       _t("Configure") + "</a></td></tr>\n");
        }
        Boolean isAdvanced = _context.getBooleanProperty("routerconsole.advanced");
        if (isAdvanced) {
            buf.append("<tr><td><b>CoDel:</b></td><td><b>" + _t("Target") + ":</b> " + codelTarget() + "ms &ensp;<b>" +
                       _t("Interval") + ":</b> " + codelInterval() + "ms</td></tr>\n");
        }
        if (firstInstalled() != null && firstVersion() != null && lastUpdated() != null) {
            buf.append("<tr><td><b>" + _t("Installed") + ":</b></td><td>" + installDate + " (" + firstVersion() + ")" +
                       " &ensp;<span class=\"nowrap\"><b>" + _t("Location") + ":</b> " + appDir.toString() + "</span>" +
                       " &ensp;<span class=\"nowrap\"><b>" + _t("Config Dir") + ":</b> " + configDir + "</span></td></tr>\n");
            buf.append("<tr><td><b>" + _t("Updated") + ":</b></td><td>" + lastUpdate);
            if (updatePolicy() != null)
                buf.append(" &ensp;<b>" + _t("Update Policy") + ":</b> " + updatePolicy());
            if ((updateUnsigned() != null && updateUnsigned().contains("true")) || (updateDevSU3() !=null && updateDevSU3().contains("true")))
                buf.append(" (" + _t("Development updates enabled") + ")");
            buf.append(" &ensp;<a href=\"/configupdate\">" + _t("Configure") + "</a></td></tr>\n");
        }
        buf.append("<tr><td><b>" + _t("Started") + ":</b></td><td>" + new Date(_context.router().getWhenStarted()) +
                   " &ensp;<b>" + _t("Uptime") + ":</b> " + DataHelper.formatDuration(_context.router().getUptime()) +
                   " &ensp;<b>" + _t("Clock Skew") + ":</b> " + _context.clock().getOffset() + "ms</td></tr>\n");
        buf.append("</table>\n");

        buf.append("<h3 id=\"transports\">").append(_t("Router Transport Addresses")).append("</h3>\n<pre id=\"activetransports\">\n");
        SortedMap<String, Transport> transports = _context.commSystem().getTransports();
        if (!transports.isEmpty()) {
            for (Transport t : transports.values()) {
                if (t.hasCurrentAddress()) {
                    for (RouterAddress ra : t.getCurrentAddresses()) {
                        buf.append(ra.toString());
                        buf.append("\n\n");
                    }
                } else {
                    buf.append(_t("{0} is used for outbound connections only", t.getStyle()));
                    buf.append("\n\n");
                }
            }
        } else {
            buf.append(_t("none"));
        }
        buf.append("</pre>\n");
        out.write(buf.toString());
        // UPnP Status
        _context.commSystem().renderStatusHTML(_out);
        out.flush();
    }
}
