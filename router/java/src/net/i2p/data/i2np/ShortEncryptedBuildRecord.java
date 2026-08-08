package net.i2p.data.i2np;

/**
 *  Small records.
 *  218 bytes.
 *  Preliminary, see proposal 157.
 *
 *  Note that these are layer-encrypted and layer-decrypted in-place.
 *  Do not cache.
 *
 *  @since 0.9.49
 */
public class ShortEncryptedBuildRecord extends EncryptedBuildRecord {

    /** Record data size in bytes. */
    public final static int LENGTH = ShortTunnelBuildMessage.SHORT_RECORD_SIZE;

    /**
     *  Encrypted record with the given data.
     *
     *  @throws IllegalArgumentException if data is not correct length (null is ok)
     */
    public ShortEncryptedBuildRecord(byte[] data) {
        super(data);
    }

    /** Record size in bytes. */
    @Override
    public int length() {
        return LENGTH;
    }
}
