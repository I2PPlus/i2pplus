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

    public final String name;
    public final String announceURL;
    public final String baseURL;
    public final boolean supportsDetails;

    /**
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
