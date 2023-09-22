package net.i2p.router.web.helpers;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;         // debug
import java.net.InetAddress;
import java.text.Collator;
import java.text.DecimalFormat;      // debug
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
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
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.JobImpl;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseSegmentor;
import net.i2p.router.networkdb.kademlia.SegmentedNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.GeoIP;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.util.HashDistance;   // debug
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;
import net.i2p.router.web.WebAppStarter;
import net.i2p.util.Addresses;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;
import static net.i2p.router.sybil.Util.biLog2;


class NetDbRenderer {
    private final RouterContext _context;

    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
    }

    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {
        return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);
    }

    public static final int LOOKUP_WAIT = 8 * 1000;

    public boolean isFloodfill() {
//        return _context.netDbSegmentor().floodfillEnabled();
        return _context.netDb().floodfillEnabled();
    }

    /**
     *  Inner class, can't be Serializable
     */
    private class LeaseSetComparator implements Comparator<LeaseSet> {
         public int compare(LeaseSet l, LeaseSet r) {
             Hash dl = l.getHash();
             Hash dr = r.getHash();
             boolean locall = _context.clientManager().isLocal(dl);
             boolean localr = _context.clientManager().isLocal(dr);
             if (locall && !localr) return -1;
             if (localr && !locall) return 1;
             return dl.toBase32().compareTo(dr.toBase32());
        }
    }

    /** for debugging @since 0.7.14 */
    private static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet>, Serializable {
         private final Hash _us;
         public LeaseSetRoutingKeyComparator(Hash us) {
             _us = us;
         }
         public int compare(LeaseSet l, LeaseSet r) {
             return HashDistance.getDistance(_us, l.getRoutingKey()).compareTo(HashDistance.getDistance(_us, r.getRoutingKey()));
        }
    }

    private static class RouterInfoComparator implements Comparator<RouterInfo>, Serializable {
         public int compare(RouterInfo l, RouterInfo r) {
             return l.getIdentity().getHash().toBase64().compareTo(r.getIdentity().getHash().toBase64());
        }
    }

    /**
     *  One String must be non-null
     *
     *  @param page zero-based
     *  @param routerPrefix may be null. "." for our router only
     *  @param version may be null
     *  @param country may be null
     *  @param family may be null
     *  @param highPort if nonzero, a range from port to highPort inclusive
     */
    public void renderRouterInfoHTML(Writer out, int pageSize, int page,
                                     String routerPrefix, String version,
                                     String country, String family, String caps,
                                     String ip, String sybil, int port, int highPort, SigType type, EncType etype,
                                     String mtu, String ipv6, String ssucaps,
                                     String tr, int cost, int icount) throws IOException {
                                            renderRouterInfoHTML(out, pageSize, page,
                                                                 routerPrefix, version,
                                                                 country, family, caps,
                                                                 ip, sybil, port, highPort, type, etype,
                                                                 mtu, ipv6, ssucaps,
                                                                 tr, cost, icount, null, false);
                                     }
    public void renderRouterInfoHTML(Writer out, int pageSize, int page,
                                     String routerPrefix, String version,
                                     String country, String family, String caps,
                                     String ip, String sybil, int port, int highPort, SigType type, EncType etype,
                                     String mtu, String ipv6, String ssucaps,
                                     String tr, int cost, int icount, String client, boolean allClients) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        List<Hash> sybils = sybil != null ? new ArrayList<Hash>(128) : null;
        FloodfillNetworkDatabaseFacade netdb = _context.netDb();
        if (client != null) {
            netdb = _context.clientNetDb(client);
        }

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
                    banned = _context.banlist().isBanlisted(hash);
                    if (!banned) {
                        // remote lookup
                        LookupWaiter lw = new LookupWaiter();
                        netdb.lookupRouterInfo(hash, lw, lw, LOOKUP_WAIT);
                        // just wait right here in the middle of the rendering, sure
                        synchronized(lw) {
                            try { lw.wait(LOOKUP_WAIT); } catch (InterruptedException ie) {}
                        }
                        ri = (RouterInfo) netdb.lookupLocallyWithoutValidation(hash);
                    }
                }
                if (ri != null) {
                   renderRouterInfo(buf, ri, false, true);
                } else {
                    buf.append("<div class=netdbnotfound>");
                    if (routerPrefix != null) {
                        buf.append(_t("Router")).append(' ');
                        buf.append(routerPrefix);
                        buf.append(' ').append(banned ? _t("is banned") : _t("not found in network database"));
                    } else {
                        buf.append(_t("No results"));
                    }
                    buf.append("</div>");
                }
            } else {
                buf.append("<div class=netdbnotfound>");
                buf.append("Bad Base64 router hash").append(' ');
                buf.append(DataHelper.escapeHTML(routerPrefix));
                buf.append("</div>");
            }
        } else {
            StringBuilder ubuf = new StringBuilder();
            if (routerPrefix != null)
                ubuf.append("&amp;r=").append(routerPrefix);
            if (version != null)
                ubuf.append("&amp;v=").append(version);
            if (country != null)
                ubuf.append("&amp;c=").append(country);
            if (family != null)
                ubuf.append("&amp;fam=").append(family);
            if (caps != null)
                ubuf.append("&amp;caps=").append(caps);
            if (tr != null)
                ubuf.append("&amp;tr=").append(tr);
            if (type != null)
                ubuf.append("&amp;type=").append(type);
            if (etype != null)
                ubuf.append("&amp;etype=").append(etype);
            if (ip != null)
                ubuf.append("&amp;ip=").append(ip);
            if (port != 0)
                ubuf.append("&amp;port=").append(port);
            if (mtu != null)
                ubuf.append("&amp;mtu=").append(mtu);
            if (ipv6 != null)
                ubuf.append("&amp;ipv6=").append(ipv6);
            if (ssucaps != null)
                ubuf.append("&amp;ssucaps=").append(ssucaps);
            if (cost != 0)
                ubuf.append("&amp;cost=").append(cost);
            if (sybil != null)
                ubuf.append("&amp;sybil=").append(sybil);
            if (page > 0) {
                buf.append("<p class=infohelp id=pagenav>" +
                           "<a href=\"/netdb?pg=").append(page)
                   .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
                buf.append(_t("Page")).append(' ').append(page + 1);
                buf.append("</p>");
            }
            boolean notFound = true;
            Set<RouterInfo> routers = new HashSet<RouterInfo>();
            if (allClients){
                    routers.addAll(_context.netDbSegmentor().getRoutersKnownToClients());
            } else {
                if (client == null)
                    routers.addAll(_context.netDb().getRouters());
                else
                    routers.addAll(_context.clientNetDb(client).getRouters());

            }
            int ipMode = 0;
            String ipArg = ip;  // save for error message
            String altIPv6 = null;
            if (ip != null) {
                if (ip.endsWith("/24")) {
                    ipMode = 1;
                } else if (ip.endsWith("/16")) {
                    ipMode = 2;
                } else if (ip.endsWith("/8")) {
                    ipMode = 3;
                } else if (ip.indexOf(':') > 0) {
                    ipMode = 4;
                    if (ip.endsWith("::")) {
                        // truncate for prefix search
                        ip = ip.substring(0, ip.length() - 1);
                    } else {
                        // We don't canonicalize as we search, so create alt string to check also
                        altIPv6 = getAltIPv6(ip);
                    }
                }
                if (ipMode > 0 && ipMode < 4) {
                    for (int i = 0; i < ipMode; i++) {
                        int last = ip.substring(0, ip.length() - 1).lastIndexOf('.');
                        if (last > 0)
                            ip = ip.substring(0, last + 1);
                    }
                }
            }
            if (ipv6 != null) {
                if (ipv6.endsWith("::")) {
                    // truncate for prefix search
                    ipv6 = ipv6.substring(0, ipv6.length() - 1);
                } else {
                    // We don't canonicalize as we search, so create alt string to check also
                    altIPv6 = getAltIPv6(ipv6);
                }
            }
            String familyArg = family;  // save for error message
            if (family != null)
                family = family.toLowerCase(Locale.US);
            int toSkip = pageSize * page;
            int skipped = 0;
            int written = 0;
            boolean morePages = false;
            outerloop:
            for (RouterInfo ri : routers) {
                Hash key = ri.getIdentity().getHash();
                if ((routerPrefix != null && key.toBase64().startsWith(routerPrefix)) ||
                    (version != null && version.equals(ri.getVersion())) ||
                    (country != null && country.equals(_context.commSystem().getCountry(key))) ||
                    // 'O' will catch PO and XO also
                    (caps != null && hasCap(ri, caps)) ||
                    (tr != null && ri.getTargetAddress(tr) != null) ||
                    (type != null && type == ri.getIdentity().getSigType()) ||
                    (etype != null && etype == ri.getIdentity().getEncType())) {
                        if (skipped < toSkip) {
                            skipped++;
                            continue;
                        }
                        if (written++ >= pageSize) {
                            morePages = true;
                            break;
                        }
                        renderRouterInfo(buf, ri, false, true);
                        if (sybil != null)
                            sybils.add(key);
                        notFound = false;
                    } else if (tr != null) {
                        boolean found;
                        if (tr.equals("NTCP_1")) {
                            RouterAddress ra = ri.getTargetAddress("NTCP");
                            found = ra != null && ra.getOption("v") == null;
                        } else if (tr.equals("NTCP_2")) {
                            RouterAddress ra = ri.getTargetAddress("NTCP");
                            found = ra != null && ra.getOption("v") != null;
                        } else if (tr.equals("SSU_1")) {
                            RouterAddress ra = ri.getTargetAddress("SSU");
                            found = ra != null && ra.getOption("v") == null;
                        } else if (tr.equals("SSU_2")) {
                            RouterAddress ra = ri.getTargetAddress("SSU");
                            found = ra != null && ra.getOption("v") != null;
                        } else {
                            RouterAddress ra = ri.getTargetAddress(tr);
                            found = ra != null;
                        }
                        if (!found)
                            continue;
                        if (skipped < toSkip) {
                            skipped++;
                            continue;
                        }
                        if (written++ >= pageSize) {
                            morePages = true;
                            break;
                        }
                        renderRouterInfo(buf, ri, false, true);
                        if (sybil != null)
                            sybils.add(key);
                        notFound = false;
                    } else if (family != null) {
                        String rifam = ri.getOption("family");
                        if (rifam != null && rifam.toLowerCase(Locale.US).contains(family)) {
                            if (skipped < toSkip) {
                                skipped++;
                                continue;
                            }
                            if (written++ >= pageSize) {
                                morePages = true;
                                break outerloop;
                            }
                            renderRouterInfo(buf, ri, false, true);
                            if (sybil != null)
                                sybils.add(key);
                            notFound = false;
                        }
                    } else if (ip != null) {
                        for (RouterAddress ra : ri.getAddresses()) {
                            if (ipMode == 0) {
                                if (ip.equals(ra.getHost())) {
                                    if (skipped < toSkip) {
                                        skipped++;
                                        break;
                                    }
                                    if (written++ >= pageSize) {
                                        morePages = true;
                                        break outerloop;
                                    }
                                    renderRouterInfo(buf, ri, false, true);
                                    if (sybil != null)
                                        sybils.add(key);
                                    notFound = false;
                                    break;
                                }
                            } else {
                                String host = ra.getHost();
                            if (host != null && (host.startsWith(ip) ||
                                                 (altIPv6 != null && host.startsWith(altIPv6)))) {
                                    if (skipped < toSkip) {
                                        skipped++;
                                        break;
                                    }
                                    if (written++ >= pageSize) {
                                        morePages = true;
                                        break outerloop;
                                    }
                                    renderRouterInfo(buf, ri, false, true);
                                    if (sybil != null)
                                        sybils.add(key);
                                    notFound = false;
                                    break;
                                }
                            }
                        }
                    } else if (port != 0) {
                        for (RouterAddress ra : ri.getAddresses()) {
                        int raport = ra.getPort();
                        if (port == raport ||
                            (highPort > 0 && raport >= port && raport <= highPort)) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        }
                    } else if (mtu != null) {
                        for (RouterAddress ra : ri.getAddresses()) {
                            if (mtu.equals(ra.getOption("mtu"))) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        }
                    } else if (ipv6 != null) {
                        for (RouterAddress ra : ri.getAddresses()) {
                            String host = ra.getHost();
                        if (host != null && (host.startsWith(ipv6) ||
                                             (altIPv6 != null && host.startsWith(altIPv6)))) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        }
                    } else if (ssucaps != null) {
                        for (RouterAddress ra : ri.getAddresses()) {
                            if (!"SSU".equals(ra.getTransportStyle()))
                                continue;
                            String racaps = ra.getOption("caps");
                            if (racaps == null)
                                continue;
                            if (racaps.contains(ssucaps)) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        }
                    } else if (cost != 0) {
                        for (RouterAddress ra : ri.getAddresses()) {
                            if (cost == ra.getCost()) {
                                if (skipped < toSkip) {
                                    skipped++;
                                    break;
                                }
                                if (written++ >= pageSize) {
                                    morePages = true;
                                    break outerloop;
                                }
                                renderRouterInfo(buf, ri, false, true);
                                if (sybil != null)
                                    sybils.add(key);
                                notFound = false;
                                break;
                            }
                        }
                    }
            }
            if (notFound) {
                buf.append("<div class=netdbnotfound>");
                buf.append(_t("Router")).append(' ');
                if (routerPrefix != null)
                    buf.append(routerPrefix).append(' ');
                if (version != null)
                    buf.append(_t("Version")).append(' ').append(version).append(' ');
                if (country != null)
                    buf.append(_t("Country")).append(' ').append(country).append(' ');
                if (family != null)
                    buf.append(_t("Family")).append(' ').append(family).append(' ');
                if (ip != null)
                    buf.append("IP ").append(ip).append(' ');
                if (ipv6 != null)
                    buf.append("IP ").append(ipv6).append(' ');
                if (port != 0) {
                    buf.append(_t("Port")).append(' ').append(port);
                    if (highPort != 0)
                        buf.append('-').append(highPort);
                    buf.append(' ');
                }
                if (mtu != null)
                    buf.append(_t("MTU")).append(' ').append(mtu).append(' ');
                if (cost != 0)
                    buf.append("Cost ").append(cost).append(' ');
                if (type != null)
                    buf.append("Type ").append(type).append(' ');
                if (etype != null)
                    buf.append("Type ").append(etype).append(' ');
                if (caps != null)
                    buf.append("Caps ").append(caps).append(' ');
                if (ssucaps != null)
                    buf.append("Caps ").append(ssucaps).append(' ');
                if (tr != null)
                    buf.append("Transport ").append(tr).append(' ');
                buf.append(_t("not found in network database"));
                buf.append("</div>");
            } else if (page > 0 || morePages) {
                buf.append("<p class=infohelp id=pagenav>");
                if (page > 0) {
                    buf.append("<a href=\"/netdb?pg=").append(page)
                       .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
                    buf.append(_t("Previous Page"));
                    buf.append("</a>&nbsp;&nbsp;&nbsp;");
                }
                buf.append(_t("Page")).append(' ').append(page + 1);
                if (morePages) {
                    buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?pg=").append(page + 2)
                       .append("&amp;ps=").append(pageSize).append(ubuf).append("\">");
                    buf.append(_t("Next Page"));
                    buf.append("</a>");
                }
                buf.append("</p>");
            }
        }
        out.write(buf.toString());
        out.flush();
        if (sybil != null)
        SybilRenderer.renderSybilHTML(out, _context, sybils, sybil);
    }

    /**
     *  @since 0.9.48
     */
    private class LookupWaiter extends JobImpl {
        public LookupWaiter() { super(_context); }
        public void runJob() {
            synchronized(this) {
                notifyAll();
            }
        }
        public String getName() { return "Console NetDb Lookup"; }
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
        } else {
            return ricaps.contains(caps);
        }
    }

    /**
     *  All the leasesets
     *
     *  @param debug @since 0.7.14 sort by distance from us, display
     *               median distance, and other stuff, useful when floodfill
     */
    public void renderLeaseSetHTML(Writer out, boolean debug, String client, boolean clientsOnly) throws IOException {
        StringBuilder buf = new StringBuilder(4*1024);
        if (debug) {
            buf.append("<p id=debugmode>Debug mode - Sorted by hash distance, closest first. <a href=\"/netdb?l=1\">[Compact mode]</a></p>\n");
        }
        Hash ourRKey;
        Set<LeaseSet> leases;
        DecimalFormat fmt;
        FloodfillNetworkDatabaseFacade netdb = null;
        if (clientsOnly) {
            netdb = _context.netDb();
        } else {
            if (client != null)
                netdb = _context.clientNetDb(client);
            else
                netdb = _context.netDb();
        }
        if (debug) {
            ourRKey = _context.routerHash();
            leases = new TreeSet<LeaseSet>(new LeaseSetRoutingKeyComparator(ourRKey));
            fmt = new DecimalFormat("#0.00");
        } else {
            ourRKey = null;
            leases = new TreeSet<LeaseSet>(new LeaseSetComparator());
            fmt = null;
        }
        if (clientsOnly)
            leases.addAll(_context.netDbSegmentor().getLeasesKnownToClients());
        else
            leases.addAll(netdb.getLeases());
        int medianCount = 0;
        int rapCount = 0;
        BigInteger median = null;
        int c = 0;

        // Summary
        if (debug) {
            buf.append("<table id=leasesetdebug>\n");
        } else if (client == null) {
            buf.append("<table id=leasesetsummary>\n");
        }
/**
        if (clientsOnly) {
            buf.append("<tr><th colspan=4>Leaseset Summary for All Clients: ").append(client).append("</th><tr>\n");
        } else if (client != null) {
            buf.append("<tr><th colspan=4>Leaseset Summary for Client: ").append(client).append("</th></tr>\n");
        } else {
            buf.append("<tr><th colspan=4>Leaseset Summary for Floodfill</th></tr>\n");
        }
        buf.append("<tr><td><b>Total Leasesets:</b></td><td colspan=3>").append(leases.size()).append("</td></tr>\n");
**/
        if (debug || client == null) {
            buf.append("<tr><th><b>Total Leasesets:</b></th><th colspan=3>").append(leases.size()).append("</th></tr>\n");
        }
        if (debug) {
            RouterKeyGenerator gen = _context.routerKeyGenerator();
            if (leases.size() > 0) {
                buf.append("<tr><td><b>Published (RAP) Leasesets:</b></td><td colspan=3>").append(leases).append("</td></tr>\n");
            }
            buf.append("<tr><td><b>Mod Data:</b></td><td>").append(DataHelper.getUTF8(gen.getModData())).append("</td>")
               .append("<td><b>Last Changed:</b></td><td>").append(DataHelper.formatTime(gen.getLastChanged())).append("</td></tr>\n")
               .append("<tr><td><b>Next Mod Data:</b></td><td>").append(DataHelper.getUTF8(gen.getNextModData())).append("</td>")
               .append("<td><b>Change in:</b></td><td>").append(DataHelper.formatDuration(gen.getTimeTillMidnight())).append("</td></tr>\n");
        }
        int ff = 0;
        if (client == null) {
            ff = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
            buf.append("<tr><td><b>Known Floodfills:</b></td><td colspan=3>").append(ff).append("</td></tr>\n");
            buf.append("<tr><td><b>Currently Floodfill?</b></td><td>").append(netdb.floodfillEnabled() ? "yes" : "no");
        }
        if (debug) {
            buf.append("</td><td><b>Routing Key:</b></td><td>").append(ourRKey.toBase64());
        } else {
            buf.append("</td><td colspan=2>");
        }
        buf.append("</td></tr>\n</table>\n");
            if (leases.isEmpty()) {
            if (!debug && client == null)
                buf.append("<div id=noleasesets><i>").append(_t("No Leasesets currently active.")).append("</i></div>");
        } else {
            boolean linkSusi = _context.portMapper().isRegistered("susidns");
            long now = _context.clock().now();
            buf.append("<div class=leasesets_container>");
            for (LeaseSet ls : leases) {
                String distance;
                if (debug) {
                    medianCount = rapCount / 2;
                    BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                    // Find the center of the RAP leasesets
                    if (ls.getReceivedAsPublished()) {
                       rapCount++;
                        if (c++ == medianCount) {
                            median = dist;
                        }
                    }
                    distance = fmt.format(biLog2(dist));
                } else {
                    distance = null;
                }
                renderLeaseSet(buf, ls, debug, now, linkSusi, distance);
                out.write(buf.toString());
                buf.setLength(0);
              } // for each
              if (debug && isFloodfill()) {
                  buf.append("<table id=leasesetdebug><tr><td><b>").append(_t("Network data")).append(":</b></td><td colspan=3>");
                  //buf.append("</b></p><p><b>Center of Key Space (router hash): " + ourRKey.toBase64());
                  if (median != null) {
                      double log2 = biLog2(median);
                      buf.append("</td></tr>")
                         .append("<tr><td><b>").append(_t("Median distance (bits)")).append(":</b></td><td colspan=3>").append(fmt.format(log2)).append("</td></tr>\n");
                      // 2 for 4 floodfills... -1 for median
                      // this can be way off for unknown reasons
                      int total = (int) Math.round(Math.pow(2, 2 + 256 - 1 - log2));
                      buf.append("<tr><td><b>").append(_t("Estimated total floodfills")).append(":</b></td><td colspan=3>").append(total).append("</td></tr>\n");
                      buf.append("<tr><td><b>").append(_t("Estimated total leasesets")).append(":</b></td><td colspan=3>").append(total * rapCount / 4);
                  } else {
                      buf.append("<i>No data available.</i>");
                  }
                  buf.append("</td></tr></table>\n");
              } // median table
              buf.append("</div>");
        }  // !empty
        out.write(buf.toString());
        out.flush();
    }

    /**
     * Single LeaseSet
     * @since 0.9.57
     */
    public void renderLeaseSet(Writer out, String hostname, boolean debug) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        Hash hash = ConvertToHash.getHash(hostname);
        if (hash == null) {
            buf.append("<div class=netdbnotfound>");
            buf.append("Hostname not found").append(' ');
            buf.append(hostname);
            buf.append("</div>");
        } else {
            LeaseSet ls = _context.netDb().lookupLeaseSetLocally(hash);
            if (ls == null) {
                // remote lookup
                LookupWaiter lw = new LookupWaiter();
                // use-case for the exploratory netDb here?
                _context.netDb().lookupLeaseSetRemotely(hash, lw, lw, 8*1000, null);
                // just wait right here in the middle of the rendering, sure
                synchronized(lw) {
                    try { lw.wait(9*1000); } catch (InterruptedException ie) {}
                }
                ls = _context.netDb().lookupLeaseSetLocally(hash);
            }
            if (ls != null) {
                BigInteger dist = HashDistance.getDistance(_context.routerHash(), ls.getRoutingKey());
                DecimalFormat fmt = new DecimalFormat("#0.00");
                String distance = fmt.format(biLog2(dist));
                buf.append("<div class=\"leasesets_container netdbsearch\">");
                renderLeaseSet(buf, ls, true, _context.clock().now(), false, distance);
                buf.append("</div>");
            } else {
                buf.append("<div class=netdbnotfound>");
                buf.append(_t("LeaseSet")).append(" for ");
                buf.append(hostname);
                buf.append(' ').append(_t("not found in network database"));
                buf.append("</div>");
            }
        }
        out.write(buf.toString());
        out.flush();
    }

    /** @since 0.9.57 split out from above */
    private void renderLeaseSet(StringBuilder buf, LeaseSet ls, boolean debug,
                                long now, boolean linkSusi, String distance) {
        // warning - will be null for non-local encrypted
        Destination dest = ls.getDestination();
        Hash key = ls.getHash();
        if (key != null)
            buf.append("<table class=leaseset id=\"ls_").append(key.toBase32().substring(0,4)).append("\">\n");
        else
            buf.append("<table class=leaseset>\n");
        buf.append("<tr><th><b class=lskey>").append(_t("LeaseSet")).append(":</b> <code title =\"")
           .append(_t("LeaseSet Key")).append("\">").append(key.toBase64()).append("</code>");
        int type = ls.getType();
        if (type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2 || _context.keyRing().get(key) != null)
            buf.append(" <b class=encls>(").append(_t("Encrypted")).append(")</b>");
        buf.append("</th>");
        if (_context.clientManager().isLocal(key)) {
            buf.append("<th>");
            boolean unpublished = !_context.clientManager().shouldPublishLeaseSet(key);
            TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
            buf.append("<a href=\"tunnels#" + key.toBase64().substring(0,4) + "\"><span class=\"lsdest");
            if (!unpublished)
                buf.append(" published");
            buf.append("\" title=\"")
               .append(_t("View local tunnels for destination"));
            if (!unpublished)
                buf.append(" (").append(_t("published")).append(")");
            buf.append("\">");
            if (in != null && in.getDestinationNickname() != null)
                buf.append(DataHelper.escapeHTML(in.getDestinationNickname()));
            else
                buf.append(dest.toBase64().substring(0, 6));
            buf.append("</span></a></th></tr>\n");
            // we don't show a b32 or addressbook links if encrypted
            if (type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                buf.append("<tr><td");
                // If the dest is published but not in the addressbook, an extra
                // <td> is appended with an "Add to addressbook" link, so this
                // <td> should not span 2 columns.
                String host = null;
                if (!unpublished) {
                    host = _context.namingService().reverseLookup(dest);
                }
                if (unpublished || host != null || !linkSusi) {
                    buf.append(" colspan=2");
                }
                buf.append(">");
                String b32 = key.toBase32();
                String truncb32 = b32.substring(0, 24);
                buf.append("<a href=\"http://").append(b32).append("/\">").append(truncb32).append("&hellip;b32.i2p</a></td>");
                if (linkSusi && !unpublished && host == null) {
                    buf.append("<td class=addtobook colspan=2>").append("<a title=\"").append(_t("Add to addressbook"))
                       .append("\" href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                       .append(dest.toBase64()).append("#add\">").append(_t("Add to local addressbook")).append("</a></td>");
                } // else probably a client
            }
        } else {
            buf.append("<th>");
            String host = (dest != null) ? _context.namingService().reverseLookup(dest) : null;
            if (host != null) {
                buf.append("<a class=destlink href=\"http://").append(host).append("/\">").append(host).append("</a></th>");
            } else {
                String b32 = key.toBase32();
                String truncb32 = b32.substring(0, 24);
                buf.append("<code title=\"").append(_t("Destination")).append("\">");
                if (dest != null)
                    buf.append(dest.toBase64().substring(0, 6));
                else
                    buf.append("n/a");
                buf.append("</code></th></tr>\n<tr><td");
                if (!linkSusi)
                    buf.append(" colspan=2");
                buf.append("><a href=\"http://").append(b32).append("\">").append(truncb32).append("&hellip;b32.i2p</a></td>");
                if (linkSusi && dest != null) {
                    buf.append("<td class=addtobook><a title=\"").append(_t("Add to addressbook"))
                       .append("\" href=\"/susidns/addressbook.jsp?book=private&amp;destination=")
                       .append(dest.toBase64()).append("#add\">").append(_t("Add to local addressbook")).append("</a></td></tr>\n");
                }
            }
        }
        long exp;
        buf.append("<tr><td colspan=2>");
        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
            exp = ls.getLatestLeaseDate() - now;
        } else {
            LeaseSet2 ls2 = (LeaseSet2) ls;
            long pub = now - ls2.getPublished();
            buf.append("&nbsp; &bullet; &nbsp;<b>").append(_t("Type")).append(":</b> ").append(type)
               .append(" &nbsp; &bullet; &nbsp;<b>").append(_t("Published{0} ago", ":</b> " + DataHelper.formatDuration2(pub)));
            exp = ((LeaseSet2)ls).getExpires()-now;
        }
        buf.append(" &nbsp; &bullet; &nbsp;<b>");
        if (exp > 0)
            buf.append(_t("Expires{0}", ":</b> " + DataHelper.formatDuration2(exp)).replace(" in", ""));
        else
            buf.append(_t("Expired{0} ago", ":</b> " + DataHelper.formatDuration2(0-exp)));
        if (debug) {
            buf.append(" &nbsp; &bullet; &nbsp;<b title=\"").append(_t("Received as published?")).append("\">RAP:</b> ").append(ls.getReceivedAsPublished());
            buf.append(" &nbsp; &bullet; &nbsp;<b title=\"").append(_t("Received as reply?")).append("\">RAR:</b> ").append(ls.getReceivedAsReply());
            buf.append(" &nbsp; &bullet; &nbsp;<b>").append(_t("Distance")).append(":</b> ").append(distance);
            if (type != DatabaseEntry.KEY_TYPE_LEASESET) {
                LeaseSet2 ls2 = (LeaseSet2) ls;
                if (ls2.isOffline()) {
                    buf.append(" &nbsp; &bullet; &nbsp;<b>").append(_t("Offline signed")).append(":</b> ");
                    exp = ls2.getTransientExpiration() - now;
                    if (exp > 0)
                        buf.append(" &nbsp; &bullet; &nbsp;<b>").append(_t("Expires{0}", ":</b> " + DataHelper.formatDuration2(exp)));
                    else
                        buf.append(" &nbsp; &bullet; &nbsp;<b>").append(_t("Expired{0} ago", ":</b> " + DataHelper.formatDuration2(0-exp)));
                    buf.append(" &nbsp; &bullet; &nbsp;<b>").append(_t("Type")).append(":</b> ").append(ls2.getTransientSigningKey().getType());
                }
            }
            buf.append("</td></tr>\n<tr><td colspan=2><span class=ls_crypto>");
            buf.append("<span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Signature type")).append(":</b> ");
            if (dest != null && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                buf.append(dest.getSigningPublicKey().getType()).append("</span>");
            } else {
                // encrypted, show blinded key type
                buf.append(ls.getSigningKey().getType()).append("</span>");
            }
            if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                buf.append("<br><span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Encryption Key"))
                   .append(":</b> ELGAMAL_2048 [").append(ls.getEncryptionKey().toBase64().substring(0, 8))
                   .append("&hellip;]</span>");
            } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
                LeaseSet2 ls2 = (LeaseSet2) ls;
                for (PublicKey pk : ls2.getEncryptionKeys()) {
                    buf.append("<br><span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Encryption Key")).append(":</b> ");
                    EncType etype = pk.getType();
                    if (etype != null)
                        buf.append(etype);
                    else
                        buf.append(_t("Unsupported type")).append(" ").append(pk.getUnknownTypeCode());
                    buf.append(" [").append(pk.toBase64().substring(0, 8)).append("&hellip;]</span>");
                }
            }
            buf.append("<br><span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Routing Key"))
               .append(":</b> ").append(ls.getRoutingKey().toBase64().substring(0,16))
               .append("&hellip;</span></td></tr>\n");
        } else {
            buf.append("</td></tr>\n<tr><td colspan=2>");
            buf.append("<span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Signature type")).append(":</b> ");
            if (dest != null && type != DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                buf.append(dest.getSigningPublicKey().getType());
            } else {
                // encrypted, show blinded key type
                buf.append(ls.getSigningKey().getType());
            }
            buf.append("</span> ");
            if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
                buf.append("<span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Encryption Key")).append(":</b> ELGAMAL_2048</span>");
            } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
                LeaseSet2 ls2 = (LeaseSet2) ls;
                for (PublicKey pk : ls2.getEncryptionKeys()) {
                    buf.append("<span class=nowrap>&nbsp; &bullet; &nbsp;<b>").append(_t("Encryption Key")).append(":</b> ");
                    EncType etype = pk.getType();
                    if (etype != null)
                        buf.append(etype).append("</span> ");
                    else
                        buf.append(_t("Unsupported type")).append(" ").append(pk.getUnknownTypeCode()).append("</span> ");
                }
            }
            buf.append("</span></td></tr>");
        }
        buf.append("<tr");
        if (debug)
            buf.append(" class=\"debugMode\"");
        buf.append("><td colspan=2>\n<ul class=netdb_leases>\n");
        boolean isMeta = ls.getType() == DatabaseEntry.KEY_TYPE_META_LS2;
        for (int i = 0; i < ls.getLeaseCount(); i++) {
            Lease lease = ls.getLease(i);
            buf.append("<li title=\"").append(_t("Lease")).append("\"><b");
            buf.append(" class=\"leaseNumber\">");
            buf.append(i + 1);
            buf.append("</b> <span class=tunnel_peer title=\"Gateway\">");
            buf.append(_context.commSystem().renderPeerHTML(lease.getGateway(), false));
            buf.append("</span> ");
            if (!isMeta && debug) {
                buf.append("<span class=netdb_tunnel title=\"Tunnel ID\">").append(" <span class=tunnel_id>")
                   .append(lease.getTunnelId().getTunnelId()).append("</span></span> ");
            }
            long exl = lease.getEndTime() - now;
            if (debug) {
                if (exl > 0)
                    buf.append("&#10140; <b class=netdb_expiry>").append(_t("Expires in {0}", DataHelper.formatDuration2(exl))).append("</b>");
                else
                    buf.append("&#10140; <b class=netdb_expiry>").append(_t("Expired {0} ago", DataHelper.formatDuration2(0-exl))).append("</b>");
            }
            buf.append("</li>\n");
        }
        buf.append("</ul>\n</td></tr>\n");
        buf.append("</table>\n");
    }

    /**
     *  @param mode 0: charts only; 1: full routerinfos; 2: abbreviated routerinfos
     *         mode 3: Same as 0 but sort countries by count
     */
    public void renderStatusHTML(Writer out, int pageSize, int page, int mode, String client, boolean clientsOnly) throws IOException {
        if (!_context.netDb().isInitialized()) {
            out.write("<div id=notinitialized>");
            out.write(_t("Not initialized"));
            out.write("</div>");
            out.flush();
            return;
        }
        Log log = _context.logManager().getLog(NetDbRenderer.class);
        long start = System.currentTimeMillis();

        boolean full = mode == 1;
        boolean shortStats = mode == 2;
        boolean showStats = full || shortStats;  // this means show the router infos
        Hash us = _context.routerHash();

        Set<RouterInfo> routers = new TreeSet<RouterInfo>(new RouterInfoComparator());
        if (client != null) {
            routers.addAll(_context.clientNetDb(client).getRouters());
        } else if (clientsOnly) {
            routers.addAll(_context.netDbSegmentor().getRoutersKnownToClients());
        } else {
            routers.addAll(_context.netDb().getRouters());
        }
        int toSkip = pageSize * page;
        boolean nextpg = routers.size() > toSkip + pageSize;
        StringBuilder buf = new StringBuilder(8192);
        if (showStats && full && page == 0)
            buf.append("<p class=infohelp id=debugmode>Advanced mode - includes all statistics published by floodfills. <a href=\"/netdb?f=2\">[Compact mode]</a></p>\n");
        else if (shortStats && page == 0)
            buf.append("<p class=infohelp>Compact mode - does not include statistics published by floodfills. <a href=\"/netdb?f=1\">[Advanced mode]</a></p>\n");
        if (showStats && (page > 0 || nextpg)) {
            buf.append("<p class=infohelp id=pagenav>");
            if (page > 0) {
                buf.append("<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page)
                   .append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
            }
            buf.append(_t("Page")).append(' ').append(page + 1);
            if (nextpg) {
                buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page + 2)
                   .append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Next Page"));
                buf.append("</a>");
            }
            buf.append("</p>");
        }
        if (showStats && page == 0) {
            RouterInfo ourInfo = _context.router().getRouterInfo();
            renderRouterInfo(buf, ourInfo, true, true);
            out.write(buf.toString());
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
                    out.write(buf.toString());
                    buf.setLength(0);
                }
                String routerVersion = ri.getOption("router.version");
                if (routerVersion != null)
                    versions.increment(routerVersion);
                String country = _context.commSystem().getCountry(key);
                if(country != null)
                    countries.increment(country);
                transportCount[classifyTransports(ri)]++;
            }
        }
        if (showStats && (page > 0 || morePages)) {
            buf.append("<p class=infohelp id=pagenav>");
            if (page > 0) {
                buf.append("<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page).append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Previous Page"));
                buf.append("</a>&nbsp;&nbsp;&nbsp;");
            }
            buf.append(_t("Page")).append(' ').append(page + 1);
            if (morePages) {
                buf.append("&nbsp;&nbsp;&nbsp;<a href=\"/netdb?f=").append(mode).append("&amp;pg=").append(page + 2).append("&amp;ps=").append(pageSize).append("\">");
                buf.append(_t("Next Page"));
                buf.append("</a>");
            }
            buf.append("</p>");
        }
