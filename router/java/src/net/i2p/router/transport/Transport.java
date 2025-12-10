package net.i2p.router.transport;
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
import java.util.List;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.OutNetMessage;

/**
 * Core interface for I2P transport protocols.
 * 
 * This interface defines the contract for all transport implementations
 * that enable I2P routers to communicate with each other. Transports
 * handle message sending, receiving, peer management, and network
 * address configuration.
 * 
 * <strong>Transport Types:</strong>
 * <ul>
 *   <li>NTCP - Stream-based TCP transport with encryption</li>
 *   <li>UDP - Datagram-based transport with reliability</li>
 *   <li>SSU - Sessionless UDP for introduction</li>
 * </ul>
 * 
 * <strong>Implementation Requirements:</strong>
 * <ul>
 *   <li>Implement all interface methods for proper functionality</li>
 *   <li>Handle both IPv4 and IPv6 addressing</li>
 *   <li>Support concurrent message sending and receiving</li>
 *   <li>Provide proper error handling and logging</li>
 *   <li>Integrate with TransportManager lifecycle</li>
 * </ul>
 * 
 * <strong>Plugin Development:</strong>
 * To implement a new transport plugin:
 * <ol>
 *   <li>Implement this interface and required base classes</li>
 *   <li>Add transport to TransportManager.startListening()</li>
 *   <li>Ensure proper configuration and discovery</li>
 *   <li>Test with existing I2P network compatibility</li>
 * </ol>
 * 
 * <strong>Note:</strong> API is subject to change. Please contact the
 * I2P development team if you're writing a new transport or
 * transport plugin to ensure compatibility and guidance.
 */
public interface Transport {

    /**
     * Request to send a message to the specified router.
     * 
     * This method allows the transport to evaluate whether it can
     * and wants to send a message to the target router. The transport
     * may decline based on current load, peer reputation, or
     * transport-specific constraints.
     * 
     * @param toAddress the target router's contact information
     * @param dataSize size of message payload, assumes full 16-byte header,
     *                  transports should adjust as necessary for their overhead
     * @return a TransportBid containing send details, or null if unwilling to send
     */
    public TransportBid bid(RouterInfo toAddress, int dataSize);

    /**
     * Asynchronously send a message to the specified peer.
     * 
     * This method handles the actual message transmission. If the send
     * operation succeeds, it queues up any follow-up jobs defined in
     * msg.getOnSendJob() and registers them with OutboundMessageRegistry
     * (if the message has a reply selector). If the send fails,
     * it queues up any failure handling jobs from msg.getOnFailedSendJob().
     * 
     * The method should return immediately and perform the actual sending
     * asynchronously to avoid blocking the calling thread.
     * 
     * @param msg the message to send, containing destination, payload, and callbacks
     */
    public void send(OutNetMessage msg);
    public void startListening();
    public void stopListening();

    /**
     * Get all addresses this transport is currently listening on.
     * 
     * This method returns the complete list of RouterAddress objects
     * representing all network endpoints (both IPv4 and IPv6) that
     * this transport is actively listening for connections on.
     * 
     * This method replaces the older getCurrentAddress() method
     * to support multiple addresses per transport.
     * 
     * @return list of all currently listening addresses, never null
     * @since IPv6 support was added
     */
    public List<RouterAddress> getCurrentAddresses();

    /**
     * Get the first currently listening address of specified IP version.
     * 
     * This method returns the first RouterAddress matching the requested
     * IP version from the list of current addresses. This is useful
     * when a specific address type is needed rather than all addresses.
     * 
     * Note: An address without a host component is considered IPv4.
     * This method replaces the older getCurrentAddress() method.
     * 
     * @param ipv6 true to return IPv6 address only; false to return IPv4 address only
     * @return first matching address for the specified IP version, or null if no such address exists
     * @since 0.9.50 lifted from TransportImpl for interface consistency
     */
    public RouterAddress getCurrentAddress(boolean ipv6);

    /**
     *  Do we have any current address?
     *  @since IPv6
     */
    public boolean hasCurrentAddress();

    /**
     * Request transport to update its addresses based on current conditions.
     * 
     * This method asks the transport to re-evaluate its current
     * network configuration and update its advertised addresses accordingly.
     * The transport should consider:
     * <ul>
     *   <li>Current network interface status</li>
     *   <li>UPnP discovery results</li>
     *   <li>Configuration file settings</li>
     *   <li>Firewall and NAT constraints</li>
     * </ul>
     * 
     * @return updated list of all addresses the transport is now advertising, never null
     */
    public List<RouterAddress> updateAddress();

