package net.i2p.app;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.Test;

/**
 *  Tests for ClientAppManagerImpl, the app registry that lets
 *  client apps find each other by name in an AppContext.
 *  Covers register/unregister semantics, duplicate detection,
 *  and the lookup contract.
 *
 *  @since 0.9.30
 */
public class ClientAppManagerImplTest {

    private static final class FakeApp implements ClientApp {
        private final String _name;
        private ClientAppState _state;

        FakeApp(String name) {
            _name = name;
            _state = ClientAppState.UNINITIALIZED;
        }

        public void startup() {
            _state = ClientAppState.RUNNING;
        }

        public void shutdown(String[] args) {
            _state = ClientAppState.STOPPED;
        }

        public ClientAppState getState() {
            return _state;
        }

        public String getName() {
            return _name;
        }

        public String getDisplayName() {
            return _name;
        }
    }

    private ClientAppManager newManager() {
        return new ClientAppManagerImpl(I2PAppContext.getGlobalContext());
    }

    @Test
    public void testRegisterAndGet() {
        ClientAppManager mgr = newManager();
        FakeApp app = new FakeApp("testapp");
        assertTrue(mgr.register(app));
        assertEquals(app, mgr.getRegisteredApp("testapp"));
    }

    @Test
    public void testDuplicateRegistrationRejected() {
        ClientAppManager mgr = newManager();
        FakeApp app = new FakeApp("dup");
        assertTrue(mgr.register(app));
        assertFalse(mgr.register(app));
        FakeApp other = new FakeApp("dup");
        assertFalse(mgr.register(other));
        // the first app remains registered
        assertEquals(app, mgr.getRegisteredApp("dup"));
    }

    @Test
    public void testUnregister() {
        ClientAppManager mgr = newManager();
        FakeApp app = new FakeApp("gone");
        assertTrue(mgr.register(app));
        mgr.unregister(app);
        assertNull(mgr.getRegisteredApp("gone"));
    }

    @Test
    public void testUnregisterWrongInstanceDoesNothing() {
        ClientAppManager mgr = newManager();
        FakeApp app = new FakeApp("name");
        assertTrue(mgr.register(app));
        // unregistering a different instance with the same name must not remove it
        FakeApp other = new FakeApp("name");
        mgr.unregister(other);
        assertEquals(app, mgr.getRegisteredApp("name"));
    }

    @Test
    public void testGetRegisteredAppUnknown() {
        ClientAppManager mgr = newManager();
        assertNull(mgr.getRegisteredApp("nonexistent"));
    }

    @Test
    public void testMultipleDistinctApps() {
        ClientAppManager mgr = newManager();
        FakeApp a = new FakeApp("a");
        FakeApp b = new FakeApp("b");
        assertTrue(mgr.register(a));
        assertTrue(mgr.register(b));
        assertEquals(a, mgr.getRegisteredApp("a"));
        assertEquals(b, mgr.getRegisteredApp("b"));
        mgr.unregister(a);
        assertNull(mgr.getRegisteredApp("a"));
        assertEquals(b, mgr.getRegisteredApp("b"));
    }

    @Test
    public void testNotifyIsNoop() {
        ClientAppManager mgr = newManager();
        FakeApp app = new FakeApp("x");
        // must not throw and must not mutate state
        mgr.notify(app, ClientAppState.RUNNING, null, null);
        assertEquals(ClientAppState.UNINITIALIZED, app.getState());
    }

    @Test
    public void testReRegisterAfterUnregister() {
        ClientAppManager mgr = newManager();
        FakeApp app = new FakeApp("again");
        assertTrue(mgr.register(app));
        mgr.unregister(app);
        assertTrue(mgr.register(app));
        assertEquals(app, mgr.getRegisteredApp("again"));
    }
}
