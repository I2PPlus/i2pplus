package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * How much of a mailbox one refresh is allowed to fetch.
 *
 * <p>The defect: a refresh turned every UIDL the server listed into a message object, a
 * request, and a temporary file, with no ceiling on the count, the bytes, or the free
 * space left to hold them, and nothing told the user that the list was incomplete. A
 * server that reports a very large mailbox grew the webmail session until the router ran
 * out of heap or inodes.
 *
 * @since 0.9.72+
 */
public class FetchBudgetTest {

    /** Free space treated as plenty, for the cases that are not about the disk. */
    private static final long SPARE = 1024L * 1024 * 1024;
    /** A message size used where the size is what is being tested. */
    private static final int MESSAGE = 4096;

    /** Scratch directory for the free-space checks. */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * A fixture group for the fetch budget; it holds no state.
     *
     * @since 0.9.72+
     */
    public FetchBudgetTest() {}

    /**
     * @param fetched messages fetched so far
     * @param bytes bytes fetched so far
     * @param size the bytes this fetch would cost
     * @return why this fetch may not go ahead, NONE if it may
     */
    private static FetchBudget.Reason reason(int fetched, long bytes, int size) {
        return FetchBudget.stopReason(fetched, bytes, SPARE, size,
                                      FetchBudget.MAX_MESSAGES, FetchBudget.MAX_BYTES,
                                      FetchBudget.MIN_FREE_BYTES);
    }

    /**
     * The count ceiling holds at the boundary: the last allowed message is fetched, the
     * one after it is not, and the reason names the count.
     */
    @Test
    public void testTheCountStopsAtTheCeiling() {
        assertEquals(FetchBudget.Reason.NONE,
                     reason(FetchBudget.MAX_MESSAGES - 1, 0, MESSAGE));
        assertEquals(FetchBudget.Reason.COUNT, reason(FetchBudget.MAX_MESSAGES, 0, MESSAGE));
        // a message of unknown size is still bounded by the count
        assertEquals(FetchBudget.Reason.COUNT, reason(FetchBudget.MAX_MESSAGES, 0, 0));
    }

    /**
     * The byte ceiling counts the message about to be fetched, not only the ones
     * already fetched, so a refresh cannot end up over its allowance by one message.
     */
    @Test
    public void testTheByteCeilingCountsTheMessageBeingFetched() {
        long room = FetchBudget.MAX_BYTES - 1;
        assertEquals(FetchBudget.Reason.BYTES, reason(0, room, MESSAGE));
        assertEquals(FetchBudget.Reason.NONE, reason(0, room, 0));
        assertEquals(FetchBudget.Reason.NONE,
                     reason(0, FetchBudget.MAX_BYTES - MESSAGE, MESSAGE));
        assertEquals(FetchBudget.Reason.BYTES, reason(0, FetchBudget.MAX_BYTES, MESSAGE));
    }

    /**
     * The temp directory has to still have its floor of free space after the fetch, so
     * a disk that is nearly full stops the fetch before it fills the disk rather than
     * after.
     */
    @Test
    public void testTheDiskFloorIsKeptAfterTheFetch() {
        long floor = FetchBudget.MIN_FREE_BYTES;
        assertEquals(FetchBudget.Reason.NONE,
                     FetchBudget.stopReason(0, 0, floor, 0, FetchBudget.MAX_MESSAGES,
                                            FetchBudget.MAX_BYTES, floor));
        assertEquals(FetchBudget.Reason.DISK,
                     FetchBudget.stopReason(0, 0, floor - 1, 0, FetchBudget.MAX_MESSAGES,
                                            FetchBudget.MAX_BYTES, floor));
        assertEquals(FetchBudget.Reason.DISK,
                     FetchBudget.stopReason(0, 0, floor, MESSAGE, FetchBudget.MAX_MESSAGES,
                                            FetchBudget.MAX_BYTES, floor));
    }

    /**
     * A budget counts what it hands out, and refuses once it has handed out the
     * ceiling, which is what stops the loop rather than the loop stopping itself.
     */
    @Test
    public void testTheBudgetCountsWhatItQueues() {
        FetchBudget budget = new FetchBudget(null);
        for (int i = 0; i < FetchBudget.MAX_MESSAGES; i++) {
            assertTrue("refetched at " + i, budget.allows(0));
            budget.queued(0);
        }
        assertEquals(FetchBudget.MAX_MESSAGES, budget.getMessages());
        assertFalse(budget.allows(0));
        assertEquals(FetchBudget.Reason.COUNT, budget.getReason());
    }

