package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.i2p.I2PAppContext;
import net.i2p.crypto.HKDF;
import net.i2p.data.SessionKey;

import org.junit.Test;

/**
 * Consuming a tag from a {@link RatchetTagSet} (1.1 of the hot-path findings,
 * D-1). The lookup used to scan the receive window comparing tags, up to 320
 * equals() calls for every inbound ES message; it now goes through a side index
 * from tag to tag number. The index is only a win if it stays the size of the
 * live window - an ES tagset mints a fresh tag for every one it spends, so an
 * index that is not pruned on consume and on trim grows with the session's
 * lifetime instead.
 *
 * @since 0.9.72+
 */
public class RatchetTagSetConsumeTest {

    private static final int MIN = 8;
    private static final int MAX = 16;
    private static final int CONSUMED = 60;

    /**
     * A fresh inbound ES tagset holding MIN tags.
     *
     * @return the tagset
     */
    private static RatchetTagSet tagSet() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        HKDF hkdf = new HKDF(ctx);
        byte[] root = new byte[32];
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) {
            root[i] = (byte) i;
            data[i] = (byte) (i + 1);
        }
        return new RatchetTagSet(hkdf, null, null, new SessionKey(root), new SessionKey(data),
                                 ctx.clock().now(), 0, 0, MIN, MAX);
    }

    /**
     * The tagset's own tag window, oldest first.
     *
     * @param ts the tagset
     * @return its live tag window
     * @throws Exception if {@code _sessionTags} is not a field of
     *                   {@link RatchetTagSet} or the reflective read fails
     */
    @SuppressWarnings("unchecked")
    private static SparseArray<RatchetSessionTag> window(RatchetTagSet ts) throws Exception {
        Field field = RatchetTagSet.class.getDeclaredField("_sessionTags");
        field.setAccessible(true);
        return (SparseArray<RatchetSessionTag>) field.get(ts);
    }

    /**
     * The side index that replaced the linear scan.
     *
     * @param ts the tagset
     * @return the tag-to-number index
     * @throws Exception if {@code _tagNumbers} is not a field of
     *                   {@link RatchetTagSet} or the reflective read fails
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<RatchetSessionTag, Integer> index(RatchetTagSet ts) throws Exception {
        Field field = RatchetTagSet.class.getDeclaredField("_tagNumbers");
        field.setAccessible(true);
        return (java.util.Map<RatchetSessionTag, Integer>) field.get(ts);
    }

    /**
     * Every tag in the live window resolves, and resolves only once - a tag
     * spent twice must not hand out a key again.
     *
     * @throws Exception if reading the private tag window reflectively fails
     */
    @Test
    public void testEachTagResolvesOnce() throws Exception {
        RatchetTagSet ts = tagSet();
        SparseArray<RatchetSessionTag> tags = window(ts);
        int live = tags.size();
        assertTrue("tagset must start with tags to consume: " + live, live > 0);
        RatchetSessionTag[] all = new RatchetSessionTag[live];
        for (int i = 0; i < live; i++) {
            all[i] = tags.valueAt(i);
        }
        for (RatchetSessionTag tag : all) {
            assertNotNull("live tag did not resolve", ts.consume(tag));
        }
        for (RatchetSessionTag tag : all) {
            assertNull("a spent tag resolved again", ts.consume(tag));
        }
    }

    /**
     * A tag the tagset never generated is rejected.
     */
    @Test
    public void testAnUnknownTagIsRejected() {
        RatchetTagSet ts = tagSet();
        assertNull(ts.consume(new RatchetSessionTag(-0x5e17c0deL)));
    }

    /**
     * The index has to track the live window, not the number of tags the session
     * has used. This is the leak the linear scan hid: an unpruned index grows
     * past the window on every spend, and the memory it costs is what the scan
     * was trading away.
     *
     * @throws Exception if reading the private window or index reflectively fails
     */
    @Test
    public void testTheIndexStaysProportionalToTheWindow() throws Exception {
        RatchetTagSet ts = tagSet();
        for (int i = 0; i < CONSUMED; i++) {
            SparseArray<RatchetSessionTag> tags = window(ts);
            assertTrue("tag window emptied after " + i + " consumes", tags.size() > 0);
            assertNotNull("newest tag must still resolve",
                          ts.consume(tags.valueAt(tags.size() - 1)));
        }
        int indexed = index(ts).size();
        assertTrue("index grew with the session's lifetime total: " + indexed,
                   indexed <= 2 * MAX);
        assertTrue("tagset must have spent more than it indexes: " + CONSUMED,
                   CONSUMED > 2 * MAX);
    }
}
