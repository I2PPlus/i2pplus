package net.i2p.i2ptunnel.socks;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.socks.SOCKSException;
import net.i2p.socks.SOCKS5Constants.AddressType;
import net.i2p.socks.SOCKS5Constants.Command;
import net.i2p.socks.SOCKS5Constants.Reply;

/**
 * Tests the SOCKS5 request validation helpers checkCommand() and
 * parseAddressType().
 *
 * @since 0.9.70+
 */
public class SOCKS5ServerTest {

    private static final byte[] REJECT_REPLY = {
        0x05, 0x07, 0x00, 0x03, 0x07,
        '0', '.', '0', '.', '0', '.', '0',
        0x00, 0x00
    };
    private static final byte[] BAD_ADDRTYPE_REPLY = {
        0x05, 0x08, 0x00, 0x03, 0x07,
        '0', '.', '0', '.', '0', '.', '0',
        0x00, 0x00
    };

    private static SOCKS5Server newServer() {
        return new SOCKS5Server(I2PAppContext.getGlobalContext(), new Socket(), new Properties());
    }

    private static Object invoke(SOCKS5Server server, String name, Class<?>[] types, Object... args) throws Throwable {
        Method m = SOCKS5Server.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(server, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        }
    }

    @Test
    public void testCheckCommandConnect() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Object rv = invoke(server, "checkCommand",
                           new Class<?>[] {int.class, DataOutputStream.class},
                           Command.CONNECT, new DataOutputStream(baos));
        assertEquals(Command.CONNECT, rv);
        assertEquals(0, baos.size());
    }

    @Test
    public void testCheckCommandTorResolve() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Object rv = invoke(server, "checkCommand",
                           new Class<?>[] {int.class, DataOutputStream.class},
                           Command.TOR_RESOLVE, new DataOutputStream(baos));
        assertEquals(Command.TOR_RESOLVE, rv);
        assertEquals(0, baos.size());
    }

    @Test
    public void testCheckCommandBindRejected() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            invoke(server, "checkCommand",
                   new Class<?>[] {int.class, DataOutputStream.class},
                   Command.BIND, new DataOutputStream(baos));
            fail("expected SOCKSException");
        } catch (SOCKSException se) {
            assertTrue(se.getMessage().contains("BIND"));
            assertArrayEquals(REJECT_REPLY, baos.toByteArray());
        }
    }

    @Test
    public void testCheckCommandUnknownRejected() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            invoke(server, "checkCommand",
                   new Class<?>[] {int.class, DataOutputStream.class},
                   0x42, new DataOutputStream(baos));
            fail("expected SOCKSException");
        } catch (SOCKSException se) {
            assertArrayEquals(REJECT_REPLY, baos.toByteArray());
        }
    }

    @Test
    public void testParseIpv4() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invoke(server, "parseAddressType",
               new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
               new DataInputStream(new ByteArrayInputStream(new byte[] {0x01, 10, 0, 0, 1})),
               Command.CONNECT, new DataOutputStream(baos));
        assertEquals("10.0.0.1", server.connHostName);
        assertEquals(AddressType.IPV4, server.addressType);
        assertEquals(0, baos.size());
    }

    @Test
    public void testParseDomainName() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] req = {0x03, 4, 't', 'e', 's', 't'};
        invoke(server, "parseAddressType",
               new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
               new DataInputStream(new ByteArrayInputStream(req)),
               Command.CONNECT, new DataOutputStream(baos));
        assertEquals("test", server.connHostName);
        assertEquals(AddressType.DOMAINNAME, server.addressType);
        assertEquals(0, baos.size());
    }

    @Test
    public void testParseDomainNameZeroLength() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            invoke(server, "parseAddressType",
                   new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
                   new DataInputStream(new ByteArrayInputStream(new byte[] {0x03, 0})),
                   Command.CONNECT, new DataOutputStream(baos));
            fail("expected SOCKSException");
        } catch (SOCKSException se) {
            assertTrue(se.getMessage().contains("DOMAINNAME"));
        }
    }

    @Test
    public void testParseIpv6() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] req = {0x04, 0x20, 0x01, 0x0d, (byte) 0xb8,
                      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        invoke(server, "parseAddressType",
               new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
               new DataInputStream(new ByteArrayInputStream(req)),
               Command.CONNECT, new DataOutputStream(baos));
        assertTrue(server.connHostName.startsWith("2001:db8"));
        assertEquals(AddressType.IPV6, server.addressType);
    }

    @Test
    public void testParseUnknownAddressType() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            invoke(server, "parseAddressType",
                   new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
                   new DataInputStream(new ByteArrayInputStream(new byte[] {0x09})),
                   Command.CONNECT, new DataOutputStream(baos));
            fail("expected SOCKSException");
        } catch (SOCKSException se) {
            assertArrayEquals(BAD_ADDRTYPE_REPLY, baos.toByteArray());
        }
    }

    @Test
    public void testTorResolveCachesHostname() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        byte[] req = {0x03, 8, 't', 'e', 's', 't', '.', 'i', '2', 'p'};
        try {
            invoke(server, "parseAddressType",
                   new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
                   new DataInputStream(new ByteArrayInputStream(req)),
                   Command.TOR_RESOLVE, out);
            fail("expected SOCKSException");
        } catch (SOCKSException se) {
            assertEquals("ignore", se.getMessage());
        }
        byte[] reply = baos.toByteArray();
        assertEquals(10, reply.length);
        assertEquals(0x05, reply[0]);
        assertEquals(Reply.SUCCEEDED, reply[1]);
        assertEquals(AddressType.IPV4, reply[3]);
        assertEquals((byte) 0xff, reply[4]);
        String fakeIp = (reply[4] & 0xff) + "." + (reply[5] & 0xff) + "." +
                        (reply[6] & 0xff) + "." + (reply[7] & 0xff);

        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        byte[] ascii = fakeIp.getBytes("US-ASCII");
        byte[] req2 = new byte[2 + ascii.length];
        req2[0] = 0x03;
        req2[1] = (byte) ascii.length;
        System.arraycopy(ascii, 0, req2, 2, ascii.length);
        invoke(server, "parseAddressType",
               new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
               new DataInputStream(new ByteArrayInputStream(req2)),
               Command.CONNECT, new DataOutputStream(baos2));
        assertEquals("test.i2p", server.connHostName);
        assertEquals(0, baos2.size());
    }

    @Test
    public void testTorCacheMiss() throws Throwable {
        SOCKS5Server server = newServer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] req = {0x03, 5, '2', '5', '5', '.', '1'};
        try {
            invoke(server, "parseAddressType",
                   new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
                   new DataInputStream(new ByteArrayInputStream(req)),
                   Command.CONNECT, new DataOutputStream(baos));
            fail("expected SOCKSException");
        } catch (SOCKSException se) {
            assertTrue(se.getMessage().contains("cache"));
            assertArrayEquals(BAD_ADDRTYPE_REPLY, baos.toByteArray());
        }
    }

    @Test
    public void testIpv4MappedToDomain() throws Throwable {
        Properties props = new Properties();
        props.setProperty("ipmapping.10.0.0.1", "example.i2p");
        SOCKS5Server server = new SOCKS5Server(I2PAppContext.getGlobalContext(), new Socket(), props);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invoke(server, "parseAddressType",
               new Class<?>[] {DataInputStream.class, int.class, DataOutputStream.class},
               new DataInputStream(new ByteArrayInputStream(new byte[] {0x01, 10, 0, 0, 1})),
               Command.CONNECT, new DataOutputStream(baos));
        assertEquals("example.i2p", server.connHostName);
        assertEquals(AddressType.DOMAINNAME, server.addressType);
    }
}
