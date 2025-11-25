package net.i2p.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;

/**
 * Extends EepGet for POST.
 * Adapted from old jrandom EepPost, removed 2012 as unused.
 * Ref: RFC 7578
 *
 * @since 0.9.67
 */
public class EepPost extends EepGet {

    private static final String CRLF = "\r\n";
    private static final byte[] CRLFB = DataHelper.getASCII(CRLF);
    private static final int PROP_MAX_POST_PAYLOAD_RAM = 32*1024;

    public EepPost(I2PAppContext ctx, String proxyHost, int proxyPort, int numRetries, String outputFile, String url) {
        /*
         * We're using this constructor:
         * public EepGet(I2PAppContext ctx, boolean shouldProxy, String proxyHost, int proxyPort, int numRetries, long minSize,
         *               long maxSize, String outputFile, OutputStream outputStream, String url, boolean allowCaching, String etag,
         *               String postData) {
         */
        super(ctx, true, proxyHost, proxyPort, numRetries, -1, -1, outputFile, null, url, true, null, null);
    }

    /**
     * Submit an HTTP POST to the given URL (using the proxy if specified), uploading the given fields.
     * If the field's value is a File object, then that file is uploaded, and if the field's value is
     * a String object, the value is posted for that particular field.
     * Multiple values for one field name is not currently supported.
     *
     * Large files will be copied to a temp file.
     * For large String content, consider the post(File) method.
     *
     * Note: param field values must be String or File.
     */
    public boolean post(Map<String, Object> fields, long headerTimeout, long totalTimeout, long inactivityTimeout) {
        if (fields.isEmpty())
            throw new IllegalArgumentException();
        boolean multipart = false;
        long sz = 0;
        for (Object o : fields.values()) {
            if (o instanceof File) {
                sz += ((File) o).length();
                multipart = true;
                break;
            }
        }
        if (multipart) {
            String sep = getSeparator();
            boolean useTmp = sz > PROP_MAX_POST_PAYLOAD_RAM;
            File tmp = null;
            OutputStream out = null;
            ByteArrayOutputStream baos = null;
            try {
                if (useTmp) {
                    tmp = new File(_context.getTempDir(), "eeppost-" + _context.random().nextLong() + ".dat");
                    out = new FileOutputStream(tmp);
                    if (_log.shouldDebug())
                        _log.debug("Estimated size: " + sz + ", using temp file " + tmp);
                } else {baos = new ByteArrayOutputStream(4096);}
                sendFields(out, sep, fields);
                if (useTmp) {out.close();}
            } catch (IOException ioe) {
                try {out.close();}
                catch (IOException ioe2) {}
                if (tmp != null) {tmp.delete();}
                return false;
            }
            String type = "multipart/form-data, boundary=" + sep;
            boolean rv;
            if (tmp != null) {
                rv = post(type, tmp, headerTimeout, totalTimeout, inactivityTimeout);
                tmp.delete();
            } else {
                rv = post(type, baos.toByteArray(), headerTimeout, totalTimeout, inactivityTimeout);
            }
            return rv;
        } else {
            StringBuilder out = new StringBuilder(2048);
            sendFields(out, fields);
            String type = "application/x-www-form-urlencoded";
            return post(type, out.toString(), headerTimeout, totalTimeout, inactivityTimeout);
        }
    }

    /**
     *  In-memory, not for large POSTs
     */
    public boolean post(String contentType, String data, long headerTimeout, long totalTimeout, long inactivityTimeout) {
        if (data.length() == 0)
            throw new IllegalArgumentException();
        setPostData(contentType, data);
        return super.fetch(headerTimeout, totalTimeout, inactivityTimeout);
    }

    /**
     *  In-memory, not for large POSTs
     */
    public boolean post(String contentType, byte[] data, long headerTimeout, long totalTimeout, long inactivityTimeout) {
        if (data.length == 0)
            throw new IllegalArgumentException();
        setPostData(contentType, data);
        return super.fetch(headerTimeout, totalTimeout, inactivityTimeout);
    }

