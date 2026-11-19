package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins that starting and stopping the manager can be asked for twice.
 *
 * <p>Neither start() nor stop() checked whether the manager was already running, and
 * neither was synchronized, so a second start() registered the app again and started
 * a second directory monitor over the same torrents, and a second stop() re-ran the
 * whole teardown - halting the acceptor, shutting the executor down and flushing the
 * config again. The app manager can now start and stop us as well, through startup()
 * and shutdown(), which used to be documented no-ops, so both halves of that
 * duplication are reachable from outside the webapp.
 *
 * @since 0.9.72+
 */
public class SnarkManagerLifecycleTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SnarkManagerLifecycleTest() {}

    /** The manager under test. */
    private SnarkManager mgr;

    /** The context it runs in, standalone so nothing reaches the network. */
    private I2PAppContext ctx;

    /** The scratch directory the config and metadata are written to. */
    private File baseDir;

    /**
     * A manager in a scratch directory, named i2psnark so it takes the registered
     * app path rather than the navigation-link path.
     */
    @Before
    public void setUp() {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-snark-lifecycle-" + System.nanoTime());
        assertTrue(baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        ctx = new I2PAppContext(props);
        mgr = new SnarkManager(ctx, "/i2psnark", "i2psnark");
    }

    /**
     * Stop the manager and remove the scratch directory.
     */
    @After
    public void tearDown() {
        if (mgr != null) {
            try { mgr.stop(); } catch (Exception ignore) {}
        }
        if (baseDir != null) {
            try { net.i2p.util.FileUtil.rmdir(baseDir, false); } catch (Exception ignore) {}
        }
    }

    /**
     * How many directory monitor threads are alive, counted rather than asserted
     * absolutely so a leftover from another case cannot fail this one.
     *
     * @return the number of live SnarkDirMon threads
     */
    private static int dirMonitors() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().contains("SnarkDirMon") && t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Starting registers the app and reports it running; stopping unregisters it and
     * reports it stopped. The state used to be a constant INITIALIZED, so the console
     * could not tell a running I2PSnark from a stopped one.
     */
    @Test
    public void testStartAndStopTrackTheState() {
        ClientAppManager cmgr = ctx.clientAppManager();
        assertEquals(ClientAppState.INITIALIZED, mgr.getState());
        mgr.start();
        assertEquals(ClientAppState.RUNNING, mgr.getState());
        assertSame(mgr, cmgr.getRegisteredApp("i2psnark"));
        mgr.stop();
        assertEquals(ClientAppState.STOPPED, mgr.getState());
        assertNull(cmgr.getRegisteredApp("i2psnark"));
    }

    /**
     * The defect: a second start() ran the whole start-up again, directory monitor
     * included, over the torrents the first one is already watching.
     */
    @Test
    public void testASecondStartDoesNotStartAnotherMonitor() {
        mgr.start();
        int afterFirst = dirMonitors();
        assertTrue("the first start() started no directory monitor", afterFirst > 0);
        mgr.start();
        assertEquals("a second start() started another monitor", afterFirst, dirMonitors());
        assertEquals(ClientAppState.RUNNING, mgr.getState());
    }

    /**
     * A second stop() does not re-run the teardown. It used to: halt the acceptor,
     * shut the executor down, stop every torrent and flush the config all over again.
     */
    @Test
    public void testASecondStopIsHarmless() {
        mgr.start();
        mgr.stop();
        mgr.stop();
        assertEquals(ClientAppState.STOPPED, mgr.getState());
    }

    /**
     * The app manager starts and stops client apps through startup() and
     * shutdown(), which used to do nothing at all, so an app manager could never
     * bring I2PSnark up or down.
     */
    @Test
    public void testTheAppManagerMethodsDriveTheSameLifecycle() {
        ClientAppManager cmgr = ctx.clientAppManager();
        mgr.startup();
        assertEquals(ClientAppState.RUNNING, mgr.getState());
        assertSame(mgr, cmgr.getRegisteredApp("i2psnark"));
        mgr.shutdown(null);
        assertEquals(ClientAppState.STOPPED, mgr.getState());
        assertNull(cmgr.getRegisteredApp("i2psnark"));
    }

    /**
     * Stopping a manager that was never started has nothing to tear down, and must
     * not claim a state it never passed through.
     */
    @Test
    public void testStoppingANeverStartedManagerIsHarmless() {
        mgr.stop();
        assertEquals(ClientAppState.INITIALIZED, mgr.getState());
        mgr.stop();
        assertEquals(ClientAppState.INITIALIZED, mgr.getState());
    }
}
