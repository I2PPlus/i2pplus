/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

import java.io.Serializable;

/**
 * A structure for torrent creation filters
 *
 * @since 0.9.62+
 */
public class TorrentCreateFilter implements Serializable {

    private static final long serialVersionUID = -4345254904315868015L;

    /** Label shown in the create-torrent filter list, such as ".nfo files". */
    public final String name;
    /** The filename fragment tested against each file when a torrent is built. */
    public final String filterPattern;
    /** How the fragment is tested: "starts_with", "contains" or "ends_with". */
    public final String filterType;
    /** True to pre-check this filter in the create-torrent form. */
    public final boolean isDefault;

    /**
     * Creates a new torrent creation filter.
     *
     * @param name the label shown in the create-torrent filter list
     * @param filterPattern the filename fragment matched against each file
     * @param filterType the match mode: "starts_with", "contains" or "ends_with"
     * @param isDefault true to pre-check this filter in the create-torrent form
     */
    public TorrentCreateFilter(
            String name, String filterPattern, String filterType, boolean isDefault) {
        this.name = name;
        this.filterPattern = filterPattern;
        this.filterType = filterType;
        this.isDefault = isDefault;
    }
}
