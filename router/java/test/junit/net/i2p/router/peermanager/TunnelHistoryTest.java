package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.router.RouterContext;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The per-peer tunnel record: what each build, rejection, failure and test
 * round leaves behind, how those counters decay, and what survives a restart
 * through the profile file.
 */
public class TunnelHistoryTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        try {
            _ctx = new RouterContext(null);
        } catch (Exception e) {
            _ctx = null;
        }
    }

    @Test
    public void testCreation() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertNotNull(th);
    }

    @Test
    public void testInitialValues() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertEquals(0, th.getLifetimeAgreedTo());
        assertEquals(0, th.getLifetimeRejected());
        assertEquals(0, th.getLifetimeFailed());
    }

    @Test
    public void testIncrementAgreedTo() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementAgreedTo();
        assertEquals(1, th.getLifetimeAgreedTo());
        assertTrue(th.getLastAgreedTo() > 0);
    }

    @Test
    public void testIncrementRejectedProbabalistic() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT);
        assertEquals(1, th.getLifetimeRejected());
        assertTrue(th.getLastRejectedProbabalistic() > 0);
    }

    @Test
    public void testIncrementRejectedBandwidth() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        assertEquals(1, th.getLifetimeRejected());
        assertTrue(th.getLastRejectedBandwidth() > 0);
    }

    @Test
    public void testIncrementRejectedTransient() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementRejected(TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD);
        assertEquals(1, th.getLifetimeRejected());
        assertTrue(th.getLastRejectedTransient() > 0);
    }

    @Test
    public void testIncrementFailed() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementFailed(100);
        assertEquals(1, th.getLifetimeFailed());
        assertTrue(th.getLastFailed() > 0);
    }

    @Test
    public void testMultipleIncrements() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        for (int i = 0; i < 10; i++) {
            th.incrementAgreedTo();
        }
        assertEquals(10, th.getLifetimeAgreedTo());

        for (int i = 0; i < 3; i++) {
            th.incrementRejected(TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        }
        assertEquals(3, th.getLifetimeRejected());

        for (int i = 0; i < 2; i++) {
            th.incrementFailed(100);
        }
        assertEquals(2, th.getLifetimeFailed());
    }

    @Test
    public void testRejectionRateStat() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertNotNull(th.getRejectionRate());
    }

    @Test
    public void testFailedRateStat() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        assertNotNull(th.getFailedRate());
    }

    @Test
    public void testRejectConstants() {
        assertEquals(10, TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT);
        assertEquals(20, TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD);
        assertEquals(30, TunnelHistory.TUNNEL_REJECT_BANDWIDTH);
        assertEquals(50, TunnelHistory.TUNNEL_REJECT_CRIT);
    }

    /**
     * A round that came back is counted, not just timestamped. The success
     * count is what any ratio against the failure count needs.
     */
    @Test
    public void testIncrementTestSucceeded() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementTestSucceeded();
        assertEquals(1, th.getLifetimeTestSucceeded());
        assertTrue(th.getLastTestedSuccessfully() > 0);
    }

    /**
     * A round that did not come back is charged too. Before this the test
     * cycle wrote nothing to a profile on a failed round, so a peer's test
     * record was only the rounds that answered.
     */
    @Test
    public void testIncrementTestFailed() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        th.incrementTestFailed();
        assertEquals(1, th.getLifetimeTestFailed());
        assertTrue(th.getLastTestFailed() > 0);
    }

    /**
     * Passing and failing a round are separate records: a success does not
     * erase the failure it sits beside, or the ratio between them is lost.
     */
    @Test
    public void testTestCountersDoNotShadowEachOther() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        for (int i = 0; i < 4; i++) {
            th.incrementTestFailed();
        }
        for (int i = 0; i < 3; i++) {
            th.incrementTestSucceeded();
        }
        assertEquals(4, th.getLifetimeTestFailed());
        assertEquals(3, th.getLifetimeTestSucceeded());
    }

    /**
     * The test counters decay with the other lifetime counters, so a peer is
     * not held to its whole history and a bad patch is outlived.
     */
    @Test
    public void testTestCountersDecay() {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        for (int i = 0; i < 8; i++) {
            th.incrementTestFailed();
            th.incrementTestSucceeded();
        }
        th.decayLifetimeCounters(System.currentTimeMillis() +
                                 TunnelHistory.DECAY_INTERVAL_MS + 1000L);
        assertEquals(6, th.getLifetimeTestFailed());
        assertEquals(6, th.getLifetimeTestSucceeded());
    }

    /**
     * The test counters and their timestamps survive a restart, the way the
     * build counters do, so a peer's record is not rebuilt from nothing every
     * boot.
     *
     * @throws Exception on a stream the store or the load cannot use
     */
    @Test
    public void testTestCountersSurviveStoreAndLoad() throws Exception {
        Assume.assumeTrue(_ctx != null);
        TunnelHistory th = new TunnelHistory(_ctx, "test.group");
        for (int i = 0; i < 5; i++) {
            th.incrementTestFailed();
        }
        for (int i = 0; i < 2; i++) {
            th.incrementTestSucceeded();
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        th.store(buf, false);
        Properties props = new Properties();
        props.load(new ByteArrayInputStream(buf.toByteArray()));

        TunnelHistory restored = new TunnelHistory(_ctx, "test.group");
        restored.load(props);
        assertEquals(5, restored.getLifetimeTestFailed());
        assertEquals(2, restored.getLifetimeTestSucceeded());
        assertEquals(th.getLastTestFailed(), restored.getLastTestFailed());
        assertEquals(th.getLastTestedSuccessfully(),
                     restored.getLastTestedSuccessfully());
    }
}
