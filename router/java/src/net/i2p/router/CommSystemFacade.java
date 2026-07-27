package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.util.Translate;

/**
 * Manages peer communication subsystem including transport protocols, connection handling, and network address management for router-to-router messaging.
 *
 */
public abstract class CommSystemFacade implements Service {

    /** @since 0.9.45 */
    protected static final String ROUTER_BUNDLE_NAME = "net.i2p.router.util.messages";
/** Commsystemfacade */

    protected CommSystemFacade() {}

    /**
     *  Queue an outbound message for delivery through the appropriate transport.
     *
     *  @param msg the message to send
     */
    public abstract void processMessage(OutNetMessage msg);

    /**
     *  Render the transport status section of the router console page.
     *
     *  @param out destination writer
     *  @param urlBase base URL for links, may be null
     *  @param sortFlags flags controlling sort order
     *  @throws IOException on write error
     */
    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException { }
    /**
     *  Render the transport status section of the router console page with default flags.
     *
     *  @param out destination writer
     *  @throws IOException on write error
     */
    public void renderStatusHTML(Writer out) throws IOException { renderStatusHTML(out, null, 0); }

    /** Create the list of RouterAddress structures based on the router's config */
    public List<RouterAddress> createAddresses() { return Collections.emptyList(); }

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to or received a message from in the last five minutes.
     */
    public abstract int countActivePeers();

    /**
     *  How many peers are we currently connected to, that we have
     *  sent a message to in the last minute.
     *  Unused for anything, to be removed.
     */
    public abstract int countActiveSendPeers();

    /**
     *  Report whether inbound bandwidth has capacity for the given percentage load.
     *
     *  @param pct percentage of bandwidth currently in use
     *  @return true if capacity is available
     */
    public boolean haveInboundCapacity(int pct) { return true; }
    /**
     *  Report whether outbound bandwidth has capacity for the given percentage load.
     *
     *  @param pct percentage of bandwidth currently in use
     *  @return true if capacity is available
     */
    public boolean haveOutboundCapacity(int pct) { return true; }
    /**
     *  Report whether outbound bandwidth is operating well below its limit.
     *
     *  @return true if high outbound capacity is available
     */
    public boolean haveHighOutboundCapacity() { return true; }
    /**
     *  Retrieve recent transport-related error messages for display in the console.
     *
     *  @return list of error message strings, non-null
     */
    public List<String> getMostRecentErrorMessages() { return Collections.emptyList(); }

    /**
     * Median clock skew of connected peers in seconds, or null if we cannot answer.
     * CommSystemFacadeImpl overrides this.
     */
    public Long getMedianPeerClockSkew() { return null; }

    /**
     * Return framed average clock skew of connected peers in seconds, or null if we cannot answer.
     * CommSystemFacadeImpl overrides this.
     *
     * @param percentToInclude percentage of peers to include in the frame
     * @return average skew in seconds
     */
    public long getFramedAveragePeerClockSkew(int percentToInclude) { return 0; }

    /**
     * Determine under what conditions we are remotely reachable.
     * For internal use only.
     * Not recommended for plugins or embedded applications, as
     * the integer codes may change. Use getStatus() instead.
     *
     * @deprecated use getStatus()
     */
    @Deprecated
    public short getReachabilityStatus() { return (short) getStatus().getCode(); }

    /**
     * Determine under what conditions we are remotely reachable.
     *
     * @since 0.9.20
     */
    public Status getStatus() { return Status.OK; }

    /**
     * getStatus().toStatusString(), translated if available.
     *
     * @since 0.9.45
     */
    public String getLocalizedStatusString() {
        return getStatus().toStatusString();
    }

    /**
     * @deprecated unused
     */
    @Deprecated
    public void recheckReachability() {}

    /**
     *  Check whether the given peer has excessive pending outbound messages.
     *
     *  @param peer the peer to check
     *  @return true if the peer is backlogged
     */
    public boolean isBacklogged(Hash peer) { return false; }
    /**
     *  Check whether the given peer was recently unreachable.
     *
     *  @param peer the peer to check
     *  @return true if the peer was unreachable
     */
    public boolean wasUnreachable(Hash peer) { return false; }
    /**
     *  Check whether a transport connection exists with the given peer.
     *
     *  @param peer the peer to check
     *  @return true if a connection is established
     */
    public abstract boolean isEstablished(Hash peer);

