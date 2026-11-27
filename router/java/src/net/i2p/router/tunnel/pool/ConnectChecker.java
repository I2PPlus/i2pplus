package net.i2p.router.tunnel.pool;

import java.util.Collection;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportManager;
import net.i2p.router.util.PeerLogFormatter;
import net.i2p.util.Log;

    /**
     * Tools to check transport compatibility.
     *
     * @since 0.9.34
     */
    public class ConnectChecker {

    /** The router context. */
    protected final RouterContext ctx;
    /** The logger. */
    protected final Log log;

    /** Bit for an NTCP connection to an IPv4 address. */
    static final int NTCP_V4 = 0x01;
    /** Bit for an NTCP connection to an IPv6 address. */
    static final int NTCP_V6 = 0x04;
    /** Bit for an SSU connection to an IPv4 address. */
    static final int SSU_V4 = 0x02;
    /** Bit for an SSU connection to an IPv6 address. */
    static final int SSU_V6 = 0x08;
    /** Bit for an SSU2 connection to an IPv4 address. */
    static final int SSU2_V4 = 0x10;
    /** Bit for an SSU2 connection to an IPv6 address. */
    static final int SSU2_V6 = 0x20;
    /** Any IPv4 transport. */
    public static final int ANY_V4 = NTCP_V4 | SSU_V4 | SSU2_V4;

    /**
     * Inbound capacity, as a percentage of each transport's inbound connection
     * limit, that we require before letting a peer we have no session with be
     * the closest hop of one of our inbound tunnels.
     */
    private static final int INBOUND_CAPACITY_PCT = 95;

    /**
     * Create a new ConnectChecker.
     * @param context the router context
     */
    public ConnectChecker(RouterContext context) {
        ctx = context;
        log = ctx.logManager().getLog(getClass());
    }

    /**
     * Is NTCP disabled?
     *
     * @return true if NTCP is disabled
     * @since 0.9.34
     */
    protected boolean isNTCPDisabled() {return !TransportManager.isNTCPEnabled(ctx);}

    /**
     * Is SSU disabled?
     *
     * @return true if SSU is disabled
     * @since 0.9.34
     */
    protected boolean isSSUDisabled() {return !ctx.getBooleanPropertyDefaultTrue(TransportManager.PROP_ENABLE_UDP);}

    /**
     * Is SSU2 enabled?
     * @return whether s s u2 enabled
     * @since 0.9.56
     */
    private boolean isSSU2Enabled() {return true;}

    /**
     * Can "from" connect to "to" based on published addresses?
     * <p>
     * This is intended for tunnel candidates, where we already have
     * the RI. Will not force RI lookups.
     * Either from or to may be us.
     * <p>
     * This is best effort, as we can't know for sure.
     * Published addresses or introducers may have changed.
     * Even if a can't connect to b, they may already be connected
     * as b connected to a.
     *
     * @param from the source router hash
     * @param to the destination router hash
     * @return true if we don't have either RI
     * @since 0.9.34
     */
    public boolean canConnect(Hash from, Hash to) {
        Hash us = ctx.routerHash();
        if (us == null) {return true;}
        boolean usf = from.equals(us);
        if (usf) {
            if (ctx.commSystem().isEstablished(to)) {
                if (ctx.commSystem().wasUnreachable(to)) {
                    if (log.shouldWarn()) {
                        log.warn("Cannot connect: we control from, but " +
                                 PeerLogFormatter.routerHashTag(to, us) + " is unreachable");
                    }
                    return false;
                }
                return true;
            }
        }
        boolean ust = to.equals(us);
        if (ust) {
            if (ctx.commSystem().isEstablished(from)) {
                if (ctx.commSystem().wasUnreachable(from)) {
                    if (log.shouldWarn()) {
                        log.warn("Cannot connect: we control to, but " +
                                 PeerLogFormatter.routerHashTag(from, us) + " is unreachable");
                    }
                    return false;
                }
                return true;
            }
        }
        // Presence-only checks: the build requestor accepts unvalidated cached entries
        RouterInfo rt = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(to);
        if (rt == null) {return false;}
        RouterInfo rf = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(from);
        if (rf == null) {return false;}
        int ct;
        if (ust) {ct = getInboundMask(rt);} // to us
        else {
            Collection<RouterAddress> at = rt.getAddresses();
            // assume nothing if hidden
            if (at.isEmpty()) {return false;}
            ct = getConnectMask(at);
        }

        int cf;
        if (usf) {cf = getOutboundMask(rf);} // from us
        else {
            Collection<RouterAddress> a = rf.getAddresses();
            if (a.isEmpty()) {cf = NTCP_V4 | SSU_V4;} // assume IPv4 if hidden
            else {cf = getConnectMask(a);}
        }

        boolean rv = (ct & cf) != 0;
        if (!rv && log.shouldWarn()) {
            log.warn("Cannot connect " + PeerLogFormatter.routerHashTag(from, us) +
                     " with mask " + cf + " -> " + PeerLogFormatter.routerHashTag(to, us) +
                     " with mask " + ct);
        }
        // When neither peer is us, check our own reachability as a proxy.
        // If we can't reach a tunnel participant, they're unlikely to reliably
        // forward data for others either.
        if (rv && !usf && !ust) {
            if (!ctx.commSystem().getEstablished().isEmpty()) {
                if (ctx.commSystem().wasUnreachable(from) ||
                    ctx.commSystem().wasUnreachable(to)) {
                    if (log.shouldWarn()) {
                        log.warn("Cannot connect, non-self pair rejected -> A participant is unreachable"
                                 + "\n* From " + PeerLogFormatter.routerHashTag(from, us) + " unreachable: " +
                                 ctx.commSystem().wasUnreachable(from)
                                 + " * To " + PeerLogFormatter.routerHashTag(to, us) + " unreachable: " +
                                 ctx.commSystem().wasUnreachable(to));
                    }
                    return false;
                }
            }
        }
        return rv;
    }

    /**
     * Can we connect to "to" based on published addresses?
     * <p>
     * This is intended for tunnel candidates, where we already have
     * the RI. Will not force RI lookups.
     * <p>
     * This is best effort, as we can't know for sure.
     * Does not check isEstablished(); do that first.
     *
     * @param ourMask our connection mask
     * @param to the destination router info
     * @return true if we can connect
     * @since 0.9.34
     */
    public boolean canConnect(int ourMask, RouterInfo to) {
        Collection<RouterAddress> ra = to.getAddresses();
        // assume nothing if hidden
        if (ra.isEmpty()) {return false;}
        int ct = getConnectMask(ra);
        return (ourMask & ct) != 0;
    }

    /**
     * Can "from" connect to us based on published addresses?
     * <p>
     * This is intended for tunnel candidates, where we already have
     * the RI. Will not force RI lookups.
     * <p>
     * This is best effort, as we can't know for sure.
     * Does not check isEstablished(); do that first.
     *
     * @param from the source router info
     * @param ourMask our connection mask
     * @return true if from can connect to us
     * @since 0.9.34
     */
    public boolean canConnect(RouterInfo from, int ourMask) {
        if (ourMask == 0) {return false;}
        Collection<RouterAddress> ra = from.getAddresses();
        int cf;
        if (ra.isEmpty()) {cf = NTCP_V4 | SSU_V4;} // assume v4 if hidden
        else {cf = getConnectMask(ra);}
        return (cf & ourMask) != 0;
    }

    /**
     * Our inbound mask: the routes we published that someone outside can
     * actually dial.
     *
     * @param us our router info
     * @return bitmask for accepting connections, 0 if nothing we published can
     *         be dialed
     * @since 0.9.34
     */
    public int getInboundMask(RouterInfo us) {
        // Only what we published, and only the routes that can actually be
        // dialed. The status switch this replaces consulted nothing we published
        // at all for UNKNOWN, DISCONNECTED, HOSED and the v4-OK statuses, so at
        // startup, and during a network problem, we claimed v4 inbound routes
        // whatever the router advertises - an outbound-only router included,
        // whose NTCP2 address has no host at all.
        return getConnectMask(us.getAddresses(), true);
    }

    /**
     * Must the hop adjacent to us be a peer we already have a session with?
     * <p>
     * True when nothing we published can be dialed at all, and when a symmetric
     * NAT leaves only IPv4 UDP routes: under one, the mapping a peer sees is
     * per-peer, so the port we published is not one anyone else can reach us on.
     * Both cases are the same problem the {@code hidden} flag already covers -
     * an inbound tunnel whose adjacent hop was picked without a live session
     * fails its build, and the adjacent hop is penalized for a route we never
     * had.
     *
     * @param us our router info
     * @return true if the adjacent hop has to be an established peer
     * @since 0.9.72+
     */
    boolean requiresConnectedInboundPeer(RouterInfo us) {
        int mask = getInboundMask(us);
        if (mask == 0)
            return true;
        Status status = ctx.commSystem().getStatus();
        if (status != Status.IPV4_SNAT_IPV6_OK && status != Status.IPV4_SNAT_IPV6_UNKNOWN)
            return false;
        return (mask & ~(SSU_V4 | SSU2_V4)) == 0;
    }

    /**
     * Must the hop adjacent to us be a peer we already have a session with?
     * <p>
     * The full decision the peer selectors fold into their {@code hidden} flag:
     * a hidden router, one that publishes no addresses at all, one whose
     * transports are near their inbound connection limit, and one whose
     * published routes cannot be dialed all mean the same thing when picking the
     * closest hop - an inbound tunnel built through a peer we cannot be reached
     * through fails, and that peer is blamed for it.
     *
     * @return true if the closest hop of an inbound tunnel has to be established
     * @since 0.9.72+
     */
    boolean inboundHopMustBeConnected() {
        RouterInfo us = ctx.router().getRouterInfo();
        return ctx.router().isHidden() ||
               us.getAddressCount() <= 0 ||
               !ctx.commSystem().haveInboundCapacity(INBOUND_CAPACITY_PCT) ||
               requiresConnectedInboundPeer(us);
    }

    /**
     * Our outbound mask.
     * For most cases, we use our comm system status.
     *
     * @param us our router info
     * @return bitmask for initiating connections
     * @since 0.9.34
     */
    public int getOutboundMask(RouterInfo us) {
        // from us
        int cf = 0;
        Status status = ctx.commSystem().getStatus();
        switch (status) {
            case OK:
                // use what we published, as the OK state doesn't tell us about IPv6
                // Addresses.isConnectedIPv6() is too slow
                Collection<RouterAddress> a = us.getAddresses();
                if (a.isEmpty()) {
                    // we are hidden
                    // TODO ipv6
                    if (!isNTCPDisabled()) {cf |= NTCP_V4;}
                    if (!isSSUDisabled()) {
                        cf |= SSU_V4;
                        if (isSSU2Enabled()) {cf |= SSU2_V4;}
                    }
                } else {cf = getConnectMask(a);}
                break;

            case IPV4_OK_IPV6_FIREWALLED:
            case IPV4_UNKNOWN_IPV6_OK:
            case IPV4_FIREWALLED_IPV6_OK:
            case IPV4_SNAT_IPV6_OK:
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
                if (!isNTCPDisabled()) {cf |= NTCP_V4 | NTCP_V6;}
                if (!isSSUDisabled()) {
                    cf |= SSU_V4 | SSU_V6;
                    if (isSSU2Enabled()) {cf |= SSU2_V4 | SSU2_V6;}
                }
                break;

            case IPV4_DISABLED_IPV6_OK:
            case IPV4_DISABLED_IPV6_UNKNOWN:
            case IPV4_DISABLED_IPV6_FIREWALLED:
                if (!isNTCPDisabled()) {cf |= NTCP_V6;}
                if (!isSSUDisabled()) {
                    cf |= SSU_V6;
                    if (isSSU2Enabled()) {cf |= SSU2_V6;}
                }
                break;

            case DIFFERENT:
            case IPV4_SNAT_IPV6_UNKNOWN:
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
            case REJECT_UNSOLICITED:
            case IPV4_OK_IPV6_UNKNOWN:
            case DISCONNECTED:
            case HOSED:
            case UNKNOWN:
            default:
                if (!isNTCPDisabled()) {cf |= NTCP_V4;}
                if (!isSSUDisabled()) {
                    cf |= SSU_V4;
                    if (isSSU2Enabled()) {cf |= SSU2_V4;}
                }
                break;
        }
        return cf;
    }

    /** Prevent object churn. */
    private static final String IHOST[] = {"ihost0", "ihost1", "ihost2"};

    /**
     * The bitmask of supported transports for the given addresses, counting only
     * what could be dialed from outside when {@code inbound} is set.
     * <p>
     * An address with no host, or with no port published at all, cannot be
     * dialed; our own addresses are not judged by the port heuristic applied to
     * other routers', which rejects ports a NAT mapping of ours may well land on.
     * The
     * {@code caps} option that a hostless address carries says what the peer
     * could reach, not what can reach it. Our own transports publish exactly that
     * form for an outbound-only listener, so reading the families out of
     * {@code caps} tells the tunnel validator that a peer can dial us when
     * nothing is listening. An SSU address with an introducer tag is the
     * exception: the introducer can reach it on our behalf.
     *
     * @param addrs non-empty, set your own default if empty
     * @param inbound true to count only routes that can actually be dialed
     * @return bitmask of v4/v6 NTCP/SSU/SSU2
     * @since 0.9.72+
     */
    static int getConnectMask(Collection<RouterAddress> addrs, boolean inbound) {
        int rv = 0;
        for (RouterAddress ra : addrs) {
            String style = ra.getTransportStyle();
            String host = ra.getHost();
            // A host with no port is not a route - the transports write no port
            // when they have no bind port. These are our own published addresses,
            // so the only question is whether a port was published at all.
            // TransportUtil.isValidPort() is the heuristic for judging somebody
            // else's address, and its blocklist of ports other routers should not
            // use - 9030, 7650-7668, 9100 - is a NAT mapping our own external
            // port can land in, which would then hide our inbound route.
            boolean dialable = host != null && ra.getPort() > 0;
            if ("NTCP2".equals(style)) {
                if (host != null) {
                    if (inbound && !dialable)
                        continue;
                    if (host.contains(":")) {rv |= NTCP_V6;}
                    else {rv |= NTCP_V4;}
                } else {
                    if (inbound)
                        continue;
                    String caps = ra.getOption("caps");
                    if (caps != null) {
                        if (caps.contains("4")) {rv |= NTCP_V4;}
                        if (caps.contains("6")) {rv |= NTCP_V6;}
                    }
                }
            } else if ("SSU".equals(style)) {
                boolean v2 = ra.getOption("v") != null;
                if (host == null) {
                    if (inbound && !hasIntroducer(ra))
                        continue;
                    String caps = ra.getOption("caps");
                    boolean v4 = false;
                    boolean v6 = false;
                    if (caps != null) {
                        if (caps.contains("4")) {
                            v4 = true;
                            rv |= SSU_V4;
                            if (v2) {rv |= SSU2_V4;}
                        }
                        if (caps.contains("6")) {
                            v6 = true;
                            rv |= SSU_V6;
                            if (v2) {rv |= SSU2_V6;}
                        }
                    }
                    if (!v4 && !v6) {
                        // ihost only for v1
                        for (int i = 0; i < 2; i++) {
                            String ihost = ra.getOption(IHOST[i]);
                            if (ihost == null) {break;}
                            if (ihost.contains(":")) {rv |= SSU_V6;}
                            else {rv |= SSU_V4;}
                        }
                    }
                } else if (host.contains(":")) {
                    if (inbound && !dialable)
                        continue;
                    rv |= SSU_V6;
                    if (v2) {rv |= SSU2_V6;}
                } else {
                    if (inbound && !dialable)
                        continue;
                    rv |= SSU_V4;
                    if (v2) {rv |= SSU2_V4;}
                }
            } else if ("SSU2".equals(style)) {
                if (host == null) {
                    if (inbound && !hasIntroducer(ra))
                        continue;
                    String caps = ra.getOption("caps");
                    if (caps != null) {
                        if (caps.contains("4")) {rv |= SSU2_V4;}
                        if (caps.contains("6")) {rv |= SSU2_V6;}
                    }
                } else if (host.contains(":")) {
                    if (inbound && !dialable)
                        continue;
                    rv |= SSU2_V6;
                } else {
                    if (inbound && !dialable)
                        continue;
                    rv |= SSU2_V4;
                }
            }
        }
        return rv;
    }

    /**
     * The bitmask of supported transports for the given addresses, counting what
     * the peer could reach, dialable or not.
     *
     * @param addrs non-empty, set your own default if empty
     * @return bitmask of v4/v6 NTCP/SSU
     * @since 0.9.34
     */
    private static int getConnectMask(Collection<RouterAddress> addrs) {
        return getConnectMask(addrs, false);
    }

    /**
     * Can an introducer reach this address on someone else's behalf? An SSU
     * introducer tag, or for v1 an introducer host, stands in for the host and
     * port the address does not carry.
     *
     * @param ra the published address
     * @return true if there is an introducer route to it
     * @since 0.9.72+
     */
    private static boolean hasIntroducer(RouterAddress ra) {
        if (ra.getOption("itag0") != null)
            return true;
        for (String ihost : IHOST) {
            if (ra.getOption(ihost) != null)
                return true;
        }
        return false;
    }

}
