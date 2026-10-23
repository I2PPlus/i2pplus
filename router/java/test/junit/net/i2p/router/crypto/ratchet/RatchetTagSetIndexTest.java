package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.HKDF;
import net.i2p.data.SessionKey;

import org.junit.Test;

/**
 * Tests the tag index a {@link RatchetTagSet} keeps for the session key
 * manager.
 * <p>
 * The index exists so the SKM can drop a whole tagset's tags out of its
 * inbound map by iterating the tagset, instead of scanning every tag the router
 * knows. That only pays if the index holds the live window. It used to be
 * append-only: an inbound ES tagset generates a fresh tag for every one it
 * spends, so the index grew to the session's lifetime total - measured at about
 * 4400 against a live window of 320 - and both SKM removal loops did roughly 13x
 * the work they needed to while retaining the dead tags.
 * <p>
 * Driven through the real ratchet, with a listener standing in for the SKM so
 * the tags it expires can be compared against what the index still holds.
 *
 * @since 0.9.72+
 */
public class RatchetTagSetIndexTest {

    private static final int MIN = 8;
    private static final int MAX = 16;
    private static final int CONSUMED = 60;

    /**
     * Stands in for the SKM: records every tag registered and every tag expired.
     */
    private static class RecordingListener implements SessionTagListener {

        private final List<RatchetSessionTag> _added = new ArrayList<>();
        private final List<RatchetSessionTag> _expired = new ArrayList<>();

        @Override
        public boolean addTag(RatchetSessionTag tag, RatchetTagSet ts) {
            _added.add(tag);
            return true;
        }

        @Override
        public void expireTag(RatchetSessionTag tag, RatchetTagSet ts) {
            _expired.add(tag);
        }

        List<RatchetSessionTag> added() {return _added;}

        List<RatchetSessionTag> expired() {return _expired;}
    }

