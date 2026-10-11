package net.i2p.router.news;

/**
 * Represents a single news entry with content and metadata.
 * <p>
 * Encapsulates news item data including title, link, identifier,
 * update timestamp, summary, content, and author information.
 * Supports content type specification and proper sorting
 * based on update timestamps.
 * <p>
 * Provides Comparable interface implementation for chronological
 * ordering of news entries. All String fields may be null
 * to accommodate optional metadata elements in news feeds.
 *
 * @since 0.9.17
 */
public class NewsEntry implements Comparable<NewsEntry> {
    /**
     * Every field is left at its default - null Strings and a zero update timestamp -
     * for the Atom parser to fill in as it reads the entry.
     */
    public NewsEntry() {}

    /**
     * Headline text of the news item.
     */
    public String title;
    /**
     * URL the headline links to.
     */
    public String link;
    /**
     * Feed-assigned identifier for the item.
     */
    public String id;
    /**
     * Publication time in milliseconds since the epoch.
     */
    public long updated;
    /**
     * Short synopsis shown in the headline list.
     */
    public String summary;
    /**
     * Body text of the item, in the markup named by contentType.
     */
    public String content;
    /**
     * Markup language of the content field, as an attribute of it.
     */
    public String contentType; // attribute of content
    /**
     * Display name of the item's author, a subnode of the author node.
     */
    public String authorName;  // subnode of author

    /** reverse, newest first */
    @Override
    public int compareTo(NewsEntry e) {
        if (updated > e.updated)
            return -1;
        if (updated < e.updated)
            return 1;
        return 0;
    }

    /**
     * Treats two entries as equal when they share an update time.
     */
    @Override
    public boolean equals(Object o) {
        if(o == null) {
        	return false;
        }
        if(!(o instanceof NewsEntry)) {
        	return false;
        }
    	NewsEntry e = (NewsEntry) o;

    	return this.compareTo(e) == 0;
    }

    /**
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
    	return (int) updated;
    }
}
