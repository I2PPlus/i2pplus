package net.i2p.crypto.eddsa.math;

import net.i2p.crypto.eddsa.Utils;

/** Mathematical constants for EdDSA operations */
final class Constants {
    /** The field element 0, little-endian. */
    public static final byte[] ZERO = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    /** The field element 1, little-endian. */
    public static final byte[] ONE = Utils.hexToBytes("0100000000000000000000000000000000000000000000000000000000000000");
    /** The field element 2, little-endian. */
    public static final byte[] TWO = Utils.hexToBytes("0200000000000000000000000000000000000000000000000000000000000000");
    /** The field element 4, little-endian. */
    public static final byte[] FOUR = Utils.hexToBytes("0400000000000000000000000000000000000000000000000000000000000000");
    /** The field element 5, little-endian. */
    public static final byte[] FIVE = Utils.hexToBytes("0500000000000000000000000000000000000000000000000000000000000000");
    /** The field element 8, little-endian. */
    public static final byte[] EIGHT = Utils.hexToBytes("0800000000000000000000000000000000000000000000000000000000000000");
}
