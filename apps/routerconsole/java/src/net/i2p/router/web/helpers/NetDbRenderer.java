package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger; // debug
import java.text.Collator;
import java.text.DecimalFormat; // debug
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.JobImpl;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.RouterContext;
import static net.i2p.router.sybil.Util.biLog2;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.util.HashDistance; // debug
import net.i2p.router.web.Messages;
import net.i2p.util.Addresses;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

class NetDbRenderer {
    private final RouterContext _context;
    public NetDbRenderer (RouterContext ctx) {_context = ctx;}
    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}
    public static final int LOOKUP_WAIT = 8 * 1000;
    public boolean isFloodfill() {return _context.netDb().floodfillEnabled();}

    /**
     *  Inner class, can't be Serializable
     */
    private class LeaseSetComparator implements Comparator<LeaseSet> {
         public int compare(LeaseSet l, LeaseSet r) {
             Hash keyL = l.getHash();
             Hash keyR = r.getHash();
             TunnelPoolSettings inL = _context.tunnelManager().getInboundSettings(keyL);
             TunnelPoolSettings inR = _context.tunnelManager().getInboundSettings(keyR);

             boolean isClientL = !_context.clientNetDb(keyL).toString().contains("Main");
             boolean isClientR = !_context.clientNetDb(keyR).toString().contains("Main");
             boolean isMetaL = l.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
             boolean isMetaR = r.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
             boolean nicknameL = inL != null && inL.getDestinationNickname() != null;
             boolean nicknameR = inR != null && inR.getDestinationNickname() != null;
             boolean nameL =  _context.namingService().reverseLookup(keyL) != null && !isMetaL;
             boolean nameR =  _context.namingService().reverseLookup(keyR) != null && !isMetaR;
             boolean publishedL = _context.clientManager().shouldPublishLeaseSet(keyL) && !isMetaL;
             boolean publishedR = _context.clientManager().shouldPublishLeaseSet(keyR) && !isMetaR;
             boolean localL = _context.clientManager().isLocal(keyL) && !isMetaL;
             boolean localR = _context.clientManager().isLocal(keyR) && !isMetaR;

             if (publishedL && !publishedR) return -1;
             if (publishedR && !publishedL) return 1;
             if (isClientL && !isClientR) return -1;
             if (isClientR && !isClientL) return 1;
             if (nicknameL && !nicknameR) return -1;
             if (nicknameR && !nicknameL) return 1;
             if (nameL && !nameR) return -1;
             if (nameR && !nameL) return 1;

             return keyL.toBase32().compareTo(keyR.toBase32());
        }
    }

    /** for debugging @since 0.7.14 */
    private static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet>, Serializable {
         private final Hash _us;
         public LeaseSetRoutingKeyComparator(Hash us) {_us = us;}
         public int compare(LeaseSet l, LeaseSet r) {
             return HashDistance.getDistance(_us, l.getRoutingKey()).compareTo(HashDistance.getDistance(_us, r.getRoutingKey()));
        }
    }

    /**
     *  At least one String must be non-null, non-empty
     *
     *  @param page zero-based
     *  @param routerPrefix may be null. "." for our router only
     *  @param version may be null
     *  @param country may be null
     *  @param family may be null
     *  @param highPort if nonzero, a range from port to highPort inclusive
     */

    public void renderRouterInfoHTML(Writer out, int pageSize, int page, String routerPrefix, String version,
                                     String country, String family, String caps, String ip, String sybil, int port,
                                     int highPort, SigType type, EncType etype, String mtu, String ipv6, String ssucaps,
                                     String tr, int cost, int icount) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        List<Hash> sybils = sybil != null ? new ArrayList<Hash>(128) : null;
        NetworkDatabaseFacade netdb = _context.netDb();

        if (".".equals(routerPrefix)) {
            buf.append("<p class=infowarn><b>")
               .append(_t("Never reveal your router identity to anyone, as it is uniquely linked to your IP address in the network database."))
               .append("</b></p>\n");
            renderRouterInfo(buf, _context.router().getRouterInfo(), true, true);
        } else if (routerPrefix != null && routerPrefix.length() >= 44) {
            // easy way, full hash
            byte[] h = Base64.decode(routerPrefix);
            if (h != null && h.length == Hash.HASH_LENGTH) {
                Hash hash = new Hash(h);
                RouterInfo ri = (RouterInfo) netdb.lookupLocallyWithoutValidation(hash);
                boolean banned = false;
                if (ri == null) {
                    LookupWaiter lw = new LookupWaiter();
                    synchronized(lw) {
                        netdb.lookupRouterInfo(hash, lw, lw, LOOKUP_WAIT); // remote lookup
                        try {lw.wait(LOOKUP_WAIT);}
                        catch (InterruptedException ie) {}
                    }
                }
                ri = (RouterInfo) netdb.lookupLocallyWithoutValidation(hash);
                if (ri != null) {renderRouterInfo(buf, ri, false, true);}
                else {
                    buf.append("<div class=netdbnotfound>").append(_t("Router")).append(' ')
                       .append(routerPrefix).append(' ').append(banned ? _t("is banned") : _t("not found in network database"))
                       .append(".").append("</div>");
                }
            } else {
                buf.append("<div class=netdbnotfound>").append(_t("Bad Base64 Router hash")).append(' ')
                   .append(DataHelper.escapeHTML(routerPrefix)).append("</div>");
            }
        } else {
            StringBuilder ubuf = new StringBuilder();
            if (routerPrefix != null) {ubuf.append("&amp;r=").append(routerPrefix);}
            if (version != null) {ubuf.append("&amp;v=").append(version);}
            if (country != null) {ubuf.append("&amp;c=").append(country);}
            if (family != null) {ubuf.append("&amp;fam=").append(family);}
            if (caps != null) {ubuf.append("&amp;caps=").append(caps);}
            if (tr != null) {ubuf.append("&amp;tr=").append(tr);}
            if (type != null) {ubuf.append("&amp;type=").append(type);}
            if (etype != null) {ubuf.append("&amp;etype=").append(etype);}
            if (ip != null) {ubuf.append("&amp;ip=").append(ip);}
            if (port != 0) {ubuf.append("&amp;port=").append(port);}
            if (mtu != null) {ubuf.append("&amp;mtu=").append(mtu);}
            if (ipv6 != null) {ubuf.append("&amp;ipv6=").append(ipv6);}
            if (ssucaps != null) {ubuf.append("&amp;ssucaps=").append(ssucaps);}
            if (cost != 0) {ubuf.append("&amp;cost=").append(cost);}
            if (sybil != null) {ubuf.append("&amp;sybil=").append(sybil);}
            String itag;
            if (icount > 0) {
                ubuf.append("&amp;i=").append(icount);
                itag = "itag" + (icount - 1);
            } else {itag = null;}
            Set<RouterInfo> routers = new HashSet<RouterInfo>();
            routers.addAll(_context.netDb().getRouters());
            int ipMode = 0;
            String ipArg = ip; // save for error message
            String altIPv6 = null;
            if (ip != null) {
                if (ip.endsWith("/24")) {ipMode = 1;}
                else if (ip.endsWith("/16")) {ipMode = 2;}
                else if (ip.endsWith("/8")) {ipMode = 3;}
                else if (ip.indexOf(':') > 0) {
                    ipMode = 4;
                    if (ip.endsWith("::")) {ip = ip.substring(0, ip.length() - 1);} // truncate for prefix search
                    else {altIPv6 = getAltIPv6(ip);} // We don't canonicalize as we search, so create alt string to check also
                }
                if (ipMode > 0 && ipMode < 4) {
                    for (int i = 0; i < ipMode; i++) {
                        int last = ip.substring(0, ip.length() - 1).lastIndexOf('.');
                        if (last > 0) {ip = ip.substring(0, last + 1);}
                    }
                }
            }
            if (ipv6 != null) {
                if (ipv6.endsWith("::")) {ipv6 = ipv6.substring(0, ipv6.length() - 1);} // truncate for prefix search
                else {altIPv6 = getAltIPv6(ipv6);} // We don't canonicalize as we search, so create alt string to check also
            }
            String familyArg = family; // save for error message
            if (family != null) {family = family.toLowerCase(Locale.US);}
            if (routerPrefix != null && !routers.isEmpty()) {filterHashPrefix(routers, routerPrefix);}
            if (version != null && !routers.isEmpty()) {filterVersion(routers, version);}
            if (country != null && !routers.isEmpty()) {filterCountry(routers, country);}
            if (caps != null && !routers.isEmpty()) {filterCaps(routers, caps);}
            if (type != null && !routers.isEmpty()) {filterSigType(routers, type);}
            if (etype != null && !routers.isEmpty()) {filterEncType(routers, etype);}
            if (tr != null && !routers.isEmpty()) {filterTransport(routers, tr);}
            if (family != null && !routers.isEmpty()) {filterFamily(routers, family);}
            if (ip != null && !routers.isEmpty()) {
                if (ipMode == 0) {filterIP(routers, ip);}
                else {filterIP(routers, ip, altIPv6);}
            }
            if (port != 0 && !routers.isEmpty()) {filterPort(routers, port, highPort);}
            if (mtu != null && !routers.isEmpty()) {filterMTU(routers, mtu);}
            if (ipv6 != null && !routers.isEmpty()) {filterIP(routers, ipv6, altIPv6);}
            if (ssucaps != null && !routers.isEmpty()) {filterSSUCaps(routers, ssucaps);}
            if (cost != 0 && !routers.isEmpty()) {filterCost(routers, cost);}
            if (itag != null && !routers.isEmpty()) {filterITag(routers, itag);}

            if (routers.isEmpty()) {
                buf.append("<div class=netdbnotfound>");
                buf.append(_t("No routers with")).append(' ');
                if (routerPrefix != null) {buf.append("[").append(_t("Hash prefix")).append(' ').append(routerPrefix).append("] ");}
                if (version != null) {buf.append("[").append(_t("Version")).append(' ').append(version).append("] ");}
                if (country != null) {buf.append("[").append(_t("Country")).append(' ').append(country).append("] ");}
                if (family != null) {buf.append("[").append(_t("Family")).append(' ').append(family).append("] ");}
                if (ip != null) {buf.append("[").append("IPv4 address ").append(ip).append("] ");}
                if (ipv6 != null) {buf.append("[").append("IPv6 address ").append(ipv6).append("] ");}
                if (port != 0) {
                    buf.append("[").append(_t("Port")).append(' ').append(port);
                    if (highPort != 0) {buf.append('-').append(highPort);}
                    buf.append("] ");
                }
                if (mtu != null) {buf.append("[").append(_t("MTU")).append(' ').append(mtu).append("] ");}
                if (cost != 0) {buf.append("[").append("Cost ").append(cost).append("] ");}
                if (type != null) {buf.append("[").append("Type ").append(type).append("] ");}
                if (etype != null) {buf.append("[").append("Type ").append(etype).append("] ");}
                if (caps != null) {buf.append("[").append("Caps ").append(caps).append("] ");}
                if (ssucaps != null) {buf.append("[").append("SSU Caps ").append(ssucaps).append("] ");}
                if (tr != null) {buf.append("[").append("Transport ").append(tr).append("] ");}
                buf.append(_t("found in the network database")).append(".</div>");
            } else {
                List<RouterInfo> results = new ArrayList<RouterInfo>(routers);
                int sz = results.size();
                if (sz > 1) {Collections.sort(results, RouterInfoComparator.getInstance());}
                boolean morePages = false;
                int toSkip = pageSize * page;
                int last = Math.min(toSkip + pageSize, sz - 1);
                if (last < sz - 1) {morePages = true;}
                for (int i = toSkip; i <= last; i++) {
                    RouterInfo ri = results.get(i);
                    renderRouterInfo(buf, ri, false, true);
                    if (sybil != null) {sybils.add(ri.getIdentity().getHash());}
                    if ((i & 0x07) == 0) {
                        out.append(buf);
                        buf.setLength(0);
                    }
                }
                paginate(buf, ubuf, page, pageSize, morePages, sz);
            }
        }
        out.append(buf);
        out.flush();
        if (sybil != null)
        SybilRenderer.renderSybilHTML(out, _context, sybils, sybil);
    }

    /**
     *  @since 0.9.64 split out from above
     */
    private void paginate(StringBuilder buf, StringBuilder ubuf, int page, int pageSize, boolean morePages, int sz) {
        int totalPages = (int) Math.ceil((double) sz / pageSize);
        if (sz > 1) {
            String results = "<span id=results" + (sz > pageSize ? " class=more" : "") + ">" + sz + " " + _t("results") + "</span>\n";
            buf.append("<div id=pagenav>\n").append(results);
            if (sz > pageSize) {
                int current = page + 1;
                if (page == 0) {page++;}
                if (current > 1) {
                    buf.append("<a href=\"/netdb?pg=").append(page).append("&amp;ps=").append(pageSize).append(ubuf)
                       .append("\" title=\"").append(_t("Previous Page")).append("\"><span id=prevPage class=pageLink>").append("⏴</span></a>");
                }  else {
                    buf.append("<span id=prevPage class=\"pageLink disabled\">").append("⏴").append("</span>" );
                }
                for (int i = 1; i <= totalPages; i++) {
                    if (i <= totalPages) {
                        buf.append(" <a href=\"/netdb?pg=").append(i).append("&amp;ps=").append(pageSize).append(ubuf).append("\"")
                           .append(i == current ? " id=currentPage" : "").append(">")
                           .append("<span class=pageLink").append(">").append(i).append("</span></a> ");
                    }
                }
                if (current < totalPages) {
                    buf.append("<a href=\"/netdb?pg=").append(page + 2).append("&amp;ps=").append(pageSize).append(ubuf)
                       .append("\" title=\"").append(_t("Next Page")).append("\">").append("<span id=nextPage class=pageLink>⏵</span></a>\n");
                } else {
                    buf.append("<span id=nextPage class=\"pageLink disabled\">").append("⏵").append("</span>\n");
                }
            }
            //renderPageSizeInput(buf);
            buf.append("</div>\n");
        }
    }

    private void renderPageSizeInput(StringBuilder buf) {
        buf.append("<form id=pagesize hidden>\n")
           .append("<label>").append(_t("Results per page")).append(": ")
           .append("<input type=text name=pageSize value=\"\" maxlength=4 pattern=\"[0-9]{1,4}\"></label>\n")
           .append("<input type=submit value=").append(_t("Update")).append(">\n")
           .append("</form>");
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterHashPrefix(Set<RouterInfo> routers, String routerPrefix) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            Hash key = ri.getIdentity().getHash();
            if (!key.toBase64().startsWith(routerPrefix)) {iter.remove();}
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterVersion(Set<RouterInfo> routers, String version) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            if (!ri.getVersion().equals(version)) {iter.remove();}
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private void filterCountry(Set<RouterInfo> routers, String country) {
        String[] countryCodes = country.split("[, ]+");
        boolean foundMatch;
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            Hash key = ri.getIdentity().getHash();
            foundMatch = false;
            for (String countryCode : countryCodes) {
                if (_context.commSystem().getCountry(key).equalsIgnoreCase(countryCode.trim())) {
                    foundMatch = true;
                    break;
                }
            }
            if (!foundMatch) {iter.remove();}
        }
    }

    /**
     *  Count all floodfills in specified country code
     *  @since 0.9.64 split out from above
     */
    private int countFloodfillsInCountry(Set<RouterInfo> routers, String countryCode) {
        int count = 0;
        for (RouterInfo ri : routers) {
            String caps = ri.getCapabilities();
            Hash key = ri.getIdentity().getHash();
            if (_context.commSystem().getCountry(key).equalsIgnoreCase(countryCode.trim()) &&
                caps.indexOf("f") >=0) {
                count++;
            }
        }
        return count;
    }

    /**
     *  Count all X tier routers in specified country code
     *  @since 0.9.64 split out from above
     */
    private int countXTierInCountry(Set<RouterInfo> routers, String countryCode) {
        int count = 0;
        for (RouterInfo ri : routers) {
            String caps = ri.getCapabilities();
            Hash key = ri.getIdentity().getHash();
            if (_context.commSystem().getCountry(key).equalsIgnoreCase(countryCode.trim()) &&
                caps.indexOf("X") >=0) {
                count++;
            }
        }
        return count;
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterCaps(Set<RouterInfo> routers, String caps) {
        int len = caps.length();
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            String ca = ri.getCapabilities();
            for (int i = 0; i < len; i++) {
                // must contain all caps specified
                if (ca.indexOf(caps.charAt(i)) < 0) {
                    iter.remove();
                    continue outer;
                }
            }
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterSigType(Set<RouterInfo> routers, SigType type) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            if (ri.getIdentity().getSigType() != type) {iter.remove();}
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterEncType(Set<RouterInfo> routers, EncType type) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            if (ri.getIdentity().getEncType() != type) {iter.remove();}
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterTransport(Set<RouterInfo> routers, String tr) {
        String transport;
        int mode;
        if (tr.equals("NTCP_1")) {
            transport = "NTCP";
            mode = 0;
        } else if (tr.equals("NTCP_2")) {
            transport = "NTCP";
            mode = 1;
        } else if (tr.equals("SSU_1")) {
            transport = "SSU";
            mode = 2;
        } else if (tr.equals("SSU_2")) {
            transport = "SSU";
            mode = 3;
        } else {
            transport = tr;
            mode = 4;
        }
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            RouterAddress ra = ri.getTargetAddress(transport);
            if (ra != null) {
                switch (mode) {
                    case 0:
                    case 2:
                        if (ra.getOption("v") == null) {continue;}
                        break;

                    case 1:
                    case 3:
                        if (ra.getOption("v") != null) {continue;}
                        break;

                    case 4:
                        continue;
                }
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterFamily(Set<RouterInfo> routers, String family) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            String fam = ri.getOption("family");
            if (fam != null) {
                if (fam.toLowerCase(Locale.US).contains(family)) {continue;}
            }
            iter.remove();
        }
    }

    /**
     *  Exact match
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterIP(Set<RouterInfo> routers, String ip) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (ip.equals(ra.getHost())) {continue outer;}
            }
            iter.remove();
        }
    }

    /**
     *  Prefix
     *  Remove all non-matching from routers
     *  @param altip may be null
     *  @since 0.9.64 split out from above
     */
    private static void filterIP(Set<RouterInfo> routers, String ip, String altip) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                String host = ra.getHost();
                if (host != null && (host.startsWith(ip) || (altip != null && host.startsWith(altip)))) {
                    continue outer;
                }
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterPort(Set<RouterInfo> routers, int port, int highPort) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                int raport = ra.getPort();
                if (port == raport || (highPort > 0 && raport >= port && raport <= highPort)) {
                    continue outer;
                }
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterMTU(Set<RouterInfo> routers, String smtu) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (smtu.equals(ra.getOption("mtu"))) {continue outer;}
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterSSUCaps(Set<RouterInfo> routers, String caps) {
        int len = caps.length();
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            inner:
            for (RouterAddress ra : ri.getAddresses()) {
                String ca = ra.getOption("caps");
                if (ca == null) {continue;}
                for (int i = 0; i < len; i++) {
                    // must contain all caps specified
                    if (ca.indexOf(caps.charAt(i)) < 0) {break inner;}
                }
                continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterCost(Set<RouterInfo> routers, int cost) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getCost() == cost) {continue outer;}
            }
            iter.remove();
        }
    }

    /**
     *  Remove all non-matching from routers
     *  @since 0.9.64 split out from above
     */
    private static void filterITag(Set<RouterInfo> routers, String itag) {
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            for (RouterAddress ra : ri.getAddresses()) {
                if (ra.getOption(itag) != null) {continue outer;}
            }
            iter.remove();
        }
    }

    /**
     *  @since 0.9.48
     */
    private class LookupWaiter extends JobImpl {
        public LookupWaiter() {super(_context);}
        public void runJob() {synchronized(this) {notifyAll();}}
        public String getName() {return "Console NetDb Lookup";}
    }

    /**
     *  Special handling for 'O' cap
     *  @param caps non-null
     *  @since 0.9.38
     */
    private static boolean hasCap(RouterInfo ri, String caps) {
        String ricaps = ri.getCapabilities();
        if (caps.equals("O")) {
            return ricaps.contains(caps) &&
                   !ricaps.contains("P") &&
                   !ricaps.contains("X");
        } else {return ricaps.contains(caps);}
    }

    /**
     *  All the leasesets
     *
     *  @param debug @since 0.7.14 sort by distance from us, display
     *               median distance, and other stuff, useful when floodfill
     */
    public void renderLeaseSetHTML(Writer out, boolean debug, Hash client) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        if (!_context.netDb().isInitialized()) {
            buf.append("<div id=notinitialized>").append(_t("Not initialized")).append("</div>");
            out.append(buf);
            return;
        }

        boolean noLeasesets = _context.netDb().getLeases().size() <= 0;
        Hash ourRKey;
        DecimalFormat fmt;
        NetworkDatabaseFacade netdb;
        Set<LeaseSet> leases;
        boolean notLocal = (client == null || debug);
        int localLSCount = 0;

        if (client == null) {netdb = _context.netDb();}
        else {netdb = _context.clientNetDb(client);}

        if (notLocal) {
            ourRKey = _context.routerHash();
            leases = new TreeSet<LeaseSet>(new LeaseSetRoutingKeyComparator(ourRKey));
            fmt = new DecimalFormat("#0.00");
        } else {
            ourRKey = null;
            leases = new TreeSet<LeaseSet>(new LeaseSetComparator());
            fmt = null;
        }

        if (notLocal) {leases.addAll(netdb.getLeases());}
        else {
            if (netdb.getPublishedLeases().size() > 0) {leases.addAll(netdb.getPublishedLeases());}
            if (netdb.getUnpublishedLeases().size() > 0) {leases.addAll(netdb.getUnpublishedLeases());}
            localLSCount = netdb.getPublishedLeases().size() + netdb.getUnpublishedLeases().size();
        }
        int medianCount = 0;
        int rapCount = 0;
        BigInteger median = null;
        int c = 0;

        /** Summary */
        boolean ffEnabled = netdb.floodfillEnabled();
        if (debug) {buf.append("<table id=leasesetdebug>\n");}
        else if (client == null) {buf.append("<table id=leasesetsummary>\n");}

        if (notLocal) {
            buf.append("<tr><th colspan=2>").append(_t("Total Leasesets")).append(": ").append(leases.size()).append("</th>");
            if (debug) {buf.append("<th colspan=2 class=right><a href=\"/netdb?l=1\">").append(_t("Compact mode")).append("</a></th>");}
            else {buf.append("<th class=right><a href=\"/netdb?l=2\">").append(_t("Debug Mode")).append("</a></th>");}
            buf.append("</tr>\n");
        }

        if (debug) {
            RouterKeyGenerator gen = _context.routerKeyGenerator();
            if (leases.size() > 0) {
                buf.append("<tr id=rapLS><td><b>").append(_t("Published (RAP) Leasesets")).append("</b></td><td colspan=4>").append(netdb.getKnownLeaseSets())
                   .append(" (").append(_t("Sorted by hash distance, closest first.")).append(")</td></tr>\n");
            }
            buf.append("<tr><td><b>").append(_t("Mod Data")).append("</b></td><td>").append(DataHelper.getUTF8(gen.getModData())).append("</td>")
               .append("<td><b>").append(_t("Last Changed")).append("</b></td><td>").append(DataHelper.formatTime(gen.getLastChanged())).append("</td></tr>\n")
               .append("<tr><td><b>").append(_t("Next Mod Data")).append("</b></td><td>").append(DataHelper.getUTF8(gen.getNextModData())).append("</td>")
               .append("<td><b>").append(_t("Change in")).append("</b></td><td>").append(DataHelper.formatDuration(gen.getTimeTillMidnight())).append("</td></tr>\n");
        }


        if (client == null) {
            buf.append("<tr>");
            if (debug) {
                buf.append("<td><b>").append(_t("Floodfill mode enabled")).append("</b></td><td>")
                   .append(ffEnabled ? "<span class=yes>yes</span>" : "<span class=no>no</span>");
            } else {buf.append("<td colspan=2>");}
        }

        if (debug) {buf.append("</td><td><b>").append(_t("Routing Key")).append("</b></td><td>").append(ourRKey.toBase64());}
        else if (client == null) {buf.append("</td><td colspan=2>");}
        if (notLocal) {buf.append("</td></tr>\n</table>\n");}

        /** LeaseSets */
        if (!leases.isEmpty()) {
            boolean linkSusi = _context.portMapper().isRegistered("susidns");
            long now = _context.clock().now();
            for (LeaseSet ls : leases) {
                String distance;
                if (debug) {
                    medianCount = rapCount / 2;
                    BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                    // Find the center of the RAP leasesets
                    if (ls.getReceivedAsPublished()) {
                        rapCount++;
                        if (c++ == medianCount) {median = dist;}
                    }
                    distance = fmt.format(biLog2(dist));
                } else {distance = null;}
                renderLeaseSet(buf, ls, debug, now, linkSusi, distance);
                out.append(buf);
                buf.setLength(0);
            } // for each

            if (debug && isFloodfill()) {
                int ffCount = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
                int ffEstimated = Math.max(ffCount*4, 6000);
                if (median != null) {
                    double log2 = biLog2(median);
                    // 2 for 4 floodfills... -1 for median - this can be way off for unknown reasons
                    int total = (int) Math.round(Math.pow(2, 2 + 256 - 1 - log2));
                    buf.append("<table id=leasesetKeyspace>\n")
                       .append("<tbody><tr id=medianDistance><td><b>").append(_t("Median distance (bits)")).append(":</b></td><td colspan=3>")
                       .append(fmt.format(log2)).append("</td></tr>\n")
                       .append("<tr id=estimatedFF><td><b>").append(_t("Estimated total floodfills")).append(":</b></td><td colspan=3>")
                       .append(ffEstimated).append("</td></tr>\n")
                       .append("<tr id=estimatedLS><td><b>").append(_t("Estimated total leasesets")).append(":</b></td><td colspan=3>")
                       .append(total * rapCount / 4).append("</td></tr></tbody>\n</table>\n");
                }
            }
        } // !empty
        out.append(buf);
        out.flush();
    }