/*
        if (log.shouldWarn()) {
            long end = System.currentTimeMillis();
            log.warn("part 1 took " + (end - start));
            start = end;
        }
*/
        if (!showStats) {

            // the summary table
            buf.append("<table id=netdboverview width=100%>\n<tr><th colspan=3>");
/*
            if (client != null) {
                   buf.append(_t("Network Database Router Statistics for Client " + client));
            } else if (clientsOnly) {
                buf.append(_t("Network Database Router Statistics for all Clients" + client));
            } else {
                buf.append(_t("Network Database Router Statistics"));
           }
*/
        buf.append(_t("Network Database Router Statistics"));
        buf.append("</th></tr>\n<tr><td style=vertical-align:top>");
            // versions table
            List<String> versionList = new ArrayList<String>(versions.objects());
            if (!versionList.isEmpty()) {
                Collections.sort(versionList, Collections.reverseOrder(new VersionComparator()));
                buf.append("<table id=netdbversions>\n");
                buf.append("<thead>\n<tr><th>" + _t("Version") + "</th><th>" + _t("Count") + "</th></tr>\n</thead>\n");
                for (String routerVersion : versionList) {
                    int num = versions.count(routerVersion);
                    String ver = DataHelper.stripHTML(routerVersion);
                    buf.append("<tr><td><span class=version><a href=\"/netdb?v=").append(ver).append("\">").append(ver);
                    buf.append("</a></span></td><td>").append(num).append("</td></tr>\n");
                }
                buf.append("</table>\n");
            }
            buf.append("</td><td style=vertical-align:top>\n");
            out.write(buf.toString());
            buf.setLength(0);
/*
            if (log.shouldWarn()) {
                long end = System.currentTimeMillis();
                log.warn("part 2 took " + (end - start));
                start = end;
            }
*/
            String showAll = _t("Show all routers with this capability in the NetDb");
            buf.append("<table id=netdbtiers>\n");
            buf.append("<thead>\n<tr><th>" + _t("Bandwidth Tier") + "</th><th>" + _t("Count") + "</th></tr>\n</thead>\n");
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
               .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW256).size()).append("</td></tr>\n");
            buf.append("<tr><td><a href=\"/netdb?caps=P\" title=\"").append(showAll).append("\"><b>P</b></a>262 - 2047&#8239;KB/s</td><td>")
               .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW512).size()).append("</td></tr>\n");
            buf.append("<tr><td><a href=\"/netdb?caps=X\" title=\"").append(showAll).append("\"><b>X</b></a>Over 2048&#8239;KB/s</td><td>")
               .append(_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_BW_UNLIMITED).size()).append("</td></tr>\n");
            buf.append("</table>\n");
            out.write(buf.toString());
            buf.setLength(0);

            if (_context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_MODERATE).size() > 0 ||
                _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_SEVERE).size() > 0 ||
                _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_NO_TUNNELS).size() > 0) {

                buf.append("<table id=netdbcongestion>\n");
                buf.append("<thead>\n<tr><th>" + _t("Congestion Cap") + "</th><th>" + _t("Count") + "</th></tr>\n</thead>\n");
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
                out.write(buf.toString());
                buf.setLength(0);
            }
