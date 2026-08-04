package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Headless end-to-end tests for the private CompressedRequestor class:
 * a real loopback ServerSocket acts as the webserver, a stub I2PSocket as
 * the browser. Exercises the request forwarding, header rewriting, gzip,
 * keepalive, and reset-propagation paths in run().
 */
public class CompressedRequestorTest {
    private static final I2PAppContext CTX = I2PAppContext.getGlobalContext();
    private static final Log LOG = new Log(CompressedRequestorTest.class);
    private static final String REQUESTOR_CLASS = I2PTunnelHTTPServer.class.getName() + "$CompressedRequestor";

    /** I2PSocket stub for the browser side: capture output, count reset()/close() */
    private static class BrowserSocket implements I2PSocket {
        private final ByteArrayInputStream _in;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int resetCount;
        int closeCount;
        long readTimeout = -1;

        BrowserSocket(byte[] in) {_in = new ByteArrayInputStream(in);}

        public InputStream getInputStream() {return _in;}
        public OutputStream getOutputStream() {return out;}
        public void reset() throws IOException {resetCount++;}
        public void close() throws IOException {closeCount++;}
        public void setReadTimeout(long ms) {readTimeout = ms;}
        public long getReadTimeout() {return readTimeout;}
        public int getLocalPort() {return 80;}
        public int getPort() {return 0;}
        public boolean isClosed() {return false;}
        public Destination getPeerDestination() {return null;}
        public Destination getThisDestination() {return null;}
        public I2PSocketOptions getOptions() {return null;}
        public void setOptions(I2PSocketOptions options) {}
        public void setSocketErrorListener(SocketErrorListener lsnr) {}
    }

    private ThreadPoolExecutor _executor;

