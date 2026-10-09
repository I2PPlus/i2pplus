package net.i2p.crypto.eddsa;

import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.math.ScalarOps;
import net.i2p.crypto.eddsa.math.bigint.BigIntegerLittleEndianEncoding;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * EdDSA signature engine implementing the Java Signature API.
 * Supports both standard streaming and one-shot operation modes for efficiency.
 *
 * @author str4d
 * @since 0.9.15
 */
public class EdDSAEngine extends Signature {
    /** The signature algorithm name. */
    public static final String SIGNATURE_ALGORITHM = "NONEwithEdDSA";

    /** The message digest used for signing/verification. */
    protected MessageDigest digest;
    private ByteArrayOutputStream baos;
    private EdDSAKey key;
    private boolean oneShotMode;
    private byte[] oneShotBytes;
    private int oneShotOffset;
    private int oneShotLength;

    /**
     * To efficiently sign or verify data in one shot, pass this to setParameters()
     * after initSign() or initVerify() but BEFORE THE FIRST AND ONLY
     * update(data) or update(data, off, len). The data reference will be saved
     * and then used in sign() or verify() without copying the data.
     * Violate these rules and you will get a SignatureException.
     *
     * @since 0.9.25
     */
    public static final AlgorithmParameterSpec ONE_SHOT_MODE = new OneShotSpec();

    private static final BigIntegerLittleEndianEncoding _ble = new BigIntegerLittleEndianEncoding();

    /**
     * {@link EdDSABlinding#ORDER} as 32 little-endian bytes, so that the
     * RFC 8032 range check on S can be done by subtraction instead of building
     * a BigInteger. Verified against BigInteger.compareTo().
     */
    private static final byte[] ORDER_LITTLE_ENDIAN = {
        (byte) 0xed, (byte) 0xd3, (byte) 0xf5, (byte) 0x5c,
        (byte) 0x1a, (byte) 0x63, (byte) 0x12, (byte) 0x58,
        (byte) 0xd6, (byte) 0x9c, (byte) 0xf7, (byte) 0xa2,
        (byte) 0xde, (byte) 0xf9, (byte) 0xde, (byte) 0x14,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10
    };

    /**
     * Reusable buffer for the S half of a signature, to avoid allocating a
     * fresh array for every verification. Only valid within one call, and
     * never retained by any callee. Like the digest, baos and oneShotBytes
     * state this class already keeps, it assumes the instance is used by one
     * thread at a time - callers that care create a fresh engine per
     * operation.
     */
    private byte[] sBuf;

    private static class OneShotSpec implements AlgorithmParameterSpec {}

    /**
     * No specific EdDSA-internal hash requested, allows any EdDSA key.
     */
    public EdDSAEngine() {
        super(SIGNATURE_ALGORITHM);
    }

    /**
     * Specific EdDSA-internal hash requested, only matching keys will be allowed.
     *
     * @param digest the hash algorithm that keys must have to sign or verify.
     */
    public EdDSAEngine(MessageDigest digest) {
        this();
        this.digest = digest;
    }

    /**
     * @since 0.9.25
     */
    private void reset() {
        if (digest != null) digest.reset();
        if (baos != null) baos.reset();
        oneShotMode = false;
        oneShotBytes = null;
    }

