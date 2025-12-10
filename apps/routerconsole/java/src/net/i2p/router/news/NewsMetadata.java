package net.i2p.router.news;

import java.util.List;
import net.i2p.data.DataHelper;
import net.i2p.util.VersionComparator;

/**
 * Data structure for I2P router news feed metadata and update information.
 * <p>
 * Contains standard Atom feed metadata fields including title, subtitle,
 * and update timestamps, along with I2P-specific release information
 * such as version requirements and update sources.
 * <p>
 * Provides structured access to release data with nested classes for
 * individual releases and their associated updates. Supports version
 * comparison and validation for update management.
 * <p>
 * All String and List fields may be null to accommodate optional
 * metadata elements in news feeds.
 *
 * @since 0.9.17
 */
public class NewsMetadata {
    // Standard Atom feed metadata
    public String feedTitle;
    public String feedSubtitle;
    public String feedID;
    public long feedUpdated;

    /** I2P metadata */
    public List<Release> releases;

    /**
     * Represents a software release with version requirements and update sources.
     * <p>
     * Contains release metadata including publication date, version
     * requirements, and available update sources. Supports version
     * comparison for determining latest releases and update ordering.
     * <p>
     * All update source lists may be empty if no sources are available
     * for this release. Provides structured access to release information
     * for update management and version checking.
     *
     * @since 0.9.17
     */
    public static class Release implements Comparable<Release> {
        public long date;
        public String minVersion;
        public String minJavaVersion;
        public String i2pVersion;
        public List<Update> updates;

        @Override
        public int compareTo(Release other) {
            // Sort latest version first.
            return VersionComparator.comp(other.i2pVersion, i2pVersion);
        }

        /**
         *  For findbugs.
         *  Warning, not a complete comparison.
         *  Must be enhanced before using in a Map or Set.
         *  @since 0.9.21
         */
        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof Release))
                return false;
            Release r = (Release) o;
            return DataHelper.eq(i2pVersion, r.i2pVersion);
        }

        /**
         *  For findbugs.
         *  @since 0.9.21
         */
        @Override
        public int hashCode() {
            return DataHelper.hashCode(i2pVersion);
        }
    }

    /**
     * Represents an update source with multiple distribution methods.
     * <p>
     * Defines update types and their associated distribution sources
     * including torrent, clearnet, SSL, and I2P network URLs.
     * Supports type-based ordering for update priority management.
     * <p>
     * All source lists may be empty if no sources are available
     * for this update type.
     *
     * @since 0.9.52
     */
    public static class Update implements Comparable<Update> {
        public String type;
        public String torrent;
        /**
         *  Stored as of 0.9.52, but there is no registered handler
         */
        public List<String> clearnet;
        /**
         *  Stored as of 0.9.52, but there is no registered handler
         */
        public List<String> ssl;
        /**
         *  In-net URLs
         *  @since 0.9.52
         */
        public List<String> i2pnet;

        @Override
        public int compareTo(Update other) {
            return getTypeOrder() - other.getTypeOrder();
        }

        /** lower is preferred */
        protected int getTypeOrder() {
            if ("su3".equalsIgnoreCase(type))
                return 1;
            else if ("su2".equalsIgnoreCase(type))
                return 2;
            else
                return 3;
        }

        /**
         *  For findbugs.
         *  Warning, not a complete comparison.
         *  Must be enhanced before using in a Map or Set.
         *  @since 0.9.21
         */
        @Override
        public boolean equals(Object o) {
            if (o == null)
                return false;
            if (!(o instanceof Update))
                return false;
            Update u = (Update) o;
            return getTypeOrder() == u.getTypeOrder();
        }

        /**
         *  For findbugs.
         *  @since 0.9.21
         */
        @Override
        public int hashCode() {
            return getTypeOrder();
        }
    }
}
