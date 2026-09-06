package net.i2p.router.transport;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.LHMCache;
import net.i2p.util.Translate;

/**
 *  HTML rendering of peers (flag, capability bar, identity block) for the
 *  NetDb, PeerHelper, and tunnel pages.
 *
 *  Extracted from {@link CommSystemFacadeImpl} (2026-09-06) so the peer-render
 *  cluster, its RouterInfo/capacity caches, and its i18n state live apart from
 *  the facade core. The facade retains thin {@code commSystem()} delegators so
 *  the public API is unchanged; collaborators (country lookup, reverse-DNS)
 *  are injected rather than fetched through {@code commSystem()} round-trips.
 *
 *  Pure memory-independent decisions (bandwidth classification, visible-cap
 *  cleanup) are package-visible statics so they can be tested without a running
 *  router; see {@code PeerHTMLRendererDecisionTest}.
 *
 *  @since 0.9.71+
 */
public class PeerHTMLRenderer {

    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";
    private static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";

    /** Removes the leading D/E/G bandwidth marker from a visible capacity string */
    private final RouterContext _context;
    private final CountryLookup _countryLookup;
    private final ReverseDnsLookup _rdns;

    // Cache RouterInfo and Capacity to improve repeated lookup efficiency
    private final Map<Hash, RouterInfo> routerInfoCache = Collections.synchronizedMap(new LHMCache<>(5000));
    private final Map<Hash, String> capacityCache = Collections.synchronizedMap(new LHMCache<>(5000));

    /**
     *  @param ctx non-null
     *  @param countryLookup non-null
     *  @param rdns non-null
     *  @since 0.9.71+
     */
    public PeerHTMLRenderer(RouterContext ctx, CountryLookup countryLookup, ReverseDnsLookup rdns) {
        _context = ctx;
        _countryLookup = countryLookup;
        _rdns = rdns;
    }

    /**
     * Renders HTML for a peer with optional extended info.
     * Uses cached RouterInfo, country info, and reverse lookup cache.
     *
     * @param peer Peer Hash
     * @param extended Whether to show extended capabilities
     * @return HTML snippet representing peer
     */
    public String renderPeerHTML(Hash peer, boolean extended) {
        StringBuilder buf = new StringBuilder(256);
        RouterInfo ri = getRouterInfoCached(peer);
        String c = _countryLookup.getCountry(peer);
        String h = peer.toBase64();

        if (ri != null) {
            String caps = ri.getCapabilities();
            String v = ri.getVersion();
            String ip = Addresses.toString(CountryLookup.getValidIP(ri));

            buf.append("<table class=rid><tr><td class=rif>");
            if (c != null) {
                String countryName = localizedCountryName(c);

                buf.append("<a href=\"/netdb?c=").append(c).append("\"><img width=20 height=15 alt=")
                   .append(c.toUpperCase(Locale.US)).append(" title=\"").append(countryName);

                if (ip != null && !"null".equals(ip)) {
                    // Non-blocking reverse-DNS: resolved name or raw IP, queued on a miss.
                    buf.append(" &bullet; ").append(resolveHostForDisplay(ip));
                }
                buf.append("\" src=\"/flags.jsp?c=").append(c).append("\" loading=lazy></a>");
            } else {
                buf.append("<img width=20 height=15 alt=\"??\" src=\"/flags.jsp?c=xx\" title=\"").append(_t("unknown"));
                if (ip != null) {buf.append(" &bullet; ").append(ip);}
                buf.append("\" loading=lazy>");
            }
            buf.append("</td><td class=rih>");
            buf.append("<a title=\"");
            if (caps.contains("f") && !extended) {buf.append(_t("Floodfill"));}
            if (v != null) {
                if (!extended) {buf.append(" &bullet; ");}
                buf.append(v);
            }
            buf.append("\" href=\"netdb?r=").append(h.substring(0,10)).append("\">").append(h.substring(0,4)).append("</a>");
            if (extended) {buf.append("</td>").append(renderPeerCaps(peer, true));}
        } else {
            buf.append("<table class=rid><tr><td class=rif>").append(renderPeerFlag(peer))
               .append("</td><td class=rih>").append(h.substring(0,4));
            if (extended) {buf.append("</td><td class=rbw>?</td>");}
        }
        buf.append("</tr></table>");
        return buf.toString();
    }

