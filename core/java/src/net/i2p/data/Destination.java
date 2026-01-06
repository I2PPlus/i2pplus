package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import net.i2p.util.LHMCache;
import net.i2p.util.SystemVersion;

/**
 * Defines an endpoint in the I2P network that can receive messages.
 * 
 * <p>Destination represents the fundamental addressing and identity concept in I2P:</p>
 * <ul>
 *   <li><strong>Network Endpoint:</strong> Messages sent to a Destination will find it regardless of location</li>
 *   <li><strong>Cryptographic Identity:</strong> Contains encryption and signing keys</li>
 *   <li><strong>Certificate:</strong> Optional metadata about the identity</li>
 *   <li><strong>Addressing:</strong> Can be encoded as Base64 or Base32 (.b32.i2p)</li>
 *   <li><strong>Immutability:</strong> Identity becomes immutable after keys are set</li>
 * </ul>
 * 
 * <p><strong>Key Components:</strong></p>
 * <ul>
 *   <li><strong>Public Encryption Key:</strong> Historically used for end-to-end encryption</li>
 *   <li><strong>Signing Public Key:</strong> Used for identity verification and LeaseSet signing</li>
 *   <li><strong>Certificate:</strong> Optional metadata (NULL, HIDDEN, SIGNED, etc.)</li>
 *   <li><strong>Cached Hash:</strong> SHA-256 hash for efficient identification</li>
 * </ul>
 * 
 * <p><strong>Address Formats:</strong></p>
 * <ul>
 *   <li><strong>Base64:</strong> Full binary encoding for configuration files</li>
 *   <li><strong>Base32:</strong> Human-readable .b32.i2p addresses</li>
 *   <li><strong>Hash:</strong> 32-byte SHA-256 for internal identification</li>
 * </ul>
 * 
 * <p><strong>Historical Changes:</strong></p>
 * <ul>
 *   <li><strong>Pre-0.6:</strong> Public key used for end-to-end encryption</li>
 *   <li><strong>Post-0.6:</strong> End-to-end encryption removed, LeaseSet keys used instead</li>
 *   <li><strong>LeaseSet Encryption:</strong> Public key first bytes used as IV (deprecated)</li>
 *   <li><strong>0.9.9:</strong> Immutable after keys and certificate are set</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong></p>
 * <ul>
 *   <li><strong>Client Connections:</strong> Target for I2P client communications</li>
 *   <li><strong>Service Hosting:</strong> Identity for hosting I2P services</li>
 *   <li><strong>Message Routing:</strong> Destination for I2NP message delivery</li>
 *   <li><strong>Address Book:</strong> Entry in address books and contact lists</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Key Protection:</strong> Private keys must be securely stored</li>
 *   <li><strong>Identity Verification:</strong> Always verify signatures from destinations</li>
 *   <li><strong>Certificate Validation:</strong> Check certificate types and content</li>
 *   <li><strong>Deprecated Features:</strong> Avoid legacy encryption mechanisms</li>
 * </ul>
 * 
 * <p><strong>Performance Features:</strong></p>
 * <ul>
 *   <li><strong>Caching:</strong> LRU cache for frequently used destinations</li>
 *   <li><strong>Lazy Computation:</strong> Base32 address computed on demand</li>
 *   <li><strong>Efficient Storage:</strong> Optimized memory usage and serialization</li>
 *   <li><strong>Fast Lookup:</strong> Hash-based identification for quick comparisons</li>
 * </ul>
 * 
 * <p><strong>Immutability:</strong></p>
 * <ul>
 *   <li><strong>After 0.9.9:</strong> Keys and certificate cannot be changed after setting</li>
 *   <li><strong>Thread Safety:</strong> Immutable objects are inherently thread-safe</li>
 *   <li><strong>Corruption Prevention:</strong> Protects against accidental modification</li>
 *   <li><strong>Exception:</strong> Modification attempts throw {@link IllegalStateException}</li>
 * </ul>
 * 
 * <p><strong>Migration Notes:</strong></p>
 * <ul>
 *   <li><strong>Legacy Encryption:</strong> Public key usage deprecated in favor of LeaseSet encryption</li>
 *   <li><strong>Modern Alternatives:</strong> Use EncryptedLeaseSet for proper encryption</li>
 *   <li><strong>IV Generation:</strong> LeaseSet encryption IV should not use public key bytes</li>
 * </ul>
 *
 * @author jrandom
 */
public class Destination extends KeysAndCert {

    private String _cachedB64;

