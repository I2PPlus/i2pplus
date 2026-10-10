package net.i2p.i2ptunnel.access;

import java.io.IOException;
import java.util.Map;
import net.i2p.data.Base32;
import net.i2p.data.Hash;

/**
 * Base class for filter definition elements.
 *
 * @since 0.9.40
 */
abstract class FilterDefinitionElement {

    /**
     * The access threshold this element's destinations are subject to.
     */
    protected final Threshold threshold;

    /**
     * Create an element that applies a single threshold to the destinations it names.
     *
     * @param threshold the threshold
     */
    FilterDefinitionElement(Threshold threshold) {
        this.threshold = threshold;
    }

    /**
     * Updates the provided map with the hash(es) of remote destinations
     * mentioned in this element
     *
     * @param map the destination-to-tracker map the discovered hashes are added to
     * @throws IOException if the destinations this element names cannot be read,
     * as when a file-backed element cannot be opened
     */
    abstract void update(Map<Hash, DestTracker> map) throws IOException;

    /**
     * The access limit applied to the destinations this element names.
     *
     * @return the threshold
     */
    Threshold getThreshold() {
        return threshold;
    }

    /**
     * Utility method to create a Hash object from a .b32 string
     *
     * @param b32 the 60 character ".b32.i2p" address to decode
     * @return the destination hash the address names
     * @throws InvalidDefinitionException if the string is not a well formed .b32.i2p
     * address, or its base 32 payload does not decode
     */
    protected static Hash fromBase32(String b32) throws InvalidDefinitionException {
        if (b32.length() != 60)
            throw new InvalidDefinitionException("Invalid b32 " + b32);
        if (!b32.endsWith(".b32.i2p"))
            throw new InvalidDefinitionException("Invalid b32 " + b32);
        String s = b32.substring(0, 52);
        byte[] b = Base32.decode(s);
        if (b == null)
            throw new InvalidDefinitionException("Invalid b32 " + b32);
        return Hash.create(b);
    }
}
