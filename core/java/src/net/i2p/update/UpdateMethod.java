package net.i2p.update;

/**
 *  Transport mechanism for getting something.
 *
 *  @since 0.9.4
 */
public enum UpdateMethod {
    /** Dummy: internal use only */
    METHOD_DUMMY,
    /** HTTP via .i2p or outproxy */
    HTTP,
    /** Direct HTTP via clearnet */
    HTTP_CLEARNET,
    /** Direct HTTPS via clearnet */
    HTTPS_CLEARNET,
    /** BitTorrent protocol */
    TORRENT,
    /** Gnutella protocol */
    GNUTELLA,
    /** IMule protocol */
    IMULE,
    /** Tahoe-LAFS protocol */
    TAHOE_LAFS,
    /** Debian package manager */
    DEBIAN,
    /** Local file */
    FILE
}