    /**
     * Render the HTML flag image for the given peer.
     */
    public String renderPeerFlag(Hash peer) {
        StringBuilder buf = new StringBuilder(128);
        RouterInfo ri = getRouterInfoCached(peer);
        String unknownFlag = "<img class=unknownflag width=24 height=18 alt=\"??\" src=\"/flags.jsp?c=xx\" loading=lazy>";
        String countryCode = _countryLookup.getCountry(peer);
        if (countryCode == null) {countryCode = "xx";}
        String countryName = localizedCountryName(countryCode);
        buf.append("<span class=cc hidden>").append(countryCode.toUpperCase(Locale.US)).append("</span>");
        buf.append("<span class=peerFlag title=\"");
        if (ri != null) {
            String ip = Addresses.toString(CountryLookup.getValidIP(ri));
            if (ip == null || ip.isEmpty() || "null".equals(ip)) {
                byte[] transportIP = CountryLookup.getIP(ri);
                if (transportIP != null) {
                    ip = Addresses.toString(transportIP);
                }
            }
            if (!"xx".equals(countryCode) && countryName.length() > 2) {
                buf.append(countryName);
                if (ip != null && ip.length() > 6) {
                    buf.append(" &bullet; ");
                    String host = resolveHostForDisplay(ip);
                    if (!host.equals(ip)) {
                        buf.append(host).append(" (").append(ip).append(")");
                    } else {
                        buf.append(host);
                    }
                }
            } else {buf.append(_t("unknown"));}
            buf.append("\">");
            if (!"xx".equals(countryCode)) {
                buf.append("<a href=\"/netdb?c=").append(countryCode).append("\"><img width=24 height=18 alt=")
                   .append(countryCode.toUpperCase(Locale.US)).append(" src=\"/flags.jsp?c=")
                   .append(countryCode).append("\" loading=lazy></a>");
            } else {buf.append(unknownFlag);}
        } else {buf.append(_t("unknown")).append("\">").append(unknownFlag);}
        buf.append("</span>");
        return buf.toString();
    }

    /**
     * Renders the peer's capability HTML block.
     * Caches data and removes unnecessary repeated computation.
     *
     * @param peer Peer Hash
     * @param inline If true, render inline without table wrapper
     * @return HTML snippet of peer capabilities
     */
    public String renderPeerCaps(Hash peer, boolean inline) {
        StringBuilder buf = new StringBuilder(inline ? 128 : 256);
        if (!inline) {buf.append("<table class=\"rid ric\"><tr>");}

        RouterInfo ri = getRouterInfoCached(peer);
        if (ri != null) {
            String caps = ri.getCapabilities();
            String capacity = getCapacityCached(peer);

            boolean hasD = caps.indexOf('D') >= 0;
            boolean hasE = caps.indexOf('E') >= 0;
            boolean hasG = caps.indexOf('G') >= 0;
            boolean isFF = caps.indexOf('f') >= 0;
            boolean isU = caps.indexOf('U') >= 0;
            boolean isR = caps.indexOf('R') >= 0;

            buf.append("<td class=\"rbw ").append(capacity);
            if (isFF) buf.append(" isff");
            if (isU) buf.append(" isU");
            if (hasD) buf.append(" isD");
            else if (hasE) buf.append(" isE");
            else if (hasG) buf.append(" isG");
            buf.append("\"><a href=\"/netdb?caps=").append(capacity);

            if (isFF) buf.append("f");
            if (isU) buf.append("U");
            else if (isR) buf.append("R");
            if (hasD) buf.append("D");
            else if (hasE) buf.append("E");
            else if (hasG) buf.append("G");
            buf.append("\" title=\"").append(_t("Show all routers with this capability in the NetDb")).append("\">");

            buf.append(capacity);

            buf.append("</a></td>");
        } else {buf.append("<td class=rbw>?</td>");}
        if (!inline) {buf.append("</tr></table>\n");}
        return buf.toString();
    }

