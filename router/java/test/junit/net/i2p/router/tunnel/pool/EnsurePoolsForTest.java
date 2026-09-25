package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.dummy.DummyTunnelManagerFacade;

/**
 * Tests for {@link net.i2p.router.TunnelManagerFacade#ensurePoolsFor(Hash)}:
 * the int-returning ABI (no void variant ever shipped) and the no-op result
 * for a client with no registered pools.
 *
 * @since 0.9.71+
 */
public class EnsurePoolsForTest {

    private RouterContext _ctx;

    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    private static Hash unknownClient() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[Hash.HASH_LENGTH - 1] = 1;
        return Hash.create(data);
    }

    @Test
    public void testUnknownClientNudgesNothing() {
        TunnelPoolManager mgr = new TunnelPoolManager(_ctx);
        assertEquals(0, mgr.ensurePoolsFor(unknownClient()));
    }

    @Test
    public void testDummyFacadeAlwaysNudgesNothing() {
        assertEquals(0, new DummyTunnelManagerFacade().ensurePoolsFor(unknownClient()));
    }
}
