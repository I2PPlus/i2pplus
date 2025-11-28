package net.i2p.router.transport.udp;

/**
 * Generic means of SACK/NACK transmission for partially or fully
 * received messages
 */
interface ACKBitfield {

    /**
     * Get the message ID this is partially ACKing.
     * 
     * @return message ID
     */
    public long getMessageId();

    /**
     * Get how many fragments are covered in this bitfield.
     * 
     * @return number of fragments
     */
    public int fragmentCount();

    /**
     * Check if the given fragment has been received.
     * 
     * @param fragmentNum fragment number to check
     * @return true if fragment has been received
     */
    public boolean received(int fragmentNum);

    /**
     * Check if the entire message has been received completely.
     * 
     * @return true if message has been completely received
     */
    public boolean receivedComplete();

    /**
     *  Number of fragments acked in this bitfield.
     *  Faster than looping through received()
     *  
     *  @return number of fragments acked
     *  @since 0.9.16
     */
    public int ackCount();

    /**
     *  Highest fragment number acked in this bitfield.
     *  @return highest fragment number acked, or -1 if none
     *  @since 0.9.16
     */
    public int highestReceived();
}