    /**
     * Check if any transport has a connection attempt in progress
     * for the given peer (handshake started but not yet complete).
     *
     * @param peer hash of the peer to check
     * @return true if at least one transport is currently establishing
     * @since 0.9.62
     */
    public boolean isConnecting(Hash peer) { return false; }
    /**
     *  Get the IP address associated with the given destination.
     *
     *  @param dest destination hash
     *  @return IP address bytes or null if unknown
     */
    public byte[] getIP(Hash dest) { return null; }
    /**
     *  Queue a reverse-DNS lookup for the given IP address.
     *
     *  @param ip IP address bytes to look up
     */
    public void queueLookup(byte[] ip) {}

    /**
     * Tell the comm system that we may disconnect from this peer.
     * This is advisory only.
     *
     * @since 0.9.24
     */
    public void mayDisconnect(Hash peer) {}

    /**
     * Tell the comm system to disconnect from this peer.
     *
     * @since 0.9.38
     */
    public void forceDisconnect(Hash peer) {}

    /**
     * Tell the comm system to disconnect from this peer with a reason.
     *
     * @param peer the peer hash
     * @param reason reason for disconnection (for logging), may be null
     * @since 0.9.38
     */
    public void forceDisconnect(Hash peer, String reason) {}

    /** @since 0.8.11 */
    public String getOurCountry() { return null; }

    /** @since 0.8.13 */
    public boolean isInStrictCountry() { return false; }

    /** @since 0.9.16 */
    public boolean isInStrictCountry(Hash peer) { return false; }

    /** @since 0.9.16 */
    public boolean isInStrictCountry(RouterInfo ri) { return false; }

    /**
     *  Get the two-letter country code for the given peer's IP address.
     *
     *  @param peer the peer to look up
     *  @return two-letter country code or null if unknown
     */
    public String getCountry(Hash peer) { return null; }
    /**
     *  Get the two-letter country code for the given IP address.
     *
     *  @param ip the IP address to look up
     *  @return two-letter country code or null if unknown
     */
    public String getCountry(String ip) { return null; }
    /**
     *  Resolve a two-letter country code to its full country name.
     *
     *  @param code two-letter country code
     *  @return country name, or the code itself if unknown
     */
    public String getCountryName(String code) { return code; }

    /**
     * Get the country code map
     *
     * @return Map of two-letter lower case code to untranslated country name, unmodifiable
     * @since 0.9.53
     */
    public Map<String, String> getCountries() {
        return Collections.emptyMap();
    }

    /**
     *  Render an HTML snippet identifying the given peer, optionally with extended details.
     *
     *  @param peer the peer to render
     *  @param extended if true include extended information
     *  @return HTML string
     */
    public String renderPeerHTML(Hash peer, boolean extended) {
        return peer.toBase64().substring(0, 4);
    }

    /**
     *  Render the country flag for the given peer as HTML.
     *
     *  @param peer the peer to render
     *  @return HTML string for the flag
     */
    public String renderPeerFlag(Hash peer) {
        return peer.toBase64().substring(0, 4);
    }

    /**
     *  Render the peer's capabilities as HTML, optionally inline.
     *
     *  @param peer the peer to render
     *  @param inline if true render inline
     *  @return HTML string
     */
    public String renderPeerCaps(Hash peer, boolean inline) {
        return peer.toBase64().substring(0, 4);
    }

    /**
     *  Look up the canonical hostname for the given IP, blocking on DNS if necessary.
     *
     *  @param ipAddress the IP address to look up
     *  @return hostname, or the IP itself if not resolvable
     */
    public synchronized String getCanonicalHostName(String ipAddress) {
        return ipAddress;
    }

    /**
     *  Synchronous canonical hostname lookup for the given IP.
     *
     *  @param ipAddress the IP address to look up
     *  @return hostname, or the IP itself if not resolvable
     */
    public String getCanonicalHostNameSync(String ipAddress) {
        return ipAddress;
    }

