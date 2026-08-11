package net.i2p.util;

import gnu.getopt.Getopt;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;

/**
 * This is a quick hack to get a working EepHead, primarily for the following usage:
 * <pre>
 * EepHead foo = new EepHead(...);
 * if (foo.fetch()) {
 *     String lastmod = foo.getLastModified();
 *     if (lastmod != null) {
 *         parse the string...
 *         ...
 *     }
 * }
 * </pre>
 * Other use cases (command line, listeners, etc...) lightly- or un-tested.
 * Note that this follows redirects! This may not be what you want or expect.
 *
 * Writing from scratch rather than extending EepGet would maybe have been less bloated memory-wise.
 * This way gets us redirect handling, among other benefits.
 *
 * @author zzz
 * @since 0.7.7
 */
public class EepHead extends EepGet {
    /** EepGet needs either a non-null file or a stream... shouldn't actually be written to... */
    private static final OutputStream _dummyStream = new OutputStream() {
        @Override
        public void write(int b) {}
        @Override
        public void write(byte[] b, int off, int len) {}
    };

    /**
     * Create a new EepHead to fetch HTTP response headers from the given URL.
     *
     * @param ctx         I2P app context
     * @param proxyHost   proxy hostname
     * @param proxyPort   proxy port
     * @param numRetries  number of retries on failure
     * @param url         target URL
     */
    public EepHead(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String url) {
        super(ctx, true, proxyHost, proxyPort, numRetries, -1, -1, null, _dummyStream, url, true, null, null);
    }

