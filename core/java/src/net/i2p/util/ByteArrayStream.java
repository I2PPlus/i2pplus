package net.i2p.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 *  OutputStream to InputStream adapter.
 *  Zero-copy where possible. Unsynchronized.
 *  This is NOT a Pipe.
 *  Do NOT reset after writing.
 *
 *  @since 0.9.48
 */
public class ByteArrayStream extends ByteArrayOutputStream {

    /**
     * Creates a new byte array output stream.
     */
    public ByteArrayStream() {
        super();
    }

    /**
     *  Creates a new byte array output stream with the specified size.
     *
     * @param size the initial size of the internal buffer
     */
    public ByteArrayStream(int size) {
        super(size);
    }

    /**
     *  Resets this stream to the beginning.
     *  @throws IllegalStateException if previously written
     */
    @Override
    public void reset() {
        if (count > 0)
            throw new IllegalStateException();
    }

    /**
     *  Returns the byte array containing the written data.
     *  Zero-copy only if the data fills the buffer.
     *  Use asInputStream() for guaranteed zero-copy.
     * @return the byte array containing the written data
     */
    @Override
    public byte[] toByteArray() {
        if (count == buf.length)
            return buf;
        return Arrays.copyOfRange(buf, 0, count);
    }

    /**
     *  Creates an input stream from the written data.
     *  All data previously written. Zero-copy. Not a Pipe.
     *  Data written after this call will not appear.
     * @return an input stream containing the written data
     */
    public ByteArrayInputStream asInputStream() {
        return new ByteArrayInputStream(buf, 0, count);
    }

    /**
     *  Copies all written data to the target array.
     *
     * @param target the target array to copy to
     * @param offset the offset in the target array
     */
    public void copyTo(byte[] target, int offset) {
        System.arraycopy(buf, 0, target, offset, count);
    }

    /**
     *  Verifies the signature of the written data.
     *
     * @param signature the signature to verify
     * @param verifyingKey the public key to verify with
     * @return true if the signature is valid
     */
    public boolean verifySignature(Signature signature, SigningPublicKey verifyingKey) {
        return DSAEngine.getInstance().verifySignature(signature, buf, 0, count, verifyingKey);
    }

    /**
     *  Verifies the signature of the written data.
     *
     * @param ctx the application context
     * @param signature the signature to verify
     * @param verifyingKey the public key to verify with
     * @return true if the signature is valid
     */
    public boolean verifySignature(I2PAppContext ctx, Signature signature, SigningPublicKey verifyingKey) {
        return ctx.dsa().verifySignature(signature, buf, 0, count, verifyingKey);
    }

    /**
     *  Signs the written data.
     *
     * @param signingKey the private key to sign with
     * @return the signature, or null on error
     */
    public Signature sign(SigningPrivateKey signingKey) {
        return DSAEngine.getInstance().sign(buf, 0, count, signingKey);
    }

    /**
     *  Signs the written data.
     *
     * @param ctx the application context
     * @param signingKey the private key to sign with
     * @return the signature, or null on error
     */
    public Signature sign(I2PAppContext ctx, SigningPrivateKey signingKey) {
        return ctx.dsa().sign(buf, 0, count, signingKey);
    }
}
