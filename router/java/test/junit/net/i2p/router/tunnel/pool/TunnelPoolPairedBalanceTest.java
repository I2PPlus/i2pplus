package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Contract tests for the paired inbound/outbound pool decisions
 * {@link TunnelPool#shouldHoldInboundPruning(int, int, int, int)} and
 * {@link TunnelPool#shouldNudgePartnerBuild(int, int)}.
 *
 * <p>A client destination has two halves: inbound tunnels carry traffic to it
 * and publish its LeaseSet, outbound tunnels carry its replies back out.  Both
 * paired-pool rules used to be written from the inbound side only.  On a live
 * router that produced one consistent shape: 11 of 11 hosted destinations sat
 * at or above their inbound count while their outbound half sat below it with
 * nothing building (update.skank 4/4 inbound against 1/4 outbound).  The reply
 * leg of a stream rides the outbound half, so the destination stayed reachable
 * and answered nothing — 1,346 resends in the reply direction against 16 in the
 * request direction, ending at the 60-send retransmit cap.
 *
 * <p>The pruning hold is what manufactured the asymmetry: it suppressed inbound
 * cleanup for as long as the outbound half was short, with no ceiling, so
 * inbound piled up to 10/8 while outbound decayed to 5/8.  The nudge is what
 * left it uncorrected: only a server pool could ask for a replacement, so the
 * starved outbound half never asked.
 *
 * @since 0.9.72+
 */
public class TunnelPoolPairedBalanceTest {

    /**
     * JUnit creates one instance per test method; both predicates under test are
     * static, so the instance carries no state between them.
     */
    public TunnelPoolPairedBalanceTest() {
    }

    /**
     * The hold exists to stop a synchronized collapse, so it must be in force
     * while a short partner still needs the inbound tunnels it pairs with.
     */
    @Test
    public void holdsWhilePartnerIsShortAndInboundIsAtConfigured() {
        assertTrue(TunnelPool.shouldHoldInboundPruning(4, 4, 1, 4));
    }

    /**
     * An inbound pool below its own count has no excess to release, so the hold
     * applies there too.
     */
    @Test
    public void holdsWhenInboundIsBelowConfigured() {
        assertTrue(TunnelPool.shouldHoldInboundPruning(2, 4, 1, 4));
    }

    /**
     * The regression: an unbounded hold kept inbound at 10/8 for as long as
     * outbound sat at 5/8, so the excess tunnels were never released and the
     * starved direction never got the slots they occupied.  Past the configured
     * count, an inbound tunnel is not reachability.
     */
    @Test
    public void releasesOnceInboundIsOverConfigured() {
        assertFalse(TunnelPool.shouldHoldInboundPruning(10, 8, 5, 8));
    }

    /**
     * A partner holding its own count is not strained, so nothing is being
     * protected and cleanup runs normally.
     */
    @Test
    public void noHoldWhenPartnerMeetsItsCount() {
        assertFalse(TunnelPool.shouldHoldInboundPruning(4, 4, 4, 4));
        assertFalse(TunnelPool.shouldHoldInboundPruning(4, 4, 6, 4));
    }

    /**
     * An empty pool is inert either way - {@code pruneExcessTunnels()} returns
     * before this gate for a pool of one tunnel or fewer - so this pins that the
     * predicate answers rather than throwing on the degenerate counts.
     */
    @Test
    public void emptyPoolHoldsHarmlessly() {
        assertTrue(TunnelPool.shouldHoldInboundPruning(0, 0, 0, 4));
    }

    /**
     * One usable partner tunnel is the state the nudge exists for: that pool's
     * own cleanup may not notice in time, so the healthy side raises the alarm.
     */
    @Test
    public void nudgesWhenPartnerHoldsOneUsableTunnel() {
        assertTrue(TunnelPool.shouldNudgePartnerBuild(4, 1));
    }

    /**
     * An empty partner is the same alarm, louder.
     */
    @Test
    public void nudgesWhenPartnerIsEmpty() {
        assertTrue(TunnelPool.shouldNudgePartnerBuild(2, 0));
    }

    /**
     * Two usable tunnels is a degraded partner, not a dying one, and nudging on
     * every degraded pass would storm the executor.
     */
    @Test
    public void noNudgeWhilePartnerHasSpareCapacity() {
        assertFalse(TunnelPool.shouldNudgePartnerBuild(4, 2));
    }

    /**
     * A pool holding one tunnel is itself the collapse the nudge exists to
     * prevent, and an empty pool has no partner relationship to protect, so
     * neither may ask.
     */
    @Test
    public void noNudgeFromAPoolThatHasNothingToSpare() {
        assertFalse(TunnelPool.shouldNudgePartnerBuild(1, 0));
        assertFalse(TunnelPool.shouldNudgePartnerBuild(0, 0));
    }
}
