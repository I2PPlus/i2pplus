package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.IncomingConnectionFilter;

/**
 * Tests that a server tunnel refuses to start when the access filter it was
 * configured with cannot be read, instead of coming up with every connection
 * allowed.
 *
 * @since 0.9.72+
 */
public class AccessFilterStartupTest {

    /** Context with no properties set, so the escape hatch is off. */
    private static final I2PAppContext CTX = new I2PAppContext();

    /** Context with the escape hatch on. */
    private static final I2PAppContext ALLOW_CTX;
    static {
        Properties props = new Properties();
        props.setProperty(I2PTunnelServer.PROP_FILTER_ALLOW_ON_ERROR, "true");
        ALLOW_CTX = new I2PAppContext(props);
    }

    /** Console sink that keeps nothing. */
    private static final Logging SINK = s -> {};

    @Test
    public void testNoFilterConfiguredAllowsEverything() {
        assertSame(IncomingConnectionFilter.ALLOW,
                   I2PTunnelServer.createAccessFilter(CTX.logManager().getLog(I2PTunnelServer.class),
                                                      CTX, null, SINK));
    }

    @Test
    public void testMissingFilterFileAbortsStartup() throws Exception {
        File missing = new File(tempDir(), "absent.rules");
        try {
            I2PTunnelServer.createAccessFilter(CTX.logManager().getLog(I2PTunnelServer.class),
                                               CTX, missing.getPath(), SINK);
            fail("A configured filter that cannot be read must stop the tunnel starting");
        } catch (IllegalArgumentException expected) {
            assertTrue("The failure must name the file the operator has to fix: " + expected.getMessage(),
                       expected.getMessage().contains("absent.rules"));
        }
    }

    @Test
    public void testMalformedFilterFileAbortsStartup() throws Exception {
        File definition = new File(tempDir(), "bad.rules");
        Files.write(definition.toPath(), "invalid default\n".getBytes(StandardCharsets.UTF_8));
        try {
            I2PTunnelServer.createAccessFilter(CTX.logManager().getLog(I2PTunnelServer.class),
                                               CTX, definition.getPath(), SINK);
            fail("A filter definition that does not parse must stop the tunnel starting");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getCause() != null);
        }
    }

    /**
     * The escape hatch is what an operator uses when they want the tunnel up even
     * though the policy did not load; it has to be asked for by name.
     *
     * @throws Exception if the scratch directory cannot be created
     */
    @Test
    public void testEscapeHatchStartsUnfiltered() throws Exception {
        File missing = new File(tempDir(), "absent.rules");
        assertSame(IncomingConnectionFilter.ALLOW,
                   I2PTunnelServer.createAccessFilter(ALLOW_CTX.logManager().getLog(I2PTunnelServer.class),
                                                      ALLOW_CTX, missing.getPath(), SINK));
    }

    @Test
    public void testValidFilterIsApplied() throws Exception {
        File definition = new File(tempDir(), "good.rules");
        Files.write(definition.toPath(), "deny default\n".getBytes(StandardCharsets.UTF_8));
        IncomingConnectionFilter filter =
            I2PTunnelServer.createAccessFilter(CTX.logManager().getLog(I2PTunnelServer.class),
                                               CTX, definition.getPath(), SINK);
        assertNotNull(filter);
        assertNotSame(IncomingConnectionFilter.ALLOW, filter);
    }

    /**
     * A scratch directory for the definition files this test writes.
     *
     * @return a directory that is removed when the JVM exits
     */
    private static File tempDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "access-filter-startup");
        dir.mkdirs();
        dir.deleteOnExit();
        return dir;
    }
}
