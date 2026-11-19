package org.klomp.snark;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.update.UpdateManager;

import org.junit.Test;

/**
 * Pins which app the torrent update registration is willing to use.
 *
 * <p>The registration asked the app manager for the app named "update" and cast the
 * result straight to an update manager. Anything else under that name threw a
 * ClassCastException on the timer thread, and the registration - the only route by
 * which router updates arrive as a torrent - was simply lost.
 *
 * @since 0.9.72+
 */
public class SnarkUpdateRegistrationTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SnarkUpdateRegistrationTest() {}

    /**
     * An app manager that hands back one app, under one name.
     */
    private static class Apps implements ClientAppManager {

        /** The name the app is registered under. */
        private final String _name;

        /** The app to hand back, null for nothing registered. */
        private final ClientApp _app;

        /**
         * Build the manager.
         *
         * @param name the name the app is registered under
         * @param app the app to hand back, null for nothing registered
         */
        Apps(String name, ClientApp app) {
            _name = name;
            _app = app;
        }

        /**
         * State changes are ignored here.
         *
         * @param app the app reporting
         * @param state the new state
         * @param message an optional message
         * @param e an optional failure
         */
        @Override
        public void notify(ClientApp app, ClientAppState state, String message, Exception e) {}

        /**
         * Registration is not what these cases test.
         *
         * @param app the app registering
         * @return true always
         */
        @Override
        public boolean register(ClientApp app) {
            return true;
        }

        /**
         * Unregistration is not what these cases test.
         *
         * @param app the app unregistering
         */
        @Override
        public void unregister(ClientApp app) {}

        /**
         * The one registered app, if the name matches.
         *
         * @param name the name to look up
         * @return the app, or null for any other name
         */
        @Override
        public ClientApp getRegisteredApp(String name) {
            return _name.equals(name) ? _app : null;
        }
    }

    /**
     * An update manager that is also a client app, the way the real one is.
     *
     * @return a mock usable as both
     */
    private static UpdateManager updateManager() {
        return mock(UpdateManager.class, withSettings().extraInterfaces(ClientApp.class));
    }

    /**
     * No app manager at all - the state before the router has one - is no update
     * manager, and not a failure.
     */
    @Test
    public void testNoAppManagerIsNoUpdateManager() {
        assertNull(SnarkManager.pickUpdateManager(null));
    }

    /**
     * Nothing registered under that name is no update manager.
     */
    @Test
    public void testAnAbsentAppIsNoUpdateManager() {
        assertNull(SnarkManager.pickUpdateManager(new Apps(UpdateManager.APP_NAME, null)));
    }

    /**
     * An app under some other name is not the update manager.
     */
    @Test
    public void testAnAppUnderAnotherNameIsIgnored() {
        ClientAppManager cmgr = new Apps("NavHelper", (ClientApp) updateManager());
        assertNull(SnarkManager.pickUpdateManager(cmgr));
    }

    /**
     * The defect: an app that is not an update manager was cast to one anyway, and
     * the cast threw on the timer thread.
     */
    @Test
    public void testAnAppThatIsNotAnUpdateManagerIsIgnored() {
        ClientAppManager cmgr = new Apps(UpdateManager.APP_NAME, mock(ClientApp.class));
        assertNull(SnarkManager.pickUpdateManager(cmgr));
    }

    /**
     * The app under that name, when it is an update manager, is the one used.
     */
    @Test
    public void testTheUpdateManagerIsReturned() {
        UpdateManager umgr = updateManager();
        ClientAppManager cmgr = new Apps(UpdateManager.APP_NAME, (ClientApp) umgr);
        assertSame(umgr, SnarkManager.pickUpdateManager(cmgr));
    }
}
