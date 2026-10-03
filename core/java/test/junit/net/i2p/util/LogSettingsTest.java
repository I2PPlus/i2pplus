package net.i2p.util;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.Properties;

/**
 * @author Comwiz
 */
public class LogSettingsTest extends TestCase {

    private Properties p;
    private Log log;
    private I2PAppContext _context;
    private File f;

    /** Scratch directory for the per-test logger config. */
    private static final File TMPDIR = new File(System.getProperty("java.io.tmpdir"));

    private String origMinimumOnScreenLevel;
    private String origLogSettings;

    /**
     * Initializes the test fixture.
     *
     * Called before every test case method.
     */
    protected void setUp() throws IOException {

        _context = I2PAppContext.getGlobalContext();
        log = _context.logManager().getLog(LogSettingsTest.class);
        p = new Properties();
        // Point the LogManager at a private scratch file for the duration of the
        // test. Each test writes settings and then calls rereadConfig(), so the
        // file it writes and the file the LogManager re-reads have to be the same
        // one. They were not: the test wrote a relative "logger.config" (resolving
        // into the module basedir, inside the source tree) while rereadConfig()
        // read <configDir>/logger.config, so the settings never took effect and
        // the assertions only passed because of leftover state from earlier runs.
        // Scratch file under the temp dir keeps the workspace clean and makes the
        // round trip actually work.
        f = File.createTempFile("i2p-logsettings", ".config", TMPDIR);
        // Seed from the config the router would actually use. These tests only
        // override two properties and assert about everything else, so they need
        // the real baseline; starting from an empty file silently changes the
        // default level and the messages under test never appear.
        File live = new File(_context.getConfigDir(), LogManager.CONFIG_LOCATION_DEFAULT);
        if (live.exists()) {
            copyFile(live, f);
        }
        _context.logManager().setConfig(f.getAbsolutePath());
        DataHelper.loadProps(p, f);
        origMinimumOnScreenLevel = p.getProperty("logger.record.net.i2p.util.LogSettingsTest", Log.STR_ERROR);
        origLogSettings = p.getProperty("logger.minimumOnScreenLevel", Log.STR_CRIT);
    }

    protected void tearDown() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", origMinimumOnScreenLevel);
        p.setProperty("logger.minimumOnScreenLevel", origLogSettings);
        DataHelper.storeProps(p, f);
        // Restore the LogManager before removing the file: it is a global
        // singleton shared with every later test in this JVM, and leaving it
        // pointed at a deleted scratch file would silence logging for all of them.
        _context.logManager().setConfig(LogManager.CONFIG_LOCATION_DEFAULT);
        if (!f.delete()) {
            f.deleteOnExit();
        }

