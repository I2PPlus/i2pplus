package net.i2p.i2ptunnel;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2005 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.i2ptunnel.util.*;
import net.i2p.i2ptunnel.util.LimitOutputStream.DoneCallback;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * This does the transparent gzip decompression on the client side.
 * Extended in I2PTunnelHTTPServer to do the compression on the server side.
 *
 * Simple stream for delivering an HTTP response to
 * the client, trivially filtered to make sure "Connection: close"
 * is always in the response.  Perhaps add transparent handling of the
 * Content-Encoding: x-i2p-gzip, adjusting the headers to say Content-Encoding: identity?
 * Content-Encoding: gzip is trivial as well, but Transfer-Encoding: chunked makes it
 * more work than is worthwhile at the moment.
 *
 */
class HTTPResponseOutputStream extends FilterOutputStream {
    private final Log _log;
    protected ByteArray _headerBuffer;
    private boolean _headerWritten;
    private final byte _buf1[];
    protected boolean _gzip;
    protected long _dataExpected = -1;
    protected boolean _keepAliveIn, _keepAliveOut;
    /** lower-case, trimmed */
    protected String _contentType;
    /** lower-case, trimmed */
    protected String _contentEncoding;
    private final DoneCallback _callback;

    private static final int CACHE_SIZE = 16*1024;
    private static final ByteCache _cache = ByteCache.getInstance(8, CACHE_SIZE);
    // OOM DOS prevention
    private static final int MAX_HEADER_SIZE = 64*1024;
    /** we ignore any potential \r, since we trim it on write anyway */
    private static final byte NL = '\n';
    private static final byte[] CONNECTION_CLOSE = DataHelper.getASCII("Connection: close\r\n");
    private static final byte[] CRLF = DataHelper.getASCII("\r\n");

    public HTTPResponseOutputStream(OutputStream raw) {
        this(raw, null);
    }

    /**
     * Optionally call callback when we're done.
     *
     * @param cb may be null
     * @since 0.9.62
     */
    private HTTPResponseOutputStream(OutputStream raw, DoneCallback cb) {
        super(raw);
        I2PAppContext context = I2PAppContext.getGlobalContext();
        _log = context.logManager().getLog(HTTPResponseOutputStream.class);
        _headerBuffer = _cache.acquire();
        _buf1 = new byte[1];
        _callback = cb;
    }

    /**
     * Optionally keep sockets alive and call callback when we're done.
     *
     * @param allowKeepAliveIn We may, but are not required to, keep the input socket alive.
     *                         This is the server on the server side and I2P on the client side.
     * @param allowKeepAliveOut We may, but are not required to, keep the output socket alive.
     *                          This is I2P on the server side and the browser on the client side.
     * @param isHead is this a response to a HEAD, and thus no data is expected (RFC 2616 sec. 4.4)
     * @param cb non-null if allowKeepAlive is true
     * @since 0.9.62
     */
    public HTTPResponseOutputStream(OutputStream raw, boolean allowKeepAliveIn, boolean allowKeepAliveOut,
                                    boolean isHead, DoneCallback cb) {
        this(raw, cb);
        _keepAliveIn = allowKeepAliveIn;
        _keepAliveOut = allowKeepAliveOut;
        if (isHead)
            _dataExpected = 0;
        if (_log.shouldInfo())
            _log.info("Before headers: keepaliveIn? " + allowKeepAliveIn + " keepaliveOut? " + allowKeepAliveOut);
    }

    /**
     * Should we keep the input stream alive when done?
     *
     * @return false before the headers are written
     * @since 0.9.62
     */
    public boolean getKeepAliveIn() {
        return _keepAliveIn && _headerWritten;
    }

    /**
     * Should we keep the output stream alive when done?
     * Only supported for the browser socket side.
     * I2P socket on server side not supported yet.
     *
     * @return false before the headers are written
     * @since 0.9.62
     */
    public boolean getKeepAliveOut() {
        return _keepAliveOut && _headerWritten;
    }

    @Override
    public void write(int c) throws IOException {
        _buf1[0] = (byte)c;
        write(_buf1, 0, 1);
    }

    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        if (_headerWritten) {
            out.write(buf, off, len);
            return;
        }

