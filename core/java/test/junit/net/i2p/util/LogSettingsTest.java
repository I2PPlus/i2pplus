package net.i2p.util;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * Tests how {@code logger.record.*} entries are resolved to a class's log level.
 *
 * <p>Two rules are non-obvious and were previously untested:
 * <ul>
 * <li>a root name matches on package boundaries, so {@code net.i2p.util.LogSettingsTes}
 *     must not capture {@code net.i2p.util.LogSettingsTest};</li>
 * <li>when several entries match, the longest (most specific) one wins.</li>
 * </ul>
 * Either rule breaking would silently misconfigure logging in production - losing
 * WARN and ERROR, or flooding DEBUG - which is why they are pinned here.
 *
 * <p>These assertions go through {@link Log#shouldLog(int)}, so they measure the
 * resolved level rather than re-implementing the comparison.
 *
 * <p>This class used to capture System.out through a pipe and wait on the
 * background {@link LogWriter}. That tested the writer's flush cadence rather than
 * the rule above, and raced a 15s batch timer, so it was intermittently slow and
 * failed on timing alone.
 *
 * @since 0.9.71+
 */
public class LogSettingsTest extends TestCase {

    /** Scratch directory for the per-test logger config. */
    private static final File TMPDIR = new File(System.getProperty("java.io.tmpdir"));

    /** This class's own log name, the thing the rules are applied to. */
    private static final String US = "net.i2p.util.LogSettingsTest";

    private Properties p;
    private Log log;
    private I2PAppContext _context;
    private File f;

    /**
     * Initializes the test fixture.
     *
     * Called before every test case method.
     */
    protected void setUp() throws IOException {
        _context = I2PAppContext.getGlobalContext();
        // A minimal, explicit config rather than a copy of the live one: these
        // tests assert on which entries win, so an inherited entry from the host
        // would make them depend on the machine they run on.
        f = File.createTempFile("i2p-logsettings", ".config", TMPDIR);
        p = new Properties();
        p.setProperty("logger.defaultLevel", Log.toLevelString(Log.ERROR));
        _context.logManager().setConfig(f.getAbsolutePath());
        reread();
    }

    protected void tearDown() throws IOException {
        // Restore the LogManager before removing the file: it is a global
        // singleton shared with every later test in this JVM, and leaving it
        // pointed at a deleted scratch file would misconfigure logging for them.
        _context.logManager().setConfig(LogManager.CONFIG_LOCATION_DEFAULT);
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }

    /**
     * Write the config and make the LogManager actually re-read it.
     *
     * <p>{@code loadConfig()} returns early unless the file's mtime is strictly
     * newer than its last read, and it compares a millisecond clock against a
     * filesystem timestamp of coarser granularity. Two writes inside one tick
     * therefore look unchanged and the second is silently dropped, so a level
     * change now and then never reached the Log. Nudging the mtime past the clock
     * removes the race.
     */
    private void reread() throws IOException {
        DataHelper.storeProps(p, f);
        f.setLastModified(Math.max(f.lastModified(), _context.clock().now() + 1000L));
        _context.logManager().rereadConfig();
        log = _context.logManager().getLog(LogSettingsTest.class);
    }

    /** An exact entry for this class applies to it. */
    public void testExactEntryApplies() throws IOException {
        p.setProperty("logger.record." + US, Log.toLevelString(Log.ERROR));
        reread();
        assertFalse("INFO is below the configured ERROR", log.shouldLog(Log.INFO));
        assertTrue("ERROR is the configured level", log.shouldLog(Log.ERROR));
        assertTrue("CRIT is above ERROR", log.shouldLog(Log.CRIT));
    }

    /**
     * The most specific matching entry wins over a broader one. A router config
     * routinely sets a package-wide level and then overrides one noisy class.
     */
    public void testLongerPrefixWins() throws IOException {
        p.setProperty("logger.record.net.i2p", Log.toLevelString(Log.WARN));
        p.setProperty("logger.record." + US, Log.toLevelString(Log.DEBUG));
        reread();
        assertTrue("the class-specific DEBUG must beat the package-wide WARN",
                   log.shouldLog(Log.DEBUG));
        assertTrue(log.shouldInfo());
    }

    /**
     * A root name that is only a character-wise prefix must not match. Without the
     * boundary check, {@code net.i2p.util.LogSettingsTes} would capture this
     * class - and any class whose name happens to continue with those letters.
     *
     * <p>The near-miss entry is set to CRIT while the default stays ERROR, so
     * ERROR is the discriminating assertion: captured, the class would be narrowed
     * to CRIT and ERROR would be suppressed; not captured, it keeps the default
     * and ERROR still passes. Asserting DEBUG instead would be vacuous, since
     * DEBUG fails under the default too.
     */
    public void testPartialPrefixDoesNotMatch() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTes", Log.toLevelString(Log.CRIT));
        reread();
        assertTrue("a non-boundary prefix must not capture this class",
                   log.shouldLog(Log.ERROR));
        assertFalse("must fall back to the default level, not CRIT",
                    log.shouldLog(Log.WARN));
    }

    /** A longer sibling name that merely shares this prefix is also not captured. */
    public void testSiblingWithSharedPrefixNotMatched() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTestHelper",
                      Log.toLevelString(Log.CRIT));
        reread();
        assertTrue("a longer sibling name must not narrow this class",
                   log.shouldLog(Log.ERROR));
        assertFalse("must fall back to the default level, not CRIT",
                    log.shouldLog(Log.WARN));
    }

    /** With no matching entry the class falls back to logger.defaultLevel. */
    public void testNoEntryUsesDefaultLevel() throws IOException {
        reread();
        assertFalse("default is ERROR, so INFO must be suppressed", log.shouldLog(Log.INFO));
        assertTrue(log.shouldLog(Log.ERROR));
    }
}