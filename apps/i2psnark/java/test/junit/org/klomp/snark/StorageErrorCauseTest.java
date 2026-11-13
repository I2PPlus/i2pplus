package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import org.junit.Test;

/**
 * Pins the cause-chain search in {@link StorageError#classify(IOException)} and
 * {@link StorageError#describe(IOException)}. The storage layer wraps a failed write
 * or read in its own exception naming the file it was working on, so the errno text
 * the classifier needs sits on the cause, not on the exception that reaches the
 * torrent. Before the search existed, a full disk arrived as an unclassifiable
 * error, which is transient, and the piece was re-downloaded forever.
 *
 * @since 0.9.72+
 */
public class StorageErrorCauseTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the classifier is static and carries no state.
     *
     * @since 0.9.72+
     */
    public StorageErrorCauseTest() {}

    /**
     * The wrapper the storage layer builds on a failed write: the file name in the
     * message, the real failure as the cause.
     *
     * @param cause what went wrong at the filesystem
     * @return the wrapper as {@code Storage.writePiece()} throws it
     */
    private static IOException writeWrapper(IOException cause) {
        IOException wrapper = new IOException("Error writing /data/torrent/file.dat");
        wrapper.initCause(cause);
        return wrapper;
    }

    /**
     * A full disk behind the write wrapper is NO_SPACE, which is fatal, so the
     * torrent stops with a reason instead of re-downloading the piece forever.
     */
    @Test
    public void testErrnoOnTheCauseIsFound() {
        IOException ioe = writeWrapper(new IOException("No space left on device"));
        assertEquals(StorageError.NO_SPACE, StorageError.classify(ioe));
        assertTrue(StorageError.classify(ioe).isFatal());
        assertEquals("No space left on device", StorageError.describe(ioe));
    }

    /**
     * A typed NIO failure two links down is classified by its type, with no message
     * parsing of the wrapper.
     */
    @Test
    public void testTypedExceptionOnTheCauseIsFound() {
        IOException ioe = writeWrapper(new AccessDeniedException("/data/torrent/file.dat"));
        assertEquals(StorageError.PERMISSION, StorageError.classify(ioe));
        assertTrue(StorageError.classify(ioe).isFatal());
    }

    /**
     * A reason three links down is still found: the storage layer wraps once to name
     * the file and NIO may wrap again to name the syscall.
     */
    @Test
    public void testReasonThreeLinksDownIsFound() {
        IOException mid = new IOException("RandomAccessFile.write");
        mid.initCause(new IOException("Read-only file system"));
        IOException ioe = writeWrapper(mid);
        assertEquals(StorageError.READ_ONLY, StorageError.classify(ioe));
    }

    /**
     * The outermost link that says something wins, so a wrapper that already carries
     * the reason is never overridden by a vaguer cause.
     */
    @Test
    public void testOutermostReasonWins() {
        IOException ioe = writeWrapper(new IOException("something odd"));
        IOException outer = new IOException("Disk quota exceeded");
        outer.initCause(ioe);
        assertEquals(StorageError.QUOTA, StorageError.classify(outer));
    }

    /**
     * A chain with no reason anywhere stays OTHER and stays transient, so a genuinely
     * odd failure still only drops the request rather than stopping the torrent.
     */
    @Test
    public void testUnclassifiableChainStaysOther() {
        IOException ioe = writeWrapper(new IOException("widget frobnicated"));
        assertEquals(StorageError.OTHER, StorageError.classify(ioe));
        assertFalse(StorageError.classify(ioe).isFatal());
    }

    /**
     * Two exceptions that name each other as each other's cause form a cycle, and
     * the search has to terminate on it rather than spin.
     */
    @Test
    public void testCauseCycleTerminates() {
        IOException outer = new IOException("Error writing /data/torrent/file.dat");
        IOException inner = new IOException("RandomAccessFile.write");
        outer.initCause(inner);
        inner.initCause(outer);
        assertEquals(StorageError.OTHER, StorageError.classify(outer));
        assertEquals("I/O error: RandomAccessFile.write", StorageError.describe(outer));
    }

    /**
     * A chain longer than the search bound is not walked to its end, so a pathological
     * chain costs a bounded number of comparisons. The bound is eight links, well
     * past the two or three a real storage failure produces.
     */
    @Test
    public void testLongChainIsBounded() {
        IOException ioe = new IOException("No space left on device");
        for (int i = 0; i < 40; i++) {
            IOException outer = new IOException("wrapper " + i);
            outer.initCause(ioe);
            ioe = outer;
        }
        assertEquals(StorageError.OTHER, StorageError.classify(ioe));
    }

    /**
     * An unclassifiable chain describes itself with the deepest message it has, which
     * is the operating system's reason rather than the file name the wrapper carries.
     */
    @Test
    public void testDescribeUsesTheDeepestMessage() {
        IOException ioe = writeWrapper(new IOException("widget frobnicated"));
        assertEquals("I/O error: widget frobnicated", StorageError.describe(ioe));
    }

    /**
     * A chain whose links carry no message at all still describes as the plain
     * classification label, with no trailing separator.
     */
    @Test
    public void testDescribeWithoutAnyMessage() {
        IOException inner = new IOException((String) null);
        IOException outer = new IOException((String) null);
        outer.initCause(inner);
        assertEquals("I/O error", StorageError.describe(outer));
        assertNull(StorageError.deepestMessage(outer));
    }
}
