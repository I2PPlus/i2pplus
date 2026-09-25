package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.TunnelId;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;

import org.junit.Test;

/**
 * Tests for the restart/shutdown drain of the batched first-test subsystem:
 * batch state is isolated per router context ({@link TestJob#batchState}),
 * {@link TestJob#beginBatchReset} empties everything a restart would
 * otherwise outlive (buffer, key set, pump flag, gauges, live instances)
 * and refuses new offers until {@link TestJob#endBatchReset} reopens the
 * context, and a job-queue shutdown leaves the context permanently quiesced
 * because the queue never comes back.
 *
 * @since 0.9.71+
 */
public class TestJobBatchResetTest {

    private static final long NOW = 1_000_000L;

    private static RouterContext offerCtx() {
        RouterContext ctx = mock(RouterContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(NOW);
        when(ctx.clock()).thenReturn(clock);
        when(ctx.router()).thenReturn(mock(Router.class));
        JobQueue jq = mock(JobQueue.class);
        when(jq.isAlive()).thenReturn(true);
        when(ctx.jobQueue()).thenReturn(jq);
        when(ctx.statManager()).thenReturn(mock(StatManager.class));
        return ctx;
    }

    private static TunnelPool livePool() {
        TunnelPool pool = mock(TunnelPool.class);
        TunnelPoolSettings settings = mock(TunnelPoolSettings.class);
        when(settings.getDestinationNickname()).thenReturn("dest");
        when(pool.getSettings()).thenReturn(settings);
        when(pool.isAlive()).thenReturn(true);
        return pool;
    }

    private static PooledTunnelCreatorConfig cfg(TunnelPool pool, long tunnelId) {
        PooledTunnelCreatorConfig cfg = mock(PooledTunnelCreatorConfig.class);
        when(cfg.getTunnelPool()).thenReturn(pool);
        when(cfg.isInbound()).thenReturn(true);
        TunnelId tid = mock(TunnelId.class);
        when(tid.getTunnelId()).thenReturn(tunnelId);
        when(cfg.getReceiveTunnelId(0)).thenReturn(tid);
        when(cfg.getExpiration()).thenReturn(NOW + 10 * 60_000L);
        when(cfg.getTestStatus()).thenReturn(TunnelTestStatus.UNTESTED);
        when(pool.listTunnels()).thenReturn(Collections.<TunnelInfo>singletonList(cfg));
        return cfg;
    }

    private static void bufferOne(TestJob.BatchState state, long tunnelId) {
        TestJob.PendingTest p = new TestJob.PendingTest(
                cfg(livePool(), tunnelId), null);
        synchronized (state.bufferLock) {
            state.firstTestBuffer.addLast(p);
            if (p.key != null) {
                state.bufferedKeys.add(p.key);
            }
        }
    }

    // ---------------- per-context state (row: static dispatcher state) ----------------

    @Test
    public void testTwoContextsGetDistinctStates() {
        RouterContext a = offerCtx();
        RouterContext b = offerCtx();
        TestJob.BatchState sa = TestJob.batchState(a);
        TestJob.BatchState sb = TestJob.batchState(b);
        assertNotSame("each context owns its own batch state", sa, sb);
        assertSame("the same context always maps to the same state",
                   sa, TestJob.batchState(a));
    }

    @Test
    public void testGaugesAreScopedToTheirState() {
        TestJob.BatchState a = TestJob.batchState(offerCtx());
        TestJob.BatchState b = TestJob.batchState(offerCtx());
        a.inFlight.set(5);
        assertEquals(0, b.inFlight.get());
    }

    // ---------------- beginBatchReset / endBatchReset (row: restart drain) ----------------

    @Test
    public void testBeginBatchResetDrainsBufferGaugesAndPump() {
        RouterContext ctx = offerCtx();
        TestJob.BatchState state = TestJob.batchState(ctx);
        bufferOne(state, 11L);
        bufferOne(state, 12L);
        state.pumpQueued.set(true);
        state.totalTestJobs.set(4);
        state.inFlight.set(3);
        TestJob.claimPoolTestSlot(state, "client-dest-inbound");
        state.poolInFlight.put("client-dest-inbound", new AtomicInteger(2));
        TestJob instance = mock(TestJob.class);
        state.runningTests.put(Long.valueOf(11), instance);

        TestJob.beginBatchReset(ctx);

        assertTrue("offers must be refused while the reset runs", state.quiesced);
        synchronized (state.bufferLock) {
            assertTrue("buffered candidates reference tunnels the restart replaces",
                       state.firstTestBuffer.isEmpty());
            assertTrue(state.bufferedKeys.isEmpty());
        }
        assertFalse("a queued pump would outlive the tunnels it drains",
                    state.pumpQueued.get());
        assertEquals(0, state.totalTestJobs.get());
        assertEquals(0, state.inFlight.get());
        assertTrue(state.poolInFlight.isEmpty());
        assertTrue(state.poolTestCounts.isEmpty());
        assertTrue(state.runningTests.isEmpty());
        verify(instance).cancelForReset();
    }

    @Test
    public void testOffersRefusedWhileQuiescedAndResumeAfterEnd() {
        RouterContext ctx = offerCtx();
        TestJob.BatchState state = TestJob.batchState(ctx);

        TestJob.beginBatchReset(ctx);
        TunnelPool refusedPool = livePool();
        assertFalse("offers are refused during the drain",
                    TestJob.offerFirstTest(ctx, cfg(refusedPool, 21L), refusedPool));
        synchronized (state.bufferLock) {
            assertTrue(state.firstTestBuffer.isEmpty());
        }

        TestJob.endBatchReset(ctx);
        assertFalse(state.quiesced);
        TunnelPool acceptedPool = livePool();
        assertTrue("the first offer after the reset is accepted",
                   TestJob.offerFirstTest(ctx, cfg(acceptedPool, 22L), acceptedPool));
        synchronized (state.bufferLock) {
            assertEquals(1, state.firstTestBuffer.size());
        }
    }

    @Test
    public void testQuiesceDoesNotLeakAcrossContexts() {
        RouterContext a = offerCtx();
        RouterContext b = offerCtx();
        TestJob.BatchState sa = TestJob.batchState(a);
        TestJob.BatchState sb = TestJob.batchState(b);

        assertTrue(TestJob.offerFirstTest(b, cfg(livePool(), 31L), null));
        sa.inFlight.set(7);

        TestJob.beginBatchReset(a);

        assertTrue(sa.quiesced);
        assertFalse("a restart of one context must not silence another",
                    sb.quiesced);
        synchronized (sb.bufferLock) {
            assertEquals("B's buffered candidate survives A's drain",
                         1, sb.firstTestBuffer.size());
        }
        assertEquals(0, sb.inFlight.get());
        assertTrue("B still accepts offers", TestJob.offerFirstTest(b, cfg(livePool(), 32L), null));
    }

    @Test
    public void testStaleReleaseAfterDrainCannotGoNegative() {
        RouterContext ctx = offerCtx();
        TestJob.BatchState state = TestJob.batchState(ctx);
        state.totalTestJobs.set(5);
        TestJob.InstanceClaims claims = new TestJob.InstanceClaims("client-dest-inbound");

        TestJob.beginBatchReset(ctx);
        claims.releaseTotal(state);

        assertEquals("a release racing the drain must not go negative",
                     0, state.totalTestJobs.get());
    }

    // ---------------- onJobQueueShutdown (row: shutdown drain) ----------------

    @Test
    public void testOnJobQueueShutdownDrainsAndStaysQuiesced() {
        RouterContext ctx = offerCtx();
        TestJob.BatchState state = TestJob.batchState(ctx);
        bufferOne(state, 41L);
        state.totalTestJobs.set(2);

        TestJob.onJobQueueShutdown(ctx);

        assertTrue("a dead queue never accepts work again", state.quiesced);
        assertEquals(0, state.totalTestJobs.get());
        synchronized (state.bufferLock) {
            assertTrue(state.firstTestBuffer.isEmpty());
        }
        assertFalse(TestJob.offerFirstTest(ctx, cfg(livePool(), 42L), null));
    }
}
