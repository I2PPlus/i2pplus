package net.i2p.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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
            // the gunzip resume marker tracks _outFile; drop it with the file
            new File(_outFile.getPath() + EepGet.GZIP_PARTIAL_MARKER_SUFFIX).delete();
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
                // claim the full compressed length but send only half, then
                // hang up: the decompressor must fail and be joined
                writeHeaders(out, 200, "Content-Encoding: gzip\r\n", gz.length);
                out.write(gz, 0, gz.length / 2);
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
        // Pin the retry backoff to ~0 so the test doesn't pay the production jitter delay.
        String priorDelay = System.getProperty(EepGet.PROP_RETRY_DELAY);
        System.setProperty(EepGet.PROP_RETRY_DELAY, "0");
        try {
            EepGet get = newFetch(server.url("/flaky"), 2);
            assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
            byte[] stored = readFile(_outFile);
            assertEquals(body.length, stored.length);
            assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
            assertEquals(2, calls[0]);
        } finally {
            if (priorDelay != null)
                System.setProperty(EepGet.PROP_RETRY_DELAY, priorDelay);
            else
                System.clearProperty(EepGet.PROP_RETRY_DELAY);
        }
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
     * A data-phase stall that still made progress must be retried at once
     * instead of after the default backoff, and the retry must resume with a
     * Range header so the transfer completes without a multi-second pause.
     */
    public void testBlockedReadStallRetriesImmediatelyWithRangeResume() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final int partial = 8 * 1024;
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                if (calls[0] == 1) {
                    // serve headers and a partial body, then stall past the
                    // inactivity timeout: the client's read fails with progress
                    writeHeaders(out, 200, "", body.length);
                    out.write(body, 0, partial);
                    out.flush();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                String range = headers.get("range");
                int offset = -1;
                if (range != null && range.startsWith("bytes=")) {
                    try {
                        offset = Integer.parseInt(range.substring(6).replace("-", ""));
                    } catch (NumberFormatException nfe) {
                        offset = -1;
                    }
                }
                if (offset <= 0 || offset >= body.length) {
                    // no usable Range: a full 200 against the partial file
                    // fails the fetch and the assertions below catch it
                    writeResponse(out, 200, body.length, body);
                    return;
                }
                writeResponse(out, 206, body.length - offset, body, offset);
            }
        });
        final long[] failedAt = {0};
        final long[] retriedAt = {0};
        final EepGet get = newFetch(server.url("/stall"), 2);
        get.addStatusListener(new EepGet.StatusListener() {
            @Override
            public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            }

            @Override
            public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            }

            @Override
            public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
                if (failedAt[0] == 0)
                    failedAt[0] = System.currentTimeMillis();
            }

            @Override
            public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            }

            @Override
            public void headerReceived(String url, int currentAttempt, String key, String val) {
            }

            @Override
            public void attempting(String url) {
                if (failedAt[0] != 0 && retriedAt[0] == 0)
                    retriedAt[0] = System.currentTimeMillis();
            }
        });
        // deliberately no PROP_RETRY_DELAY override: the default backoff
        // (5s + jitter) is the baseline the immediate stall retry must beat
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, 400));
        assertEquals(2, calls[0]);
        assertEquals(2, server.getRequestLines().size());
        assertNull(server.getHeadersList().get(0).get("range"));
        String range = server.getHeadersList().get(1).get("range");
        assertNotNull(range);
        assertTrue(range, range.startsWith("bytes="));
        int offset = Integer.parseInt(range.substring(6).replace("-", ""));
        assertTrue(offset > 0);
        assertTrue(offset <= partial);
        assertTrue(failedAt[0] > 0);
        assertTrue(retriedAt[0] > 0);
        // immediate retry: the default backoff alone would be >= 5000ms
        assertTrue("retry gap " + (retriedAt[0] - failedAt[0]) + "ms",
                retriedAt[0] - failedAt[0] < 2000);
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * stopFetching() from attemptFailed must cancel the remaining retries:
     * the fetch loop exits before scheduling another attempt.
     */
    public void testStopFetchingCancelsRetries() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                if (calls[0] == 1) {
                    // empty response forces the retry path
                    return;
                }
                writeResponse(out, 200, body.length, body);
            }
        });
        final EepGet get = newFetch(server.url("/cancel"), 2);
        get.addStatusListener(new EepGet.StatusListener() {
            @Override
            public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            }

            @Override
            public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            }

            @Override
            public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
                get.stopFetching();
            }

            @Override
            public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            }

            @Override
            public void headerReceived(String url, int currentAttempt, String key, String val) {
            }

            @Override
            public void attempting(String url) {
            }
        });
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(1, calls[0]);
        assertEquals(1, server.getRequestLines().size());
    }

    /**
     * A reused EepGet instance must fetch cleanly after a prior fetch failed
     * with a watchdog-aborted stall: the attempt counter advances (so a late
     * timer command from the previous fetch cannot match it and abort this
     * one), the abort flag is reset at the loop top, and the partial file
     * resumes to completion.
     */
    public void testReusedInstanceFetchesCleanlyAfterWatchdogAbort() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final int partial = 8 * 1024;
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                if (calls[0] == 1) {
                    // serve headers and a partial body, then stall past the
                    // inactivity timeout so the watchdog aborts the attempt
                    writeHeaders(out, 200, "", body.length);
                    out.write(body, 0, partial);
                    out.flush();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                String range = headers.get("range");
                int offset = -1;
                if (range != null && range.startsWith("bytes=")) {
                    try {
                        offset = Integer.parseInt(range.substring(6).replace("-", ""));
                    } catch (NumberFormatException nfe) {
                        offset = -1;
                    }
                }
                if (offset <= 0 || offset >= body.length) {
                    writeResponse(out, 200, body.length, body);
                    return;
                }
                writeResponse(out, 206, body.length - offset, body, offset);
            }
        });
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        EepGet get = new EepGet(_context, 0, _outFile.getAbsolutePath(), server.url("/reuse"));
        // fetch #1: no retries, stall past the inactivity watchdog
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, 400));
        assertEquals(1, calls[0]);
        // the stale-timer guard depends on the attempt counter advancing
        assertTrue(get._currentAttempt > 0);
        // fetch #2 on the same instance must not inherit the abort
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(2, calls[0]);
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * An external interrupt while waiting out the retry backoff must stop the
     * fetch: the remaining retries are not burned and the fetch returns false.
     */
    public void testExternalInterruptDuringRetrySleepStopsFetch() throws Exception {
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                // empty response forces the retry path on every attempt
            }
        });
        String priorDelay = System.getProperty(EepGet.PROP_RETRY_DELAY);
        System.setProperty(EepGet.PROP_RETRY_DELAY, "60000");
        final boolean[] ok = {false};
        try {
            _outFile = File.createTempFile("eepget-test", ".out");
            _outFile.deleteOnExit();
            final EepGet get = new EepGet(_context, 5, _outFile.getAbsolutePath(), server.url("/interrupt"));
            Thread fetcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    // no operation-wide deadline here: it would correctly cap
                    // the 60s backoff at the total timeout and break before
                    // the sleep this test needs to interrupt
                    ok[0] = get.fetch(FETCH_TIMEOUT, -1, FETCH_TIMEOUT);
                }
            }, "EepGetInterruptTest");
            fetcher.start();
            // wait until the first attempt failed and the thread is inside the backoff sleep
            long deadline = System.currentTimeMillis() + 15 * 1000;
            while (System.currentTimeMillis() < deadline && !inThreadSleep(fetcher)) {
                Thread.sleep(10);
            }
            assertTrue("fetcher never reached the retry sleep", inThreadSleep(fetcher));
            fetcher.interrupt();
            fetcher.join(10 * 1000);
            assertFalse("fetch did not stop after the interrupt", fetcher.isAlive());
            assertFalse(ok[0]);
            assertEquals(1, calls[0]);
        } finally {
            if (priorDelay != null)
                System.setProperty(EepGet.PROP_RETRY_DELAY, priorDelay);
            else
                System.clearProperty(EepGet.PROP_RETRY_DELAY);
        }
    }

    /**
     * Whether the thread's current stack shows it inside Thread.sleep().
     *
     * @param t thread to inspect
     * @return true if sleeping
     */
    private static boolean inThreadSleep(Thread t) {
        if (!t.isAlive())
            return false;
        for (StackTraceElement e : t.getStackTrace()) {
            if ("java.lang.Thread".equals(e.getClassName()) && "sleep".equals(e.getMethodName()))
                return true;
        }
        return false;
    }

    /**
     * Write a simple response with Content-Length and body.
     */
    private static void writeResponse(OutputStream out, int code, long length, byte[] body) throws IOException {
        writeResponse(out, code, length, body, 0);
    }

    private static void writeResponse(OutputStream out, int code, long length, byte[] body, int offset) throws IOException {
        String extra = "";
        if (code == 206)
            extra = "Content-Range: bytes " + offset + '-' + (offset + length - 1)
                    + '/' + (offset + length) + "\r\n";
        writeHeaders(out, code, extra, length);
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

    // ---------- isStalledButProgressing ----------

    public void testStallWithSocketTimeoutAndProgress() {
        assertTrue(EepGet.isStalledButProgressing(
                new SocketTimeoutException("Read timed out"), false, 100, 200));
    }

    public void testStallWithWatchdogAbortAndProgress() {
        assertTrue(EepGet.isStalledButProgressing(
                new IOException("Timed out reading the HTTP data"), false, 100, 200));
    }

    public void testStallWithWatchdogAbortedSocketCloseAndProgress() {
        // The watchdog closes the socket; the blocked read surfaces a raw
        // SocketException, not a timeout type — the aborted flag is the signal.
        assertTrue(EepGet.isStalledButProgressing(
                new IOException(new SocketException("Socket closed")), true, 100, 200));
    }

    public void testStallWithWrappedSocketTimeoutCauseAndProgress() {
        assertTrue(EepGet.isStalledButProgressing(
                new IOException("read failed", new SocketTimeoutException("Read timed out")),
                false, 100, 200));
    }

    public void testStallWithoutProgress() {
        // header-phase timeout: nothing appended this attempt
        assertFalse(EepGet.isStalledButProgressing(
                new SocketTimeoutException("Read timed out"), false, 100, 100));
    }

    public void testWatchdogAbortWithoutProgressIsNotStall() {
        // watchdog fired during headers: no bytes appended this attempt
        assertFalse(EepGet.isStalledButProgressing(
                new IOException(new SocketException("Socket closed")), true, 100, 100));
    }

    public void testStallWithBackwardsByteCount() {
        assertFalse(EepGet.isStalledButProgressing(
                new SocketTimeoutException("Read timed out"), false, 200, 100));
    }

    public void testNonTimeoutFailureWithProgress() {
        assertFalse(EepGet.isStalledButProgressing(
                new IOException("Connection reset"), false, 100, 200));
    }

    public void testUnclassifiedSocketExceptionWithoutAbortIsNotStall() {
        // a remote reset is not our watchdog: must still take the default backoff
        assertFalse(EepGet.isStalledButProgressing(
                new IOException(new SocketException("Connection reset")), false, 100, 200));
    }

    public void testHeaderTimeoutMessageNotStall() {
        assertFalse(EepGet.isStalledButProgressing(
                new IOException("Timed out reading the HTTP headers"), false, 100, 200));
    }

    public void testBareInterruptedIOExceptionNotClassifiedAsTimeout() {
        // a bare interrupt carries no timeout evidence; only the explicit
        // abort signal may classify it as a watchdog-aborted stall
        assertFalse(EepGet.isStalledButProgressing(
                new InterruptedIOException("Read interrupted"), false, 100, 200));
        assertTrue(EepGet.isStalledButProgressing(
                new InterruptedIOException("Read interrupted"), true, 100, 200));
    }

    public void testTotalBudgetExhaustedBoundaries() {
        // <= 0 disables the total timeout entirely
        assertFalse(EepGet.isTotalBudgetExhausted(-1, 1000, 999999));
        assertFalse(EepGet.isTotalBudgetExhausted(0, 1000, 999999));
        // the deadline itself counts as exhausted
        assertFalse(EepGet.isTotalBudgetExhausted(3000, 1000, 3999));
        assertTrue(EepGet.isTotalBudgetExhausted(3000, 1000, 4000));
        assertTrue(EepGet.isTotalBudgetExhausted(3000, 1000, 4001));
    }

    // ---------- resume validation (FIN-CORE-02/03/04, LS-03) ----------

    /**
     * A Content-Range header parses in the satisfiable, unknown-total, and
     * unsatisfied forms; malformed or out-of-bounds values are rejected.
     */
    public void testParseContentRange() {
        EepGet.ContentRange cr = EepGet.parseContentRange("bytes 0-99/100");
        assertNotNull(cr);
        assertEquals(0, cr.start);
        assertEquals(99, cr.end);
        assertEquals(100, cr.total);

        cr = EepGet.parseContentRange("bytes 1000-4999/5000");
        assertNotNull(cr);
        assertEquals(1000, cr.start);
        assertEquals(4999, cr.end);
        assertEquals(5000, cr.total);

        // an unknown total is allowed for a satisfiable range
        cr = EepGet.parseContentRange("bytes 10-19/*");
        assertNotNull(cr);
        assertEquals(10, cr.start);
        assertEquals(19, cr.end);
        assertEquals(-1, cr.total);

        // the unsatisfied form sent with a 416
        cr = EepGet.parseContentRange("bytes */5000");
        assertNotNull(cr);
        assertEquals(-1, cr.start);
        assertEquals(-1, cr.end);
        assertEquals(5000, cr.total);

        // the unit token is case-insensitive
        assertNotNull(EepGet.parseContentRange("BYTES 0-9/10"));

        assertNull(EepGet.parseContentRange(null));
        assertNull(EepGet.parseContentRange(""));
        assertNull(EepGet.parseContentRange("items 0-9/10"));
        assertNull(EepGet.parseContentRange("bytes 0-9"));
        assertNull(EepGet.parseContentRange("bytes 0-9/"));
        assertNull(EepGet.parseContentRange("bytes -9/10"));
        assertNull(EepGet.parseContentRange("bytes 0-/10"));
        assertNull(EepGet.parseContentRange("bytes 9-0/10"));
        assertNull(EepGet.parseContentRange("bytes 0-10/10"));
        assertNull(EepGet.parseContentRange("bytes a-b/c"));
    }

    /**
     * A 206 must carry a Content-Range whose start matches the resume offset,
     * and a gzip 206 must never append to existing content; anything else
     * throws so the fetch restarts instead of corrupting the output.
     */
    public void testValidateResumeResponse206() throws Exception {
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        EepGet get = new EepGet(_context, 0, _outFile.getAbsolutePath(), "http://127.0.0.1/x");
        get._responseCode = 206;
        get._alreadyTransferred = 1000;
        get._isGzippedResponse = false;

        get._contentRange = "bytes 1000-4999/5000";
        assertFalse(get.validateResumeResponse());

        get._contentRange = "bytes 500-4999/5000";
        try {
            get.validateResumeResponse();
            fail("mismatched Content-Range start must be rejected");
        } catch (IOException expected) {
            // expected
        }

        get._contentRange = null;
        try {
            get.validateResumeResponse();
            fail("missing Content-Range must be rejected");
        } catch (IOException expected) {
            // expected
        }

        get._contentRange = "bytes */5000";
        try {
            get.validateResumeResponse();
            fail("unsatisfied Content-Range on a 206 must be rejected");
        } catch (IOException expected) {
            // expected
        }

        // a gzip 206 cannot append to existing decompressed bytes
        get._contentRange = "bytes 1000-4999/5000";
        get._isGzippedResponse = true;
        try {
            get.validateResumeResponse();
            fail("gzip 206 with a partial file must be rejected");
        } catch (IOException expected) {
            // expected
        }
        get._isGzippedResponse = false;

        // a fresh file resumes from offset zero
        get._alreadyTransferred = 0;
        get._contentRange = "bytes 0-4999/5000";
        assertFalse(get.validateResumeResponse());
    }

    /**
     * A 416 only completes the fetch when Content-Range proves our offset is
     * the whole file; otherwise it fails without retrying the same Range.
     */
    public void testValidateResumeResponse416() throws Exception {
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        EepGet get = new EepGet(_context, 0, _outFile.getAbsolutePath(), "http://127.0.0.1/x");

        // offset equals the total: complete
        get._responseCode = 416;
        get._alreadyTransferred = 5000;
        get._bytesRemaining = 123;
        get._transferFailed = false;
        get._keepFetching = true;
        get._contentRange = "bytes */5000";
        assertTrue(get.validateResumeResponse());
        assertFalse(get._transferFailed);
        assertFalse(get._keepFetching);
        assertEquals(0, get._bytesRemaining);

        // offset short of the total: the partial file is not complete
        get._alreadyTransferred = 1000;
        get._bytesRemaining = 123;
        get._transferFailed = false;
        get._keepFetching = true;
        get._contentRange = "bytes */5000";
        assertTrue(get.validateResumeResponse());
        assertTrue(get._transferFailed);
        assertFalse(get._keepFetching);
        assertEquals(0, get._bytesRemaining);

        // an unknown total cannot prove completeness
        get._contentRange = "bytes */*";
        get._transferFailed = false;
        get._keepFetching = true;
        assertTrue(get.validateResumeResponse());
        assertTrue(get._transferFailed);

        // neither can a missing header
        get._contentRange = null;
        get._transferFailed = false;
        get._keepFetching = true;
        assertTrue(get.validateResumeResponse());
        assertTrue(get._transferFailed);

        // other response codes are untouched
        get._responseCode = 200;
        get._contentRange = null;
        assertFalse(get.validateResumeResponse());
    }

    /**
     * A partial file that already matches the whole resource completes on a
     * 416 whose Content-Range proves the offset, leaving the file intact.
     */
    public void testRangeAlreadyComplete416() throws Exception {
        final byte[] body = pattern(4096);
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        FileOutputStream fo = new FileOutputStream(_outFile);
        fo.write(body);
        fo.close();
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeHeaders(out, 416, "Content-Range: bytes */" + body.length + "\r\n", 0);
            }
        });
        EepGet get = new EepGet(_context, 0, _outFile.getAbsolutePath(), server.url("/alldone"));
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(416, get.getStatusCode());
        assertEquals("bytes=" + body.length + "-", server.getHeadersList().get(0).get("range"));
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * A 416 to an incomplete partial file fails the fetch and leaves the
     * partial content untouched; the identical Range would only get the same
     * answer, so no retry burns the budget.
     */
    public void testRangeNotSatisfiable416Incomplete() throws Exception {
        final byte[] body = pattern(4096);
        final int prefixLen = 1000;
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        FileOutputStream fo = new FileOutputStream(_outFile);
        fo.write(body, 0, prefixLen);
        fo.close();
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeHeaders(out, 416, "Content-Range: bytes */" + body.length + "\r\n", 0);
            }
        });
        EepGet get = new EepGet(_context, 2, _outFile.getAbsolutePath(), server.url("/unsat"));
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(416, get.getStatusCode());
        assertEquals(1, server.getRequestLines().size());
        assertEquals(prefixLen, _outFile.length());
    }

    /**
     * A partial file written by the transparent gunzipper restarts from
     * scratch instead of resuming: the persisted marker keeps its
     * decompressed length out of the Range offset (regression for the gzip
     * resume corruption).
     */
    public void testGzipPartialRestartsNotResumes() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final byte[] gz = gzip(body);
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                if (calls[0] == 1) {
                    // hang up mid-gunzip, leaving decompressed bytes + marker
                    writeHeaders(out, 200, "Content-Encoding: gzip\r\n", gz.length);
                    out.write(gz, 0, Math.max(1, gz.length / 2));
                    out.flush();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                if (headers.get("range") != null) {
                    // resuming the compressed stream would corrupt the file
                    writeResponse(out, 206, gz.length - 1, gz, 1);
                    return;
                }
                writeHeaders(out, 200, "Content-Encoding: gzip\r\n", gz.length);
                out.write(gz);
            }
        });
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        EepGet first = new EepGet(_context, 0, _outFile.getAbsolutePath(), server.url("/gzresume"));
        assertFalse(first.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, 400));
        File marker = new File(_outFile.getPath() + EepGet.GZIP_PARTIAL_MARKER_SUFFIX);
        assertTrue("gzip marker missing after partial gunzip", marker.exists());

        EepGet second = new EepGet(_context, 0, _outFile.getAbsolutePath(), server.url("/gzresume"));
        assertTrue(second.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(2, server.getRequestLines().size());
        assertNull("gzip partial must restart, not resume",
                server.getHeadersList().get(1).get("range"));
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * getResumeOffset() must not report a decompressed file's length as a
     * resume offset while its marker exists, and must clear a stale marker
     * left for a file that is gone.
     */
    public void testGetResumeOffsetGzipMarker() throws Exception {
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        FileOutputStream fo = new FileOutputStream(_outFile);
        fo.write(pattern(512));
        fo.close();
        EepGet get = new EepGet(_context, 0, _outFile.getAbsolutePath(), "http://127.0.0.1/x");
        assertEquals(512, get.getResumeOffset(_outFile));

        File marker = new File(_outFile.getPath() + EepGet.GZIP_PARTIAL_MARKER_SUFFIX);
        marker.deleteOnExit();
        assertTrue(marker.createNewFile());
        assertEquals("marker must force a restart", 0, get.getResumeOffset(_outFile));
        assertTrue(_outFile.exists());

        // a marker for a deleted file is stale; nothing left to protect
        _outFile.delete();
        assertEquals(0, get.getResumeOffset(_outFile));
        assertFalse(marker.exists());
    }

    /**
     * A header-phase timeout against a partial file takes the normal backoff:
     * only body bytes read during the attempt count as progress, never the
     * pre-existing file length the Range offset came from (regression for
     * the stall baseline that skipped the delay and retried at once).
     */
    public void testHeaderTimeoutWithPartialUsesBackoff() throws Exception {
        final byte[] body = pattern(50 * 1024);
        final int prefixLen = 1000;
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                if (calls[0] == 1) {
                    // hold the request open past the header timeout without writing
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                String range = headers.get("range");
                int offset = -1;
                if (range != null && range.startsWith("bytes=")) {
                    try {
                        offset = Integer.parseInt(range.substring(6).replace("-", ""));
                    } catch (NumberFormatException nfe) {
                        offset = -1;
                    }
                }
                if (offset <= 0 || offset >= body.length) {
                    writeResponse(out, 200, body.length, body);
                    return;
                }
                writeResponse(out, 206, body.length - offset, body, offset);
            }
        });
        _outFile = File.createTempFile("eepget-test", ".out");
        _outFile.deleteOnExit();
        FileOutputStream fo = new FileOutputStream(_outFile);
        fo.write(body, 0, prefixLen);
        fo.close();
        final long[] failedAt = {0};
        final long[] retriedAt = {0};
        EepGet get = new EepGet(_context, 1, _outFile.getAbsolutePath(), server.url("/hdrstall"));
        get.addStatusListener(new EepGet.StatusListener() {
            @Override
            public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
            }

            @Override
            public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            }

            @Override
            public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
                if (failedAt[0] == 0)
                    failedAt[0] = System.currentTimeMillis();
            }

            @Override
            public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            }

            @Override
            public void headerReceived(String url, int currentAttempt, String key, String val) {
            }

            @Override
            public void attempting(String url) {
                if (failedAt[0] != 0 && retriedAt[0] == 0)
                    retriedAt[0] = System.currentTimeMillis();
            }
        });
        // deliberately no PROP_RETRY_DELAY override: the default backoff
        // (5s + jitter) is what the regression's immediate retry must lose to
        assertTrue(get.fetch(300, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(2, calls[0]);
        assertTrue(failedAt[0] > 0);
        assertTrue(retriedAt[0] > 0);
        long gap = retriedAt[0] - failedAt[0];
        assertTrue("retry gap " + gap + "ms must be the default backoff, not immediate",
                gap >= 4500);
        byte[] stored = readFile(_outFile);
        assertEquals(body.length, stored.length);
        assertEquals(new String(body, StandardCharsets.ISO_8859_1), new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * The total timeout bounds the whole operation: once the budget is gone,
     * no further backoff sleep or attempt runs (regression for per-attempt
     * totals that let a hung fetch outlive its caller's deadline).
     */
    public void testTotalTimeoutBoundsOperation() throws Exception {
        final byte[] body = pattern(64 * 1024);
        final int partial = 8 * 1024;
        final int[] calls = {0};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                calls[0]++;
                // headers and a partial body, then stall past both timeouts
                writeHeaders(out, 200, "", body.length);
                out.write(body, 0, partial);
                out.flush();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        String priorDelay = System.getProperty(EepGet.PROP_RETRY_DELAY);
        System.setProperty(EepGet.PROP_RETRY_DELAY, "4000");
        try {
            _outFile = File.createTempFile("eepget-test", ".out");
            _outFile.deleteOnExit();
            EepGet get = new EepGet(_context, 2, _outFile.getAbsolutePath(), server.url("/budget"));
            long start = System.currentTimeMillis();
            assertFalse(get.fetch(800, 1000, FETCH_TIMEOUT));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("elapsed " + elapsed + "ms must stay near the 1000ms budget",
                    elapsed < 3500);
            assertEquals("no attempt may start after the deadline", 1, calls[0]);
        } finally {
            if (priorDelay != null)
                System.setProperty(EepGet.PROP_RETRY_DELAY, priorDelay);
            else
                System.clearProperty(EepGet.PROP_RETRY_DELAY);
        }
    }

    /**
     * A fresh fetch answered with 416 (no Range was ever sent) must fail
     * instead of writing the error body as a success.
     */
    public void testFreshFile416Fails() throws Exception {
        final String[] rangeSeen = {null};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                rangeSeen[0] = headers.get("range");
                writeHeaders(out, 416, "Content-Range: bytes */1000\r\n", 0);
            }
        });
        EepGet get = newFetch(server.url("/fresh416"), 0);
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(416, get.getStatusCode());
        assertNull("a fresh fetch must not send a Range", rangeSeen[0]);
        assertEquals("416 is terminal; no retry", 1, server.getRequestLines().size());
        assertEquals(0, _outFile.length());
    }

    /**
     * A caller-supplied Range (webseed piece fetch) gets a 206 whose
     * Content-Range starts at the requested offset, not at a local resume
     * offset; the validator must accept it and the Content-Range/Content-Length
     * span must match.
     */
    public void testCustomRangeHeader206Accepted() throws Exception {
        final byte[] body = pattern(1000);
        final String[] rangeSeen = {null};
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                rangeSeen[0] = headers.get("range");
                writeResponse(out, 206, 600, body, 400);
            }
        });
        EepGet get = newFetch(server.url("/customrange"), 0);
        get.addHeader("Range", "bytes=400-999");
        assertTrue(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals("bytes=400-999", rangeSeen[0]);
        assertEquals(1, server.getRequestLines().size());
        byte[] stored = readFile(_outFile);
        assertEquals(600, stored.length);
        assertEquals(new String(body, 400, 600, StandardCharsets.ISO_8859_1),
                new String(stored, StandardCharsets.ISO_8859_1));
    }

    /**
     * A 206 whose Content-Range start differs from the Range we sent would
     * splice the wrong bytes into the output; reject it before the body.
     */
    public void testCustomRangeHeaderWrongStartRejected() throws Exception {
        final byte[] body = pattern(1000);
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeResponse(out, 206, 600, body, 300);
            }
        });
        EepGet get = newFetch(server.url("/customrangewrong"), 0);
        get.addHeader("Range", "bytes=400-999");
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(1, server.getRequestLines().size());
        assertEquals("body must not be written before validation", 0, _outFile.length());
    }

    /**
     * A 206 whose Content-Range span disagrees with Content-Length is
     * unusable; reject it before the body.
     */
    public void testCustomRange206SpanMismatchRejected() throws Exception {
        TestServer server = startServer(new Handler() {
            @Override
            public void handle(TestServer s, String requestLine, Map<String, String> headers, OutputStream out) throws IOException {
                writeHeaders(out, 206, "Content-Range: bytes 400-999/1000\r\n", 599);
            }
        });
        EepGet get = newFetch(server.url("/customrangespan"), 0);
        get.addHeader("Range", "bytes=400-999");
        assertFalse(get.fetch(FETCH_TIMEOUT, TOTAL_TIMEOUT, FETCH_TIMEOUT));
        assertEquals(1, server.getRequestLines().size());
        assertEquals(0, _outFile.length());
    }

    /**
     * requestedRangeStart(): resume offset wins, caller Range supplies the
     * start, absent Range means zero, and unparseable Range forms are unknown.
     */
    public void testRequestedRangeStart() {
        assertEquals(1000, EepGet.requestedRangeStart(1000, null));
        assertEquals(0, EepGet.requestedRangeStart(0, null));
        assertEquals(400, EepGet.requestedRangeStart(0,
                Collections.singletonList("Range: bytes=400-999")));
        assertEquals(400, EepGet.requestedRangeStart(0,
                Collections.singletonList("range: bytes=400-")));
        assertEquals(-1, EepGet.requestedRangeStart(0,
                Collections.singletonList("Range: bytes=0-99,200-299")));
        assertEquals(-1, EepGet.requestedRangeStart(0,
                Collections.singletonList("Range: bytes=-500")));
        assertEquals(-1, EepGet.requestedRangeStart(0,
                Collections.singletonList("Range: items=1-2")));
        assertEquals(0, EepGet.requestedRangeStart(0,
                Collections.singletonList("User-Agent: x")));
    }

    /**
     * fatalAbort(): an abort landing after the declared body completed must
     * not fail the attempt; a genuine mid-body abort always does.
     */
    public void testFatalAbortDecision() {
        assertFalse(EepGet.fatalAbort(false, true, 0, false));
        assertFalse(EepGet.fatalAbort(false, true, 100, true));
        assertFalse(EepGet.fatalAbort(true, true, 0, false));
        assertFalse(EepGet.fatalAbort(true, false, 0, false));
        assertFalse(EepGet.fatalAbort(true, false, 100, true));
        assertTrue(EepGet.fatalAbort(true, true, 100, false));
        assertTrue(EepGet.fatalAbort(true, true, 100, true));
        assertTrue(EepGet.fatalAbort(true, false, 100, false));
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
