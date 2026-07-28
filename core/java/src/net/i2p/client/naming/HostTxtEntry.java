package net.i2p.client.naming;

import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.OrderedProperties;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

/**
 * A hostname, b64 destination, and optional properties.
 * Includes methods to sign and verify the entry.
 * Used by addressbook to parse subscription data,
 * and by i2ptunnel to generate signed metadata.
 *
 * @since 0.9.26
 */
public class HostTxtEntry {

    private final String name;
    private final String dest;
    private final OrderedProperties props;
    private boolean isValidated;
    private boolean isValid;

    /** Separator between name and destination in the hosts.txt line */
    public static final char KV_SEPARATOR = '=';
    /** Separator between the destination part and the properties part */
    public static final String PROPS_SEPARATOR = "#!";
    /** Separator between individual properties */
    public static final char PROP_SEPARATOR = '#';
    /** Property key for the action type (e.g. adddest, remove) */
    public static final String PROP_ACTION = "action";
    /** Property key for the date timestamp (seconds since epoch) */
    public static final String PROP_DATE = "date";
    /** Property key for the destination Base64 string */
    public static final String PROP_DEST = "dest";
    /** Property key for the expiry time */
    public static final String PROP_EXPIRES = "expires";
    /** Property key for the hostname */
    public static final String PROP_NAME = "name";
    /** Property key for the old destination (used in change operations) */
    public static final String PROP_OLDDEST = "olddest";
    /** Property key for the old hostname (used in rename operations) */
    public static final String PROP_OLDNAME = "oldname";
    /** Property key for the old signature for inner verification */
    public static final String PROP_OLDSIG = "oldsig";
    /** Property key for the signature */
    public static final String PROP_SIG = "sig";
    /** Action value: add a new destination */
    public static final String ACTION_ADDDEST = "adddest";
    /** Action value: add a new hostname */
    public static final String ACTION_ADDNAME = "addname";
    /** Action value: add a subdomain entry */
    public static final String ACTION_ADDSUBDOMAIN = "addsubdomain";
    /** Action value: change an existing destination */
    public static final String ACTION_CHANGEDEST = "changedest";
    /** Action value: change an existing hostname */
    public static final String ACTION_CHANGENAME = "changename";
    /** Action value: remove an entry */
    public static final String ACTION_REMOVE = "remove";
    /** Action value: remove all entries */
    public static final String ACTION_REMOVEALL = "removeall";
    /** Action value: update an existing entry */
    public static final String ACTION_UPDATE = "update";

    /**
     * Create a new host text entry with name and destination, no properties.
     *
     * @param name the hostname
     * @param dest the Base64 destination
     */
    public HostTxtEntry(String name, String dest) {
        this(name, dest, (OrderedProperties) null);
    }

    /**
     * Create a new host text entry with name, destination, and serialized properties.
     *
     * @param name the hostname
     * @param dest the Base64 destination
     * @param sprops line part after the #!, non-null
     * @throws IllegalArgumentException on dup key in sprops and other errors
     */
    public HostTxtEntry(String name, String dest, String sprops) throws IllegalArgumentException {
        this(name, dest, parseProps(sprops));
    }

    /**
     * A 'remove' entry. Name and Dest will be null.
     *
     * @param sprops line part after the #!, non-null
     * @throws IllegalArgumentException on dup key in sprops and other errors
     */
    public HostTxtEntry(String sprops) throws IllegalArgumentException {
        this(null, null, parseProps(sprops));
    }

    /**
     * Create a new host text entry with optional properties.
     *
     * @param name the hostname
     * @param dest the Base64 destination
     * @param props may be null
     */
    public HostTxtEntry(String name, String dest, OrderedProperties props) {
        this.name = name;
        this.dest = dest;
        this.props = props;
    }

    /**
     * Return the hostname.
     *
     * @return the hostname
     */
    public String getName() {
        return name;
    }

    /**
     * Return the Base64 destination.
     *
     * @return the Base64 destination
     */
    public String getDest() {
        return dest;
    }

    /**
     * Return the properties, or null.
     *
     * @return the properties, or null
     */
    public OrderedProperties getProps() {
        return props;
    }

    /**
     * @param line part after the #!
     * @return the parsed properties
     * @throws IllegalArgumentException on dup key and other errors
     */
    private static OrderedProperties parseProps(String line) throws IllegalArgumentException {
        line = line.trim();
        OrderedProperties rv = new OrderedProperties();
        String[] entries = DataHelper.split(line, "#");
        for (int i = 0; i < entries.length; i++) {
            String kv = entries[i];
            int eq = kv.indexOf('=');
            if (eq <= 0 || eq == kv.length() - 1) throw new IllegalArgumentException("No value: \"" + kv + '"');
            String k = kv.substring(0, eq);
            String v = kv.substring(eq + 1);
            Object old = rv.setProperty(k, v);
            if (old != null) throw new IllegalArgumentException("Dup key: " + k);
        }
        return rv;
    }

