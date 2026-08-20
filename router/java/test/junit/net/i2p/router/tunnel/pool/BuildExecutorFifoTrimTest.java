package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * FIFO trimming of the recent-build-id set: keeps the newest entries and
 * drops the oldest, so late duplicate replies are still detected.
 *
 * @since 0.9.71+
 */
public class BuildExecutorFifoTrimTest {

    @Test
    public void testNoTrimUnderLimit() {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(1L);
        ids.add(2L);
        BuildExecutor.trimFifo(ids, 5);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(2L));
    }

    @Test
    public void testTrimDropsOldest() {
        Set<Long> ids = new LinkedHashSet<>();
        for (long i = 1; i <= 130; i++) {
            ids.add(i);
        }
        BuildExecutor.trimFifo(ids, 128);
        assertEquals(128, ids.size());
        // oldest two (1, 2) dropped
        assertFalse(ids.contains(1L));
        assertFalse(ids.contains(2L));
        // newest retained
        assertTrue(ids.contains(130L));
        assertTrue(ids.contains(129L));
    }

    @Test
    public void testTrimPreservesOrder() {
        Set<Long> ids = new LinkedHashSet<>();
        for (long i = 1; i <= 130; i++) {
            ids.add(i);
        }
        BuildExecutor.trimFifo(ids, 128);
        Long first = ids.iterator().next();
        assertEquals(Long.valueOf(3), first);
    }
}