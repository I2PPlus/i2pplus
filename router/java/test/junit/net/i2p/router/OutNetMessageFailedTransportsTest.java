package net.i2p.router;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The failed-transport list of an OutNetMessage is read on the send path of
 * every transport - including from toString(), which formats it into a log
 * message - while another transport may be recording a failure of its own at
 * the same time, so it has to survive being modified while it is walked.
 * A plain ArrayList did not: the walk threw ConcurrentModificationException
 * inside the job that was only trying to log the message.
 */
public class OutNetMessageFailedTransportsTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        _ctx = RouterTestHelper.getContext();
    }

    private static OutNetMessage message() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        return new OutNetMessage(_ctx);
    }

    /**
     * Walking the list, recording another failure, then carrying on with the
     * walk: the caller sees the failures recorded before the walk started, and
     * nothing throws.
     */
    @Test
    public void testRecordingAFailureDoesNotBreakAnOngoingWalk() {
        OutNetMessage msg = message();
        assertEquals(0, msg.transportFailed("SSU"));
        List<String> failed = msg.getFailedTransports();
        Iterator<String> it = failed.iterator();
        msg.transportFailed("NTCP");
        assertTrue(failed.toString(), it.hasNext());
        assertEquals("SSU", it.next());
        assertFalse("a walk sees only the failures recorded before it started", it.hasNext());
    }

    /**
     * toString() is where the crash came through, since it appends the live
     * list. It still reports both failures once they are recorded.
     */
    @Test
    public void testToStringWhileRecordingAFailure() {
        OutNetMessage msg = message();
        msg.transportFailed("SSU");
        Iterator<String> it = msg.getFailedTransports().iterator();
        msg.transportFailed("NTCP");
        it.hasNext();
        it.next();
        String s = msg.toString();
        assertTrue(s, s.contains("SSU"));
        assertTrue(s, s.contains("NTCP"));
    }

    /**
     * The counts and contents the bidding paths rely on.
     */
    @Test
    public void testCountAndContents() {
        OutNetMessage msg = message();
        assertEquals(0, msg.getFailedTransportCount());
        assertTrue(msg.getFailedTransports().isEmpty());
        assertEquals("no failures recorded yet", 0, msg.transportFailed("SSU"));
        assertEquals("one failure recorded already", 1, msg.transportFailed("NTCP"));
        assertEquals(2, msg.getFailedTransportCount());
        List<String> failed = msg.getFailedTransports();
        assertEquals(2, failed.size());
        assertTrue(failed.toString(), failed.contains("SSU"));
        assertTrue(failed.toString(), failed.contains("NTCP"));
    }

    /**
     * The shape of the crash, with the threads: transports failing at once while
     * the message is formatted over and over.
     */
    @Test
    public void testConcurrentFailuresWhileFormatting() throws InterruptedException {
        final OutNetMessage msg = message();
        // make sure the list exists before the readers start
        msg.transportFailed("SSU");
        final String[] styles = {"SSU", "NTCP", "SSU2"};
        List<Thread> threads = new ArrayList<>(styles.length);
        for (final String style : styles) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < 50; j++)
                    msg.transportFailed(style);
            }, "failed-" + style);
            threads.add(t);
            t.start();
        }
        for (int i = 0; i < 200; i++)
            assertTrue(msg.toString().contains("Delivery failure on transports"));
        for (Thread t : threads)
            t.join(5000);
        assertEquals(styles.length * 50 + 1, msg.getFailedTransportCount());
    }
}
