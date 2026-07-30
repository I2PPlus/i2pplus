package net.i2p.addressbook;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.HostTxtEntry;
import net.i2p.util.PortMapper;

/**
 * An iterator over the subscriptions in a SubscriptionList.  Note that this iterator
 * returns AddressBook objects, and not Subscription objects.
 * Yes, the EepGet fetch() is done in here in next().
 *
 * @author Ragnarok
 */
class SubscriptionIterator implements Iterator<AddressBook> {

    private final Iterator<Subscription> subIterator;
    private final String proxyHost;
    private final int proxyPort;
    private final long delay;

    /**
     * Construct a SubscriptionIterator using the Subscriptions in List subscriptions.
     *
     * @param subscriptions
     *            List of Subscription objects that represent address books.
     * @param delay the minimum delay since last fetched for the iterator to actually fetch
     * @param proxyHost proxy hostname
     * @param proxyPort proxy port number
     */
    public SubscriptionIterator(List<Subscription> subscriptions, long delay, String proxyHost, int proxyPort) {
        this.subIterator = subscriptions.iterator();
        this.delay = delay;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    /**
     * {@inheritDoc}
     * @return whether next is present
     */
    @Override
    public boolean hasNext() {
        return this.subIterator.hasNext();
    }

    /**
     * Fetch the next subscription's address book, if the delay has been met.
     *
     * @return non-null AddressBook (empty if the minimum delay has not been met,
     *          or there is no proxy tunnel, or the fetch otherwise fails)
     */
    public AddressBook next() {
        Subscription sub = this.subIterator.next();
        if (sub.getLocation().startsWith("file:")) {
            // test only
            return new AddressBook(sub.getLocation().substring(5));
        } else if (sub.getLastFetched() + this.delay < I2PAppContext.getGlobalContext().clock().now() &&
            I2PAppContext.getGlobalContext().portMapper().getPort(PortMapper.SVC_HTTP_PROXY) >= 0 &&
            !I2PAppContext.getGlobalContext().getBooleanProperty("i2p.vmCommSystem")) {
            return new AddressBook(sub, this.proxyHost, this.proxyPort);
        } else {
            return new AddressBook(Collections.<String, HostTxtEntry>emptyMap());
        }
    }

    /**
     * Not supported.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
