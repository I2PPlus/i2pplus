package net.i2p.client.streaming;

import java.io.IOException;

import net.i2p.client.I2PSessionException;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for socket-manager session failure reporting.
 *
 * <p>Two defects: the catch logged at ERROR with the message only, so an orderly
 * self-interrupt looked identical to a real fault and the log could not say which
 * thread or which setup stage was involved.
 *
 * @since 0.9.71+
 */
public class SocketManagerFactoryFailureTest {

    private static I2PSessionException interrupt(String stage) {
        return new I2PSessionException(stage, new InterruptedException());
    }

    // ---- isSessionInterrupt ----

    @Test
    public void anInterruptedCauseIsDetected() {
        assertTrue(I2PSocketManagerFactory.isSessionInterrupt(interrupt("Interrupted")));
    }

    @Test
    public void aRealFaultIsNotAnInterrupt() {
        assertFalse(I2PSocketManagerFactory.isSessionInterrupt(
                new I2PSessionException("refused", new IOException("connection refused"))));
    }

    @Test
    public void aMessageSayingInterruptedIsStillAnInterrupt() {
        // The stage name must not make this look like a hard failure.
        assertTrue(I2PSocketManagerFactory.isSessionInterrupt(
                interrupt("Interrupted waiting for the session to open")));
    }

    @Test
    public void noCauseIsNotAnInterrupt() {
        assertFalse(I2PSocketManagerFactory.isSessionInterrupt(
                new I2PSessionException("Interrupted")));
    }

    @Test
    public void nullIsNotAnInterrupt() {
        assertFalse(I2PSocketManagerFactory.isSessionInterrupt(null));
    }

    // ---- describeSessionFailure ----

    /** The user's ask: name the thread, so the interrupted work is identifiable. */
    @Test
    public void theThreadNameIsAlwaysIncluded() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(
                interrupt("Interrupted waiting for the session to open"), "Torrent magnet 1c18a5");
        assertTrue(msg, msg.contains("[Torrent magnet 1c18a5]"));
    }

    /** And name the stage, so we know what was interrupted. */
    @Test
    public void theStageIsIncluded() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(
                interrupt("Interrupted waiting for the destination to be published"), "worker-3");
        assertTrue(msg, msg.contains("waiting for the destination to be published"));
    }

    @Test
    public void anInterruptSaysSoRatherThanSayingError() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(interrupt("Interrupted"), "w-1");
        assertTrue(msg, msg.contains("interrupted"));
        assertFalse("an orderly stop must not read as an error", msg.startsWith("Error"));
    }

    @Test
    public void aRealFaultStillReadsAsAnError() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(
                new I2PSessionException("refused", new IOException("connection refused")), "w-2");
        assertTrue(msg, msg.startsWith("Error creating session for socket manager"));
        assertTrue("the cause must be surfaced for a real fault", msg.contains("connection refused"));
    }

    @Test
    public void aRealFaultIncludesTheCauseType() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(
                new I2PSessionException("refused", new IOException("connection refused")), "w-2");
        assertTrue(msg, msg.contains("IOException"));
    }

    /** The message-only version was what made the original log unreadable. */
    @Test
    public void anInterruptOmitsTheRedundantCause() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(interrupt("Interrupted"), "w-3");
        assertFalse("the stage message already says interrupted",
                    msg.contains("java.lang.InterruptedException"));
    }

    @Test
    public void aMessageLessFailureFallsBackToTheType() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(
                new I2PSessionException(null, new IOException("x")), "w-4");
        assertTrue(msg, msg.contains("I2PSessionException"));
    }

    @Test
    public void aNullFailureStillNamesTheThread() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(null, "w-5");
        assertTrue(msg, msg.contains("[w-5]"));
        assertTrue(msg, msg.contains("no exception given"));
    }

    @Test
    public void theDescriptionStaysOnOneLine() {
        String msg = I2PSocketManagerFactory.describeSessionFailure(
                interrupt("Interrupted waiting for the session to open"), "Torrent magnet 1c18a5");
        assertFalse("a multi-line log entry is harder to grep", msg.contains("\n"));
    }
}