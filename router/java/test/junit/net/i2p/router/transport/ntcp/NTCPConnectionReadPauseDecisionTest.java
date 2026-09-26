package net.i2p.router.transport.ntcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the read-queue backpressure decisions extracted from
 * {@link NTCPConnection}, which stop a peer that sends faster than we process
 * from growing the read queue without bound.
 *
 * @since 0.9.71+
 */
public class NTCPConnectionReadPauseDecisionTest {

    private static final int MAX = NTCPConnection.getMaxReadBufs();

    @Test
    public void pauseOnlyAtTheLimit() {
        assertFalse(NTCPConnection.shouldPauseReads(false, 0));
        assertFalse(NTCPConnection.shouldPauseReads(false, 1));
        assertFalse(NTCPConnection.shouldPauseReads(false, MAX - 1));
        assertTrue(NTCPConnection.shouldPauseReads(false, MAX));
        assertTrue(NTCPConnection.shouldPauseReads(false, MAX + 1));
    }

    @Test
    public void pausedStaysPaused() {
        assertFalse(NTCPConnection.shouldPauseReads(true, MAX));
        assertFalse(NTCPConnection.shouldPauseReads(true, MAX + 100));
    }

    @Test
    public void resumeOnlyWhenPausedAndDrained() {
        assertTrue(NTCPConnection.shouldResumeReads(true, true));
        assertFalse(NTCPConnection.shouldResumeReads(true, false));
        assertFalse(NTCPConnection.shouldResumeReads(false, true));
        assertFalse(NTCPConnection.shouldResumeReads(false, false));
    }
}
