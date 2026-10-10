package com.maxmind.db;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Metadata for MaxMind database files.
 * Contains information about database format, version, build epoch, type, languages,
 * and various structural information about the database.
 */
public final class Metadata {
    private final int binaryFormatMajorVersion;
    private final int binaryFormatMinorVersion;

    private final long buildEpoch;

    private final String databaseType;

    private final Map<String, String> description;

    private final int ipVersion;

    private final List<String> languages;

    private final int nodeByteSize;

    private final int nodeCount;

    private final int recordSize;

    private final int searchTreeSize;

    /**
     * Create Metadata from decoded map
     * @param metadata the decoded metadata map keyed by field name, for example "build_epoch"
     */
    @SuppressWarnings("unchecked")
    Metadata(Map metadata) {
        this.binaryFormatMajorVersion = getInt(metadata,
                "binary_format_major_version");
        this.binaryFormatMinorVersion = getInt(metadata,
                "binary_format_minor_version");
        this.buildEpoch = getLong(metadata, "build_epoch");
        this.databaseType = getString(metadata, "database_type");
        this.languages = (List<String>) metadata.get("languages");
        this.description = (Map<String, String>) metadata.get("description");
        this.ipVersion = getInt(metadata, "ip_version");
        this.nodeCount = getInt(metadata, "node_count");
        this.recordSize = getInt(metadata, "record_size");
        this.nodeByteSize = this.recordSize / 4;
        this.searchTreeSize = this.nodeCount * this.nodeByteSize;
    }

    private static int getInt(Object m, String key) {
        Map map = (Map) m;
        Number i = (Number) map.get(key);
        if (i != null)
            return i.intValue();
        return 0;
    }

    private static long getLong(Object m, String key) {
        Map map = (Map) m;
        Number i = (Number) map.get(key);
        if (i != null)
            return i.longValue();
        return 0;
    }

    private static String getString(Object m, String key) {
        Map map = (Map) m;
        return (String) map.get(key);
    }

    /**
     * Gets the major version of the database's binary format.
     *
     * @return the major version number for the database's binary format.
     */
    public int getBinaryFormatMajorVersion() {
        return this.binaryFormatMajorVersion;
    }

    /**
     * Gets the minor version of the database's binary format.
     *
     * @return the minor version number for the database's binary format.
     */
    public int getBinaryFormatMinorVersion() {
        return this.binaryFormatMinorVersion;
    }

    /**
     * Gets the date the database was built.
     *
     * @return the date of the database build.
     */
    public Date getBuildDate() {
        return new Date(this.buildEpoch * 1000);
    }

    /**
     * Gets the structure of the data records associated with an IP address.
     * The actual definition of these structures is left up to the database creator.
     *
     * @return a string that indicates the structure of each data record
     */
    public String getDatabaseType() {
        return this.databaseType;
    }

    /**
     * Gets the database description in each supported language.
     *
     * @return map from language code to description in that language.
     */
    public Map<String, String> getDescription() {
        return this.description;
    }

    /**
     * Gets the IP version the database holds address data for.
     *
     * @return whether the database contains IPv4 or IPv6 address data. The only
     * possible values are 4 and 6.
     */
    public int getIpVersion() {
        return this.ipVersion;
    }

    /**
     * Gets the languages the database carries localized names in.
     *
     * @return list of languages supported by the database.
     */
    public List<String> getLanguages() {
        return this.languages;
    }

    /**
     * Gets the size in bytes of one search tree node.
     *
     * @return the size in bytes of a single search tree node
     */
    int getNodeByteSize() {
        return this.nodeByteSize;
    }

    /**
     * Gets the number of nodes in the search tree.
     *
     * @return the number of nodes in the search tree.
     */
    int getNodeCount() {
        return this.nodeCount;
    }

    /**
     * Gets the number of bits in one search tree record.
     * Note that each node consists of two records.
     *
     * @return the number of bits in a record in the search tree. Note that each
     * node consists of two records.
     */
    int getRecordSize() {
        return this.recordSize;
    }

    /**
     * Gets the total size in bytes of the search tree.
     *
     * @return the size in bytes of the whole search tree
     */
    int getSearchTreeSize() {
        return this.searchTreeSize;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    /**
     * Return a description of the database metadata, for diagnostics.
     */
    @Override
    public String toString() {
        return "Metadata [binaryFormatMajorVersion="
                + this.binaryFormatMajorVersion + ", binaryFormatMinorVersion="
                + this.binaryFormatMinorVersion + ", buildEpoch="
                + this.buildEpoch + ", databaseType=" + this.databaseType
                + ", description=" + this.description + ", ipVersion="
                + this.ipVersion + ", nodeCount=" + this.nodeCount
                + ", recordSize=" + this.recordSize + "]";
    }
}
