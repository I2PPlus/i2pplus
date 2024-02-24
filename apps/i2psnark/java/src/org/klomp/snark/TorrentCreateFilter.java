/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

/**
 * A structure for torrent creation filters
 * @since 0.9.62+
 */
public class TorrentCreateFilter {

    public final String name;
    public final String filterPattern;
    public final boolean isDefault;

    public TorrentCreateFilter(String name, String filterPattern, boolean isDefault) {
        this.name = name;
        this.filterPattern = filterPattern;
        this.isDefault = isDefault;
    }
}
