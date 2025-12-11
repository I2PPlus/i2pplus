package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Implementation of MetaLease as specified in 
 * <a href="https://geti2p.net/spec/proposals/123-new-netdb-entries">Proposal 123: New NetDb Entries</a>.
 * 
 * <p>MetaLease extends the Lease concept to reference other LeaseSets rather than tunnel endpoints:</p>
 * <ul>
 *   <li>Points to another LeaseSet via its hash instead of a gateway router</li>
 *   <li>Enables advanced routing and load balancing strategies</li>
 *   <li>Includes cost and type information for selection algorithms</li>
 *   <li>Fixed length of 40 bytes for consistency with Lease2</li>
 * </ul>
 * 
 * <p><strong>Structure:</strong></p>
 * <ul>
 *   <li><strong>LeaseSet Hash:</strong> 32-byte hash of the target LeaseSet (available via {@link #getGateway()})</li>
 *   <li><strong>Cost:</strong> 4-byte cost value for routing decisions</li>
 *   <li><strong>Type:</strong> 4-byte type identifier for the referenced LeaseSet</li>
 * </ul>
 * 
 * <p><strong>Key Differences from Lease:</strong></p>
 * <ul>
 *   <li><strong>Reference:</strong> Points to LeaseSet hash, not gateway router</li>
 *   <li><strong>TunnelId:</strong> Not supported (throws {@link UnsupportedOperationException})</li>
 *   <li><strong>Metadata:</strong> Includes cost and type for intelligent selection</li>
 *   <li><strong>Usage:</strong> Used within MetaLeaseSet for indirect routing</li>
 * </ul>
 * 
 * <p><strong>Usage Scenarios:</strong></p>
 * <ul>
 *   <li><strong>Load Balancing:</strong> Distribute traffic across multiple LeaseSets</li>
 *   <li><strong>Geographic Routing:</strong> Select LeaseSets based on location or performance</li>
 *   <li><strong>Fallback Strategies:</strong> Provide alternative LeaseSets for redundancy</li>
 *   <li><strong>Service Aggregation:</strong> Combine multiple services under one destination</li>
 * </ul>
 * 
 * <p><strong>Selection Criteria:</strong></p>
 * <ul>
 *   <li><strong>Cost:</strong> Lower values indicate preferred routes</li>
 *   <li><strong>Type:</strong> Application-specific categorization</li>
 *   <li><strong>Hash:</strong> Uniquely identifies the target LeaseSet</li>
 * </ul>
 * 
 * <p><strong>Implementation Status:</strong> PRELIMINARY - Subject to change as proposal evolves</p>
 *
 * @since 0.9.38
 */
public class MetaLease extends Lease {

    public static final int LENGTH = 40;

    private int _cost;
    private int _type;

    public int getCost() {
        return _cost;
    }

    public void setCost(int cost) {
        _cost = cost;
    }

    public int getType() {
        return _type;
    }

    public void setType(int type) {
        _type = type;
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public TunnelId getTunnelId() {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public void setTunnelId(TunnelId id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _gateway = Hash.create(in);
        // flags
        DataHelper.skip(in, 2);
        _type = in.read();
        _cost = in.read();
        _end = DataHelper.readLong(in, 4) * 1000;
    }

    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_gateway == null)
            throw new DataFormatException("Not enough data to write out a Lease");
        _gateway.writeBytes(out);
        // flags
        DataHelper.writeLong(out, 2, 0);
        out.write(_type);
        out.write(_cost);
        DataHelper.writeLong(out, 4, _end / 1000);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof MetaLease)) return false;
        MetaLease lse = (MetaLease) object;
        return _end == lse.getEndTime()
               && _type == lse._type
               && _cost == lse._cost
               && DataHelper.eq(_gateway, lse.getGateway());
    }

    @Override
    public int hashCode() {
        return (int) _end ^ DataHelper.hashCode(_gateway)
               ^ _cost;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[Meta Lease: ");
        buf.append("\n\tEnd Date: ").append(DataHelper.formatTime(_end));
        buf.append("\n\tTarget: ").append(_gateway);
        buf.append("\n\tCost: ").append(_cost);
        buf.append("\n\tType: ").append(_type);
        buf.append("]");
        return buf.toString();
    }
}
