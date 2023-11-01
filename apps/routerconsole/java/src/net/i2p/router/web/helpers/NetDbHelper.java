package net.i2p.router.web.helpers;

import java.io.IOException;
import java.text.Collator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.SystemVersion;
import net.i2p.router.sybil.Analysis;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.Messages;

/**
 *  /netdb
 *  A FormHandler since 0.9.38.
 *  Most output is generated in NetDbRenderer and SybilRender.
 */
public class NetDbHelper extends FormHandler {
    private String _routerPrefix;
    private String _version;
    private String _country;
    private String _family, _caps, _ip, _sybil, _mtu, _ssucaps, _ipv6, _transport, _hostname, _sort;
    private int _full, _port, _cost, _page, _mode, _highPort, _icount;
    private long _date;
    private int _limit = DEFAULT_LIMIT;
    private boolean _lease;
    private boolean _clientOnly;
    private boolean _debug;
    private boolean _graphical;
    private SigType _type;
    private EncType _etype;
    private String _newNonce;
    private boolean _postOK;

    private static final int DEFAULT_LIMIT = SystemVersion.isSlow() ? 250 : 1000;
    private static final int DEFAULT_PAGE = 0;

    public boolean isFloodfill() {
        return _context.netDb().floodfillEnabled();
    }

    private static final String titles[] =
                                          {_x("Summary"),                       // 0  -
                                           _x("Local Router"),                  // 1  - r=.
                                           _x("Router Lookup"),                 // 2  -
                                           _x("All Routers"),                   // 3  - f=2
                                           _x("All Routers"),                   // 4  - f=1 (debug)
                                           _x("LeaseSets"),                     // 5  - l=1
                                           _x("LeaseSets"),                     // 6  - l=2 (debug)
                                           _x("LeaseSets"),                     // 7  - l=3
                                           _x("Advanced Lookup"),               // 8  - f=4
                                           _x("LeaseSet Lookup"),               // 9  -
                                           _x("All Routers (Client NetDb)"),    // 10 - f=5
                                           _x("All Routers (Client NetDb)"),    // 11 - f=6 (debug)
                                           _x("Sybil Analysis"),                // 12 - f=3
                                          };

    private static final String links[] =
                                          {"",                                  // 0
                                           "?r=.",                              // 1
                                           "",                                  // 2
                                           "?f=2",                              // 3
                                           "?f=1",                              // 4
                                           "?l=1",                              // 5
                                           "?l=2",                              // 6
                                           "?l=3",                              // 7
                                           "?f=4",                              // 8
                                           "",                                  // 9
                                           "?f=5",                              // 10
                                           "?f=6",                              // 11
                                           "?f=3",                              // 12
                                          };


    public void setRouter(String r) {
        if (r != null && r.length() > 0)
            _routerPrefix = DataHelper.stripHTML(r);  // XSS
    }

    /** @since 0.9.21 */
    public void setVersion(String v) {
        if (v != null && v.length() > 0)
            _version = DataHelper.stripHTML(v);  // XSS
    }

