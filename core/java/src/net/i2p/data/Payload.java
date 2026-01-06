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
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Message payload container for I2P communications with encryption support.
 * 
 * <p>Payload provides message content delivery in I2P:</p>
 * <ul>
 *   <li><strong>Message Content:</strong> Contains actual data being delivered</li>
 *   <li><strong>Encryption Support:</strong> Standard encryption wrapping per I2P spec</li>
 *   <li><strong>I2CP Protocol:</strong> Primarily used in client-router communication</li>
 *   <li><strong>Legacy Feature:</strong> Previously supported end-to-end encryption</li>
 * </ul>
 * 
 * <p><strong>Historical Context:</strong></p>
 * <ul>
 *   <li><strong>End-to-End Encryption:</strong> Previously encrypted messages between clients</li>
 *   <li><strong>Protocol Change:</strong> End-to-end encryption removed in I2P</li>
 *   <li><strong>Current Usage:</strong> Use get/setEncryptedData() methods instead</li>
 *   <li><strong>I2CP Focus:</strong> Now mainly used for I2CP message transport</li>
 * </ul>
 * 
 * <p><strong>Current Structure:</strong></p>
 * <ul>
 *   <li><strong>Encrypted Data:</strong> Primary payload content for transmission</li>
 *   <li><strong>Unencrypted Data:</strong> Legacy support for decrypted content access</li>
 *   <li><strong>Size Limits:</strong> Maximum 64KB to prevent OOM attacks</li>
 *   <li><strong>Format Compliance:</strong> Follows I2P data structure specification</li>
 * </ul>
 * 
 * <p><strong>Usage Patterns:</strong></p>
 * <ul>
 *   <li><strong>Modern Usage:</strong> Use getEncryptedData()/setEncryptedData() for payload</li>
 *   <li><strong>I2CP Messages:</strong> Transport messages between client and router</li>
 *   <li><strong>Legacy Support:</strong> getUnencryptedData() for backward compatibility</li>
 *   <li><strong>Size Management:</strong> Monitor payload size to prevent resource exhaustion</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Size Validation:</strong> Enforce 64KB maximum payload size</li>
 *   <li><strong>Memory Protection:</strong> Prevent OOM attacks through large payloads</li>
 *   <li><strong>Content Validation:</strong> Verify payload integrity and format</li>
 *   <li><strong>Encryption Context:</strong> Understand current encryption model in I2P</li>
 * </ul>
 * 
 * <p><strong>Performance Aspects:</strong></p>
 * <ul>
 *   <li><strong>Efficient Storage:</strong> Minimal overhead for payload data</li>
 *   <li><strong>Fast Access:</strong> Direct byte array access methods</li>
 *   <li><strong>Memory Management:</strong> Careful allocation and cleanup</li>
 *   <li><strong>Size Checking:</strong> Quick validation of payload limits</li>
 * </ul>
 * 
 * <p><strong>Migration Notes:</strong></p>
 * <ul>
 *   <li><strong>From End-to-End:</strong> Previously handled client-to-client encryption</li>
 *   <li><strong>To Transport Only:</strong> Now focuses on message transport</li>
 *   <li><strong>API Simplification:</strong> Use encrypted data methods for clarity</li>
 *   <li><strong>Backward Compatibility:</strong> Legacy methods still available</li>
 * </ul>
 *
 * @author jrandom
 */
public class Payload extends DataStructureImpl {
    //private final static Log _log = new Log(Payload.class);
    private byte[] _encryptedData;
    private byte[] _unencryptedData;

    /** So we don't OOM on I2CP protocol errors. Actual max is smaller. */
    private static final int MAX_LENGTH = 64*1024;

    public Payload() {
    }

    /**
     * Retrieve the unencrypted body of the message.
     *
     * Deprecated.
     * Unless you are doing encryption, use getEncryptedData() instead.
     *
     * @return body of the message, or null if the message has either not been
     *          decrypted yet or if the hash is not correct
     */
    public byte[] getUnencryptedData() {
        return _unencryptedData;
    }

    /**
     * Populate the message body with data.  This does not automatically encrypt
     * yet.
     *
     * Deprecated.
     * Unless you are doing encryption, use setEncryptedData() instead.
     * @throws IllegalArgumentException if bigger than 64KB
     */
    public void setUnencryptedData(byte[] data) {
        if (data.length > MAX_LENGTH)
            throw new IllegalArgumentException();
        _unencryptedData = data;
    }

    /** the real data */
    public byte[] getEncryptedData() {
        return _encryptedData;
    }

    /**
     * the real data
     * @throws IllegalArgumentException if bigger than 64KB
     */
    public void setEncryptedData(byte[] data) {
        if (data.length > MAX_LENGTH)
            throw new IllegalArgumentException();
        _encryptedData = data;
    }

    public int getSize() {
        if (_unencryptedData != null)
            return _unencryptedData.length;
        else if (_encryptedData != null)
            return _encryptedData.length;
        else
            return 0;
    }

    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        int size = (int) DataHelper.readLong(in, 4);
        if (size < 0 || size > MAX_LENGTH) throw new DataFormatException("payload size out of range (" + size + ")");
        _encryptedData = new byte[size];
        int read = read(in, _encryptedData);
        if (read != size) throw new DataFormatException("Incorrect number of bytes read in the payload structure");
        //if (_log.shouldDebug())
        //    _log.debug("read payload: " + read + " bytes");
    }

    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_encryptedData == null) throw new DataFormatException("Not yet encrypted.  Please set the encrypted data");
        DataHelper.writeLong(out, 4, _encryptedData.length);
        out.write(_encryptedData);
        //if (_log.shouldDebug())
        //    _log.debug("wrote payload: " + _encryptedData.length);
    }

    /**
      *  Writes the encrypted payload to the target array.
      *
      *  @return the written length (NOT the new offset)
      */
    public int writeBytes(byte target[], int offset) {
        if (_encryptedData == null) throw new IllegalStateException("Not yet encrypted.  Please set the encrypted data");
        DataHelper.toLong(target, offset, 4, _encryptedData.length);
        offset += 4;
        System.arraycopy(_encryptedData, 0, target, offset, _encryptedData.length);
        return 4 + _encryptedData.length;
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof Payload)) return false;
        Payload p = (Payload) object;
        return Arrays.equals(_unencryptedData, p.getUnencryptedData())
               && Arrays.equals(_encryptedData, p.getEncryptedData());
    }

    @Override
    public int hashCode() {
        return DataHelper.hashCode(_encryptedData != null ? _encryptedData : _unencryptedData);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(32);
        buf.append("Payload: ");
        if (_encryptedData != null)
            buf.append(_encryptedData.length).append(" bytes");
        else
            buf.append("null");
        return buf.toString();
    }
}
