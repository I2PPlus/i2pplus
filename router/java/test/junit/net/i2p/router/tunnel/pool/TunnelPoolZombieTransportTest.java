package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests the transport label attached to a zombie exclusion.
 *
 * <p>A zombie is a tunnel whose transport session died after the build
 * completed, so selection is not at fault and cannot be fixed by filtering
 * candidates. The split by transport is what identifies the cause, and the
 * RouterInfo style is a free-form string, so the label has to fold the
 * published spellings before it becomes a stat name. Counting an unrecognised
 * spelling as "unknown" matters: an empty or novel style would otherwise
 * produce a stat name with no reader.
 *
 * @since 0.9.71+
 */
public class TunnelPoolZombieTransportTest {

    @Test
    public void testKnownSpellings() {
        assertEquals("NTCP", TunnelPool.zombieTransportBucket("NTCP"));
        assertEquals("NTCP2", TunnelPool.zombieTransportBucket("NTCP2"));
        assertEquals("SSU", TunnelPool.zombieTransportBucket("SSU"));
        assertEquals("SSU2", TunnelPool.zombieTransportBucket("SSU2"));
    }

    @Test
    public void testUnrecognisedSpellingFoldsToUnknown() {
        assertEquals("unknown", TunnelPool.zombieTransportBucket("SSU2 "));
        assertEquals("unknown", TunnelPool.zombieTransportBucket("ssu2"));
        assertEquals("unknown", TunnelPool.zombieTransportBucket(""));
        assertEquals("unknown", TunnelPool.zombieTransportBucket("tor"));
    }

    @Test
    public void testAbsentStyleFoldsToUnknown() {
        assertEquals("unknown", TunnelPool.zombieTransportBucket(null));
    }
}
