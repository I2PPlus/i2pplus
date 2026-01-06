package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import net.i2p.util.Clock;

/**
 * Authorization grant proving a router/tunnel may receive messages for a destination.
 * 
 * <p>Lease represents the fundamental routing authorization in I2P:</p>
 * <ul>
 *   <li><strong>Authorization:</strong> Proves specific router can receive messages</li>
 *   <li><strong>Time-Bound:</strong> Valid only for specified time period</li>
 *   <li><strong>Destination-Specific:</strong> Authorizes service for particular destination</li>
 *   <li><strong>Tunnel Endpoint:</strong> Identifies specific tunnel on gateway router</li>
 * </ul>
 * 
 * <p><strong>Key Components:</strong></p>
 * <ul>
 *   <li><strong>Gateway:</strong> {@link Hash} identifying the router hosting the tunnel</li>
 *   <li><strong>Tunnel ID:</strong> {@link TunnelId} identifying specific tunnel on gateway</li>
 *   <li><strong>End Time:</strong> {@code long} timestamp when lease expires</li>
 * </ul>
 * 
 * <p><strong>Authorization Model:</strong></p>
 * <ul>
 *   <li><strong>Proof of Authority:</strong> Destination authorizes specific router</li>
 *   <li><strong>Time Limitation:</strong> Authorization expires after set duration</li>
 *   <li><strong>Tunnel Specificity:</strong> Authorizes particular tunnel, not all tunnels</li>
 *   <li><strong>Renewable:</strong> Leases can be refreshed before expiration</li>
 * </ul>
 * 
 * <p><strong>Usage in LeaseSet:</strong></p>
 * <ul>
 *   <li><strong>Multiple Leases:</strong> Destinations typically have several concurrent leases</li>
 *   <li><strong>Load Balancing:</strong> Multiple leases distribute message load</li>
 *   <li><strong>Redundancy:</strong> Backup tunnels if primary becomes unavailable</li>
 *   <li><strong>Mobility:</strong> Leases updated as destination moves between routers</li>
 * </ul>
 * 
 * <p><strong>Network Operations:</strong></p>
 * <ul>
 *   <li><strong>Message Routing:</strong> Routers use leases to forward messages</li>
 *   <li><strong>Tunnel Selection:</strong> Senders choose from available leases</li>
 *   <li><strong>Expiration Handling:</strong> Leases removed when they expire</li>
 *   <li><strong>LeaseSet Publication:</strong> Leases published to network database</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Verification:</strong> Verify lease authenticity via LeaseSet signature</li>
 *   <li><strong>Expiration:</strong> Ensure leases are still valid before use</li>
 *   <li><strong>Gateway Trust:</strong> Verify gateway router is trusted</li>
 *   <li><strong>Tunnel Security:</strong> Ensure tunnel endpoint is secure</li>
 * </ul>
 * 
 * <p><strong>Performance Aspects:</strong></p>
 * <ul>
 *   <li><strong>Efficient Lookup:</strong> Hash-based gateway identification</li>
 *   <li><strong>Quick Expiration:</strong> Timestamp comparison for validity checks</li>
 *   <li><strong>Compact Storage:</strong> Minimal data structure for network transmission</li>
 *   <li><strong>Fast Comparison:</strong> Optimized equals() and hashCode() methods</li>
 * </ul>
 * 
 * <p><strong>Related Structures:</strong></p>
 * <ul>
 *   <li>{@link LeaseSet} - Container for multiple leases</li>
 *   <li>{@link Lease2} - Enhanced version with 4-byte timestamps</li>
 *   <li>{@link MetaLease} - References other LeaseSets instead of tunnels</li>
 *   <li>{@link TunnelId} - Tunnel identifier on gateway router</li>
 * </ul>
 *
 * @author jrandom
 */
public class Lease extends DataStructureImpl {
    protected Hash _gateway;
    protected TunnelId _tunnelId;
    protected long _end;

    public Lease() {}

    /** Retrieve the router at which the destination can be contacted
     * @return identity of the router acting as a gateway
     */
    public Hash getGateway() {return _gateway;}

    /** Configure the router at which the destination can be contacted
     * @param ident router acting as the gateway
     */
    public void setGateway(Hash ident) {_gateway = ident;}

    /** Tunnel on the gateway to communicate with
     * @return tunnel ID
     */
    public TunnelId getTunnelId() {return _tunnelId;}

    /** Configure the tunnel on the gateway to communicate with
     * @param id tunnel ID
     */
    public void setTunnelId(TunnelId id) {_tunnelId = id;}

    /**
     * @deprecated use getEndTime()
     */
    @Deprecated
    public Date getEndDate() {return new Date(_end);}

    /**
     * @deprecated use setEndDate(long)
     */
    @Deprecated
    public void setEndDate(Date date) {_end = date.getTime();}

    /**
      *  Gets the lease end time.
      *
      * @since 0.9.48
      */
    public long getEndTime() {return _end;}

    /**
      *  Sets the lease end date.
      *
      * @since 0.9.48
      */
    public void setEndDate(long date) {_end = date;}

    /** has this lease already expired? */
    public boolean isExpired() {return isExpired(0);}

    /** has this lease already expired (giving allowing up the fudgeFactor milliseconds for clock skew)? */
    public boolean isExpired(long fudgeFactor) {
        return _end < Clock.getInstance().now() - fudgeFactor;
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        //_gateway = new Hash();
        //_gateway.readBytes(in);
        _gateway = Hash.create(in);
        _tunnelId = new TunnelId();
        _tunnelId.readBytes(in);
        _end = DataHelper.readLong(in, 8);
    }

    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_gateway == null) || (_tunnelId == null)) {
            throw new DataFormatException("Not enough data to write out a Lease");
        }
        _gateway.writeBytes(out);
        _tunnelId.writeBytes(out);
        DataHelper.writeLong(out, 8, _end);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {return true;}
        if ((object == null) || !(object instanceof Lease)) {return false;}
        Lease lse = (Lease) object;
        return _end == lse.getEndTime() &&
                       DataHelper.eq(_tunnelId, lse.getTunnelId()) &&
                       DataHelper.eq(_gateway, lse.getGateway());

    }

    @Override
    public int hashCode() {
        return (int) _end ^ DataHelper.hashCode(_gateway) ^ DataHelper.hashCode(_tunnelId);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("\n* Gateway: [").append(_gateway.toBase64().substring(0,6) + "]");
        buf.append(" -> Expires: ").append(DataHelper.formatTime(_end));
        buf.append(" [TunnelID ").append(_tunnelId).append("]");
        return buf.toString();
    }

}