        for (int i = 0; i < len; i++) {
            ensureCapacity();
            int valid = _headerBuffer.getValid();
            _headerBuffer.getData()[valid] = buf[off+i];
            _headerBuffer.setValid(valid + 1);

            if (headerReceived()) {
                writeHeader();
                _headerWritten = true;
                if (i + 1 < len) {
                    // write out the remaining
                    out.write(buf, off+i+1, len-i-1);
                }
                return;
            }
        }
    }

    /**
     *  grow (and free) the buffer as necessary
     *  @throws IOException if the headers are too big
     */
    private void ensureCapacity() throws IOException {
        int valid = _headerBuffer.getValid();
        if (valid >= MAX_HEADER_SIZE)
            throw new IOException("Max header size (" + MAX_HEADER_SIZE + "B) exceeded -> " + valid + "B size header detected");
        byte[] data = _headerBuffer.getData();
        int len = data.length;
        if (valid + 1 >= len) {
            int newSize = len * 2;
            ByteArray newBuf = new ByteArray(new byte[newSize]);
            System.arraycopy(data, 0, newBuf.getData(), 0, valid);
            newBuf.setValid(valid);
            newBuf.setOffset(0);
            // if we changed the ByteArray size, don't put it back in the cache
            if (len == CACHE_SIZE)
                _cache.release(_headerBuffer);
            _headerBuffer = newBuf;
        }
    }

    /** are the headers finished? */
    private boolean headerReceived() {
        int valid = _headerBuffer.getValid();
        if (valid < 3)
            return false;
        byte[] data = _headerBuffer.getData();
        byte third = data[valid - 1];
        if (third != NL)
            return false;
        byte first = data[valid - 3];
        if (first == NL)     // \n\r\n
            return true;
        byte second = data[valid - 2];
        return second == NL; //   \n\n
    }

    /**
     * Possibly tweak that first HTTP response line (HTTP/1.0 200 OK, etc).
     * Overridden on server side.
     *
     */
    protected String filterResponseLine(String line) {
        return line;
    }

    /** ok, received, now munge & write it */
    private void writeHeader() throws IOException {
        boolean connectionSent = false;
        boolean chunked = false;

        int lastEnd = -1;
        byte[] data = _headerBuffer.getData();
        int valid = _headerBuffer.getValid();
        for (int i = 0; i < valid; i++) {
            if (data[i] == NL) {
                if (lastEnd == -1) {
                    String responseLine = DataHelper.getUTF8(data, 0, i+1); // includes NL
                    responseLine = (responseLine.trim() + "\r\n");
                    if (_log.shouldInfo())
                        _log.info("Response: " + responseLine.trim());
                    // Persistent conn requires HTTP/1.1
                    if (!responseLine.startsWith("HTTP/1.1 ")) {
                        _keepAliveIn = false;
                        _keepAliveOut = false;
                    }
                    // force zero datalen for 1xx, 204, 304 (RFC 2616 sec. 4.4)
                    // so that these don't prevent keepalive
                    int sp = responseLine.indexOf(" ");
                    if (sp > 0) {
                        String s = responseLine.substring(sp + 1);
                        if (s.startsWith("1") || s.startsWith("204") || s.startsWith("304"))
                            _dataExpected = 0;
                    } else {
                        // no status?
                        _keepAliveIn = false;
                        _keepAliveOut = false;
                    }

                    out.write(DataHelper.getUTF8(responseLine));
                } else {
                    for (int j = lastEnd+1; j < i; j++) {
                        if (data[j] == ':') {
                            int keyLen = j-(lastEnd+1);
                            int valLen = i-(j+1);
                            if ( (keyLen <= 0) || (valLen < 0) )
                                throw new IOException("Invalid header @ " + j);
                            String key = DataHelper.getUTF8(data, lastEnd+1, keyLen);
                            String val;
                            if (valLen == 0)
                                val = "";
                            else
                                val = DataHelper.getUTF8(data, j+2, valLen).trim();

                            if (_log.shouldInfo()) {
                                _log.info("Response header: [" + key + ": " + val + "]");
                            }

                            String lcKey = key.toLowerCase(Locale.US);
                            if ("connection".equals(lcKey)) {
                                if (val.toLowerCase(Locale.US).contains("upgrade")) {
                                    // pass through for websocket
                                    out.write(DataHelper.getASCII("Connection: " + val + "\r\n"));
                                    // Disable persistence
                                    _keepAliveOut = false;
                                } else {
                                    // Strip to allow persistence, replace to disallow
                                    if (!_keepAliveOut)
                                    out.write(CONNECTION_CLOSE);
                                }
                                // We do not expect Connection: keep-alive here,
                                // as it's the default for HTTP/1.1, the server proxy doesn't support it,
                                // and we don't support keepalive for HTTP/1.0
                                _keepAliveIn = false;
                                connectionSent = true;
                            } else if ("proxy-connection".equals(lcKey)) {
                                // Nonstandard, strip
                            } else if ("content-encoding".equals(lcKey) && "x-i2p-gzip".equals(val.toLowerCase(Locale.US))) {
                                _gzip = true;
                                // client side only
                                // x-i2p-gzip is not chunked, which is nonstandard, but we track the
                                // end of data in GunzipOutputStream and call the callback,
                                // so we can support i2p-side keepalive here.
                            } else if ("proxy-authenticate".equals(lcKey)) {
                                // filter this hop-by-hop header; outproxy authentication must be configured in I2PTunnelHTTPClient
                                // see e.g. http://blog.c22.cc/2013/03/11/privoxy-proxy-authentication-credential-exposure-cve-2013-2503/
                            } else {
                                if ("content-length".equals(lcKey)) {
                                    // save for compress decision on server side
                                    try {
                                        _dataExpected = Long.parseLong(val);
                                    } catch (NumberFormatException nfe) {}
                                } else if ("content-type".equals(lcKey)) {
                                    // save for compress decision on server side
                                    _contentType = val.toLowerCase(Locale.US);
                                } else if ("content-encoding".equals(lcKey)) {
                                    // save for compress decision on server side
                                    _contentEncoding = val.toLowerCase(Locale.US);
                                } else if ("transfer-encoding".equals(lcKey) && val.toLowerCase(Locale.US).contains("chunked")) {
                                    // save for keepalive decision on client side
                                    chunked = true;
                                } else if ("set-cookie".equals(lcKey)) {
                                    String lcVal = val.toLowerCase(Locale.US);
                                    if (lcVal.contains("domain=b32.i2p") ||
                                        lcVal.contains("domain=.b32.i2p") ||
                                        lcVal.contains("domain=i2p") ||
                                        lcVal.contains("domain=.i2p")) {
                                        // Strip privacy-damaging "supercookies" for i2p and b32.i2p
                                        // See RFC 6265 and http://publicsuffix.org/
                                        if (_log.shouldInfo())
                                            _log.info("Stripping \"" + key + ": " + val + "\" from response ");
                                        break;
                                    }
                                }
                                out.write(DataHelper.getUTF8(key.trim() + ": " + val + "\r\n"));
                            }
                            break;
                        }
                    }
                }
                lastEnd = i;
            }
        }

        // Now make the final keepalive decisions
        if (_keepAliveOut) {
            // we need one but not both
            if ((chunked && _dataExpected >= 0) ||
                (!chunked && _dataExpected < 0))
                _keepAliveOut = false;
        }
        if (_keepAliveIn) {
            // we need one but not both
            if ((chunked && _dataExpected >= 0) ||
                (!chunked && _dataExpected < 0))
                _keepAliveIn = false;
        }
        
        if (!connectionSent && !_keepAliveOut)
            out.write(CONNECTION_CLOSE);

        finishHeaders();

        boolean shouldCompress = shouldCompress();
        if (_log.shouldInfo())
            _log.info("After headers: GZIP? " + _gzip + " Compressed? " + shouldCompress + " KeepAliveIn? " + _keepAliveIn + " KeepAliveOut? " + _keepAliveOut);

        if (data.length == CACHE_SIZE)
            _cache.release(_headerBuffer);
        _headerBuffer = null;

        // Setup the keepalive streams
        // Until we have keepalive for the i2p socket, the client side
        // does not need to do this, we just wait for the socket to close.
        // Until we have keepalive for the server socket, the server side
        // does not need to do this, we just wait for the socket to close.
        if (_keepAliveIn && !shouldCompress) {
            if (_dataExpected > 0) {
                // content-length
                // filter output stream to count the data
                out = new ByteLimitOutputStream(out, _callback, _dataExpected);
            } else if (_dataExpected == 0) {
                if (_callback != null)
                    _callback.streamDone();
            } else {
                // -1, chunked
                // filter output stream to look for the end
                // do not strip the chunking; pass it through
                out = new DechunkedOutputStream(out, _callback, false);
            }
        }

        if (shouldCompress) {
            beginProcessing();
        }
    }

    protected boolean shouldCompress() { return _gzip; }

    protected void finishHeaders() throws IOException {
        out.write(CRLF); // end of the headers
    }

    @Override
    public void close() throws IOException {
        if (_log.shouldInfo())
            _log.info("Closing " + out + " -> Compressed? " + shouldCompress() +
                      " KeepAliveIn? " + _keepAliveIn + " KeepAliveOut? " + _keepAliveOut);
        synchronized(this) {
            // synch with changing out field below
            super.close();
        }
    }

    protected void beginProcessing() throws IOException {
        OutputStream po = new GunzipOutputStream(out, _callback);
        synchronized(this) {
            out = po;
        }
    }

