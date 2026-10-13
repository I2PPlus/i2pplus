package net.i2p.crypto.elgamal.impl;

import static net.i2p.crypto.SigUtil.intToASN1;

import net.i2p.crypto.elgamal.ElGamalPublicKey;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPublicKeySpec;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

/**
 * Implementation of ElGamal public keys.
 * <p>
 * This class provides a concrete implementation of the ElGamalPublicKey interface,
 * representing the public component of an ElGamal key pair. It stores the public
 * value y = g^x mod p and the ElGamal parameters (prime p and generator g), and
 * supports X.509 encoding for standardized key storage and transmission.
 */
public class ElGamalPublicKeyImpl implements ElGamalPublicKey, DHPublicKey {
    private static final long serialVersionUID = 8712728417091216948L;

    /** The public value y = g^x mod p. */
    private BigInteger y;
    /** El spec */
    private ElGamalParameterSpec elSpec;

    /**
     * Adopts the y value and parameters of an ElGamal key spec.
     *
     * @param spec the ElGamal public key spec
     */
    public ElGamalPublicKeyImpl(ElGamalPublicKeySpec spec) {
        this.y = spec.getY();
        this.elSpec = new ElGamalParameterSpec(spec.getParams().getP(), spec.getParams().getG());
    }

    /**
     * Adopts the y value and parameters of a DH key spec.
     *
     * @param spec the DH public key spec
     */
    public ElGamalPublicKeyImpl(DHPublicKeySpec spec) {
        this.y = spec.getY();
        this.elSpec = new ElGamalParameterSpec(spec.getP(), spec.getG());
    }

    /**
     * Copies an existing ElGamal public key.
     *
     * @param key the ElGamal public key to copy
     */
    public ElGamalPublicKeyImpl(ElGamalPublicKey key) {
        this.y = key.getY();
        this.elSpec = key.getParameters();
    }

    /**
     * Copies an existing DH public key into ElGamal form.
     *
     * @param key the DH public key to copy
     */
    public ElGamalPublicKeyImpl(DHPublicKey key) {
        this.y = key.getY();
        this.elSpec = new ElGamalParameterSpec(key.getParams().getP(), key.getParams().getG());
    }

    /**
     * Builds a key from a bare public value and its parameters.
     *
     * @param y the public value y = g^x mod p
     * @param elSpec the ElGamal parameters
     */
    public ElGamalPublicKeyImpl(BigInteger y, ElGamalParameterSpec elSpec) {
        this.y = y;
        this.elSpec = elSpec;
    }

    /**
     * Rejects X.509 encoding, which is not implemented here.
     *
     * @param spec the X.509 key spec
     * @throws InvalidKeySpecException always, not yet implemented
     */
    public ElGamalPublicKeyImpl(X509EncodedKeySpec spec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException("todo");
    }

    /**
     * Names the ElGamal algorithm this key belongs to.
     * @return the JCA algorithm name for this key, always "ElGamal"
     */
    @Override
    public String getAlgorithm() {
        return "ElGamal";
    }

    /**
     * Names the X.509 encoding this key serialises to.
     * @return the JCA key format name for this key, always "X.509"
     */
    @Override
    public String getFormat() {
        return "X.509";
    }

    /**
     * Serialises p, g and y into the X.509 SubjectPublicKeyInfo form.
     * @return the X.509 SubjectPublicKeyInfo encoding, a DER sequence holding
     *         p, g and y
     */
    @Override
    public byte[] getEncoded() {
        byte[] pb = elSpec.getP().toByteArray();
        byte[] gb = elSpec.getG().toByteArray();
        byte[] yb = y.toByteArray();
        int seq3len = spaceFor(pb.length) + spaceFor(gb.length);
        int seq2len = 8 + spaceFor(seq3len);
        int seq1len = spaceFor(seq2len) + spaceFor(yb.length + 1);
        int totlen = spaceFor(seq1len);
        byte[] rv = new byte[totlen];
        int idx = 0;
        // sequence 1
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq1len);

        // Algorithm Identifier
        // sequence 2
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq2len);
        // OID: 1.3.14.7.2.1.1
        rv[idx++] = 0x06;
        rv[idx++] = 6;
        rv[idx++] = (1 * 40) + 3;
        rv[idx++] = 14;
        rv[idx++] = 7;
        rv[idx++] = 2;
        rv[idx++] = 1;
        rv[idx++] = 1;

        // params
        // sequence 3
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq3len);
        // P
        // integer
        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, pb.length);
        System.arraycopy(pb, 0, rv, idx, pb.length);
        idx += pb.length;
        // G
        // integer
        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, gb.length);
        System.arraycopy(gb, 0, rv, idx, gb.length);
        idx += gb.length;

        // the key
        // bit string
        rv[idx++] = 0x03;
        idx = intToASN1(rv, idx, yb.length + 1);
        rv[idx++] = 0; // number of trailing unused bits
        // BC puts an integer in the bit string, we're not going to do that
        System.arraycopy(yb, 0, rv, idx, yb.length);
        return rv;
    }

    /**
     * Calculate the TLV space for a value of the given length.
     *
     * @param val the length of the value, 65535 max
     * @return the length of the TLV
     */
    static int spaceFor(int val) {
        int rv;
        if (val > 255) rv = 3;
        else if (val > 127) rv = 2;
        else rv = 1;
        return 1 + rv + val;
    }

    /**
     * Fetches the ElGamal group parameters.
     * @return the ElGamal group parameters this key was built with, never null
     */
    @Override
    public ElGamalParameterSpec getParameters() {
        return elSpec;
    }

    /**
     * Wraps the same group parameters in a fresh DH spec.
     * @return a newly built DH spec carrying the same prime p and generator g
     */
    @Override
    public DHParameterSpec getParams() {
        return new DHParameterSpec(elSpec.getP(), elSpec.getG());
    }

    /**
     * The public value y = g^x mod p.
     * @return the public value y = g^x mod p, never null
     */
    @Override
    public BigInteger getY() {
        return y;
    }

    /**
     * True if the keys are equal.
     * @return true if the keys are equal
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ElGamalPublicKeyImpl)) return false;
        ElGamalPublicKeyImpl other = (ElGamalPublicKeyImpl) obj;
        return y.equals(other.y) && elSpec.getP().equals(other.elSpec.getP()) && elSpec.getG().equals(other.elSpec.getG());
    }

    /**
     * Whether h code is present.
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return y.hashCode() ^ elSpec.getP().hashCode() ^ elSpec.getG().hashCode();
    }

    /**
     * Read object
     * @param in the stream to deserialize the y value and the ElGamal parameters from
     * @throws IOException if the stream cannot be read
     * @throws ClassNotFoundException if the serialized class is not available to this router
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        this.y = (BigInteger) in.readObject();
        this.elSpec = new ElGamalParameterSpec((BigInteger) in.readObject(), (BigInteger) in.readObject());
    }

    /**
     * Write object
     * @param out the stream to serialize the y value and the ElGamal parameters to
     * @throws IOException if the stream cannot be written
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeObject(this.getY());
        out.writeObject(elSpec.getP());
        out.writeObject(elSpec.getG());
    }
}