    @Override
    protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
        reset();
        if (privateKey instanceof EdDSAPrivateKey) {
            EdDSAPrivateKey privKey = (EdDSAPrivateKey) privateKey;
            key = privKey;

            if (digest == null) {
                // Instantiate the digest from the key parameters
                try {
                    digest = MessageDigest.getInstance(key.getParams().getHashAlgorithm());
                } catch (NoSuchAlgorithmException e) {
                    throw new InvalidKeyException("cannot get required digest " + key.getParams().getHashAlgorithm() + " for private key.");
                }
            } else if (!key.getParams().getHashAlgorithm().equals(digest.getAlgorithm())) throw new InvalidKeyException("Key hash algorithm does not match chosen digest");
            digestInitSign(privKey);
        } else {
            throw new InvalidKeyException("cannot identify EdDSA private key: " + privateKey.getClass());
        }
    }

    /**
     * Initialize the digest for signing.
     *
     * @param privKey the private key
     */
    protected void digestInitSign(EdDSAPrivateKey privKey) {
        // Preparing for hash
        // r = H(h_b,...,h_2b-1,M)
        int b = privKey.getParams().getCurve().getField().getb();
        digest.update(privKey.getH(), b / 8, b / 4 - b / 8);
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        reset();
        if (publicKey instanceof EdDSAPublicKey) {
            key = (EdDSAPublicKey) publicKey;

            if (digest == null) {
                // Instantiate the digest from the key parameters
                try {
                    digest = MessageDigest.getInstance(key.getParams().getHashAlgorithm());
                } catch (NoSuchAlgorithmException e) {
                    throw new InvalidKeyException("cannot get required digest " + key.getParams().getHashAlgorithm() + " for private key.");
                }
            } else if (!key.getParams().getHashAlgorithm().equals(digest.getAlgorithm())) throw new InvalidKeyException("Key hash algorithm does not match chosen digest");
        } else if (publicKey.getFormat().equals("X.509")) {
            // X509Certificate will sometimes contain an X509Key rather than the EdDSAPublicKey itself; the contained
            // key is valid but needs to be instanced as an EdDSAPublicKey before it can be used.
            EdDSAPublicKey parsedPublicKey;
            try {
                parsedPublicKey = new EdDSAPublicKey(new X509EncodedKeySpec(publicKey.getEncoded()));
            } catch (InvalidKeySpecException ex) {
                throw new InvalidKeyException("cannot handle X.509 EdDSA public key: " + publicKey.getAlgorithm());
            }
            engineInitVerify(parsedPublicKey);
        } else {
            throw new InvalidKeyException("cannot identify EdDSA public key: " + publicKey.getClass());
        }
    }

    /**
     * Update the signature with one byte.
     *
     * @throws SignatureException if in one-shot mode
     */
    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        if (oneShotMode) throw new SignatureException("unsupported in one-shot mode");
        if (baos == null) baos = new ByteArrayOutputStream(256);
        baos.write(b);
    }

    /**
     * Update the signature with the given bytes.
     *
     * @throws SignatureException if one-shot rules are violated
     */
    @Override
    protected void engineUpdate(byte[] b, int off, int len) throws SignatureException {
        if (oneShotMode) {
            if (oneShotBytes != null) throw new SignatureException("update() already called");
            oneShotBytes = b;
            oneShotOffset = off;
            oneShotLength = len;
        } else {
            if (baos == null) baos = new ByteArrayOutputStream(256);
            baos.write(b, off, len);
        }
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
        try {
            return x_engineSign();
        } finally {
            reset();
            // must leave the object ready to sign again with
            // the same key, as required by the API
            EdDSAPrivateKey privKey = (EdDSAPrivateKey) key;
            digestInitSign(privKey);
        }
    }

    private byte[] x_engineSign() throws SignatureException {
        Curve curve = key.getParams().getCurve();
        ScalarOps sc = key.getParams().getScalarOps();
        byte[] a = ((EdDSAPrivateKey) key).geta();

        byte[] message;
        int offset;
        int length;
        if (oneShotMode) {
            if (oneShotBytes == null) throw new SignatureException("update() not called first");
            message = oneShotBytes;
            offset = oneShotOffset;
            length = oneShotLength;
        } else {
            if (baos == null) message = new byte[0];
            else message = baos.toByteArray();
            offset = 0;
            length = message.length;
        }
        // r = H(h_b,...,h_2b-1,M)
        digest.update(message, offset, length);
        byte[] r = digest.digest();

        // r mod l
        // Reduces r from 64 bytes to 32 bytes
        r = sc.reduce(r);

        // R = rB
        GroupElement rPoint = key.getParams().getB().scalarMultiply(r);
        byte[] rByte = rPoint.toByteArray();

        // S = (r + H(Rbar,Abar,M)*a) mod l
        digest.update(rByte);
        digest.update(((EdDSAPrivateKey) key).getAbyte());
        digest.update(message, offset, length);
        byte[] h = digest.digest();
        h = sc.reduce(h);
        byte[] s = sc.multiplyAndAdd(h, a, r);

        // R+S
        int b = curve.getField().getb();
        ByteBuffer out = ByteBuffer.allocate(b / 4);
        out.put(rByte).put(s);
        return out.array();
    }

    @Override
    protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
        try {
            return x_engineVerify(sigBytes);
        } finally {
            reset();
        }
    }

    private boolean x_engineVerify(byte[] sigBytes) throws SignatureException {
        Curve curve = key.getParams().getCurve();
        int b = curve.getField().getb();
        if (sigBytes.length != b / 4) throw new SignatureException("signature length is wrong");

        // R is first b/8 bytes of sigBytes, S is second b/8 bytes
        digest.update(sigBytes, 0, b / 8);
        digest.update(((EdDSAPublicKey) key).getAbyte());
        // h = H(Rbar,Abar,M)
        byte[] message;
        int offset;
        int length;
        if (oneShotMode) {
            if (oneShotBytes == null) throw new SignatureException("update() not called first");
            message = oneShotBytes;
            offset = oneShotOffset;
            length = oneShotLength;
        } else {
            if (baos == null) message = new byte[0];
            else message = baos.toByteArray();
            offset = 0;
            length = message.length;
        }
        digest.update(message, offset, length);
        byte[] h = digest.digest();

        // h mod l
        h = key.getParams().getScalarOps().reduce(h);

        int slen = b / 4 - b / 8;
        if (sBuf == null || sBuf.length != slen) {
            sBuf = new byte[slen];
        }
        byte[] sByte = sBuf;
        System.arraycopy(sigBytes, b / 8, sByte, 0, slen);
        // RFC 8032
        if (b == 256) {
            if (!isBelowOrder(sByte)) return false;
        } else if (_ble.toBigInteger(sByte).compareTo(EdDSABlinding.ORDER) >= 0) {
            // wider curves have a different order, so keep the BigInteger check
            return false;
        }

        // R = SB - H(Rbar,Abar,M)A
        GroupElement rPoint = key.getParams().getB().doubleScalarMultiplyVariableTime(((EdDSAPublicKey) key).getNegativeA(), h, sByte);

        // Variable time. This should be okay, because there are no secret
        // values used anywhere in verification.
        byte[] rCalc = rPoint.toByteArray();
        for (int i = 0; i < rCalc.length; i++) {
            if (rCalc[i] != sigBytes[i]) return false;
        }
        return true;
    }

    /**
     * Is the 32 byte little-endian value in {@code s} strictly less than
     * {@link EdDSABlinding#ORDER}?
     *
     * Equivalent to {@code _ble.toBigInteger(s).compareTo(EdDSABlinding.ORDER) < 0}
     * without allocating a BigInteger and a reversed copy of the input. This is
     * a fixed 32 iteration borrow chain: every byte is read and every byte is
     * subtracted, and the only carry is folded into the next iteration with
     * arithmetic, so there is no data-dependent branch or memory access. That
     * makes it strictly better than BigInteger.compareTo(), which exits early
     * on the first differing magnitude word. A short-circuiting byte compare
     * would be a timing oracle here; DataHelper.eq()/MessageDigest.isEqual()
     * are equality tests and cannot express an ordering, so they don't apply.
     *
     * @param s the value to test, 32 bytes, little-endian
     * @return true if s is less than the group order
     */
    private static boolean isBelowOrder(byte[] s) {
        int borrow = 0;
        for (int i = 0; i < 32; i++) {
            int diff = (s[i] & 0xff) - (ORDER_LITTLE_ENDIAN[i] & 0xff) - borrow;
            // negative diff means we owe a borrow to the next byte
            borrow = (diff >> 31) & 1;
        }
        return borrow == 1;
    }

    /**
     * To efficiently sign all the data in one shot, if it is available,
     * use this method, which will avoid copying the data.
     *
     * Same as:
     * <pre>
     * setParameter(ONE_SHOT_MODE)
     * update(data)
     * sig = sign()
     * </pre>
     *
     * @param data the message to be signed
     * @return the signature
     * @throws SignatureException if update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    public byte[] signOneShot(byte[] data) throws SignatureException {
        return signOneShot(data, 0, data.length);
    }

    /**
     * To efficiently sign all the data in one shot, if it is available,
     * use this method, which will avoid copying the data.
     *
     * Same as:
     * <pre>
     * setParameter(ONE_SHOT_MODE)
     * update(data, off, len)
     * sig = sign()
     * </pre>
     *
     * @param data byte array containing the message to be signed
     * @param off the start of the message inside data
     * @param len the length of the message
     * @return the signature
     * @throws SignatureException if update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    public byte[] signOneShot(byte[] data, int off, int len) throws SignatureException {
        oneShotMode = true;
        update(data, off, len);
        return sign();
    }

    /**
     * To efficiently verify all the data in one shot, if it is available,
     * use this method, which will avoid copying the data.
     *
     * Same as:
     * <pre>
     * setParameter(ONE_SHOT_MODE)
     * update(data)
     * ok = verify(signature)
     * </pre>
     *
     * @param data the message that was signed
     * @param signature of the message
     * @return true if the signature is valid, false otherwise
     * @throws SignatureException if update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    public boolean verifyOneShot(byte[] data, byte[] signature) throws SignatureException {
        return verifyOneShot(data, 0, data.length, signature, 0, signature.length);
    }

    /**
     * To efficiently verify all the data in one shot, if it is available,
     * use this method, which will avoid copying the data.
     *
     * Same as:
     * <pre>
     * setParameter(ONE_SHOT_MODE)
     * update(data, off, len)
     * ok = verify(signature)
     * </pre>
     *
     * @param data byte array containing the message that was signed
     * @param off the start of the message inside data
     * @param len the length of the message
     * @param signature of the message
     * @return true if the signature is valid, false otherwise
     * @throws SignatureException if update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    public boolean verifyOneShot(byte[] data, int off, int len, byte[] signature) throws SignatureException {
        return verifyOneShot(data, off, len, signature, 0, signature.length);
    }

    /**
     * To efficiently verify all the data in one shot, if it is available,
     * use this method, which will avoid copying the data.
     *
     * Same as:
     * <pre>
     * setParameter(ONE_SHOT_MODE)
     * update(data)
     * ok = verify(signature, sigoff, siglen)
     * </pre>
     *
     * @param data the message that was signed
     * @param signature byte array containing the signature
     * @param sigoff the start of the signature
     * @param siglen the length of the signature
     * @return true if the signature is valid, false otherwise
     * @throws SignatureException if update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    public boolean verifyOneShot(byte[] data, byte[] signature, int sigoff, int siglen) throws SignatureException {
        return verifyOneShot(data, 0, data.length, signature, sigoff, siglen);
    }

    /**
     * To efficiently verify all the data in one shot, if it is available,
     * use this method, which will avoid copying the data.
     *
     * Same as:
     * <pre>
     * setParameter(ONE_SHOT_MODE)
     * update(data, off, len)
     * ok = verify(signature, sigoff, siglen)
     * </pre>
     *
     * @param data byte array containing the message that was signed
     * @param off the start of the message inside data
     * @param len the length of the message
     * @param signature byte array containing the signature
     * @param sigoff the start of the signature
     * @param siglen the length of the signature
     * @return true if the signature is valid, false otherwise
     * @throws SignatureException if update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    public boolean verifyOneShot(byte[] data, int off, int len, byte[] signature, int sigoff, int siglen) throws SignatureException {
        oneShotMode = true;
        update(data, off, len);
        return verify(signature, sigoff, siglen);
    }

    /**
     * Only the one-shot mode parameter specification is supported.
     *
     * @throws InvalidAlgorithmParameterException if spec is ONE_SHOT_MODE and update() already called
     * @see #ONE_SHOT_MODE
     * @since 0.9.25
     */
    @Override
    protected void engineSetParameter(AlgorithmParameterSpec spec) throws InvalidAlgorithmParameterException {
        if (spec.equals(ONE_SHOT_MODE)) {
            if (oneShotBytes != null || (baos != null && baos.size() > 0)) throw new InvalidAlgorithmParameterException("update() already called");
            oneShotMode = true;
        } else {
            super.engineSetParameter(spec);
        }
    }

    /**
     * @deprecated replaced with <a href="#engineSetParameter(java.security.spec.AlgorithmParameterSpec)">this</a>
     */
    @Override
    @Deprecated
    protected void engineSetParameter(String param, Object value) {
        throw new UnsupportedOperationException("engineSetParameter unsupported");
    }

    /**
     * Parameter retrieval; unsupported, always throws.
     *
     * @return nothing, always throws
     * @deprecated
     */
    @Override
    @Deprecated
    protected Object engineGetParameter(String param) {
        throw new UnsupportedOperationException("engineSetParameter unsupported");
    }
}