    /**
     * Write as a standard line name=dest[#!k1=v1#k2=v2...]
     * Includes newline.
     *
     * @param out the writer to write to
     * @throws IOException if writing fails
     */
    public void write(BufferedWriter out) throws IOException {
        write((Writer) out);
        out.newLine();
    }

    /**
     * Write as a standard line name=dest[#!k1=v1#k2=v2...]
     * Does not include newline.
     *
     * @param out the writer to write to
     * @throws IOException if writing fails
     */
    public void write(Writer out) throws IOException {
        if (name != null && dest != null) {
            out.write(name);
            out.write(KV_SEPARATOR);
            out.write(dest);
        }
        writeProps(out);
    }

    /**
     * Write as a "remove" line #!dest=dest#name=name#k1=v1#sig=sig...]
     * This works whether constructed with name and dest, or just properties.
     * Includes newline.
     * Must have been constructed with non-null properties.
     *
     * @param out the writer to write to
     * @throws IOException if writing fails
     */
    public void writeRemoveLine(BufferedWriter out) throws IOException {
        writeRemove(out);
        out.newLine();
    }

    /**
     * Write as a "remove" line #!dest=dest#name=name#k1=v1#sig=sig...]
     * This works whether constructed with name and dest, or just properties.
     * Does not include newline.
     * Must have been constructed with non-null properties.
     *
     * @param out the writer to write to
     * @throws IOException if writing fails
     */
    public void writeRemove(Writer out) throws IOException {
        if (props == null) throw new IllegalStateException();
        if (name != null && dest != null) {
            props.setProperty(PROP_NAME, name);
            props.setProperty(PROP_DEST, dest);
        }
        writeProps(out);
        if (name != null && dest != null) {
            props.remove(PROP_NAME);
            props.remove(PROP_DEST);
        }
    }

    /**
     * Write the props part (if any) only, without newline.
     *
     * @param out the writer to write to
     * @throws IOException if writing fails
     */
    public void writeProps(Writer out) throws IOException {
        writeProps(out, false, false);
    }

