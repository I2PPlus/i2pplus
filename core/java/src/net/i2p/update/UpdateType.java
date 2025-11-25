package net.i2p.update;

/**
 *  What to update
 *
 *  @since 0.9.4
 */
public enum UpdateType {
    /** Dummy: internal use only */
    TYPE_DUMMY,
    /** News and announcements */
    NEWS,
    /** Signed router updates */
    ROUTER_SIGNED,
    /** Unsigned router updates */
    ROUTER_UNSIGNED,
    /** Plugin updates */
    PLUGIN,
    /** GeoIP database updates */
    GEOIP,
    /** Blocklist updates */
    BLOCKLIST,
    /** unused */
    RESEED,
    /** unused */
    HOMEPAGE,
    /** unused */
    ADDRESSBOOK,
    /** Signed router updates in SU3 format @since 0.9.9 */
    ROUTER_SIGNED_SU3,
    /** News updates in SU3 format @since 0.9.15 */
    NEWS_SU3,
    /** Development router updates in SU3 format @since 0.9.17 */
    ROUTER_DEV_SU3,
    /** API updates @since 0.9.53 */
    API
}
