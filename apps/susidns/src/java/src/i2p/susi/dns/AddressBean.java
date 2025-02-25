/*
 * This file is part of SusDNS project for I2P
 * Created on Sep 02, 2005
 * $Revision: 1.1 $
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.net.IDN;
import java.util.Locale;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;

public class AddressBean {
    private final String name;
    private final byte[] dest;
    private Properties props;
    /** available as of Java 6 */
    static final boolean haveIDN;

    static {
        boolean h;
        try {
            Class.forName("java.net.IDN", false, ClassLoader.getSystemClassLoader());
            h = true;
        } catch (ClassNotFoundException cnfe) {h = false;}
        haveIDN = h;
    }

    public AddressBean(String name, String destination) {
        this(name, Base64.decode(destination));
    }

    /**
     * @since 0.9.66
     */
    public AddressBean(String name, Destination destination) {
        this(name, destination.toByteArray());
    }

    /**
     * @since 0.9.66
     */
    public AddressBean(String name, byte[] destination) {
        this.name = name;
        dest = destination;
        if (dest == null || dest.length < 387) {
            throw new IllegalArgumentException();
        }
    }

    public String getDestination() {return Base64.encode(dest);}

    /**
     * The ASCII (Punycode) name
     */
    public String getName() {return name;}

    /**
     * The Unicode name, translated from Punycode
     * @return the original string on error
     * @since 0.8.7
     */
    public String getDisplayName() {return toUnicode(name);}

    /**
     * The Unicode name, translated from Punycode
     * @return the original string on error
     * @since 0.8.7
     */
    public static String toUnicode(String host) {
        if (haveIDN) {return IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED);}
        return host;
    }

    /**
     * Is the ASCII name Punycode-encoded?
     * @since 0.8.7
     */
    public boolean isIDN() {
        return haveIDN && !IDN.toUnicode(name, IDN.ALLOW_UNASSIGNED).equals(name);
    }

    private static final char DOT = '.';
    private static final char DOT2 = 0x3002;
    private static final char DOT3 = 0xFF0E;
    private static final char DOT4 = 0xFF61;

    /**
     * Ref: java.net.IDN and RFC 3490
     * @param host will be converted to lower case
     * @return name converted to lower case and punycoded if necessary
     * @throws IllegalArgumentException on various errors or if IDN is needed but not available
     * @since 0.8.7
     */
    static String toASCII(String host) throws IllegalArgumentException {
        host = host.toLowerCase(Locale.US);

        boolean needsIDN = false;
        // Here we do easy checks and throw translated exceptions.
        // We do checks on the whole host name, not on each "label", so
        // we allow '.', and some untranslated errors will be thrown by IDN.toASCII()
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= 0x2c ||
                c == 0x2f ||
                c >= 0x3a && c <= 0x40 ||
                c >= 0x5b && c <= 0x60 ||
                c >= 0x7b && c <= 0x7f) {
                String bad = "\"" + c + "\" (0x" + Integer.toHexString(c) + ')';
                throw new IllegalArgumentException(_t("Host name \"{0}\" contains illegal character {1}", host, bad));
            }
            if (c == DOT2) {host = host.replace(DOT2, DOT);}
            else if (c == DOT3) {host = host.replace(DOT3, DOT);}
            else if (c == DOT4) {host = host.replace(DOT4, DOT);}
            else if (c > 0x7f) {needsIDN = true;}
        }
        if (host.startsWith("-")) {
            throw new IllegalArgumentException(_t("Host name cannot start with \"{0}\"", "-"));
        }
        if (host.startsWith(".")) {
            throw new IllegalArgumentException(_t("Host name cannot start with \"{0}\"", "."));
        }
        if (host.endsWith("-")) {
            throw new IllegalArgumentException(_t("Host name cannot end with \"{0}\"", "-"));
        }
        if (host.endsWith(".")) {
            throw new IllegalArgumentException(_t("Host name cannot end with \"{0}\"", "."));
        }
        if (needsIDN) {
            if (host.startsWith("xn--")) {
                throw new IllegalArgumentException(_t("Host name cannot start with \"{0}\"", "xn--"));
            }
            if (host.contains(".xn--")) {
                throw new IllegalArgumentException(_t("Host name cannot contain \"{0}\"", ".xn--"));
            }
            if (haveIDN) {return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);}
            throw new IllegalArgumentException(_t("Host name \"{0}\" requires conversion to ASCII but the conversion library is unavailable in this JVM", host));
        }
        return host;
    }

    /** @since 0.8.7 */
    public String getB32() {
        byte[] hash = I2PAppContext.getGlobalContext().sha().calculateHash(dest).getData();
        return Base32.encode(hash) + ".b32.i2p";
    }

    /** @since 0.9 */
    public String getB64() {
        return I2PAppContext.getGlobalContext().sha().calculateHash(dest).toBase64();
    }

    /** @since 0.8.7 */
    public void setProperties(Properties p) {props = p;}

    /** @since 0.8.7 */
    public String getSource() {
        String rv = getProp("s");
        if (rv.startsWith("http://")) {rv = "<a href=\"" + rv + "\" target=_top>" + rv + "</a>";}
        return rv;
    }

    /** @since 0.8.7 */
    public String getAdded() {return getDate("a");}

    /** @since 0.8.7 */
    public String getModded() {return getDate("m");}

    /** @since 0.9.26 */
    public boolean isValidated() {return Boolean.parseBoolean(getProp("v"));}

    /** @since 0.8.7 */
    public String getNotes() {return DataHelper.escapeHTML(getProp("notes"));}

    /**
     * Do this the easy way
     * @since 0.8.7
     */
    public String getCert() {
        int type = dest[384] & 0xff;
        switch (type) {
            case Certificate.CERTIFICATE_TYPE_NULL:
                return _t("None");
            case Certificate.CERTIFICATE_TYPE_HASHCASH:
                return _t("Hashcash");
            case Certificate.CERTIFICATE_TYPE_HIDDEN:
                return _t("Hidden");
            case Certificate.CERTIFICATE_TYPE_SIGNED:
                return _t("Signed");
            case Certificate.CERTIFICATE_TYPE_KEY:
                return _t("Key");
            default:
                return _t("Type {0}", type);
        }
    }

    /**
     * Do this the easy way
     * @since 0.9.12
     */
    public String getSigType() {
        int type = dest[384] & 0xff;
        if (type != Certificate.CERTIFICATE_TYPE_KEY) {return _t("DSA 1024 bit");}
        int st = ((dest[387] & 0xff) << 8) | (dest[388] & 0xff);
        if (st == 0) {return _t("DSA 1024 bit");}
        SigType stype = SigType.getByCode(st);
        if (stype == null) {return _t("Type {0}", st);}
        return stype.toString();
    }

    /**
     * @return non-null, "" if not found
     * @since 0.8.7, package private since 0.9.66
     */
    String getProp(String p) {
        if (props == null) {return "";}
        String rv = props.getProperty(p);
        return rv != null ? rv : "";
    }

    /** @since 0.8.7 */
    private String getDate(String key) {
        String d = getProp(key);
        if (d.length() > 0) {
            try {d = DataHelper.formatTime(Long.parseLong(d));}
            catch (NumberFormatException nfe) {}
        }
        return d;
    }

    /** translate */
    private static String _t(String s) {return Messages.getString(s);}

    /** translate */
    private static String _t(String s, Object o) {return Messages.getString(s, o);}

    /** translate */
    private static String _t(String s, Object o, Object o2) {return Messages.getString(s, o, o2);}

}
