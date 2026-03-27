package net.i2p.util;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
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

    private String origMinimumOnScreenLevel;
    private String origLogSettings;

    /**
     * Sets up the test fixture.
     *
     * Called before every test case method.
     */
    protected void setUp() throws IOException {

        _context = I2PAppContext.getGlobalContext();
        log = _context.logManager().getLog(LogSettingsTest.class);
        p = new Properties();
        f = new File("logger.config");
        if (!f.exists()) {
            FileWriter temp = new FileWriter(f);
            temp.close();
        }
        DataHelper.loadProps(p, f);
        origMinimumOnScreenLevel = p.getProperty("logger.record.net.i2p.util.LogSettingsTest", Log.STR_ERROR);
        origLogSettings = p.getProperty("logger.minimumOnScreenLevel", Log.STR_CRIT);
    }

    protected void tearDown() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettingsTest", origMinimumOnScreenLevel);
        p.setProperty("logger.minimumOnScreenLevel", origLogSettings);
        DataHelper.storeProps(p, f);

        System.gc();
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

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
            }
            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all DEBUG messages found", scanForPatterns(in, 15, "DEBUG: debug", "DEBUG: info", "DEBUG: warn", "DEBUG: error", "DEBUG: crit"));
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

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
            }
            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all INFO messages found", scanForPatterns(in, 14, "INFO: info", "INFO: warn", "INFO: error", "INFO: crit"));
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

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
            }
            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all WARN messages found", scanForPatterns(in, 13, "WARN: warn", "WARN: error", "WARN: crit"));
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

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
            }
            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all ERROR messages found", scanForPatterns(in, 12, "ERROR: error", "ERROR: crit"));
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

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
            }
            for (int i = 0; i < 10; i++) pout.println("");
            pout.flush();

            assertTrue("Not all CRIT messages found", scanForPatterns(in, 11, "CRIT: crit"));
        } finally {
            System.setOut(systemOut);
            pout.close();
        }
    }
}
