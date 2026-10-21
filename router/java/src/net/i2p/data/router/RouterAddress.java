package net.i2p.data.router;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.util.Addresses;
import net.i2p.util.OrderedProperties;

/**
 * Defines a method of communicating with a router
 * <p>
 * For efficiency, the options methods and structures here are unsynchronized.
 * Initialize the structure with {@link #readBytes}, or call the setOptions().
 * Don't change it after that.
 * <p>
 * To ensure integrity of the RouterInfo, methods that change an element of the
 * RouterInfo will throw an IllegalStateException after the RouterInfo is signed.
 * <p>
 * Expiration is accepted and returned, but the router MUST write all zeros:
 * the field is ignored on readin and a non-zero value fails the signature
 * check. Implementing expiration, or any other use of the field, requires
 * several releases to propagate because it is backwards-incompatible.
 *
 * @since 0.9.16 moved from net.i2p.data
 */
public class RouterAddress extends DataStructureImpl {
    /** Cost value (0-255). */
    private volatile short _cost;
    /** Expiration timestamp. */
    private long _expiration;
    /** Transport style string. */
    private String _transportStyle;
    /** Transport options. */
    private final Properties _options;
    // cached values
    /** Cached IP address. */
    private byte[] _ip = NOT_LOOKED_UP;
    /** Cached port number. */
    private int _port;

    /** Property key for host. */
    public static final String PROP_HOST = "host";
    /** Property key for port. */
    public static final String PROP_PORT = "port";
    private static final byte[] NOT_LOOKED_UP = new byte[0];
    /**
     * Create an address with empty options.
     */
    public RouterAddress() {
        _options = new OrderedProperties();
    }

    /**
     * Creates an address with a copy of the given options, so the caller
     * may reuse or modify them after construction.
     *
     * @param style transport style naming the protocol to use for this address
     * @param options may be null
     * @param cost 0-255
     */
    public RouterAddress(String style, OrderedProperties options, int cost) {
        _transportStyle = style;
        _options = new OrderedProperties();
        if (options != null)
            _options.putAll(options);
        if (cost < 0 || cost > 255)
            throw new IllegalArgumentException();
        _cost = (short) cost;
    }

    /**
     * Retrieve the weighted cost of this address, relative to other methods of
     * contacting this router.  The value 0 means free and 255 means really expensive.
     * No value above 255 is allowed.
     * <p>
     * Unused before 0.7.12.
     * @return 0-255
     */
    public int getCost() {
        return _cost;
    }

    /**
     * Configure the weighted cost of using the address.
     * No value negative or above 255 is allowed.
     * <p>
     * WARNING - do not change cost on a published address or it will break the RI sig.
     * There is no check here.
     * Rarely used, use 3-arg constructor.
     * <p>
     * NTCP is set to 10 and SSU to 5 by default, unused before 0.7.12.
     *
     * @param cost 0-255, where 0 is free and 255 is most expensive
     */
    public void setCost(int cost) {
        if (cost < 0 || cost > 255)
            throw new IllegalArgumentException();
        _cost = (short) cost;
    }

    /**
     * Retrieve the type of transport that must be used to communicate on this address.
     *
     * @return the transport style
     */
    public String getTransportStyle() {
        return _transportStyle;
    }

    /**
     * Retrieve the transport specific options necessary for communication.
     *
     * @return an unmodifiable view, non-null, sorted
     * @since 0.8.13
     */
    public Map<Object, Object> getOptionsMap() {
        return Collections.unmodifiableMap(_options);
    }

    /**
     * Transport option value for the given key.
     * @param opt the transport option name to look up, such as "host" or "port"
     * @return the value of the named transport option, or null if no such
     *         option is present
     * @since 0.8.13
     */
    public String getOption(String opt) {
        return _options.getProperty(opt);
    }

    /**
     * Caching version of {@code InetAddress.getByName(}getOption("host")).getAddress(), which is slow.
     * Caches numeric host names, and negative caches also.
     * <p>
     * As of 0.9.32, this works for literal IP addresses only, and does NOT resolve host names.
     * If a host name is specified in the options, this will return null.
     * Use {@link #getHost()} if you need the host name.
     *
     * @return IP or null
     * @since 0.9.3
     */
    public synchronized byte[] getIP() {
        if (_ip == NOT_LOOKED_UP) {
            // Only look up once, even if it fails, so we don't generate excessive DNS lookups.
            // The lifetime of a RouterAddress object is a few hours at most,
            // it will get republished or expired, so it's OK even for host names.
            String host = getHost();
            if (host != null)
                _ip = Addresses.getIPOnly(host);
            else
                _ip = null;
        }
        return _ip;
    }

    /**
     * Convenience, same as getOption("host").
     * Does no parsing, so faster than {@link #getIP()}.
     *
     * @return host string or null
     */
    public String getHost() {
        return _options.getProperty(PROP_HOST);
    }

