package net.i2p.router.util;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.math.BigInteger;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

/**
 * Utility for calculating XOR distance between router hashes.
 * <p>
 * Computes the XOR distance between two router hashes by
 * performing a bitwise XOR operation on the hash data.
 * Used in Kademlia-based distributed hash tables for determining
 * key proximity and routing decisions.
 * <p>
 * Returns the result as a BigInteger to handle the full
 * 256-bit hash space without overflow issues. The distance
 * metric is fundamental to Kademlia DHT operations including
 * key lookup, peer selection, and network topology analysis.
 *
 * @since 0.7.14
 */
public class HashDistance {

    public static BigInteger getDistance(Hash targetKey, Hash routerInQuestion) {
        // plain XOR of the key and router
        byte diff[] = DataHelper.xor(routerInQuestion.getData(), targetKey.getData());
        return new BigInteger(1, diff);
    }
}
