package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Hash;
import org.junit.Test;

/**
 * The pure decisions behind a rejected LeaseSet store: how long the key stays
 * rejected, how the rejection is reported, and what the failure line adds when
 * its own two hashes look identical.
 * <p>
 * A store that fails validation leaves the NetDb empty, so the next send to
 * that destination re-probes and usually gets the same bad entry back. One
 * live router fetched one destination 1,639 times in 133 minutes, and every
 * fetch wrote a two-line WARN whose key and LeaseSet hashes printed the same 8
 * characters, so the line read as a contradiction.
 *
 * @since 0.9.72+
 */
public class StoreRejectionDecisionTest {

    /** A fixed instant for every window boundary in these tests. */
    private static final long NOW = 1_700_000_000_000L;

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     *
     * @since 0.9.72+
     */
    public StoreRejectionDecisionTest() {
    }

    /**
     * A key that was never rejected is not held rejected, and the null entry
     * the map returns for it is the whole signal.
     *
     * @since 0.9.72+
     */
    @Test
    public void testNeverRejectedIsNotRejected() {
        assertFalse(KademliaNetworkDatabaseFacade.storeRejected(null, NOW));
    }

    /**
     * The window is open until its expiry instant and closed at it: the entry
     * is dropped from the map on the first read after it lapses.
     *
     * @since 0.9.72+
     */
    @Test
    public void testWindowClosesAtItsExpiry() {
        assertTrue(KademliaNetworkDatabaseFacade.storeRejected(
                       Long.valueOf(NOW + 1), NOW));
        assertFalse(KademliaNetworkDatabaseFacade.storeRejected(
                        Long.valueOf(NOW), NOW));
        assertFalse(KademliaNetworkDatabaseFacade.storeRejected(
                        Long.valueOf(NOW - 1), NOW));
    }

    /**
     * The window is 30 minutes: long enough to break a re-probe loop that runs
     * once a probe interval, and short enough that a destination which
     * republishes a good LeaseSet is reachable again well inside its own
     * 90-minute lifetime.
     *
     * @since 0.9.72+
     */
    @Test
    public void testWindowIsHalfAnHour() {
        long window = KademliaNetworkDatabaseFacade.STORE_REJECT_MS;
        assertEquals(30 * 60 * 1000L, window);
        assertTrue("longer than any re-probe interval", window > 60 * 1000L);
        assertTrue("shorter than a LeaseSet lifetime",
                   window < 90 * 60 * 1000L);
    }

    /**
     * One key per destination: the rejection belongs to the destination, so a
     * destination whose LeaseSet is refused never hides behind another's
     * counter, and the same destination keeps one counter across occurrences.
     *
     * @since 0.9.72+
     */
    @Test
    public void testReportKeyIsPerDestination() {
        String a = KademliaNetworkDatabaseFacade.storeRejectKey(hash(0x01));
        assertEquals("stable across occurrences", a,
                     KademliaNetworkDatabaseFacade.storeRejectKey(
                         hash(0x01)));
        assertFalse("one key per destination",
                    a.equals(KademliaNetworkDatabaseFacade.storeRejectKey(
                                 hash(0x02))));
    }

    /**
     * When the two 8-character forms the failure line prints are the same, the
     * line contradicts itself, so the full forms go on continuation lines: the
     * only way a reader can see where the two hashes part company.
     *
     * @since 0.9.72+
     */
    @Test
    public void testIdenticalShortFormsPrintTheFullForms() {
        Hash key = hash(0x01);
        Hash ls = samePrefixDifferentTail(0x01);
        String clause = KademliaNetworkDatabaseFacade.fullHashClause(key, ls);
        assertTrue("key on its own line: " + clause,
                   clause.contains("\n* Key: " + key.toBase32()));
        assertTrue("LeaseSet on its own line: " + clause,
                   clause.contains("\n* LeaseSet: " + ls.toBase32()));
        assertTrue("where they part company: " + clause,
                   clause.contains("First difference at byte 31 of 32"));
        assertTrue("three continuation lines: " + clause,
                   clause.split("\n").length == 4);
    }

    /**
     * The measured case: on a live router, all 906 key-mismatch rejections that
     * printed both full forms printed the same 52 characters twice - the two
     * hashes are the same 32 bytes, so the reason and the bytes disagree, and
     * the line has to say that rather than leave a reader to compare 52
     * characters by eye.
     *
     * @since 0.9.72+
     */
    @Test
    public void testByteIdenticalHashesFlagTheContradiction() {
        Hash key = hash(0x01);
        Hash same = hash(0x01);
        String clause = KademliaNetworkDatabaseFacade.fullHashClause(key,
                                                                     same);
        assertTrue("the verdict is there: " + clause,
                   clause.contains("The two hashes are byte-identical"));
        assertTrue("and it points at what disagrees: " + clause,
                   clause.contains("-> The key check disagrees"));
        assertFalse("no byte offset when there is none: " + clause,
                    clause.contains("First difference"));
    }

    /**
     * The verdict is about bytes, not the rendered forms: hashes that part
     * company at byte 7 say byte 7, which tells a reader a real difference
     * from a truncated rendering.
     *     *
     * @since 0.9.72+
     */
    @Test
    public void testTheVerdictNamesTheDifferingByte() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        a[0] = 0x11;
        b[0] = 0x11;
        a[7] = (byte) 0x80;
        Hash key = new Hash(a);
        Hash ls = new Hash(b);
        assertEquals("\n* First difference at byte 7 of 32",
                     KademliaNetworkDatabaseFacade.hashDiffClause(key, ls));
    }

    /**
     * When the short forms already differ, the failure line says everything it
     * needs to and the clause adds nothing.
     *
     * @since 0.9.72+
     */
    @Test
    public void testDifferentShortFormsAddNothing() {
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             hash(0x01), hash(0x02)));
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             hash(0x01), null));
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             null, hash(0x01)));
    }

    /**
     * A hash whose first byte is given and the rest zero, so its 8-character
     * short form is fixed by that byte and the four that follow it.
     *
     * @param first the first byte of the hash
     * @return a 32-byte hash
     */
    private static Hash hash(int first) {
        byte[] data = new byte[32];
        data[0] = (byte) first;
        return new Hash(data);
    }

    /**
     * A hash sharing the first five bytes of {@link #hash(int)}, so the same
     * short form, but differing in the tail - which is what makes the two
     * hashes unequal while their short forms match.
     *
     * @param first the first byte, shared with the hash it is paired with
     * @return a 32-byte hash unequal to, but short-form-identical to, it
     */
    private static Hash samePrefixDifferentTail(int first) {
        byte[] data = new byte[32];
        data[0] = (byte) first;
        data[31] = (byte) 0xff;
        return new Hash(data);
    }
}
