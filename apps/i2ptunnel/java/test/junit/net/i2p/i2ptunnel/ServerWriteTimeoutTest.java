package net.i2p.i2ptunnel;

import java.util.Properties;

import net.i2p.client.streaming.I2PSocketOptions;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link I2PTunnelServer#effectiveWriteTimeout(Properties)}.
 * <p>
 * The helper decides the bounded write timeout applied to the server's outbound I2P stream.
 * When the operator has set a write-timeout key (either the server's own
 * {@link I2PTunnelServer#PROP_WRITE_TIMEOUT} or the streaming standard
 * {@link I2PSocketOptions#PROP_WRITE_TIMEOUT}), the caller honors it and applies none of its
 * own; otherwise it returns the bounded default so unconfigured tunnels cannot fall back to
 * the full streaming disconnect timeout (120s) inside flush()/close().
 */
public class ServerWriteTimeoutTest {

    @Test
    public void testNullPropsUsesBoundedDefault() {
        long t = I2PTunnelServer.effectiveWriteTimeout(null);
        assertEquals(60_000L, t);
    }

    @Test
    public void testEmptyPropsUsesBoundedDefault() {
        long t = I2PTunnelServer.effectiveWriteTimeout(new Properties());
        assertEquals(60_000L, t);
    }

    @Test
    public void testUnrelatedPropsUsesBoundedDefault() {
        Properties p = new Properties();
        p.setProperty("i2ptunnel.server.readTimeout", "5000");
        p.setProperty("i2ptunnel.client.maxConnections", "10");
        assertEquals(60_000L, I2PTunnelServer.effectiveWriteTimeout(p));
    }

    @Test
    public void testServerKeyPresentHonorsExplicit() {
        Properties p = new Properties();
        p.setProperty(I2PTunnelServer.PROP_WRITE_TIMEOUT, "3000");
        assertTrue(I2PTunnelServer.effectiveWriteTimeout(p) < 0);
    }

    @Test
    public void testStreamingKeyPresentHonorsExplicit() {
        Properties p = new Properties();
        p.setProperty(I2PSocketOptions.PROP_WRITE_TIMEOUT, "3000");
        assertTrue(I2PTunnelServer.effectiveWriteTimeout(p) < 0);
    }

    @Test
    public void testBothKeysPresentHonorsExplicit() {
        Properties p = new Properties();
        p.setProperty(I2PTunnelServer.PROP_WRITE_TIMEOUT, "3000");
        p.setProperty(I2PSocketOptions.PROP_WRITE_TIMEOUT, "5000");
        assertTrue(I2PTunnelServer.effectiveWriteTimeout(p) < 0);
    }
}
