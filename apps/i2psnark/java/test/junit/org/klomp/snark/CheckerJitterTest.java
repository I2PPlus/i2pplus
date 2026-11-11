package org.klomp.snark;

import static org.junit.Assert.*;

import net.i2p.util.RandomSource;
import org.junit.Test;

/**
 * Tests for the timer jitter added to the per-torrent peer checker and the global
 * idle checker. Both used to re-arm on an exact period, so torrents started together
 * stayed in lockstep for their whole life: their checks, their file cleanups and their
 * local DHT announces all landed on the same instant, bunching the work on the shared
 * timer executor and on the network.
 *
 * <p>What matters is that the spread is real and bounded. A period that could go near
 * zero would spin the timer, and one that drifts upward would slow peer maintenance.
 *
 * @since 0.9.72+
 */
public class CheckerJitterTest {

    /** Idle checker period, mirrored from the private IdleChecker.CHECK_TIME. */
    private static final long IDLE_PERIOD = 63 * 1000L;

    /**
     * Fixture group for the two period helpers; it holds no state.
     */
    public CheckerJitterTest() {}

    /**
     * The peer checker period stays within a second of CHECK_PERIOD, varies between
     * draws, and averages out to the period it replaced.
     */
    @Test
    public void testPeerCheckerPeriodIsBoundedAndVaried() {
        RandomSource r = RandomSource.getInstance();
        long first = PeerCoordinator.checkPeriod(r);
        boolean varied = false;
        long sum = 0;
        int draws = 2000;
        for (int i = 0; i < draws; i++) {
            long p = PeerCoordinator.checkPeriod(r);
            assertTrue("period " + p, p >= PeerCoordinator.CHECK_PERIOD - 1000
                                            && p < PeerCoordinator.CHECK_PERIOD + 1000);
            if (p != first) {
                varied = true;
            }
            sum += p;
        }
        assertTrue("no jitter", varied);
        assertEquals((double) PeerCoordinator.CHECK_PERIOD, sum / (double) draws, 100d);
    }

    /**
     * The idle checker period stays within five seconds of 63 s, varies, and averages
     * out to the period it replaced.
     */
    @Test
    public void testIdleCheckerPeriodIsBoundedAndVaried() {
        RandomSource r = RandomSource.getInstance();
        long first = IdleChecker.checkTime(r);
        boolean varied = false;
        long sum = 0;
        int draws = 2000;
        for (int i = 0; i < draws; i++) {
            long p = IdleChecker.checkTime(r);
            assertTrue("period " + p, p >= IDLE_PERIOD - 5000 && p < IDLE_PERIOD + 5000);
            if (p != first) {
                varied = true;
            }
            sum += p;
        }
        assertTrue("no jitter", varied);
        assertEquals((double) IDLE_PERIOD, sum / (double) draws, 500d);
    }

    /**
     * Two draws from different sources are not forced apart, but the spread makes a
     * collision on the same millisecond unlikely, which is the point: two torrents
     * started together drift apart instead of staying aligned.
     */
    @Test
    public void testTwoCheckersRarelyAgree() {
        RandomSource r = RandomSource.getInstance();
        int same = 0;
        int draws = 1000;
        for (int i = 0; i < draws; i++) {
            if (PeerCoordinator.checkPeriod(r) == PeerCoordinator.checkPeriod(r)) {
                same++;
            }
        }
        assertTrue("same " + same + " of " + draws, same < draws / 5);
    }
}