    /**
     * Caching version of {@link java.lang.Integer#parseInt}getOption("port"))
     * Caches valid ports 1-65535 only.
     *
     * @return 1-65535 or 0 if invalid
     * @since 0.9.3
     */
    public int getPort() {
        if (_port != 0)
            return _port;
        String port = _options.getProperty(PROP_PORT);
        if (port != null) {
            try {
                int rv = Integer.parseInt(port);
                if (rv > 0 && rv <= 65535)
                    _port = rv;
            } catch (NumberFormatException nfe) { /* ignored */ }
        }
        return _port;
    }

    /**
     * Restores the expiration field, which is accepted but must be all zeros:
     * it is ignored on readin and a non-zero value fails the signature check.
     * @throws IllegalStateException if was already read in
     */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        if (_transportStyle != null)
            throw new IllegalStateException();
        // EOF will be thrown in next read
        _cost = (short) in.read();
        _expiration = DataHelper.readLong(in, 8);
        _transportStyle = DataHelper.readString(in);
        // reduce Object proliferation
        if (_transportStyle.equals("SSU"))
            _transportStyle = "SSU";
        else if (_transportStyle.equals("NTCP2"))
            _transportStyle = "NTCP2";
        else if (_transportStyle.equals("NTCP"))
            _transportStyle = "NTCP";
        else if (_transportStyle.equals("SSU2"))
            _transportStyle = "SSU2";
        // enforce mapping order so bad ones will fail-fast
        // before the signature check
        DataHelper.readProperties(in, _options, true);
    }

    /**
     * Write the cost, expiration, transport style, and options.
     * As of 0.9.3 the expiration MUST be all zeros, as it is ignored on
     * readin and the signature will fail.
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_transportStyle == null)
            throw new DataFormatException("uninitialized");
        out.write((byte) _cost);
        DataHelper.writeLong(out, 8, _expiration);
        DataHelper.writeString(out, _transportStyle);
        DataHelper.writeProperties(out, _options);
    }

    /**
     * Transport, host, and port only.
     * Never look at cost or other properties.
     */
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof RouterAddress)) return false;
        RouterAddress addr = (RouterAddress) object;
        boolean rv =
               getPort() == addr.getPort() &&
               DataHelper.eq(getHost(), addr.getHost()) &&
               DataHelper.eq(_transportStyle, addr._transportStyle);
               //DataHelper.eq(_options, addr._options) &&
        if (!rv || !_transportStyle.equals("SSU"))
               return rv;
        // SSU 4/6 caps
        return DataHelper.eq(_options.getProperty("caps"), addr._options.getProperty("caps"));
    }

    /**
     * Everything, including Transport, host, port, options, and cost.
     * @param addr may be null
     * @return true only if every field of both addresses matches
     */
    public boolean deepEquals(RouterAddress addr) {
        return
               equals(addr) &&
               _cost == addr._cost &&
               _options.equals(addr._options);
    }

    /**
     * Just use a few items for speed (expiration is always null).
     * Never look at cost or other properties.
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_transportStyle) ^
               DataHelper.hashCode(getIP()) ^
               getPort();
    }

    /**
     * Key-value pairs per line before the log form wraps. A full SSU2 address
     * carries up to thirty options, and one unbroken line buries the values
     * that matter.
     */
    private static final int LOG_WRAP = 4;

    /**
     * Form for logs and peer pages: style, host:port, then the remaining
     * options and cost, sorted by {@link OrderedProperties}, wrapped onto a
     * continuation line every {@link #LOG_WRAP} pairs. The {@code " * "}
     * separator is kept on every pair so the console renders it as a bullet.
     * Keys (i, s), version (v), capability bits (caps) and post-quantum
     * flags (pq) are debugging detail and are omitted; use {@link #toHtml()}
     * where they are wanted.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append(_transportStyle).append(": ");
        Object host = _options.get("host");
        Object port = _options.get("port");
        buf.append(host != null ? host : "").append(':').append(port != null ? port : "");
        int pairs = 0;
        for (Map.Entry<Object, Object> e : _options.entrySet()) {
            String key = (String) e.getKey();
            if (_hiddenOption(key)) {continue;}
            _logSep(buf, pairs);
            buf.append(_label(key)).append(": ").append(e.getValue());
            pairs++;
        }
        _logSep(buf, pairs);
        buf.append("Cost: ").append(_cost);
        pairs++;
        if (_expiration > 0) {
            _logSep(buf, pairs);
            buf.append("Expires: ").append(new Date(_expiration));
        }
        return buf.toString();
    }

    /**
     * Separator before a pair in the log form: the plain {@code " * "} bullet,
     * or that bullet at the start of a fresh line once {@link #LOG_WRAP} pairs
     * have been written.
     */
    private static void _logSep(StringBuilder buf, int pairs) {
        buf.append(pairs > 0 && pairs % LOG_WRAP == 0 ? "\n * " : " * ");
    }

    /**
     * Human-readable names for the option keys, for {@link #toHtml()}. The
     * introducer keys are prefixes that take a slot index, such as {@code ih0},
     * and are looked up without the index. These mirror the option-key
     * constants in the transport packages, which this package cannot reference
     * without a dependency cycle, so keep the two in step by hand.
     * <p>
     * Names are deliberately kept to a similar length so the values line up in
     * the console's monospace block.
     */
    private static final Map<String, String> HTML_LABELS = new HashMap<>(16);

    static {
        HTML_LABELS.put("host", "Host");
        HTML_LABELS.put("port", "Port");
        HTML_LABELS.put("mtu", "MTU");
        HTML_LABELS.put("i", "Static IV");
        HTML_LABELS.put("s", "Static Key");
        HTML_LABELS.put("v", "Version");
        HTML_LABELS.put("caps", "Capacity");
        HTML_LABELS.put("pq", "PQ Version");
        HTML_LABELS.put("key", "Intro Key");
        HTML_LABELS.put("ihost", "Intro Host");
        HTML_LABELS.put("iport", "Intro Port");
        HTML_LABELS.put("ikey", "Intro Key");
        HTML_LABELS.put("itag", "Intro Tag");
        HTML_LABELS.put("iexp", "Intro Exp");
        HTML_LABELS.put("ih", "Intro Hash");
    }

    /**
     * Labels are padded to this width so the colons and values line up in a
     * monospace block. A longer label is written out unchanged.
     */
    private static final int HTML_LABEL_WIDTH = 12;

    private static final String HTML_PAD = "            ";

    /**
     * HTML rendering for the console info page: one key-value pair per line,
     * each side in its own span so the theme can style keys and values apart.
     * Unlike {@link #toString()} this keeps the option keys the log form omits,
     * names them readably, escapes the values, and orders the lines by the name
     * the reader sees rather than by the option key. The transport style and its
     * host:port stay leading.
     *
     * @return the address as HTML lines, each pair newline terminated
     * @since 0.9.72+
     */
    public String toHtml() {
        StringBuilder buf = new StringBuilder(256);
        Object host = _options.get("host");
        Object port = _options.get("port");
        _htmlPair(buf, _transportStyle, (host != null ? host : "") + ":" + (port != null ? port : ""));
        // Sorted by the label, not the option key - the readable names do not
        // sort the same way. The introduction key "key" follows every "i*" key
        // in the options, but its name belongs among the other introducer rows.
        List<Map.Entry<String, String>> pairs = new ArrayList<>(_options.size() + 2);
        for (Map.Entry<Object, Object> e : _options.entrySet()) {
            String key = (String) e.getKey();
            if ("host".equals(key) || "port".equals(key)) {continue;}
            pairs.add(new AbstractMap.SimpleEntry<>(_htmlLabel(key), String.valueOf(e.getValue())));
        }
        pairs.add(new AbstractMap.SimpleEntry<>("Cost", Integer.toString(_cost)));
        if (_expiration > 0)
            pairs.add(new AbstractMap.SimpleEntry<>("Expires", new Date(_expiration).toString()));
        Collections.sort(pairs, Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        for (Map.Entry<String, String> e : pairs)
            _htmlPair(buf, e.getKey(), e.getValue());
        return buf.toString();
    }

    /**
     * Appends one {@code <span class=key>label</span> <span
     * class=value>value</span>} line, padding the label and escaping the value.
     * There is no punctuation between the two spans - the theme styles the key
     * span, and that styling is what separates the label from the value.
     */
    private static void _htmlPair(StringBuilder buf, String label, String value) {
        int pad = HTML_LABEL_WIDTH - label.length();
        buf.append("<span class=key>").append(label);
        if (pad > 0)
            buf.append(HTML_PAD, 0, pad);
        buf.append("</span> <span class=value>").append(DataHelper.escapeHTML(value)).append("</span>\n");
    }

    /**
     * Human-readable name for an option key, or the capitalized key if it is
     * unknown. Introducer keys are a prefix plus a slot index, so {@code ih0}
     * reads as the {@code ih} name followed by the slot number.
     */
    private static String _htmlLabel(String key) {
        String label = HTML_LABELS.get(key);
        if (label != null)
            return label;
        int i = key.length();
        while (i > 0 && Character.isDigit(key.charAt(i - 1)))
            i--;
        if (i > 0 && i < key.length()) {
            label = HTML_LABELS.get(key.substring(0, i));
            if (label != null)
                return label + ' ' + key.substring(i);
        }
        return _label(key);
    }

    /**
     * Display label for an option key: MTU uppercased, others capitalized.
     */
    private static String _label(String key) {
        if ("mtu".equals(key))
            return "MTU";
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    /**
     * Options omitted from {@link #toString()} as debugging detail.
     */
    private static boolean _hiddenOption(String key) {
        return "host".equals(key) || "port".equals(key) || "i".equals(key) ||
               "s".equals(key) || "v".equals(key) || "caps".equals(key) || "pq".equals(key);
    }

}