    /**
     * Fast hostname lookup that never blocks on network DNS.
     * Returns cached result if available, otherwise does a local ASN lookup.
     * Queues background async RDNS if enabled, updating the cache on success.
     *
     * @return hostname from cache, ASN org name, or null if not resolvable
     * @since 0.9.70+
     */
    public String getLocalHostName(String ipAddress) {
        return ipAddress;
    }

    /**
     *  @return SortedMap of style to Transport (a copy)
     *  @since 0.9.31
     */
    public SortedMap<String, Transport> getTransports() {
        return new TreeMap<>();
    }

    /**
     *  Get all the peers we are connected to.
     *  This should be more efficient than repeated calls to isEstablished()
     *  if you have to check a lot.
     *
     *  @return the hashes of all the routers we are connected to, non-null
     *  @since 0.9.34
     */
    public abstract List<Hash> getEstablished();

    /** @since 0.8.13 */
    public boolean isDummy() { return true; }

    /** @since 0.9.53 */
    public boolean isRunning() { return true; }

    /**
     * Tell other transports our address changed
     */
    public void notifyReplaceAddress(RouterAddress address) {}

    /**
     * Tell other transports our address changed
     *
     * @since 0.9.20
     */
    public void notifyRemoveAddress(RouterAddress address) {}

    /**
     * Tell other transports our address changed
     *
     * @since 0.9.20
     */
    public void notifyRemoveAddress(boolean ipv6) {}

    /**
     *  Pluggable transport
     *
     *  @since 0.9.16
     */
    public void registerTransport(Transport t) {}

    /**
     *  Pluggable transport
     *
     *  @since 0.9.16
     */
    public void unregisterTransport(Transport t) {}

    /**
     *  Factory for making X25519 key pairs.
     *
     *  @since 0.9.46
     */
    public X25519KeyFactory getXDHFactory() { return null; }

    /**
     *  Router must call after netdb is initialized
     *
     *  @since 0.9.41
     */
    public void initGeoIP() {}

    /**
     *  Exempt this router hash from any incoming throttles or rejections
     *
     *  @since 0.9.58
     */
    public void exemptIncoming(Hash peer) {}

    /**
     *  Is this IP exempt from any incoming throttles or rejections
     *
     *  @since 0.9.58
     */
    public boolean isExemptIncoming(String ip) { return false; }

    /**
     *  Remove this IP from the exemptions
     *
     *  @since 0.9.58
     */
    public void removeExemption(String ip) {}

    /*
     *  Reachability status codes
     *
     *	IPv4	IPv6	Status
     *	----	----	------
     *	ok	ok	OK 0
     *	ok	x	OK 0
     *	ok	unk	OK/UNKNOWN 1
     *	ok	fw	OK/FIREWALLED 2
     *
     *	x	ok	DISABLED/OK 5
     *	x	x	HOSED 12
     *	x	unk	DISABLED/UNKNOWN 10
     *	x	fw	DISABLED/FIREWALLED 11
     *
     *	unk	ok	UNKNOWN/OK 3
     *	unk	x	UNKNOWN 14
     *	unk	unk	UNKNOWN 14
     *	unk	fw	UNKNOWN/FIREWALLED 9
     *
     *	fw	ok	FIREWALLED/OK 4
     *	fw	x	FIREWALLED 8
     *	fw	unk	FIREWALLED/UNKNOWN 7
     *	fw	fw	FIREWALLED 8
     *
     *	sym	any	DIFFERENT 6 (TODO add IPv6 states or not worth it?)
     *	disconnected	DISCONNECTED 12
     *	hosed		HOSED 13
     */