    /**
     * CLI entry point: fetch and display HTTP response headers for a URL.
     * Usage: EepHead [-p 127.0.0.1:4444] [-n #retries] url
     */
    public static void main(String[] args) {
        String proxyHost = "127.0.0.1";
        int proxyPort = 4444;
        int numRetries = 1;
        int inactivityTimeout = 10 * 1000;
        String username = null;
        String password = null;
        boolean error = false;
        Getopt g = new Getopt("eephead", args, "p:cn:t:u:x:");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
                switch (c) {
                    case 'p':
                        String s = g.getOptarg();
                        int colon = s.indexOf(':');
                        if (colon >= 0) {
                            proxyHost = s.substring(0, colon);
                            String port = s.substring(colon + 1);
                            proxyPort = Integer.parseInt(port);
                        } else {
                            proxyHost = s;
                        }
                        break;

                    case 'c':
                        // no proxy, same as -p :0
                        proxyHost = "";
                        proxyPort = 0;
                        break;

                    case 'n':
                        numRetries = Integer.parseInt(g.getOptarg());
                        break;

                    case 't':
                        inactivityTimeout = 1000 * Integer.parseInt(g.getOptarg());
                        break;

                    case 'u':
                        username = g.getOptarg();
                        break;

                    case 'x':
                        password = g.getOptarg();
                        break;

                    case '?':
                    case ':':
                    default:
                        error = true;
                        break;
                } // switch
            } // while
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }

        if (error || args.length - g.getOptind() != 1) {
            System.out.println(usage());
            System.exit(1);
        }
        String url = args[g.getOptind()];

        EepHead get = new EepHead(I2PAppContext.getGlobalContext(), proxyHost, proxyPort, numRetries, url);
        if (username != null) {
            if (password == null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    do {
                        System.err.print("Proxy password: ");
                        password = r.readLine();
                        if (password == null) throw new IOException();
                        password = password.trim();
                    } while (password.isEmpty());
                } catch (IOException ioe) {
                    System.exit(1);
                }
            }
            get.addAuthorization(username, password);
        }

        boolean fetched = get.fetch((long) 10 * 1000, -1, inactivityTimeout);
        if (!fetched && get.getStatusCode() < 0) {
            System.out.println(" ✖ No response from: " + url);
            System.exit(1);
        }
        printResponse(get, url);
    }

    /**
     * Print all available response headers.
     *
     * @param get  the EepHead instance after fetch
     * @param url  the original request URL
     */
    private static void printResponse(EepHead get, String url) {
        System.out.println(" • URL: " + url);
        String server = get.getServer();
        String cc = get.getCacheControl();
        String ar = get.getAcceptRanges();
        String lm = get.getLastModified();
        String et = get.getEtag();
        long contentLength = get.getContentLength();
        String cl = contentLength >= 0 ? String.valueOf(contentLength) : "n/a";
        String xf = get.getXframeOptions();
        String cto = get.getXContentTypeOptions();
        String st = get.getStatus();
        System.out.println(" • Server: " + guessServer(server, cc, cl, lm, ar, et, xf, cto));
        String xpb = get.getXPoweredBy();
        if (xpb != null) System.out.println(" • X-Powered-By: " + xpb);
        if (st != null) System.out.println(" • Status: " + st);
        String ct = get.getContentType();
        if (ct != null) System.out.println(" • Content-Type: " + ct);
        System.out.println(" • Content-Length: " + cl);
        String te = get.getTransferEncoding();
        if (te != null) System.out.println(" • Transfer-Encoding: " + te);
        String ce = get.getContentEncoding();
        if (ce != null) System.out.println(" • Content-Encoding: " + ce);
        String clang = get.getContentLanguage();
        if (clang != null && !clang.isEmpty()) System.out.println(" • Content-Language: " + clang);
        if (lm != null) System.out.println(" • Last-Modified: " + lm);
        if (et != null) System.out.println(" • Etag: " + et);
        if (cc != null) System.out.println(" • Cache-Control: " + cc);
        if (ar != null) System.out.println(" • Accept-Ranges: " + ar);
        String vary = get.getVary();
        if (vary != null) System.out.println(" • Vary: " + vary);
        String exp = get.getExpiryDate();
        if (exp != null) System.out.println(" • Expires: " + exp);
        String cookie = get.getCookie();
        if (cookie != null) System.out.println(" • Set-Cookie: " + cookie);
        String rp = get.getReferrerPolicy();
        if (rp != null) System.out.println(" • Referrer-Policy: " + rp);
        if (cto != null) System.out.println(" • X-Content-Type-Options: " + cto);
        if (xf != null) System.out.println(" • X-FrameOptions: " + xf);
        String csp = get.getCSP();
        if (csp != null) System.out.println(" • Content-Security-Policy: " + csp);
        String xss = get.getXSSProtection();
        if (xss != null) System.out.println(" • X-XSS-Protection: " + xss);
    }

    /**
     * Try to identify the server software from the response headers.
     * Uses known header fingerprints when the Server header is absent.
     *
     * @param server  Server header value (may be null)
     * @param cc      Cache-Control header value
     * @param cl      Content-Length as string
     * @param lm      Last-Modified header value
     * @param ar      Accept-Ranges header value
     * @param et      ETag header value
     * @param xf      X-Frame-Options header value
     * @param cto     X-Content-Type-Options header value
     * @return the identified server name, or &quot;unknown&quot;
     */
    private static String guessServer(String server, String cc, String cl, String lm, String ar, String et,
                                       String xf, String cto) {
        if (server != null) return server;
        if (cc != null
                && (cc.equals("max-age=3600,public") || cc.equals("no-cache, private, max-age=2628000")))
            return "Jetty (?)";
        if (cc == null && "217".equals(cl) && lm != null && "bytes".equals(ar) && et == null)
            return "Jetty (ZZZOT)";
        if (cc == null && "DENY".equals(xf) && "nosniff".equals(cto))
            return "Jetty (?)";
        if ("bytes".equals(ar) && lm != null && et != null)
            return "nginx (?)";
        return "unknown";
    }

    /**
     * Return the command-line usage text for eephead.
     *
     * @return CLI usage string
     */
    private static String usage() {
        return "Usage:\n" + "  eephead [opts] <url>   request server headers for url\n\n" + "Options:\n"
                + "  -c               do not use proxy\n" + "  -n <value>       number of retries (default 1)\n"
                + "  -p <host:port>   use alternative proxy (default is 127.0.0.1:4444)\n"
                + "  -t <value>       timeout in seconds (default 10)\n" + "  -u <value>       proxy username\n"
                + "  -x <value>       proxy password\n";
    }

    /**
     * Read the response headers and notify listeners.
     *
     * @param timeout may be null as of 0.9.49
     */
    @Override
    protected void doFetch(SocketTimeout timeout) throws IOException {
        _aborted = false;
        readHeaders();
        if (_aborted) throw new IOException("Timed out reading the HTTP headers");

        if (timeout != null) {
            timeout.resetTimer();
            if (_fetchInactivityTimeout > 0) timeout.setInactivityTimeout(_fetchInactivityTimeout);
            else timeout.setInactivityTimeout(DEFAULT_INACTIVITY_TIMEOUT);
        }

        // Should we even follow redirects for HEAD?
        if (_redirectLocation != null) {
            try {
                if (_redirectLocation.startsWith("http://")) {
                    _actualURL = _redirectLocation;
                } else {
                    // the Location: field has been required to be an absolute URI at least since
                    // RFC 1945 (HTTP/1.0 1996), so it isn't clear what the point of this is.
                    // This oddly adds a ":" even if no port, but that seems to work.
                    URI url = new URI(_actualURL);
                    String host = url.getHost();
                    if (host == null) throw new MalformedURLException("Redirected to invalid URL");
                    int port = url.getPort();
                    if (port < 0) port = 80;
                    if (_redirectLocation.startsWith("/"))
                        _actualURL = "http://" + host + ":" + port + _redirectLocation;
                    else
                        // this blows up completely on a redirect to https://, for example
                        _actualURL = "http://" + host + ":" + port + "/" + _redirectLocation;
                }
            } catch (URISyntaxException use) {
                IOException ioe = new MalformedURLException("Redirected to invalid URL");
                ioe.initCause(use);
                throw ioe;
            }

            AuthState as = _authState;
            if (_responseCode == 407) {
                if (!_shouldProxy) throw new IOException("Proxy auth response from non-proxy");
                if (as == null) throw new IOException("Proxy requires authentication");
                if (as.authSent) throw new IOException("Proxy authentication failed"); // ignore stale
                if (_log.shouldInfo()) _log.info("Adding auth");
                // actually happens in getRequest()
            } else {
                _redirects.incrementAndGet();
                if (_redirects.get() > 5) {
                    String redirectURL = _redirectLocation;
                    if (redirectURL.startsWith("http://")) {
                        redirectURL = redirectURL.substring(7, redirectURL.length());
                    }
                    if (redirectURL.contains("b32.i2p")) {
                        redirectURL = redirectURL.substring(0, 32) + "...";
                    }
                    throw new IOException("Too many redirects to " + redirectURL);
                }
                if (_log.shouldInfo()) _log.info("Redirecting to " + _redirectLocation);
                if (as != null) as.authSent = false;
            }

            // reset some important variables, we don't want to save the values from the redirect
            _bytesRemaining = -1;
            _redirectLocation = null;
            _etag = null;
            _lastModified = null;
            _contentType = null;
            _encodingChunked = false;

            sendRequest(timeout);
            doFetch(timeout);
            return;
        }
        if (timeout != null) timeout.cancel();

        if (_log.shouldDebug()) _log.debug("Headers read completely");

        if (_out != null) _out.close();
        _out = null;

        if (_aborted) throw new IOException("Timed out reading the HTTP data");

        if (_transferFailed) {
            // 404, etc - transferFailed is called after all attempts fail, by fetch() above
            for (int i = 0; i < _listeners.size(); i++)
                _listeners
                        .get(i)
                        .attemptFailed(_url, 0, 0, _currentAttempt, _numRetries, new Exception("Attempt failed"));
        } else {
            for (int i = 0; i < _listeners.size(); i++)
                _listeners.get(i).transferComplete(0, 0, 0, _url, "dummy", false);
        }
    }

    /**
     *  Should we read the body of the response?
     *
     *  @return false always
     *  @since 0.9.50
     */
    @Override
    protected boolean shouldReadBody() {
        return false;
    }

    /**
     * Build the HTTP HEAD request, including headers for Host, User-Agent, and proxy auth.
     *
     * @return the full HTTP 1.1 request string
     */
    @Override
    protected String getRequest() throws IOException {
        StringBuilder buf = new StringBuilder(512);
        URI url;
        try {
            url = new URI(_actualURL);
        } catch (URISyntaxException use) {
            IOException ioe = new MalformedURLException("Invalid URL");
            ioe.initCause(use);
            throw ioe;
        }
        String host = url.getHost();
        if (host == null) throw new MalformedURLException("Invalid URL");
        int port = url.getPort();
        String path = url.getRawPath();
        String query = url.getRawQuery();
        if (_log.shouldDebug()) _log.debug("Requesting headers for:" + _actualURL);
        // RFC 2616 sec 5.1.2 - full URL if proxied, absolute path only if not proxied
        String urlToSend;
        if (_shouldProxy) {
            urlToSend = _actualURL;
            if ((path == null || path.isEmpty()) && (query == null || query.isEmpty())) urlToSend += "/";
        } else {
            urlToSend = path;
            if (urlToSend == null || urlToSend.isEmpty()) urlToSend = "/";
            if (query != null) urlToSend += '?' + query;
        }
        buf.append("HEAD ").append(urlToSend).append(" HTTP/1.1\r\n");
        // RFC 2616 sec 5.1.2 - host + port (NOT authority, which includes userinfo)
        buf.append("Host: ").append(host);
        if (port >= 0) buf.append(':').append(port);
        buf.append("\r\n");
        buf.append("Accept-Encoding: \r\n");
        // This will be replaced if we are going through I2PTunnelHTTPClient
        buf.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (_authState != null && _shouldProxy && _authState.authMode != AUTH_MODE.NONE) {
            buf.append("Proxy-Authorization: ");
            buf.append(_authState.getAuthHeader("HEAD", urlToSend));
            buf.append("\r\n");
        }
        buf.append("Connection: close\r\n\r\n");
        if (_log.shouldDebug()) _log.debug("Request: [" + buf.toString() + "]");
        return buf.toString();
    }

    /** We don't decrement the variable (unlike in EepGet), so this is valid */
    public long getContentLength() {
        return _bytesRemaining;
    }
}
