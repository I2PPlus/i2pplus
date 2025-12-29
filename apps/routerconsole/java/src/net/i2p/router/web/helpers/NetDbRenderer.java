package net.i2p.router.web.helpers;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static net.i2p.router.sybil.Util.biLog2;

import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.math.BigInteger;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.i2p.util.LHMCache;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
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
import net.i2p.router.JobImpl;
import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.router.transport.TransportImpl;
import net.i2p.router.util.HashDistance;
import net.i2p.router.web.Messages;
import net.i2p.stat.RateConstants;
import net.i2p.util.Addresses;
import net.i2p.util.ConvertToHash;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.VersionComparator;

/**
 *  Renders the network database (netdb) information for the router console.
 *  Handles router and leaseset listings, search, and statistics.
 *  Optimized for performance with parallel rendering and optional reverse DNS lookups.
 */
class NetDbRenderer {
    private final RouterContext _context;
    public NetDbRenderer (RouterContext ctx) {
        _context = ctx;
        _organizer = ctx.profileOrganizer();
        //NetDbCachingJob.schedule(_context);
    }
    private static final String PROP_ENABLE_REVERSE_LOOKUPS = "routerconsole.enableReverseLookups";
    public boolean enableReverseLookups() {return _context.getBooleanProperty(PROP_ENABLE_REVERSE_LOOKUPS);}
    public static final int LOOKUP_WAIT = 3 * 1000;
    public boolean isFloodfill() {return _context.netDb().floodfillEnabled();}
    public int localLSCount;
    private final ProfileOrganizer _organizer;
    private final int BATCH_SIZE = SystemVersion.isSlow() ? 8 : Math.max(SystemVersion.getCores() - 2, 16);
    private long now = System.currentTimeMillis();

    /**
     *  Comparator for LeaseSets, used in the leaseset listing.
     *  Prioritizes published, nicknamed, named, client, and meta leasesets.
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
             if (nicknameL && !nicknameR) return -1;
             if (nicknameR && !nicknameL) return 1;
             if (nameL && !nameR) return -1;
             if (nameR && !nameL) return 1;
             if (isClientL && !isClientR) return -1;
             if (isClientR && !isClientL) return 1;
             return keyL.toBase32().compareTo(keyR.toBase32());
        }
    }

    /**
     *  Comparator for LeaseSets sorted by hash distance from local router.
     *  Used in debug/floodfill mode.
     *  @since 0.7.14
     */
    private static class LeaseSetRoutingKeyComparator implements Comparator<LeaseSet>, Serializable {
         private final transient Hash _us;
         public LeaseSetRoutingKeyComparator(Hash us) {_us = us;}
         public int compare(LeaseSet l, LeaseSet r) {
             return HashDistance.getDistance(_us, l.getRoutingKey()).compareTo(HashDistance.getDistance(_us, r.getRoutingKey()));
        }
    }

    /**
     *  Renders router information matching search criteria to the given writer.
     *  Supports filtering by version, country, family, capabilities, IP, port, etc.
     *  Streams results to reduce memory pressure on low-memory systems.
     *  Performs reverse DNS lookups in parallel if enabled.
     *
     *  @param out Writer to output HTML
     *  @param pageSize number of results per page
     *  @param page zero-based page index
     *  @param routerPrefix optional base64 hash prefix to filter routers (null for all)
     *  @param version optional version string to filter routers
     *  @param country optional country code(s) (comma/space separated) to filter routers
     *  @param family optional router family name to filter routers
     *  @param capabilities optional capability characters (e.g. "fK") to match all
     *  @param ipAddress optional IPv4 address or prefix to filter routers
     *  @param sybil optional; if non-null, collects hashes for Sybil analysis
     *  @param port optional port or start of range (used with highPort)
     *  @param highPort optional end of port range
     *  @param signatureType optional signature type to filter routers
     *  @param encryptionType optional encryption type to filter routers
     *  @param mtu optional MTU value to filter routers
     *  @param ipv6Address optional IPv6 address or prefix to filter routers
     *  @param ssuCapabilities optional SSU capability characters to match
     *  @param transport optional transport style (e.g., "NTCP", "SSU")
     *  @param cost optional cost value to filter router addresses
     *  @param introducerCount unused
     *  @throws IOException if writing fails
     */
    public void renderRouterInfoHTML(Writer out, int pageSize, int page, String routerPrefix, String version,
                                     String country, String family, String capabilities, String ipAddress, String sybil,
                                     int port, int highPort, SigType signatureType, EncType encryptionType, String mtu,
                                     String ipv6Address, String ssuCapabilities, String transport, int cost, int introducerCount) throws IOException {
        StringBuilder buf = new StringBuilder(4 * 1024);
        NetworkDatabaseFacade networkDatabase = _context.netDb();
        List<Hash> sybilHashes = sybil != null ? new ArrayList<Hash>(128) : null;

        // Handle special cases (local or single router lookup)
        if (".".equals(routerPrefix)) {
            renderRouterInfo(buf, _context.router().getRouterInfo(), true);
            out.append(buf);
            return;
        } else if (routerPrefix != null && routerPrefix.length() >= 44) {
            Hash hash = new Hash(Base64.decode(routerPrefix));
            RouterInfo routerInfo = lookupRouterInfoWithWait(networkDatabase, hash, LOOKUP_WAIT);
            if (routerInfo != null) {renderRouterInfo(buf, routerInfo, false);}
            else {buf.append("<div class=netdbnotfound>").append(_t("Router not found")).append("</div>");}
            out.append(buf);
            return;
        }

        // Prepare URL for pagination
        StringBuilder urlParameters = new StringBuilder();
        appendUrlParam(urlParameters, "r", routerPrefix);
        appendUrlParam(urlParameters, "v", version);
        appendUrlParam(urlParameters, "c", country);
        appendUrlParam(urlParameters, "fam", family);
        appendUrlParam(urlParameters, "caps", capabilities);
        appendUrlParam(urlParameters, "tr", transport);
        appendUrlParam(urlParameters, "ip", ipAddress);
        if (port != 0) urlParameters.append("&amp;port=").append(port);
        appendUrlParam(urlParameters, "mtu", mtu);
        appendUrlParam(urlParameters, "ipv6", ipv6Address);
        appendUrlParam(urlParameters, "ssucaps", ssuCapabilities);
        if (cost != 0) urlParameters.append("&amp;cost=").append(cost);
        appendUrlParam(urlParameters, "sybil", sybil);

        // Streaming setup
        Stream<RouterInfo> routerStream = networkDatabase.getRouters().stream();

        // Apply filters
        if (routerPrefix != null) {
            routerStream = routerStream.filter(ri -> ri.getIdentity().getHash().toBase64().startsWith(routerPrefix));
        }
        if (version != null) {
            routerStream = routerStream.filter(ri -> version.equals(ri.getVersion()));
        }
        if (country != null) {
            Set<String> countryCodes = Arrays.stream(country.split("[, ]+")).map(String::trim).collect(Collectors.toSet());
            routerStream = routerStream.filter(ri -> {
                String routerCountry = _context.commSystem().getCountry(ri.getIdentity().getHash());
                return routerCountry != null && countryCodes.contains(routerCountry.toLowerCase(Locale.US));
            });
        }
        if (capabilities != null) {
            routerStream = routerStream.filter(ri -> {
                String caps = ri.getCapabilities();
                return capabilities.chars().allMatch(c -> caps.indexOf(c) >= 0);
            });
        }
        if (signatureType != null) {
            routerStream = routerStream.filter(ri -> ri.getIdentity().getSigType() == signatureType);
        }
        if (encryptionType != null) {
            routerStream = routerStream.filter(ri -> ri.getIdentity().getEncType() == encryptionType);
        }
        if (transport != null) {
            routerStream = routerStream.filter(ri -> {
                RouterAddress ra = ri.getTargetAddress(transport);
                return ra != null;
            });
        }
        if (family != null) {
            routerStream = routerStream.filter(ri -> {
                String fam = ri.getOption("family");
                return fam != null && fam.toLowerCase(Locale.US).contains(family.toLowerCase(Locale.US));
            });
        }

        // Handle IP filtering
        if (ipAddress != null) {
            routerStream = routerStream.filter(ri -> {
                return ri.getAddresses().stream().anyMatch(addr -> addr.getHost() != null && addr.getHost().startsWith(ipAddress));
            });
        }

        if (port != 0) {
            final int low = port;
            final int high = highPort > 0 ? highPort : port;

            if (transport != null && !transport.isEmpty()) {
                // Filter by port AND transport
                routerStream = routerStream.filter(ri -> {
                    return ri.getAddresses().stream().anyMatch(addr -> {
                        return addr.getTransportStyle().equals(transport) &&
                               addr.getPort() >= low && addr.getPort() <= high;
                    });
                });
            } else {
                // Filter by port only (any transport)
                routerStream = routerStream.filter(ri -> {
                    return ri.getAddresses().stream().anyMatch(addr -> {
                        int p = addr.getPort();
                        return p >= low && p <= high;
                    });
                });
            }
        }

        // Count total before applying page
        List<RouterInfo> allRouters = routerStream.collect(Collectors.toList());
        int totalSize = allRouters.size();

        if (totalSize == 0) {
            writeNoResults(buf, routerPrefix, version, country, family, capabilities, ipAddress, port, highPort,
                           mtu, ipv6Address, ssuCapabilities, cost, signatureType, encryptionType, transport);
            out.append(buf);
            return;
        }

        // Sort if needed
        Collections.sort(allRouters, RouterInfoComparator.getInstance());

        // Pagination
        int fromIndex = Math.min(page * pageSize, totalSize - 1);
        int toIndex = Math.min(fromIndex + pageSize, totalSize);
        List<RouterInfo> routersToRender = allRouters.subList(fromIndex, toIndex);

        // Reverse DNS lookups
        Map<String, String> rdnsLookups = enableReverseLookups()
            ? precacheReverseDNSLookups(routersToRender)
            : Collections.emptyMap();

        renderRoutersToWriter(routersToRender, out, false, page, pageSize);

        if (sybil != null) {
            sybilHashes.addAll(routersToRender.stream()
                                              .map(ri -> ri.getIdentity().getHash())
                                              .collect(Collectors.toList()));
        }

        // Pagination UI
        if (totalSize > pageSize) {
            paginate(buf, urlParameters, page, pageSize, toIndex < totalSize, totalSize);
            out.append(buf);
        }

        if (sybil != null) {
            SybilRenderer.renderSybilHTML(out, _context, sybilHashes, sybil);
        }
    }

