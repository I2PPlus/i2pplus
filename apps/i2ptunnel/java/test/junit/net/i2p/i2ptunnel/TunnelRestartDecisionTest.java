package net.i2p.i2ptunnel;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Properties;

import org.junit.Test;

/**
 * Pins the two decisions a tunnel restart pass makes.
 *
 * <p>Every restart path the console offers ran on the servlet request thread: it
 * slept a second between stopping and starting each tunnel, so "Restart all" held
 * the browser on a spinner for a second per tunnel, and a single tunnel's restart
 * waited up to ten seconds for the stop and then built the tunnel inline. Moving
 * the pass off the request thread leaves a start queued behind a delay, and during
 * that delay another request can stop, edit or delete the tunnel. Nothing checked
 * for that, so the stale start won and the tunnel came back up after the operator
 * took it down.
 *
 * @since 0.9.72+
 */
public class TunnelRestartDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public TunnelRestartDecisionTest() {}

    /**
     * A tunnel the console can be asked about.
     *
     * @param client true for a client tunnel, false for a server tunnel
     * @param running true if the tunnel is up right now
     * @return the tunnel to test against
     */
    private static TunnelController controller(boolean client, boolean running) {
        TunnelController tc = mock(TunnelController.class);
        when(tc.isClient()).thenReturn(client);
        when(tc.getIsRunning()).thenReturn(running);
        return tc;
    }

    /**
     * A tunnel the operator stopped stays stopped - "Restart all" is not "start
     * all", and a pass that started stopped tunnels would undo a deliberate stop.
     */
    @Test
    public void testAStoppedTunnelIsNotRestarted() {
        int[] scopes = {TunnelControllerGroup.RESTART_ALL, TunnelControllerGroup.RESTART_CLIENTS,
                        TunnelControllerGroup.RESTART_SERVERS};
        for (int scope : scopes) {
            assertFalse("a stopped tunnel was picked up by scope " + scope,
                        TunnelControllerGroup.isRestartable(controller(true, false), scope));
            assertFalse("a stopped server tunnel was picked up by scope " + scope,
                        TunnelControllerGroup.isRestartable(controller(false, false), scope));
        }
        assertFalse("no tunnel at all",
                    TunnelControllerGroup.isRestartable(null, TunnelControllerGroup.RESTART_ALL));
    }

    /**
     * The client and server scopes must not overlap: "Restart all clients" leaves
     * the servers alone and "Restart all servers" leaves the clients alone, so an
     * operator can restart one half of a busy router without dropping the other.
     */
    @Test
    public void testTheScopesKeepClientsAndServersApart() {
        int clients = TunnelControllerGroup.RESTART_CLIENTS;
        int servers = TunnelControllerGroup.RESTART_SERVERS;
        assertTrue(TunnelControllerGroup.isRestartable(controller(true, true), clients));
        assertFalse("a client in the server scope",
                    TunnelControllerGroup.isRestartable(controller(true, true), servers));
        assertTrue(TunnelControllerGroup.isRestartable(controller(false, true), servers));
        assertFalse("a server in the client scope",
                    TunnelControllerGroup.isRestartable(controller(false, true), clients));
    }

    /**
     * The all scope takes both halves, and nothing else.
     */
    @Test
    public void testTheAllScopeTakesBothHalves() {
        int all = TunnelControllerGroup.RESTART_ALL;
        assertTrue(TunnelControllerGroup.isRestartable(controller(true, true), all));
        assertTrue(TunnelControllerGroup.isRestartable(controller(false, true), all));
        assertFalse(TunnelControllerGroup.isRestartable(controller(true, false), all));
    }

    /**
     * A start queued against a generation is valid only while that generation is
     * current; a second stop, a destroy, or a second restart pass moves it on.
     */
    @Test
    public void testAStartIsOnlyValidForTheGenerationItWasQueuedAt() {
        assertTrue(TunnelController.isStartStillValid(0, 0));
        assertTrue(TunnelController.isStartStillValid(7, 7));
        assertFalse("a start survived a stop", TunnelController.isStartStillValid(7, 8));
        assertFalse("a start survived two stops", TunnelController.isStartStillValid(7, 9));
    }

    /**
     * A stop that took effect bumps the generation, which is what cancels the
     * start the restart pass queued. A stop of an already stopped tunnel changes
     * nothing and supersedes nothing, so it must not bump.
     */
    @Test
    public void testAStopThatTakesEffectMovesTheGenerationOn() {
        TunnelController tc = newController();
        long initial = tc.getLifecycleGeneration();
        tc.stopTunnel();
        assertEquals("a no-op stop bumped the generation", initial, tc.getLifecycleGeneration());

        tc.changeState(TunnelController.TunnelState.RUNNING);
        long whileRunning = tc.getLifecycleGeneration();
        tc.stopTunnel();
        assertEquals("a real stop did not bump the generation once",
                     whileRunning + 1, tc.getLifecycleGeneration());
        assertEquals(TunnelController.TunnelState.STOPPED, tc.getState());

        tc.changeState(TunnelController.TunnelState.RUNNING);
        tc.destroyTunnel();
        assertEquals("a destroy did not bump the generation",
                     whileRunning + 2, tc.getLifecycleGeneration());
    }

    /**
     * The guard is wired into the start, not only available beside it: a start
     * handed a generation the tunnel has moved past leaves the tunnel exactly as
     * it was, with nothing started in the background.
     */
    @Test
    public void testAStaleStartLeavesTheTunnelAlone() {
        TunnelController tc = newController();
        tc.changeState(TunnelController.TunnelState.STOPPED);
        long current = tc.getLifecycleGeneration();
        tc.startTunnelBackground(current + 3);
        assertEquals("a superseded start changed the state",
                     TunnelController.TunnelState.STOPPED, tc.getState());
        assertFalse("a superseded start marked the tunnel starting", tc.getIsStarting());
    }

    /**
     * A controller for a client tunnel, with no keys to create and no session to
     * open, so the lifecycle calls stay local.
     *
     * @return the controller to exercise
     */
    private static TunnelController newController() {
        Properties cfg = new Properties();
        cfg.setProperty(TunnelController.PROP_TYPE, TunnelController.TYPE_HTTP_CLIENT);
        cfg.setProperty(TunnelController.PROP_INTFC, "127.0.0.1");
        return new TunnelController(cfg, "", false);
    }
}
