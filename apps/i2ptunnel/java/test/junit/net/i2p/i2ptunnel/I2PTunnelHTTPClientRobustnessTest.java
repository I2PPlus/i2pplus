package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

/**
 * Tests for the HTTP client proxy robustness helpers:
 * the 503 service-unavailable response and the tunnel-pool fail-fast check.
 *
 * @since 0.9.70+
 */
public class I2PTunnelHTTPClientRobustnessTest {

    @Test
    public void testServiceUnavailableWithMessage() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        I2PTunnelHTTPClient.writeServiceUnavailable(baos, "No tunnels available");
        assertEquals("HTTP/1.1 503 Service Unavailable - No tunnels available\r\nConnection: close\r\n\r\n",
                     baos.toString());
    }

    @Test
    public void testServiceUnavailableNoMessage() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        I2PTunnelHTTPClient.writeServiceUnavailable(baos, null);
        assertEquals("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n", baos.toString());
    }

    @Test
    public void testServiceUnavailableEmptyMessage() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        I2PTunnelHTTPClient.writeServiceUnavailable(baos, "");
        assertEquals("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n", baos.toString());
    }

    @Test
    public void testServiceUnavailableNullOut() {
        I2PTunnelHTTPClient.writeServiceUnavailable(null, "ignored");
    }
}