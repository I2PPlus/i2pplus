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
 * Interface for transforming a hash into the key under which it is stored in
 * the network database (KBucketSet).
 *
 * <p>What the interface defines:</p>
 * <ul>
 *   <li>{@link #getRoutingKey(Hash)} maps a hash to a routing key. The
 *       transformation, and whether it is a function of changing network-wide
 *       "mod data", is up to the implementation - this interface says nothing
 *       about reversibility, caching, or cost.</li>
 *   <li>{@link #getLastChanged()} reports when that mod data last changed, so
 *       a caller holding a previously derived key can tell whether it is
 *       stale and needs to be regenerated.</li>
 *   <li>{@link #getInstance()} returns the generator of the global context,
 *       which is null unless that context is a router context.</li>
 * </ul>
 *
 * <p>Availability:</p>
 * <ul>
 *   <li>Not available in a plain I2PAppContext - I2PAppContext.routingKeyGenerator()
 *       returns null there, because only a router holds a network database.</li>
 *   <li>The implementation is {@code net.i2p.data.router.RouterKeyGenerator},
 *       which appends the current GMT date to the hash and hashes the result,
 *       rotating daily at midnight GMT.</li>
 * </ul>
 *
 * @since 0.9.16 moved from net.i2p.data.RoutingKeyGenerator
 */
public abstract class RoutingKeyGenerator {

    /**
     * Generator for this context.
     *
     * @return null in I2PAppContext; non-null in RouterContext.
     */
    public static RoutingKeyGenerator getInstance() {
        return I2PAppContext.getGlobalContext().routingKeyGenerator();
    }

    /**
     *  The version of the current (today's) mod data.
     *  Use to determine if the routing key should be regenerated.
     *
     *  @return the last changed
     */
    public abstract long getLastChanged();

    /**
     * Routing key for a key.
     *
     *  The result depends on the mod data of the implementing generator, so
     *  compare getLastChanged() before reusing a previously derived key.
     *
     *  @param origKey non-null
     *  @return the routing key
     *  @throws IllegalArgumentException if origKey is null
     */
    public abstract Hash getRoutingKey(Hash origKey);
}