    /**
     * These must be increasing in "badness" (see TransportManager.java),
     * but UNKNOWN must be last.
     *
     * We are able to receive unsolicited connections
     * on all enabled transports
     */
/** Status Ok constant */
    public static final short STATUS_OK = 0;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We can receive unsolicited connections on IPv4.
     *  We might be able to receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Ok Ipv6 Unknown constant */
    public static final short STATUS_IPV4_OK_IPV6_UNKNOWN = 2;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We can receive unsolicited connections on IPv4.
     *  We cannot receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Ok Ipv6 Firewalled constant */
    public static final short STATUS_IPV4_OK_IPV6_FIREWALLED = 1;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We may be able to receive unsolicited connections on IPv4.
     *  We can receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Unknown Ipv6 Ok constant */
    public static final short STATUS_IPV4_UNKNOWN_IPV6_OK = 4;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We cannot receive unsolicited connections on IPv4.
     *  We can receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Firewalled Ipv6 Ok constant */
    public static final short STATUS_IPV4_FIREWALLED_IPV6_OK = 3;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  IPv4 is disabled.
     *  We can receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Disabled Ipv6 Ok constant */
    public static final short STATUS_IPV4_DISABLED_IPV6_OK = 5;

    /**
     *  We are behind a symmetric NAT which will make our 'from' address look
     *  differently when we talk to multiple people
     *  We can receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Snat Ipv6 Ok constant */
    public static final short STATUS_IPV4_SNAT_IPV6_OK = 6;

    /**
     * We are behind a symmetric NAT which will make our 'from' address look
     * differently when we talk to multiple people
     *
     */
/** Status Different constant */
    public static final short STATUS_DIFFERENT = 7;

    /**
     *  We are behind a symmetric NAT which will make our 'from' address look
     *  differently when we talk to multiple people
     *  We might be able to receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Snat Ipv6 Unknown constant */
    public static final short STATUS_IPV4_SNAT_IPV6_UNKNOWN = 8;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We cannot receive unsolicited connections on IPv4.
     *  We might be able to receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Firewalled Ipv6 Unknown constant */
    public static final short STATUS_IPV4_FIREWALLED_IPV6_UNKNOWN = 10;

    /**
     * We are able to talk to peers that we initiate communication with, but
     * cannot receive unsolicited connections, i.e. Firewalled,
     * on all enabled transports.
     */
/** Status Reject Unsolicited constant */
    public static final short STATUS_REJECT_UNSOLICITED = 9;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  We may be able to receive unsolicited connections on IPv4.
     *  We cannot receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Unknown Ipv6 Firewalled constant */
    public static final short STATUS_IPV4_UNKNOWN_IPV6_FIREWALLED = 11;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  IPv4 is disabled.
     *  We might be able to receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Disabled Ipv6 Unknown constant */
    public static final short STATUS_IPV4_DISABLED_IPV6_UNKNOWN = 13;

    /**
     *  We have an IPv6 transport enabled and a public IPv6 address.
     *  IPv4 is disabled.
     *  We can receive unsolicited connections on IPv6.
     *
     *  @since 0.9.20
     */
/** Status Ipv4 Disabled Ipv6 Firewalled constant */
    public static final short STATUS_IPV4_DISABLED_IPV6_FIREWALLED = 12;

    /**
     *  We have no network interface at all enabled transports
     *
     *  @since 0.9.4
     */
/** Status Disconnected constant */
    public static final short STATUS_DISCONNECTED = 14;

    /**
     * Our detection system is broken (SSU bind port failed)
     */
/** Status Hosed constant */
    public static final short STATUS_HOSED = 15;

