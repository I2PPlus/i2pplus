package net.i2p.i2ptunnel.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Properties;

import net.i2p.i2ptunnel.TunnelController;

/**
 * Tests the TunnelConfig bean-to-properties conversion implemented by
 * getConfig() and its helpers.
 *
 * @since 0.9.70+
 */
public class TunnelConfigTest {

    private static TunnelConfig newConfig(String type) {
        TunnelConfig tc = new TunnelConfig();
        tc.setType(type);
        return tc;
    }

    @Test
    public void testServerBasic() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_HTTP_SERVER);
        tc.setName("test");
        tc.setDescription("desc");
        tc.setTargetPort(80);
        Properties out = tc.getConfig();
        assertEquals(TunnelController.TYPE_HTTP_SERVER, out.getProperty(TunnelController.PROP_TYPE));
        assertEquals("test", out.getProperty(TunnelController.PROP_NAME));
        assertEquals("desc", out.getProperty(TunnelController.PROP_DESCR));
        assertEquals("80", out.getProperty(TunnelController.PROP_TARGET_PORT));
        assertEquals("false", out.getProperty(TunnelController.PROP_START));
        assertNull(out.getProperty(TunnelController.PROP_LISTEN_PORT));
        assertNull(out.getProperty(TunnelController.PROP_SHARED));
        assertEquals("false", out.getProperty(TunnelController.OPT_BUNDLE_REPLY));
    }

    @Test
    public void testClientBasic() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_HTTP_CLIENT);
        tc.setPort(4444);
        Properties out = tc.getConfig();
        assertEquals("4444", out.getProperty(TunnelController.PROP_LISTEN_PORT));
        assertEquals("false", out.getProperty(TunnelController.PROP_SHARED));
        assertEquals("", out.getProperty(TunnelController.PROP_INTFC));
        assertNull(out.getProperty(TunnelController.PROP_TARGET_PORT));
        assertNull(out.getProperty(TunnelController.OPT_BUNDLE_REPLY));
    }

    @Test
    public void testServerForcesBundleReply() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_IRC_SERVER);
        Properties out = tc.getConfig();
        assertEquals("true", out.getProperty(TunnelController.OPT_BUNDLE_REPLY));
    }

    @Test
    public void testStreamrClientUsesTargetHost() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_STREAMR_CLIENT);
        tc.setTargetHost("127.0.0.1");
        Properties out = tc.getConfig();
        assertEquals("127.0.0.1", out.getProperty(TunnelController.PROP_TARGET_HOST));
        assertNull(out.getProperty(TunnelController.PROP_INTFC));
    }

    @Test
    public void testStreamrServerUsesInterface() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_STREAMR_SERVER);
        tc.setReachableBy("0.0.0.0");
        Properties out = tc.getConfig();
        assertEquals("0.0.0.0", out.getProperty(TunnelController.PROP_INTFC));
        assertNull(out.getProperty(TunnelController.PROP_TARGET_HOST));
    }

    @Test
    public void testProxyList() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_HTTP_CLIENT);
        tc.setProxyList("proxy1:1234");
        Properties out = tc.getConfig();
        assertEquals("proxy1:1234", out.getProperty(TunnelController.PROP_PROXIES));
    }

    @Test
    public void testFilterDefinition() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_HTTP_SERVER);
        tc.setFilterDefinition("filter.txt");
        Properties out = tc.getConfig();
        assertEquals("filter.txt", out.getProperty(TunnelController.PROP_FILTER));
    }

    @Test
    public void testI2CPHostAndPort() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_HTTP_CLIENT);
        tc.setClientHost("127.0.0.1");
        tc.setClientPort("7654");
        Properties out = tc.getConfig();
        assertEquals("127.0.0.1", out.getProperty(TunnelController.PROP_I2CP_HOST));
        assertEquals("7654", out.getProperty(TunnelController.PROP_I2CP_PORT));
    }

    @Test
    public void testDescriptionHashSanitized() {
        TunnelConfig tc = newConfig(TunnelController.TYPE_HTTP_SERVER);
        tc.setDescription("a#b");
        assertEquals("a b", tc.getConfig().getProperty(TunnelController.PROP_DESCR));
    }
}
