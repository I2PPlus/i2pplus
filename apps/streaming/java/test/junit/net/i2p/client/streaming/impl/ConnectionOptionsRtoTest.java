package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Whether the initial RTO survives the two ways options get rebuilt (§4.3).
 * setProperties() applies only the properties present in its argument, and
 * buildOptions() copies the current options and overlays the changes. Both
 * paths used to reset the RTO to the compiled default - one because it read the
 * property without asking whether it was there, the other because the copy
 * carried every other field but that one - so a configured RTO came back as the
 * default the moment anything else in the options was touched.
 */
public class ConnectionOptionsRtoTest {

    /** Private in ConnectionOptions; spelled out here to keep it that way. */
    private static final String PROP_INITIAL_RTO = "i2p.streaming.initialRTO";

    private int _savedDefault;

    @Before
    public void setUp() {
        _savedDefault = ConnectionOptions.getInitialRTO();
        ConnectionOptions.setInitialRTO(9000);
    }

    @After
    public void tearDown() {
        ConnectionOptions.setInitialRTO(_savedDefault);
    }

    /**
     * A merge that never mentions the RTO leaves the configured one alone.
     */
    @Test
    public void testMergeWithoutTheRtoKeepsIt() {
        Properties opts = new Properties();
        opts.setProperty(PROP_INITIAL_RTO, "7000");
        ConnectionOptions options = new ConnectionOptions(opts);
        assertEquals(7000, options.getRTO());
        options.setProperties(new Properties());
        assertEquals("an empty merge reset the RTO", 7000, options.getRTO());
    }

    /**
     * A merge that does mention it still applies it.
     */
    @Test
    public void testMergeWithTheRtoAppliesIt() {
        ConnectionOptions options = new ConnectionOptions(new Properties());
        Properties change = new Properties();
        change.setProperty(PROP_INITIAL_RTO, "4000");
        options.setProperties(change);
        assertEquals("a merge carrying the RTO did not apply it", 4000, options.getRTO());
    }

    /**
     * The copy buildOptions() makes keeps the RTO.
     */
    @Test
    public void testCopyKeepsTheRto() {
        Properties opts = new Properties();
        opts.setProperty(PROP_INITIAL_RTO, "7000");
        ConnectionOptions options = new ConnectionOptions(opts);
        ConnectionOptions copy = new ConnectionOptions(options);
        assertEquals("the copy lost the RTO", 7000, copy.getRTO());
    }
}
