package net.i2p.i2ptunnel.socks;

import static org.junit.Assert.*;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.socks.SOCKSException;
import net.i2p.i2ptunnel.I2PTunnelHTTPClientBase;

/**
 * Tests SOCKSServerFactory, specifically the SOCKS 4/4a authorization bypass fix.
 *
 * @since 0.9.71+
 */
public class SOCKSServerFactoryTest {

    private static Socket mockSocket(int socksVer) throws IOException {
        Socket s = Mockito.mock(Socket.class);
        PipedInputStream pis = new PipedInputStream();
        PipedOutputStream pos = new PipedOutputStream(pis);
        Mockito.when(s.getInputStream()).thenReturn(pis);
        pos.write(socksVer);
        pos.flush();
        Mockito.when(s.getOutputStream()).thenReturn(new DataOutputStream(new java.io.ByteArrayOutputStream()));
        return s;
    }

    @Test
    public void testSOCKS4aRejectedWhenAuthEnabled() throws Exception {
        Properties props = new Properties();
        props.setProperty(I2PTunnelHTTPClientBase.PROP_AUTH, "true");
        try {
            SOCKSServerFactory.createSOCKSServer(I2PAppContext.getGlobalContext(), mockSocket(0x04), props);
            fail("expected SOCKSException");
        } catch (SOCKSException e) {
            assertTrue(e.getMessage().contains("SOCKS 4/4a"));
        }
    }

    @Test
    public void testSOCKS4aAllowedWhenAuthDisabled() throws Exception {
        Properties props = new Properties();
        Socket s = mockSocket(0x04);
        SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(
            I2PAppContext.getGlobalContext(), s, props);
        assertNotNull(serv);
        assertTrue(serv instanceof SOCKS4aServer);
    }

    @Test
    public void testSOCKS5AllowedRegardlessOfAuth() throws Exception {
        Properties props = new Properties();
        props.setProperty(I2PTunnelHTTPClientBase.PROP_AUTH, "true");
        Socket s = mockSocket(0x05);
        SOCKSServer serv = SOCKSServerFactory.createSOCKSServer(
            I2PAppContext.getGlobalContext(), s, props);
        assertNotNull(serv);
        assertTrue(serv instanceof SOCKS5Server);
    }
}