    /**
     * Our reachability is unknown on all
     */
/** Status Unknown constant */
    public static final short STATUS_UNKNOWN = 16;

/**
     * Network connectivity status enumeration for IPv4 and IPv6 transport capabilities.
     * Represents firewall status, NAT configuration, and transport readiness for tunnel participation.
     *  Since codes may change.
     *
     *  @since 0.9.20
     */
    public enum Status {
        /** IPv4 OK, IPv6 OK or disabled or no address */
        OK(STATUS_OK, _x("OK")),
        /** IPv4 OK, IPv6 connectivity testing */
        IPV4_OK_IPV6_UNKNOWN(STATUS_IPV4_OK_IPV6_UNKNOWN, _x("IPv4: OK; IPv6: Testing")),
        /** IPv4 OK, IPv6 firewalled */
        IPV4_OK_IPV6_FIREWALLED(STATUS_IPV4_OK_IPV6_FIREWALLED, _x("IPv4: OK; IPv6: Firewalled")),
        /** IPv4 connectivity testing, IPv6 OK */
        IPV4_UNKNOWN_IPV6_OK(STATUS_IPV4_UNKNOWN_IPV6_OK, _x("IPv4: Testing; IPv6: OK")),
        /** IPv4 firewalled, IPv6 OK */
        IPV4_FIREWALLED_IPV6_OK(STATUS_IPV4_FIREWALLED_IPV6_OK, _x("IPv4: Firewalled; IPv6: OK")),
        /** IPv4 disabled, IPv6 OK */
        IPV4_DISABLED_IPV6_OK(STATUS_IPV4_DISABLED_IPV6_OK, _x("IPv4: Disabled; IPv6: OK")),
        /** IPv4 symmetric NAT (not source NAT) */
        IPV4_SNAT_IPV6_OK(STATUS_IPV4_SNAT_IPV6_OK, _x("IPv4: Symmetric NAT; IPv6: OK")),
        /** IPv4 symmetric NAT, IPv6 firewalled or disabled or no address */
        DIFFERENT(STATUS_DIFFERENT, _x("Symmetric NAT")),
        /** IPv4 symmetric NAT (not source NAT) */
        IPV4_SNAT_IPV6_UNKNOWN(STATUS_IPV4_SNAT_IPV6_UNKNOWN, _x("IPv4: Symmetric NAT; IPv6: Testing")),
        /** IPv4 firewalled, IPv6 connectivity testing */
        IPV4_FIREWALLED_IPV6_UNKNOWN(STATUS_IPV4_FIREWALLED_IPV6_UNKNOWN, _x("IPv4: Firewalled; IPv6: Testing")),
        /** IPv4 firewalled, IPv6 firewalled or disabled or no address */
        REJECT_UNSOLICITED(STATUS_REJECT_UNSOLICITED, _x("Firewalled")),
        /** IPv4 connectivity testing, IPv6 firewalled */
        IPV4_UNKNOWN_IPV6_FIREWALLED(STATUS_IPV4_UNKNOWN_IPV6_FIREWALLED, _x("IPv4: Testing; IPv6: Firewalled")),
        /** IPv4 disabled, IPv6 connectivity testing */
        IPV4_DISABLED_IPV6_UNKNOWN(STATUS_IPV4_DISABLED_IPV6_UNKNOWN, _x("IPv4: Disabled; IPv6: Testing")),
        /** IPv4 disabled, IPv6 firewalled */
        IPV4_DISABLED_IPV6_FIREWALLED(STATUS_IPV4_DISABLED_IPV6_FIREWALLED, _x("IPv4: Disabled; IPv6: Firewalled")),
        /** No network interface available on any transport */
        DISCONNECTED(STATUS_DISCONNECTED, _x("Disconnected")),
        /** Transport detection failure, SSU bind port conflict */
        HOSED(STATUS_HOSED, _x("Port Conflict")),
        /** Reachability has not been determined on any transport */
        UNKNOWN(STATUS_UNKNOWN, _x("Testing"));

        private final int code;
        private final String status;

        /**
         *  @param code the integer status code
         *  @param status the human-readable status string
         */
        Status(int code, String status) {
            this.code = code;
            this.status = status;
        }

        /**
         *  Get the integer code for this reachability status.
         *
         *  @return the integer status code
         */
        public int getCode() {
            return code;
        }

