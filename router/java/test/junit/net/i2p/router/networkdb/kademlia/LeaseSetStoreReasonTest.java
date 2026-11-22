package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import net.i2p.client.I2PClientFactory;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;

import org.junit.Test;

/**
 * Why a LeaseSet store is refused, in the one line the caller writes.
 *
 * <p>The validators used to log their own reason and return it as well, so the
 * store path wrote the same rejection twice: the live log held 45,419
 * "Invalid NetDbStore attempt!" lines beside 45,423 "Store validation failed"
 * lines for the same 45.4k stores, 42,850 of them for one destination. They now
 * return the reason and say nothing, and the caller - the only site holding the
 * key, the LeaseSet's shape and its dates together - writes one entry. That
 * makes the returned string the whole finding, so these cases pin that it
 * carries what the dropped log lines used to.
 *
 * @since 0.9.72+
 */
public class LeaseSetStoreReasonTest {

    /** Holds no state; each case builds its own LeaseSets. */
    public LeaseSetStoreReasonTest() {}

    /** One hour, the unit the two clock-skew windows are compared in. */
    private static final long HOUR = 60L*60*1000;

    /**
     * A destination to publish a LeaseSet under.
     *
     * @return a fresh destination
     */
    private static Destination dest() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            I2PClientFactory.createClient().createDestination(out);
            return Destination.create(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("dest gen failed", e);
        }
    }

    /**
     * A LeaseSet published under its own destination, unsigned - the cases here
     * stop at the key and the dates, before the signature is reached.
     *
     * @return a LeaseSet whose hash is its destination's hash
     */
    private static LeaseSet leaseSet(Destination d) {
        LeaseSet ls = new LeaseSet();
        ls.setDestination(d);
        return ls;
    }

    /** A LeaseSet stored under the hash of its own destination is not refused. */
    @Test
    public void ownKeyIsAccepted() {
        LeaseSet ls = leaseSet(dest());
        assertNull(KademliaNetworkDatabaseFacade.validateKeyMatch(ls.getHash(), ls));
    }

    /**
     * The reason for a mismatched key names the destination the LeaseSet really
     * belongs to. The caller already prints the key it was stored under, and
     * the two hashes side by side are what tells a floodfill operator which of
     * the two the sender got wrong - the detail the validator's own log line
     * used to be the only place carrying it.
     */
    @Test
    public void mismatchedKeyReasonNamesTheRealDestination() {
        LeaseSet ls = leaseSet(dest());
        Hash other = dest().calculateHash();
        String err = KademliaNetworkDatabaseFacade.validateKeyMatch(other, ls);
        assertNotNull(err);
        assertTrue("no LeaseSet hash in the reason: " + err,
                   err.contains('[' + ls.getHash().toShortBase32() + ']'));
        // the caller supplies the key and the wrapper; the reason must not repeat them
        assertTrue("the reason repeats the key: " + err,
                   !err.contains(other.toShortBase32()));
        assertTrue("the reason carries the store wrapper: " + err,
                   !err.contains("NetDbStore"));
    }

    /**
     * A LeaseSet with no published identity has no hash to compare the key
     * against, which is a different fact from a mismatch - and the old code
     * reached straight for {@code getHash().toShortBase32()} on it, so the store
     * path threw instead of reporting why it refused.
     */
    @Test
    public void noPublishedDestinationSaysSo() {
        LeaseSet ls = new LeaseSet();
        String err = KademliaNetworkDatabaseFacade.validateKeyMatch(dest().calculateHash(), ls);
        assertNotNull("no reason for an entry with no destination", err);
        assertTrue("the reason claims a mismatch instead: " + err,
                   err.contains("publishes no destination"));
        assertTrue("the reason puts a hash in the bracketed form the console links: " + err,
                   !err.contains("["));
    }

    /** A LeaseSet whose dates are near now is not refused for its dates. */
    @Test
    public void nearDatesAreNotRefused() {
        long now = 1_700_000_000_000L;
        assertNull(KademliaNetworkDatabaseFacade.checkFutureExpiration(
                     DatabaseEntry.KEY_TYPE_LEASESET, now + 60*1000L, now));
    }

    /**
     * A LeaseSet expiring well past the clock-skew window is refused, and the
     * reason says which window it failed - the number the dropped log line
     * carried and the reader needs to tell a skewed destination from a broken
     * clock of our own.
     */
    @Test
    public void farFutureExpiryReasonNamesTheWindow() {
        long now = 1_700_000_000_000L;
        String err = KademliaNetworkDatabaseFacade.checkFutureExpiration(
                       DatabaseEntry.KEY_TYPE_LEASESET, now + 30 * HOUR, now);
        assertNotNull(err);
        assertTrue("no clock-skew window in the reason: " + err, err.contains("clock-skew limit"));
    }

    /**
     * A meta LeaseSet is held to the wider window - four hours is inside it and
     * outside the plain one - and past that window the reason names the window
     * that actually applied to it, not the plain one.
     */
    @Test
    public void metaLeaseSetGetsTheWiderWindow() {
        long now = 1_700_000_000_000L;
        assertNull(KademliaNetworkDatabaseFacade.checkFutureExpiration(
                     DatabaseEntry.KEY_TYPE_META_LS2, now + 4 * HOUR, now));
        String err = KademliaNetworkDatabaseFacade.checkFutureExpiration(
                       DatabaseEntry.KEY_TYPE_META_LS2, now + 24 * 60 * HOUR, now);
        assertNotNull(err);
        assertTrue("no clock-skew window in the reason: " + err, err.contains("clock-skew limit"));
    }

    /**
     * The two windows are different, so a date that is refused for one type is
     * accepted for the other - which is what makes the type-specific limit a
     * real branch and not one constant printed twice.
     */
    @Test
    public void thePlainWindowIsTighterThanTheMetaOne() {
        long now = 1_700_000_000_000L;
        long when = now + 4 * HOUR;
        assertNotNull(KademliaNetworkDatabaseFacade.checkFutureExpiration(
                        DatabaseEntry.KEY_TYPE_LEASESET, when, now));
        assertNull(KademliaNetworkDatabaseFacade.checkFutureExpiration(
                     DatabaseEntry.KEY_TYPE_META_LS2, when, now));
    }
}
