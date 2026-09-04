package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the pure decision logic behind the streaming autoban:
 * the refusal-threshold trigger, ban-extension, and ban-expiry rules.
 * These mirror the hot-path decisions in ConnectionManager so the
 * flood-loop behavior can be pinned without a running router.
 *
 * @since 0.9.71+
 */
public class TempBanDecisionTest {

    /* refusalThresholdMet */

    @Test
    public void testRefusalBelowThresholdNotBanned() {
        assertFalse("refusals equal to threshold must not ban",
                    ConnectionManager.refusalThresholdMet(50, 50));
        assertFalse("refusals below threshold must not ban",
                    ConnectionManager.refusalThresholdMet(10, 50));
        assertFalse("zero refusals must not ban",
                    ConnectionManager.refusalThresholdMet(0, 50));
    }

    @Test
    public void testRefusalAboveThresholdBanned() {
        assertTrue("refusals above threshold must ban",
                   ConnectionManager.refusalThresholdMet(51, 50));
        // one over is enough; 52/50 is also over (">{", not ">=", is the boundary)
        assertTrue(ConnectionManager.refusalThresholdMet(52, 50));
        assertTrue(ConnectionManager.refusalThresholdMet(500, 50));
    }

    @Test
    public void testThresholdDisabledNeverBans() {
        assertFalse("threshold 0 (disabled) must never ban",
                    ConnectionManager.refusalThresholdMet(1000, 0));
        assertFalse("negative threshold must never ban",
                    ConnectionManager.refusalThresholdMet(1000, -1));
    }

    /* banActive (expiry) */

    @Test
    public void testBanActiveBeforeExpiry() {
        long now = 1000;
        assertTrue("ban until future is active",
                   ConnectionManager.banActive(now + 1, now));
        assertTrue("ban until far future is active",
                   ConnectionManager.banActive(now + 86400000L, now));
    }

    @Test
    public void testBanExpired() {
        long now = 1000;
        assertFalse("ban exactly at now is expired",
                    ConnectionManager.banActive(now, now));
        assertFalse("ban in the past is expired",
                    ConnectionManager.banActive(now - 1, now));
        assertFalse("null ban is not active",
                    ConnectionManager.banActive(null, now));
    }

    /* banIsLonger (extension only, never shrink) */

    @Test
    public void testBanNotExtendedByShorter() {
        Long existing = Long.valueOf(2000);
        assertFalse("shorter candidate must not shrink the ban",
                    ConnectionManager.banIsLonger(existing, Long.valueOf(1500)));
        assertFalse("equal candidate must not change the ban",
                    ConnectionManager.banIsLonger(existing, Long.valueOf(2000)));
    }

    @Test
    public void testBanExtendedByLonger() {
        assertTrue("longer candidate replaces the ban",
                   ConnectionManager.banIsLonger(Long.valueOf(2000), Long.valueOf(3000)));
    }

    @Test
    public void testBanIsLongerNullGuards() {
        assertFalse(ConnectionManager.banIsLonger(null, Long.valueOf(3000)));
        assertFalse(ConnectionManager.banIsLonger(Long.valueOf(2000), null));
        assertFalse(ConnectionManager.banIsLonger(null, null));
    }

    /* synBurstTripped (sub-second SYN burst gate) */

    @Test
    public void testBurstTrippedWithinWindow() {
        long now = 1000000;
        // count 11 SYNs in a 500ms window with burst threshold 10 -> tripped
        assertTrue(ConnectionManager.synBurstTripped(now - 100, 11, now, 500, 10));
    }

    @Test
    public void testBurstNotTrippedAtOrBelowThreshold() {
        long now = 1000000;
        // exactly at threshold: not an ''exceeds'' trip
        assertFalse(ConnectionManager.synBurstTripped(now - 100, 10, now, 500, 10));
        assertFalse(ConnectionManager.synBurstTripped(now - 100, 1, now, 500, 10));
        // null/absent window start -> never trips
        assertFalse(ConnectionManager.synBurstTripped(null, 99, now, 500, 10));
    }

    @Test
    public void testBurstAgedOutOfWindow() {
        long now = 1000000;
        // window start older than windowMs -> burst ignored (count doesn't apply)
        assertFalse(ConnectionManager.synBurstTripped(now - 501, 99, now, 500, 10));
        // exactly at boundary is outside (>= windowMs)
        assertFalse(ConnectionManager.synBurstTripped(now - 500, 99, now, 500, 10));
    }

    @Test
    public void testBurstGateDisabled() {
        long now = 1000000;
        // windowMs or burst <= 0 disables the gate entirely
        assertFalse(ConnectionManager.synBurstTripped(Long.valueOf(now - 100), 99, now, 0, 10));
        assertFalse(ConnectionManager.synBurstTripped(Long.valueOf(now - 100), 99, now, 500, 0));
    }

    /* tooManyStreamsForDest (per-dest stream budget) */

    @Test
    public void testPerDestOverBudgetAtCeiling() {
        // a dest reaches its boundary exactly at max -> no more streams
        assertTrue(ConnectionManager.tooManyStreamsForDest(512, 512));
        assertTrue(ConnectionManager.tooManyStreamsForDest(513, 512));
        assertTrue(ConnectionManager.tooManyStreamsForDest(1000, 512));
        assertTrue(ConnectionManager.tooManyStreamsForDest(1, 1));
    }

    @Test
    public void testPerDestUnderBudget() {
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 512));
        assertFalse(ConnectionManager.tooManyStreamsForDest(511, 512));
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 0));
    }

    @Test
    public void testPerDestBudgetDisabled() {
        // a non-positive ceiling must never refuse, regardless of stream count
        assertFalse(ConnectionManager.tooManyStreamsForDest(100000, 0));
        assertFalse(ConnectionManager.tooManyStreamsForDest(100000, -1));
    }

    @Test
    public void testPerDestIsolation() {
        // two dests are independent: one at the ceiling does not affect a fresh one
        assertTrue(ConnectionManager.tooManyStreamsForDest(50, 50));
        assertFalse(ConnectionManager.tooManyStreamsForDest(0, 50));
    }
}