/*
            if (log.shouldWarn()) {
                long end = System.currentTimeMillis();
                log.warn("part 3 took " + (end - start));
                start = end;
            }
*/
            // transports table
            buf.append("<table id=netdbtransports>\n");
            buf.append("<thead>\n<tr><th>" + _t("Transports") + "</th><th>" + _t("Count") + "</th></tr>\n</thead>\n");
            for (int i = 0; i < TNAMES.length; i++) {
                int num = transportCount[i];
                if (num > 0) {
                    buf.append("<tr><td>").append(_t(TNAMES[i]));
                    buf.append("</td><td>").append(num).append("</td></tr>\n");
                }
            }
            buf.append("</table>\n");
            buf.append("</td>");
            buf.append("<td style=vertical-align:top>\n");

            // country table
            List<String> countryList = new ArrayList<String>(countries.objects());
            buf.append("<table id=netdbcountrylist data-sortable>\n");
            buf.append("<thead>\n<tr><th>" + _t("Country") + "</th><th data-sort-default>" + _t("Count") + "</th></tr>\n</thead>\n");
            if (!countryList.isEmpty()) {
                Collections.sort(countryList, new CountryComparator());
                buf.append("<tbody id=cclist>");
                for (String country : countryList) {
                    int num = countries.count(country);
                    buf.append("<tr><td><a href=\"/netdb?c=").append(country).append("\">");
                    buf.append("<img width=20 height=15 alt=\"").append(country.toUpperCase(Locale.US)).append("\"");
                    buf.append(" src=\"/flags.jsp?c=").append(country).append("\">");
                    buf.append(getTranslatedCountry(country));
                    buf.append("</a></td><td>").append(num).append("</td></tr>\n");
                }
                buf.append("</tbody></table>\n");
            } else {
                buf.append("<tbody><tr><td colspan=2>").append(_t("Initializing"))
                   .append("&hellip;</td></tr></tbody></table>\n");
            }
            buf.append("</td></tr>\n</table>\n");