/** WIP
    public void renderLeaseSet(Writer out, String hostname, boolean debug) throws IOException {
        StringBuilder buf = new StringBuilder(3*1024);
        if (!_context.netDb().isInitialized()) {
            buf.append("<div id=notinitialized>").append(_t("Not initialized")).append("</div>");
            out.append(buf);
            return;
        }
        NetworkDatabaseFacade netdb = _context.netDb();
        Set<LeaseSet> leases = new HashSet<>(netdb.getLeases());
        Hash hash = ConvertToHash.getHash(hostname);
        List<LeaseSet> matchedLeases = new ArrayList<>();
        if (hash != null) {
            LeaseSet ls = netdb.lookupLeaseSetLocally(hash);
            if (ls != null) {matchedLeases.add(ls);}
        } else {matchedLeases = matchPartialHash(hostname, leases);}
        if (!matchedLeases.isEmpty()) {
            for (LeaseSet ls : matchedLeases) {
                BigInteger dist = HashDistance.getDistance(_context.routerHash(), ls.getRoutingKey());
                DecimalFormat fmt = new DecimalFormat("#0.00");
                String distance = fmt.format(biLog2(dist));
                long now = _context.clock().now();
                renderLeaseSet(buf, ls, true, now, false, distance);
            }
        } else {
            if (hash == null) {
                buf.append("<div class=netdbnotfound>")
                  .append(_t("Hostname {0} not found in network database", hostname))
                  .append("</div>");
            } else {
                buf.append("<div class=netdbnotfound>")
                   .append(_t("LeaseSet containing substring \'{0}\' not found in network database", hostname))
                   .append("</div>");
            }
        }
        out.append(buf);
        out.flush();
    }

    public List<LeaseSet> matchPartialHash(String hostname, Collection<LeaseSet> leases) {
        Hash hash = ConvertToHash.getHash(hostname);
        List matchedLeases = new ArrayList<>();
        for (LeaseSet lsQuery : leases) {
            String queryHashStr = lsQuery.getHash().toString();
            if (hash != null) {
                String b32 = hash.toBase32();
                String b64 = hash.toBase64();
                if (queryHashStr.equals(hash)) {matchedLeases.add(lsQuery);}
                else if (queryHashStr.contains(hostname) || queryHashStr.contains(b32) || queryHashStr.contains(b64)) {
                    matchedLeases.add(lsQuery);
                }
            } else if (queryHashStr.contains(hostname)) {matchedLeases.add(lsQuery);}
        }
        return matchedLeases;
    }
**/
    /**
     * Single LeaseSet
     * @since 0.9.57
     */
    public void renderLeaseSet(Writer out, String hostname, boolean debug) throws IOException {
        StringBuilder buf = new StringBuilder(3*1024);
        if (!_context.netDb().isInitialized()) {
            buf.append("<div id=notinitialized>").append(_t("Not initialized")).append("</div>");
            out.append(buf);
            return;
        }
        Hash hash = ConvertToHash.getHash(hostname);
        if (hash == null) {
            buf.append("<div class=netdbnotfound>").append(_t("Hostname {0} not found in network database", hostname)).append("</div>");
        } else {
            NetworkDatabaseFacade netdb;
            netdb = _context.netDb();
            LeaseSet ls = netdb.lookupLeaseSetLocally(hash);
            Set<LeaseSet> leases;
            leases = new TreeSet<LeaseSet>(new LeaseSetComparator());
            leases.addAll(netdb.getLeases());
            if (ls == null) {
                // Remote lookup
                LookupWaiter lw = new LookupWaiter();
                synchronized(lw) {
                    _context.netDb().lookupLeaseSetRemotely(hash, lw, lw, LOOKUP_WAIT, null);
                    try {lw.wait(LOOKUP_WAIT + 1000);} // Wait for the lookup to complete
                    catch (InterruptedException ie) {}
                }
                ls = _context.netDb().lookupLeaseSetLocally(hash);
            }
            if (ls != null) {
                BigInteger dist = HashDistance.getDistance(_context.routerHash(), ls.getRoutingKey());
                DecimalFormat fmt = new DecimalFormat("#0.00");
                String distance = fmt.format(biLog2(dist));
                long now = _context.clock().now();
                buf.append("<span id=singleLS></span>");
                renderLeaseSet(buf, ls, true, now, false, distance);
            } else {
                buf.append("<div class=netdbnotfound>").append(_t("LeaseSet for {0} not found in network database", hostname)).append("</div>");
            }
        }
        out.append(buf);
        out.flush();
    }

    /** @since 0.9.57 split out from above */
    private void renderLeaseSet(StringBuilder buf, LeaseSet ls, boolean debug, long now, boolean linkSusi, String distance) {
        if (!_context.netDb().isInitialized()) {
            buf.append("<div id=notinitialized>").append(_t("Not initialized")).append("</div>");
            return;
        }
        // warning - will be null for non-local encrypted
        Destination dest = ls.getDestination();
        Hash key = ls.getHash();
        int type = ls.getType();
        NetworkDatabaseFacade subDbKey = key != null ? _context.clientNetDb(key): null;
        if (key != null) {
            buf.append("<table class=\"leaseset lazy\" id=\"ls_").append(key.toBase32().substring(0,4)).append("\">");
        } else {buf.append("<table class=\"leaseset lazy\">");}
        buf.append("<tr><th><b class=lskey>");
        if (type == DatabaseEntry.KEY_TYPE_META_LS2) {buf.append(_t("Meta"));}
        buf.append(_t("LeaseSet")).append(":</b> <code title =\"")
           .append(_t("LeaseSet Key")).append("\">").append(key.toBase64()).append("</code>");
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || _context.keyRing().get(key) != null) {
            buf.append(" <b class=encls>[").append(_t("Encrypted")).append("]</b>");
        }
        buf.append("</th>");
        if (_context.clientManager().isLocal(key)) {
            buf.append("<th>");
            boolean published = _context.clientManager().shouldPublishLeaseSet(key);
            TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
            buf.append("<a href=\"tunnels#").append(key.toBase64().substring(0,4)).append("\"><span class=\"lsdest");
            if (published) {buf.append(" published");}
            buf.append("\" title=\"").append(_t("View local tunnels for destination"));
            if (published) {buf.append(" (").append(_t("published")).append(")");}
            buf.append("\">");
            if (in != null && in.getDestinationNickname() != null) {
                buf.append(DataHelper.escapeHTML(in.getDestinationNickname()));
            } else {buf.append(dest.toBase64().substring(0,6));}
            buf.append("</span></a></th></tr>\n");
        } else {
            buf.append("<th>");
            String host = (dest != null) ? _context.namingService().reverseLookup(dest) : null;
            if (host != null) {
                buf.append("<a class=destlink target=_blank href=\"http://").append(host).append("/\">").append(host).append("</a></th>");
            } else {
                if (dest != null) {
                    String b32 = key.toBase32();
                    String truncb32 = b32.substring(0, 5) + "…" + b32.substring(b32.length() - 13, b32.length() - 10) + ".b32.i2p";
                    buf.append("<a target=_blank href=\"http://").append(b32).append("\">").append(truncb32).append("</a>");
                } else {buf.append("n/a");}
                buf.append("</th></tr>\n");
            }
        }

        long exp;
        String bullet = "<span class=bullet>&nbsp; &bullet; &nbsp;</span>";
        buf.append("<tr><td>");
        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {exp = ls.getLatestLeaseDate() - now;}
        else {
            LeaseSet2 ls2 = (LeaseSet2) ls;
            long pub = now - ls2.getPublished();
            exp = ((LeaseSet2)ls).getExpires() - now;
        }
        buf.append("<span class=\"nowrap expiry\" title=\"").append(_t("Expiry")).append("\">").append(bullet).append("<b>");
        if (exp > 0) {buf.append(_t("Expires{0}", ":</b> " + DataHelper.formatDuration2(exp)).replace(" in", ""));}
        else {buf.append(_t("Expired{0} ago", ":</b> " + DataHelper.formatDuration2(0-exp)));}
        buf.append("</span>");

        if (debug) {
/*
            boolean asPublished = ls.getReceivedAsPublished();
            boolean asReply = ls.getReceivedAsReply();
            if (asPublished) {
                buf.append(" <span class=\"nowrap rxAsPub\" title=\"").append(_t("Received as published")).append("\"> ").append(bullet)
                   .append("<b>RAP</b></span>");
            } else if (asReply) {
                buf.append(" <span class=\"nowrap rxAsRep\" title=\"").append(_t("Received as reply")).append("\"> ").append(bullet)
                   .append("<b>RAR</b></span>");
            }
*/
            buf.append(' ').append(bullet).append("<b class=distance title=\"").append(_t("Distance")).append("\">")
               .append(_t("Distance")).append(":</b> ").append(distance)
               .append(" <span class=\"nowrap rkey\" title=\"").append(_t("Routing Key")).append("\">")
               .append(bullet).append("<b>").append(_t("Routing Key"))
               .append(":</b> ").append(ls.getRoutingKey().toBase64().substring(0,16))
               .append("&hellip;</span>");

            if (type != DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet2 ls2 = (LeaseSet2) ls;
                if (ls2.isOffline()) {
                    buf.append(" <span class=nowrap>").append(bullet).append("<b>").append(_t("Offline signed")).append(":</b> ");
                    exp = ls2.getTransientExpiration() - now;
                    if (exp > 0) {
                        buf.append(' ').append(bullet).append("<b>").append(_t("Expires{0}", ":</b> " + DataHelper.formatDuration2(exp)));
                    } else {
                        buf.append(' ').append(bullet).append("<b>").append(_t("Expired{0} ago", ":</b> " + DataHelper.formatDuration2(0-exp)));
                    }
                    buf.append("</span> <span class=nowrap>").append(bullet).append("<b>").append(_t("Type")).append(":</b> ")
                       .append(ls2.getTransientSigningKey().getType()).append("</span>");
                }
            }
        }

        String sigtype = "";
        boolean isLS2 = dest != null && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2;
        String stype = isLS2 ? dest.getSigningPublicKey().getType().toString().trim() :
                               ls.getSigningKey().getType().toString().trim(); // encrypted, show blinded key type
        if (stype.equals("EdDSA_SHA512_Ed25519")) {sigtype = "Ed25519";}
        else if (stype.equals("DSA_SHA1")) {sigtype = "SHA1";}
        else if (stype.equals("RedDSA_SHA512_Ed25519")) {sigtype = "Red25519";}
        else if (stype.equals("ECDSA_SHA256_P256")) {sigtype = "ECDSA/P256";}
        else if (stype.equals("ECDSA_SHA384_P384")) {sigtype = "ECDSA/P384";}
        else if (stype.equals("ECDSA_SHA512_P521")) {sigtype = "ECDSA/P521";}
        else if (stype.equals("RSA_SHA256_2048")) {sigtype = "RSA/2048";}
        else if (stype.equals("RSA_SHA384_3072")) {sigtype = "RSA/3072";}
        else if (stype.equals("RSA_SHA512_4096")) {sigtype = "RSA/4096";}

        buf.append("</td><td><span class=ls_crypto>")
           .append("<span class=\"nowrap stype\" title=\"").append(_t("Signature type")).append(": ")
           .append(stype).append("\">").append(bullet).append("<b>").append(_t("Signature type")).append(":</b> ")
           .append("<span>").append(sigtype).append("</span></span></span></td></tr>\n")
           .append("<tr class=ekeys><td colspan=2>");

        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
            buf.append("<span class=\"nowrap ekey\" title=\"").append(_t("Encryption Key")).append("\">").append(bullet)
               .append("<b>").append(_t("Encryption Key")).append(":</b> <span title=ELGAMAL_2048>ElGamal")
               .append(debug ? " <span class=pubKey title=\"" + _t("Public Key") + "\">[" +
                       ls.getEncryptionKey().toBase64().substring(0,8) + "&hellip;]</span>" : "")
               .append("</span>");
        } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
            LeaseSet2 ls2 = (LeaseSet2) ls;
            for (PublicKey pk : ls2.getEncryptionKeys()) {
                EncType etype = pk.getType();
                buf.append(" <span class=\"nowrap ekey\" title=\"").append(_t("Encryption Key")).append("\">").append(bullet)
                   .append("<b>").append(_t("Encryption Key")).append(":</b> ");
                if (etype != null) {
                    String enctype = "";
                    if (etype.toString().trim().equals("ECIES_X25519")) {enctype = "ECIES";}
                    else if (etype.toString().trim().equals("ELGAMAL_2048")) {enctype = "ElGamal";}
                    else if (etype.toString().trim().equals("MLKEM512_X25519")) {enctype = "MLKEM512";}
                    else if (etype.toString().trim().equals("MLKEM768_X25519")) {enctype = "MLKEM768";}
                    else if (etype.toString().trim().equals("MLKEM1024_X25519")) {enctype = "MLKEM1024";}
                    buf.append("<span title=\"").append(etype).append("\">").append(enctype)
                       .append(debug ? " <span class=pubKey title=\"" + _t("Public Key") + "\">[" +
                               pk.toBase64().substring(0,8) + "&hellip;]</span>" : "")
                       .append("</span>");
                }
                else {buf.append(_t("Unsupported type")).append(" ").append(pk.getUnknownTypeCode());}
                buf.append("</span>");
            }
        }
        buf.append("</td></tr>\n");

        if (ls.getLeaseCount() > 0) {
            buf.append("<tr").append(debug ? " class=debugMode" : "").append(">")
               .append("<td colspan=2>\n<ul class=netdb_leases>\n");
            boolean isMeta = ls.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
            for (int i = 0; i < ls.getLeaseCount(); i++) {
                Lease lease = ls.getLease(i);
                long exl = lease.getEndTime() - now;
                boolean expired = exl <= 0;
                String expiry = !expired ? _t("Expires in {0}", DataHelper.formatDuration2(exl))
                                         : _t("Expired {0} ago", DataHelper.formatDuration2(0-exl));
                buf.append("<li><b").append(" class=\"leaseNumber")
                   .append(expired ? " expired" : "").append("\" title=\"").append(expiry).append("\">")
                   .append(i + 1).append("</b> <span class=tunnel_peer title=Gateway>")
                   .append(_context.commSystem().renderPeerHTML(lease.getGateway(), false))
                   .append("</span></li>\n");
            }
            buf.append("</ul>\n</td></tr>\n");
        }
        buf.append("</table>\n");
    }

    /**
     *  @param mode 0: charts only;
     *         mode 1: full routerinfos;
     *         mode 2: abbreviated routerinfos
     *         mode 3: Same as 0 but sort countries by count
     */
    public void renderStatusHTML(Writer out, int pageSize, int page, int mode) throws IOException {
        if (!_context.netDb().isInitialized()) {
            out.write("<div id=notinitialized>" + _t("Not initialized") + "</div>\n");
            out.flush();
            return;
        }
        Log log = _context.logManager().getLog(NetDbRenderer.class);
        long start = System.currentTimeMillis();

        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats; // this means show the RouterInfos
        Hash us = _context.routerHash();

        Set<RouterInfo> routers = new TreeSet<RouterInfo>(RouterInfoComparator.getInstance());
        routers.addAll(_context.netDb().getRouters());
        int toSkip = pageSize * page;
        boolean nextpg = routers.size() > toSkip + pageSize;
        StringBuilder buf = new StringBuilder(8192);

        if (showStats && full && page == 0) {
            buf.append("<p class=infohelp id=debugmode>")
               .append(_t("Advanced mode - includes all statistics published by floodfills."))
               .append(" <a href=\"/netdb?f=2\">[Compact mode]</a></p>\n");
        } else if (shortStats && page == 0) {
            buf.append("<p class=infohelp>")
               .append(_t("Compact mode - does not include statistics published by floodfills."))
               .append(" <a href=\"/netdb?f=1\">[Advanced mode]</a></p>\n");
        }

        if (showStats && page == 0) {
            RouterInfo ourInfo = _context.router().getRouterInfo();
            renderRouterInfo(buf, ourInfo, true, true);
            out.append(buf);
            buf.setLength(0);
        }

        ObjectCounterUnsafe<String> versions = new ObjectCounterUnsafe<String>();
        ObjectCounterUnsafe<String> countries = new ObjectCounterUnsafe<String>();
        int[] transportCount = new int[TNAMES.length];

        int skipped = 0;
        int written = 0;
        boolean morePages = false;
        for (RouterInfo ri : routers) {
            Hash key = ri.getIdentity().getHash();
            boolean isUs = key.equals(us);
            if (!isUs) {
                if (showStats) {
                    if (skipped < toSkip) {
                        skipped++;
                        continue;
                    }
                    if (written++ >= pageSize) {
                        morePages = true;
                        break;
                    }
                    renderRouterInfo(buf, ri, false, full);
                    out.append(buf);
                    buf.setLength(0);
                }
                String routerVersion = ri.getOption("router.version");
                if (routerVersion != null) {versions.increment(routerVersion);}
                String country = _context.commSystem().getCountry(key);
                if(country != null) {countries.increment(country);}
                transportCount[classifyTransports(ri)]++;
            }
        }

        if (showStats) {
            int sz = routers.size() - 1; // -1 for us
            StringBuilder ubuf = new StringBuilder();
            ubuf.append("&amp;f=").append(mode);
            paginate(buf, ubuf, page, pageSize, morePages, sz);
        }

        if (!showStats) {
            // the summary table
            buf.append("<table id=netdboverview width=100%>\n<tr><th colspan=3>")
               .append(_t("Network Database Router Statistics"))
               .append("</th></tr>\n<tr><td style=vertical-align:top>");
            // versions table
            List<String> versionList = new ArrayList<String>(versions.objects());
            if (!versionList.isEmpty()) {
                Collections.sort(versionList, Collections.reverseOrder(new VersionComparator()));
                buf.append("<table id=netdbversions>\n").append("<thead>\n<tr><th>").append(_t("Version"))
                   .append("</th><th>").append(_t("Count")).append("</th></tr>\n</thead>\n");
                for (String routerVersion : versionList) {
                    int num = versions.count(routerVersion);
                    String ver = DataHelper.stripHTML(routerVersion);
                    buf.append("<tr><td><span class=version><a href=\"/netdb?v=").append(ver).append("\">")
                       .append(ver).append("</a></span></td><td>").append(num).append("</td></tr>\n");
                }
                buf.append("</table>\n");
            }
            buf.append("</td><td style=vertical-align:top>\n");
            out.append(buf);
            buf.setLength(0);

            String showAll = _t("Show all routers with this capability in the NetDb");
            buf.append("<table id=netdbtiers>\n")
               .append("<thead>\n<tr><th>").append(_t("Bandwidth Tier")).append("</th><th>")
               .append(_t("Count")).append("</th></tr>\n</thead>\n");
            if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW12).size() > 0) {
                buf.append("<tr><td><a href=\"/netdb?caps=K\" title=\"").append(showAll).append("\"><b>K</b></a>Under 12&#8239;KB/s</td><td>")
                   .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW12).size()).append("</td></tr>\n");
            }
            if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW32).size() > 0) {
                buf.append("<tr><td><a href=\"/netdb?caps=L\" title=\"").append(showAll).append("\"><b>L</b></a>12 - 48&#8239;KB/s</td><td>")
                   .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW32).size()).append("</td></tr>\n");
            }
            if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW64).size() > 0) {
                buf.append("<tr><td><a href=\"/netdb?caps=M\" title=\"").append(showAll).append("\"><b>M</b></a>49 - 65&#8239;KB/s</td><td>")
                   .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW64).size()).append("</td></tr>\n");
            }
            if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW128).size() > 0) {
                buf.append("<tr><td><a href=\"/netdb?caps=N\" title=\"").append(showAll).append("\"><b>N</b></a>66 - 130&#8239;KB/s</td><td>")
                   .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW128).size()).append("</td></tr>\n");
            }
            buf.append("<tr><td><a href=\"/netdb?caps=O\" title=\"").append(showAll).append("\"><b>O</b></a>131 - 261&#8239;KB/s</td><td>")
               .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW256).size()).append("</td></tr>\n")
               .append("<tr><td><a href=\"/netdb?caps=P\" title=\"").append(showAll).append("\"><b>P</b></a>262 - 2047&#8239;KB/s</td><td>")
               .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW512).size()).append("</td></tr>\n")
               .append("<tr><td><a href=\"/netdb?caps=X\" title=\"").append(showAll).append("\"><b>X</b></a>Over 2048&#8239;KB/s</td><td>")
               .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW_UNLIMITED).size()).append("</td></tr>\n")
               .append("</table>\n");
            out.append(buf);
            buf.setLength(0);

            if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_MODERATE).size() > 0 ||
                _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_SEVERE).size() > 0 ||
                _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_NO_TUNNELS).size() > 0) {

                buf.append("<table id=netdbcongestion>\n");
                buf.append("<thead>\n<tr><th>").append(_t("Congestion Cap")).append("</th><th>").append(_t("Count")).append("</th></tr>\n</thead>\n");
                if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_MODERATE).size() > 0) {
                    buf.append("<tr><td><a class=isD href=\"/netdb?caps=D\" title=\"").append(showAll).append("\"><b>D</b></a>")
                       .append(_t("Medium congestion / low performance")).append("</td><td>")
                       .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_MODERATE).size()).append("</td></tr>\n");
                }
                if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_SEVERE).size() > 0) {
                    buf.append("<tr><td><a class=isE href=\"/netdb?caps=E\" title=\"").append(showAll).append("\"><b>E</b></a>")
                       .append(_t("High congestion")).append("</td><td>")
                       .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_SEVERE).size()).append("</td></tr>\n");
                }
                if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_NO_TUNNELS).size() > 0) {
                    buf.append("<tr><td><a class=isG href=\"/netdb?caps=G\" title=\"").append(showAll).append("\"><b>G</b></a>")
                       .append(_t("Rejecting all tunnel requests")).append("</td><td>")
                       .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_NO_TUNNELS).size()).append("</td></tr>\n");
                }
                buf.append("</table>\n");
                out.append(buf);
                buf.setLength(0);
            }

            // transports table
            buf.append("<table id=netdbtransports>\n")
               .append("<thead>\n<tr><th>").append(_t("Transports")).append("</th><th>").append(_t("Count")).append("</th></tr>\n</thead>\n");
            for (int i = 0; i < TNAMES.length; i++) {
                int num = transportCount[i];
                if (num > 0) {buf.append("<tr><td>").append(_t(TNAMES[i])).append("</td><td>").append(num).append("</td></tr>\n");}
            }
            buf.append("</table>\n").append("</td>").append("<td style=vertical-align:top>\n");

            // country table
            List<String> countryList = new ArrayList<String>(countries.objects());
            buf.append("<table id=netdbcountrylist data-sortable>\n")
               .append("<thead>\n<tr>")
               .append("<th>").append(_t("Country")).append("</th>")
               .append("<th class=countX>").append(_t("X Tier")).append("</th>")
               .append("<th class=countFF>").append(_t("Floodfills")).append("</th>")
               .append("<th class=countCC data-sort-default>").append(_t("Total")).append("</th>")
               .append("</tr>\n</thead>\n");
            if (!countryList.isEmpty()) {
                Collections.sort(countryList, new CountryComparator());
                buf.append("<tbody id=cclist>\n");
                for (String country : countryList) {
                    int num = countries.count(country);
                    buf.append("<tr><td><a href=\"/netdb?c=").append(country).append("\">")
                       .append("<img width=20 height=15 alt=\"").append(country.toUpperCase(Locale.US)).append("\"")
                       .append(" src=\"/flags.jsp?c=").append(country).append("\">")
                       .append(getTranslatedCountry(country).replace("xx", _t("Unknown"))).append("</a></td>")
                       .append("<td class=countX>").append("<a href=\"/netdb?caps=X&amp;cc=").append(country).append("\">")
                       .append(countXTierInCountry(routers, country)).append("</a></td>")
                       .append("<td class=countFF>").append("<a href=\"/netdb?caps=f&amp;cc=").append(country).append("\">")
                       .append(countFloodfillsInCountry(routers, country)).append("</a></td>")
                       .append("<td class=countCC>").append(num).append("</td></tr>\n");
                }
                buf.append("</tbody></table>\n");
            } else {
                buf.append("<tbody><tr><td colspan=2>").append(_t("Initializing")).append("&hellip;</td></tr></tbody></table>\n");
            }
            buf.append("</td></tr>\n</table>\n");
        } // if !showStats
        out.append(buf);
        out.flush();
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
     *  Sort by translated country name using rules for the current language setting
     *  Inner class, can't be Serializable
     */
    private class CountryComparator implements Comparator<String> {
         private static final long serialVersionUID = 1L;
         private final Collator coll;

         public CountryComparator() {
             super();
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(String l, String r) {
             return coll.compare(getTranslatedCountry(l), getTranslatedCountry(r));
        }
    }

    /**
     *  Reverse sort by count, then forward by translated name.
     *
     *  @since 0.9.57
     */
    private class CountryCountComparator implements Comparator<String> {
         private static final long serialVersionUID = 1L;
         private final ObjectCounterUnsafe<String> counts;
         private final Collator coll;

         public CountryCountComparator(ObjectCounterUnsafe<String> counts) {
             super();
             this.counts = counts;
             coll = Collator.getInstance(new Locale(Messages.getLanguage(_context)));
         }

         public int compare(String l, String r) {
             int rv = counts.count(r) - counts.count(l);
             if (rv != 0) {return rv;}
             return coll.compare(getTranslatedCountry(l), getTranslatedCountry(r));
        }
    }

    /**
     *  Sort by style, then host
     *  @since 0.9.38
     */
    static class RAComparator implements Comparator<RouterAddress> {
         private static final long serialVersionUID = 1L;

         public int compare(RouterAddress l, RouterAddress r) {
             int rv = l.getTransportStyle().compareTo(r.getTransportStyle());
             if (rv != 0) {return rv;}
             String lh = l.getHost();
             String rh = r.getHost();
             if (lh == null) {return (rh == null) ? 0 : -1;}
             if (rh == null) {return 1;}
             return lh.compareTo(rh);
        }
    }

    /**
     *  Be careful to use stripHTML for any displayed routerInfo data to prevent vulnerabilities
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo info, boolean isUs, boolean full) {
        RouterIdentity ident = info.getIdentity();
        Hash h = info.getHash();
        String hash = h.toBase64();
        String family = info.getOption("family");
        buf.append("<table class=\"netdbentry lazy\">").append("<tr><th>");
        if (isUs) {
            buf.append("<b id=our-info>").append(_t("Our info")).append(":</b></th><th><code>").append(hash)
               .append("</code></th><th id=netdb_ourinfo>");
        } else {
            buf.append("<b>").append(_t("Router")).append(":</b></th><th><code>").append(hash).append("</code></th><th>");
        }
        if (_context.banlist().isBanlisted(h)) {
            buf.append("<a class=banlisted href=\"/profiles?f=3\" title=\"").append(_t("Router is banlisted")).append("\">Banned</a> ");
        }
        Collection<RouterAddress> addresses = info.getAddresses();
        boolean isJavaI2P = false;
        boolean isI2PD = false;
        for (RouterAddress addr : addresses) {
            String style = addr.getTransportStyle();
            int transportCost = addr.getCost();
            if ((style.startsWith("SSU") && transportCost == 5) ||
                (style.startsWith("NTCP") && transportCost == 14) &&
                (style.startsWith("SSU") && transportCost != 15)) {
                isJavaI2P = true;
                break;
            } else if ((style.startsWith("SSU") && transportCost == 3) ||
                (style.startsWith("NTCP") && transportCost == 8)) {
                isI2PD = true;
            }
        }
        if (isJavaI2P) {buf.append("<span class=javai2p title=\"").append(_t("Java I2P variant")).append("\"></span> ");}
        else if (isI2PD) {buf.append("<span class=i2pd title=\"").append(_t("I2Pd variant")).append("\"></span> ");}
        if (ident.isCompressible()) {
            buf.append("<span class=compressible title=\"").append(_t("RouterInfo is compressible")).append("\"></span> ");
        }
        String tooltip = "\" title=\"" + _t("Show all routers with this capability in the NetDb") + "\"><span";
        boolean hasD = DataHelper.stripHTML(info.getCapabilities()).contains("D");
        boolean hasE = DataHelper.stripHTML(info.getCapabilities()).contains("E");
        boolean hasG = DataHelper.stripHTML(info.getCapabilities()).contains("G");
        boolean isR = DataHelper.stripHTML(info.getCapabilities()).contains("R");
        boolean isU = DataHelper.stripHTML(info.getCapabilities()).contains("U");
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
                                               .replace("f", "<a href=\"/netdb?caps=f\"><span class=ff>F</span></a>")
                                               .replace("R", "<a href=\"/netdb?caps=R\"><span class=reachable>R</span></a>")
                                               .replace("U", "<a href=\"/netdb?caps=U\"><span class=unreachable>U</span></a>")
                                               .replace("K", "<a href=\"/netdb?caps=K\"><span class=tier>K</span></a>")
                                               .replace("L", "<a href=\"/netdb?caps=L\"><span class=tier>L</span></a>")
                                               .replace("M", "<a href=\"/netdb?caps=M\"><span class=tier>M</span></a>")
                                               .replace("N", "<a href=\"/netdb?caps=N\"><span class=tier>N</span></a>")
                                               .replace("O", "<a href=\"/netdb?caps=O\"><span class=tier>O</span></a>")
                                               .replace("P", "<a href=\"/netdb?caps=P\"><span class=tier>P</span></a>")
                                               .replace("X", "<a href=\"/netdb?caps=X\"><span class=tier>X</span></a>");
        if (hasD) {caps = caps.replace("D","").replace("class=tier", "class=\"tier isD\"").replace("\"><span class", "D\"><span class");}
        else if (hasE) {caps = caps.replace("E","").replace("class=tier", "class=\"tier isE\"").replace("\"><span class", "E\"><span class");}
        else if (hasG) {caps = caps.replace("G","").replace("class=tier", "class=\"tier isG\"").replace("\"><span class", "G\"><span class");}
        if (hasD) {
            if (isR) {
                caps = caps.replace("caps=KD", "caps=KRD")
                           .replace("caps=LD", "caps=LRD")
                           .replace("caps=MD", "caps=MRD")
                           .replace("caps=ND", "caps=NRD")
                           .replace("caps=OD", "caps=ORD")
                           .replace("caps=PD", "caps=PRD")
                           .replace("caps=XD", "caps=XRD");
            } else if (isU) {
                caps = caps.replace("caps=KD", "caps=KUD")
                           .replace("caps=LD", "caps=LUD")
                           .replace("caps=MD", "caps=MUD")
                           .replace("caps=ND", "caps=NUD")
                           .replace("caps=OD", "caps=OUD")
                           .replace("caps=PD", "caps=PUD")
                           .replace("caps=XD", "caps=XUD");
            }
        } else if (hasE) {
            if (isR) {
                caps = caps.replace("caps=KE", "caps=KRE")
                           .replace("caps=LE", "caps=LRE")
                           .replace("caps=ME", "caps=MRE")
                           .replace("caps=NE", "caps=NRE")
                           .replace("caps=OE", "caps=ORE")
                           .replace("caps=PE", "caps=PRE")
                           .replace("caps=XE", "caps=XRE");
            } else if (isU) {
                caps = caps.replace("caps=KE", "caps=KUE")
                           .replace("caps=LE", "caps=LUE")
                           .replace("caps=ME", "caps=MUE")
                           .replace("caps=NE", "caps=NUE")
                           .replace("caps=OE", "caps=OUE")
                           .replace("caps=PE", "caps=PUE")
                           .replace("caps=XE", "caps=XUE");
            }
        } else if (hasG) {
            if (isR) {
                caps = caps.replace("KG", "KRG")
                           .replace("LG", "LRG")
                           .replace("MG", "MRG")
                           .replace("NG", "NRG")
                           .replace("OG", "ORG")
                           .replace("PG", "PRG")
                           .replace("XG", "XRG");
            } else if (isU) {
                caps = caps.replace("KG", "KUG")
                           .replace("LG", "LUG")
                           .replace("MG", "MUG")
                           .replace("NG", "NUG")
                           .replace("OG", "OUG")
                           .replace("PG", "PUG")
                           .replace("XG", "XUG");
            }
        }
        caps = caps.replace("\"><span", tooltip);
        buf.append(caps);
        buf.append("&nbsp;<a href=\"/netdb?v=").append(DataHelper.stripHTML(info.getVersion())).append("\">")
           .append("<span class=version title=\"").append(_t("Show all routers with this version in the NetDb"))
           .append("\">").append(DataHelper.stripHTML(info.getVersion())).append("</span></a>");
        if (!isUs) {
            buf.append("<span class=netdb_header>");
            if (family != null) {
                FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
                buf.append("<a class=\"familysearch");
                if (fkc != null) {buf.append(" verified");}
                buf.append("\" href=\"/netdb?fam=").append(DataHelper.stripHTML(family))
                   .append("\" title=\"").append(_t("Show all members of the {0} family in NetDb", DataHelper.stripHTML(family)))
                   .append("\">").append(_t("Family")).append("</a>");
            }
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(info.getHash());
            if (prof != null) {
                buf.append("<a class=viewprofile href=\"/viewprofile?peer=").append(hash).append("\" title=\"")
                   .append(_t("View profile")).append("\">").append(_t("Profile")).append("</a>");
            }
            buf.append("<a class=configpeer href=\"/configpeer?peer=").append(hash)
               .append("\" title=\"").append(_t("Configure peer"))
               .append("\">").append(_t("Edit")).append("</a>")
               .append(_context.commSystem().renderPeerFlag(h));
        } else {
            long used = (long) _context.statManager().getRate("router.memoryUsed").getRate(60*1000).getAvgOrLifetimeAvg();
            used /= 1024*1024;
            buf.append("&nbsp;<span id=netdb_ram><b>").append(_t("Memory usage")).append(":</b> ").append(used).append("M</span>");
        }
        buf.append("</span></th></tr>\n<tr>");
        long age = _context.clock().now() - info.getPublished();
        if (isUs && _context.router().isHidden()) {
            buf.append("<td><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b></td>")
               .append("<td><span class=netdb_info>")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>&nbsp;&nbsp;");
        } else if (age > 0) {
            buf.append("<td><b>").append(_t("Published")).append(":</b></td>")
               .append("<td><span class=netdb_info>")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>&nbsp;&nbsp;");
            String address = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(info));
            //String caps = DataHelper.stripHTML(info.getCapabilities());
            boolean isUnreachable = caps.contains("U") || caps.contains("H");
            long uptime = _context.router().getUptime();
            if (enableReverseLookups() && uptime > 30*1000 && !isUnreachable && address != null) {
                String rdns = _context.commSystem().getCanonicalHostName(address);
                if (rdns != null && !rdns.equals(address) && !rdns.equals("unknown")) {
                    buf.append("<span class=netdb_info><b>").append(_t("Hostname")).append(":</b> <span class=rdns>")
                       .append(rdns).append("</span></span>&nbsp;&nbsp;");
                }
            } else if (uptime > 30*1000 && (isUnreachable || address == null)) {
                byte[] ip = TransportImpl.getIP(info.getHash());
                if (ip != null) {
                    _context.commSystem().queueLookup(ip);
                    String directAddress = Addresses.toString(ip);
                    if (enableReverseLookups()) {
                        String rdns = _context.commSystem().getCanonicalHostName(directAddress);
                        if (rdns != null && !rdns.equals(directAddress) && !rdns.equals("unknown")) {
                            buf.append("<span class=netdb_info><b>").append(_t("Hostname")).append(" (")
                               .append(_t("direct")).append(")").append(":</b> <span class=rdns>")
                               .append(rdns).append(" (").append(directAddress).append(")</span></span>&nbsp;&nbsp;");
                        } else {
                            buf.append("<span class=netdb_info><b>").append(_t("IP Address")).append(" (")
                               .append(_t("direct")).append(")").append(":</b> <span class=rdns>").append(directAddress)
                               .append("</span></span>&nbsp;&nbsp;");
                        }
                    } else {
                        buf.append("<span class=netdb_info><b>").append(_t("IP Address")).append(" (")
                           .append(_t("direct")).append(")").append(":</b> <span class=rdns>").append(directAddress)
                           .append("</span></span>&nbsp;&nbsp;");
                    }
                }
            }
         } else {
            // shouldn't happen
            buf.append("<td><b>").append(_t("Published")).append("</td><td>:</b> in ")
               .append(DataHelper.formatDuration2(0-age)).append("<span class=netdb_info>???</span>&nbsp;&nbsp;");
        }

        if (family != null) {
            FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
            buf.append("<span class=\"netdb_family\"><b>").append(_t("Family"))
               .append(":</b> <span class=familyname>").append(DataHelper.stripHTML(family));
            if (fkc != null) {
               buf.append(" <span class=verified title=\"")
                  .append(_t("Verified family (signing certificate is installed and valid)"))
                  .append("\">[").append(_t("Verified")).append("]</span>");
            }
            buf.append("</span></span>");
        }
        buf.append("</td><td>");
        buf.append("<span class=\"signingkey\" title=\"")
           .append(_t("Show all routers with this signature type in the NetDb"))
           .append("\">").append("<a class=\"keysearch\" href=\"/netdb?type=")
           .append(info.getIdentity().getSigningPublicKey().getType().toString())
           .append("\">").append(info.getIdentity().getSigningPublicKey().getType().toString())
           .append("</a></span>")
           .append("&nbsp;<span class=\"signingkey encryption\" title=\"")
           .append(_t("Show all routers with this encryption type in the NetDb"))
           .append("\">").append("<a class=\"keysearch\" href=\"/netdb?etype=")
           .append(info.getIdentity().getPublicKey().getType().toString())
           .append("\">").append(info.getIdentity().getPublicKey().getType().toString())
           .append("</a></span></td></tr>\n<tr>")
           .append("<td><b>" + _t("Addresses") + ":</b></td>")
           .append("<td colspan=2 class=\"netdb_addresses\">")
           .append("<ul>");

        Collection<RouterAddress> addrs = info.getAddresses();
        byte[] ip = TransportImpl.getIP(info.getHash());
        if (addrs.isEmpty()) {
            buf.append(_t("n/a"));
            if (ip != null) {_context.commSystem().queueLookup(ip);}
        } else {
            if (addrs.size() > 1) {
                // addrs is unmodifiable
                List<RouterAddress> laddrs = new ArrayList<RouterAddress>(addrs);
                Collections.sort(laddrs, new RAComparator());
                addrs = laddrs;
            }

            boolean hasDetails = false;
            for (RouterAddress addr : addrs) {
                if (ip != null) {_context.commSystem().queueLookup(ip);}
                String style = addr.getTransportStyle();
                int cost = addr.getCost();

                Map<Object, Object> p = addr.getOptionsMap();
                List<Map.Entry<Object, Object>> netProps = new ArrayList<>();
                List<Map.Entry<Object, Object>> otherEntries = new ArrayList<>();

                // Separate host, port, mtu, and caps from others
                for (Map.Entry<Object, Object> e : p.entrySet()) {
                    String name = (String) e.getKey();
                    if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("port") || name.equalsIgnoreCase("mtu") || name.equalsIgnoreCase("caps")) {
                        netProps.add(e);
                    } else {
                        otherEntries.add(e);
                    }
                    if (!netProps.isEmpty() || !otherEntries.isEmpty()) {
                        hasDetails = true;
                    }
                }

                // Sort host, port, mtu, and caps alphabetically
                Collections.sort(netProps, (e1, e2) -> {
                    String key1 = (String) e1.getKey();
                    String key2 = (String) e2.getKey();
                    return key1.compareTo(key2);
                });

                // Create a new list with unique entries for each property name
                List<Map.Entry<Object, Object>> sortedProps = new ArrayList<>();
                Set<String> seenNames = new HashSet<>();
                for (Map.Entry<Object, Object> e : netProps) {
                    String name = (String) e.getKey();
                    if (!seenNames.contains(name)) {
                        sortedProps.add(e);
                        seenNames.add(name);
                    }
                }
                for (Map.Entry<Object, Object> e : otherEntries) {
                    String name = (String) e.getKey();
                    if (!seenNames.contains(name)) {
                        sortedProps.add(e);
                        seenNames.add(name);
                    }
                }

                if (!hasDetails) {continue;}
                buf.append("<li>");
                buf.append("<b class=\"netdb_transport\"");
                if (!((style.equals("SSU") && cost == 5) || (style.startsWith("NTCP") && cost == 10))) {
                    buf.append(" title=\"").append(_t("Cost")).append(": ").append(cost).append("\"");
                }
                buf.append(">").append(DataHelper.stripHTML(style)).append("</b> ");

                boolean hasHost = false;
                // Append host and port first
                for (Map.Entry<Object, Object> e : sortedProps) {
                    String name = (String) e.getKey();
                    String val = (String) e.getValue();
                    if (name.equalsIgnoreCase("host")) {
                        buf.append("<span class=nowrap><span class=netdb_name>")
                           .append(_t(DataHelper.stripHTML(name))).append(":</span> ")
                           .append("<span class=\"netdb_info host\">");
                        if (DataHelper.stripHTML(val).equals("::")) {
                            buf.append(_t("n/a")); // fix empty ipv6
                        } else {
                            buf.append("<a title=\"").append(_t("Show all routers with this address in the NetDb")).append("\" ");
                            if (DataHelper.stripHTML(val).contains(":")) {buf.append(" href=\"/netdb?ipv6=");}
                            else {buf.append(" href=\"/netdb?ip=");}
                            if (DataHelper.stripHTML(val).contains(":")) {
                                if (DataHelper.stripHTML(val).length() > 8) {buf.append(DataHelper.stripHTML(val).substring(0,4));}
                                else {buf.append(DataHelper.stripHTML(val));}
                            } else {buf.append(DataHelper.stripHTML(val));}
                            buf.append("\">").append(DataHelper.stripHTML(val)).append("</a>");
                        }
                        buf.append("</span>");
                        buf.append("</span> ");
                        hasHost = true;
                    } else if (name.equalsIgnoreCase("port")) {
                        buf.append("<span class=nowrap><span class=netdb_name>")
                           .append(_t(DataHelper.stripHTML(name)))
                           .append(":</span> <span class=\"netdb_info port\">").append("<a title=\"")
                           .append(_t("Show all routers with this port in the NetDb")).append("\" ")
                           .append(" href=\"/netdb?port=")
                           .append(DataHelper.stripHTML(val)).append("\">")
                           .append(DataHelper.stripHTML(val))
                           .append("</a></span></span> ");
                    }
                }

                // Append other entries
                for (Map.Entry<Object, Object> e : sortedProps) {
                    String name = (String) e.getKey();
                    String val = (String) e.getValue();
                    if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("port")) continue;
                    if (name.equals("v")) {continue;}
                    if (!full && (name.equals("caps") || name.equals("mtu"))) {continue;} // only show caps and mtu keys if adv. mode
                    if (hasHost && !full) {break;} // only show I and S keys if no host or if in adv. mode
                    if (name.contains("key") || name.contains("itag") || name.contains("iexp")) {continue;}
                    if (name.toLowerCase().equals("mtu")) {name = name.toUpperCase();}
                    buf.append(" <span class=nowrap><span class=netdb_name>").append(_t(DataHelper.stripHTML(name)))
                       .append(":</span> <span class=netdb_info>").append(DataHelper.stripHTML(val)).append("</span></span> ");
                }
                if (!isUs) {buf.append("</li>");}
            }
            buf.append("</ul>").append("</td></tr>\n");
        }

        if (full) {
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(info.getHash());
            boolean isFF = info.getCapabilities().indexOf("f") >= 0;
            if (prof != null || isUs) {
            buf.append("<tr><td><b>" + _t("Stats") + ":</b><td colspan=2>\n<ul class=netdbStats>");
                Map<Object, Object> p = info.getOptionsMap();
                for (Map.Entry<Object, Object> e : p.entrySet()) {
                    String key = (String) e.getKey();
                    if (key.toLowerCase().contains("caps") || key.toLowerCase().contains("version") ||
                        key.toLowerCase().equals("family") || key.toLowerCase().contains("tunnel.") ||
                        key.toLowerCase().contains("stat_") || (key.equals("netId") && !isUs)) {continue;}

                    String netDbKey = DataHelper.stripHTML(key)
                        .replace("netdb.", "")
                        .replace("netId", "<li><b>" + _t("Network ID")) // only shown for our own routerinfo
                        .replace("knownLeaseSets", "<li><b>" + _t("LeaseSets"))
                        .replace("knownRouters", "<li><b>" + _t("Routers"))
                        .replace("stat_", "")
                        .replace("uptime", "<li><b>" + _t("Uptime"));

/**
                        // TODO: place family entries underneath general network stats
                        .replace("family.", "Family ")
                        .replace("Family key", "<li class=\"longstat fam\"><b>" + _t("Family Key"))
                        .replace("Family sig", "<li class=\"longstat fam\"><b>" + _t("Family Sig"))
                        .replace("tunnel.buildExploratoryExpire.60m",  "<li class=longstat><b>" + _t("Exploratory tunnels expire (1h)"))
                        .replace("tunnel.buildExploratoryReject.60m",  "<li class=longstat><b>" + _t("Exploratory tunnels reject (1h)"))
                        .replace("tunnel.buildExploratorySuccess.60m", "<li class=longstat><b>" + _t("Exploratory tunnels build success (1h)"))
                        .replace("tunnel.buildClientExpire.60m",       "<li class=longstat><b>" + _t("Client tunnels expire (1h)"))
                        .replace("tunnel.buildClientReject.60m",       "<li class=longstat><b>" + _t("Client tunnels reject (1h)"))
                        .replace("tunnel.buildClientSuccess.60m",      "<li class=longstat><b>" + _t("Client tunnels build success (1h)"))
                        .replace("tunnel.participatingTunnels.60m",    "<li class=longstat><b>" + _t("Participating tunnels (1h)"))
                        .replace("tunnel.participatingTunnels.60s",    "<li class=longstat><b>" + _t("Participating tunnels (60s)"))
                        .replace("stat_bandwidthSendBps.60m",          "<li class=longstat><b>" + _t("Bandwidth send rate (1h)"))
                        .replace("stat_bandwidthReceiveBps.60m",       "<li class=longstat><b>" + _t("Bandwidth receive rate (1h)"));
**/

                    String val = (String) e.getValue();
                    String[] values = DataHelper.stripHTML(val).split(";");

                    // Hide these stats
                    // TODO: only show 4th value for each stat
                    List<String> stats = Arrays.asList(
                        "tunnel.buildExploratoryExpire.60m",
                        "tunnel.buildExploratoryReject.60m",
                        "tunnel.buildExploratorySuccess.60m",
                        "tunnel.buildClientExpire.60m",
                        "tunnel.buildClientReject.60m",
                        "tunnel.buildClientSuccess.60m",
                        "tunnel.participatingTunnels.60m",
                        "tunnel.participatingTunnels.60s",
                        "stat_bandwidthSendBps.60m",
                        "stat_bandwidthReceiveBps.60m"
                    );

                    if (!stats.contains(key) && !key.toLowerCase().contains("family")) {
                        buf.append(netDbKey);
                        String netDbValue = DataHelper.stripHTML(val)
                                                      .replace("XO", "X")
                                                      .replace("PO", "P")
                                                      .replace("R", "")
                                                      .replace("U", "")
                                                      .replace(";", " <span class=\"bullet\">&bullet;</span> ")
                                                      .replace("&bullet;</span> 555", "&bullet;</span> " + _t("n/a"));
                        buf.append(":</b> ").append(netDbValue).append("</li>");
                    }
                }

                if (!isUs && prof != null) {
                    long now = _context.clock().now();
                    long heard = prof.getFirstHeardAbout();
                    if (heard > 0) {
                        long peerAge = Math.max(now - heard, 1);
                        if (isFF) {buf.append("<br>");}
                        buf.append("<li><b>").append(_t("First heard about")).append(":</b> ")
                           .append(_t("{0} ago", DataHelper.formatDuration2(peerAge))).append("</li>");
                    }
                    heard = prof.getLastHeardAbout();
                    if (heard > 0) {
                        long peerAge = Math.max(now - heard, 1);
                        buf.append("<li><b>").append(_t("Last heard about")).append(":</b> ")
                           .append(_t("{0} ago", DataHelper.formatDuration2(peerAge))).append("</li>");
                    }
                    heard = prof.getLastHeardFrom();
                    if (heard > 0) {
                        long peerAge = Math.max(now - heard, 1);
                        buf.append("<li><b>").append(_t("Last heard from")).append(":</b> ")
                           .append(_t("{0} ago", DataHelper.formatDuration2(peerAge))).append("</li>");
                    }
                }
                buf.append("</ul></td></tr>\n");
            }
        }
        buf.append("</table>\n");
    }

    private static final int SSU = 1;
    private static final int SSUI = 2;
    private static final int NTCP = 4;
    private static final int IPV6 = 8;
    private static final String[] TNAMES = { _x("Hidden or starting up"), _x("SSU"), _x("SSU with introducers"), "",
                                             _x("NTCP"), _x("NTCP and SSU"), _x("NTCP and SSU with introducers"), "",
                                             "", _x("IPv6 SSU"), _x("IPv6 Only SSU, introducers"), _x("IPv6 SSU, introducers"),
                                             _x("IPv6 NTCP"), _x("IPv6 NTCP, SSU"), _x("IPv6 Only NTCP, SSU, introducers"),
                                             _x("IPv6 NTCP, SSU, introducers")
                                           };
    /**
     *  what transport types
     */
    private static int classifyTransports(RouterInfo info) {
        int rv = 0;
        for (RouterAddress addr : info.getAddresses()) {
            String style = addr.getTransportStyle();
            if (style.equals("NTCP2") || style.equals("NTCP")) {rv |= NTCP;}
            else if (style.equals("SSU") || style.equals("SSU2")) {
                if (addr.getOption("itag0") != null) {rv |= SSUI;}
                else {rv |= SSU;}
            }
            String host = addr.getHost();
            if (host != null && host.contains(":")) {rv |= IPV6;}
            else {
                String caps = addr.getOption("caps");
                if (caps != null && caps.contains("6")) {rv |= IPV6;}
            }
        }
        // map invalid values with "" in TNAMES
        if (rv == 3) {rv = 2;}
        else if (rv == 7) {rv = 6;}
        else if (rv == 8) {rv = 0;}
        return rv;
    }

    /**
     *  If ipv6 is in compressed form, return expanded form.
     *  If ipv6 is in expanded form, return compressed form.
     *  Else return null.
     *
     *  @param ip ipv6 only, not ending with ::
     *  @return alt string or null
     *  @since 0.9.57
     */
    private static String getAltIPv6(String ip) {
        if (ip.contains("::")) {
            byte[] bip = Addresses.getIPOnly(ip); // convert to expanded
            if (bip != null) {return Addresses.toString(bip);}
        } else if (ip.contains(":0:")) {return Addresses.toCanonicalString(ip);} // convert to canonical
        return null;
    }

    /** translate a string */
    private String _t(String s) {return Messages.getString(s, _context);}

    /** tag only */
    private static final String _x(String s) {return s;}

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
    private String _t(String s, Object o) {return Messages.getString(s, o, _context);}

}