    @Before
    public void setUp() {
        _executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() {
        _executor.shutdownNow();
    }

    /** Construct the private CompressedRequestor via reflection */
    private static Runnable newRequestor(Socket webserver, BrowserSocket browser, String headers,
                                         boolean compress, boolean upgrade, boolean keepalive,
                                         AtomicInteger waiter, ThreadPoolExecutor executor) throws Exception {
        Class<?> clazz = Class.forName(REQUESTOR_CLASS);
        Constructor<?> ctor = clazz.getDeclaredConstructor(Socket.class, I2PSocket.class, String.class,
                                                           I2PAppContext.class, Log.class, boolean.class,
                                                           boolean.class, ThreadPoolExecutor.class,
                                                           boolean.class, AtomicInteger.class);
        ctor.setAccessible(true);
        return (Runnable) ctor.newInstance(webserver, browser, headers, CTX, LOG, compress, upgrade,
                                           executor, keepalive, waiter);
    }

    private static byte[] readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            int c = in.read();
            if (c == -1) {break;}
            buf.write(c);
            String soFar = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (soFar.endsWith("\r\n\r\n")) {break;}
        }
        return buf.toByteArray();
    }

    private static String readRequestString(InputStream in) throws IOException {
        return new String(readRequest(in), StandardCharsets.UTF_8);
    }

    private static byte[] gzipPayload(byte[] response) throws IOException {
        String text = new String(response, StandardCharsets.UTF_8);
        int bodyStart = text.indexOf("\r\n\r\n");
        assertTrue("no header terminator", bodyStart >= 0);
        return Arrays.copyOfRange(response, bodyStart + 4, response.length);
    }

    /** Run a requestor against a server thread that handles one accepted connection */
    private void runScenario(BrowserSocket browser, String requestHeaders, boolean compress,
                             boolean upgrade, boolean keepalive, AtomicInteger waiter,
                             final ThrowingConsumer<Socket> serverHandler,
                             final AtomicReference<Throwable> serverError) throws Exception {
        final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread server = new Thread(new Runnable() {
            public void run() {
                try {
                    Socket s = ss.accept();
                    serverHandler.accept(s);
                } catch (Throwable t) {
                    serverError.set(t);
                }
            }
        });
        server.start();
        Socket webserver = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
        try {
            Runnable r = newRequestor(webserver, browser, requestHeaders, compress, upgrade, keepalive, waiter, _executor);
            r.run();
        } finally {
            webserver.close();
            ss.close();
        }
        server.join(10000);
        if (serverError.get() != null) {
            throw new AssertionError("server thread failed", serverError.get());
        }
    }

    /** like java.util.function.Consumer but throws */
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Throwable;
    }

    @Test
    public void testGetKeepAlive() throws Exception {
        BrowserSocket browser = new BrowserSocket(new byte[0]);
        AtomicInteger waiter = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        String request = "GET / HTTP/1.1\r\nHost: x.i2p\r\nConnection: close\r\n\r\n";
        final String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\n\r\nhello";
        runScenario(browser, request, false, false, true, waiter, new ThrowingConsumer<Socket>() {
            public void accept(Socket s) throws Throwable {
                String req = readRequestString(s.getInputStream());
                assertTrue(req.startsWith("GET / HTTP/1.1\r\n"));
                assertTrue(req.contains("Connection: close"));
                s.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                s.close();
            }
        }, err);
        assertEquals(2, waiter.get());
        assertEquals(0, browser.closeCount);
        String out = browser.out.toString();
        assertTrue(out.contains("200 OK"));
        assertTrue(out.contains("Content-Length: 5"));
        assertTrue(out.endsWith("hello"));
    }

    @Test
    public void testGetGzipCompressed() throws Exception {
        BrowserSocket browser = new BrowserSocket(new byte[0]);
        AtomicInteger waiter = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final byte[] body = new byte[2000];
        Arrays.fill(body, (byte) 'a');
        final String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 2000\r\n\r\n";
        runScenario(browser, "GET / HTTP/1.1\r\nHost: x.i2p\r\n\r\n", true, false, true, waiter,
                    new ThrowingConsumer<Socket>() {
            public void accept(Socket s) throws Throwable {
                readRequest(s.getInputStream());
                s.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().write(body);
                s.close();
            }
        }, err);
        assertEquals(2, waiter.get());
        String out = browser.out.toString();
        assertTrue(out.contains("200 OK"));
        assertTrue(out.contains("Content-Encoding: x-i2p-gzip"));
        byte[] payload = gzipPayload(browser.out.toByteArray());
        ByteArrayOutputStream unzipped = new ByteArrayOutputStream();
        InputStream gunzip = new GZIPInputStream(new ByteArrayInputStream(payload));
        byte[] buf = new byte[1024];
        int n;
        while ((n = gunzip.read(buf)) != -1) {unzipped.write(buf, 0, n);}
        gunzip.close();
        assertArrayEquals(body, unzipped.toByteArray());
    }

    @Test
    public void testPostNotKeepAlive() throws Exception {
        BrowserSocket browser = new BrowserSocket("body".getBytes(StandardCharsets.UTF_8));
        AtomicInteger waiter = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final String request = "POST /submit HTTP/1.1\r\nHost: x.i2p\r\nConnection: close\r\nContent-Length: 4\r\n\r\n";
        final String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\n\r\ndone!";
        runScenario(browser, request, false, false, true, waiter, new ThrowingConsumer<Socket>() {
            public void accept(Socket s) throws Throwable {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] req = readRequest(s.getInputStream());
                buf.write(req);
                String reqStr = new String(req, StandardCharsets.UTF_8);
                assertTrue(reqStr.startsWith("POST /submit HTTP/1.1\r\n"));
                byte[] bodyBytes = new byte[4];
                readFully(s.getInputStream(), bodyBytes);
                assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), bodyBytes);
                s.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                s.close();
            }
        }, err);
        assertEquals(1, waiter.get());
        assertEquals(1, browser.closeCount);
        assertTrue(browser.out.toString().contains("done!"));
    }

    @Test
    public void testUpgradeNotKeepAlive() throws Exception {
        BrowserSocket browser = new BrowserSocket(new byte[0]);
        AtomicInteger waiter = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final String response = "HTTP/1.1 101 Switching Protocols\r\nConnection: upgrade\r\n\r\n";
        runScenario(browser, "GET /chat HTTP/1.1\r\nHost: x.i2p\r\n\r\n", false, true, true, waiter,
                    new ThrowingConsumer<Socket>() {
            public void accept(Socket s) throws Throwable {
                readRequest(s.getInputStream());
                s.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                s.close();
            }
        }, err);
        assertEquals(1, waiter.get());
        assertTrue(browser.out.toString().contains("101 Switching Protocols"));
        assertEquals(1, browser.closeCount);
    }

    @Test
    public void testServerClosesWithoutResponse() throws Exception {
        BrowserSocket browser = new BrowserSocket(new byte[0]);
        AtomicInteger waiter = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        runScenario(browser, "GET / HTTP/1.1\r\nHost: x.i2p\r\n\r\n", false, false, true, waiter,
                    new ThrowingConsumer<Socket>() {
            public void accept(Socket s) throws Throwable {
                readRequest(s.getInputStream());
                s.close();
            }
        }, err);
        assertEquals(1, waiter.get());
        assertEquals(1, browser.closeCount);
        assertEquals(0, browser.resetCount);
        assertEquals(0, browser.out.size());
    }

    @Test
    public void testServerResetPropagatesToBrowser() throws Exception {
        BrowserSocket browser = new BrowserSocket(new byte[0]);
        AtomicInteger waiter = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final String partial = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n0123456789";
        runScenario(browser, "GET / HTTP/1.1\r\nHost: x.i2p\r\n\r\n", false, false, true, waiter,
                    new ThrowingConsumer<Socket>() {
            public void accept(Socket s) throws Throwable {
                readRequest(s.getInputStream());
                s.getOutputStream().write(partial.getBytes(StandardCharsets.UTF_8));
                s.setSoLinger(true, 0);
                s.close();
            }
        }, err);
        assertEquals(1, waiter.get());
        assertTrue("browser should be reset", browser.resetCount >= 1);
    }

    private static void readFully(InputStream in, byte[] out) throws IOException {
        int off = 0;
        while (off < out.length) {
            int n = in.read(out, off, out.length - off);
            if (n < 0) {throw new IOException("EOF reading " + out.length + " bytes, got " + off);}
            off += n;
        }
    }
}
