package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.Test;

import net.i2p.router.RouterContext;

/**
 * Precedence tests for the consolidated prune early-expiry property:
 * canonical router.pruneEarlyExpiryDelay first, legacy
 * router.tunnel.pruneEarlyExpiryDelay as fallback, else the 120s default.
 * Both names are honored so pre-0.9.71 configs keep working.
 *
 * @since 0.9.71+
 */
public class TunnelPoolPruneExpiryTest {

    private static final String CANONICAL = "router.pruneEarlyExpiryDelay";
    private static final String LEGACY = "router.tunnel.pruneEarlyExpiryDelay";

    /**
     * Context answering the two prune properties; unmentioned properties
     * fall through to the supplied default (RouterContext contract).
     */
    private static RouterContext ctxWith(final long canonical, final long legacy) {
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.getProperty(anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long def = inv.getArgument(1);
            if (CANONICAL.equals(key)) {return canonical >= 0 ? canonical : def;}
            if (LEGACY.equals(key)) {return legacy >= 0 ? legacy : def;}
            return def;
        });
        return ctx;
    }

    @Test
    public void testCanonicalPropertyWins() {
        assertEquals(45_000L, TunnelPool.getPruneEarlyExpiry(ctxWith(45_000L, 30_000L)));
    }

    @Test
    public void testLegacyPropertyUsedWhenCanonicalAbsent() {
        assertEquals(30_000L, TunnelPool.getPruneEarlyExpiry(ctxWith(-1L, 30_000L)));
    }

    @Test
    public void testDefaultWhenNeitherPropertyPresent() {
        assertEquals(TunnelPool.DEFAULT_PRUNE_EARLY_EXPIRY,
                     TunnelPool.getPruneEarlyExpiry(ctxWith(-1L, -1L)));
        assertEquals(120_000L, TunnelPool.getPruneEarlyExpiry(ctxWith(-1L, -1L)));
    }

    @Test
    public void testExplicitZeroIsHonoredNotReplacedByDefault() {
        assertEquals(0L, TunnelPool.getPruneEarlyExpiry(ctxWith(0L, -1L)));
    }
}
