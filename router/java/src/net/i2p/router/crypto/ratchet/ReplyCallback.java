package net.i2p.router.crypto.ratchet;

/**
 * Callback interface for ratchet acknowledgment notifications with expiration support
 *
 * @since 0.9.46
 */
public interface ReplyCallback {

    /**
     *  When does this callback expire?
     *  @return java time
     */
    public long getExpiration();

    /**
     *  A reply was received.
     */
    public void onReply();
}