    /**
     * A fresh inbound ES tagset with a listener attached.
     *
     * @param lsnr the listener to register tags with
     * @return an inbound ES tagset holding MIN tags
     */
    private static RatchetTagSet tagSet(SessionTagListener lsnr) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        HKDF hkdf = new HKDF(ctx);
        byte[] root = new byte[32];
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) {
            root[i] = (byte) i;
            data[i] = (byte) (i + 1);
        }
        return new RatchetTagSet(hkdf, lsnr, null, new SessionKey(root), new SessionKey(data),
                                 ctx.clock().now(), 0, 0, MIN, MAX);
    }

    /**
     * The tagset's own tag window, oldest first. Read reflectively so the test
     * can consume in the order the ratchet expects.
     *
     * @param ts the tagset
     * @return its live tag window
     * @throws Exception if the reflective read fails, because the private
     *                   {@code _sessionTags} field no longer exists or the JVM
     *                   refuses to make it accessible
     */
    @SuppressWarnings("unchecked")
    private static SparseArray<RatchetSessionTag> window(RatchetTagSet ts) throws Exception {
        Field field = RatchetTagSet.class.getDeclaredField("_sessionTags");
        field.setAccessible(true);
        return (SparseArray<RatchetSessionTag>) field.get(ts);
    }

    /**
     * Spend CONSUMED tags, newest first, so each spend leaves the older ones
     * behind for the trim pass to expire. Consuming in strict order would keep
     * the window flush against the threshold and never expire anything, which is
     * not the case the index has to survive.
     *
     * @param ts the tagset to advance
     * @throws Exception if the reflective read of the tag window fails
     */
    private static void advance(RatchetTagSet ts) throws Exception {
        for (int i = 0; i < CONSUMED; i++) {
            SparseArray<RatchetSessionTag> tags = window(ts);
            assertTrue("tag window emptied after " + i + " consumes", tags.size() > 0);
            assertNotNull("newest tag must still be consumable",
                          ts.consume(tags.valueAt(tags.size() - 1)));
        }
    }

    /**
     * The index has to stay near the live window, not the number of tags the
     * session has used. This is the leak: the index was append-only, so after
     * 60 spends it held every tag ever generated.
     *
     * @throws Exception if the reflective read of the tag window in advance() fails
     */
    @Test
    public void testIndexStaysProportionalToTheWindow() throws Exception {
        RecordingListener lsnr = new RecordingListener();
        RatchetTagSet ts = tagSet(lsnr);

        advance(ts);

        int indexed = ts.getRegisteredTags().size();
        assertTrue("index grew with the session's lifetime total: " + indexed,
                   indexed <= 2 * MAX);
        // Sanity: the tagset really did generate far more than it now indexes,
        // otherwise the bound above would be satisfied by doing nothing.
        assertTrue("tagset must have generated more than it indexes: " + lsnr.added().size(),
                   lsnr.added().size() > 2 * MAX);
    }

    /**
     * A tag the SKM has expired must leave the index. The SKM has already
     * dropped it from its inbound map, so the index can only ever hand it back
     * as a redundant remove() - and it never stops doing so.
     *
     * @throws Exception if the reflective read of the tag window in advance() fails
     */
    @Test
    public void testExpiredTagsLeaveTheIndex() throws Exception {
        RecordingListener lsnr = new RecordingListener();
        RatchetTagSet ts = tagSet(lsnr);

        advance(ts);

        assertFalse("the ratchet must have expired tags for this to mean anything",
                    lsnr.expired().isEmpty());
        for (RatchetSessionTag tag : lsnr.expired()) {
            assertFalse("expired tag still indexed", ts.getRegisteredTags().contains(tag));
        }
    }

    /**
     * The index still holds the tags the SKM actually needs: everything in the
     * live window. Emptying the index would silently break the SKM's cleanup of
     * a finished handshake.
     *
     * @throws Exception if the reflective read of the tag window fails
     */
    @Test
    public void testIndexStillCoversTheLiveWindow() throws Exception {
        RecordingListener lsnr = new RecordingListener();
        RatchetTagSet ts = tagSet(lsnr);

        advance(ts);

        SparseArray<RatchetSessionTag> tags = window(ts);
        assertTrue("window must still be non-empty", tags.size() > 0);
        for (int i = 0; i < tags.size(); i++) {
            assertTrue("live tag missing from the index",
                       ts.getRegisteredTags().contains(tags.valueAt(i)));
        }
    }

    /**
     * A tag that was spent leaves the index as well. The SKM removes it from its
     * inbound map before asking for the key, so an index that keeps it can only
     * ever hand it back as a redundant remove() - and it does so on every later
     * cleanup, for the life of the session.
     *
     * @throws Exception if the reflective read of the tag window fails
     */
    @Test
    public void testSpentTagsLeaveTheIndex() throws Exception {
        RecordingListener lsnr = new RecordingListener();
        RatchetTagSet ts = tagSet(lsnr);
        List<RatchetSessionTag> spent = new ArrayList<>();

        for (int i = 0; i < CONSUMED; i++) {
            SparseArray<RatchetSessionTag> tags = window(ts);
            RatchetSessionTag tag = tags.valueAt(tags.size() - 1);
            assertNotNull(ts.consume(tag));
            spent.add(tag);
        }

        for (RatchetSessionTag tag : spent) {
            assertFalse("spent tag still indexed", ts.getRegisteredTags().contains(tag));
        }
    }

    /**
     * Clearing the index after the SKM has removed a whole tagset's tags must
     * actually empty it - that is what releases a finished handshake's tags.
     *
     * @throws Exception if the reflective read of the tag window in advance() fails
     */
    @Test
    public void testClearReleasesEveryTag() throws Exception {
        RecordingListener lsnr = new RecordingListener();
        RatchetTagSet ts = tagSet(lsnr);

        assertEquals(MIN, ts.getRegisteredTags().size());
        ts.clearRegisteredTags();
        assertTrue(ts.getRegisteredTags().isEmpty());

        // And the tagset keeps working afterwards: the next spend re-registers.
        advance(ts);
        assertTrue(ts.getRegisteredTags().size() > 0);
    }
}