    /**
     *  Appends a URL parameter to the given StringBuilder if the value is not null.
     *
     *  @param sb StringBuilder to append to
     *  @param key URL parameter name
     *  @param value parameter value, may be null
     */
    private void appendUrlParam(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append("&amp;").append(key).append("=").append(value);
        }
    }

    /**
     *  Writes a message indicating no routers matched the search criteria.
     *
     *  @param buf output buffer
     *  @param routerPrefix optional router hash prefix
     *  @param version optional version string
     *  @param country optional country code
     *  @param family optional family name
     *  @param capabilities optional capability string
     *  @param ipAddress optional IPv4 address
     *  @param port optional port or start of range
     *  @param highPort optional end of port range
     *  @param mtu optional MTU value
     *  @param ipv6Address optional IPv6 address
     *  @param ssuCapabilities optional SSU capabilities
     *  @param cost optional address cost
     *  @param signatureType optional signature type
     *  @param encryptionType optional encryption type
     *  @param transport optional transport style
     */
    private void writeNoResults(StringBuilder buf, String routerPrefix, String version, String country, String family,
                                String capabilities, String ipAddress, int port, int highPort, String mtu,
                                String ipv6Address, String ssuCapabilities, int cost, SigType signatureType,
                                EncType encryptionType, String transport) {
        buf.append("<div class=netdbnotfound>").append(_t("No routers with")).append(' ');
        if (routerPrefix != null) buf.append("Hash prefix ").append(routerPrefix).append(' ');
        if (version != null) buf.append("Version ").append(version).append(' ');
        if (country != null) buf.append("Country ").append(country).append(' ');
        if (family != null) buf.append("Family ").append(family).append(' ');
        if (ipAddress != null) buf.append("IPv4 ").append(ipAddress).append(' ');
        if (ipv6Address != null) buf.append("IPv6 ").append(ipv6Address).append(' ');
        if (port != 0) {
            buf.append("Port ").append(port);
            if (highPort > 0) buf.append('-').append(highPort);
            buf.append(' ');
        }
        if (mtu != null) buf.append("MTU ").append(mtu).append(' ');
        if (cost != 0) buf.append("Cost ").append(cost).append(' ');
        if (signatureType != null) buf.append("SigType ").append(signatureType).append(' ');
        if (encryptionType != null) buf.append("EncType ").append(encryptionType).append(' ');
        if (capabilities != null) buf.append("Caps ").append(capabilities).append(' ');
        if (ssuCapabilities != null) buf.append("SSU Caps ").append(ssuCapabilities).append(' ');
        if (transport != null) buf.append("Transport ").append(transport).append(' ');
        buf.append(_t("found in the network database")).append(".</div>");
    }

    /**
     *  Renders pagination controls.
     *
     *  @param buf output buffer
     *  @param ubuf URL parameters for links
     *  @param page current page (0-based)
     *  @param pageSize results per page
     *  @param morePages true if there are more pages after this one
     *  @param sz total number of results
     *  @since 0.9.64
     */
    private void paginate(StringBuilder buf, StringBuilder ubuf, int page, int pageSize, boolean morePages, int sz) {
        int totalPages = (int) Math.ceil((double) sz / pageSize);
        if (sz > 1) {
            String results = "<span id=results" + (sz > pageSize ? " class=more" : "") + ">" + sz + " " + _t("results") + "</span>\n";
            buf.append("<div id=pagenav hidden>\n").append(results);
            if (sz > pageSize) {
                int current = page + 1;
                if (page == 0) {page++;}
                if (current > 1) {
                    buf.append("<a href=\"/netdb?pg=").append(page).append("&amp;ps=").append(pageSize).append(ubuf)
                       .append("\" title=\"").append(_t("Previous Page")).append("\"><span id=prevPage class=pageLink>‹</span></a>");
                }  else {
                    buf.append("<span id=prevPage class=\"pageLink disabled\">‹</span>" );
                }
                for (int i = 1; i <= totalPages; i++) {
                    if (i <= totalPages) {
                        buf.append(" <a href=\"/netdb?pg=").append(i).append("&amp;ps=").append(pageSize).append(ubuf).append("\"")
                           .append(i == current ? " id=currentPage" : "").append(">")
                           .append("<span class=pageLink>").append(i).append("</span></a> ");
                    }
                }
                if (current < totalPages) {
                    buf.append("<a href=\"/netdb?pg=").append(page + 2).append("&amp;ps=").append(pageSize).append(ubuf)
                       .append("\" title=\"").append(_t("Next Page")).append("\">").append("<span id=nextPage class=pageLink>›</span></a>\n");
                } else {
                    buf.append("<span id=nextPage class=\"pageLink disabled\">›</span>\n");
                }
            }
            buf.append("</div>\n");
        }
    }

    /**
     *  Renders the page size input form (hidden by default).
     */
    private void renderPageSizeInput(StringBuilder buf) {
        buf.append("<form id=pagesize hidden>\n<label>")
           .append(_t("Results per page"))
           .append(": <input type=text name=pageSize value=\"\" maxlength=4 pattern=\"[0-9]{1,4}\"></label>\n<input type=submit value=")
           .append(_t("Update"))
           .append(">\n</form>");
    }

    /**
     *  Filters routers by hash prefix.
     *  @since 0.9.64
     */
    private static void filterHashPrefix(Set<RouterInfo> routers, String routerPrefix) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            Hash key = ri.getIdentity().getHash();
            if (!key.toBase64().startsWith(routerPrefix)) {iter.remove();}
        }
    }

    /**
     *  Filters routers by version.
     *  @since 0.9.64
     */
    private static void filterVersion(Set<RouterInfo> routers, String version) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            if (!ri.getVersion().equals(version)) {iter.remove();}
        }
    }

    /**
     *  Filters routers by country code.
     *  @since 0.9.64
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
     *  Counts floodfills in a country.
     *  @since 0.9.64
     */
    private int countFloodfillsInCountry(Set<RouterInfo> routers, String countryCode) {
        int count = 0;
        for (RouterInfo ri : routers) {
            String caps = ri.getCapabilities();
            Hash key = ri.getIdentity().getHash();
            if (_context.commSystem().getCountry(key).equalsIgnoreCase(countryCode.trim()) && caps.indexOf("f") >=0) {
                count++;
            }
        }
        return count;
    }

    /**
     *  Counts X-tier routers in a country.
     *  @since 0.9.64
     */
    private int countXTierInCountry(Set<RouterInfo> routers, String countryCode) {
        int count = 0;
        for (RouterInfo ri : routers) {
            String caps = ri.getCapabilities();
            Hash key = ri.getIdentity().getHash();
            if (_context.commSystem().getCountry(key).equalsIgnoreCase(countryCode.trim()) && caps.indexOf("X") >=0) {
                count++;
            }
        }
        return count;
    }

    /**
     *  Filters routers by capabilities (must contain all specified).
     *  @since 0.9.64
     */
    private static void filterCaps(Set<RouterInfo> routers, String caps) {
        int len = caps.length();
        outer:
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            String ca = ri.getCapabilities();
            for (int i = 0; i < len; i++) {
                if (ca.indexOf(caps.charAt(i)) < 0) {
                    iter.remove();
                    continue outer;
                }
            }
        }
    }

    /**
     *  Filters routers by signature type.
     *  @since 0.9.64
     */
    private static void filterSigType(Set<RouterInfo> routers, SigType type) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            if (ri.getIdentity().getSigType() != type) {iter.remove();}
        }
    }

    /**
     *  Filters routers by encryption type.
     *  @since 0.9.64
     */
    private static void filterEncType(Set<RouterInfo> routers, EncType type) {
        for (Iterator<RouterInfo> iter = routers.iterator(); iter.hasNext();) {
            RouterInfo ri = iter.next();
            if (ri.getIdentity().getEncType() != type) {iter.remove();}
        }
    }

    /**
     *  Filters routers by transport and version.
     *  @since 0.9.64
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
     *  Filters routers by family (case-insensitive substring match).
     *  @since 0.9.64
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
     *  Filters routers by exact IP match.
     *  @since 0.9.64
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
     *  Filters routers by IP prefix match (supports IPv4 ranges and IPv6 prefixes).
     *  @param altip alternative IPv6 representation, may be null
     *  @since 0.9.64
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
     *  Filters routers by port or port range.
     *  @since 0.9.64
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
     *  Filters routers by MTU.
     *  @since 0.9.64
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
     *  Filters routers by SSU capabilities (must contain all specified).
     *  @since 0.9.64
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
                    if (ca.indexOf(caps.charAt(i)) < 0) {break inner;}
                }
                continue outer;
            }
            iter.remove();
        }
    }

    /**
     *  Filters routers by address cost.
     *  @since 0.9.64
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
     *  Filters routers by presence of an introducer tag.
     *  @since 0.9.64
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

    private boolean isUnreachable(RouterInfo ri) {
        String caps = ri.getCapabilities();
        return caps != null && (caps.contains("U"));
    }

    /**
     *  Job used to wait for a router lookup.
     *  @since 0.9.48
     */
    private class LookupWaiter extends JobImpl {
        public LookupWaiter() {super(_context);}
        public void runJob() {synchronized(this) {notifyAll();}}
        public String getName() {return "Console NetDb Lookup";}
    }

    /**
     *  Looks up a RouterInfo by its hash with a timeout.
     *  Tries local lookup first, then remote, then local again.
     *
     *  @param networkDatabase the network database facade
     *  @param hash the router hash
     *  @param timeout max time to wait in milliseconds
     *  @return the RouterInfo or null if not found
     *  @since 0.9.68+
     */
    private RouterInfo lookupRouterInfoWithWait(NetworkDatabaseFacade networkDatabase, Hash hash, long timeout) {
        RouterInfo routerInfo = (RouterInfo) networkDatabase.lookupLocallyWithoutValidation(hash);
        if (routerInfo == null) {
            LookupWaiter lookupWaiter = new LookupWaiter();
            synchronized (lookupWaiter) {
                networkDatabase.lookupRouterInfo(hash, lookupWaiter, lookupWaiter, timeout);
                try {
                    lookupWaiter.wait(timeout);
                } catch (InterruptedException ignored) {}
            }
            routerInfo = (RouterInfo) networkDatabase.lookupLocallyWithoutValidation(hash);
        }
        return routerInfo;
    }

    /**
     * Precache reverse DNS lookups for a collection of RouterInfo entries.
     *
     * @param routers Collection of RouterInfo objects to resolve
     * @return Map of IP address to canonical hostname (may be empty)
     */
    public Map<String, String> precacheReverseDNSLookups(Collection<RouterInfo> routers) {
        final int MAX_CACHED_ENTRIES = 5000;
        if (!enableReverseLookups() || _context.router().isHidden()) {
            return Collections.emptyMap();
        }

        Set<String> ipSet = new LinkedHashSet<>();
        for (RouterInfo ri : routers) {
            String ip = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(ri));
            if (ip != null && !ip.isEmpty()) {
                ipSet.add(ip);
                if (ipSet.size() > MAX_CACHED_ENTRIES) {
                    Iterator<String> it = ipSet.iterator();
                    if (it.hasNext()) {
                      it.next();
                      it.remove();
                    }
                }
            }
        }

        if (ipSet.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> rdnsLookups = new HashMap<>();
        int cores = Math.max(1, SystemVersion.getCores());
        int poolSize = Math.max(1, Math.min(Math.max(cores/2, 4), Math.max(1, ipSet.size())));
        ExecutorService dnsExecutor = Executors.newFixedThreadPool(poolSize);
        List<Future<String>> dnsFutures = new ArrayList<>();

        for (String ip : ipSet) {
            dnsFutures.add(dnsExecutor.submit(() -> {
                String hostname = _context.commSystem().getCanonicalHostNameSync(ip);
                return hostname;
            }));
        }

        long start = System.currentTimeMillis();
        long timeout = 2500;
        for (int i = 0; i < dnsFutures.size(); i++) {
            Future<String> future = dnsFutures.get(i);
            String ip = ipSet.toArray(new String[0])[i];
            try {
                long remaining = timeout - (System.currentTimeMillis() - start);
                if (remaining <= 0) break;
                String hostname = future.get(remaining, TimeUnit.MILLISECONDS);
                if (hostname != null && !hostname.equals(ip) && !hostname.equals("unknown")) {
                    rdnsLookups.put(ip, hostname);
                    putCachedReverseDNS(ip, hostname);
                }
            } catch (TimeoutException e) {
                break;
            } catch (InterruptedException | ExecutionException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        dnsExecutor.shutdownNow();
        try {dnsExecutor.awaitTermination(1, TimeUnit.SECONDS);} catch (InterruptedException e) {Thread.currentThread().interrupt();}

        return rdnsLookups;
    }

    /**
     * Precaches RouterInfo for all known introducers by querying the network database.
     * This helps ensure we have up-to-date info for routers we might need to contact.
     */
    public void precacheIntroducerInfos() {
        final int MAX_INTRODUCERS = 5000;
        Set<RouterInfo> allRouters = _context.netDb().getRouters();
        Set<Hash> introducerHashes = new LinkedHashSet<>();

        for (RouterInfo ri : allRouters) {
            if (isUnreachable(ri)) {
                for (RouterAddress address : ri.getAddresses()) {
                    if ("SSU".equals(address.getTransportStyle())) {
                        Map<Object, Object> options = address.getOptionsMap();
                        for (Map.Entry<Object, Object> entry : options.entrySet()) {
                            String key = entry.getKey().toString();
                            if (key.toLowerCase(Locale.US).startsWith("ih")) {
                                String value = entry.getValue().toString();
                                Hash ihost = ConvertToHash.getHash(value);
                                if (ihost != null) {
                                    introducerHashes.add(ihost);
                                    if (introducerHashes.size() > MAX_INTRODUCERS) {
                                        // Remove the oldest entry (first inserted due to LinkedHashSet)
                                        Iterator<Hash> it = introducerHashes.iterator();
                                        if (it.hasNext()) {
                                            it.next();
                                            it.remove();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        _context.logManager().getLog(NetDbRenderer.class).info("Pre-caching " + introducerHashes.size() + " introducer RouterInfos");

        for (Hash hash : introducerHashes) {
            scheduleLookup(hash);
        }
    }

    /**
     * Schedule a lookup job for a specific router if it's not already in the local netDb.
     *
     * @param hash Hash of the router to look up
     */
    private void scheduleLookup(Hash hash) {
        _context.netDb().lookupRouterInfoLocally(hash);
        if (_context.netDb().lookupRouterInfoLocally(hash) == null) {
            _context.jobQueue().addJob(new JobImpl(_context) {
                public void runJob() {
                    lookupRouterInfoWithWait(_context.netDb(), hash, LOOKUP_WAIT);
                }

                public String getName() {
                    return "Introducer Lookup: " + hash.toBase64().substring(0, 6);
                }
            });
        }
    }

    /**
     *  Special handling for 'O' cap (high bandwidth but not P or X).
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
     *  Renders all leasesets.
     *
     *  @param out Writer to output HTML
     *  @param debug if true, sort by distance and show debug info
     *  @param client if non-null, render only leasesets for that client
     *  @since 0.7.14
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
        localLSCount = 0;
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
        boolean ffEnabled = netdb.floodfillEnabled();
        if (debug) {buf.append("<table id=leasesetdebug>\n");}
        else if (client == null) {buf.append("<table id=leasesetsummary>\n");}
        if (notLocal) {
            buf.append("<tr><th colspan=2>").append(_t("Total Remote Leasesets")).append(": ").append(leases.size()).append("</th>");
            if (debug) {
                buf.append("<th colspan=2 class=right><a href=\"/netdb?l=1\">").append(_t("Compact mode"))
                    .append("</a> | <a href=\"/netdb?l=3\">").append(_t("Local Leasesets")).append("</a>");
            } else {
                buf.append("<th colspan=2 class=right><a href=\"/netdb?l=2\">").append(_t("Debug Mode"))
                   .append("</a> | <a href=\"/netdb?l=3\">").append(_t("Local Leasesets")).append("</a>");
            }
            buf.append("</th></tr>\n");
        } else {renderLocalSummary(out);}
        if (debug) {
            RouterKeyGenerator gen = _context.routerKeyGenerator();
            if (leases.size() > 0) {
                buf.append("<tr id=rapLS><td><b>").append(_t("Published (RAP) Leasesets")).append("</b></td><td colspan=4>").append(leases.size())
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
        if (!leases.isEmpty()) {
            boolean linkSusi = _context.portMapper().isRegistered("susidns");
            now = _context.clock().now();
            for (LeaseSet ls : leases) {
                String distance;
                if (debug) {
                    medianCount = rapCount / 2;
                    BigInteger dist = HashDistance.getDistance(ourRKey, ls.getRoutingKey());
                    if (ls.getReceivedAsPublished()) {
                        rapCount++;
                        if (c++ == medianCount) {median = dist;}
                    }
                    distance = fmt.format(biLog2(dist));
                } else {distance = null;}
                renderLeaseSet(buf, ls, debug, now, linkSusi, distance);
                out.append(buf);
                buf.setLength(0);
            }
            if (debug && isFloodfill()) {
                int ffCount = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL).size();
                int ffEstimated = Math.max(ffCount*4, 6000);
                if (median != null) {
                    double log2 = biLog2(median);
                    int total = (int) Math.round(Math.pow(2, 2 + 256 - 1 - log2));
                    buf.append("<table id=leasesetKeyspace>\n")
                       .append("<tbody><tr id=medianDistance><td><b>").append(_t("Median distance (bits)")).append(":</b></td><td colspan=3>")
                       .append(fmt.format(log2)).append("</td></tr>\n")
                       .append("<tr id=estimatedFF><td><b>").append(_t("Estimated total floodfills")).append("</b></td><td colspan=3>")
                       .append(ffEstimated).append("</td></tr>\n")
                       .append("<tr id=estimatedLS><td><b>").append(_t("Estimated total leasesets")).append("</b></td><td colspan=3>")
                       .append(total * rapCount / 4).append("</td></tr></tbody>\n</table>\n");
                }
            }
        }
        out.append(buf);
        out.flush();
    }

    private boolean isRendered = false;

    /**
     *  Renders the local leaseset summary header (hidden until JS shows it).
     */
    public void renderLocalSummary(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        if (isRendered) {return;}
        buf.append("<table id=leasesetsummary class=local hidden>\n<tr><th colspan=2>")
           .append(_t("Total Local Leasesets"))
           .append(": <span id=lsLocalCount></span></th><th colspan=2 class=right>");
        if (isFloodfill()) {
            buf.append("<a href=\"/netdb?l=1\">").append(_t("Remote Leasesets")).append("</a>");
        }
        buf.append("</th></tr></table>\n");
        out.append(buf);
        out.flush();
        isRendered = true;
    }

    /**
     *  Renders a single leaseset by hostname or b32.
     *
     *  @param out Writer to output HTML
     *  @param hostname the destination b32, full hash, or hostname
     *  @param debug if true, show debug info
     *  @since 0.9.57
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
                LookupWaiter lw = new LookupWaiter();
                synchronized(lw) {
                    _context.netDb().lookupLeaseSetRemotely(hash, lw, lw, LOOKUP_WAIT, null);
                    try {lw.wait(LOOKUP_WAIT + 1000);}
                    catch (InterruptedException ie) {}
                }
                ls = _context.netDb().lookupLeaseSetLocally(hash);
            }
            if (ls != null) {
                BigInteger dist = HashDistance.getDistance(_context.routerHash(), ls.getRoutingKey());
                DecimalFormat fmt = new DecimalFormat("#0.00");
                String distance = fmt.format(biLog2(dist));
                now = _context.clock().now();
                buf.append("<span id=singleLS></span>");
                renderLeaseSet(buf, ls, true, now, false, distance);
            } else {
                buf.append("<div class=netdbnotfound>").append(_t("LeaseSet for {0} not found in network database", hostname)).append("</div>");
            }
        }
        out.append(buf);
        out.flush();
    }

    /**
     *  Renders a single LeaseSet as HTML.
     *
     *  @param buf output buffer
     *  @param ls the LeaseSet to render
     *  @param debug if true, include debug info like distance
     *  @param now current time for expiry calculation
     *  @param linkSusi if true, link to susidns (unused)
     *  @param distance hash distance from local router (for debug)
     *  @since 0.9.57
     */
    private void renderLeaseSet(StringBuilder buf, LeaseSet ls, boolean debug, long now, boolean linkSusi, String distance) {
        if (!_context.netDb().isInitialized()) {
            buf.append("<div id=notinitialized>").append(_t("Not initialized")).append("</div>");
            return;
        }
        Destination dest = ls.getDestination();
        Hash key = ls.getHash();
        int type = ls.getType();
        if (key != null) {
            buf.append("<table class=\"leaseset").append(!debug ? " lazy" : "").append("\" id=\"ls_").append(key.toBase32().substring(0,4)).append("\">");
        } else {buf.append("<table class=\"leaseset").append(!debug ? " lazy" : "").append("\">");}
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
            buf.append("<a href=\"tunnels#").append(key.toBase64().substring(0,4)).append("\"><span class=\"lsdest");
            if (published) {buf.append(" published");}
            buf.append("\" title=\"").append(_t("View local tunnels for destination"));
            if (published) {buf.append(" (").append(_t("published")).append(")");}
            buf.append("\">");
            buf.append(getLocalClientNickname(key));
            buf.append("</span></a></th></tr>\n");
        } else {
            buf.append("<th>");
            String host = (dest != null) ? _context.namingService().reverseLookup(dest) : null;
            if (host != null) {
                buf.append("<a class=destlink target=_blank href=\"http://").append(host).append("/\">").append(host).append("</a></th>");
            } else {
                if (dest != null) {
                    String b32 = key.toBase32();
                    String truncb32 = b32.substring(0, 5) + "…¦" + b32.substring(b32.length() - 13, b32.length() - 10) + ".b32.i2p";
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
        boolean isExpired = exp <= 0;
        buf.append("<span class=\"nowrap expiry").append(isExpired ? " expired" : "").append("\" title=\"")
           .append(_t("Expiry")).append("\">").append(bullet).append("<b>");
        if (!isExpired) {buf.append(_t("Expires{0}", ":</b> " + DataHelper.formatDuration2(exp)).replace(" in", ""));}
        else {buf.append(_t("Expired{0} ago", ":</b> " + DataHelper.formatDuration2(0-exp)));}
        buf.append("</span>");
        if (debug) {
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
                    if (!isExpired) {
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
                               ls.getSigningKey().getType().toString().trim();
        if (stype.equals("EdDSA_SHA512_Ed25519")) {sigtype = "Ed25519";}
        else if (stype.equals("DSA_SHA1")) {sigtype = "SHA1";}
        else if (stype.equals("RedDSA_SHA512_Ed25519")) {sigtype = "Red25519";}
        else if (stype.equals("ECDSA_SHA256_P256")) {sigtype = "ECDSA/P256";}
        else if (stype.equals("ECDSA_SHA384_P384")) {sigtype = "ECDSA/P384";}
        else if (stype.equals("ECDSA_SHA512_P521")) {sigtype = "ECDSA/P521";}
        else if (stype.equals("RSA_SHA256_2048")) {sigtype = "RSA/2048";}
        else if (stype.equals("RSA_SHA384_3072")) {sigtype = "RSA/3072";}
        else if (stype.equals("RSA_SHA512_4096")) {sigtype = "RSA/4096";}
        buf.append("</td><td><span class=ls_crypto><span class=\"nowrap stype\" title=\"")
           .append(_t("Signature type"))
           .append(": ")
           .append(stype)
           .append("\">")
           .append(bullet)
           .append("<b>")
           .append(_t("Signature type"))
           .append(":</b> <span>")
           .append(sigtype)
           .append("</span></span></span></td></tr>\n<tr class=ekeys><td colspan=2>");
        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
            buf.append("<span class=\"nowrap ekey\" title=\"")
               .append(_t("Encryption Key"))
               .append("\">")
               .append(bullet)
               .append("<b>")
               .append(_t("Encryption Key"))
               .append(":</b> <span title=ELGAMAL_2048>ElGamal")
               .append(debug ? " <span class=pubKey title=\"" + _t("Public Key") + "\">[" +
                       ls.getEncryptionKey().toBase64().substring(0,8) + "&hellip;]</span>" : "")
               .append("</span>");
        } else if (type == DatabaseEntry.KEY_TYPE_LS2) {
            LeaseSet2 ls2 = (LeaseSet2) ls;
            for (PublicKey pk : ls2.getEncryptionKeys()) {
                EncType etype = pk.getType();
                buf.append(" <span class=\"nowrap ekey\" title=\"")
                   .append(_t("Encryption Key"))
                   .append("\">")
                   .append(bullet)
                   .append("<b>")
                   .append(_t("Encryption Key"))
                   .append(":</b> ");
                if (etype != null) {
                    String enctype = "";
                    if (etype.toString().trim().equals("ECIES_X25519")) {enctype = "ECIES";}
                    else if (etype.toString().trim().equals("ELGAMAL_2048")) {enctype = "ElGamal";}
                    else if (etype.toString().trim().equals("MLKEM512_X25519")) {enctype = "MLKEM512";}
                    else if (etype.toString().trim().equals("MLKEM768_X25519")) {enctype = "MLKEM768";}
                    else if (etype.toString().trim().equals("MLKEM1024_X25519")) {enctype = "MLKEM1024";}
                    buf.append("<span title=\"")
                       .append(etype)
                       .append("\">")
                       .append(enctype)
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
     *  Gets the local client nickname for a destination hash.
     *
     *  @param key the destination hash
     *  @return the nickname or a truncated base64 hash
     *  @since 0.9.67+
     */
    private String getLocalClientNickname(Hash key) {
        if (key == null) {return _t("Unknown");}
        TunnelPoolSettings in = _context.tunnelManager().getInboundSettings(key);
        TunnelPoolSettings out = _context.tunnelManager().getOutboundSettings(key);
        if (in != null && in.getDestinationNickname() != null) {
            return in.getDestinationNickname();
        } else if (out != null && out.getDestinationNickname() != null) {
            return out.getDestinationNickname();
        }
        return key.toBase64().substring(0,6);
    }

    /**
     *  Renders the network database status page.
     *
     *  @param out Writer to output HTML
     *  @param pageSize number of routers per page
     *  @param page zero-based page index
     *  @param mode rendering mode:
     *              0 = summary charts,
     *              1 = full router infos,
     *              2 = compact router infos,
     *              3 = summary charts sorted by country count
     */
    public void renderStatusHTML(Writer out, int pageSize, int page, int mode) throws IOException {
        if (!_context.netDb().isInitialized()) {
            out.write("<div id=notinitialized>" + _t("Not initialized") + "</div>\n");
            out.flush();
            return;
        }
        boolean full = (mode == 1);
        boolean shortStats = (mode == 2);
        boolean showStats = full || shortStats;
        boolean isLocal = false;
        Hash us = _context.routerHash();
        Set<RouterInfo> routers = new TreeSet<>(RouterInfoComparator.getInstance());
        routers.addAll(_context.netDb().getRouters());
        int offset = pageSize * page;
        boolean hasNextPage = routers.size() > offset + pageSize;
        StringBuilder buf = new StringBuilder(8192);
        if (showStats && page == 0) {
            isLocal = _context.router().getRouterInfo().getIdentity().getHash().equals(us);
            renderRouterInfo(buf, _context.router().getRouterInfo(), isLocal);
            out.append(buf);
            buf.setLength(0);
        }
        ObjectCounterUnsafe<String> versions = new ObjectCounterUnsafe<>();
        ObjectCounterUnsafe<String> countries = new ObjectCounterUnsafe<>();
        int[] transportCount = new int[TNAMES.length];
        List<RouterInfo> pagedRouters = new ArrayList<>();
        int skipped = 0;
        if (mode == 1 || mode == 2) {
            for (RouterInfo ri : routers) {
                Hash key = ri.getIdentity().getHash();
                isLocal = key != null && key.equals(us);
                if (!key.equals(us)) {
                    if (skipped < offset) {
                        skipped++;
                    } else if (pagedRouters.size() < pageSize) {
                        pagedRouters.add(ri);
                    } else {
                        break;
                    }
                }
                String routerVersion = ri.getOption("router.version");
                if (routerVersion != null) versions.increment(routerVersion);
                String country = _context.commSystem().getCountry(key);
                if (country != null) countries.increment(country);
                transportCount[classifyTransports(ri)]++;
            }
        } else if (!showStats) {
            countFullRouterStats(routers, versions, countries, transportCount);
        }
        if (showStats) {
            renderRoutersToWriter(routers, out, isLocal, page, pageSize);
            paginate(buf, new StringBuilder("&amp;f=").append(mode), page, pageSize, hasNextPage, routers.size() - 1);
            out.append(buf);
            buf.setLength(0);
        } else {
            renderSummaryTables(buf, versions, countries, transportCount, routers);
            out.append(buf);
            buf.setLength(0);
        }
        out.flush();
    }

    /**
     * Renders a list of RouterInfo objects to the given Writer.
     * Uses parallel rendering for small sets (&lt; 300), streams for large sets.
     */
    public void renderRoutersToWriter(Collection<RouterInfo> routers, Writer out, boolean isLocal, int page, int pageSize) throws IOException {
        if (routers == null || routers.isEmpty()) return;

        List<RouterInfo> list = new ArrayList<>(routers);
        int total = list.size();
        int fromIndex = Math.min(page * pageSize, total - 1);
        int toIndex = Math.min(fromIndex + pageSize, total);

        List<RouterInfo> pageList = list.subList(fromIndex, toIndex);

        int maxBeforeStreaming = enableReverseLookups() ? 100 : 200;

        if (pageList.size() <= maxBeforeStreaming) {
            out.write(renderRouterInfosInParallel(pageList, isLocal));
        } else {
            Map<String, String> rdnsLookups = enableReverseLookups()
                ? precacheReverseDNSLookups(pageList)
                : Collections.emptyMap();

            for (RouterInfo ri : pageList) {
                StringBuilder sb = new StringBuilder();
                if (rdnsLookups.isEmpty()) {
                    renderRouterInfo(sb, ri, isLocal);
                } else {
                    renderRouterInfo(sb, ri, isLocal, rdnsLookups);
                }
                out.append(sb);
                out.flush();
            }
        }
    }

    /**
     *  Renders the summary tables (versions, bandwidth, transports, countries).
     */
    private void renderSummaryTables(StringBuilder buf, ObjectCounterUnsafe<String> versions,
                                     ObjectCounterUnsafe<String> countries, int[] transportCount,
                                     Set<RouterInfo> routers) {
        buf.append("<table id=netdboverview width=100%>\n<tr><th colspan=3>")
           .append(_t("Network Database Router Statistics"))
           .append("</th></tr>\n<tr><td style=vertical-align:top>");
        renderVersionsTable(buf, versions);
        buf.append("</td><td style=vertical-align:top>\n");
        renderBandwidthTiers(buf);
        renderCongestionCaps(buf);
        renderTransportsTable(buf, transportCount);
        buf.append("</td><td style=vertical-align:top>\n");
        renderCountryTable(buf, countries, routers);
        buf.append("</td>\n</tr>\n</table>\n");
    }

    /**
     *  Renders the router versions table.
     */
    private void renderVersionsTable(StringBuilder buf, ObjectCounterUnsafe<String> versions) {
        List<String> versionList = new ArrayList<>(versions.objects());
        if (versionList.isEmpty()) return;
        Collections.sort(versionList, Collections.reverseOrder(new VersionComparator()));
        buf.append("<table id=netdbversions>\n<thead><tr><th data-sort-default data-sort-direction=descending>").append(_t("Version"))
           .append("</th><th>")
           .append(_t("Count")).append("</th></tr></thead>\n<tbody>");
        for (String v : versionList) {
            int count = versions.count(v);
            String sanitized = DataHelper.stripHTML(v);
            buf.append("<tr><td><span class=version><a href=\"/netdb?v=").append(sanitized).append("\">")
               .append(sanitized).append("</a></span></td><td>").append(count).append("</td></tr>\n");
        }
        buf.append("</tbody>\n</table>\n");
    }

    private static final char[] BW_CAPS = {
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW12,
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW32,
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW64,
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW128,
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW256,
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW512,
        FloodfillNetworkDatabaseFacade.CAPABILITY_BW_UNLIMITED
    };
    private static final String[] BW_LABELS = {
        "K", "L", "M", "N", "O", "P", "X"
    };
    private static final String[] BW_DESCRIPTIONS = {
        _x("Under 12 KB/s"), "12 - 48 KB/s", "49 - 65 KB/s",
        "66 - 130 KB/s", "131 - 261 KB/s", "262 - 2047 KB/s", _x("Over 2048 KB/s")
    };

    /**
     *  Renders the bandwidth tiers table.
     */
    private void renderBandwidthTiers(StringBuilder buf) {
        String showAll = _t("Show all routers with this capability in the NetDb");
        buf.append("<table id=netdbtiers>\n<thead><tr><th data-sort-default data-sort-direction=ascending>")
           .append(_t("Bandwidth Tier")).append("</th><th>")
           .append(_t("Count")).append("</th></tr></thead>\n<tbody>");
        Map<Character, Set<Hash>> capsPeers = new HashMap<>();
        for (char cap : BW_CAPS) {
            Set<Hash> peers = _context.peerManager().getPeersByCapability(cap);
            capsPeers.put(cap, peers != null ? peers : Collections.emptySet());
        }
        for (int i = 0; i < BW_CAPS.length; i++) {
            char cap = BW_CAPS[i];
            Set<Hash> peers = capsPeers.get(cap);
            if (!peers.isEmpty()) {
                buf.append("<tr><td><a href=\"/netdb?caps=").append(BW_LABELS[i])
                   .append("\" title=\"").append(showAll).append("\"><b>")
                   .append(BW_LABELS[i]).append("</b></a>")
                   .append(_t(BW_DESCRIPTIONS[i])).append("</td><td>")
                   .append(peers.size()).append("</td></tr>\n");
            }
        }
        buf.append("</tbody>\n</table>\n");
    }

    /**
     *  Renders the congestion capabilities table.
     */
    private void renderCongestionCaps(StringBuilder buf) {
        String showAll = _t("Show all routers with this capability in the NetDb");
        Set<Hash> moderate = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_MODERATE);
        Set<Hash> severe = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_CONGESTION_SEVERE);
        Set<Hash> noTunnels = _context.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_NO_TUNNELS);
        if ((moderate == null || moderate.isEmpty()) &&
            (severe == null || severe.isEmpty()) &&
            (noTunnels == null || noTunnels.isEmpty()))
            return;
        buf.append("<table id=netdbcongestion>\n<thead><tr><th data-sort-default data-sort-direction=ascending>")
           .append(_t("Congestion Cap")).append("</th><th>")
           .append(_t("Count")).append("</th></tr></thead>\n<tbody>");
        if (moderate != null && !moderate.isEmpty()) {
            buf.append("<tr><td><a class=isD href=\"/netdb?caps=D\" title=\"").append(showAll).append("\"><b>D</b></a>")
               .append(_t("Medium congestion / low performance")).append("</td><td>")
               .append(moderate.size()).append("</td></tr>\n");
        }
        if (severe != null && !severe.isEmpty()) {
            buf.append("<tr><td><a class=isE href=\"/netdb?caps=E\" title=\"").append(showAll).append("\"><b>E</b></a>")
               .append(_t("High congestion")).append("</td><td>")
               .append(severe.size()).append("</td></tr>\n");
        }
        if (noTunnels != null && !noTunnels.isEmpty()) {
            buf.append("<tr><td><a class=isG href=\"/netdb?caps=G\" title=\"").append(showAll).append("\"><b>G</b></a>")
               .append(_t("Rejecting all tunnel requests")).append("</td><td>")
               .append(noTunnels.size()).append("</td></tr>\n");
        }
        buf.append("</tbody>\n</table>\n");
    }

    /**
     *  Renders the transports table.
     */
    private void renderTransportsTable(StringBuilder buf, int[] transportCount) {
        buf.append("<table id=netdbtransports>\n<thead><tr><th data-sort-default data-sort-direction=ascending>")
           .append(_t("Transports")).append("</th><th>")
           .append(_t("Count")).append("</th></tr></thead>\n<tbody>");
        for (int i = 0; i < TNAMES.length; i++) {
            int count = transportCount[i];
            if (count > 0) {
                buf.append("<tr><td>").append(_t(TNAMES[i])).append("</td><td>").append(count).append("</td></tr>\n");
            }
        }
        buf.append("</tbody>\n</table>\n");
    }

    /**
     *  Renders the country list table.
     */
    private void renderCountryTable(StringBuilder buf, ObjectCounterUnsafe<String> countries, Set<RouterInfo> routers) {
        List<String> countryList = new ArrayList<>(countries.objects());
        buf.append("<table id=netdbcountrylist>\n<thead><tr>")
           .append("<th data-sort-direction=ascending>").append(_t("Country")).append("</th>")
           .append("<th class=countX>").append(_t("X Tier")).append("</th>")
           .append("<th class=countFF>").append(_t("Floodfills")).append("</th>")
           .append("<th class=countCC data-sort-default>").append(_t("Total")).append("</th>")
           .append("</tr></thead>\n");
        if (!countryList.isEmpty()) {
            Collections.sort(countryList, new CountryComparator());
            buf.append("<tbody id=cclist>\n");
            for (String country : countryList) {
                int totalCount = Math.max(countries.count(country) - 1, 0);
                buf.append("<tr><td><a href=\"/netdb?c=").append(country).append("\">")
                   .append("<img width=20 height=15 alt=\"").append(country.toUpperCase(Locale.US)).append("\"")
                   .append(" src=\"/flags.jsp?c=").append(country).append("\">")
                   .append(getTranslatedCountry(country).replace("xx", _t("Unknown"))).append("</a></td>")
                   .append("<td class=countX><a href=\"/netdb?caps=X&amp;cc=").append(country).append("\">")
                   .append(countXTierInCountry(routers, country)).append("</a></td>")
                   .append("<td class=countFF><a href=\"/netdb?caps=f&amp;cc=").append(country).append("\">")
                   .append(countFloodfillsInCountry(routers, country)).append("</a></td>")
                   .append("<td class=countCC>").append(totalCount).append("</td></tr>\n");
            }
            buf.append("</tbody>\n</table>\n");
        } else {
            buf.append("<tbody><tr><td colspan=2>").append(_t("Initializing")).append("&hellip;</td></tr></tbody></table>\n");
        }
    }

    /**
     *  Counts full router stats for summary mode.
     */
    private void countFullRouterStats(Set<RouterInfo> routers, ObjectCounterUnsafe<String> versions,
                                     ObjectCounterUnsafe<String> countries, int[] transportCount) {
        for (RouterInfo ri : routers) {
            Hash key = ri.getIdentity().getHash();
            String routerVersion = ri.getOption("router.version");
            if (routerVersion != null) versions.increment(routerVersion);
            String country = _context.commSystem().getCountry(key);
            if (country != null) countries.increment(country);
            transportCount[classifyTransports(ri)]++;
        }
    }

    /**
     *  Gets translated country name from code.
     *  @since 0.9.9
     */
    private String getTranslatedCountry(String code) {
        String name = _context.commSystem().getCountryName(code);
        return Translate.getString(name, _context, Messages.COUNTRY_BUNDLE_NAME);
    }

    /**
     *  Comparator for countries by translated name.
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
     *  Comparator for countries by count (desc) then name (asc).
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
     *  Comparator for router addresses by transport then host.
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

    private static final Map<Character, String> CAP_REPLACEMENTS;
    static {
        CAP_REPLACEMENTS = new HashMap<>();
        CAP_REPLACEMENTS.put('f', "<a href=\"/netdb?caps=f\"><span class=ff>F</span></a>");
        CAP_REPLACEMENTS.put('R', "<a href=\"/netdb?caps=R\"><span class=reachable>R</span></a>");
        CAP_REPLACEMENTS.put('U', "<a href=\"/netdb?caps=U\"><span class=unreachable>U</span></a>");
        CAP_REPLACEMENTS.put('K', "<a href=\"/netdb?caps=K\"><span class=tier>K</span></a>");
        CAP_REPLACEMENTS.put('L', "<a href=\"/netdb?caps=L\"><span class=tier>L</span></a>");
        CAP_REPLACEMENTS.put('M', "<a href=\"/netdb?caps=M\"><span class=tier>M</span></a>");
        CAP_REPLACEMENTS.put('N', "<a href=\"/netdb?caps=N\"><span class=tier>N</span></a>");
        CAP_REPLACEMENTS.put('O', "<a href=\"/netdb?caps=O\"><span class=tier>O</span></a>");
        CAP_REPLACEMENTS.put('P', "<a href=\"/netdb?caps=P\"><span class=tier>P</span></a>");
        CAP_REPLACEMENTS.put('X', "<a href=\"/netdb?caps=X\"><span class=tier>X</span></a>");
    }

    /**
     * Internal class to hold reverse DNS cache entries with expiration time.
     */
    private static class CacheEntry {
        String value;
        long expiresAt;
        CacheEntry(String value, long ttlMillis) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /**
     * Reverse DNS cache with TTL (24 hours).
     * Small cache since we rely on file-backed rdnsCache in CommSystemFacadeImpl
     */
    private final LHMCache<String, CacheEntry> reverseLookupCache = new LHMCache<>(50);
    private static final long TTL_24_HOURS = 24 * 60 * 60 * 1000; // 24 hours

    /**
     * Gets a value from cache if it's still valid.
     */
    private String getCachedReverseDNS(String ip) {
        CacheEntry entry = reverseLookupCache.get(ip);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        } else {
            reverseLookupCache.remove(ip); // Clean up
            return null;
        }
    }

    /**
     * Puts a new value into the cache with TTL.
     */
    private void putCachedReverseDNS(String ip, String hostname) {
        if (hostname != null && !hostname.equals(ip) && !hostname.equals("unknown")) {
            reverseLookupCache.put(ip, new CacheEntry(hostname, TTL_24_HOURS));
        }
    }

    /**
     *  Renders a single RouterInfo as HTML.
     *
     *  @param buf output buffer
     *  @param routerInfo the router to render
     *  @param isLocalRouter true if this is the local router
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo routerInfo, boolean isLocalRouter) {
        renderRouterInfo(buf, routerInfo, isLocalRouter, null);
    }

    /**
     *  Renders a single RouterInfo as HTML with optional pre-resolved reverse DNS lookups.
     *
     *  @param buf output buffer
     *  @param routerInfo the router to render
     *  @param isLocalRouter true if this is the local router
     *  @param rdnsLookups map of IP to hostname for reverse DNS, may be null
     */
    private void renderRouterInfo(StringBuilder buf, RouterInfo routerInfo, boolean isLocalRouter, Map<String, String> rdnsLookups) {
        RouterIdentity identity = routerInfo.getIdentity();
        Hash routerHash = routerInfo.getHash();
        String routerHashBase64 = routerHash.toBase64();
        String family = routerInfo.getOption("family");
        String profileTier = getPeerProfileTier(routerHash, true);
        String profileTierClass = getPeerProfileTier(routerHash, false);
        String caps = DataHelper.stripHTML(routerInfo.getCapabilities());
        boolean hasD = false, hasE = false, hasG = false, isReachable = false, isUnreachable = false, isFF = false;
        for (int i = 0; i < caps.length(); i++) {
            char c = caps.charAt(i);
            if (c == 'f') isFF = true;
            if (c == 'D') hasD = true;
            else if (c == 'E') hasE = true;
            else if (c == 'G') hasG = true;
            else if (c == 'R') isReachable = true;
            else if (c == 'U') isUnreachable = true;
        }
        buf.append("<table class=\"netdbentry lazy\">\n<thead><tr>");
        if (isLocalRouter) {
            buf.append("<th id=us><b id=our-info>").append(_t("Our info")).append(":</b><th><code>").append(routerHashBase64)
               .append("</code></th><th id=netdb_ourinfo>");
        } else {
            buf.append("<th class=\"router").append(isFF ? " isFF" : "").append(' ').append(profileTierClass).append("\" title=\"")
               .append(isFF ? _t("Floodfill") : _t("Router")).append(" - ").append(profileTier).append("\"><b>")
               .append(_t("Router")).append(":</b><th><code>").append(routerHashBase64).append("</code></th><th>");
        }
        if (_context.banlist().isBanlisted(routerHash)) {
            buf.append("<a class=banlisted href=\"/profiles?f=3\" title=\"").append(_t("Router is banlisted")).append("\">Banned</a> ");
        }
        boolean isJavaI2PVariant = false;
        boolean isI2PdVariant = false;
        for (RouterAddress address : routerInfo.getAddresses()) {
            String transportStyle = address.getTransportStyle();
            int cost = address.getCost();
            if ((transportStyle.startsWith("SSU") && cost == 5) || (transportStyle.startsWith("NTCP") && cost == 14) && !isUnreachable(routerInfo)) {
                isJavaI2PVariant = true;
                break;
            } else if ((transportStyle.startsWith("SSU") && cost == 3) || (transportStyle.startsWith("NTCP") && cost == 8) && !isUnreachable(routerInfo)) {
                isI2PdVariant = true;
            }
        }
        if (isJavaI2PVariant) {
            buf.append("<span class=javai2p title=\"").append(_t("Java I2P variant")).append("\"></span> ");
        } else if (isI2PdVariant) {
            buf.append("<span class=i2pd title=\"").append(_t("I2Pd variant")).append("\"></span> ");
        }
        if (identity.isCompressible()) {
            buf.append("<span class=compressible title=\"").append(_t("RouterInfo is compressible")).append("\"></span> ");
        }
        StringBuilder processedCaps = new StringBuilder(caps.length() * 2);
        for (int i = 0; i < caps.length(); i++) {
            char c = caps.charAt(i);
            processedCaps.append(CAP_REPLACEMENTS.getOrDefault(c, String.valueOf(c)));
        }
        String processedCapsStr = processedCaps.toString();
        if (hasD || hasE || hasG) {
            if (hasD && processedCapsStr.contains("D")) {
                processedCapsStr = processedCapsStr.replace("D", "");
            } else if (hasE && processedCapsStr.contains("E")) {
                processedCapsStr = processedCapsStr.replace("E", "");
            } else if (hasG && processedCapsStr.contains("G")) {
                processedCapsStr = processedCapsStr.replace("G", "");
            }
            if (hasD) {
                processedCapsStr = processedCapsStr
                    .replace("class=\"tier\"", "class=\"tier isD\"")
                    .replace("class=tier", "class=\"tier isD\"");
                if (isReachable) {
                    processedCapsStr = processedCapsStr
                        .replaceAll("href=\"/netdb\\?caps=K", "href=\"/netdb?caps=KRD")
                        .replaceAll("href=\"/netdb\\?caps=L", "href=\"/netdb?caps=LRD")
                        .replaceAll("href=\"/netdb\\?caps=M", "href=\"/netdb?caps=MRD")
                        .replaceAll("href=\"/netdb\\?caps=N", "href=\"/netdb?caps=NRD")
                        .replaceAll("href=\"/netdb\\?caps=O", "href=\"/netdb?caps=ORD")
                        .replaceAll("href=\"/netdb\\?caps=P", "href=\"/netdb?caps=PRD")
                        .replaceAll("href=\"/netdb\\?caps=X", "href=\"/netdb?caps=XRD");
                } else if (isUnreachable) {
                    processedCapsStr = processedCapsStr
                        .replaceAll("href=\"/netdb\\?caps=K", "href=\"/netdb?caps=KUD")
                        .replaceAll("href=\"/netdb\\?caps=L", "href=\"/netdb?caps=LUD")
                        .replaceAll("href=\"/netdb\\?caps=M", "href=\"/netdb?caps=MUD")
                        .replaceAll("href=\"/netdb\\?caps=N", "href=\"/netdb?caps=NUD")
                        .replaceAll("href=\"/netdb\\?caps=O", "href=\"/netdb?caps=OUD")
                        .replaceAll("href=\"/netdb\\?caps=P", "href=\"/netdb?caps=PUD")
                        .replaceAll("href=\"/netdb\\?caps=X", "href=\"/netdb?caps=XUD");
                }
            } else if (hasE) {
                processedCapsStr = processedCapsStr
                    .replace("class=\"tier\"", "class=\"tier isE\"")
                    .replace("class=tier", "class=\"tier isE\"");
                if (isReachable) {
                    processedCapsStr = processedCapsStr
                        .replaceAll("href=\"/netdb\\?caps=K", "href=\"/netdb?caps=KRE")
                        .replaceAll("href=\"/netdb\\?caps=L", "href=\"/netdb?caps=LRE")
                        .replaceAll("href=\"/netdb\\?caps=M", "href=\"/netdb?caps=MRE")
                        .replaceAll("href=\"/netdb\\?caps=N", "href=\"/netdb?caps=NRE")
                        .replaceAll("href=\"/netdb\\?caps=O", "href=\"/netdb?caps=ORE")
                        .replaceAll("href=\"/netdb\\?caps=P", "href=\"/netdb?caps=PRE")
                        .replaceAll("href=\"/netdb\\?caps=X", "href=\"/netdb?caps=XRE");
                } else if (isUnreachable) {
                    processedCapsStr = processedCapsStr
                        .replaceAll("href=\"/netdb\\?caps=K", "href=\"/netdb?caps=KUE")
                        .replaceAll("href=\"/netdb\\?caps=L", "href=\"/netdb?caps=LUE")
                        .replaceAll("href=\"/netdb\\?caps=M", "href=\"/netdb?caps=MUE")
                        .replaceAll("href=\"/netdb\\?caps=N", "href=\"/netdb?caps=NUE")
                        .replaceAll("href=\"/netdb\\?caps=O", "href=\"/netdb?caps=OUE")
                        .replaceAll("href=\"/netdb\\?caps=P", "href=\"/netdb?caps=PUE")
                        .replaceAll("href=\"/netdb\\?caps=X", "href=\"/netdb?caps=XUE");
                }
            } else if (hasG) {
                processedCapsStr = processedCapsStr
                    .replace("class=\"tier\"", "class=\"tier isG\"")
                    .replace("class=tier", "class=\"tier isG\"");
                if (isReachable) {
                    processedCapsStr = processedCapsStr
                        .replaceAll("href=\"/netdb\\?caps=K", "href=\"/netdb?caps=KRG")
                        .replaceAll("href=\"/netdb\\?caps=L", "href=\"/netdb?caps=LRG")
                        .replaceAll("href=\"/netdb\\?caps=M", "href=\"/netdb?caps=MRG")
                        .replaceAll("href=\"/netdb\\?caps=N", "href=\"/netdb?caps=NRG")
                        .replaceAll("href=\"/netdb\\?caps=O", "href=\"/netdb?caps=ORG")
                        .replaceAll("href=\"/netdb\\?caps=P", "href=\"/netdb?caps=PRG")
                        .replaceAll("href=\"/netdb\\?caps=X", "href=\"/netdb?caps=XRG");
                } else if (isUnreachable) {
                    processedCapsStr = processedCapsStr
                        .replaceAll("href=\"/netdb\\?caps=K", "href=\"/netdb?caps=KUG")
                        .replaceAll("href=\"/netdb\\?caps=L", "href=\"/netdb?caps=LUG")
                        .replaceAll("href=\"/netdb\\?caps=M", "href=\"/netdb?caps=MUG")
                        .replaceAll("href=\"/netdb\\?caps=N", "href=\"/netdb?caps=NUG")
                        .replaceAll("href=\"/netdb\\?caps=O", "href=\"/netdb?caps=OUG")
                        .replaceAll("href=\"/netdb\\?caps=P", "href=\"/netdb?caps=PUG")
                        .replaceAll("href=\"/netdb\\?caps=X", "href=\"/netdb?caps=XUG");
                }
            }
        }
        String tooltip = "\" title=\"" + _t("Show all routers with this capability in the NetDb") + "\"><span";
        processedCapsStr = processedCapsStr.replace("\"><span", tooltip);
        buf.append(processedCapsStr);
        String version = DataHelper.stripHTML(routerInfo.getVersion());
        buf.append("&nbsp;<a href=\"/netdb?v=").append(version).append("\">")
           .append("<span class=version title=\"").append(_t("Show all routers with this version in the NetDb"))
           .append("\">").append(version).append("</span></a>");
        if (!isLocalRouter) {
            buf.append("<span class=netdb_header>");
            if (family != null) {
                FamilyKeyCrypto familyKeyCrypto = _context.router().getFamilyKeyCrypto();
                buf.append("<a class=\"familysearch");
                if (familyKeyCrypto != null) {
                    buf.append(" verified");
                }
                buf.append("\" href=\"/netdb?fam=").append(DataHelper.stripHTML(family))
                   .append("\" title=\"").append(_t("Show all members of the {0} family in NetDb", DataHelper.stripHTML(family)))
                   .append("\">").append(_t("Family")).append("</a>");
            }
            PeerProfile profile = _context.profileOrganizer().getProfileNonblocking(routerHash);
            if (profile != null) {
                buf.append("<a class=viewprofile href=\"/viewprofile?peer=").append(routerHashBase64).append("\" title=\"")
                   .append(_t("View profile")).append("\">").append(_t("Profile")).append("</a>");
            }
            buf.append("<a class=configpeer href=\"/configpeer?peer=").append(routerHashBase64)
               .append("\" title=\"").append(_t("Configure peer"))
               .append("\">").append(_t("Edit")).append("</a>")
               .append(_context.commSystem().renderPeerFlag(routerHash)).append("</span>");
        } else {
            long memoryUsedBytes = (long) _context.statManager().getRate("router.memoryUsed").getRate(RateConstants.ONE_MINUTE).getAvgOrLifetimeAvg();
            long memoryUsedMegabytes = memoryUsedBytes / (1024 * 1024);
            buf.append("&nbsp;<span id=netdb_ram><b>").append(_t("Memory usage")).append(":</b> ").append(memoryUsedMegabytes).append("M</span>");
        }
        buf.append("</th></tr></thead>\n<tbody>\n<tr>");
        now = _context.clock().now();
        long published = routerInfo.getPublished();
        long age = now - published;
        if (isLocalRouter && _context.router().isHidden()) {
            buf.append("<td><b>").append(_t("Hidden")).append(", ").append(_t("Updated")).append(":</b></td>")
               .append("<td><span class=netdb_info>")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>&nbsp;&nbsp;");
        } else if (age > 0) {
            buf.append("<td><b>").append(_t("Published")).append(":</b></td><td><span class=netdb_info>")
               .append(_t("{0} ago", DataHelper.formatDuration2(age)))
               .append("</span>&nbsp;&nbsp;");
            String primaryAddress = net.i2p.util.Addresses.toString(CommSystemFacadeImpl.getValidIP(routerInfo));
            String capsStr = processedCapsStr;
            boolean isUnreachableFlag = capsStr.contains("U") || capsStr.contains("H");
            boolean enableWhoisLookups = _context.getBooleanProperty("routerconsole.enableWhoisLookups");
            if (enableReverseLookups() && _context.router().getUptime() > 30 * 1000 && !isUnreachableFlag && primaryAddress != null) {
                String canonicalHostname = null;
                if (rdnsLookups != null) {
                    canonicalHostname = rdnsLookups.get(primaryAddress);
                }
                if (canonicalHostname == null) {
                    canonicalHostname = getCachedReverseDNS(primaryAddress);
                }
                if (canonicalHostname == null) {
                    canonicalHostname = _context.commSystem().getCanonicalHostName(primaryAddress);
                    if (canonicalHostname != null && !canonicalHostname.equals(primaryAddress) && !canonicalHostname.equals("unknown")) {
                        putCachedReverseDNS(primaryAddress, canonicalHostname);
                    }
                }
                if (canonicalHostname != null && !canonicalHostname.equals(primaryAddress) && !canonicalHostname.equals("unknown")) {
                    buf.append("<span class=netdb_info><b>").append(_t("Hostname"));
                    if (enableWhoisLookups) {
                        buf.append(" / ").append(_t("Whois"));
                    }
                    buf.append(":</b> <span class=rdns>").append(canonicalHostname).append("</span></span>&nbsp;&nbsp;");
                }
            } else if (_context.router().getUptime() > 30 * 1000 && (isUnreachableFlag || primaryAddress == null)) {
                byte[] ipAddress = TransportImpl.getIP(routerHash);
                if (ipAddress != null) {
                    _context.commSystem().queueLookup(ipAddress);
                    String directAddressString = Addresses.toString(ipAddress);
                    if (enableReverseLookups()) {
                        String canonicalHostname = null;
                        if (rdnsLookups != null) {
                            canonicalHostname = rdnsLookups.get(primaryAddress);
                        }
                        if (canonicalHostname == null) {
                            canonicalHostname = getCachedReverseDNS(primaryAddress);
                        }
                        if (canonicalHostname == null) {
                            canonicalHostname = _context.commSystem().getCanonicalHostName(primaryAddress);
                            if (canonicalHostname != null && !canonicalHostname.equals(primaryAddress) && !canonicalHostname.equals("unknown")) {
                                putCachedReverseDNS(primaryAddress, canonicalHostname);
                            }
                        }
                        if (canonicalHostname != null && !canonicalHostname.equals(directAddressString) && !canonicalHostname.equals("unknown")) {
                            buf.append("<span class=netdb_info><b>").append(_t("Hostname"));
                            if (enableWhoisLookups) {
                                buf.append(" / ").append(_t("Whois"));
                            }
                            buf.append(" (").append(_t("direct")).append("):</b> <span class=rdns>")
                               .append(canonicalHostname).append(" (").append(directAddressString).append(")</span></span>&nbsp;&nbsp;");
                        } else {
                            buf.append("<span class=netdb_info><b>").append(_t("IP Address")).append(" (")
                               .append(_t("direct")).append("):</b> <span class=rdns>").append(directAddressString)
                               .append("</span></span>&nbsp;&nbsp;");
                        }
                    } else {
                        buf.append("<span class=netdb_info><b>").append(_t("IP Address")).append(" (")
                           .append(_t("direct")).append("):</b> <span class=rdns>").append(directAddressString)
                           .append("</span></span>&nbsp;&nbsp;");
                    }
                }
            }
        } else {
            buf.append("<td><b>").append(_t("Published")).append("</td><td>:</b> in ")
               .append(DataHelper.formatDuration2(0 - age)).append("<span class=netdb_info>???</span>&nbsp;&nbsp;");
        }
        if (family != null) {
            FamilyKeyCrypto familyKeyCrypto = _context.router().getFamilyKeyCrypto();
            buf.append("<span class=\"netdb_family\"><b>").append(_t("Family"))
               .append(":</b> <span class=familyname>").append(DataHelper.stripHTML(family));
            if (familyKeyCrypto != null) {
                buf.append(" <span class=verified title=\"")
                   .append(_t("Verified family (signing certificate is installed and valid)"))
                   .append("\">[").append(_t("Verified")).append("]</span>");
            }
            buf.append("</span></span>");
        }
        buf.append("</td><td class=keys>");
        buf.append("<span class=signingkey title=\"")
           .append(_t("Show all routers with this signature type in the NetDb"))
           .append("\"><a class=keysearch href=\"/netdb?type=")
           .append(identity.getSigningPublicKey().getType().toString())
           .append("\">").append(identity.getSigningPublicKey().getType().toString())
           .append("</a></span>&nbsp;<span class=\"signingkey encryption\" title=\"")
           .append(_t("Show all routers with this encryption type in the NetDb"))
           .append("\"><a class=keysearch href=\"/netdb?etype=")
           .append(identity.getPublicKey().getType().toString())
           .append("\">").append(identity.getPublicKey().getType().toString())
           .append("</a></span></td></tr>\n");
        if (!isUnreachable) {
            Collection<RouterAddress> addresses = routerInfo.getAddresses();
            if (addresses != null && !addresses.isEmpty()) {
                byte[] ipAddress = TransportImpl.getIP(routerHash);
                if (ipAddress != null) {
                    _context.commSystem().queueLookup(ipAddress);
                }

                List<RouterAddress> listAddresses = new ArrayList<>(addresses);
                if (listAddresses.size() > 1) {
                    listAddresses.sort(new RAComparator());
                }

                boolean hasSSU = false;
                boolean hasNTCP = false;
                int introducerTagCount = 0;

                // Flag to determine if any address has displayable content
                boolean hasDisplayableAddress = false;
                StringBuilder addressList = new StringBuilder();

                for (RouterAddress address : listAddresses) {
                    String transportStyle = address.getTransportStyle();
                    if (transportStyle.startsWith("SSU")) hasSSU = true;
                    if (transportStyle.startsWith("NTCP")) hasNTCP = true;

                    Map<Object, Object> optionsMap = address.getOptionsMap();
                    StringBuilder details = new StringBuilder();
                    String host = null;
                    String port = null;
                    String mtu = null;

                    for (Map.Entry<Object, Object> entry : optionsMap.entrySet()) {
                        String key = (String) entry.getKey();
                        String value = (String) entry.getValue();
                        if (key.equalsIgnoreCase("host")) { host = value; }
                        else if (key.equalsIgnoreCase("port")) { port = value; }
                        else if (key.equalsIgnoreCase("mtu")) { mtu = value; }
                    }

                    if (host != null || port != null) {
                        hasDisplayableAddress = true;

                        if (host != null) {
                            details.append("<span class=\"netdb_info host\">")
                                   .append("<a title=\"")
                                   .append(_t("Show all routers with this address in the NetDb"))
                                   .append("\" ");
                            if (host.contains(":")) {
                                details.append("href=\"/netdb?ipv6=")
                                       .append(host.length() > 8 ? host.substring(0, 4) : host);
                            } else {
                                details.append("href=\"/netdb?ip=").append(host);
                            }
                            details.append("\">").append(host).append("</a></span><span class=colon>:</span>");
                        }

                        if (port != null) {
                            details.append("<span class=\"netdb_info port\">")
                                   .append("<a title=\"")
                                   .append(_t("Show all routers with this port in the NetDb"))
                                   .append("\" href=\"/netdb?port=").append(port)
                                   .append("\">").append(port).append("</a></span>");
                        }

                        if (mtu != null) {
                            details.append("&nbsp;<span class=\"netdb_info mtu\" hidden><span title=\"")
                                   .append(_t("Maximum Transmission Unit"))
                                   .append("\">MTU</span>")
                                   .append("<a title=\"")
                                   .append(_t("Show all routers with this MTU in the NetDb"))
                                   .append("\" href=\"/netdb?mtu=").append(mtu)
                                   .append("\">").append(mtu).append("</a></span>");
                        }

                        if (details.length() > 0) {
                            addressList.append("<li><b class=netdb_transport>")
                                       .append(transportStyle.replace("2", ""))
                                       .append("</b> ")
                                       .append(details)
                                       .append("</li>");
                        }
                    }
                }

                // Only append the row if at least one address has displayable info
                if (hasDisplayableAddress) {
                    buf.append("<tr><td><b>")
                       .append(_t("Addresses"))
                       .append(":</b></td><td colspan=2 class=netdb_addresses><ul>")
                       .append(addressList)
                       .append("</ul></td></tr>");
                }
            }
        } else {
            buf.append("<tr><td><b>").append(_t("Introducers")).append(":</b></td><td colspan=2 class=netdb_introducers><ul>");

            boolean hasIntroducer = false;
            Collection<RouterAddress> addresses = routerInfo.getAddresses();
            List<RouterAddress> listAddresses = new ArrayList<>(addresses);
            for (RouterAddress address : listAddresses) {
                if (address == null) continue;
                String transportStyle = address.getTransportStyle();
                Map<Object, Object> optionsMap = address.getOptionsMap();
                for (Map.Entry<Object, Object> entry : optionsMap.entrySet()) {
                    String key = (String) entry.getKey();
                    String value = (String) entry.getValue();
                    if (key != null && key.startsWith("ih")) {
                        hasIntroducer = true;
                        String ih = (String) entry.getValue();
                        Hash ihost = ConvertToHash.getHash(ih);
                        buf.append(_context.commSystem().renderPeerHTML(ihost, true));
                    }
                }
            }
            buf.append("</ul></td></tr>\n");
        }
        List<String> statsLines = new ArrayList<>();
        Map<Object, Object> optionsMap = routerInfo.getOptionsMap();
        String networkId = "2";
        if (isLocalRouter) {
            for (Map.Entry<Object, Object> entry : optionsMap.entrySet()) {
                String key = (String) entry.getKey();
                if ("netId".equals(key)) {
                    networkId = DataHelper.stripHTML((String) entry.getValue());
                } else if ("knownLeaseSets".equals(key)) {
                    continue;
                }
            }
        }
        Set<String> skipKeys = new HashSet<>(Arrays.asList("netId", "knownLeaseSets", "knownRouters", "family", "version"));
        List<String> ignoredStats = Arrays.asList(
            "family.key", "family.sig",
            "tunnel.buildExploratoryExpire.60m", "tunnel.buildExploratoryReject.60m", "tunnel.buildExploratorySuccess.60m",
            "tunnel.buildClientExpire.60m", "tunnel.buildClientReject.60m", "tunnel.buildClientSuccess.60m",
            "tunnel.participatingTunnels.60m", "tunnel.participatingTunnels.60s",
            "stat_bandwidthSendBps.60m", "stat_bandwidthReceiveBps.60m"
        );
        for (Map.Entry<Object, Object> entry : optionsMap.entrySet()) {
            String key = (String) entry.getKey();
            String lowerKey = key.toLowerCase(Locale.US);
            if (skipKeys.contains(key) ||
                lowerKey.contains("caps") ||
                lowerKey.contains("version") ||
                lowerKey.startsWith("family") ||
                lowerKey.startsWith("tunnel.") ||
                lowerKey.startsWith("stat_") ||
                ignoredStats.contains(key)) {
                continue;
            }
            String value = (String) entry.getValue();
            String label = _t(DataHelper.stripHTML(key).replace("netdb.", "").replace("known", ""));
            String formattedValue = DataHelper.stripHTML(value).replace(";", " <span class=\"bullet\">&bull;</span> ");
            statsLines.add("<li><b>" + label + ":</b> " + formattedValue + "</li>");
        }
        if (isLocalRouter) {
            statsLines.add("<li><b>" + _t("Network Id") + ":</b> " + networkId + "</li>");
        } else {
            PeerProfile profile = _context.profileOrganizer().getProfileNonblocking(routerHash);
            if (profile != null) {
                long firstHeard = profile.getFirstHeardAbout();
                long lastHeard = profile.getLastHeardAbout();
                long lastFrom = profile.getLastHeardFrom();
                if (firstHeard > 0) {
                    statsLines.add("<li><b>" + _t("First heard about") + ":</b> " +
                        _t("{0} ago", DataHelper.formatDuration2(now - firstHeard)) + "</li>");
                }
                if (lastHeard > 0) {
                    statsLines.add("<li><b>" + _t("Last heard about") + ":</b> " +
                        _t("{0} ago", DataHelper.formatDuration2(now - lastHeard)) + "</li>");
                }
                if (lastFrom > 0) {
                    statsLines.add("<li><b>" + _t("Last heard from") + ":</b> " +
                        _t("{0} ago", DataHelper.formatDuration2(now - lastFrom)) + "</li>");
                }
            }
        }
        if (!statsLines.isEmpty()) {
            buf.append("<tr><td><b>").append(isLocalRouter ? _t("Info") : _t("Stats"))
               .append(":</b></td><td colspan=2>\n<ul class=netdbStats>\n");
            for (String line : statsLines) {buf.append(line).append('\n');}
            buf.append("</ul></td></tr>\n");
        }
        buf.append("</tbody>\n</table>\n");
    }

    /**
     *  Renders multiple RouterInfo objects in parallel.
     *
     *  @param routerInfos collection of routers to render
     *  @param isLocalRouter true if rendering the local router
     *  @return concatenated HTML
     */
    public String renderRouterInfosInParallel(Collection<RouterInfo> routerInfos, boolean isLocalRouter) {
        if (routerInfos.size() > BATCH_SIZE) {
            // Fallback: render synchronously in small chunks to avoid OOM
            StringBuilder sb = new StringBuilder();
            List<RouterInfo> list = new ArrayList<>(routerInfos);
            for (int i = 0; i < list.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, list.size());
                for (int j = i; j < end; j++) {
                    renderRouterInfo(sb, list.get(j), isLocalRouter);
                }
            }
            return sb.toString();
        }

        int numCores = Math.max(SystemVersion.getCores(), 8);
        ExecutorService executor = Executors.newFixedThreadPool(numCores);
        int chunkSize = BATCH_SIZE;
        List<RouterInfo> list = new ArrayList<>(routerInfos);
        StringBuilder fullHtml = new StringBuilder();
        Object lock = new Object();
        for (int start = 0; start < list.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, list.size());
            List<RouterInfo> chunk = list.subList(start, end);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (RouterInfo routerInfo : chunk) {
                futures.add(
                    CompletableFuture.runAsync(() -> {
                        StringBuilder buffer = new StringBuilder();
                        renderRouterInfo(buffer, routerInfo, isLocalRouter);
                        synchronized (lock) {fullHtml.append(buffer);}
                    }, executor)
                );
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        executor.shutdown();
        return fullHtml.toString();
    }

    private static final int SSU = 1;
    private static final int SSUI = 2;
    private static final int NTCP = 4;
    private static final int IPV6 = 8;
    private static final String[] TNAMES = { _x("Hidden or starting up"), _x("SSU"), _x("SSU with Introducers"), "",
                                             _x("NTCP"), _x("NTCP &amp; SSU"), _x("NTCP &amp; SSU with Introducers"), "",
                                             "", _x("SSU [IPv6]"), _x("SSU [IPv6 Only] with Introducers"), _x("SSU [IPv6] with Introducers"),
                                             _x("NTCP [IPv6]"), _x("NTCP [IPv6] &amp; SSU"), _x("NTCP &amp; SSU with Introducers [IPv6 Only]"),
                                             _x("NTCP [IPv6] &amp; SSU with Introducers")
                                           };

    /**
     *  Classifies router transports into bit flags.
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
        if (rv == 3) {rv = 2;}
        else if (rv == 7) {rv = 6;}
        else if (rv == 8) {rv = 0;}
        return rv;
    }

    /**
     *  Converts IPv6 between compressed and expanded forms.
     *
     *  @param ip IPv6 address not ending with "::"
     *  @return alternative representation or null
     *  @since 0.9.57
     */
    private static String getAltIPv6(String ip) {
        if (ip.contains("::")) {
            byte[] bip = Addresses.getIPOnly(ip);
            if (bip != null) {return Addresses.toString(bip);}
        } else if (ip.contains(":0:")) {return Addresses.toCanonicalString(ip);}
        return null;
    }

    /**
     *  Gets the peer profile tier name.
     *
     *  @param peer the peer hash
     *  @param fullname if true, return full name; else CSS class
     *  @return the tier name or class
     */
    public String getPeerProfileTier(Hash peer, boolean fullname) {
        if (peer == null || _context.routerHash().equals(peer)) return (fullname ? _t("Local") : "isLocal");
        if (_organizer.isFast(peer)) return (fullname ? _t("Fast Tier") : "isFast");
        if (_organizer.isHighCapacity(peer)) return (fullname ? _t("High Capacity Tier") : "isHighCap");
        if (_organizer.isWellIntegrated(peer)) return (fullname ? _t("Integrated Tier") : "isIntegrated");
        return (fullname ? _t("Standard Tier") : "isStandard");
    }

    /** translate a string */
    private String _t(String s) {return Messages.getString(s, _context);}
    /** tag only */
    private static final String _x(String s) {return s;}

    /**
     *  translate a string with a parameter
     *
     *  @param s string to be translated containing {0}
     *  @param o parameter, not translated
     *  @return translated string
     */
    private String _t(String s, Object o) {return Messages.getString(s, o, _context);}
}
