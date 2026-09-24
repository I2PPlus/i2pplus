package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link TestJob#removalThreshold(int, int)} and
 * {@link TestJob#shouldDeferFailingRetest(int, int)} — thin-pool guards that
 * raise the consecutive-failure removal bar and defer ASAP retests while the
 * pool has ≤ 2 remaining tunnels.
 *
 * @since 0.9.71+
 */
public class TestJobRemovalThresholdTest {

    // ---------- baseRemovalThreshold ----------

    @Test
    public void testBaseThresholdHealthy() {
        assertEquals(3, TestJob.baseRemovalThreshold(false));
    }

    @Test
    public void testBaseThresholdDegraded() {
        assertEquals(5, TestJob.baseRemovalThreshold(true));
    }

    // ---------- removalThreshold ----------

    @Test
    public void testThinPoolRaisesBarByTwo() {
        assertEquals(5, TestJob.removalThreshold(3, 2));
        assertEquals(7, TestJob.removalThreshold(5, 2));
        assertEquals(5, TestJob.removalThreshold(3, 1));
        assertEquals(5, TestJob.removalThreshold(3, 0));
        assertEquals(5, TestJob.removalThreshold(3, -1));
    }

    @Test
    public void testAdequatePoolKeepsBaseBar() {
        assertEquals(3, TestJob.removalThreshold(3, 3));
        assertEquals(3, TestJob.removalThreshold(3, 4));
        assertEquals(3, TestJob.removalThreshold(3, 10));
        assertEquals(5, TestJob.removalThreshold(5, 3));
        assertEquals(5, TestJob.removalThreshold(5, 8));
    }

    // ---------- shouldDeferFailingRetest ----------

    @Test
    public void testDeferWhenThinAndFailing() {
        assertTrue(TestJob.shouldDeferFailingRetest(2, 1));
        assertTrue(TestJob.shouldDeferFailingRetest(1, 3));
        assertTrue(TestJob.shouldDeferFailingRetest(0, 1));
    }

    @Test
    public void testNoDeferWhenHealthyPool() {
        assertFalse(TestJob.shouldDeferFailingRetest(3, 1));
        assertFalse(TestJob.shouldDeferFailingRetest(10, 5));
    }

    @Test
    public void testNoDeferWhenNoFailures() {
        assertFalse(TestJob.shouldDeferFailingRetest(2, 0));
        assertFalse(TestJob.shouldDeferFailingRetest(0, 0));
        assertFalse(TestJob.shouldDeferFailingRetest(1, 0));
    }
}
