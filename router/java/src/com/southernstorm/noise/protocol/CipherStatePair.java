package com.southernstorm.noise.protocol;

/**
 * Class that contains a pair of CipherState objects.
 *
 * CipherState pairs typically arise when HandshakeState.split() is called.
 */
public final class CipherStatePair implements Destroyable {

    private CipherState send;
    private CipherState recv;

    /**
     * Constructs a pair of CipherState objects.
     *
     * @param sender The CipherState to use to send packets to the remote party.
     * @param receiver The CipherState to use to receive packets from the remote party.
     */
    public CipherStatePair(CipherState sender, CipherState receiver)
    {
        send = sender;
        recv = receiver;
    }

    /**
     * Gets the CipherState to use to send packets to the remote party.
     *
     * @return The sending CipherState.
     */
    public CipherState getSender() {
        return send;
    }

    /**
     * Gets the CipherState to use to receive packets from the remote party.
     *
     * @return The receiving CipherState.
     */
    public CipherState getReceiver() {
        return recv;
    }

    /**
     * Destroys the receiving CipherState and retains only the sending CipherState.
     *
     * This function is intended for use with one-way handshake patterns.
     */
    public void senderOnly()
    {
        recv.destroy();
    }

    /**
     * Destroys the sending CipherState and retains only the receiving CipherState.
     *
     * This function is intended for use with one-way handshake patterns.
     */
    public void receiverOnly()
    {
        send.destroy();
    }

    /**
     * Swaps the sender and receiver.
     */
    public void swap()
    {
        CipherState temp = send;
        send = recv;
        recv = temp;
    }

    @Override
    public void destroy() {
        senderOnly();
        receiverOnly();
    }
}
