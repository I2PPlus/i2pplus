package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Properties;

import net.i2p.crypto.SigType;

/**
 * Tests the per-type default configuration applied by
 * TunnelController.setConfig() and its helpers.
 *
 * @since 0.9.70+
 */
public class TunnelControllerTest {

    private static final String OPT_LIMIT_ACTION = "option.i2p.streaming.limitAction";
    private static final String OPT_GZIP = "option.i2cp.gzip";
    private static final String OPT_BUNDLE = "option.shouldBundleReplyInfo";
    private static final String OPT_ENCTYPE = "option.i2cp.leaseSetEncType";
    private static final String OPT_TAGS_SEND = "option.crypto.tagsToSend";
    private static final String OPT_LOW_TAGS = "option.crypto.lowTagThreshold";
    private static final String OPT_PRIORITY = "option.outbound.priority";
    private static final String OPT_SIG_TYPE = "option.i2cp.destination.sigType";
    private static final String OPT_MAX_CONNS_MIN = "option.i2p.streaming.maxConnsPerMinute";
    private static final String OPT_MAX_CONNS_HOUR = "option.i2p.streaming.maxConnsPerHour";
    private static final String OPT_MAX_CONNS_DAY = "option.i2p.streaming.maxConnsPerDay";
    private static final String OPT_MAX_TOTAL_CONNS_MIN = "option.i2p.streaming.maxTotalConnsPerMinute";
    private static final String OPT_MAX_TOTAL_CONNS_HOUR = "option.i2p.streaming.maxTotalConnsPerHour";
    private static final String OPT_MAX_TOTAL_CONNS_DAY = "option.i2p.streaming.maxTotalConnsPerDay";
    private static final String OPT_MAX_STREAMS = "option.i2p.streaming.maxConcurrentStreams";
    private static final String OPT_LIMITS_SET = "option.i2p.streaming.limitsManuallySet";
    private static final String OPT_POST_MAX = "option.maxPosts";
    private static final String OPT_PROFILE = "i2p.streaming.profile";

    private static TunnelController newController(String type) {
        Properties cfg = new Properties();
        cfg.setProperty(TunnelController.PROP_TYPE, type);
        return new TunnelController(cfg, "", false);
    }

    private static TunnelController newController(Properties cfg, String prefix) {
        return new TunnelController(cfg, prefix, false);
    }

    @Test
    public void testHttpServerDefaults() {
        TunnelController tc = newController(TunnelController.TYPE_HTTP_SERVER);
        Properties out = tc.getConfig("");
        assertEquals("http", out.getProperty(OPT_LIMIT_ACTION));
        assertEquals("false", out.getProperty(OPT_GZIP));
        assertEquals("false", out.getProperty(OPT_BUNDLE));
        assertEquals("6,4", out.getProperty(OPT_ENCTYPE));
    }

    @Test
    public void testHttpServerExistingValuesPreserved() {
        Properties cfg = new Properties();
        cfg.setProperty(TunnelController.PROP_TYPE, TunnelController.TYPE_HTTP_SERVER);
        cfg.setProperty(OPT_LIMIT_ACTION, "custom");
        cfg.setProperty(OPT_GZIP, "true");
        cfg.setProperty(OPT_BUNDLE, "custom");
        cfg.setProperty(OPT_ENCTYPE, "3");
        TunnelController tc = newController(cfg, "");
        Properties out = tc.getConfig("");
        assertEquals("custom", out.getProperty(OPT_LIMIT_ACTION));
        assertEquals("true", out.getProperty(OPT_GZIP));
        assertEquals("custom", out.getProperty(OPT_BUNDLE));
        assertEquals("3", out.getProperty(OPT_ENCTYPE));
    }

    @Test
    public void testHttpClientDefaults() {
        TunnelController tc = newController(TunnelController.TYPE_HTTP_CLIENT);
        Properties out = tc.getConfig("");
        assertEquals("6,4", out.getProperty(OPT_ENCTYPE));
        assertNull(out.getProperty(OPT_BUNDLE));
        assertNull(out.getProperty(OPT_GZIP));
        assertNull(out.getProperty(OPT_LIMIT_ACTION));
    }

