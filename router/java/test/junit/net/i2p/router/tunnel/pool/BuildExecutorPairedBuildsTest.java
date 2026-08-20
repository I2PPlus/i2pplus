package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.tunnel.TCConfig;

import org.junit.Test;

/**
 * Unit tests for the pure build-accounting helpers extracted from
 * BuildExecutor.calculatePairedBuilds().
 *
 * @since 0.9.71+
 */
public class BuildExecutorPairedBuildsTest {

    private static final long NOW = 1_000_000L;

    private static TunnelInfo info(int length, long expiration) {
        TunnelInfo info = mock(TunnelInfo.class);
        when(info.getLength()).thenReturn(length);
        when(info.getExpiration()).thenReturn(expiration);
        when(info.getTunnelFailed()).thenReturn(false);
        when(info.getConsecutiveFailures()).thenReturn(0);
        when(info.getTestStatus()).thenReturn(TunnelTestStatus.UNTESTED);
        return info;
    }

    private static TCConfig cfg(int length, long expiration) {
        TCConfig cfg = new TCConfig(mock(RouterContext.class), length, true);
        cfg.setExpiration(expiration);
        return cfg;
    }

    /** production failure path: increment the counter, then refresh the status */
    private static void failOnce(TCConfig cfg) {
        cfg.incrementTestFailures();
        cfg.setTestFailed();
    }

    // ---------------- countExpiryBuckets ----------------

