// License: MIT. See docs/LICENSES.md
package net.i2p.addressbook;

/**
 * A subscription to a remote address book.
 *
 * @author Ragnarok
 *
 */
class Subscription {

    private final String location;
    private String etag;
    private String lastModified;
    private long lastFetched;

    /**
     * Construct a Subscription pointing to the address book at location, that
     * was last read at the time represented by etag and lastModified.
     *
     * @param location
     *            A String representing a url to a remote address book. Non-null.
     * @param etag
     *            The etag header that we received the last time we read this
     *            subscription. May be null.
     * @param lastModified
     *            the last-modified header we received the last time we read
     *            this subscription. May be null.
     * @param lastFetched when the subscription was last fetched (Java time, as a String).
     *            May be null.
     */
    public Subscription(String location, String etag, String lastModified, String lastFetched) {
        this.location = location;
        this.etag = etag;
        this.lastModified = lastModified;
        if (lastFetched != null) {
            try {
                this.lastFetched = Long.parseLong(lastFetched);
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
    }

    /**
     * Return the location this Subscription points at.
     *
     * @return A String representing a url to a remote address book.
     */
    public String getLocation() {
        return this.location;
    }

    /**
     * Return the etag header that we received the last time we read this
     * subscription.
     *
     * @return A String containing the etag header.
     */
    public String getEtag() {
        return this.etag;
    }

    /**
     * Set the etag header.
     *
     * @param etag
     *            A String containing the etag header.
     */
    public void setEtag(String etag) {
        this.etag = etag;
    }

    /**
     * Return the last-modified header that we received the last time we read
     * this subscription.
     *
     * @return A String containing the last-modified header.
     */
    public String getLastModified() {
        return this.lastModified;
    }

    /**
     * Set the last-modified header.
     *
     * @param lastModified
     *            A String containing the last-modified header.
     */
    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    /**
     * Return when the subscription was last fetched.
     *
     * @return the time the subscription was last fetched (Java time in ms)
     * @since 0.8.2
     */
    public long getLastFetched() {
        return this.lastFetched;
    }

    /**
     * Set when the subscription was last fetched.
     *
     * @param t the time the subscription was last fetched (Java time in ms)
     * @since 0.8.2
     */
    public void setLastFetched(long t) {
        this.lastFetched = t;
    }
}
