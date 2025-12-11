package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;

/**
 * Interface for transforming hashes into routing keys for I2P network database operations.
 * 
 * <p>RoutingKeyGenerator provides hash transformation for distributed hash table routing:</p>
 * <ul>
 *   <li><strong>Hash Transformation:</strong> Consistent munging of hashes into routing keys</li>
 *   <li><strong>Mod Data:</strong> Transformation parameters updated periodically</li>
 *   <li><strong>DHT Routing:</strong> Enables efficient key-based lookup in network database</li>
 *   <li><strong>Load Distribution:</strong> Spreads storage and lookup load across key space</li>
 * </ul>
 * 
 * <p><strong>Key Operations:</strong></p>
 * <ul>
 *   <li>{@link #getRoutingKey(Hash)} - Transform hash to routing key</li>
 *   <li>{@link #getLastChanged()} - Get current mod data version</li>
 *   <li>{@link #getInstance()} - Get generator for current context</li>
 * </ul>
 * 
 * <p><strong>Transformation Process:</strong></p>
 * <ul>
 *   <li><strong>Consistent Algorithm:</strong> Same transformation applied to all hashes</li>
 *   <li><strong>Mod Data Input:</strong> Current network parameters affect transformation</li>
 *   <li><strong>Deterministic:</strong> Same hash always produces same routing key</li>
 *   <li><strong>Reversible:</strong> Process can be inverted for analysis</li>
 * </ul>
 * 
 * <p><strong>Context Availability:</strong></p>
 * <ul>
 *   <li><strong>Router Context:</strong> Available only in RouterContext, not I2PAppContext</li>
 *   <li><strong>Implementation:</strong> Concrete implementation in RouterKeyGenerator</li>
 *   <li><strong>Client Limitation:</strong> Not available for client-only operations</li>
 * </ul>
 * 
 * <p><strong>Evolution:</strong></p>
 * <ul>
 *   <li><strong>Pre-0.9.16:</strong> Full implementation with transformation logic</li>
 *   <li><strong>Post-0.9.16:</strong> Interface with router-specific implementation</li>
 *   <li><strong>Separation:</strong> Core interface in data package, implementation in router</li>
 * </ul>
 * 
 * <p><strong>Usage in I2P:</strong></p>
 * <ul>
 *   <li><strong>NetDb Storage:</strong> Keys determine database storage location</li>
 *   <li><strong>Floodfill Operations:</strong> Routing keys used for network distribution</li>
 *   <li><strong>Lookup Efficiency:</strong> Fast key-based data retrieval</li>
 *   <li><strong>Load Balancing:</strong> Distributes network load across key space</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Key Distribution:</strong> Prevents targeted attacks on specific keys</li>
 *   <li><strong>Load Balancing:</strong> Avoids hotspots in network database</li>
 *   <li><strong>Sybil Resistance:</strong> Makes targeted attacks more difficult</li>
 *   <li><strong>Mod Data Rotation:</strong> Periodic changes prevent long-term attacks</li>
 * </ul>
 * 
 * <p><strong>Performance Aspects:</strong></p>
 * <ul>
 *   <li><strong>Efficient Lookup:</strong> O(1) key-based database access</li>
 *   <li><strong>Uniform Distribution:</strong> Spreads load across all network nodes</li>
 *   <li><strong>Cache Optimization:</strong> Hot routing keys cached for fast access</li>
 *   <li><strong>Minimal Computation:</strong> Fast transformation algorithm</li>
 * </ul>
 */
public abstract class RoutingKeyGenerator {

    /**
     * Get the generator for this context.
     *
     * @return null in I2PAppContext; non-null in RouterContext.
     */
    public static RoutingKeyGenerator getInstance() {
        return I2PAppContext.getGlobalContext().routingKeyGenerator();
    }

    /**
     *  The version of the current (today's) mod data.
     *  Use to determine if the routing key should be regenerated.
     */
    public abstract long getLastChanged();

    /**
     * Get the routing key for a key.
     *
     * @throws IllegalArgumentException if origKey is null
     */
    public abstract Hash getRoutingKey(Hash origKey);

}