/*
            if (log.shouldWarn()) {
                long end = System.currentTimeMillis();
                log.warn("part 4 took " + (end - start));
            }
*/
        } // if !showStats
        out.write(buf.toString());
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
             return coll.compare(getTranslatedCountry(l),
                                 getTranslatedCountry(r));
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
             if (rv != 0)
                 return rv;
             return coll.compare(getTranslatedCountry(l),
                                 getTranslatedCountry(r));
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
             if (rv != 0)
                 return rv;
             String lh = l.getHost();
             String rh = r.getHost();
             if (lh == null)
                 return (rh == null) ? 0 : -1;
             if (rh == null)
                 return 1;
             return lh.compareTo(rh);
        }
    }

    /**
     *  Be careful to use stripHTML for any displayed routerInfo data
     *  to prevent vulnerabilities
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo info, boolean isUs, boolean full) {
        String hash = info.getIdentity().getHash().toBase64();
        String family = info.getOption("family");
        buf.append("<table class=\"netdbentry lazy\">\n")
//           .append("<tr id=\"").append(hash.substring(0, 6)).append("\"><th>");
           .append("<tr><th>");
        if (isUs) {
            buf.append("<b id=our-info>" + _t("Our info") + ":</b></th><th><code>").append(hash)
               .append("</code></th><th id=netdb_ourinfo>");
        } else {
            buf.append("<b>" + _t("Router") + ":</b></th><th><code>").append(hash).append("</code></th><th>");
        }
        Hash h = info.getHash();
        if (_context.banlist().isBanlisted(h)) {
            buf.append("<a class=banlisted href=\"/profiles?f=3\" title=\"").append(_t("Router is banlisted")).append("\">Banned</a> ");
        }
        byte[] padding = info.getIdentity().getPadding();
        if (padding != null && padding.length >= 64) {
            if (DataHelper.eq(padding, 0, padding, 32, 32)) {
                buf.append("<span class=compressible title=\"" + _t("RouterInfo is compressible") + "\"></span> ");
            }
        }
        String tooltip = "\" title=\"" + _t("Show all routers with this capability in the NetDb") + "\"><span";
        boolean hasD = DataHelper.stripHTML(info.getCapabilities()).contains("D");
        boolean hasE = DataHelper.stripHTML(info.getCapabilities()).contains("E");
        boolean hasG = DataHelper.stripHTML(info.getCapabilities()).contains("G");
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
        if (hasD) {
            if (isU)
                caps = caps.replace("D","").replace("class=tier", "class=\"tier isD\"").replace("\"><span class", "UD\"><span class");
            else
                caps = caps.replace("D","").replace("class=tier", "class=\"tier isD\"").replace("\"><span class", "RD\"><span class");
        } else if (hasE) {
            if (isU)
                caps = caps.replace("E","").replace("class=tier", "class=\"tier isE\"").replace("\"><span class", "UE\"><span class");
            else
                caps = caps.replace("E","").replace("class=tier", "class=\"tier isE\"").replace("\"><span class", "RE\"><span class");
        } else if (hasG) {
            if (isU)
                caps = caps.replace("G","").replace("class=tier", "class=\"tier isG\"").replace("\"><span class", "UG\"><span class");
            else
                caps = caps.replace("G","").replace("class=tier", "class=\"tier isG\"").replace("\"><span class", "RG\"><span class");
        }
        caps = caps.replace("\"><span", tooltip);
        buf.append(caps);
/*
        if (info != null) {
            buf.append(_context.commSystem().renderPeerCaps(h, false));
        }
*/
        buf.append("&nbsp;<a href=\"/netdb?v=").append(DataHelper.stripHTML(info.getVersion())).append("\">")
           .append("<span class=version title=\"").append(_t("Show all routers with this version in the NetDb"))
           .append("\">").append(DataHelper.stripHTML(info.getVersion())).append("</span></a>");
        if (!isUs) {
            buf.append("<span class=netdb_header>");
            if (family != null) {
                FamilyKeyCrypto fkc = _context.router().getFamilyKeyCrypto();
                buf.append("<a class=\"familysearch");
                if (fkc != null)
                    buf.append(" verified");
                buf.append("\" href=\"/netdb?fam=").append(DataHelper.stripHTML(family))
                   .append("\" title=\"").append(_t("Show all members of the {0} family in NetDb", DataHelper.stripHTML(family)))
                   .append("\">").append(_t("Family")).append("</a>");
            }
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(info.getHash());
            if (prof != null) {
                buf.append("<a class=viewprofile href=\"/viewprofile?peer=").append(hash)
                   .append("\" title=\"").append(_t("View profile"))
                   .append("\">").append(_t("Profile")).append("</a>");
            }
            buf.append("<a class=configpeer href=\"/configpeer?peer=").append(hash)
               .append("\" title=\"").append(_t("Configure peer"))
               .append("\">").append(_t("Edit")).append("</a>");
            buf.append(_context.commSystem().renderPeerFlag(h));
        } else {
            long used = (long) _context.statManager().getRate("router.memoryUsed").getRate(60*1000).getAvgOrLifetimeAvg();
            used /= 1024*1024;
            buf.append("&nbsp;<span id=netdb_ram><b>").append(_t("Memory usage")).append(":</b> ").append(used).append("M</span>");
        }
        buf.append("</th></tr>\n<tr>");
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
            }
