package net.i2p.router.tunnel;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The stats {@link TunnelDispatcher} caches handles to must exist on a default
 * install.
 *
 * <p>{@code dispatch()} calls {@code addData()} on the cached handles with no null
 * check, and the handles come from {@code getOrCreateRateStat()}, which returns
 * null for a name the manager is not collecting. With the default
 * {@code stat.full} off, a plain {@code createRateStat()} registration is skipped,
 * the handle comes back null, and every dispatch through our own inbound gateway
 * throws - in {@code TestJob}, and in the floodfill DbStore ack path.
 *
 * <p>{@link RouterTestHelper#newContext()} is a router context with
 * {@code stat.full} unset, which is the condition that produced the null handle:
 * {@code ignoreStat()} is true here, so only {@code createRequiredRateStat()}
 * puts the name in the table.
 *
 * @since 0.9.72+
 */
public class TunnelDispatcherStatRegistrationTest {

    /** The names cached as hot-path handles and used unguarded. */
    private static final String[] CACHED_HANDLES = {
        "tunnel.dispatchInbound",
        "tunnel.dispatchParticipant",
        "tunnel.dispatchEndpoint",
    };

    /** Router context with {@code stat.full} unset, the default-install state. */
    private RouterContext _ctx;

    /** The dispatcher under test, whose constructor registers the stats. */
    private TunnelDispatcher _dispatcher;

    /** Builds an isolated context and a dispatcher over it. */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _dispatcher = new TunnelDispatcher(_ctx);
    }

    /** Stops the dispatcher's pumper and cleanup job. */
    @After
    public void tearDown() {
        if (_dispatcher != null)
            _dispatcher.shutdown();
    }

    /** The premise of the rest of the class: optional stats are skipped here. */
    @Test
    public void contextSkipsOptionalStats() {
        assertTrue("a plain registration would be skipped in this context",
                   _ctx.statManager().ignoreStat(CACHED_HANDLES[0]));
    }

    /**
     * Every cached handle's name is in the table after construction, so the field
     * the dispatcher dereferences is never null.
     */
    @Test
    public void cachedHandlesResolveWithoutStatFull() {
        for (String name : CACHED_HANDLES)
            assertNotNull(name + " must be registered with stat.full off",
                          _ctx.statManager().getRate(name));
    }
}