    /** @since 0.9.21 */
    public void setCountry(String c) {
        if (c != null && c.length() > 0)
            _country = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setFamily(String c) {
        if (c != null && c.length() > 0)
            _family = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setCaps(String c) {
        if (c != null && c.length() > 0)
            _caps = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setIp(String c) {
        if (c != null && c.length() > 0)
            _ip = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setSybil(String c) {
        if (c != null)
            _sybil = DataHelper.stripHTML(c);  // XSS
    }

    /** For form, same as above but with a length check
     *  @since 0.9.28
     */
    public void setSybil2(String c) {
        if (c != null && c.length() > 0)
            _sybil = DataHelper.stripHTML(c);  // XSS
    }

    /** @since 0.9.28 */
    public void setPort(String f) {
        if (f == null)
            return;
        try {
            int dash = f.indexOf('-');
            if (dash > 0) {
                _port = Integer.parseInt(f.substring(0, dash).trim());
                _highPort = Integer.parseInt(f.substring(dash + 1).trim());
            } else {
                _port = Integer.parseInt(f.trim());
            }
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.28 */
    public void setType(String f) {
        if (f != null && f.length() > 0)
            _type = SigType.parseSigType(f);
    }

    /** @since 0.9.49 */
    public void setEtype(String f) {
        if (f != null && f.length() > 0)
            _etype = EncType.parseEncType(f);
    }

    /** @since 0.9.28 */
    public void setMtu(String f) {
        if (f != null && f.length() > 0)
            _mtu = DataHelper.stripHTML(f);  // XSS
    }

    /** @since 0.9.28 */
    public void setIpv6(String f) {
        if (f != null && f.length() > 0) {
            _ipv6 = DataHelper.stripHTML(f);  // XSS
            if (!_ipv6.endsWith(":"))
                _ipv6 = _ipv6 + ':';
        }
    }

    /** @since 0.9.28 */
    public void setSsucaps(String f) {
        if (f != null && f.length() > 0)
            _ssucaps = DataHelper.stripHTML(f);  // XSS
    }

    /** @since 0.9.36 */
    public void setTransport(String f) {
        if (f != null && f.length() > 0)
            _transport = DataHelper.stripHTML(f).toUpperCase(Locale.US);
    }

    /** @since 0.9.28 */
    public void setCost(String f) {
        try {
            _cost = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.38 */
    public void setMode(String f) {
        try {
            _mode = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.38 */
    public void setDate(String f) {
        try {
            _date = Long.parseLong(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setFull(String f) {
        try {
            _full = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setLease(String l) {
        _clientOnly = "3".equals(l);
        _debug = "2".equals(l);
        _lease = _debug || "1".equals(l);
    }

    /** @since 0.9.57 */
    public void setLeaseset(String f) {
        if (f != null && f.length() > 0)
            _hostname = DataHelper.stripHTML(f);
    }

    /** @since 0.9.36 */
    public void setLimit(String f) {
        try {
            _limit = Integer.parseInt(f);
            if (_limit <= 0)
                _limit = Integer.MAX_VALUE;
            else if (_limit <= 10)
                _limit = 10;
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.36 */
    public void setPage(String f) {
        try {
            _page = Integer.parseInt(f) - 1;
            if (_page < 0)
                _page = 0;
        } catch (NumberFormatException nfe) {}
    }

    /** @since 0.9.57 */
    public void setSort(String f) {
        _sort = f;
    }

    /** @since 0.9.58 */
    public void setIntros(String f) {
        try {
            _icount = Integer.parseInt(f);
        } catch (NumberFormatException nfe) {}
    }

    public void setClientPage(String f) {
        try {

        } catch(Exception e){
            //if (_log.shouldLog)
        }
    }

    /**
     *  call for non-text-mode browsers
     *  @since 0.9.1
     */
    public void allowGraphical() {
        _graphical = true;
    }

    /**
     *  Override to save it
     *  @since 0.9.38
     */
    @Override
    public String getNewNonce() {
        _newNonce = super.getNewNonce();
        return _newNonce;
    }

    /**
     *  Now we're a FormHandler
     *  @since 0.9.38
     */
    protected void processForm() {
        _postOK = "Start Scan".equals(_action) ||
                  "Review".equals(_action);
        if ("Save".equals(_action)) {
                try {
                    Map<String, String> toSave = new HashMap<String, String>(4);
                    String newTime = getJettyString("runFrequency");
                    if (newTime != null) {
                        long ntime = Long.parseLong(newTime) * 60*60*1000;
                        toSave.put(Analysis.PROP_FREQUENCY, Long.toString(ntime));
                    }
                    String thresh = getJettyString("threshold");
                    if (thresh != null && thresh.length() > 0) {
                        float val = Math.max(Float.parseFloat(thresh), Analysis.MIN_BLOCK_POINTS);
                        toSave.put(Analysis.PROP_THRESHOLD, Float.toString(val));
                    }
                    String days = getJettyString("days");
                    if (days != null && days.length() > 0) {
                        long val = 24*60*60*1000L * Integer.parseInt(days);
                        toSave.put(Analysis.PROP_BLOCKTIME, Long.toString(val));
                    }
                    String age = getJettyString("deleteAge");
                    if (age != null && age.length() > 0) {
                        long val = 24*60*60*1000L * Integer.parseInt(age);
                        toSave.put(Analysis.PROP_REMOVETIME, Long.toString(val));
                    }
                    String enable = getJettyString("block");
                    toSave.put(Analysis.PROP_BLOCK, Boolean.toString(enable != null));
                    String nonff = getJettyString("nonff");
                    toSave.put(Analysis.PROP_NONFF, Boolean.toString(nonff != null));
                    if (_context.router().saveConfig(toSave, null))
                        addFormNotice(_t("Configuration saved successfully."));
                    else
                        addFormError("Error saving the configuration (applied but not saved) - please see the error logs");
                    Analysis.getInstance(_context).schedule();
                } catch (NumberFormatException nfe) {
                        addFormError("bad value");
                }
        }
    }

    /**
     *   storeWriter() must be called previously
     */
    public String getFloodfillNetDbSummary() {
        return getNetDbSummary(null, false);
    }

    public String getNetDbSummary(Hash client, boolean clientOnly) {
        NetDbRenderer renderer = new NetDbRenderer(_context);
        try {
            if (client == null && !clientOnly)
                renderNavBar();
            if (_routerPrefix != null || _version != null || _country != null ||
                _family != null || _caps != null || _ip != null || _sybil != null ||
                _port != 0 || _type != null || _mtu != null || _ipv6 != null ||
                _ssucaps != null || _transport != null || _cost != 0 || _etype != null ||
                _icount > 0) {
                if (client == null && !clientOnly)
                renderer.renderRouterInfoHTML(_out, _limit, _page,
                                              _routerPrefix, _version, _country,
                                              _family, _caps, _ip, _sybil, _port, _highPort, _type, _etype,
                                              _mtu, _ipv6, _ssucaps, _transport, _cost, _icount, client, clientOnly);
            } else if (_lease) {
                renderer.renderLeaseSetHTML(_out, _debug, client, _clientOnly);
            } else if (_hostname != null) {
                renderer.renderLeaseSet(_out, _hostname, true);
            } else if (_full == 3) {
                if (_mode == 12 && !_postOK)
                    _mode = 0;
                else if ((_mode == 13 || _mode == 16) && !_postOK)
                    _mode = 14;
                (new SybilRenderer(_context)).getNetDbSummary(_out, _newNonce, _mode, _date);
            } else if (_full == 4) {
                renderLookupForm();
            } else if (_full == 5) {
                renderer.renderStatusHTML(_out, _limit, _page, _full, null, true);
            } else if (_full == 6) {
                renderer.renderStatusHTML(_out, _limit, _page, _full, null, true);
            } else if (_clientOnly && client == null) {
                for (Hash _client : _context.clientManager().getPrimaryHashes()) {
                    renderer.renderLeaseSetHTML(_out, _debug, _client, clientOnly);
                }
            } else {
                if (_full == 0 && _sort != null)
                    _full = 3;
                renderer.renderStatusHTML(_out, _limit, _page, _full, client, clientOnly);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }

    public String getClientNetDbSummary(Hash client) {
        return getNetDbSummary(client, true);
    }

    /**
     *  @since 0.9.1
     */
    private int getTab() {
        if (_debug)
            return 6;
        if (_lease)
            return 5;
        if (".".equals(_routerPrefix))
            return 1;
        if (_routerPrefix != null || _version != null || _country != null ||
            _family != null || _caps != null || _ip != null || _sybil != null ||
            _port != 0 || _type != null || _mtu != null || _ipv6 != null ||
            _ssucaps != null || _transport != null || _cost != 0 || _etype != null)
            return 2;
        if (_full == 2)
            return 3;
        if (_full == 1)
            return 4;
        if (_clientOnly)
            return 7;
        if (_full == 4)
            return 8;
        if (_hostname != null)
            return 9;
        if (_full == 5)
            return 10;
        if (_full == 6)
            return 11;
        if (_full == 3)
            return 12;
        return 0;
    }

    /**
     *  @since 0.9.1
     */
    private void renderNavBar() throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        buf.append("<div class=confignav id=confignav>");
        boolean span = _graphical;
        if (!span)
            buf.append("<center>");
        int tab = getTab();
        for (int i = 0; i < titles.length; i++) {
            if (i == 2 && tab != 2)
                continue;   // can't nav to lookup
            if (i == 9 && tab != 9)
                continue;   // can't nav to lookup
            if (i == 3 && isAdvanced())
                continue; // only show All Routers (with full stats) in adv. mode
            if (i == 4 && !isAdvanced())
                continue; // and hide Routers (with full stats) from normal mode
            if (i == 5 || i== 6)
                continue; // hide standard Leasesets tab in normal/adv. mode,
                          // default to client LSs. TODO: Add link to main when ff
                          // on client page.
            //if (i > 5 && !isAdvanced())
            //    continue;
            if (i == 10 || i == 11)
                continue;
            if (i == 10 || i == 11) {
                if (_context.netDb().getRouters().size() == 0) {
                    continue;
                }
            }
            if (i == tab) {
                // we are there
                if (span)
                    buf.append("<span class=tab2>");
                buf.append(_t(titles[i]));
            } else {
                // we are not there, make a link
                if (span)
                    buf.append("<span class=tab>");
                buf.append("<a href=\"netdb").append(links[i]).append("\">").append(_t(titles[i])).append("</a>");
            }
            if (span) {
                buf.append("</span>\n");
            } else if (i != titles.length - 1) {
                buf.append("&nbsp;&nbsp;\n");
            }
        }
        if (!span)
            buf.append("</center>");
        buf.append("</div>\n");
        _out.write(buf.toString());
    }

    /**
     *  @since 0.9.28
     */
    private void renderLookupForm() throws IOException {
        _out.write("<form action=\"/netdb\" method=GET>\n" +
                   "<input type=hidden name=\"nonce\" value=\"" + _newNonce + "\" >\n" +
/*
                   "<div id=lookupCondensed hidden>" +
                   "<select name=\"netdbLookup\">" +
                   "<option value=\"caps\" selected=selected>Capabilities</option>" +
                   "<option value=\"cost\">Cost</option>" +
                   "<option value=\"c\">Country Code</option>" +
                   "<option value=\"fam\">Family</option>" +
                   "<option value=\"r\">Hash Prefix</option>" +
                   "<option value=\"ip\">IP or Hostname</option>" +
                   "<option value=\"ipv6\">IPV6 Prefix</option>" +
                   "<option value=\"mtu\">MTU</option>" +
                   "<option value=\"port\">Port Number</option>" +
                   "<option value=\"type\">Signature Type</option>" +
                   "<option value=\"ssucaps\">SSU Capabilities</option>" +
                   "<option value=\"v\">Router Version</option>" +
                   "</select>" +
                   "<input type=text size=30></div>" +
*/
                   "<table id=netdblookup><tr><th colspan=4>Network Database Search</th></tr>\n" +
                   "<tr><td colspan=4 class=subheading><b>Enter one search field <i>only</i></b></td></tr>\n" +
                   "<tr><td><b>Capabilities</b></td><td><input type=text name=\"caps\" title=\"e.g. f or XOfR\"></td>\n" +
                   "<td><b>Cost</b></td><td><input type=text name=\"cost\"></td></tr>\n" +
                   "<tr><td><b>Country</b></td><td><select name=\"c\"><option value=\"\" selected=selected></option>");
        Map<String, String> sorted = new TreeMap<String, String>(Collator.getInstance());
        for (Map.Entry<String, String> e : _context.commSystem().getCountries().entrySet()) {
            String tr = Messages.getString(e.getValue(), _context, Messages.COUNTRY_BUNDLE_NAME);
            sorted.put(tr, e.getKey());
        }
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            _out.write("<option value=\"" + e.getValue() + "\">" + e.getKey() + "</option>\n");
        }
        _out.write("</select></td>" +
                   "<td><b>Country Code</b></td><td><input type=text name=\"c\" title=\"e.g. ru\"></td></tr>\n" +
                   "<tr><td><b>Hash Prefix</b></td><td><input type=text name=\"r\"></td>\n" +
                   "<td><b>IP Address</b></td><td><input type=text name=\"ip\" " +
                   "title=\"IPv4 or IPv6, /24,/16,/8 suffixes optional for IPv4, prefix ok for IPv6\"></td></tr>\n" +
                   "<tr><td><b>Hostname or b32</b></td><td><input type=text name=\"ls\"></td>\n" +
                   "<td><b>Router Family</b></td><td><input type=text name=\"fam\"></td></tr>\n" +
                   "<tr><td><b>IPv6 Prefix</b></td><td><input type=text name=\"ipv6\"></td>\n" +
                   "<td><b>MTU</b></td><td><input type=text name=\"mtu\"></td></tr>\n" +
                   "<tr><td><b>Single port or range</b></td><td><input type=text name=\"port\"></td>\n" +
                   "<td><b>Signature Type</b></td><td><select name=\"type\"><option value=\"\" selected=selected></option>");
        for (SigType type : EnumSet.allOf(SigType.class)) {
            _out.write("<option value=\"" + type + "\">" + type + "</option>\n");
        }
        _out.write("</select></td></tr>\n" +
                   "<tr><td><b>SSU Capabilities</b></td><td><input type=text name=\"ssucaps\"></td>\n" +
                   "<td><b>Encryption Type</b></td><td><select name=\"etype\"><option value=\"\" selected=selected></option>");
        for (EncType type : EnumSet.allOf(EncType.class)) {
            _out.write("<option value=\"" + type + "\">" + type + "</option>\n");
        }
        _out.write("</select></td></tr>\n" +
                   "<tr><td><b>Router Version</b></td><td><input type=text name=\"v\"></td>\n" +
                   "<td><b>Transport</b></td><td><select name=\"tr\"><option value=\"\" selected=selected>" +
                   "<option value=\"NTCP\">NTCP</option>\n" +
                   "<option value=\"NTCP2\">NTCP2</option>\n" +
                   "<option value=\"SSU\">SSU</option>\n" +
                   "<option value=\"SSU2\">SSU2</option>\n" +
                   "</select></td></tr>\n" +
                   "<tr><td colspan=4 class=subheading><b>Add Sybil analysis (must pick one above)</b></td></tr>\n" +
                   "<tr id=sybilSearch><td><b>Sybil close to</b></td><td colspan=3><input type=text name=\"sybil2\" " +
                   "title=\"Router hash, destination hash, b32, or from address book\">&nbsp;" +
                   "<label for=\"closetorouter\"><b>or Sybil close to this router</b></label>" +
                   "<input type=checkbox class=optbox value=1 name=\"sybil\" id=closetorouter></td></tr>\n" +
                   "<tr><td colspan=4 class=optionsave><button type=submit class=search value=\"Lookup\">Lookup</button></td></tr>\n" +
                   "</table>\n</form>\n");
    }
}
