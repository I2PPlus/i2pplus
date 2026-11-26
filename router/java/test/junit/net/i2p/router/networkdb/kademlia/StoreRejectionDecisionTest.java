package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Base32;
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
 * <p>Printing the full forms did not settle it either: every key-mismatch
 * rejection in a 1 GB log set printed the same 52 characters twice and then
 * named byte 0 as the first difference, which cannot both be true. The last
 * group of cases pins that reasoning and what the line now says when a hash's
 * cached form no longer describes its bytes.
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
     * line contradicts itself, so the refused LeaseSet's full form goes on a
     * continuation line: the only way a reader can see where the two hashes
     * part company. The key's full form is not repeated - the failure line
     * already names that destination.
     *
     * @since 0.9.72+
     */
    @Test
    public void testIdenticalShortFormsPrintTheFullForms() {
        Hash key = hash(0x01);
        Hash ls = samePrefixDifferentTail(0x01);
        String clause =
            KademliaNetworkDatabaseFacade.fullHashClause(key, ls, true);
        assertTrue("LeaseSet on its own line: " + clause,
                   clause.contains("\n* LeaseSet: " + ls.toBase32()));
        assertFalse("the key is not named twice in full: " + clause,
                    clause.contains("\n* Key: "));
        assertTrue("where they part company: " + clause,
                   clause.contains("First difference at byte 31 of 32"));
        assertTrue("two continuation lines: " + clause,
                   clause.split("\n").length == 3);
    }

    /**
     * Two hashes that really are the same 32 bytes, refused even though the key
     * check is what failed. The line has to say the hashes agree rather than
     * leave a reader to compare 52 characters by eye and conclude the key check
     * is broken.
     *
     * @since 0.9.72+
     */
    @Test
    public void testByteIdenticalHashesFlagTheContradiction() {
        Hash key = hash(0x01);
        Hash same = hash(0x01);
        String clause = KademliaNetworkDatabaseFacade.fullHashClause(key,
                                                                    same, true);
        assertTrue("the verdict is there: " + clause,
                   clause.contains("The two hashes are byte-identical"));
        assertTrue("and it points at what disagrees: " + clause,
                   clause.contains("-> The key check disagrees"));
        assertFalse("no byte offset when there is none: " + clause,
                    clause.contains("First difference"));
    }

    /**
     * The live case: a LeaseSet expired 39h ago. The key check passed, so the
     * reason on the first line is the whole story and the hash clause adds
     * nothing - 4 of these in one log set, each spending three lines to repeat
     * a destination the line had already named.
     *
     * @since 0.9.72+
     */
    @Test
    public void testAStaleEntryAddsNothing() {
        Hash key = hash(0x01);
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             key, hash(0x01), false));
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             key, samePrefixDifferentTail(0x01), false));
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
                             hash(0x01), hash(0x02), true));
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             hash(0x01), null, true));
        assertEquals("", KademliaNetworkDatabaseFacade.fullHashClause(
                             null, hash(0x01), true));
    }

    /**
     * The premise the finding rests on: a base32 form cannot outlive a change
     * to byte 0 without changing, because its first character is the top five
     * bits of that byte and its second carries the other three. So a failure
     * line printing one form twice beside "first difference at byte 0" is
     * printing a stale rendering, not a matching pair.
     *
     * @since 0.9.72+
     */
    @Test
    public void testByteZeroAlwaysShowsInTheFirstTwoCharacters() {
        Hash h = hash(0x01);
        String before = h.toBase32();
        h.toByteArray()[0] = (byte) 0x80;
        String after = Base32.encode(h.toByteArray()) + ".b32.i2p";
        assertFalse("byte 0 left the form untouched: " + before,
                    before.substring(0, 2).equals(after.substring(0, 2)));
    }

    /**
     * The live case: the key's form was rendered long before the store was
     * refused, and its backing array was rewritten in between, so the two forms
     * agree while the bytes do not. The line has to name that and show what the
     * bytes hold now, and it must not accuse the side that is honest.
     *
     * @since 0.9.72+
     */
    @Test
    public void testAStaleFormIsNamedAndItsLiveBytesShown() {
        Hash key = hash(0x01);
        Hash ls = hash(0x01);
        // Rendered once up front, as other lines about this destination do
        key.toShortBase32();
        // and then the backing array rewritten under the live hash
        key.toByteArray()[0] = (byte) 0x7f;
        String clause =
            KademliaNetworkDatabaseFacade.fullHashClause(key, ls, true);
        assertTrue("the contradiction is named: " + clause,
                   clause.contains("cached, not live"));
        assertTrue("the byte offset is still there: " + clause,
                   clause.contains("First difference at byte 0 of 32"));
        String live = Base32.encode(key.toByteArray()) + ".b32.i2p";
        assertTrue("what the bytes hold now: " + clause,
                   clause.contains("\n* Key bytes now: " + live));
        assertFalse("the honest side is not accused: " + clause,
                    clause.contains("LeaseSet bytes now:"));
    }

    /**
     * A hash whose cached form still matches its bytes is not reported as
     * stale, so an ordinary mismatch stays the two lines it was.
     *
     * @since 0.9.72+
     */
    @Test
    public void testAHonestMismatchClaimsNoStaleForm() {
        Hash key = samePrefixDifferentTail(0x01);
        Hash ls = hash(0x01);
        key.toShortBase32();
        ls.toShortBase32();
        String clause =
            KademliaNetworkDatabaseFacade.fullHashClause(key, ls, true);
        assertTrue("still names the byte: " + clause,
                   clause.contains("First difference at byte 31 of 32"));
        assertFalse("no stale-form claim: " + clause,
                    clause.contains("cached, not live"));
        assertEquals("the LeaseSet form plus the byte offset: " + clause,
                     clause.split("\n").length, 3);
    }

    /**
     * When both sides were rewritten, both live forms go on the line - the
     * reader cannot tell which one to distrust from a single live form.
     *
     * @since 0.9.72+
     */
    @Test
    public void testBothStaleFormsAreShown() {
        Hash key = hash(0x01);
        Hash ls = hash(0x01);
        key.toShortBase32();
        ls.toShortBase32();
        key.toByteArray()[0] = (byte) 0x7f;
        ls.toByteArray()[31] = (byte) 0x55;
        String clause =
            KademliaNetworkDatabaseFacade.fullHashClause(key, ls, true);
        assertTrue("key live form: " + clause,
                   clause.contains("Key bytes now:"));
        assertTrue("LeaseSet live form: " + clause,
                   clause.contains("LeaseSet bytes now:"));
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