    @Test
    public void testEmptyListAllZeros() {
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>emptyList(), NOW, false);
        assertEquals(0, b.expire30s);
        assertEquals(0, b.expire90s);
        assertEquals(0, b.expire150s);
        assertEquals(0, b.expire210s);
        assertEquals(0, b.expire270s);
        assertEquals(0, b.expire330s);
        assertEquals(0, b.expireLater);
        assertEquals(0, b.fallbackCount);
        assertEquals(0, b.goodCount);
        assertEquals(0L, b.totalLatency);
    }

    @Test
    public void testBucketBoundaries() {
        // each tunnel expires exactly at one window boundary; windows are
        // cumulative so the boundary lands in the window it falls in
        List<TunnelInfo> tunnels = new ArrayList<TunnelInfo>();
        tunnels.add(cfg(3, NOW));             // expired -> 30s
        tunnels.add(cfg(3, NOW + 30 * 1000)); // 30s
        tunnels.add(cfg(3, NOW + 30 * 1000 + 1)); // 90s
        tunnels.add(cfg(3, NOW + 90 * 1000)); // 90s
        tunnels.add(cfg(3, NOW + 90 * 1000 + 1)); // 150s
        tunnels.add(cfg(3, NOW + 150 * 1000)); // 150s
        tunnels.add(cfg(3, NOW + 150 * 1000 + 1)); // 210s
        tunnels.add(cfg(3, NOW + 210 * 1000)); // 210s
        tunnels.add(cfg(3, NOW + 210 * 1000 + 1)); // 270s
        tunnels.add(cfg(3, NOW + 270 * 1000)); // 270s
        tunnels.add(cfg(3, NOW + 270 * 1000 + 1)); // 330s
        tunnels.add(cfg(3, NOW + 330 * 1000)); // 330s
        tunnels.add(cfg(3, NOW + 330 * 1000 + 1)); // later
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(tunnels, NOW, false);
        assertEquals(2, b.expire30s);
        assertEquals(2, b.expire90s);
        assertEquals(2, b.expire150s);
        assertEquals(2, b.expire210s);
        assertEquals(2, b.expire270s);
        assertEquals(2, b.expire330s);
        assertEquals(1, b.expireLater);
        assertEquals(0, b.fallbackCount);
    }

    @Test
    public void testGoodBucketTrackedInParallel() {
        TCConfig good = cfg(3, NOW + 90 * 1000);
        good.testSuccessful(250);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(good), NOW, false);
        assertEquals(1, b.expire90s);
        assertEquals(1, b.goodExpire90s);
        assertEquals(1, b.goodCount);
        assertEquals(250L, b.totalLatency);
    }

    @Test
    public void testGoodWithSingleFailureIsStillGood() {
        // status GOOD and <= 1 consecutive failures counts as good
        TunnelInfo info = mock(TunnelInfo.class);
        when(info.getLength()).thenReturn(3);
        when(info.getExpiration()).thenReturn(NOW + 90 * 1000);
        when(info.getTunnelFailed()).thenReturn(false);
        when(info.getConsecutiveFailures()).thenReturn(1);
        when(info.getTestStatus()).thenReturn(TunnelTestStatus.GOOD);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(info), NOW, false);
        assertEquals(1, b.goodCount);
        assertEquals(1, b.goodExpire90s);
    }

    @Test
    public void testFailingStatusNotGood() {
        // FAILING (2 failures) is counted in the bucket but never as good
        TCConfig failing = cfg(3, NOW + 90 * 1000);
        failing.testSuccessful(100);
        failOnce(failing);
        failOnce(failing);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(failing), NOW, false);
        assertEquals(1, b.expire90s);
        assertEquals(0, b.goodExpire90s);
        assertEquals(0, b.goodCount);
        assertEquals(0L, b.totalLatency);
    }

    @Test
    public void testFailedTunnelSkippedEntirely() {
        // 4 consecutive failures -> FAILED and getTunnelFailed() true
        TCConfig failed = cfg(3, NOW + 30 * 1000);
        failOnce(failed);
        failOnce(failed);
        failOnce(failed);
        failOnce(failed);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(failed), NOW, false);
        assertEquals(0, b.expire30s);
        assertEquals(0, b.goodCount);
    }

    @Test
    public void testAtMaxConsecutiveFailuresStillCounted() {
        // strict >: the 3-failure cap itself is tolerated (not skipped), just not good
        TCConfig deadish = cfg(3, NOW + 30 * 1000);
        deadish.testSuccessful(100);
        failOnce(deadish);
        failOnce(deadish);
        failOnce(deadish);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(deadish), NOW, false);
        assertEquals(1, b.expire30s);
        assertEquals(0, b.goodExpire30s);
    }

    @Test
    public void testTunnelFailedFlagSkipsRegardlessOfFailures() {
        TunnelInfo info = mock(TunnelInfo.class);
        when(info.getLength()).thenReturn(3);
        when(info.getExpiration()).thenReturn(NOW + 30 * 1000);
        when(info.getTunnelFailed()).thenReturn(true);
        when(info.getConsecutiveFailures()).thenReturn(0);
        when(info.getTestStatus()).thenReturn(TunnelTestStatus.GOOD);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(info), NOW, false);
        assertEquals(0, b.expire30s);
        assertEquals(0, b.goodCount);
    }

    @Test
    public void testZeroHopExcludedWhenNotAllowed() {
        TCConfig zh = cfg(1, NOW + 90 * 1000);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(zh), NOW, false);
        assertEquals(1, b.fallbackCount);
        assertEquals(0, b.expire90s);
    }

    @Test
    public void testZeroHopCountedWhenAllowed() {
        TCConfig zh = cfg(1, NOW + 90 * 1000);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(zh), NOW, true);
        assertEquals(0, b.fallbackCount);
        assertEquals(1, b.expire90s);
    }

    @Test
    public void testZeroLatencyNotSummed() {
        TCConfig good = cfg(3, NOW + 90 * 1000);
        good.testSuccessful(0);
        BuildExecutor.ExpiryBuckets b = BuildExecutor.countExpiryBuckets(Collections.<TunnelInfo>singletonList(good), NOW, false);
        assertEquals(1, b.goodCount);
        assertEquals(0L, b.totalLatency);
    }

    // ---------------- computeUrgencyScore ----------------

    @Test
    public void testZeroUsableHighestTier() {
        assertEquals(1 << 20, BuildExecutor.computeUrgencyScore(0, 4));
    }

    @Test
    public void testOneOrTwoUsableMiddleTier() {
        assertEquals(1 << 16, BuildExecutor.computeUrgencyScore(1, 4));
        assertEquals(1 << 16, BuildExecutor.computeUrgencyScore(2, 4));
    }

    @Test
    public void testDeficitScoreForHealthyPools() {
        assertEquals(3, BuildExecutor.computeUrgencyScore(5, 8));
        assertEquals(0, BuildExecutor.computeUrgencyScore(8, 8));
    }

    @Test
    public void testSurplusClampedToZero() {
        assertEquals(0, BuildExecutor.computeUrgencyScore(10, 8));
        assertEquals(0, BuildExecutor.computeUrgencyScore(3, 2));
    }

    // ---------------- collectPairTargets ----------------

    private static TunnelPool pool(boolean alive, Hash dest, int qty, boolean inbound) {
        TunnelPool p = mock(TunnelPool.class);
        when(p.isAlive()).thenReturn(alive);
        TunnelPoolSettings s = mock(TunnelPoolSettings.class);
        when(s.getDestination()).thenReturn(dest);
        when(s.getTotalQuantity()).thenReturn(qty);
        when(s.isInbound()).thenReturn(inbound);
        when(p.getSettings()).thenReturn(s);
        return p;
    }

    @Test
    public void testMaxQuantityPerDirection() {
        Hash dest = new Hash();
        List<TunnelPool> pools = Arrays.asList(pool(true, dest, 2, true),
                                               pool(true, dest, 4, true),
                                               pool(true, dest, 3, false));
        Map<Hash, int[]> targets = BuildExecutor.collectPairTargets(pools);
        int[] dirs = targets.get(dest);
        assertNotNull(dirs);
        assertEquals(4, dirs[0]);
        assertEquals(3, dirs[1]);
    }

    @Test
    public void testDeadPoolSkipped() {
        Hash dest = new Hash();
        List<TunnelPool> pools = Arrays.asList(pool(false, dest, 5, true));
        assertTrue(BuildExecutor.collectPairTargets(pools).isEmpty());
    }

    @Test
    public void testNullDestinationSkipped() {
        List<TunnelPool> pools = Arrays.asList(pool(true, null, 5, true));
        assertTrue(BuildExecutor.collectPairTargets(pools).isEmpty());
    }

    @Test
    public void testEmptyListEmptyMap() {
        assertTrue(BuildExecutor.collectPairTargets(Collections.<TunnelPool>emptyList()).isEmpty());
    }
}
