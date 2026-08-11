package net.i2p.router.transport.udp;


/**
 * Builder for UDP packets.
 */
class PacketBuilder {

    private PacketBuilder() { /* no-op */ }

    /** 37 if no extended options or rekey data, which we don't support. */
    public static final int HEADER_SIZE = UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE + 1 + 4;

    /** 4 byte msg ID + 3 byte fragment info. */
    public static final int FRAGMENT_HEADER_SIZE = 7;
    /** 46, not including acks. */
    public static final int DATA_HEADER_SIZE = HEADER_SIZE + 2 + FRAGMENT_HEADER_SIZE;

    /** IPv4 only. */
    public static final int IP_HEADER_SIZE = 20;
    /** Same for IPv4 and IPv6. */
    public static final int UDP_HEADER_SIZE = 8;

    /** Minimum IPv4 data packet overhead in bytes (74). */
    public static final int MIN_DATA_PACKET_OVERHEAD = IP_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE;

    /** IPv6 header size in bytes. */
    public static final int IPV6_HEADER_SIZE = 40;
    /** Minimum IPv6 data packet overhead in bytes (94). */
    public static final int MIN_IPV6_DATA_PACKET_OVERHEAD = IPV6_HEADER_SIZE + UDP_HEADER_SIZE + DATA_HEADER_SIZE;

    /** One byte field. */
    public static final int ABSOLUTE_MAX_ACKS = 255;

    /**
     * Higher than all other OutNetMessage priorities, but still droppable,
     * and will be shown in the codel.UDP-Sender.drop.500 stat.
     */
    static final int PRIORITY_HIGH = 550;

    /**
     *  Class for passing multiple fragments to buildPacket()
     *
     *  @since 0.9.16
     */
    public static class Fragment {
        /**
         * The outbound message state.
         */
        public final OutboundMessageState state;
        /**
         * The fragment number.
         */
        public final int num;

        /**
         * Fragment.
         */
        public Fragment(OutboundMessageState state, int num) {
            this.state = state;
            this.num = num;
        }

        /**
         * String representation of this fragment.
         */
        @Override
        public String toString() {
            return "Fragment " + num + " (" + state.fragmentSize(num) + " bytes)" + state;
        }
    }

}
