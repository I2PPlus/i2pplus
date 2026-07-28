package net.i2p.crypto;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.LHMCache;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;

/**
 * Comprehensive utility class for I2P signing keys and digital signatures.
 *
 * This class provides essential methods for converting between I2P's native key formats
 * and standard Java cryptographic key representations, as well as utilities for
 * signature encoding/decoding and key validation.
 *
 * <p>Key functionalities include:</p>
 * <ul>
 *   <li>Conversion between I2P and Java key formats</li>
 *   <li>Support for DSA, ECDSA, EdDSA, and RSA algorithms</li>
 *   <li>ASN.1 signature encoding and decoding</li>
 *   <li>Key validation and rectification</li>
 * </ul>
 *
 * @since 0.9.9, public since 0.9.12
 * @author I2P Project
 */
public final class SigUtil {

    private static final int FACTOR = SystemVersion.isAndroid() ? 1 : 4;
    private static final Map<SigningPublicKey, ECPublicKey> _ECPubkeyCache = new LHMCache<>(FACTOR * 8);
    private static final Map<SigningPrivateKey, ECPrivateKey> _ECPrivkeyCache = new LHMCache<>(8);
    private static final Map<SigningPublicKey, EdDSAPublicKey> _EdPubkeyCache = new LHMCache<>(FACTOR * 64);
    private static final Map<SigningPrivateKey, EdDSAPrivateKey> _EdPrivkeyCache = new LHMCache<>(FACTOR * 4);

    private SigUtil() {}

    /**
     *  Convert an I2P SigningPublicKey to a Java PublicKey, dispatching by algorithm.
     *
     *  @param pk non-null
     *  @return Java PublicKey (DSA, EC, EdDSA, or RSA)
     *  @throws GeneralSecurityException if conversion fails
     */
    public static PublicKey toJavaKey(SigningPublicKey pk) throws GeneralSecurityException {
        switch (pk.getType().getBaseAlgorithm()) {
            case DSA: return toJavaDSAKey(pk);
            case EC: return toJavaECKey(pk);
            case EdDSA: return toJavaEdDSAKey(pk);
            case RSA: return toJavaRSAKey(pk);
            default: throw new InvalidKeyException("Unsupported Key: " + pk);
        }
    }

    /**
     *  Convert an I2P SigningPrivateKey to a Java PrivateKey, dispatching by algorithm.
     *
     *  @param pk non-null
     *  @return Java PrivateKey (DSA, EC, EdDSA, or RSA)
     *  @throws GeneralSecurityException if conversion fails
     */
    public static PrivateKey toJavaKey(SigningPrivateKey pk) throws GeneralSecurityException {
        switch (pk.getType().getBaseAlgorithm()) {
            case DSA: return toJavaDSAKey(pk);
            case EC: return toJavaECKey(pk);
            case EdDSA: return toJavaEdDSAKey(pk);
            case RSA: return toJavaRSAKey(pk);
            default: throw new InvalidKeyException("Unsupported Key: " + pk);
        }
    }

