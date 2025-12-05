package net.i2p.i2pcontrol;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Version information and API compatibility for I2PControl interface.
 * Manages supported API versions and compatibility checks.
 */
public class I2PControlVersion {
    /** The current version of I2PControl */
    public final static String VERSION = "0.12.0";

    /** The current version of the I2PControl API being primarily being implemented */
    public final static int API_VERSION = 1;

    /** The supported versions of the I2PControl API */
    public final static Set<Integer> SUPPORTED_API_VERSIONS;

    static {
        Set<Integer> mutableSet = new HashSet<Integer>();
        mutableSet.add(1);
        SUPPORTED_API_VERSIONS = Collections.unmodifiableSet(mutableSet);
    }
}
