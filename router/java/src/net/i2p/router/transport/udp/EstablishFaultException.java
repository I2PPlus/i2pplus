package net.i2p.router.transport.udp;

/**
 * A fault in the state we hold for a peer we are establishing with, rather than
 * something the peer did to us: the RouterAddress we have for it carries no usable
 * SSU2 static key, or the version our own state machine settled on has no handshake
 * pattern to run.
 *
 * <p>It extends {@link IllegalArgumentException} so every caller that already catches
 * that keeps working unchanged, and it exists because the two kinds of failure have
 * to be told apart. The Diffie-Hellman rejects a low-order X25519 point with a plain
 * {@link IllegalArgumentException} (RFC 7748), and a peer can buy one of those per
 * packet, so those are logged at debug and trended in udp.establishDropped. A fault
 * in our own view of the peer is not remote-paced, and hiding it costs the operator
 * the only sign that the RouterAddress they are trying to reach is unusable.
 *
 * @since 0.9.72+
 */
class EstablishFaultException extends IllegalArgumentException {

    /** Serial version UID for this serializable class. */
    private static final long serialVersionUID = 1L;

    /**
     * Names what is wrong with the state we hold, in the words the log shows.
     *
     * @param message the fault, kept short enough to sit on one log line
     */
    EstablishFaultException(String message) {
        super(message);
    }
}
