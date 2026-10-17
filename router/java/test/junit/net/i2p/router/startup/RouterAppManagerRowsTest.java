package net.i2p.router.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;

/**
 * Tests the pure row merge and dot mapping of
 * {@link RouterAppManager#buildRows} and {@link RouterAppManager#dotClass}:
 * tracked apps win over their registered twin, untracked registrations get a
 * "Registered" row, and states map to the right dot colour.
 *
 * @since 0.9.71
 */
public class RouterAppManagerRowsTest {

    private static class FakeApp implements ClientApp {
        private final String _name;
        private final ClientAppState _state;

        FakeApp(String name, ClientAppState state) {
            _name = name;
            _state = state;
        }

        @Override
        public void startup() {}

        @Override
        public void shutdown(String[] args) {}

        @Override
        public ClientAppState getState() {return _state;}

        @Override
        public String getName() {return _name;}

        @Override
        public String getDisplayName() {return _name;}
    }

    @Test
    public void trackedAppAppearsOnceWithStateAndArgs() {
        FakeApp app = new FakeApp("console", ClientAppState.RUNNING);
        Map<ClientApp, String[]> clients = new HashMap<>();
        clients.put(app, new String[] {"7657"});
        Map<String, ClientApp> registered = new HashMap<>();
        registered.put("console", app);
        List<String[]> rows = RouterAppManager.buildRows(clients, registered);
        assertEquals(1, rows.size());
        assertEquals("RUNNING", rows.get(0)[0]);
        assertEquals("console", rows.get(0)[1]);
        assertEquals("[7657]", rows.get(0)[3]);
    }

    @Test
    public void untrackedRegisteredAppGetsRegisteredRow() {
        Map<ClientApp, String[]> clients = new HashMap<>();
        Map<String, ClientApp> registered = new HashMap<>();
        registered.put("news", new FakeApp("news", ClientAppState.UNINITIALIZED));
        List<String[]> rows = RouterAppManager.buildRows(clients, registered);
        assertEquals(1, rows.size());
        assertEquals("Registered", rows.get(0)[0]);
        assertEquals("news", rows.get(0)[1]);
        assertEquals("—", rows.get(0)[3]);
    }

    @Test
    public void nullArgsRenderAsEmDash() {
        FakeApp app = new FakeApp("sam", ClientAppState.RUNNING);
        Map<ClientApp, String[]> clients = new HashMap<>();
        clients.put(app, null);
        List<String[]> rows = RouterAppManager.buildRows(clients, new HashMap<>());
        assertEquals("—", rows.get(0)[3]);
    }

    @Test
    public void rowsSortedByName() {
        Map<ClientApp, String[]> clients = new HashMap<>();
        clients.put(new FakeApp("zzzot", ClientAppState.RUNNING), new String[0]);
        clients.put(new FakeApp("appmanager", ClientAppState.RUNNING), new String[0]);
        Map<String, ClientApp> registered = new HashMap<>();
        registered.put("middle", new FakeApp("middle", ClientAppState.UNINITIALIZED));
        List<String[]> rows = RouterAppManager.buildRows(clients, registered);
        assertEquals(3, rows.size());
        assertEquals("appmanager", rows.get(0)[1]);
        assertEquals("middle", rows.get(1)[1]);
        assertEquals("zzzot", rows.get(2)[1]);
    }

    @Test
    public void dotClassMapsStates() {
        assertEquals("green", RouterAppManager.dotClass("RUNNING"));
        assertEquals("yellow", RouterAppManager.dotClass("STARTING"));
        assertEquals("yellow", RouterAppManager.dotClass("STOPPING"));
        assertEquals("red", RouterAppManager.dotClass("START_FAILED"));
        assertEquals("grey", RouterAppManager.dotClass("STOPPED"));
        assertEquals("grey", RouterAppManager.dotClass("Registered"));
    }

    @Test
    public void everyStateMapsToADot() {
        for (ClientAppState s : ClientAppState.values()) {
            assertTrue(s.name(), RouterAppManager.dotClass(s.name()).length() > 0);
        }
    }
}
