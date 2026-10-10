/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

/**
 * Represents a known BitTorrent tracker with its configuration and capabilities.
 *
 * <p>This immutable class stores tracker information including:
 *
 * <ul>
 *   <li>Human-readable name for display</li>
 *   <li>Announce URL for torrent registrations</li>
 *   <li>Base web URL for tracker web interfaces</li>
 *   <li>Capability flags (e.g., supports detailed responses)</li>
 * </ul>
 *
 * <p>Trackers are used by TrackerClient to announce torrent status and discover peers.
 *
 * @since 0.9.1
 */
public class Tracker {

    /** The tracker's host name, also used to infer whether it serves scrape details. */
    public final String name;
    /** The URL a client posts its infohash to for peer discovery. */
    public final String announceURL;
    /** The tracker's web site, shown as a link in the config page; null when it has none. */
    public final String baseURL;
    /** True for the hosts known to serve a per-torrent details page. */
    public final boolean supportsDetails;

    /**
     * Create a tracker.
     *
     * @param name the tracker's host name, also used to infer whether it serves scrape details
     * @param announceURL the URL a client posts its infohash to for peer discovery
     * @param baseURL The web site, may be null
     */
    public Tracker(String name, String announceURL, String baseURL) {
        this.name = name;
        this.announceURL = announceURL;
        this.baseURL = baseURL;
        this.supportsDetails =
                name.contains("tracker2.postman.i2p") || name.contains("torrfreedom.i2p");
    }
}