    /**
     *  For large POSTs
     */
    public boolean post(String contentType, File data, long headerTimeout, long totalTimeout, long inactivityTimeout) {
        if (!data.isFile() || data.length() == 0)
            throw new IllegalArgumentException();
        setPostData(contentType, data);
        return super.fetch(headerTimeout, totalTimeout, inactivityTimeout);
    }

    /**
     * @throws UnsupportedOperationException always
     */
    public boolean fetch() {
        throw new UnsupportedOperationException("use post()");
    }

    /**
     * @throws UnsupportedOperationException always
     */
    public boolean fetch(long fetchHeaderTimeout) {
        throw new UnsupportedOperationException("use post()");
    }

    /**
     * @throws UnsupportedOperationException always
     */
    public boolean fetch(long fetchHeaderTimeout, long totalTimeout, long inactivityTimeout) {
        throw new UnsupportedOperationException("use post()");
    }

    /**
     *  Adapted from old jrandom EepPost
     */
    private static void sendFields(StringBuilder out, Map<String, Object> fields) {
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String field = e.getKey();
            Object val = e.getValue();
            if (!first)
                out.append('&');
            sendField(out, field, val.toString());
            first = false;
        }
        out.append(CRLF);
    }

    /**
     *  Multipart
     *  Adapted from old jrandom EepPost
     *  @param separator non-null
     */
    private static void sendFields(OutputStream out, String separator, Map<String, Object> fields) throws IOException {
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String field = e.getKey();
            Object val = e.getValue();
            if (val instanceof File) {
                sendFile(out, separator, field, (File)val);
            } else {
                sendField(out, separator, field, val.toString());
            }
        }
        out.write(DataHelper.getUTF8("--" + separator + "--" + CRLF));
    }

    /**
     *  Adapted from old jrandom EepPost
     */
    private static void sendField(StringBuilder out, String field, String val) {
        // TODO % encoding
        out.append(field.replace(" ", "+")).append('=').append(val.replace(" ", "+"));
    }

    /**
     *  Multipart
     *  Adapted from old jrandom EepPost
     *  @param separator non-null
     */
    private static void sendField(OutputStream out, String separator, String field, String val) throws IOException {
        out.write(DataHelper.getUTF8("--" + separator + CRLF));
        out.write(DataHelper.getUTF8("Content-Disposition: form-data; name=\"" + field + "\"" + CRLF + CRLF));
        out.write(DataHelper.getUTF8(val));
        out.write(CRLFB);
    }

    /**
     *  Multipart
     *  Adapted from old jrandom EepPost
     *  @param separator non-null
     */
    private static void sendFile(OutputStream out, String separator, String field, File file) throws IOException {
        out.write(DataHelper.getUTF8("--" + separator + CRLF));
        out.write(DataHelper.getUTF8("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + file.getName() + "\"" + CRLF));
        out.write(DataHelper.getUTF8("Content-Type: application/octet-stream" + CRLF + CRLF));
        FileInputStream in = new FileInputStream(file);
        try {
            DataHelper.copy(in, out);
        } finally {
            in.close();
        }
        out.write(CRLFB);
    }

    /**
     *  Adapted from old jrandom EepPost
     */
    private String getSeparator() {
        byte separator[] = new byte[32];
        _context.random().nextBytes(separator);
        return Base32.encode(separator);
    }

    /**
     * EepPost [-p 127.0.0.1:4444] [-n #retries] url
     */
    public static void main(String args[]) {
        String proxyHost = "127.0.0.1";
        int proxyPort = 4444;
        int numRetries = 0;
        int headerTimeout = CONNECT_TIMEOUT;
        int totalTimeout = -1;
        int inactivityTimeout = INACTIVITY_TIMEOUT;
        int markSize = 1024;
        int lineLen = 40;
        String saveAs = null;
        String username = null;
        String password = null;
        boolean error = false;
        Map<String, Object> fields = new HashMap<String, Object>(8);
        Getopt g = new Getopt("eeppost", args, "p:cn:t:v:w:o:u:x:l:m:s:f:");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
              switch (c) {
                case 'p':
                    String s = g.getOptarg();
                    int colon = s.indexOf(':');
                    if (colon >= 0) {
                        // Todo IPv6 [a:b:c]:4444
                        proxyHost = s.substring(0, colon);
                        String port = s.substring(colon + 1);
                        proxyPort = Integer.parseInt(port);
                    } else {
                        proxyHost = s;
                        // proxyPort remains default
                    }
                    break;

                case 'c':
                    // no proxy, same as -p :0
                    proxyHost = "";
                    proxyPort = 0;
                    break;

                case 'f': {
                    String[] t = DataHelper.split(g.getOptarg(), "=", 2);
                    if (t.length == 2 && t[0].length() > 0)
                        fields.put(t[0], new File(t[1]));
                    else
                        error = true;
                    break;
                }

                case 'l':
                    lineLen = Integer.parseInt(g.getOptarg());
                    break;

                case 'm':
                    markSize = Integer.parseInt(g.getOptarg());
                    break;

                case 'n':
                    numRetries = Integer.parseInt(g.getOptarg());
                    break;

                case 'o':
                    saveAs = g.getOptarg();
                    break;

                case 's': {
                    String[] t = DataHelper.split(g.getOptarg(), "=", 2);
                    if (t.length == 2 && t[0].length() > 0)
                        fields.put(t[0], t[1]);
                    else
                        error = true;
                    break;
                }

                case 't':
                    inactivityTimeout = 1000 * Integer.parseInt(g.getOptarg());
                    break;

                case 'u':
                    username = g.getOptarg();
                    break;

                case 'v':
                    headerTimeout = 1000 * Integer.parseInt(g.getOptarg());
                    break;

                case 'w':
                    totalTimeout = 1000 * Integer.parseInt(g.getOptarg());
                    break;

                case 'x':
                    password = g.getOptarg();
                    break;

                case '?':
                case ':':
                default:
                    error = true;
                    break;
              }  // switch
            } // while
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }

        if (error || args.length - g.getOptind() != 1 || fields.isEmpty()) {
            if (fields.isEmpty())
                System.err.println("At least one -s or -f parameter required");
            usage();
            System.exit(1);
        }
        String url = args[g.getOptind()];

        if (saveAs == null)
            saveAs = suggestName(url);

        EepPost post = new EepPost(I2PAppContext.getGlobalContext(), proxyHost, proxyPort, numRetries, saveAs, url);
        if (username != null) {
            if (password == null) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                    do {
                        System.err.print("Proxy password: ");
                        password = r.readLine();
                        if (password == null)
                            throw new IOException();
                        password = password.trim();
                    } while (password.length() <= 0);
                } catch (IOException ioe) {
                    System.exit(1);
                }
            }
            post.addAuthorization(username, password);
        }
        post.addStatusListener(post.new CLIStatusListener(markSize, lineLen));
        if (!post.post(fields, headerTimeout, totalTimeout, inactivityTimeout)) {
            System.err.println("Failed " + url);
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("eeppost [-p 127.0.0.1[:4444]] [-c] [-o outputFile]\n" +
                           "        [-s key=value]*\n" +
                           "        [-f key=file]*\n" +
                           "        [-m markSize] (default 1024)\n" +
                           "        [-l lineLen]  (default 40)\n" +
                           "        [-n #retries] (default 0)\n" +
                           "        [-t headerTimeout]  (default 45 sec)\n" +
                           "        [-u inactivityTimeout]  (default 60 sec)\n" +
                           "        [-w totalTimeout]  (default unlimited)\n" +
                           "        [-u username] [-x password] url\n" +
                           "        (use -c or -p :0 for no proxy)");
    }

}