    @Test
    public void testIrcServerDefaults() {
        TunnelController tc = newController(TunnelController.TYPE_IRC_SERVER);
        Properties out = tc.getConfig("");
        assertEquals("20", out.getProperty(OPT_TAGS_SEND));
        assertEquals("14", out.getProperty(OPT_LOW_TAGS));
        assertEquals("6,4", out.getProperty(OPT_ENCTYPE));
        assertEquals("10", out.getProperty(OPT_PRIORITY));
        assertEquals(TunnelController.PREFERRED_SIGTYPE.name(), out.getProperty(OPT_SIG_TYPE));
        assertEquals("true", out.getProperty(OPT_BUNDLE));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_CONNS_MIN), out.getProperty(OPT_MAX_CONNS_MIN));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_CONNS_HOUR), out.getProperty(OPT_MAX_CONNS_HOUR));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_CONNS_DAY), out.getProperty(OPT_MAX_CONNS_DAY));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_TOTAL_CONNS_MIN), out.getProperty(OPT_MAX_TOTAL_CONNS_MIN));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_TOTAL_CONNS_HOUR), out.getProperty(OPT_MAX_TOTAL_CONNS_HOUR));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_TOTAL_CONNS_DAY), out.getProperty(OPT_MAX_TOTAL_CONNS_DAY));
        assertEquals(Integer.toString(TunnelController.DEFAULT_MAX_STREAMS), out.getProperty(OPT_MAX_STREAMS));
        assertEquals("1", out.getProperty(OPT_PROFILE));
    }

    @Test
    public void testIrcClientDefaults() {
        TunnelController tc = newController(TunnelController.TYPE_IRC_CLIENT);
        Properties out = tc.getConfig("");
        assertEquals("20", out.getProperty(OPT_TAGS_SEND));
        assertEquals("14", out.getProperty(OPT_LOW_TAGS));
        assertEquals("6,4", out.getProperty(OPT_ENCTYPE));
        assertEquals("10", out.getProperty(OPT_PRIORITY));
        assertNotNull(out.getProperty(OPT_SIG_TYPE));
        assertNull(out.getProperty(OPT_BUNDLE));
        assertNull(out.getProperty(OPT_MAX_CONNS_MIN));
    }

    @Test
    public void testStreamrServerDefaults() {
        TunnelController tc = newController(TunnelController.TYPE_STREAMR_SERVER);
        Properties out = tc.getConfig("");
        assertEquals("false", out.getProperty(OPT_GZIP));
        assertEquals("false", out.getProperty(OPT_BUNDLE));
        assertEquals("6,4", out.getProperty(OPT_ENCTYPE));
        assertNotNull(out.getProperty(OPT_SIG_TYPE));
        assertNotNull(out.getProperty(OPT_MAX_CONNS_MIN));
    }

    @Test
    public void testLimitsSetRespected() {
        Properties cfg = new Properties();
        cfg.setProperty(TunnelController.PROP_TYPE, TunnelController.TYPE_IRC_SERVER);
        cfg.setProperty(OPT_LIMITS_SET, "true");
        TunnelController tc = newController(cfg, "");
        Properties out = tc.getConfig("");
        assertNull(out.getProperty(OPT_MAX_CONNS_MIN));
        assertNull(out.getProperty(OPT_POST_MAX));
    }

    @Test
    public void testSigTypePreserved() {
        Properties cfg = new Properties();
        cfg.setProperty(TunnelController.PROP_TYPE, TunnelController.TYPE_IRC_SERVER);
        cfg.setProperty(OPT_SIG_TYPE, "1");
        TunnelController tc = newController(cfg, "");
        assertEquals("1", tc.getConfig("").getProperty(OPT_SIG_TYPE));
    }

    @Test
    public void testPrefixFiltering() {
        Properties cfg = new Properties();
        cfg.setProperty("tun0." + TunnelController.PROP_TYPE, TunnelController.TYPE_HTTP_SERVER);
        cfg.setProperty("unrelated", "x");
        TunnelController tc = newController(cfg, "tun0.");
        Properties out = tc.getConfig("");
        assertEquals(TunnelController.TYPE_HTTP_SERVER, out.getProperty(TunnelController.PROP_TYPE));
        assertNull(out.getProperty("unrelated"));
        assertNull(out.getProperty("tun0." + TunnelController.PROP_TYPE));
    }

    @Test
    public void testPrefixedConfigRepliesPrefixed() {
        Properties cfg = new Properties();
        cfg.setProperty("tun0." + TunnelController.PROP_TYPE, TunnelController.TYPE_HTTP_SERVER);
        TunnelController tc = newController(cfg, "tun0.");
        Properties out = tc.getConfig("tun0.");
        assertEquals(TunnelController.TYPE_HTTP_SERVER, out.getProperty("tun0." + TunnelController.PROP_TYPE));
    }

    @Test
    public void testSigTypeConstantsUsable() {
        // guard that the test's expected value matches the router's preferred sigtype
        assertTrue(SigType.ECDSA_SHA256_P256.equals(TunnelController.PREFERRED_SIGTYPE) ||
                   SigType.DSA_SHA1.equals(TunnelController.PREFERRED_SIGTYPE) ||
                   SigType.EdDSA_SHA512_Ed25519.equals(TunnelController.PREFERRED_SIGTYPE));
    }
}
