package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The ceiling on the runner count: what the operator asked for, what the heap
 * can carry, and what the CPU can actually run.
 *
 * <p>The configured value used to be read once in the constructor and never
 * again, so {@code router.maxJobRunners} was only honoured at startup, and the
 * setter that claimed to handle configuration changes had no callers. The
 * ceiling is now recalculated from the live property every tenth check.
 *
 * @since 0.9.72+
 */
public class JobQueueScalerCeilingTest {

    /** Bytes estimated per runner thread, the divisor every memory term uses. */
    private static final long PER_RUNNER = 2L * 1024 * 1024;

    /**
     * The ceiling's cases. Every case calls the static decision, so the
     * instance holds no state and needs no router or job queue.
     */
    public JobQueueScalerCeilingTest() {
    }

    /**
     * A 512MB heap with 256MB free and 16 cores, asking for 12 runners: the
     * operator's doubling is the binding term at 24, well under 25 from the
     * heap, 64 from the free memory and 32 from the cores.
     */
    @Test
    public void theConfiguredDoublingBindsOnAHealthyRouter() {
        long max = 512L * 1024 * 1024;
        long free = 256L * 1024 * 1024;
        assertEquals(24, JobQueueScaler.runnerCeiling(12, max, free, 16, 4));
    }

    /**
     * A small heap caps the count below what was asked for: 64MB of heap
     * carries 3 runners at the 10% share, against 24 from the config.
     */
    @Test
    public void aSmallHeapCapsTheCount() {
        long max = 64L * 1024 * 1024;
        long free = 32L * 1024 * 1024;
        assertEquals(3, JobQueueScaler.runnerCeiling(12, max, free, 8, 2));
    }

    /**
     * Half of what is still free is what runners may claim, so a heap that has
     * filled up pulls the ceiling down even on a machine with plenty of cores.
     */
    @Test
    public void usedMemoryPullsTheCeilingDown() {
        long max = 512L * 1024 * 1024;
        long plenty = 256L * 1024 * 1024;
        long nearlyFull = 16L * 1024 * 1024;
        int roomy = JobQueueScaler.runnerCeiling(64, max, plenty, 32, 4);
        int tight = JobQueueScaler.runnerCeiling(64, max, nearlyFull, 32, 4);
        assertTrue(roomy > tight);
        // 16MB free, half of it for runners, 2MB each
        assertEquals(4, tight);
    }

    /**
     * Past two threads per core the context switches cost more than the extra
     * runner earns, so the cores cap the count however much heap there is.
     */
    @Test
    public void theCoreCountCapsTheCount() {
        long max = 8L * 1024 * 1024 * 1024;
        assertEquals(20, JobQueueScaler.runnerCeiling(64, max, max, 10, 2));
    }

    /**
     * A machine with few cores still gets the standing floor of 16, the same
     * floor {@link JobQueue#getMaxRunnerCount()} applies, so the scaler and the
     * queue cannot disagree about how many runners may exist.
     */
    @Test
    public void aSmallMachineKeepsAWorkableFloor() {
        long max = 8L * 1024 * 1024 * 1024;
        assertEquals(16, JobQueueScaler.runnerCeiling(64, max, max, 1, 2));
        assertEquals(16, JobQueueScaler.runnerCeiling(64, max, max, 7, 2));
    }

    /**
     * The minimum is not something the scaler may trade away: a heap that
     * cannot afford it still gets it.
     */
    @Test
    public void theCeilingNeverFallsBelowTheMinimum() {
        long max = 4L * 1024 * 1024;
        assertEquals(4, JobQueueScaler.runnerCeiling(12, max, 0L, 1, 4));
    }

    /**
     * A heap with nothing free has no room for runners at all, and the answer
     * is the minimum rather than zero or a negative count.
     */
    @Test
    public void noFreeMemoryStillKeepsTheMinimum() {
        long max = 512L * 1024 * 1024;
        assertEquals(2, JobQueueScaler.runnerCeiling(12, max, 0L, 8, 2));
    }

    /**
     * The divisor is 2MB per runner, so the memory terms land on whole runners
     * and never round up into a thread the heap cannot hold.
     */
    @Test
    public void memoryTermsCountWholeRunners() {
        long max = PER_RUNNER * 10;
        // 10% of 20MB is 2MB, one runner
        assertEquals(1, JobQueueScaler.runnerCeiling(12, max, max, 8, 1));
    }
}
