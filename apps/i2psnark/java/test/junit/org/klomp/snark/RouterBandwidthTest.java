package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.util.FileUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for taking snark's bandwidth caps from the router it is connected to. Snark's own caps
 * default to numbers the router will never move - the router's defaults are 1024 KB/s in and 128
 * KB/s out - so snark queues bytes that sit in its own buffers until they time out. The option is
 * off by default, and a router that publishes no limit for a direction leaves snark's own cap in
 * place.
 *
 * @since 0.9.72+
 */
public class RouterBandwidthTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public RouterBandwidthTest() {}

    /** The router's own limit keys, as FIFOBandwidthRefiller names them. */
    private static final String PROP_IN = "i2np.bandwidth.inboundKBytesPerSecond";

    /** The router's outbound limit key. */
    private static final String PROP_OUT = "i2np.bandwidth.outboundKBytesPerSecond";

    /** The manager the config is loaded into. */
    private SnarkManager mgr;

    /** The scratch I2P directory tree the manager is built in. */
    private File baseDir;

    /**
     * Builds a manager that is not started, in a scratch directory tree, so its config can be
     * loaded without a router or a session.
     */
    @Before
    public void setUp() {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-snark-bw-" + System.nanoTime());
        assertTrue("cannot create the scratch directory", baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        props.setProperty(PROP_IN, "512");
        props.setProperty(PROP_OUT, "64");
        I2PAppContext ctx = new I2PAppContext(props);
        mgr = new SnarkManager(ctx, "/test-snark-bw", "test-snark-bw");
    }

    /**
     * Removes the scratch tree the manager was built in.
     */
    @After
    public void tearDown() {
        if (mgr != null) {
            // best-effort teardown of a manager that was never started
            try {
                mgr.stop();
            } catch (Exception e) { /* ignored */ }
        }
        FileUtil.rmdir(baseDir, false);
    }

    /**
     * A router limit is used for that direction.
     */
    @Test
    public void testARouterLimitIsUsed() {
        assertEquals(512, SnarkManager.bwFromRouter(512, 9999));
        assertEquals(128, SnarkManager.bwFromRouter(128, 9999));
    }

    /**
     * A router that is unlimited, or that publishes no limit at all - a standalone snark - leaves
     * snark's own cap standing.
     */
    @Test
    public void testNoRouterLimitKeepsSnarksOwnCap() {
        assertEquals(9999, SnarkManager.bwFromRouter(0, 9999));
        assertEquals(9999, SnarkManager.bwFromRouter(-1, 9999));
    }

    /**
     * Following the router is not a one-way ratchet: a router allowed more than snark's own cap
     * raises it, because the point is to match what the router can move.
     */
    @Test
    public void testARouterLimitIsNotACeilingOnly() {
        assertEquals(20000, SnarkManager.bwFromRouter(20000, 9999));
    }

    /**
     * With the option on, loading the config applies the router's numbers to both directions;
     * with it off, snark's own numbers stand.
     *
     * @throws Exception if the private config or manager fields cannot be reached
     */
    @Test
    public void testTheOptionDrivesTheConfiguredLimits() throws Exception {
        setConfig(SnarkManager.PROP_BW_FROM_ROUTER, "true");
        mgr.loadConfig(null);
        assertEquals(512L * 1024, bwLimit("down"));
        assertEquals(64L * 1024, bwLimit("up"));

        setConfig(SnarkManager.PROP_BW_FROM_ROUTER, "false");
        mgr.loadConfig(null);
        assertEquals((long) SnarkManager.DEFAULT_MAX_DOWN_BW * 1024, bwLimit("down"));
        assertEquals((long) SnarkManager.DEFAULT_MAX_UP_BW * 1024, bwLimit("up"));
    }

    /**
     * Writes one config entry.
     *
     * @param key the config key
     * @param value the value to store
     * @throws Exception if the private config map cannot be reached
     */
    private void setConfig(String key, String value) throws Exception {
        Field f = SnarkManager.class.getDeclaredField("_config");
        f.setAccessible(true);
        Properties config = (Properties) f.get(mgr);
        config.setProperty(key, value);
    }

    /**
     * The bandwidth cap snark settled on for one direction.
     *
     * @param direction "down" or "up"
     * @return the cap, in bytes per second
     * @throws Exception if the private manager field cannot be reached
     */
    private long bwLimit(String direction) throws Exception {
        Field f = SnarkManager.class.getDeclaredField("_bwManager");
        f.setAccessible(true);
        BandwidthManager bwm = (BandwidthManager) f.get(mgr);
        return "down".equals(direction) ? bwm.getDownBWLimit() : bwm.getUpBWLimit();
    }
}
