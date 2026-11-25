package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import net.i2p.router.TunnelInfo;

import org.junit.Test;

/**
 * Outbound tunnel rotation for a source-destination pair whose cached tunnel
 * was set aside because the stream to that destination stalled.
 * <p>
 * Pins the contract of
 * {@link OutboundClientMessageOneShotJob#selectDistinctOutboundTunnel(TunnelInfo, Supplier, Random)}:
 * the stalled tunnel is skipped whenever the pool offers another, and kept when
 * it does not, because a stalled path that is still the only path beats no send
 * at all. This is the consumer of the stall signal the streaming layer publishes
 * to {@code StallRegistry}; without it the router re-draws at random and lands
 * back on the tunnel that just stopped delivering one send in N.
 *
 * @since 0.9.72+
 */
public class OutboundStallRotationTest {

    /**
     * Builds the tunnels and the drawer per case; the class itself holds no state.
     */
    public OutboundStallRotationTest() {
    }

    /**
     * A pool that offers nothing but the stalled tunnel keeps it. Rotating onto
     * nothing would drop the send, and a stalled tunnel is a slow path, not a
     * veto.
     */
    @Test
    public void testSaturatedPoolKeepsStalledTunnel() {
        TunnelInfo stalled = mock(TunnelInfo.class);
        Drawer drawer = new Drawer(stalled);
        assertSame(stalled, OutboundClientMessageOneShotJob.selectDistinctOutboundTunnel(
                                  stalled, drawer, new Random(1)));
    }

    /**
     * With one alternative in the pool, the stalled tunnel is never returned -
     * not for any of the seeds tried. A single draw would return it one time in
     * two for a two-tunnel pool, which is the failure this guards.
     */
    @Test
    public void testAlternativeAlwaysPreferred() {
        TunnelInfo stalled = mock(TunnelInfo.class);
        TunnelInfo other = mock(TunnelInfo.class);
        for (int seed = 0; seed < 32; seed++) {
            Drawer drawer = new Drawer(stalled, other);
            assertNotSame("seed " + seed, stalled,
                          OutboundClientMessageOneShotJob.selectDistinctOutboundTunnel(
                                  stalled, drawer, new Random(seed)));
        }
    }

    /**
     * A pool with no tunnels at all returns the stalled tunnel, and the drawing
     * stops at the first null rather than spinning on an empty pool.
     */
    @Test
    public void testEmptyPoolKeepsStalledTunnel() {
        TunnelInfo stalled = mock(TunnelInfo.class);
        Drawer drawer = new Drawer();
        assertSame(stalled, OutboundClientMessageOneShotJob.selectDistinctOutboundTunnel(
                                  stalled, drawer, new Random(1)));
        assertEquals("one draw is enough to learn the pool is empty", 1, drawer._calls);
    }

    /**
     * The number of draws is bounded whatever the pool hands back, so a stalled
     * pair on a hot send path cannot pay an unbounded selection cost.
     */
    @Test
    public void testDrawsAreBounded() {
        TunnelInfo stalled = mock(TunnelInfo.class);
        Drawer drawer = new Drawer(true, stalled, mock(TunnelInfo.class), mock(TunnelInfo.class));
        OutboundClientMessageOneShotJob.selectDistinctOutboundTunnel(stalled, drawer, new Random(1));
        assertTrue("drawer calls: " + drawer._calls, drawer._calls <= 8);
    }

    /**
     * Hands out a fixed sequence of pool tunnels, counting every draw so the
     * bounded-draw and stop-at-null rules are measurable. A cycling drawer never
     * runs dry, which is what shows the draw loop is bounded by its own count
     * rather than by the pool giving up.
     */
    private static class Drawer implements Supplier<TunnelInfo> {

        /** The tunnels this fake pool returns, in order. */
        private final List<TunnelInfo> _tunnels;

        /** Whether the sequence repeats forever instead of running dry. */
        private final boolean _forever;

        /** How far into {@link #_tunnels} this drawer has got. */
        private int _index;

        /** Draws taken, including the ones that returned null. */
        private int _calls;

        /**
         * @param tunnels the tunnels the fake pool holds, in draw order
         */
        Drawer(TunnelInfo... tunnels) {
            this(false, tunnels);
        }

        /**
         * @param forever true to repeat {@code tunnels} instead of returning null
         * @param tunnels the tunnels the fake pool holds, in draw order
         */
        Drawer(boolean forever, TunnelInfo... tunnels) {
            _tunnels = Arrays.asList(tunnels);
            _forever = forever;
        }

        /**
         * @return the next pooled tunnel, or null once a non-cycling pool is
         *         drawn dry
         */
        @Override
        public TunnelInfo get() {
            _calls++;
            if (_index >= _tunnels.size()) {
                if (!_forever) {return null;}
                _index = 0;
            }
            return _tunnels.get(_index++);
        }
    }
}