        System.gc();
    }

    /** Copy a config file, so the scratch fixture starts from the real baseline. */
    private static void copyFile(File from, File to) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(from);
        try {
            java.io.OutputStream out = new java.io.FileOutputStream(to);
            try {
                byte[] buf = new byte[4096];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * Scan for the expected patterns on a reader thread, so the test waits for
     * the writer to actually push the records instead of guessing with a fixed
     * sleep.
     *
     * <p>The pipe blocks, so the scan cannot run inline: a reader thread is the
     * only way to wait on real output. {@code LogManager.flush()} merely
     * notifies the writer and gives up after a bounded poll, so records may
     * still be in flight when it returns; the previous blanket 1500ms sleep
     * covered that by luck rather than by construction. The bound below is
     * generous for a loaded machine and still returns as soon as the last
     * pattern lands.
     *
     * @return true if every pattern was seen before the bound expired
     */
    private boolean awaitPatterns(final BufferedReader in, final int nLines, final String... patterns) {
        final java.util.concurrent.atomic.AtomicBoolean found =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try { found.set(scanForPatterns(in, nLines, patterns)); }
                catch (IOException ioe) { /* left false; the assert reports it */ }
            }
        }, "LogSettingsTest-scan");
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(5000);
        } catch (InterruptedException ie) {
            // Report whatever was seen rather than propagating: an interrupt
            // here must not turn a logging assertion into an error.
            Thread.currentThread().interrupt();
        }
        return found.get();
    }

    /**
     * Read up to nLines lines from the pipe, scanning for expected patterns.
     * Returns true if all expected messages were found.
     */
    private boolean scanForPatterns(BufferedReader in, int nLines, String... patterns) throws IOException {
        boolean[] found = new boolean[patterns.length];
        int totalFound = 0;
        // Read nLines + 5 extra to drain any interleaved log output
        int maxRead = nLines + 5;
        for (int i = 0; i < maxRead; i++) {
            String line = in.readLine();
            if (line == null) break;
            for (int j = 0; j < patterns.length; j++) {
                if (!found[j] && line.contains(patterns[j])) {
                    found[j] = true;
                    totalFound++;
                }
            }
            if (totalFound == patterns.length) break;
        }
        return totalFound == patterns.length;
    }

    public void testDebug() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", Log.toLevelString(Log.DEBUG));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));

        DataHelper.storeProps(p, f);

        _context.logManager().rereadConfig();

        PipedInputStream pin = new PipedInputStream(8192);
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));

        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));

        System.setOut(pout);

        try {
            log.debug("DEBUG" + ": debug");
            log.info("DEBUG" + ": info");
            log.warn("DEBUG" + ": warn");
            log.error("DEBUG" + ": error");
            log.log(Log.CRIT, "DEBUG" + ": crit");
            _context.logManager().flush();

            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all DEBUG messages found", awaitPatterns(in, 15, "DEBUG: debug", "DEBUG: info", "DEBUG: warn", "DEBUG: error", "DEBUG: crit"));
        } finally {
            System.setOut(systemOut);
            pout.close();
        }
    }

    public void testInfo() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", Log.toLevelString(Log.INFO));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));

        DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();

        PipedInputStream pin = new PipedInputStream(8192);
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));

        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));

        System.setOut(pout);

        try {
            log.debug("INFO" + ": debug");
            log.info("INFO" + ": info");
            log.warn("INFO" + ": warn");
            log.error("INFO" + ": error");
            log.log(Log.CRIT, "INFO" + ": crit");
            _context.logManager().flush();

            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all INFO messages found", awaitPatterns(in, 14, "INFO: info", "INFO: warn", "INFO: error", "INFO: crit"));
        } finally {
            System.setOut(systemOut);
            pout.close();
        }
    }

    public void testWarn() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", Log.toLevelString(Log.WARN));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));

        DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();

        PipedInputStream pin = new PipedInputStream(8192);
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));

        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));

        System.setOut(pout);

        try {
            log.debug("WARN" + ": debug");
            log.info("WARN" + ": info");
            log.warn("WARN" + ": warn");
            log.error("WARN" + ": error");
            log.log(Log.CRIT, "WARN" + ": crit");
            _context.logManager().flush();

            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all WARN messages found", awaitPatterns(in, 13, "WARN: warn", "WARN: error", "WARN: crit"));
        } finally {
            System.setOut(systemOut);
            pout.close();
        }
    }

    public void testError() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", Log.toLevelString(Log.ERROR));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));

        DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();

        PipedInputStream pin = new PipedInputStream(8192);
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));

        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));

        System.setOut(pout);

        try {
            log.debug("ERROR" + ": debug");
            log.info("ERROR" + ": info");
            log.warn("ERROR" + ": warn");
            log.error("ERROR" + ": error");
            log.log(Log.CRIT, "ERROR" + ": crit");
            _context.logManager().flush();

            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all ERROR messages found", awaitPatterns(in, 12, "ERROR: error", "ERROR: crit"));
        } finally {
            System.setOut(systemOut);
            pout.close();
        }
    }

    public void testCrit() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", Log.toLevelString(Log.CRIT));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));

        DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();

        PipedInputStream pin = new PipedInputStream(8192);
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));

        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));

        System.setOut(pout);

        try {
            log.debug("CRIT" + ": debug");
            log.info("CRIT" + ": info");
            log.warn("CRIT" + ": warn");
            log.error("CRIT" + ": error");
            log.log(Log.CRIT, "CRIT" + ": crit");
            _context.logManager().flush();

            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all CRIT messages found", awaitPatterns(in, 11, "CRIT: crit"));
        } finally {
            System.setOut(systemOut);
            pout.close();
        }
    }
}