    /**
     *  Use if SigType is unknown.
     *  For efficiency, use fromJavakey(pk, type) if type is known.
     *
     *  @param pk JAVA key!
     *  @return the I2P signing public key
     *  @throws InvalidKeyException on unknown type
     *  @since 0.9.18
     */
    public static SigningPublicKey fromJavaKey(PublicKey pk) throws GeneralSecurityException {
        if (pk instanceof DSAPublicKey) {
            return fromJavaKey((DSAPublicKey) pk);
        }
        if (pk instanceof ECPublicKey) {
            ECPublicKey k = (ECPublicKey) pk;
            ECParameterSpec spec = k.getParams();
            SigType type;
            if (ECConstants.equals(spec, ECConstants.P256_SPEC)) type = SigType.ECDSA_SHA256_P256;
            else if (ECConstants.equals(spec, ECConstants.P384_SPEC)) type = SigType.ECDSA_SHA384_P384;
            else if (ECConstants.equals(spec, ECConstants.P521_SPEC)) type = SigType.ECDSA_SHA512_P521;
            else throw new InvalidKeyException("Unknown EC type");
            return fromJavaKey(k, type);
        }
        if (pk instanceof EdDSAPublicKey) {
            return fromJavaKey((EdDSAPublicKey) pk, SigType.EdDSA_SHA512_Ed25519);
        }
        if (pk instanceof RSAPublicKey) {
            RSAPublicKey k = (RSAPublicKey) pk;
            int sz = k.getModulus().bitLength();
            SigType type;
            if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA256_2048.getParams()).getKeysize()) type = SigType.RSA_SHA256_2048;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA384_3072.getParams()).getKeysize()) type = SigType.RSA_SHA384_3072;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA512_4096.getParams()).getKeysize()) type = SigType.RSA_SHA512_4096;
            else throw new InvalidKeyException("Unknown RSA type");
            return fromJavaKey(k, type);
        }
        String algo = pk.getAlgorithm();
        if ("EdDSA".equals(algo)) {
            // Java 15+ EdDSA EdECKey class
            // try to convert to our class
            byte[] enc = pk.getEncoded();
            if (enc != null) {
                X509EncodedKeySpec spec = new X509EncodedKeySpec(enc);
                try {
                    EdDSAPublicKey edpk = new EdDSAPublicKey(spec);
                    return fromJavaKey(edpk, SigType.EdDSA_SHA512_Ed25519);
                } catch (GeneralSecurityException gse) { /* ignored */ }
            }
        }
        throw new InvalidKeyException("Unknown type: " + pk.getClass());
    }

    /**
     *  Use if SigType is known.
     *
     *  @param pk JAVA key!
     *  @return I2P public key
     */
    public static SigningPublicKey fromJavaKey(PublicKey pk, SigType type) throws GeneralSecurityException {
        switch (type.getBaseAlgorithm()) {
            case DSA: return fromJavaKey((DSAPublicKey) pk);
            case EC: return fromJavaKey((ECPublicKey) pk, type);
            case EdDSA: return fromJavaKey((EdDSAPublicKey) pk, type);
            case RSA: return fromJavaKey((RSAPublicKey) pk, type);
            default: throw new InvalidKeyException("Unknown type: " + type);
        }
    }

    /**
     *  Use if SigType is unknown.
     *  For efficiency, use fromJavakey(pk, type) if type is known.
     *
     *  @param pk JAVA key!
     *  @return the I2P signing private key
     *  @throws InvalidKeyException on unknown type
     *  @since 0.9.18
     */
    public static SigningPrivateKey fromJavaKey(PrivateKey pk) throws GeneralSecurityException {
        if (pk instanceof DSAPrivateKey) {
            return fromJavaKey((DSAPrivateKey) pk);
        }
        if (pk instanceof ECPrivateKey) {
            ECPrivateKey k = (ECPrivateKey) pk;
            ECParameterSpec spec = k.getParams();
            SigType type;
            if (ECConstants.equals(spec, ECConstants.P256_SPEC)) type = SigType.ECDSA_SHA256_P256;
            else if (ECConstants.equals(spec, ECConstants.P384_SPEC)) type = SigType.ECDSA_SHA384_P384;
            else if (ECConstants.equals(spec, ECConstants.P521_SPEC)) type = SigType.ECDSA_SHA512_P521;
            else {
                // failing on Android (ticket #2296)
                throw new InvalidKeyException("Unknown EC type: " + pk.getClass() + " spec: " + spec.getClass());
            }
            return fromJavaKey(k, type);
        }
        if (pk instanceof EdDSAPrivateKey) {
            return fromJavaKey((EdDSAPrivateKey) pk, SigType.EdDSA_SHA512_Ed25519);
        }
        if (pk instanceof RSAPrivateKey) {
            RSAPrivateKey k = (RSAPrivateKey) pk;
            int sz = k.getModulus().bitLength();
            SigType type;
            if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA256_2048.getParams()).getKeysize()) type = SigType.RSA_SHA256_2048;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA384_3072.getParams()).getKeysize()) type = SigType.RSA_SHA384_3072;
            else if (sz <= ((RSAKeyGenParameterSpec) SigType.RSA_SHA512_4096.getParams()).getKeysize()) type = SigType.RSA_SHA512_4096;
            else throw new InvalidKeyException("Unknown RSA type");
            return fromJavaKey(k, type);
        }
        String algo = pk.getAlgorithm();
        if ("EdDSA".equals(algo)) {
            // Java 15+ EdDSA EdECKey class
            // try to convert to our class
            byte[] enc = pk.getEncoded();
            if (enc != null) {
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(enc);
                try {
                    EdDSAPrivateKey edpk = new EdDSAPrivateKey(spec);
                    return fromJavaKey(edpk, SigType.EdDSA_SHA512_Ed25519);
                } catch (GeneralSecurityException gse) { /* ignored */ }
            }
        }
        throw new InvalidKeyException("Unknown type: " + pk.getClass());
    }

    /**
     *  Use if SigType is known.
     *
     *  @param pk JAVA key!
     *  @return I2P private key
     */
    public static SigningPrivateKey fromJavaKey(PrivateKey pk, SigType type) throws GeneralSecurityException {
        switch (type.getBaseAlgorithm()) {
            case DSA: return fromJavaKey((DSAPrivateKey) pk);
            case EC: return fromJavaKey((ECPrivateKey) pk, type);
            case EdDSA: return fromJavaKey((EdDSAPrivateKey) pk, type);
            case RSA: return fromJavaKey((RSAPrivateKey) pk, type);
            default: throw new InvalidKeyException("Unknown type: " + type);
        }
    }

    /**
     *  Convert an I2P EC public key to a Java ECPublicKey, with caching.
     *
     *  @param pk non-null
     *  @return Java ECPublicKey
     *  @throws GeneralSecurityException if conversion fails
     */
    public static ECPublicKey toJavaECKey(SigningPublicKey pk) throws GeneralSecurityException {
        synchronized (_ECPubkeyCache) {
            ECPublicKey rv = _ECPubkeyCache.get(pk);
            if (rv != null) return rv;
            rv = cvtToJavaECKey(pk);
            _ECPubkeyCache.put(pk, rv);
            return rv;
        }
    }

    /**
     *  Convert an I2P EC private key to a Java ECPrivateKey, with caching.
     *
     *  @param pk non-null
     *  @return Java ECPrivateKey
     *  @throws GeneralSecurityException if conversion fails
     */
    public static ECPrivateKey toJavaECKey(SigningPrivateKey pk) throws GeneralSecurityException {
        synchronized (_ECPrivkeyCache) {
            ECPrivateKey rv = _ECPrivkeyCache.get(pk);
            if (rv != null) return rv;
            rv = cvtToJavaECKey(pk);
            _ECPrivkeyCache.put(pk, rv);
            return rv;
        }
    }

    /**
     *  Convert an I2P EC public key to a Java ECPublicKey without caching.
     *  Splits the key data into affine x and y coordinates.
     *
     *  @param pk non-null
     *  @return Java ECPublicKey
     *  @throws GeneralSecurityException if conversion fails
     */
    private static ECPublicKey cvtToJavaECKey(SigningPublicKey pk) throws GeneralSecurityException {
        SigType type = pk.getType();
        BigInteger[] xy = split(pk.getData());
        ECPoint w = new ECPoint(xy[0], xy[1]);
        // see ECConstants re: casting
        ECPublicKeySpec ks = new ECPublicKeySpec(w, (ECParameterSpec) type.getParams());
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(ks);
    }

    /**
     *  Convert an I2P EC private key to a Java ECPrivateKey without caching.
     *  Extracts the scalar s from the key data.
     *
     *  @param pk non-null
     *  @return Java ECPrivateKey
     *  @throws GeneralSecurityException if conversion fails
     */
    private static ECPrivateKey cvtToJavaECKey(SigningPrivateKey pk) throws GeneralSecurityException {
        SigType type = pk.getType();
        byte[] b = pk.getData();
        BigInteger s = new BigInteger(1, b);
        // see ECConstants re: casting
        ECPrivateKeySpec ks = new ECPrivateKeySpec(s, (ECParameterSpec) type.getParams());
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPrivateKey) kf.generatePrivate(ks);
    }

    /**
     *  Convert a Java ECPublicKey to an I2P SigningPublicKey of the given type.
     *
     *  @param pk non-null
     *  @param type the I2P signature type
     *  @return I2P public key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static SigningPublicKey fromJavaKey(ECPublicKey pk, SigType type) throws GeneralSecurityException {
        ECPoint w = pk.getW();
        BigInteger x = w.getAffineX();
        BigInteger y = w.getAffineY();
        int len = type.getPubkeyLen();
        byte[] b = combine(x, y, len);
        return new SigningPublicKey(type, b);
    }

    /**
     *  Convert a Java ECPrivateKey to an I2P SigningPrivateKey of the given type.
     *
     *  @param pk non-null
     *  @param type the I2P signature type
     *  @return I2P private key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static SigningPrivateKey fromJavaKey(ECPrivateKey pk, SigType type) throws GeneralSecurityException {
        BigInteger s = pk.getS();
        int len = type.getPrivkeyLen();
        byte[] bs = rectify(s, len);
        return new SigningPrivateKey(type, bs);
    }

    /**
     *  Convert an I2P EdDSA public key to a Java EdDSAPublicKey, with caching.
     *
     *  @param pk non-null
     *  @return Java EdDSAPublicKey
     *  @since 0.9.15
     */
    public static EdDSAPublicKey toJavaEdDSAKey(SigningPublicKey pk) throws GeneralSecurityException {
        synchronized (_EdPubkeyCache) {
            EdDSAPublicKey rv = _EdPubkeyCache.get(pk);
            if (rv != null) return rv;
            rv = cvtToJavaEdDSAKey(pk);
            _EdPubkeyCache.put(pk, rv);
            return rv;
        }
    }

    /**
     *  Convert an I2P EdDSA private key to a Java EdDSAPrivateKey, with caching.
     *
     *  @param pk non-null
     *  @return Java EdDSAPrivateKey
     *  @since 0.9.15
     */
    public static EdDSAPrivateKey toJavaEdDSAKey(SigningPrivateKey pk) throws GeneralSecurityException {
        synchronized (_EdPrivkeyCache) {
            EdDSAPrivateKey rv = _EdPrivkeyCache.get(pk);
            if (rv != null) return rv;
            rv = cvtToJavaEdDSAKey(pk);
            _EdPrivkeyCache.put(pk, rv);
            return rv;
        }
    }

    /**
     *  Convert an I2P EdDSA public key to a Java EdDSAPublicKey without caching.
     *
     *  @param pk non-null
     *  @return Java EdDSAPublicKey
     *  @since 0.9.15
     */
    private static EdDSAPublicKey cvtToJavaEdDSAKey(SigningPublicKey pk) throws GeneralSecurityException {
        try {
            return new EdDSAPublicKey(new EdDSAPublicKeySpec(pk.getData(), (EdDSAParameterSpec) pk.getType().getParams()));
        } catch (IllegalArgumentException iae) {
            throw new InvalidKeyException(iae);
        }
    }

    /**
     *  Convert an I2P EdDSA or RedDSA private key to a Java EdDSAPrivateKey without caching.
     *
     *  @param pk non-null
     *  @return Java EdDSAPrivateKey
     *  @since 0.9.15
     */
    private static EdDSAPrivateKey cvtToJavaEdDSAKey(SigningPrivateKey pk) throws GeneralSecurityException {
        try {
            EdDSAParameterSpec paramspec = (EdDSAParameterSpec) pk.getType().getParams();
            EdDSAPrivateKeySpec pkspec;
            SigType type = pk.getType();
            if (type == SigType.EdDSA_SHA512_Ed25519 || type == SigType.EdDSA_SHA512_Ed25519ph) pkspec = new EdDSAPrivateKeySpec(pk.getData(), paramspec);
            else if (type == SigType.RedDSA_SHA512_Ed25519) pkspec = new EdDSAPrivateKeySpec(pk.getData(), null, paramspec);
            else throw new InvalidKeyException();
            return new EdDSAPrivateKey(pkspec);
        } catch (IllegalArgumentException iae) {
            throw new InvalidKeyException(iae);
        }
    }

    /**
     *  Convert a Java EdDSAPublicKey to an I2P SigningPublicKey.
     *
     *  @param pk non-null
     *  @param type the I2P signature type
     *  @return I2P public key
     *  @since 0.9.15
     */
    public static SigningPublicKey fromJavaKey(EdDSAPublicKey pk, SigType type) {
        return new SigningPublicKey(type, pk.getAbyte());
    }

    /**
     *  Convert a Java EdDSAPrivateKey to an I2P SigningPrivateKey.
     *  Handles EdDSA (seed-based) and RedDSA (private scalar) key types.
     *
     *  @param pk non-null
     *  @param type the I2P signature type
     *  @return I2P private key
     *  @since 0.9.15
     */
    public static SigningPrivateKey fromJavaKey(EdDSAPrivateKey pk, SigType type) throws GeneralSecurityException {
        byte[] data;
        if (type == SigType.EdDSA_SHA512_Ed25519 || type == SigType.EdDSA_SHA512_Ed25519ph) data = pk.getSeed();
        else if (type == SigType.RedDSA_SHA512_Ed25519) data = pk.geta();
        else throw new InvalidKeyException();
        return new SigningPrivateKey(type, data);
    }

    /**
     *  Convert an I2P DSA public key to a Java DSAPublicKey.
     *
     *  @param pk non-null
     *  @return Java DSAPublicKey
     *  @throws GeneralSecurityException if conversion fails
     */
    public static DSAPublicKey toJavaDSAKey(SigningPublicKey pk) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("DSA");
        // y p q g
        KeySpec ks = new DSAPublicKeySpec(new NativeBigInteger(1, pk.getData()), CryptoConstants.dsap, CryptoConstants.dsaq, CryptoConstants.dsag);
        return (DSAPublicKey) kf.generatePublic(ks);
    }

    /**
     *  Convert an I2P DSA private key to a Java DSAPrivateKey.
     *
     *  @param pk non-null
     *  @return Java DSAPrivateKey
     *  @throws GeneralSecurityException if conversion fails
     */
    public static DSAPrivateKey toJavaDSAKey(SigningPrivateKey pk) throws GeneralSecurityException {
        KeyFactory kf = KeyFactory.getInstance("DSA");
        // x p q g
        KeySpec ks = new DSAPrivateKeySpec( new BigInteger(1, pk.getData()),
                        // see cvtToJavaECKey
                        // KeySpec ks = new DSAPrivateKeySpec(new NativeBigInteger(1, pk.getData()),
                        CryptoConstants.dsap,
                        CryptoConstants.dsaq,
                        CryptoConstants.dsag);
        return (DSAPrivateKey) kf.generatePrivate(ks);
    }

    /**
     *  Convert a Java DSAPublicKey to an I2P DSA public key.
     *
     *  @param pk non-null
     *  @return I2P DSA public key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static SigningPublicKey fromJavaKey(DSAPublicKey pk) throws GeneralSecurityException {
        BigInteger y = pk.getY();
        SigType type = SigType.DSA_SHA1;
        int len = type.getPubkeyLen();
        byte[] by = rectify(y, len);
        return new SigningPublicKey(type, by);
    }

    /**
     *  Convert a Java DSAPrivateKey to an I2P DSA private key.
     *
     *  @param pk non-null
     *  @return I2P DSA private key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static SigningPrivateKey fromJavaKey(DSAPrivateKey pk) throws GeneralSecurityException {
        BigInteger x = pk.getX();
        SigType type = SigType.DSA_SHA1;
        int len = type.getPrivkeyLen();
        byte[] bx = rectify(x, len);
        return new SigningPrivateKey(type, bx);
    }

    /**
     *  Prefer toJavaKey(SigningPublicKey) for type-generic conversion.
     *
     *  @param pk non-null
     *  @return Java RSA public key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static RSAPublicKey toJavaRSAKey(SigningPublicKey pk) throws GeneralSecurityException {
        SigType type = pk.getType();
        KeyFactory kf = KeyFactory.getInstance("RSA");
        BigInteger n = new NativeBigInteger(1, pk.getData());
        BigInteger e = ((RSAKeyGenParameterSpec) type.getParams()).getPublicExponent();
        // modulus exponent
        KeySpec ks = new RSAPublicKeySpec(n, e);
        return (RSAPublicKey) kf.generatePublic(ks);
    }

    /**
     *  Convert an I2P RSA private key to a Java RSAPrivateKey.
     *  As of 0.9.31, if pk is a RSASigningPrivateCrtKey, returns a RSAPrivateCrtKey.
     *
     *  @param pk non-null
     *  @return Java RSAPrivateKey
     *  @throws GeneralSecurityException if conversion fails
     */
    public static RSAPrivateKey toJavaRSAKey(SigningPrivateKey pk) throws GeneralSecurityException {
        if (pk instanceof RSASigningPrivateCrtKey) return ((RSASigningPrivateCrtKey) pk).toJavaKey();
        KeyFactory kf = KeyFactory.getInstance("RSA");
        // private key is modulus (pubkey) + exponent
        BigInteger[] nd = split(pk.getData());
        // modulus exponent
        KeySpec ks = new RSAPrivateKeySpec(nd[0], nd[1]);
        return (RSAPrivateKey) kf.generatePrivate(ks);
    }

    /**
     *  Convert a Java RSAPublicKey to an I2P RSA public key of the given type.
     *
     *  @param pk non-null
     *  @param type the I2P signature type
     *  @return I2P RSA public key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static SigningPublicKey fromJavaKey(RSAPublicKey pk, SigType type) throws GeneralSecurityException {
        BigInteger n = pk.getModulus();
        int len = type.getPubkeyLen();
        byte[] bn = rectify(n, len);
        return new SigningPublicKey(type, bn);
    }

    /**
     *  Convert a Java RSAPrivateKey to an I2P RSA private key of the given type.
     *  As of 0.9.31, if pk is a RSAPrivateCrtKey, returns a RSASigningPrivateCrtKey.
     *
     *  @param pk non-null
     *  @param type the I2P signature type
     *  @return I2P RSA private key
     *  @throws GeneralSecurityException if conversion fails
     */
    public static SigningPrivateKey fromJavaKey(RSAPrivateKey pk, SigType type) throws GeneralSecurityException {
        // private key is modulus (pubkey) + exponent
        BigInteger n = pk.getModulus();
        BigInteger d = pk.getPrivateExponent();
        byte[] b = combine(n, d, type.getPrivkeyLen());
        if (pk instanceof RSAPrivateCrtKey) return RSASigningPrivateCrtKey.fromJavaKey((RSAPrivateCrtKey) pk);
        return new SigningPrivateKey(type, b);
    }

    /**
     *  Convert an I2P Signature to a Java ASN.1 signature byte array.
     *  RSA and EdDSA signatures are passed through unchanged.
     *
     *  @param sig non-null
     *  @return ASN.1 DER-encoded signature bytes (or raw for RSA/EdDSA)
     */
    public static byte[] toJavaSig(Signature sig) {
        // RSA and EdDSA sigs are not ASN encoded
        if (sig.getType().getBaseAlgorithm() == SigAlgo.RSA || sig.getType().getBaseAlgorithm() == SigAlgo.EdDSA) return sig.getData();
        return sigBytesToASN1(sig.getData());
    }

    /**
     *  Convert a Java ASN.1 signature byte array to an I2P Signature.
     *  RSA and EdDSA signatures are passed through unchanged.
     *
     *  @param asn ASN.1 DER-encoded signature bytes (or raw for RSA/EdDSA)
     *  @param type the I2P signature type
     *  @return an I2P Signature with the given type
     *  @throws SignatureException if ASN.1 decoding fails
     */
    public static Signature fromJavaSig(byte[] asn, SigType type) throws SignatureException {
        // RSA and EdDSA sigs are not ASN encoded
        if (type.getBaseAlgorithm() == SigAlgo.RSA || type.getBaseAlgorithm() == SigAlgo.EdDSA) return new Signature(type, asn);
        return new Signature(type, aSN1ToSigBytes(asn, type.getSigLen()));
    }

    /**
     *  Import a Java X.509-encoded public key from a file.
     *
     *  @param file non-null, containing X.509 encoded key data
     *  @param type the I2P signature type
     *  @return Java PublicKey
     *  @throws GeneralSecurityException if key conversion fails
     *  @throws IOException if file reading fails
     */
    public static PublicKey importJavaPublicKey(File file, SigType type) throws GeneralSecurityException, IOException {
        byte[] data = getData(file);
        KeySpec ks = new X509EncodedKeySpec(data);
        String algo = type.getBaseAlgorithm().getName();
        KeyFactory kf = KeyFactory.getInstance(algo);
        return kf.generatePublic(ks);
    }

    /**
     *  Import a Java PKCS8-encoded private key from a file.
     *
     *  @param file non-null, containing PKCS8 encoded key data
     *  @param type the I2P signature type
     *  @return Java PrivateKey
     *  @throws GeneralSecurityException if key conversion fails
     *  @throws IOException if file reading fails
     */
    public static PrivateKey importJavaPrivateKey(File file, SigType type) throws GeneralSecurityException, IOException {
        byte[] data = getData(file);
        KeySpec ks = new PKCS8EncodedKeySpec(data);
        String algo = type.getBaseAlgorithm().getName();
        KeyFactory kf = KeyFactory.getInstance(algo);
        return kf.generatePrivate(ks);
    }

    /** 16 KB max */
    private static byte[] getData(File file) throws IOException {
        byte[] buf = new byte[1024];
        InputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try {
            in = new FileInputStream(file);
            int read = 0;
            int tot = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                tot += read;
                if (tot > 16 * 1024) throw new IOException("too big");
            }
            return out.toByteArray();
        } finally {
            if (in != null) try {
                    in.close();
                } catch (IOException ioe) { /* ignored */ }
        }
    }

    /**
     *  Split a byte array into two BigIntegers
     *
     *  @param b length must be even
     *  @return array of two BigIntegers
     *  @since 0.9.9
     */
    private static NativeBigInteger[] split(byte[] b) {
        if ((b.length & 0x01) != 0) throw new IllegalArgumentException("length must be even");
        int sublen = b.length / 2;
        byte[] bx = new byte[sublen];
        byte[] by = new byte[sublen];
        System.arraycopy(b, 0, bx, 0, sublen);
        System.arraycopy(b, sublen, by, 0, sublen);
        NativeBigInteger x = new NativeBigInteger(1, bx);
        NativeBigInteger y = new NativeBigInteger(1, by);
        return new NativeBigInteger[] {x, y};
    }

    /**
     *  Combine two BigIntegers of nominal length = len / 2
     *
     *  @param x non-negative first value
     *  @param y non-negative second value
     *  @param len total output length (must be even)
     *  @return array of exactly len bytes
     *  @throws InvalidKeyException if length is odd or either value is too large
     *  @since 0.9.9, package private since 0.9.31
     */
    static byte[] combine(BigInteger x, BigInteger y, int len) throws InvalidKeyException {
        if ((len & 0x01) != 0) throw new InvalidKeyException("length must be even");
        int sublen = len / 2;
        byte[] b = new byte[len];
        byte[] bx = rectify(x, sublen);
        byte[] by = rectify(y, sublen);
        System.arraycopy(bx, 0, b, 0, sublen);
        System.arraycopy(by, 0, b, sublen, sublen);
        return b;
    }

    /**
     *  Convert a BigInteger to a fixed-length byte array, trimming or zero-padding as needed.
     *
     *  @param bi non-negative
     *  @param len desired output length
     *  @return array of exactly len bytes
     *  @throws InvalidKeyException if the value is negative or too large to fit
     */
    public static byte[] rectify(BigInteger bi, int len) throws InvalidKeyException {
        byte[] b = bi.toByteArray();
        if (b.length == len) {
            // just right
            return b;
        }
        if (b.length > len + 1) throw new InvalidKeyException("key too big (" + b.length + ") max is " + (len + 1));
        byte[] rv = new byte[len];
        if (b.length == 0) return rv;
        if ((b[0] & 0x80) != 0) throw new InvalidKeyException("negative");
        if (b.length > len) {
            // leading 0 byte
            if (b[0] != 0) throw new InvalidKeyException("key too big (" + b.length + ") max is " + len);
            System.arraycopy(b, 1, rv, 0, len);
        } else {
            // smaller
            System.arraycopy(b, 0, rv, len - b.length, b.length);
        }
        return rv;
    }

    /**
     *  Encode raw signature bytes (r || s) into ASN.1 DER SEQUENCE format.
     *
     *  See http://download.oracle.com/javase/1.5.0/docs/guide/security/CryptoSpec.html
     *
     *  Convert to BigInteger and back so we have the minimum length representation, as required.
     *  r and s are always non-negative.
     *
     *  Only supports sigs up to about 252 bytes. See code to fix BER encoding for this before you
     *  add a SigType with bigger signatures.
     *
     *  @param sig raw r||s bytes, length must be even
     *  @return ASN.1 DER-encoded SEQUENCE { r INTEGER, s INTEGER }
     *  @throws IllegalArgumentException if length is odd or encoded size exceeds limits
     *  @since 0.8.7, moved to SigUtil in 0.9.9
     */
    private static byte[] sigBytesToASN1(byte[] sig) {
        BigInteger[] rs = split(sig);
        return sigBytesToASN1(rs[0], rs[1]);
    }

    /**
     *  http://download.oracle.com/javase/1.5.0/docs/guide/security/CryptoSpec.html
     *<pre>
     *  Signature Format: ASN.1 sequence of two INTEGER values: r and s, in that order:
     *                                SEQUENCE ::= { r INTEGER, s INTEGER }
     *
     *  http://en.wikipedia.org/wiki/Abstract_Syntax_Notation_One
     *  30 -- tag indicating SEQUENCE
     *  xx - length in octets
     *
     *  02 -- tag indicating INTEGER
     *  xx - length in octets
     *  xxxxxx - value
     *</pre>
     *
     *  Encode two BigInteger values (r, s) into ASN.1 DER SEQUENCE format.
     *  r and s are always non-negative.
     *
     *  Only supports sigs up to about 65530 bytes. See code to fix BER encoding for this before you
     *  add a SigType with bigger signatures.
     *
     *  @param r non-negative
     *  @param s non-negative
     *  @return ASN.1 DER-encoded SEQUENCE { r INTEGER, s INTEGER }
     *  @throws IllegalArgumentException if too big
     *  @since 0.9.25, split out from sigBytesToASN1(byte[])
     */
    public static byte[] sigBytesToASN1(BigInteger r, BigInteger s) {
        int extra = 4;
        byte[] rb = r.toByteArray();
        if (rb.length > 127) {
            extra++;
            if (rb.length > 255) extra++;
        }
        byte[] sb = s.toByteArray();
        if (sb.length > 127) {
            extra++;
            if (sb.length > 255) extra++;
        }
        int seqlen = rb.length + sb.length + extra;
        int totlen = seqlen + 2;
        if (seqlen > 127) {
            totlen++;
            if (seqlen > 255) totlen++;
        }
        byte[] rv = new byte[totlen];
        int idx = 0;

        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seqlen);

        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, rb.length);
        System.arraycopy(rb, 0, rv, idx, rb.length);
        idx += rb.length;

        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, sb.length);
        System.arraycopy(sb, 0, rv, idx, sb.length);

        return rv;
    }

    /**
     *  Output an length or integer value in ASN.1
     *  Does NOT output the tag e.g. 0x02 / 0x30
     *
     *  @param val 0-65535
     *  @return the new index
     *  @since 0.9.25
     */
    public static int intToASN1(byte[] d, int idx, int val) {
        if (val < 0 || val > 65535) throw new IllegalArgumentException("fixme length " + val);
        if (val > 127) {
            if (val > 255) {
                d[idx++] = (byte) 0x82;
                d[idx++] = (byte) (val >> 8);
            } else {
                d[idx++] = (byte) 0x81;
            }
        }
        d[idx++] = (byte) val;
        return idx;
    }

    /**
     *  See above.
     *  Only supports sigs up to about 65530 bytes. See code to fix BER encoding for bigger than that.
     *
     *  @param len must be even, twice the nominal length of each BigInteger
     *  @return len bytes, call split() on the result to get two BigIntegers
     *  @since 0.8.7, moved to SigUtil in 0.9.9
     */
    private static byte[] aSN1ToSigBytes(byte[] asn, int len) throws SignatureException {
        if (asn[0] != 0x30) throw new SignatureException("asn[0] = " + (asn[0] & 0xff));
        // handles total len > 127
        int idx = 2;
        if ((asn[1] & 0x80) != 0) idx += asn[1] & 0x7f;
        if (asn[idx] != 0x02) throw new SignatureException("asn[2] = " + (asn[idx] & 0xff));
        byte[] rv = new byte[len];
        int sublen = len / 2;
        int rlen = asn[++idx];
        if ((rlen & 0x80) != 0) {
            if ((rlen & 0xff) == 0x81) {
                rlen = asn[++idx] & 0xff;
            } else if ((rlen & 0xff) == 0x82) {
                rlen = asn[++idx] & 0xff;
                rlen <<= 8;
                rlen |= asn[++idx] & 0xff;
            } else {
                throw new SignatureException("FIXME R length > 65535");
            }
        }
        if ((asn[++idx] & 0x80) != 0) throw new SignatureException("R is negative");
        if (rlen > sublen + 1) throw new SignatureException("R too big " + rlen);
        if (rlen == sublen + 1) System.arraycopy(asn, idx + 1, rv, 0, sublen);
        else System.arraycopy(asn, idx, rv, sublen - rlen, rlen);
        idx += rlen;

        if (asn[idx] != 0x02) throw new SignatureException("asn[s] = " + (asn[idx] & 0xff));
        int slen = asn[++idx];
        if ((slen & 0x80) != 0) {
            if ((slen & 0xff) == 0x81) {
                slen = asn[++idx] & 0xff;
            } else if ((slen & 0xff) == 0x82) {
                slen = asn[++idx] & 0xff;
                slen <<= 8;
                slen |= asn[++idx] & 0xff;
            } else {
                throw new SignatureException("FIXME S length > 65535");
            }
        }
        if ((asn[++idx] & 0x80) != 0) throw new SignatureException("S is negative");
        if (slen > sublen + 1) throw new SignatureException("S too big " + slen);
        if (slen == sublen + 1) System.arraycopy(asn, idx + 1, rv, sublen, sublen);
        else System.arraycopy(asn, idx, rv, len - slen, slen);
        return rv;
    }

    /**
     *  See above.
     *  Only supports sigs up to about 65530 bytes. See code to fix BER encoding for bigger than that.
     *
     *  @param len nominal length of each BigInteger
     *  @return two BigIntegers
     *  @since 0.9.25
     */
    public static NativeBigInteger[] aSN1ToBigInteger(byte[] asn, int len) throws SignatureException {
        byte[] sig = aSN1ToSigBytes(asn, len * 2);
        return split(sig);
    }

    /**
     *  Clear all cached Java key conversions.
     *  Should be called when key types or configurations change.
     */
    public static void clearCaches() {
        synchronized (_ECPubkeyCache) {
            _ECPubkeyCache.clear();
        }
        synchronized (_ECPrivkeyCache) {
            _ECPrivkeyCache.clear();
        }
        synchronized (_EdPubkeyCache) {
            _EdPubkeyCache.clear();
        }
        synchronized (_EdPrivkeyCache) {
            _EdPrivkeyCache.clear();
        }
    }
}
