package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.i2ptunnel.I2PTunnelRunner.RaceWin;
import org.junit.Test;

/**
 * Unit tests for dual-race winner preservation on empty-response retries:
 * the race delivers the winner inside a pushback stream that still holds the
 * first response byte, so the redrive path must read from the supplied stream
 * instead of reacquiring a fresh socket stream that starts past that byte
 * (which would corrupt the response header), and every race exit path must
 * close exactly the sockets it does not return.
 *
 * @since 0.9.71+
 */
public class I2PTunnelRunnerRaceTest {

    private static final byte[] REQ = "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    /**
     * The supplied stream (a race winner's pushback wrapper) must win over a
     * fresh socket stream: reacquiring would hand the forwarder a stream that
     * never saw the byte the race already consumed.
     */
    @Test
    public void testPickPrefersSuppliedOverSocketStream() throws Exception {
        InputStream supplied = new ByteArrayInputStream("SUPPLIED".getBytes(StandardCharsets.UTF_8));
        I2PSocket sock = new MockI2PSocket("SOCKET".getBytes(StandardCharsets.UTF_8)).sock;
        InputStream picked = I2PTunnelRunner.pickRedriveStream(supplied, sock);
        assertSame(supplied, picked);
        byte[] buf = new byte[8];
        assertEquals(8, picked.read(buf));
        assertEquals("SUPPLIED", new String(buf, StandardCharsets.UTF_8));
    }

    /**
     * With no caller-supplied stream the picker falls back to the live socket.
     */
    @Test
    public void testPickFallsBackToSocketStreamWhenAbsent() throws Exception {
        I2PSocket sock = new MockI2PSocket("SOCKONLY".getBytes(StandardCharsets.UTF_8)).sock;
        InputStream picked = I2PTunnelRunner.pickRedriveStream(null, sock);
        assertNotNull(picked);
        byte[] buf = new byte[8];
        assertEquals(8, picked.read(buf));
        assertEquals("SOCKONLY", new String(buf, StandardCharsets.UTF_8));
    }

    /**
     * Neither side available: null, no throw.
     */
    @Test
    public void testPickNullWhenBothAbsent() throws Exception {
        assertNull(I2PTunnelRunner.pickRedriveStream(null, null));
    }

    /**
     * The socket holding first-byte data wins; its pushback stream still
     * delivers that byte; the loser is closed and the winner stays open;
     * both legs received the buffered request.
     */
    @Test
    public void testRaceWinnerKeepsFirstByteAndClosesLoser() throws Exception {
        MockI2PSocket a = new MockI2PSocket("H".getBytes(StandardCharsets.UTF_8));
        MockI2PSocket b = new MockI2PSocket(new byte[0]);
        I2PTunnelRunner r = newRunner();
        RaceWin win = r.raceEmptyPair(new I2PSocket[]{a.sock, b.sock}, REQ);
        assertNotNull(win);
        assertSame(a.sock, win.sock);
        assertEquals('H', win.in.read());
        assertTrue("loser must be closed", b.closed.get());
        assertFalse("winner must stay open", a.closed.get());
        assertArrayEquals(REQ, a.out.toByteArray());
        assertArrayEquals(REQ, b.out.toByteArray());
    }

    /**
     * Both legs empty: nothing returned, both sockets closed.
     */
    @Test
    public void testRaceBothEmptyClosesBoth() throws Exception {
        MockI2PSocket a = new MockI2PSocket(new byte[0]);
        MockI2PSocket b = new MockI2PSocket(new byte[0]);
        I2PTunnelRunner r = newRunner();
        RaceWin win = r.raceEmptyPair(new I2PSocket[]{a.sock, b.sock}, REQ);
        assertNull(win);
        assertTrue(a.closed.get());
        assertTrue(b.closed.get());
    }

    /**
     * End-to-end pin for the header-corruption bug: the redrive stream
     * picker must return the race winner's pushback wrapper (still holding
     * the first byte), not a freshly acquired socket stream without it.
     */
    @Test
    public void testRaceWinnerFirstByteSurvivesRedrivePick() throws Exception {
        MockI2PSocket a = new MockI2PSocket("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
        MockI2PSocket b = new MockI2PSocket(new byte[0]);
        I2PTunnelRunner r = newRunner();
        RaceWin win = r.raceEmptyPair(new I2PSocket[]{a.sock, b.sock}, REQ);
        assertNotNull(win);
        InputStream redrive = I2PTunnelRunner.pickRedriveStream(win.in, win.sock);
        assertSame("redrive must keep the pushback stream holding the first byte",
                   win.in, redrive);
        assertEquals('H', redrive.read());
    }

    /**
     * Construct a never-started base runner; raceEmptyPair only touches its
     * pair arguments and the logger.
     */
    private static I2PTunnelRunner newRunner() {
        return new I2PTunnelRunner(new Socket(),
                new MockI2PSocket(new byte[0]).sock, new Object(),
                null, null, null, null, false, false);
    }

    /**
     * Configurable I2PSocket proxy: fresh input stream per call, shared
     * captured output, close() flips the flag.
     */
    private static final class MockI2PSocket {
        final AtomicBoolean closed = new AtomicBoolean();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] input;
        final I2PSocket sock;

        MockI2PSocket(byte[] input) {
            this.input = input;
            this.sock = (I2PSocket) Proxy.newProxyInstance(I2PSocket.class.getClassLoader(),
                    new Class<?>[]{I2PSocket.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            String name = m.getName();
                            if ("getInputStream".equals(name)) {
                                return new ByteArrayInputStream(MockI2PSocket.this.input);
                            }
                            if ("getOutputStream".equals(name)) {
                                return MockI2PSocket.this.out;
                            }
                            if ("close".equals(name)) {
                                MockI2PSocket.this.closed.set(true);
                                return null;
                            }
                            Class<?> rt = m.getReturnType();
                            if (rt == boolean.class) {return Boolean.FALSE;}
                            if (rt == int.class) {return Integer.valueOf(0);}
                            if (rt == long.class) {return Long.valueOf(0);}
                            return null;
                        }
                    });
        }
    }
}