    private RouterInfo getRouterInfoCached(Hash peer) {
        RouterInfo rv = routerInfoCache.get(peer);
        if (rv == null) {
            rv = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            if (rv != null) {
                routerInfoCache.put(peer, rv);
            }
        }
        return rv;
    }

    private String getCapacityCached(Hash peer) {
        String rv = capacityCache.get(peer);
        if (rv == null) {
            RouterInfo ri = getRouterInfoCached(peer);
            if (ri == null) {
                rv = "?";
            } else {
                rv = classifyCapacity(ri.getCapabilities());
            }
            capacityCache.put(peer, rv);
        }
        return rv;
    }

    /**
     *  Localized country display name for a two-letter code. Names needing no
     *  translation (single words, already localized) are returned verbatim.
     *
     *  Shared by {@link #renderPeerHTML} and {@link #renderPeerFlag}; extracting
     *  it removes the duplicated lookup-and-translate branch from both.
     *
     *  @param code two-letter country code (never null at call sites)
     *  @return country name, translated when multi-word
     *  @since 0.9.71+
     */
    private String localizedCountryName(String code) {
        String countryName = _countryLookup.getCountryName(code);
        if (countryName.length() > 2) {
            countryName = Translate.getString(countryName, _context, COUNTRY_BUNDLE_NAME);
        }
        return countryName;
    }

    /**
     *  Reverse-DNS label: resolves {@code ip} to its canonical host name when
     *  reverse lookups are enabled and the resolver offers a distinct, known
     *  name; otherwise returns the raw IP. Non-blocking-the lookup is served
     *  from the rDNS cache and a background lookup is queued on a miss.
     *
     *  Deduplicates the resolver branch formerly duplicated across
     *  {@link #renderPeerHTML} and {@link #renderPeerFlag}; the flag renderer
     *  adds the raw-IP suffix itself when the name differs.
     *
     *  @param ip literal IP string (never null)
     *  @return canonical host name or the raw IP
     *  @since 0.9.71+
     */
    private String resolveHostForDisplay(String ip) {
        if (!_rdns.enableReverseLookups()) {return ip;}
        String canonicalHost = _rdns.getCanonicalHostName(ip);
        if (canonicalHost == null || canonicalHost.equals(ip) || _t("unknown").equals(canonicalHost)) {
            return ip;
        }
        return canonicalHost;
    }

    /**
     *  Highest-priority bandwidth capability char present in the caps string,
     *  or "?" when none is advertised.
     *
     *  Priority follows {@link RouterInfo#BW_CAPABILITY_CHARS} order, so a peer
     *  advertising both a high and a low class is classified at its high end.
     *  This is the decision the former {@code getCapacityCached} inline loop
     *  made; it is exposed so the offline {@code PeerHTMLRendererDecisionTest}
     *  can pin both the string and the null/empty handling without a router.
     *
     *  @param caps capabilities string (never null in practice; null treated as none)
     *  @return single bandwidth-class char or "?"
     *  @since 0.9.71+
     */
    static String classifyCapacity(String caps) {
        if (caps == null) {return "?";}
        for (int i = 0; i < RouterInfo.BW_CAPABILITY_CHARS.length(); i++) {
            char c = RouterInfo.BW_CAPABILITY_CHARS.charAt(i);
            if (caps.indexOf(c) >= 0) {
                return String.valueOf(c);
            }
        }
        return "?";
    }

    /**
     *  Translate
     */
    private final String _t(String s) {return Translate.getString(s, _context, BUNDLE_NAME);}
}