    /**
     * Asking whether a fetch fits charges nothing, so a message found on disk after the
     * question does not use up the budget.
     */
    @Test
    public void testAskingDoesNotChargeTheBudget() {
        FetchBudget budget = new FetchBudget(null);
        for (int i = 0; i < 5; i++)
            assertTrue(budget.allows(MESSAGE));
        assertEquals(0, budget.getMessages());
        assertEquals(0, budget.getBytes());
    }

    /**
     * The tally is corrected to what actually arrived, so a server that lists the wrong
     * size cannot fetch past the byte ceiling, and it never runs backwards below zero.
     */
    @Test
    public void testTheTallyFollowsWhatActuallyArrived() {
        FetchBudget budget = new FetchBudget(null);
        budget.queued(1000);
        assertEquals(1000, budget.getBytes());
        budget.correct(4000 - 1000);
        assertEquals(4000, budget.getBytes());
        budget.correct(-10_000);
        assertEquals(0, budget.getBytes());
        assertEquals(1, budget.getMessages());
    }

    /**
     * A negative charge is charged as nothing, so a size the server did not list cannot
     * buy extra allowance.
     */
    @Test
    public void testAnUnknownSizeIsChargedAsNothing() {
        FetchBudget budget = new FetchBudget(null);
        budget.queued(-1);
        assertEquals(0, budget.getBytes());
        assertEquals(1, budget.getMessages());
    }

    /**
     * A real directory with space in it allows a fetch, and one that does not exist
     * does not stop every fetch, which would leave the inbox empty forever.
     */
    @Test
    public void testTheFreeSpaceCheckUsesTheTempDirectory() {
        FetchBudget roomy = new FetchBudget(folder.getRoot());
        assertTrue(roomy.allows(MESSAGE));
        FetchBudget missing = new FetchBudget(new File(folder.getRoot(), "not-there"));
        assertTrue(missing.allows(MESSAGE));
    }

    /**
     * The constants have to be usable together: a refresh is several batches, and the
     * disk floor has to leave room for a message inside the byte allowance.
     */
    @Test
    public void testTheConstantsFitTogether() {
        assertTrue(FetchBudget.BATCH_SIZE > 1);
        assertTrue(FetchBudget.MAX_MESSAGES >= FetchBudget.BATCH_SIZE);
        assertTrue(FetchBudget.MIN_FREE_BYTES > 0);
        assertTrue(FetchBudget.MAX_BYTES > FetchBudget.MIN_FREE_BYTES);
        assertTrue(FetchBudget.HEADER_BYTES > 0);
        assertTrue(FetchBudget.HEADER_BYTES < FetchBudget.MAX_BYTES);
    }

    /**
     * The reason is said in words, in the log and on the page, rather than as an enum
     * name a reader has to look up.
     */
    @Test
    public void testTheReasonIsDescribedInWords() {
        assertTrue(FetchBudget.describe(FetchBudget.Reason.COUNT).contains("message count"));
        assertTrue(FetchBudget.describe(FetchBudget.Reason.BYTES).contains("byte allowance"));
        assertTrue(FetchBudget.describe(FetchBudget.Reason.DISK).contains("free space"));
    }

    /**
     * The defect end to end for the user: a short list says how many messages are
     * missing, in the right number, and tells the reader to refresh again.
     */
    @Test
    public void testTheNoteSaysWhatWasLeftOut() {
        String one = MailCache.fetchNote(1, FetchBudget.Reason.COUNT);
        assertTrue(one, one.startsWith("1 message is not shown yet"));
        String many = MailCache.fetchNote(7, FetchBudget.Reason.COUNT);
        assertTrue(many, many.startsWith("7 messages are not shown yet"));
        assertNotEquals(one, many);
        for (FetchBudget.Reason reason : FetchBudget.Reason.values()) {
            String note = MailCache.fetchNote(3, reason);
            assertTrue(reason.name(), note.length() > 10);
            assertTrue(reason.name(), note.toLowerCase().contains("refresh"));
        }
    }
}