    /**
     * Write the props part (if any) only, without newline
     */
    private void writeProps(Writer out, boolean omitSig, boolean omitOldSig) throws IOException {
        if (props == null) return;
        boolean started = false;
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String k = (String) e.getKey();
            if (omitSig && k.equals(PROP_SIG)) continue;
            if (omitOldSig && k.equals(PROP_OLDSIG)) continue;
            if (started) {
                out.write(PROP_SEPARATOR);
            } else {
                started = true;
                out.write(PROPS_SEPARATOR);
            }
            String v = (String) e.getValue();
            out.write(k);
            out.write(KV_SEPARATOR);
            out.write(v);
        }
    }

    /**
     * Verify with the dest public key using the "sig" property
     * @return whether valid sig is present
     */
    public boolean hasValidSig() {
        if (props == null || name == null || dest == null) return false;
        if (!isValidated) {
            isValidated = true;
            StringWriter buf = new StringWriter(1024);
            String sig = props.getProperty(PROP_SIG);
            if (sig == null) return false;
            buf.append(name);
            buf.append(KV_SEPARATOR);
            buf.append(dest);
            try {
                writeProps(buf, true, false);
            } catch (IOException ioe) {
                // won't happen
                return false;
            }
            byte[] sdata = Base64.decode(sig);
            if (sdata == null) return false;
            Destination d;
            try {
                d = new Destination(dest);
            } catch (DataFormatException dfe) {
                return false;
            }
            SigningPublicKey spk = d.getSigningPublicKey();
            SigType type = spk.getType();
            if (type == null) return false;
            Signature s;
            try {
                s = new Signature(type, sdata);
            } catch (IllegalArgumentException iae) {
                return false;
            }
            isValid = DSAEngine.getInstance().verifySignature(s, DataHelper.getUTF8(buf.toString()), spk);
        }
        return isValid;
    }

    /**
     * Verify with the "olddest" property's public key using the "oldsig" property
     * @return whether valid inner sig is present
     */
    public boolean hasValidInnerSig() {
        if (props == null || name == null || dest == null) return false;
        boolean rv = false;
        // don't cache result
        StringWriter buf = new StringWriter(1024);
        String sig = props.getProperty(PROP_OLDSIG);
        String olddest = props.getProperty(PROP_OLDDEST);
        if (sig == null || olddest == null) return false;
        buf.append(name);
        buf.append(KV_SEPARATOR);
        buf.append(dest);
        try {
            writeProps(buf, true, true);
        } catch (IOException ioe) {
            // won't happen
            return false;
        }
        byte[] sdata = Base64.decode(sig);
        if (sdata == null) return false;
        Destination d;
        try {
            d = new Destination(olddest);
        } catch (DataFormatException dfe) {
            return false;
        }
        SigningPublicKey spk = d.getSigningPublicKey();
        SigType type = spk.getType();
        if (type == null) return false;
        Signature s;
        try {
            s = new Signature(type, sdata);
        } catch (IllegalArgumentException iae) {
            return false;
        }
        rv = DSAEngine.getInstance().verifySignature(s, DataHelper.getUTF8(buf.toString()), spk);
        return rv;
    }

    /**
     * Verify with the "dest" property's public key using the "sig" property
     * @return whether valid remove sig is present
     */
    public boolean hasValidRemoveSig() {
        if (props == null) return false;
        boolean rv = false;
        // don't cache result
        StringWriter buf = new StringWriter(1024);
        String sig = props.getProperty(PROP_SIG);
        String olddest = props.getProperty(PROP_DEST);
        if (sig == null || olddest == null) return false;
        try {
            writeProps(buf, true, true);
        } catch (IOException ioe) {
            // won't happen
            return false;
        }
        byte[] sdata = Base64.decode(sig);
        if (sdata == null) return false;
        Destination d;
        try {
            d = new Destination(olddest);
        } catch (DataFormatException dfe) {
            return false;
        }
        SigningPublicKey spk = d.getSigningPublicKey();
        SigType type = spk.getType();
        if (type == null) return false;
        Signature s;
        try {
            s = new Signature(type, sdata);
        } catch (IllegalArgumentException iae) {
            return false;
        }
        rv = DSAEngine.getInstance().verifySignature(s, DataHelper.getUTF8(buf.toString()), spk);
        return rv;
    }

    @Override
    public int hashCode() {
        return dest.hashCode();
    }

    /**
     *  Compares Destination only, not properties
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof HostTxtEntry)) return false;
        HostTxtEntry he = (HostTxtEntry) o;
        return dest.equals(he.getDest());
    }

    /**
     * Sign and set the "sig" property
     * Must have been constructed with non-null properties.
     */
    public void sign(SigningPrivateKey spk) {
        signIt(spk, PROP_SIG);
    }

    /**
     * Sign and set the "oldsig" property
     * Must have been constructed with non-null properties.
     */
    public void signInner(SigningPrivateKey spk) {
        signIt(spk, PROP_OLDSIG);
    }

    /**
     * Sign as a "remove" line #!dest=dest#name=name#k1=v1#sig=sig...]
     * Must have been constructed with non-null properties.
     */
    public void signRemove(SigningPrivateKey spk) {
        if (props == null) throw new IllegalStateException();
        if (props.containsKey(PROP_SIG)) throw new IllegalStateException();
        props.setProperty(PROP_NAME, name);
        props.setProperty(PROP_DEST, dest);
        if (!props.containsKey(PROP_DATE)) props.setProperty(PROP_DATE, Long.toString(System.currentTimeMillis() / 1000));
        StringWriter buf = new StringWriter(1024);
        try {
            writeProps(buf);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        props.remove(PROP_NAME);
        props.remove(PROP_DEST);
        Signature s = DSAEngine.getInstance().sign(DataHelper.getUTF8(buf.toString()), spk);
        if (s == null) throw new IllegalArgumentException("sig failed");
        props.setProperty(PROP_SIG, s.toBase64());
    }

    /**
     * Sign the entry and set the specified signature property.
     *
     * @param spk the signing private key
     * @param sigprop The signature property to set
     */
    private void signIt(SigningPrivateKey spk, String sigprop) {
        if (props == null) throw new IllegalStateException();
        if (props.containsKey(sigprop)) throw new IllegalStateException();
        if (!props.containsKey(PROP_DATE)) props.setProperty(PROP_DATE, Long.toString(System.currentTimeMillis() / 1000));
        StringWriter buf = new StringWriter(1024);
        buf.append(name);
        buf.append(KV_SEPARATOR);
        buf.append(dest);
        try {
            writeProps(buf);
        } catch (IOException ioe) {
            throw new IllegalStateException(ioe);
        }
        Signature s = DSAEngine.getInstance().sign(DataHelper.getUTF8(buf.toString()), spk);
        if (s == null) throw new IllegalArgumentException("sig failed");
        props.setProperty(sigprop, s.toBase64());
    }

    /**
     *  Usage: HostTxtEntry [-i] [-x] [hostname.i2p] [key=val]...
     */

}