    /**
     * Source of transport address configuration.
     *  @since IPv6
     */
    public enum AddressSource {
        SOURCE_UPNP("upnp"),
        SOURCE_INTERFACE("local"),
        /** unused */
        SOURCE_CONFIG("config"),
        SOURCE_SSU("ssu");

        private final String cfgstr;

        AddressSource(String cfgstr) {
            this.cfgstr = cfgstr;
        }

        public String toConfigString() {
            return cfgstr;
        }
    }

    /**
     * Notify transport of an external address change event.
     * 
     * This method informs the transport that its external address has
     * changed due to various sources like UPnP discovery, interface
     * changes, or configuration updates. The transport should evaluate
     * whether to accept this change based on the source and current state.
     * 
     * <strong>Call Conditions:</strong>
     * <ul>
     *   <li>Should NOT be called if IP didn't change from source's perspective</li>
     *   <li>Should NOT be called for local/private addresses</li>
     *   <li>May be called multiple times (once for IPv4, once for IPv6)</li>
     *   <li>Transport should validate source before accepting</li>
     * </ul>
     * 
     * <strong>Timing:</strong>
     * Can be called before startListening() to set initial address,
     * or after transport is already running.
     * 
     * @param source the source of this address change (UPnP, interface, config, SSU)
     * @param ip the new external IP address (IPv4 or IPv6), may be null to indicate IPv4 failure or port-only change
     * @param port the new external port number, 0 if unknown or unchanged
     */
    public void externalAddressReceived(AddressSource source, byte[] ip, int port);

    /**
     *  Notify a transport of an external address change.
     *  This may be from a local interface, UPnP, a config change, etc.
     *  This should not be called if the ip didn't change
     *  (from that source's point of view), or is a local address.
     *  May be called multiple times for IPv4 or IPv6.
     *  The transport should also do its own checking on whether to accept
     *  notifications from this source.
     *
     *  This can be called after the transport is running.
     *
     *  TODO externalAddressRemoved(source, ip, port)
     *
     *  @param source defined in Transport.java
     *  @since 0.9.20
     */
    public void externalAddressRemoved(AddressSource source, boolean ipv6);

    /**
     *  Notify a transport of the results of trying to forward a port.
     *
     *  @param ip may be null
     *  @param port the internal port
     *  @param externalPort the external port, which for now should always be the same as
     *                      the internal port if the forwarding was successful.
     */
    public void forwardPortStatus(byte[] ip, int port, int externalPort, boolean success, String reason);

    /**
     * Get the internal port that transport wants forwarded via UPnP.
     * 
     * This method returns the preferred internal port that the
     * transport would like to have mapped to an external port through
     * UPnP port forwarding. This is different from the actual
     * listening port because UPnP may map a different external port.
     * 
     * <strong>Note:</strong>
     * This cannot be determined from getCurrentAddress() because:
     * <ul>
     *   <li>Transport must open the port before publishing address</li>
     *   <li>UPnP may map different external port</li>
     *   <li>External port is what gets advertised, not internal</li>
     * </ul>
     * 
     * @return preferred internal port for UPnP forwarding, -1 for no preference, or 0 for any port
     */
    public int getRequestedPort();

    /**
     * Set the event listener for transport-related notifications.
     * 
     * The listener will be notified of various transport events
     * such as message availability, peer status changes, and
     * transport state changes. Only one listener can be active
     * at a time.
     * 
     * @param listener the event listener to receive transport notifications
     */
    public void setListener(TransportEventListener listener);

    /**
     * Get the unique style identifier for this transport.
     * 
     * This method returns a string that uniquely identifies the
     * transport type (e.g., "NTCP", "UDP", "SSU"). This
     * is used for configuration, logging, and transport selection.
     * 
     * @return unique transport style identifier
     */
    public String getStyle();

    /**
     * Get list of peers that this transport has established connections with.
     * 
     * This method returns the current set of peer hashes that
     * have successfully completed connection establishment. These are
     * peers that the transport can actively communicate with.
     * 
     * <strong>Note:</strong> The returned list may or may not be
     * modifiable depending on implementation. Callers should not
     * modify the returned list directly.
     * 
     * @return list of established peer hashes, may be unmodifiable
     * @since 0.9.34
     */
    public List<Hash> getEstablished();

    public int countPeers();
    public int countActivePeers();
    public int countActiveSendPeers();

