package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.crypto.KeyPair;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.crypto.X25519KeyFactory;

/**
 * Where the XDHE key pair for a next forward key comes from (A-1). The lookup
 * used to cast the global context straight to RouterContext on the ratchet
 * handshake path, which is a ClassCastException anywhere the global context is
 * not a running router - a test, a tool, or a tagset built before the router is
 * up - and a null dereference on a comm system with no XDHE factory.
 */
public class RatchetTagSetNextKeysTest {

    /**
     * A plain context, with no router behind it, still produces a usable pair.
     */
    @Test
    public void testNextKeysWithoutARouter() {
        KeyPair pair = RatchetTagSet.nextKeys(I2PAppContext.getGlobalContext());
        assertNotNull("no key pair came back outside a router", pair);
        assertEquals("not an X25519 public key", 32, pair.getPublic().getData().length);
    }

    /**
     * With a running router, its precalculated pair is the one that gets used -
     * the whole point of the factory is to keep key generation off the
     * handshake path.
     */
    @Test
    public void testNextKeysUsesTheRouterFactory() {
        RouterContext ctx = mock(RouterContext.class);
        CommSystemFacade comm = mock(CommSystemFacade.class);
        X25519KeyFactory factory = mock(X25519KeyFactory.class);
        KeyPair precalculated = mock(KeyPair.class);
        when(ctx.commSystem()).thenReturn(comm);
        when(comm.getXDHFactory()).thenReturn(factory);
        when(factory.getKeys()).thenReturn(precalculated);
        assertSame("the router's precalculated pair was skipped",
                   precalculated, RatchetTagSet.nextKeys(ctx));
    }

    /**
     * A router whose comm system has no XDHE factory still gets a pair,
     * generated on the spot rather than a null dereference.
     */
    @Test
    public void testNextKeysFallsBackWhenTheRouterHasNoFactory() {
        RouterContext ctx = mock(RouterContext.class);
        CommSystemFacade comm = mock(CommSystemFacade.class);
        when(ctx.commSystem()).thenReturn(comm);
        when(comm.getXDHFactory()).thenReturn(null);
        when(ctx.keyGenerator()).thenReturn(I2PAppContext.getGlobalContext().keyGenerator());
        KeyPair pair = RatchetTagSet.nextKeys(ctx);
        assertNotNull("no key pair came back with no factory", pair);
        assertEquals("not an X25519 public key", 32, pair.getPublic().getData().length);
    }
}