    //private static final boolean STATS = true;
    private static final int CACHE_SIZE;
    private static final int MIN_CACHE_SIZE = 32;
    private static final int MAX_CACHE_SIZE = 512;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        CACHE_SIZE = (int) Math.min(MAX_CACHE_SIZE, Math.max(MIN_CACHE_SIZE, maxMemory / 512*1024));
        //if (STATS)
        //    I2PAppContext.getGlobalContext().statManager().createRateStat("DestCache", "Hit rate", "Router", new long[] { RateConstants.TEN_MINUTES });
    }

    private static final Map<SigningPublicKey, Destination> _cache = new LHMCache<SigningPublicKey, Destination>(CACHE_SIZE);

    /**
     * Pull from cache or return new
     * @since 0.9.9
     */
    public static Destination create(InputStream in) throws DataFormatException, IOException {
        PublicKey pk = PublicKey.create(in);
        SigningPublicKey sk = SigningPublicKey.create(in);
        Certificate c = Certificate.create(in);
        byte[] padding;
        if (c.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            // convert SPK to new SPK and padding
            // EncTypes 1-3 allowed in Destinations, see proposal 145
            KeyCertificate kcert = c.toKeyCertificate();
            byte[] pad1 = pk.getPadding(kcert);
            byte[] pad2 = sk.getPadding(kcert);
            padding = combinePadding(pad1, pad2);
            pk = pk.toTypedKey(kcert);
            sk = sk.toTypedKey(kcert);
            c = kcert;
        } else {
            padding = null;
        }
        Destination rv;
        synchronized(_cache) {
            rv = _cache.get(sk);
            if (rv != null && rv.getPublicKey().equals(pk) && rv.getCertificate().equals(c) &&
                DataHelper.eq(rv.getPadding(), padding)) {
                //if (STATS)
                //    I2PAppContext.getGlobalContext().statManager().addRateData("DestCache", 1);
                return rv;
            }
            //if (STATS)
            //    I2PAppContext.getGlobalContext().statManager().addRateData("DestCache", 0);
            rv = new Destination(pk, sk, c, padding);
            _cache.put(sk, rv);
        }
        return rv;
    }

    public Destination() {}

    /**
     * alternative constructor which takes a base64 string representation
     * @param s a Base64 representation of the destination, as (eg) is used in hosts.txt
     */
    public Destination(String s) throws DataFormatException {
        fromBase64(s);
    }

    /**
     * @since 0.9.9
     */
    private Destination(PublicKey pk, SigningPublicKey sk, Certificate c, byte[] padding) {
        if (padding != null) {
            int sz = pk.length() + sk.length() + padding.length;
            if (sz != 384)
                throw new IllegalArgumentException("Bad total length " + sz);
        }
        _publicKey = pk;
        _signingKey = sk;
        _certificate = c;
        setPadding(padding);
    }

    /**
     *  Deprecated, used only by Packet.java in streaming.
     *  Broken for sig types P521 and RSA before 0.9.15
     *  @return the written length (NOT the new offset)
     */
    public int writeBytes(byte target[], int offset) {
        int cur = offset;
        System.arraycopy(_publicKey.getData(), 0, target, cur, PublicKey.KEYSIZE_BYTES);
        cur += PublicKey.KEYSIZE_BYTES;
        cur = writePaddingBytes(target, cur);
        int spkTrunc = Math.min(SigningPublicKey.KEYSIZE_BYTES, _signingKey.length());
        System.arraycopy(_signingKey.getData(), 0, target, cur, spkTrunc);
        cur += spkTrunc;
        cur += _certificate.writeBytes(target, cur);
        return cur - offset;
    }

    /**
      * deprecated was used only by Packet.java in streaming, now unused
      * Warning - used by i2p-bote. Does NOT support alternate key types. DSA-SHA1 only.
      *
      * @deprecated This method does not support alternate key types.
      * @throws IllegalStateException if data already set
      */
    @Deprecated
    public int readBytes(byte source[], int offset) throws DataFormatException {
        if (source == null) throw new DataFormatException("Null source");
        if (source.length <= offset + PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES)
            throw new DataFormatException("Not enough data (len=" + source.length + " off=" + offset + ")");
        if (_publicKey != null || _signingKey != null || _certificate != null)
            throw new IllegalStateException();
        int cur = offset;

        _publicKey = PublicKey.create(source, cur);
        cur += PublicKey.KEYSIZE_BYTES;

        _signingKey = SigningPublicKey.create(source, cur);
        cur += SigningPublicKey.KEYSIZE_BYTES;

        _certificate = Certificate.create(source, cur);
        cur += _certificate.size();

        return cur - offset;
    }

    public int size() {
        int rv = PublicKey.KEYSIZE_BYTES + _signingKey.length();
        if (_certificate.getCertificateType() == Certificate.CERTIFICATE_TYPE_KEY) {
            // cert data included in keys
            rv += 7;
            if (_paddingBlocks > 1) {
                rv += 32 * _paddingBlocks;
            } else {
                byte[] padding = getPadding();
                if (padding != null)
                    rv += padding.length;
            }
        } else {
            rv += _certificate.size();
        }
        return rv;
    }

    /**
     *  Cache it.
     *  Useful in I2PTunnelHTTPServer where it is added to the headers
     *  @since 0.9.9
     */
    @Override
    public String toBase64() {
        if (_cachedB64 == null)
            _cachedB64 = super.toBase64();
        return _cachedB64;
    }

    /**
     *  For convenience.
     *  @return "{52 chars}.b32.i2p" or null if fields not set.
     *  @since 0.9.14
     */
    public String toBase32() {
        try {
            return getHash().toBase32();
        } catch (IllegalStateException ise) {
            return null;
        }
    }

    /**
     *  Clear the cache.
     *  @since 0.9.9
     */
    public static void clearCache() {
        synchronized(_cache) {
            _cache.clear();
        }
    }

    @Override
    public boolean equals(Object o) {
        return super.equals(o) && (o instanceof Destination);
    }

    @Override
    public int hashCode() {
        // findbugs
        return super.hashCode();
    }
}