/*******
    public static void main(String args[]) {
        String simple   = "HTTP/1.1 200 OK\n" +
                          "foo: bar\n" +
                          "baz: bat\n" +
                          "\n" +
                          "hi ho, this is the body";
        String filtered = "HTTP/1.1 200 OK\n" +
                          "Connection: keep-alive\n" +
                          "foo: bar\n" +
                          "baz: bat\n" +
                          "\n" +
                          "hi ho, this is the body";
        String winfilter= "HTTP/1.1 200 OK\r\n" +
                          "Connection: keep-alive\r\n" +
                          "foo: bar\r\n" +
                          "baz: bat\r\n" +
                          "\r\n" +
                          "hi ho, this is the body";
        String minimal  = "HTTP/1.1 200 OK\n" +
                          "\n" +
                          "hi ho, this is the body";
        String winmin   = "HTTP/1.1 200 OK\r\n" +
                          "\r\n" +
                          "hi ho, this is the body";
        String invalid1 = "HTTP/1.1 200 OK\n";
        String invalid2 = "HTTP/1.1 200 OK";
        String invalid3 = "HTTP 200 OK\r\n";
        String invalid4 = "HTTP 200 OK\r";
        String invalid5 = "HTTP/1.1 200 OK\r\n" +
                          "I am broken, and I smell\r\n" +
                          "\r\n";
        String invalid6 = "HTTP/1.1 200 OK\r\n" +
                          ":I am broken, and I smell\r\n" +
                          "\r\n";
        String invalid7 = "HTTP/1.1 200 OK\n" +
                          "I am broken, and I smell:\n" +
                          ":asdf\n" +
                          ":\n" +
                          "\n";
        String large    = "HTTP/1.1 200 OK\n" +
                          "Last-modified: Tue, 25 Nov 2003 12:05:38 GMT\n" +
                          "Expires: Tue, 25 Nov 2003 12:05:38 GMT\n" +
                          "Content-length: 32\n" +
                          "\n" +
                          "hi ho, this is the body";
        String blankval = "HTTP/1.0 200 OK\n" +
                          "A:\n" +
                          "\n";

        test("Simple", simple, true);
        test("Filtered", filtered, true);
        test("Filtered windows", winfilter, true);
        test("Minimal", minimal, true);
        test("Windows", winmin, true);
        test("Large", large, true);
        test("Blank whitespace", blankval, true);
        test("Invalid (short headers)", invalid1, true);
        test("Invalid (no headers)", invalid2, true);
        test("Invalid (windows with short headers)", invalid3, true);
        test("Invalid (windows no headers)", invalid4, true);
        test("Invalid (bad headers)", invalid5, true);
        test("Invalid (bad headers2)", invalid6, false);
        test("Invalid (bad headers3)", invalid7, false);
    }

    private static void test(String name, String orig, boolean shouldPass) {
        System.out.println("====Testing: " + name + "\n" + orig + "\n------------");
        try {
            OutputStream baos = new java.io.ByteArrayOutputStream(4096);
            HTTPResponseOutputStream resp = new HTTPResponseOutputStream(baos);
            resp.write(orig.getBytes());
            resp.flush();
            String received = new String(baos.toByteArray());
            System.out.println(received);
        } catch (Exception e) {
            if (shouldPass)
                e.printStackTrace();
            else
                System.out.println("Properly fails with " + e.getMessage());
        }
    }
******/
}
