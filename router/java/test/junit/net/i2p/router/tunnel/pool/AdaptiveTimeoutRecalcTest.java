package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * When the adaptive build timeout is recalculated from the sliding results window
 * (A16). The trigger used to re-read the shared write counter and test it twice,
 * so with builds recorded concurrently the counter could pass the boundary
 * between the two reads - or between one thread's write and its check - and no
 * thread acted on it, skipping a recalculation for a whole period. The decision
 * is now made from the sequence number the caller got when it recorded its own
 * build, which is unique per build and never re-read.
 */
public class AdaptiveTimeoutRecalcTest {

    /**
     * Nothing is recalculated before a full period of builds has been recorded.
     */
    @Test
    public void testNothingFiresBeforeTheFirstPeriod() {
        for (int seq = 0; seq < BuildExecutor.ADAPTIVE_TIMEOUT_RECALC_BUILDS - 1; seq++) {
            assertFalse("recalculated after only " + (seq + 1) + " builds",
                        BuildExecutor.recalcAdaptiveTimeout(seq));
        }
    }

    /**
     * Exactly one recalculation per period, on the nose.
     */
    @Test
    public void testExactlyOneFirePerPeriod() {
        int periods = 20;
        int fires = 0;
        for (int seq = 0; seq < periods * BuildExecutor.ADAPTIVE_TIMEOUT_RECALC_BUILDS; seq++) {
            if (BuildExecutor.recalcAdaptiveTimeout(seq)) {
                fires++;
            }
        }
        assertEquals(periods, fires);
        assertTrue("the boundary build did not fire",
                   BuildExecutor.recalcAdaptiveTimeout(BuildExecutor.ADAPTIVE_TIMEOUT_RECALC_BUILDS - 1));
    }

    /**
     * Builds are recorded from several pool threads at once. Each build owns one
     * sequence number, so the periods still each get exactly one recalculation no
     * matter how the threads interleave - the property the counter re-read lost.
     *
     * @throws Exception if joining a recorder thread is interrupted
     */
    @Test
    public void testConcurrentRecordersStillFireOncePerPeriod() throws Exception {
        final int threads = 8;
        final int period = BuildExecutor.ADAPTIVE_TIMEOUT_RECALC_BUILDS;
        final int builds = period * 40;
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger fires = new AtomicInteger();
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<Thread>(threads);
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread() {
                @Override
                public void run() {
                    try {
                        go.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    int seq;
                    while ((seq = next.getAndIncrement()) < builds) {
                        if (BuildExecutor.recalcAdaptiveTimeout(seq)) {
                            fires.incrementAndGet();
                        }
                    }
                }
            };
            workers.add(th);
            th.start();
        }
        go.countDown();
        for (Thread th : workers) {
            th.join(10000);
            assertFalse("a recorder thread hung", th.isAlive());
        }
        assertEquals(builds / period, fires.get());
    }
}