        /**
         *  Merge the new status with the old status, producing the best combined estimate.
         *
         *  @param oldStatus the previous status
         *  @param newStatus the newly observed status
         *  @return the merged status reflecting both observations
         */
        public static Status merge(Status oldStatus, Status newStatus) {
            // shortcut newStatus
            if (oldStatus == newStatus || newStatus == UNKNOWN)
                return oldStatus;
            // shortcut oldStatus
            if (oldStatus == UNKNOWN || oldStatus == DISCONNECTED || oldStatus == HOSED)
                return newStatus;
            switch (newStatus) {
                case IPV4_OK_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                            return oldStatus;

                        case DIFFERENT:
                        case REJECT_UNSOLICITED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return IPV4_OK_IPV6_FIREWALLED;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return IPV4_OK_IPV6_FIREWALLED;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                // fall through
                case IPV4_UNKNOWN_IPV6_OK:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_OK_IPV6_FIREWALLED:
                            return OK;

                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case DIFFERENT:
                        case IPV4_SNAT_IPV6_OK:
                            return oldStatus;

                        case REJECT_UNSOLICITED:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return IPV4_DISABLED_IPV6_OK;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return newStatus;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                            return OK;

                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_DISABLED_IPV6_UNKNOWN:
                            return IPV4_DISABLED_IPV6_OK;

                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return IPV4_SNAT_IPV6_OK;

                        default:
                            return newStatus;
                    }

                // fall through
                case IPV4_FIREWALLED_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case REJECT_UNSOLICITED:
                        case IPV4_OK_IPV6_FIREWALLED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return REJECT_UNSOLICITED;

                        case DIFFERENT:
                            return newStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return IPV4_FIREWALLED_IPV6_OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return REJECT_UNSOLICITED;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                // fall through
                case IPV4_UNKNOWN_IPV6_FIREWALLED:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                            return IPV4_OK_IPV6_FIREWALLED;

                        case IPV4_OK_IPV6_FIREWALLED:
                            return oldStatus;

                        case REJECT_UNSOLICITED:
                        case IPV4_FIREWALLED_IPV6_OK:
                            return REJECT_UNSOLICITED;

                        case IPV4_DISABLED_IPV6_OK:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        case DIFFERENT:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                            return oldStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return newStatus;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                            return IPV4_OK_IPV6_FIREWALLED;

                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                            return REJECT_UNSOLICITED;

                        case IPV4_DISABLED_IPV6_UNKNOWN:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        case IPV4_SNAT_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_OK:
                            return DIFFERENT;

                        default:
                            return newStatus;
                    }

                // fall through
                case IPV4_DISABLED_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return IPV4_DISABLED_IPV6_OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                        case REJECT_UNSOLICITED:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        case DIFFERENT:
                            return newStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return IPV4_DISABLED_IPV6_OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return IPV4_DISABLED_IPV6_FIREWALLED;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                // fall through
                case IPV4_SNAT_IPV6_UNKNOWN:
                    switch (oldStatus) {
                        // cases where we already knew both states
                        case OK:
                        case IPV4_DISABLED_IPV6_OK:
                        case IPV4_FIREWALLED_IPV6_OK:
                        case IPV4_SNAT_IPV6_OK:
                            return IPV4_SNAT_IPV6_OK;

                        case IPV4_OK_IPV6_FIREWALLED:
                        case IPV4_DISABLED_IPV6_FIREWALLED:
                        case REJECT_UNSOLICITED:
                            return DIFFERENT;

                        case DIFFERENT:
                            return newStatus;

                        // cases where we already knew the IPv6 state only
                        case IPV4_UNKNOWN_IPV6_OK:
                            return IPV4_SNAT_IPV6_OK;

                        case IPV4_UNKNOWN_IPV6_FIREWALLED:
                            return DIFFERENT;

                        // cases where we already knew the IPv4 state only
                        case IPV4_OK_IPV6_UNKNOWN:
                        case IPV4_FIREWALLED_IPV6_UNKNOWN:
                        case IPV4_DISABLED_IPV6_UNKNOWN:
                        case IPV4_SNAT_IPV6_UNKNOWN:
                            return newStatus;

                        default:
                            return newStatus;
                    }

                case UNKNOWN:
                    return oldStatus;

                default:
                    return newStatus;
            }
        }

        /**
         *  Readable status, not translated
         */
        public String toStatusString() {
            return status;
        }

        /**
         * toStatusString(), translated if available.
         *
         * @param ctx the context for translation lookup
         * @return translated status string
         * @since 0.9.45
         */
        public String toLocalizedStatusString(I2PAppContext ctx) {
            return Translate.getString(status, ctx, ROUTER_BUNDLE_NAME);
        }

        /**
         * toString.
         */
        @Override
        public String toString() {
            return super.toString() + " (" + code + "; " + status + ')';
        }

        /**
         *  Tag for translation.
         */
        private static String _x(String s) { return s; }
    }
}
