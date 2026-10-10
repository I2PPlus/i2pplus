package net.i2p.router.networkdb.kademlia;

/**
 *  Signature verification failed because the
 *  sig type is unknown or unavailable.
 *
 *  @since 0.9.16
 */
public class UnsupportedCryptoException extends IllegalArgumentException {

    /**
     * UnsupportedCryptoException.
     *
     * @param msg the message naming the signature type that could not be verified
     */
    public UnsupportedCryptoException(String msg) {
        super(msg);
    }

    /**
     * UnsupportedCryptoException.
     *
     * @param msg the message naming the signature type that could not be verified
     * @param t the underlying failure that made the signature type unavailable
     */
    public UnsupportedCryptoException(String msg, Throwable t) {
        super(msg, t);
    }
}
