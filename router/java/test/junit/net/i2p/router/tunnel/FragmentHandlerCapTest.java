package net.i2p.router.tunnel;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for the bound on partially reassembled messages in
 * FragmentHandler, which keeps a peer from growing the map without limit by
 * sending fragments for many distinct message IDs.
 *
 * @since 0.9.71+
 */
public class FragmentHandlerCapTest {

    private static RouterContext _context;

    @BeforeClass
    public static void globalSetUp() {
        _context = new RouterContext(null);
    }

    @Test
    public void testMayStartNewFragmentedMessage() {
        assertTrue(FragmentHandler.mayStartNewFragmentedMessage(0));
        assertTrue(FragmentHandler.mayStartNewFragmentedMessage(1));
        assertTrue(FragmentHandler.mayStartNewFragmentedMessage(
                FragmentHandler.MAX_FRAGMENTED_MESSAGES - 1));
        assertFalse(FragmentHandler.mayStartNewFragmentedMessage(
                FragmentHandler.MAX_FRAGMENTED_MESSAGES));
        assertFalse(FragmentHandler.mayStartNewFragmentedMessage(
                FragmentHandler.MAX_FRAGMENTED_MESSAGES + 1));
    }

    @Test
    public void testGetOrCreateRefusesNewMessageWhenFull() {
        FragmentHandler handler = new FragmentHandler(_context, null);
        int max = FragmentHandler.MAX_FRAGMENTED_MESSAGES;
        for (int i = 0; i < max; i++) {
            assertNotNull("message " + i, handler.getOrCreate(i, i));
        }
        assertNull(handler.getOrCreate(max, max));
        assertNull(handler.getOrCreate(max + 1, max + 1));
        // messages already reassembling may still finish
        assertNotNull(handler.getOrCreate(0, 0));
        assertNotNull(handler.getOrCreate(max - 1, max - 1));
    }

    @Test
    public void testGetOrCreateStartsNewMessageBelowCap() {
        FragmentHandler handler = new FragmentHandler(_context, null);
        FragmentedMessage first = handler.getOrCreate(7, 7);
        assertNotNull(first);
        // a second fragment of the same message finds the existing one
        assertSame(first, handler.getOrCreate(7, 7));
    }

    /**
     * StatManager.addRateData() silently discards data for a stat that was
     * never registered, and createRateStat() is itself a no-op unless
     * stat.full is set. Every stat FragmentHandler emits must therefore be
     * declared as required, or the cap's drop counter is invisible in the
     * default configuration and the attack it defends against has no signal.
     *
     * <p>Registration is asserted by invoking the declaration directly: a bare
     * RouterContext defers tunnel wiring to initAll(), so the dispatcher's
     * constructor never runs in a unit test.
     */
    @Test
    public void testEmittedStatsAreRegistered() {
        String[] emitted = {
            "tunnel.fragmentMapFull", "tunnel.fragmentedDropped",
            "tunnel.fragmentedComplete", "tunnel.corruptMessage",
            "tunnel.smallFragments", "tunnel.fullFragments"
        };
        TunnelDispatcher.registerFragmentStats(_context.statManager());
        for (String stat : emitted) {
            assertNotNull(stat, _context.statManager().getRate(stat));
        }
    }

    /**
     * createRequiredRateStat() returns early when the name is already
     * present, so declaring twice must not replace the live stat — otherwise
     * a second declaration path would silently reset accumulated counters.
     */
    @Test
    public void testRegistrationIsIdempotent() {
        TunnelDispatcher.registerFragmentStats(_context.statManager());
        RateStat first = _context.statManager().getRate("tunnel.fragmentMapFull");
        assertNotNull(first);
        TunnelDispatcher.registerFragmentStats(_context.statManager());
        assertSame("re-registration must not replace the stat",
                first, _context.statManager().getRate("tunnel.fragmentMapFull"));
    }
}
