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
    public String title;
    public String link;
    public String id;
    public long updated;
    public String summary;
    public String content;
    public String contentType; // attribute of content
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

    @Override
    public int hashCode() {
    	return (int) updated;
    }
}
