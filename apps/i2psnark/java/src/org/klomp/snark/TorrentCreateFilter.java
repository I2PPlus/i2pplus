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

    /**
     * name.
     */
    public final String name;
    /**
     * filterPattern.
     */
    public final String filterPattern;
    /**
     * filterType.
     */
    public final String filterType;
    /**
     * isDefault.
     */
    public final boolean isDefault;

    /**
     * Creates a new torrent creation filter.
     *
     * @param name the display name for this filter
     * @param filterPattern the pattern to match against
     * @param filterType the type of filter (e.g., "include", "exclude")
     * @param isDefault true if this is the default filter
     */
    public TorrentCreateFilter(
            String name, String filterPattern, String filterType, boolean isDefault) {
        this.name = name;
        this.filterPattern = filterPattern;
        this.filterType = filterType;
        this.isDefault = isDefault;
    }
}
