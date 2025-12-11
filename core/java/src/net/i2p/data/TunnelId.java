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

/**
 * Tunnel identifier for routing messages through a sequence of routers in I2P.
 * 
 * <p>TunnelId provides unique identification within tunnel routing:</p>
 * <ul>
 *   <li><strong>Local Uniqueness:</strong> Must be unique on each router in tunnel</li>
 *   <li><strong>4-Byte Value:</strong> 32-bit identifier (1 to 0xffffffff)</li>
 *   <li><strong>Random Generation:</strong> Typically generated from cryptographically secure random</li>
 *   <li><strong>Routing Coordination:</strong> Prevents message delivery to wrong tunnels</li>
 * </ul>
 * 
 * <p><strong>Constraints and Validation:</strong></p>
 * <ul>
 *   <li><strong>Minimum Value:</strong> Must be greater than zero (ID > 0)</li>
 *   <li><strong>Maximum Value:</strong> Limited to 0xffffffff (32-bit unsigned integer)</li>
 *   <li><strong>Special Case:</strong> Zero reserved for direct replies in DatabaseStoreMessage</li>
 *   <li><strong>Uniqueness:</strong> Router must enforce uniqueness across its tunnels</li>
 * </ul>
 * 
 * <p><strong>Usage in I2P:</strong></p>
 * <ul>
 *   <li><strong>Tunnel Routing:</strong> Messages forwarded through tunnel chain</li>
 *   <li><strong>Message Delivery:</strong> Ensures messages reach correct tunnel endpoint</li>
 *   <li><strong>Tunnel Management:</strong> Creation, configuration, and teardown</li>
 *   <li><strong>Load Balancing:</strong> Multiple tunnels for traffic distribution</li>
 *   <li><strong>Client Communication:</strong> I2CP tunnel establishment and management</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li><strong>Efficient Storage:</strong> Compact 4-byte representation</li>
 *   <li><strong>Fast Comparison:</strong> Optimized equals() and hashCode() methods</li>
 *   <li><strong>Minimal Overhead:</strong> No object inheritance since 0.9.48</li>
 *   <li><strong>Memory Efficiency:</strong> Primitive long storage</li>
 * </ul>
 * 
 * <p><strong>Security Aspects:</strong></p>
 * <ul>
 *   <li><strong>ID Randomness:</strong> Use cryptographically secure random generation</li>
 *   <li><strong>ID Unpredictability:</strong> Prevent tunnel ID guessing attacks</li>
 *   <li><strong>Collision Avoidance:</strong> Local uniqueness prevents routing confusion</li>
 *   <li><strong>Isolation:</strong> Different tunnels have separate ID spaces</li>
 * </ul>
 * 
 * <p><strong>Implementation Notes:</strong></p>
 * <ul>
 *   <li><strong>Space Optimization:</strong> Does not extend DataStructureImpl since 0.9.48</li>
 *   <li><strong>External Use:</strong> Not recommended for external use, subject to change</li>
 *   <li><strong>Thread Safety:</strong> Immutable objects are inherently thread-safe</li>
 *   <li><strong>Validation:</strong> Constructor enforces valid ID range</li>
 * </ul>
 * 
 * <p><strong>Constants:</strong></p>
 * <ul>
 *   <li>{@link #MAX_ID_VALUE} - Maximum allowed tunnel ID (0xffffffff)</li>
 * </ul>
 *
 * @author jrandom
 */
public class TunnelId {
    private long _tunnelId;

    public static final long MAX_ID_VALUE = 0xffffffffL;

    public TunnelId() {
        _tunnelId = -1;
    }

    /**
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     */
    public TunnelId(long id) {
        setTunnelId(id);
    }

    public long getTunnelId() { return _tunnelId; }

    /**
     *  @param id 1 to 0xffffffff
     *  @throws IllegalArgumentException if less than or equal to zero or greater than max value
     */
    public void setTunnelId(long id) {
        if (id <= 0 || id > MAX_ID_VALUE)
            throw new IllegalArgumentException("Bad Id " + id);
        _tunnelId = id;
    }

    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _tunnelId = DataHelper.readLong(in, 4);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        // writeLong() will throw DFE on negative value
        //if (_tunnelId < 0) throw new DataFormatException("Invalid tunnel ID: " + _tunnelId);
        DataHelper.writeLong(out, 4, _tunnelId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if ( (obj == null) || !(obj instanceof TunnelId))
            return false;
        return _tunnelId == ((TunnelId)obj)._tunnelId;
    }

    @Override
    public int hashCode() {
        return (int)_tunnelId;
    }

    @Override
    public String toString() { return String.valueOf(_tunnelId); }
}
