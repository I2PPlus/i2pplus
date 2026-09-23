package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.router.networkdb.kademlia.FloodfillVerifyStoreJob.FloodCheckResult;
import net.i2p.router.networkdb.kademlia.FloodfillVerifyStoreJob.StoreCheckResult;

import org.junit.Test;

/**
 * Pins the two-phase floodfill verify decisions: phase 1 isolates a store
 * that never stuck (or went stale) at the peer we wrote to, phase 2 allows
 * one same-peer retry so an under-aged flood sample is not treated as
 * non-flooding, and phase-2 start is measured from job creation so the
 * flood delay is not stretched by phase-1 latency.
 */
public class FloodfillVerifyDecisionTest {

    /**
     *  A store-peer search reply means the entry is gone — the store failed.
     */
    @Test
    public void testStoreCheckMissingIsFailure() {
        assertEquals(StoreCheckResult.MISSING, FloodfillVerifyStoreJob.classifyStoreCheck(false, false));
        assertTrue(FloodfillVerifyStoreJob.storeCheckFailed(StoreCheckResult.MISSING));
    }

    /**
     *  A store-peer entry older than what we published is a store failure.
     */
    @Test
    public void testStoreCheckStaleIsFailure() {
        assertEquals(StoreCheckResult.STALE, FloodfillVerifyStoreJob.classifyStoreCheck(true, false));
        assertTrue(FloodfillVerifyStoreJob.storeCheckFailed(StoreCheckResult.STALE));
    }

    /**
     *  A fresh entry at the store peer advances to the flood check.
     */
    @Test
    public void testStoreCheckFreshIsNotFailure() {
        assertEquals(StoreCheckResult.FRESH, FloodfillVerifyStoreJob.classifyStoreCheck(true, true));
        assertFalse(FloodfillVerifyStoreJob.storeCheckFailed(StoreCheckResult.FRESH));
    }

    /**
     *  Timeout/setup failure at phase 1 must not blame the store peer;
     *  the flood check still runs.
     */
    @Test
    public void testStoreCheckIndeterminateDoesNotFail() {
        assertFalse(FloodfillVerifyStoreJob.storeCheckFailed(StoreCheckResult.INDETERMINATE));
    }

    /**
     *  A different floodfill with a fresh entry is full success.
     */
    @Test
    public void testFloodCheckFresh() {
        assertEquals(FloodCheckResult.FRESH, FloodfillVerifyStoreJob.classifyFloodCheck(true, true));
    }

    /**
     *  First missing reply at phase 2 retries the same peer once.
     */
    @Test
    public void testFloodCheckMissingRetriesOnce() {
        assertEquals(FloodCheckResult.MISSING, FloodfillVerifyStoreJob.classifyFloodCheck(false, false));
        assertTrue(FloodfillVerifyStoreJob.shouldRetryFloodCheck(FloodCheckResult.MISSING, false));
        assertFalse(FloodfillVerifyStoreJob.shouldRetryFloodCheck(FloodCheckResult.MISSING, true));
    }

    /**
     *  First stale reply at phase 2 also retries — old copy may be mid-flood.
     */
    @Test
    public void testFloodCheckStaleRetriesOnce() {
        assertEquals(FloodCheckResult.STALE, FloodfillVerifyStoreJob.classifyFloodCheck(true, false));
        assertTrue(FloodfillVerifyStoreJob.shouldRetryFloodCheck(FloodCheckResult.STALE, false));
        assertFalse(FloodfillVerifyStoreJob.shouldRetryFloodCheck(FloodCheckResult.STALE, true));
    }

    /**
     *  Fresh never retries; after a spent retry nothing retries.
     */
    @Test
    public void testFloodCheckFreshNeverRetries() {
        assertFalse(FloodfillVerifyStoreJob.shouldRetryFloodCheck(FloodCheckResult.FRESH, false));
        assertFalse(FloodfillVerifyStoreJob.shouldRetryFloodCheck(FloodCheckResult.FRESH, true));
    }

    /**
     *  Phase 2 starts at created + delay, independent of phase-1 latency.
     */
    @Test
    public void testPhase2StartsFromCreation() {
        assertEquals(25_000L, FloodfillVerifyStoreJob.phase2StartTime(5_000L, 20_000L));
        assertEquals(20_000L, FloodfillVerifyStoreJob.phase2StartTime(0L, 20_000L));
        assertEquals(0L, FloodfillVerifyStoreJob.phase2StartTime(0L, 0L));
    }
}
