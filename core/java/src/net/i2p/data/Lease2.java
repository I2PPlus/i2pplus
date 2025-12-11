package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Implementation of Lease2 as specified in 
 * <a href="https://geti2p.net/spec/proposals/123-new-netdb-entries">Proposal 123: New NetDb Entries</a>.
 * 
 * <p>Lease2 extends the original Lease format with enhanced timestamp resolution:</p>
 * <ul>
 *   <li>Uses 4-byte timestamps instead of 8-byte for space efficiency</li>
 *   <li>Timestamps have 1-second resolution (stored as seconds since epoch)</li>
 *   <li>Fixed length of 40 bytes for consistent serialization</li>
 *   <li>Designed for use with LeaseSet2 and its variants</li>
 * </ul>
 * 
 * <p><strong>Structure:</strong></p>
 * <ul>
 *   <li><strong>Gateway:</strong> 32-byte Hash of the gateway router</li>
 *   <li><strong>Tunnel ID:</strong> 4-byte tunnel identifier</li>
 *   <li><strong>End Time:</strong> 4-byte timestamp (seconds since epoch)</li>
 * </ul>
 * 
 * <p><strong>Key Differences from Lease:</strong></p>
 * <ul>
 *   <li>Timestamp resolution: 4 bytes vs 8 bytes in original Lease</li>
 *   <li>Time representation: Seconds vs milliseconds</li>
 *   <li>Fixed total length: 40 bytes</li>
 *   <li>Optimized for LeaseSet2 serialization format</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li>Used within LeaseSet2, MetaLeaseSet, and EncryptedLeaseSet</li>
 *   <li>Provides more compact representation for large numbers of leases</li>
 *   <li>Maintains compatibility with existing tunnel infrastructure</li>
 * </ul>
 * 
 * <p><strong>Implementation Status:</strong> PRELIMINARY - Subject to change as the proposal evolves</p>
 *
 * @since 0.9.38
 */
public class Lease2 extends Lease {

    public static final int LENGTH = 40;

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _gateway = Hash.create(in);
        _tunnelId = new TunnelId();
        _tunnelId.readBytes(in);
        _end = DataHelper.readLong(in, 4) * 1000;
    }

    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_gateway == null) || (_tunnelId == null)) {
            throw new DataFormatException("Not enough data to write out a Lease");
        }
        _gateway.writeBytes(out);
        _tunnelId.writeBytes(out);
        DataHelper.writeLong(out, 4, _end / 1000);
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof Lease2)) return false;
        Lease2 lse = (Lease2) object;
        return _end == lse.getEndTime() &&
                       DataHelper.eq(_tunnelId, lse.getTunnelId()) &&
                       DataHelper.eq(_gateway, lse.getGateway());
    }

    @Override
    public int hashCode() {
        return (int) _end ^ DataHelper.hashCode(_gateway) ^ (int) _tunnelId.getTunnelId();
    }
}
