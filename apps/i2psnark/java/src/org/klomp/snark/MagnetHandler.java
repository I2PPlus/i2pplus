package org.klomp.snark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Standalone launcher that forwards magnet links, torrent URLs, info hashes,
 * or torrent file paths to the I2PSnark browser API (POST /_add).
 *
 * <p>Registered by {@link org.klomp.snark.web.BrowserApiInstaller} as the OS
 * browser handler for magnet: links and .torrent files, replacing the previous
 * shell-script approach (no curl, no CSRF nonce scraping, no HTML parsing).
 *
 * <p>Usage: <code>java -cp i2psnark.jar org.klomp.snark.MagnetHandler --url
 * http://127.0.0.1:7657/i2psnark 'magnet:?xt=urn:btih:...'</code>
 *
 * <p>Each URI is sent as a POST with form-encoded parameter newURL. The server
 * replies with a single status line; exit code is 0 only if all were OK.
 *
 * @since 0.9.71+
 */
public final class MagnetHandler {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:7657/i2psnark";
    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;
    private static final int READ_TIMEOUT_MS = 60 * 1000;

    private MagnetHandler() {}

    public static void main(String[] args) {
        String base = DEFAULT_BASE_URL;
        int rc = 0;
        int sent = 0;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--url".equals(arg)) {
                if (i + 1 < args.length) {
                    base = args[++i];
                }
            } else if (arg.startsWith("-")) {
                System.err.println("Usage: MagnetHandler [--url <console base URL>] <magnet|url|infohash|torrent path>...");
                rc = 1;
            } else {
                sent++;
                if (!send(base, arg)) {
                    rc = 1;
                }
            }
        }
        if (sent == 0) {
            System.err.println("Usage: MagnetHandler [--url <console base URL>] <magnet|url|infohash|torrent path>...");
            rc = 1;
        }
        System.exit(rc);
    }

    /**
     * POST one URI to the browser API and print the server's status line.
     *
     * @param base console base URL, no trailing slash
     * @param uri the magnet link, torrent URL, info hash, or torrent file path
     * @return true if the server accepted it
     */
    private static boolean send(String base, String uri) {
        HttpURLConnection conn = null;
        try {
            URL url = buildApiUri(base, "/_add").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "nofilter_newURL=" + URLEncoder.encode(uri, StandardCharsets.UTF_8.name());
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String line = null;
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    line = r.readLine();
                }
            }
            if (line == null) {line = "HTTP " + code;}
            System.out.println(line);
            return code < 400 && line.startsWith("OK");
        } catch (IOException ioe) {
            System.out.println("ERR: " + ioe.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Build the absolute API URI for a POST from a console base URL.
     *
     * <p>A trailing slash on the base is stripped before appending the path so
     * the result never contains a double slash, regardless of how the operator
     * supplied the base URL.
     *
     * @param base console base URL, with or without a trailing slash
     * @param path the API path to append, e.g. "/_add"
     * @return the absolute API URI
     * @throws IllegalArgumentException if the resulting URI is not a
     *         valid absolute URI
     * @since 0.9.71+
     */
    static URI buildApiUri(String base, String path) {
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }
}