    /**
     * Get detailed peer count statistics by IP version and direction.
     * 
     * This method returns an array containing peer counts broken
     * down by IP version and connection direction. This provides
     * detailed visibility into transport usage patterns and
     * network composition.
     * 
     * <strong>Array Format:</strong>
     * <ul>
     *   <li>Version 1 (8 bytes): IPv4 inbound/outbound counts</li>
     *   <li>Version 2 (8 bytes): IPv4 inbound/outbound, IPv6 inbound/outbound counts</li>
     * </ul>
     * 
     * @return 8-byte array with peer counts:
     *         version 1: [ipv4_in, ipv4_out, ipv6_in, ipv6_out, 0, 0, 0, 0]
     *         version 2: [ipv4_in, ipv4_out, ipv6_in, ipv6_out, 0, 0, 0, 0]
     * @since 0.9.57
     */
    public int[] getPeerCounts();

    public boolean haveCapacity();
    public boolean haveCapacity(int pct);

    /**
     * Get list of clock skew measurements from peer communications.
     * 
     * This method returns a list of measured clock differences
     * (in milliseconds) between this router and various peers.
     * Clock skew is important for network time synchronization
     * and can affect message validation and routing.
     * 
     * <strong>Historical Note:</strong> This method previously
     * returned a Vector, now returns a List as of 0.9.46.
     * 
     * @return list of clock skew measurements in milliseconds,
     *         may be empty if no measurements available
     */
    public List<Long> getClockSkews();

    public List<String> getMostRecentErrorMessages();

    public void renderStatusHTML(Writer out, String urlBase, int sortFlags) throws IOException;

    /**
     * Get the current reachability status of this transport.
     * 
     * This method returns the transport's ability to establish
     * and maintain connections with other peers. The status indicates
     * whether the transport can successfully communicate through
     * firewalls, NAT, and network restrictions.
     * 
     * <strong>Historical Note:</strong> This method previously
     * returned a short value, now returns a Status enum as of 0.9.20.
     * 
     * @return current reachability status indicating transport's network accessibility
     */
    public Status getReachabilityStatus();

    /**
     * @deprecated unused
     */
    @Deprecated
    public void recheckReachability();

    /**
     *  @since 0.9.50 added to interface
     */
    public TransportUtil.IPv6Config getIPv6Config();

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @since 0.9.50 added to interface
     */
    public boolean isIPv4Firewalled();

    /**
     *  This returns true if the force-firewalled setting is configured, false otherwise.
     *
     *  @since 0.9.50 added to interface
     */
    public boolean isIPv6Firewalled();

    public boolean isBacklogged(Hash peer);

    /**
     * Check if peer was unreachable on last outbound attempt.
     * 
     * This method determines whether the last attempt to contact
     * the specified peer failed due to reachability issues.
     * This is specifically for outbound connection attempts only.
     * 
     * <strong>Important Notes:</strong>
     * <ul>
     *   <li>This is NOT reset if the peer contacts us successfully</li>
     *   <li>Status persists until peer is contacted or expires</li>
     *   <li>Used for connection retry decisions</li>
     *   <li>Different from isUnreachable() which checks current state</li>
     * </ul>
     * 
     * @param peer hash of the peer to check reachability for
     * @return true if peer was unreachable on last outbound attempt
     */
    public boolean wasUnreachable(Hash peer);

    public boolean isUnreachable(Hash peer);
    public boolean isEstablished(Hash peer);

    /**
     * Suggest that transport may disconnect from specified peer.
     * 
     * This method provides an advisory recommendation that the
     * transport should consider disconnecting from the specified peer.
     * The transport may choose to ignore this suggestion based on
     * its own criteria and current state.
     * 
     * <strong>Advisory Nature:</strong>
     * <ul>
     *   <li>Transport is not required to disconnect</li>
     *   <li>Used for peer management and load balancing</li>
     *   <li>May be called by router or other components</li>
     *   <li>Transport should evaluate based on its own state</li>
     * </ul>
     * 
     * @param peer hash of the peer that may be disconnected
     * @since 0.9.24
     */
    public void mayDisconnect(Hash peer);

    /**
     * Force immediate disconnection from specified peer.
     * 
     * This method commands the transport to immediately terminate
     * the connection to the specified peer. Unlike mayDisconnect(),
     * this is not advisory - the transport must comply and
     * disconnect as soon as possible.
     * 
     * <strong>Use Cases:</strong>
     * <ul>
     *   <li>Peer misbehavior or protocol violations</li>
     *   <li>Router shutdown or transport shutdown</li>
     *   <li>Network topology changes requiring disconnection</li>
     *   <li>Administrative or manual disconnection requests</li>
     * </ul>
     * 
     * @param peer hash of the peer to forcefully disconnect from
     * @since 0.9.38
     */
    public void forceDisconnect(Hash peer);
}