/*
            boolean debug = _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
            if (debug) {
                buf.append("<span class=netdb_info><b>").append(_t("Routing Key")).append(":</b> ")
                   .append("<span class=rkey>").append(info.getRoutingKey().toBase64()).append("</span>&nbsp;&nbsp;");
                byte[] padding = info.getIdentity().getPadding();
                if (padding != null && padding.length >= 64) {
                    if (DataHelper.eq(padding, 0, padding, 32, 32))
                        buf.append("<b>Compressible:</b> true");
                }
                buf.append("</span>\n");
            }
*/
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
           .append("<td colspan=2 class=\"netdb_addresses\">\n")
           .append("<ul>\n");

        Collection<RouterAddress> addrs = info.getAddresses();
        if (addrs.isEmpty()) {
            buf.append(_t("n/a"));
        } else {
            if (addrs.size() > 1) {
                // addrs is unmodifiable
                List<RouterAddress> laddrs = new ArrayList<RouterAddress>(addrs);
                Collections.sort(laddrs, new RAComparator());
                addrs = laddrs;
            }
            for (RouterAddress addr : addrs) {
                String style = addr.getTransportStyle();
                    buf.append("<li>");
                buf.append("<b class=\"netdb_transport\"");
                int cost = addr.getCost();
                if (!((style.equals("SSU") && cost == 5) || (style.startsWith("NTCP") && cost == 10))) {
                    buf.append(" title=\"").append(_t("Cost")).append(": ").append(cost).append("\"");
                }
                buf.append(">").append(DataHelper.stripHTML(style)).append("</b> ");
                Map<Object, Object> p = addr.getOptionsMap();
                for (Map.Entry<Object, Object> e : p.entrySet()) {
                    String name = (String) e.getKey();
                    String val = (String) e.getValue();
                    // hide keys which are dupes of the router hash displayed in header, and ntcp version
                    if (name.contains("key") || name.contains("itag") || name.contains("iexp") || (name.equals("v") && style.equals("NTCP"))) {
                        buf.append("<span class=hide><span class=nowrap><span class=netdb_name>")
                           .append(_t(DataHelper.stripHTML(name)))
                           .append(":</span> <span class=netdb_info>").append(DataHelper.stripHTML(val)).append("</span></span></span>");
                    // tag the hosts and ports so we can make them visually prominent and single clickable
                    } else if (name.contains("host")) {
                        buf.append("<span class=nowrap><span class=netdb_name>")
                           .append(_t(DataHelper.stripHTML(name))).append(":</span> ")
                           .append("<span class=\"netdb_info host\">");
                        if (DataHelper.stripHTML(val).equals("::")) {
                            buf.append(_t("n/a")); // fix empty ipv6
                        } else {
                            buf.append("<a title=\"").append(_t("Show all routers with this address in the NetDb")).append("\" ");
                            if (DataHelper.stripHTML(val).contains(":"))
                                buf.append(" href=\"/netdb?ipv6=");
                            else
                                buf.append(" href=\"/netdb?ip=");
                            if (DataHelper.stripHTML(val).contains(":")) {
                                if (DataHelper.stripHTML(val).length() > 8)
                                    buf.append(DataHelper.stripHTML(val).substring(0,4));
                                else
                                    buf.append(DataHelper.stripHTML(val));
                            } else {
                                buf.append(DataHelper.stripHTML(val));
                            }
                            buf.append("\">").append(DataHelper.stripHTML(val)).append("</a>");
                        }
                        buf.append("</span>");
/*
                        if (!DataHelper.stripHTML(val).equals("::") && !DataHelper.stripHTML(val).equals("127.0.0.1")) {
                            buf.append(" <span class=\"gwhois\" title=\"").append(_t("Lookup via gwhois.org"))
                               .append("\"><a href=\"https://gwhois.org/").append(DataHelper.stripHTML(val))
                               .append("\" target=_blank>gwhois</a></span>");
                        }
                        if (!DataHelper.stripHTML(val).equals("::") && !DataHelper.stripHTML(val).equals("127.0.0.1")) {
                            buf.append("<span>").append(DataHelper.stripHTML(val)).append("</span>");
                        }
*/
                        buf.append("</span> ");
                    } else if (name.contains("port")) {
                        buf.append("<span class=nowrap><span class=netdb_name>")
                           .append(_t(DataHelper.stripHTML(name)))
                           .append(":</span> <span class=\"netdb_info port\">").append("<a title=\"")
                           .append(_t("Show all routers with this port in the NetDb")).append("\" ")
                           .append(" href=\"/netdb?port=")
                           .append(DataHelper.stripHTML(val)).append("\">")
                           .append(DataHelper.stripHTML(val))
                           .append("</a></span></span> ");
                     } else {
                        buf.append(" <span class=nowrap><span class=netdb_name>");
                        buf.append(_t(DataHelper.stripHTML(name)))
                           .append(":</span> <span class=netdb_info>").append(DataHelper.stripHTML(val)).append("</span></span> ");
                     }
                }
                if (!isUs) {
                    buf.append("</li>\n");
                }
            }
            buf.append("</ul>\n");
            buf.append("</td></tr>\n");
        }
        if (full && !isUs) {
            PeerProfile prof = _context.profileOrganizer().getProfileNonblocking(info.getHash());
            if (prof != null) {
                buf.append("<tr><td><b>").append(_t("Stats")).append(":</b><td colspan=2>\n<ul class=\"netdbStats\">");
                Map<Object, Object> p = info.getOptionsMap();
                for (Map.Entry<Object, Object> e : p.entrySet()) {
                    //String name = (String) e.getKey();
                    String key = (String) e.getKey();
                    String netDbKey = DataHelper.stripHTML(key)
                        .replace("caps", "<li class=\"cap_stat hide\" hidden><b>"  + _t("Capabilities")) // hide caps as already in the header
                        .replace("router.version", "<li class=hide hidden><b>" + _t("Version")) // hide version as already in the header
                        .replace("coreVersion", "<li class=hide hidden><b>"    + _t("Core version")) // do we need this?
                        .replace("netdb.", "")
                        .replace("netId", "<li class=hide hidden><b>" + _t("Network ID"))
                        .replace("knownLeaseSets", "<li><b>" + _t("LeaseSets"))
                        .replace("knownRouters", "<li><b>" + _t("Routers"))
                        .replace("stat_", "")
                        .replace("uptime", "<li><b>" + _t("Uptime"))
                        // TODO: place family entries underneath general network stats
                        .replace("family.", "Family ")
                        // hide family name in css as it's already displayed above
                        .replace("family", "<li class=\"longstat fam hide\" hidden><b>" + _t("Family"))
                        .replace("Family key", "<li class=\"longstat fam\"><b>" + _t("Family Key"))
                        .replace("Family sig", "<li class=\"longstat fam\"><b>" + _t("Family Sig"))
                        .replace("tunnel.buildExploratoryExpire.60m",  "<li class=longstat><b>"   + _t("Exploratory tunnels expire (1h)"))
                        .replace("tunnel.buildExploratoryReject.60m",  "<li class=longstat><b>"   + _t("Exploratory tunnels reject (1h)"))
                        .replace("tunnel.buildExploratorySuccess.60m", "<li class=longstat><b>"   + _t("Exploratory tunnels build success (1h)"))
                        .replace("tunnel.buildClientExpire.60m",       "<li class=longstat><b>"  + _t("Client tunnels expire (1h)"))
                        .replace("tunnel.buildClientReject.60m",       "<li class=longstat><b>"   + _t("Client tunnels reject (1h)"))
                        .replace("tunnel.buildClientSuccess.60m",      "<li class=longstat><b>"   + _t("Client tunnels build success (1h)"))
                        .replace("tunnel.participatingTunnels.60m",    "<li class=longstat><b>"   + _t("Participating tunnels (1h)"))
                        .replace("tunnel.participatingTunnels.60s",    "<li class=longstat><b>"   + _t("Participating tunnels (60s)"))
                        .replace("stat_bandwidthSendBps.60m",          "<li class=longstat><b>"   + _t("Bandwidth send rate (1h)"))
                        .replace("stat_bandwidthReceiveBps.60m",       "<li class=longstat><b>"   + _t("Bandwidth receive rate (1h)"));
                    buf.append(netDbKey);
                    String val = (String) e.getValue();
                    String netDbValue = DataHelper.stripHTML(val)
                       .replace("XO", "X")
                       .replace("PO", "P")
                       .replace("R", "")
                       .replace("U", "")
                       .replace(";", " <span class=\"bullet\">&bullet;</span> ")
                       .replace("&bullet;</span> 555", "&bullet;</span> " +_t("n/a"));
                    buf.append(":</b> ");
                    buf.append(netDbValue)
                       .append("</li>\n");
                }
                long now = _context.clock().now();
                long heard = prof.getFirstHeardAbout();
                if (heard > 0) {
                    long peerAge = Math.max(now - heard, 1);
                    buf.append("<li><b>").append(_t("First heard about")).append(":</b> ")
                       .append(_t("{0} ago", DataHelper.formatDuration2(peerAge))).append("</li>\n");
                } else {
                    buf.append("<li><b>").append(_t("First heard about")).append(":</b> ")
                       .append(_t("n/a")).append("</li>\n");
                }
                heard = prof.getLastHeardAbout();
                if (heard > 0) {
                    long peerAge = Math.max(now - heard, 1);
                    buf.append("<li><b>").append(_t("Last heard about")).append(":</b> ")
                       .append(_t("{0} ago", DataHelper.formatDuration2(peerAge))).append("</li>\n");
                } else {
                    buf.append("<li><b>").append(_t("Last heard about")).append(":</b> ")
                       .append(_t("n/a")).append("</li>\n");
                }
                heard = prof.getLastHeardFrom();
                if (heard > 0) {
                    long peerAge = Math.max(now - heard, 1);
                    buf.append("<li><b>").append(_t("Last heard from")).append(":</b> ")
                       .append(_t("{0} ago", DataHelper.formatDuration2(peerAge))).append("</li>\n");
                } else {
                    buf.append("<li><b>").append(_t("Last heard from")).append(":</b> ")
                       .append(_t("n/a")).append("</li>\n");
                }
                buf.append("</ul>\n</td></tr>\n");
            }
        } else if (full) {
            buf.append("<tr><td><b>" + _t("Stats") + ":</b><td colspan=2>\n<ul class=netdbStats>");
            Map<Object, Object> p = info.getOptionsMap();
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                String key = (String) e.getKey();
                String netDbKey = DataHelper.stripHTML(key)
                    .replace("caps", "<li class=\"cap_stat hide\" hidden><b>"  + _t("Capabilities")) // hide caps as already in the header
                    .replace("router.version", "<li class=hide hidden><b>" + _t("Version")) // hide version as already in the header
                    .replace("coreVersion", "<li class=hide hidden><b>"    + _t("Core version")) // do we need this?
                    .replace("netdb.", "")
                    .replace("netId", "<hr><li><b>" + _t("Network ID")) // only show for our own id
                    .replace("knownLeaseSets", "<li><b>" + _t("LeaseSets"))
                    .replace("knownRouters", "<li><b>" + _t("Routers"))
                    .replace("stat_", "")
                    .replace("uptime", "<li><b>" + _t("Uptime"))
                    // TODO: place family entries underneath general network stats
                    .replace("family.", "Family ")
                    // hide family name in css as it's already displayed above
                    .replace("family", "<li class=\"longstat fam hide\" hidden><b>" + _t("Family"))
                    .replace("Family key", "<li class=\"longstat fam\"><b>" + _t("Family Key"))
                    .replace("Family sig", "<li class=\"longstat fam\"><b>" + _t("Family Sig"))
                    .replace("tunnel.buildExploratoryExpire.60m",  "<li class=longstat><b>"   + _t("Exploratory tunnels expire (1h)"))
                    .replace("tunnel.buildExploratoryReject.60m",  "<li class=longstat><b>"   + _t("Exploratory tunnels reject (1h)"))
                    .replace("tunnel.buildExploratorySuccess.60m", "<li class=longstat><b>"   + _t("Exploratory tunnels build success (1h)"))
                    .replace("tunnel.buildClientExpire.60m",       "<li class=longstat><b>"  + _t("Client tunnels expire (1h)"))
                    .replace("tunnel.buildClientReject.60m",       "<li class=longstat><b>"   + _t("Client tunnels reject (1h)"))
                    .replace("tunnel.buildClientSuccess.60m",      "<li class=longstat><b>"   + _t("Client tunnels build success (1h)"))
                    .replace("tunnel.participatingTunnels.60m",    "<li class=longstat><b>"   + _t("Participating tunnels (1h)"))
                    .replace("tunnel.participatingTunnels.60s",    "<li class=longstat><b>"   + _t("Participating tunnels (60s)"))
                    .replace("stat_bandwidthSendBps.60m",          "<li class=longstat><b>"   + _t("Bandwidth send rate (1h)"))
                    .replace("stat_bandwidthReceiveBps.60m",       "<li class=longstat><b>"   + _t("Bandwidth receive rate (1h)"));
                    buf.append(netDbKey);
                    String val = (String) e.getValue();
                    String netDbValue = DataHelper.stripHTML(val)
                       .replace("XO", "X")
                       .replace("PO", "P")
                       .replace("R", "")
                       .replace("U", "")
                       .replace(";", " <span class=\"bullet\">&bullet;</span> ")
                       .replace("&bullet;</span> 555", "&bullet;</span> " +_t("n/a"));
                    buf.append(":</b> ");
                    buf.append(netDbValue)
                       .append("</li>\n");
            }
            buf.append("</ul>\n</td></tr>\n");
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
                                  _x("IPv6 NTCP"), _x("IPv6 NTCP, SSU"), _x("IPv6 Only NTCP, SSU, introducers"), _x("IPv6 NTCP, SSU, introducers") };
    /**
     *  what transport types
     */
    private static int classifyTransports(RouterInfo info) {
        int rv = 0;
        for (RouterAddress addr : info.getAddresses()) {
            String style = addr.getTransportStyle();
            if (style.equals("NTCP2") || style.equals("NTCP")) {
                rv |= NTCP;
            } else if (style.equals("SSU") || style.equals("SSU2")) {
                if (addr.getOption("itag0") != null)
                    rv |= SSUI;
                else
                    rv |= SSU;
            }
            String host = addr.getHost();
            if (host != null && host.contains(":")) {
                rv |= IPV6;
            } else {
                String caps = addr.getOption("caps");
                if (caps != null && caps.contains("6"))
                    rv |= IPV6;
            }
        }
        // map invalid values with "" in TNAMES
        if (rv == 3)
            rv = 2;
        else if (rv == 7)
            rv = 6;
        else if (rv == 8)
            rv = 0;
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
            // convert to expanded
            byte[] bip = Addresses.getIPOnly(ip);
            if (bip != null)
                return Addresses.toString(bip);
        } else if (ip.contains(":0:")) {
            // convert to canonical
            return Addresses.toCanonicalString(ip);
        }
        return null;
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
