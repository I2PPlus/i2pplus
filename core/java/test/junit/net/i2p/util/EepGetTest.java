package net.i2p.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import junit.framework.TestCase;

import net.i2p.I2PAppContext;

/**
 * Exercise EepGet against a loopback HTTP server: basic, gzip, chunked,
 * redirect, range resume, oversized content-length, and failure paths.
 *
 * @since 0.9.71+
 */
public class EepGetTest extends TestCase {

    private static final int FETCH_TIMEOUT = 15 * 1000;
    private static final int TOTAL_TIMEOUT = 30 * 1000;

    private I2PAppContext _context;
    private TestServer _server;
    private File _outFile;

    @Override
    protected void setUp() {
        _context = I2PAppContext.getGlobalContext();
    }

    @Override
    protected void tearDown() throws IOException {
        if (_server != null) {
            _server.stop();
            _server = null;
        }
        if (_outFile != null) {
            _outFile.delete();
            _outFile = null;
        }
    }

    /**
     * Generate a deterministic byte pattern.
     */
    private static byte[] pattern(int size) {
        byte[] rv = new byte[size];
        for (int i = 0; i < size; i++) {
            rv[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        return rv;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(data.length / 2);
        GZIPOutputStream gz = new GZIPOutputStream(baos);
        gz.write(data);
        gz.close();
        return baos.toByteArray();
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] rv = new byte[(int) f.length()];
        InputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < rv.length) {
                int read = in.read(rv, off, rv.length - off);
                if (read < 0) {
                    break;
                }
                off += read;
            }
            return rv;
        } finally {
            in.close();
        }
    }

    /**
     * Create an EepGet (no retries) writing to a fresh temp file. The caller
     * must call fetch().
     */
    private EepGet newFetch(String url, int numRetries) {
        try {
            _outFile = File.createTempFile("eepget-test", ".out");
        } catch (IOException ioe) {
            fail("Cannot create temp file: " + ioe);
        }
        _outFile.deleteOnExit();
        return new EepGet(_context, numRetries, _outFile.getAbsolutePath(), url);
    }

    /**
     * Start a server with the given per-request handler.
     */
    private TestServer startServer(Handler handler) throws IOException {
        TestServer server = new TestServer(handler);
        _server = server;
        new Thread(server, "EepTestSrv").start();
        return server;
    }

    public void testBasicFetch() throws Exception {
        final byte[] body = pattern(200 * 1024);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeResponse(out, 200, body.length, body);
            }
        });
        EepGet get = newFetch(server.url("/basic"), 0);
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(200, get.getStatusCode());
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * Content-Encoding: gzip with a plain URL is transparently decompressed.
     */
    public void testGzipTransparent() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final byte[] gz = gzip(body);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeHeaders(out, 200, "Content-Encoding: gzip\r\n", gz.length);
                out.write(gz);
            }
        });
        EepGet get = newFetch(server.url("/data"), 0);
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * A .gz URL is stored raw, not decompressed.
     */
    public void testGzipStoredRaw() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final byte[] gz = gzip(body);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeHeaders(out, 200, "Content-Encoding: gzip\r\n", gz.length);
                out.write(gz);
            }
        });
        EepGet get = newFetch(server.url("/file.gz"), 0);
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        byte[] stored = readFile(_outFile);
        assertEquals(gz.length, stored.length);
        assertEquals(new String(gz, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * Transfer-Encoding: chunked without a Content-Length.
     */
    public void testChunked() throws Exception {
        final byte[] body = pattern(10 * 1024);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                int off = 0;
                while (off < body.length) {
                    int chunk = Math.min(1000, body.length - off);
                    out.write((Integer.toHexString(chunk) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body, off, chunk);
                    out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    off += chunk;
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            }
        });
        EepGet get = newFetch(server.url("/chunked"), 0);
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * A 301 redirect is followed.
     */
    public void testRedirect() throws Exception {
        final byte[] body = pattern(1000);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                if (requestLine.startsWith("GET /start")) {
                    out.write(("HTTP/1.1 301 Moved Permanently\r\nLocation: " + s.url("/final")
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    writeResponse(out, 200, body.length, body);
                }
            }
        });
        EepGet get = newFetch(server.url("/start"), 0);
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(2, server.getRequestLines().size());
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * More than 5 redirects fails the fetch.
     */
    public void testTooManyRedirects() throws Exception {
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                out.write(("HTTP/1.1 301 Moved Permanently\r\nLocation: " + s.url("/loop")
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
        });
        EepGet get = newFetch(server.url("/loop"), 0);
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(6, server.getRequestLines().size());
    }

    /**
     * A pre-existing partial file resumes with a Range request and a 206 response.
     */
    public void testRangeResume() throws Exception {
        final byte[] body = pattern(50 * 1024);
        final int prefixLen = 1000;
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        FileOutputStream fo = new FileOutputStream(_outFile);
        fo.write(body, 0, prefixLen);
        fo.close();

        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                String range = headers.get("range");
                if (range != null) {
                    writeResponse(out, 206, body.length - prefixLen, body, prefixLen);
                } else {
                    writeResponse(out, 200, body.length, body);
                }
            }
        });
        EepGet get = new EepGet(_context, 0, _outFile.getAbsolutePath(), server.url("/resume"));
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals("bytes=" + prefixLen + "-", server.getHeadersList().get(0).get("range"));
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * A Range resume must not be requested after a transparently-gunzipped response,
     * because the stored bytes are decompressed, not raw (regression for the gzip resume).
     */
    public void testNoRangeAfterGzip() throws Exception {
        TestEepGet get = new TestEepGet(_context);
        String req = get.requestWith(1000, false);
        assertTrue(req.contains("Range: bytes=1000-"));
        req = get.requestWith(1000, true);
        assertFalse(req.contains("Range:"));
        req = get.requestWith(0, true);
        assertFalse(req.contains("Range:"));
    }

    /**
     * A Content-Length over 2 GiB must not wrap the read counter; the served
     * bytes are still stored (regression for the int truncation).
     */
    public void testHugeContentLength() throws Exception {
        final int served = 100 * 1024;
        final long huge = 2147483648L; // 2 GiB
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeHeaders(out, 200, "", huge);
                out.write(pattern(served));
            }
        });
        EepGet get = newFetch(server.url("/huge"), 0);
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(served, _outFile.length());
    }

    /**
     * A gzip response that disconnects mid-body must fail cleanly without hanging
     * the decompressor thread or leaking the output file (regression for the
     * failure-path pipe close and join).
     */
    public void testTruncatedGzip() throws Exception {
        final byte[] gz = gzip(pattern(64 * 1024));
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                // claim a much larger body than we send, then hang up
                writeHeaders(out, 200, "Content-Encoding: gzip\r\n", gz.length * 10);
                out.write(gz);
            }
        });
        EepGet get = newFetch(server.url("/trunc"), 0);
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
    }

    /**
     * A 404 must not write the error body to the file.
     */
    public void testErrorStatus() throws Exception {
        final byte[] errBody = pattern(500);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeResponse(out, 404, errBody.length, errBody);
            }
        });
        EepGet get = newFetch(server.url("/missing"), 0);
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(404, get.getStatusCode());
        assertEquals(0, _outFile.length());
    }

    /**
     * An empty upstream response (server accepts then closes without writing any
     * body) must be retried when retries are configured, and the retry must
     * succeed once the server serves data. This is the client-side counterpart of
     * the "empty response" ("NS_ERROR_NET_EMPTY_RESPONSE") fix: a flaky or
     * unresponsive source must not be declared dead on the first zero-byte reply.
     */
    public void testRetriesAfterEmptyResponse() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                if (calls[0] == 1) {
                    // accept and hang up with no bytes: an empty response
                    return;
                }
                writeResponse(out, 200, body.length, body);
            }
        });
        // retries=2 -> up to 3 attempts; first attempt returns empty, the retry succeeds.
        EepGet get = newFetch(server.url("/flaky"), 2);
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
        assertEquals(2, calls[0]);
    }

    /**
     * The default PartialEepGet (the version/header check used by the update
     * machinery) must wire a non-zero retry count, so a transient empty response
     * does not make the version check fail immediately. Zero is only used when
     * explicitly requested via the retry-taking constructor.
     */
    public void testPartialEepGetDefaultRetries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TestPartialEepGet get = new TestPartialEepGet(_context, null, 0, baos, "http://127.0.0.1/x", 56);
        assertEquals(EepGet.DEFAULT_NUM_RETRIES, get.getRetries());
    }

    /**
     * The retry-taking PartialEepGet constructor must honor an explicit retry
     * count, including zero (no retries), preserving the previous behavior for
     * callers that want a single attempt.
     */
    public void testPartialEepGetExplicitRetries() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TestPartialEepGet none = new TestPartialEepGet(_context, null, 0, baos, "http://127.0.0.1/x", 56, 0);
        assertEquals(0, none.getRetries());
        TestPartialEepGet three = new TestPartialEepGet(_context, null, 0, baos, "http://127.0.0.1/x", 56, 3);
        assertEquals(3, three.getRetries());
    }

    /**
     * Write a simple response with Content-Length and body.
     */
    private static void writeResponse(OutputStream out, int code, long length, byte[] body) throws IOException {
        writeResponse(out, code, length, body, 0);
    }

    private static void writeResponse(OutputStream out, int code, long length, byte[] body, int offset) throws IOException {
        writeHeaders(out, code, "", length);
        if (body != null && length > 0) {
            out.write(body, offset, (int) length);
        }
    }

    private static void writeHeaders(OutputStream out, int code, String extra, long length) throws IOException {
        String reason = code == 200 ? "OK" : (code == 206 ? "Partial Content" : (code == 404 ? "Not Found" : "Status"));
        out.write(("HTTP/1.1 " + code + ' ' + reason + "\r\n" + extra
                + "Content-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * A loopback HTTP server that serves a single handler per request.
     */
    private static class TestServer implements Runnable {
        private final ServerSocket _ss;
        private final Handler _handler;
        private final List<String> _requestLines = Collections.synchronizedList(new ArrayList<String>());
        private final List<Map<String, String>> _headersList = Collections.synchronizedList(new ArrayList<Map<String, String>>());
        private volatile boolean _running = true;

        TestServer(Handler handler) throws IOException {
            _handler = handler;
            _ss = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        }

        int getPort() {
            return _ss.getLocalPort();
        }

        String url(String path) {
            return "http://127.0.0.1:" + getPort() + path;
        }

        List<String> getRequestLines() {
            return _requestLines;
        }

        List<Map<String, String>> getHeadersList() {
            return _headersList;
        }

        @Override
        public void run() {
            while (_running) {
                Socket s = null;
                try {
                    s = _ss.accept();
                    s.setSoTimeout(30 * 1000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line = in.readLine();
                    if (line == null) {
                        continue;
                    }
                    _requestLines.add(line);
                    Map<String, String> headers = new HashMap<String, String>();
                    String h;
                    while ((h = in.readLine()) != null && h.length() > 0) {
                        int idx = h.indexOf(':');
                        if (idx > 0) {
                            headers.put(h.substring(0, idx).trim().toLowerCase(Locale.US), h.substring(idx + 1).trim());
                        }
                    }
                    _headersList.add(headers);
                    OutputStream out = s.getOutputStream();
                    if (_handler != null) {
                        _handler.handle(this, line, headers, out);
                    }
                    out.flush();
                } catch (IOException ioe) {
                    if (_running) {
                        // connection aborted by the client; keep serving
                    }
                } finally {
                    if (s != null) {
                        try {
                            s.close();
                        } catch (IOException ioe) { /* ignored */ }
                    }
                }
            }
        }

        void stop() {
            _running = false;
            try {
                _ss.close();
            } catch (IOException ioe) { /* ignored */ }
        }
    }

    private interface Handler {
        void handle(TestServer server, String requestLine, Map<String, String> headers, OutputStream out) throws IOException;
    }

    /**
     * Whitebox access to the protected request builder, to verify the Range
     * resume gating without paying the retry backoff.
     */
    private static class TestEepGet extends EepGet {
        TestEepGet(I2PAppContext ctx) {
            super(ctx, 0, (String) null, "http://127.0.0.1/test");
        }

        String requestWith(long alreadyTransferred, boolean gzipped) throws IOException {
            _alreadyTransferred = alreadyTransferred;
            _isGzippedResponse = gzipped;
            return getRequest();
        }
    }

    /**
     * Whitebox access to the Protected retry count of a PartialEepGet, so the
     * update version-check retry wiring can be asserted without a live server.
     */
    private static class TestPartialEepGet extends PartialEepGet {
        TestPartialEepGet(I2PAppContext ctx, String proxyHost, int proxyPort, ByteArrayOutputStream out, String url, long size) {
            super(ctx, proxyHost, proxyPort, out, url, size);
        }

        TestPartialEepGet(I2PAppContext ctx, String proxyHost, int proxyPort, ByteArrayOutputStream out, String url, long size, int retries) {
            super(ctx, proxyHost, proxyPort, out, url, size, retries);
        }

        int getRetries() {
            return _numRetries;
        }
    }
}
