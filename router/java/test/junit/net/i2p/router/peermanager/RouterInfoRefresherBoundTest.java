package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.i2p.data.Hash;

import org.junit.Test;

/**
 * Bounding of the RouterInfo refreshes issued on behalf of promotion-held peers.
 *
 * <p>A peer held out of the tiers for want of a usable address cannot be refreshed directly -
 * that is why it is held - so the only route back is an iterative lookup. Those lookups are
 * issued from a reorg path, and a scan walks thousands of profiles, so the batch is bounded
 * twice: a maximum batch, and a minimum interval between batches. Without both, either a
 * single large scan or a burst of demotions could turn the recovery path into a flood.
 *
 * @since 0.9.71+
 */
public class RouterInfoRefresherBoundTest {

    private static Hash peer(int i) {
        byte[] h = new byte[Hash.HASH_LENGTH];
        h[0] = (byte) (i >> 24);
        h[1] = (byte) (i >> 16);
        h[2] = (byte) (i >> 8);
        h[3] = (byte) i;
        return Hash.create(h);
    }

    @Test
    public void aRequestIsRecorded() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        assertTrue(r.requestAddressRefresh(peer(1)));
        assertEquals(1, r.getAddressRefreshPending());
    }

    /** The batch cap is what stops one large scan from enqueueing thousands of lookups. */
    @Test
    public void theQueueIsCapped() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        int accepted = 0;
        for (int i = 0; i < 500; i++) {
            if (r.requestAddressRefresh(peer(i))) { accepted++; }
        }
        assertEquals("only one batch may be queued",
                     RouterInfoRefresher.MAX_ADDRESS_REFRESHES, accepted);
        assertEquals(RouterInfoRefresher.MAX_ADDRESS_REFRESHES, r.getAddressRefreshPending());
    }

    /** Overflow is counted, not silently discarded, so the size of the loss is visible. */
    @Test
    public void overflowIsCounted() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        for (int i = 0; i < 500; i++) { r.requestAddressRefresh(peer(i)); }
        assertEquals(500 - RouterInfoRefresher.MAX_ADDRESS_REFRESHES, r.getAddressRefreshDropped());
    }

    @Test
    public void nullIsNotRecorded() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        assertFalse(r.requestAddressRefresh(null));
        assertEquals(0, r.getAddressRefreshPending());
    }

    /** The same peer scanned repeatedly must not queue the same lookup twice. */
    @Test
    public void duplicateRequestsAreIgnored() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        assertTrue(r.requestAddressRefresh(peer(7)));
        assertFalse(r.requestAddressRefresh(peer(7)));
        assertEquals(1, r.getAddressRefreshPending());
    }

    @Test
    public void takingABatchClearsTheQueue() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        for (int i = 0; i < 5; i++) { r.requestAddressRefresh(peer(i)); }
        List<Hash> batch = r.takeAddressRefreshBatch(1_000_000L);
        assertEquals(5, batch.size());
        assertEquals("the queue must not be drained twice",
                     0, r.takeAddressRefreshBatch(2_000_000L).size());
    }

    /** First drain is always allowed, whatever the clock says. */
    @Test
    public void theFirstDrainIsAllowed() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        assertTrue(r.mayDrainAddressRefreshes(1_000L));
    }

    /** The interval is what stops a burst of demotions stacking batches. */
    @Test
    public void repeatedDrainsAreBlockedWithinTheInterval() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        long base = 10_000_000L;
        assertTrue(r.mayDrainAddressRefreshes(base));
        r.takeAddressRefreshBatch(base);
        assertFalse(r.mayDrainAddressRefreshes(base + 1));
        assertFalse(r.mayDrainAddressRefreshes(base + RouterInfoRefresher.ADDRESS_REFRESH_MIN_INTERVAL_MS - 1));
    }

    @Test
    public void drainsResumeAfterTheInterval() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        long base = 10_000_000L;
        r.takeAddressRefreshBatch(base);
        assertTrue("a new interval must allow a batch",
                   r.mayDrainAddressRefreshes(base + RouterInfoRefresher.ADDRESS_REFRESH_MIN_INTERVAL_MS));
    }

    /** The batch can never exceed the cap however full the queue was. */
    @Test
    public void aBatchNeverExceedsTheCap() {
        RouterInfoRefresher r = new RouterInfoRefresher();
        for (int i = 0; i < 200; i++) { r.requestAddressRefresh(peer(i)); }
        assertTrue(r.takeAddressRefreshBatch(1_000L).size() <= RouterInfoRefresher.MAX_ADDRESS_REFRESHES);
    }
}