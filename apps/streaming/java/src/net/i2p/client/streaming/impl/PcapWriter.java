package net.i2p.client.streaming.impl;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 *  Write a standard pcap file with a "TCP" packet that can be analyzed with
 *  standard tools such as wireshark.
 *
 *  The source and dest "IP" and "port" are fake but are generated from the
 *  hashes of the Destinations and stream ID's, so they will be consistent.
 *  The local "IP" will always be of the form 127.0.x.y
 *  Initial IP for a conn will be 127.0.0.0 for the local and 0.0.0.0 for the remote.
 *
 *  Reference: http://wiki.wireshark.org/Development/LibpcapFileFormat
 *
 *  The Jpcap library http://netresearch.ics.uci.edu/kfujii/jpcap/doc/
 *  was close to what I want, but it requires you to instantiate a "captor"
 *  before you can write a file, and it requires a native lib to do so,
 *  and even then, it only wants to read the file, not write it.
 *
 *  We even calculate a correct TCP header checksum to keep the tools happy.
 *  We don't, however, convert I2P-style sequence numbers, which count packets,
 *  to TCP-style byte counts. We don't track a lowest-acked-thru byte count atm, really.
 *
 *  We do represent the window size in bytes though, so that's real confusing.
 *
 *  This is designed to debug the streaming lib, but there are not log calls for every
 *  single packet - pings and pongs, and various odd cases where received packets
 *  are dropped, are not logged.
 *
 *  Yes we could dump it natively and write a wireshark dissector. That sounds hard.
 *  And we wouldn't get the TCP stream analysis built into the tools.
 *
 *  @since 0.9.4
 */
public class PcapWriter implements Closeable, Flushable {

    /** big-endian, see file format ref - 24 bytes */
    private static final byte[] FILE_HEADER = { (byte) 0xa1, (byte) 0xb2, (byte) 0xc3, (byte) 0xd4,
                                                0, 2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0,
                                                0, 0, (byte) 0xff, (byte) 0xff, 0, 0, 0, 1 };

    /** dummy macs, IPv4 ethertype */
    private static final byte[] MAC_HEADER = { 1, 2, 3, 4, 5, 6,
                                               1, 2, 3, 4, 5, 6,
                                               (byte) 0x08, 0 };
    private static final byte[] IP_HEADER_1 = { 0x45, 0 };  // the length goes after this
    private static final byte[] IP_HEADER_2 = { 0x12, 0x34, 0x40, 0, 64, 6 };  // ID, flags, TTL and TCP
    private static final byte[] UNK_IP = { (byte) 0xff, 0, 0, 0};
    private static final byte[] MY_UNK_IP = {127, 0, 0, 0};
    /** max # of streaming lib payload bytes to dump */
    private static final int MAX_PAYLOAD_BYTES = 10;

    /** options - give our custom ones some mnemonics */
    private static final int MAX_OPTION_LEN = 40;
    private static final byte OPTION_END = 0;
    private static final byte OPTION_MSS = 2;
    private static final byte OPTION_PING = 6;
    private static final byte OPTION_PONG = 7;
    private static final byte OPTION_SIGREQ = 0x55;
    private static final byte OPTION_SIG = 0x56;
    private static final byte OPTION_RDELAY = (byte) 0xde;
    private static final byte OPTION_ODELAY = (byte) 0xd0;
    private static final byte OPTION_FROM = (byte) 0xf0;
    private static final byte OPTION_NACK = (byte) 0xac;


    private final OutputStream _fos;
    private final I2PAppContext _context;

    /**
     * Creates a new PcapWriter that writes to the specified file in the log directory.
     *
     * @param ctx the application context
     * @param file the filename to write (relative to the log directory)
     * @throws IOException if the file cannot be opened or the header cannot be written
     */
    public PcapWriter(I2PAppContext ctx, String file) throws IOException {
        _context = ctx;
        File f = new File(ctx.getLogDir(), file);
        _fos = new BufferedOutputStream(new FileOutputStream(f), 64*1024);
        _fos.write(FILE_HEADER);
    }

    /**
     * Closes the output stream, flushing any buffered data.
     * Errors during close are silently ignored.
     */
    @Override
    public void close() {
        try {
            _fos.close();
        } catch (IOException ioe) { /* ignored */ }
    }

    /**
     * Flushes any buffered data to the underlying output stream.
     * Errors during flush are silently ignored.
     */
    @Override
    public void flush() {
        try {
            _fos.flush();
        } catch (IOException ioe) { /* ignored */ }
    }

    /**
     * Write an outbound packet to the pcap file.
     *
     * @param pkt the outbound packet to write
     * @throws IOException if the packet cannot be written
     */
    public void write(PacketLocal pkt) throws IOException {
        try {
            wrt(pkt, pkt.getConnection(), false);
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            throw new IOException(dfe.toString());
        }
    }

    /**
     * Write an inbound packet to the pcap file.
     *
     * @param pkt the inbound packet to write
     * @param con the connection, may be null
     * @throws IOException if the packet cannot be written
     */
    public void write(Packet pkt, Connection con) throws IOException {
        try {
            wrt(pkt, con, true);
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            throw new IOException(dfe.toString());
        }
    }

    /**
     * Write a packet to the pcap file in the proper format.
     *
     * @param pkt the packet to write
     * @param con the connection, may be null
     * @param isInbound true if the packet is inbound
     * @throws IOException if the packet cannot be written
     * @throws DataFormatException if the packet data is malformed
     */
    private synchronized void wrt(Packet pkt, Connection con, boolean isInbound) throws IOException, DataFormatException {
        int includeLen = Math.min(MAX_PAYLOAD_BYTES, pkt.getPayloadSize());

        // option block
        Options opts = new Options();
        if (pkt.isFlagSet(Packet.FLAG_MAX_PACKET_SIZE_INCLUDED))
            opts.add(OPTION_MSS, 2, pkt.getOptionalMaxSize());
        if (pkt.isFlagSet(Packet.FLAG_DELAY_REQUESTED))
            opts.add(OPTION_ODELAY, 2, pkt.getOptionalDelay());
        if (pkt.getResendDelay() > 0)
            opts.add(OPTION_RDELAY, 1, pkt.getResendDelay());
        if (pkt.isFlagSet(Packet.FLAG_SIGNATURE_REQUESTED))
            opts.add(OPTION_SIGREQ);
        if (pkt.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED))
            opts.add(OPTION_SIG);
        if (pkt.isFlagSet(Packet.FLAG_FROM_INCLUDED))
            opts.add(OPTION_FROM);
        if (pkt.isFlagSet(Packet.FLAG_ECHO)) {
            if (pkt.getSendStreamId() > 0)
                opts.add(OPTION_PING);
            else
                opts.add(OPTION_PONG);
        }
        if (pkt.getNacks() != null)
            opts.add(OPTION_NACK, 1, pkt.getNacks().length);
        int optLen = opts.size();
        byte[] options = opts.getData();

        // PCAP Header
        long now;
        if (isInbound)
            now = _context.clock().now();
        else
            now = ((PacketLocal)pkt).getLastSend();
        DataHelper.writeLong(_fos, 4, now / 1000);
        DataHelper.writeLong(_fos, 4, 1000 * (now % 1000));
        DataHelper.writeLong(_fos, 4, 54 + optLen + includeLen);   // 14 MAC + 20 IP + 20 TCP
        DataHelper.writeLong(_fos, 4, 58 + optLen + pkt.getPayloadSize()); // 54 + MAC checksum

        // MAC Header 14 bytes
        _fos.write(MAC_HEADER);

        // IP 20 bytes total
        // IP Header 12 bytes
        int length = 20 + 20 + optLen + pkt.getPayloadSize();
        _fos.write(IP_HEADER_1);
        DataHelper.writeLong(_fos, 2, length);  // total IP length
        _fos.write(IP_HEADER_2);

        // src and dst IP 8 bytes
        // make our side always start with 127.0.x.x
        byte[] srcAddr;
        byte[] dstAddr;
        if (isInbound) {
            if (con != null) {
                dstAddr = new byte[4];
                dstAddr[0] = 127;
                dstAddr[1] = 0;
                System.arraycopy(con.getSession().getMyDestination().calculateHash().getData(), 0, dstAddr, 2, 2);
            } else
                dstAddr = MY_UNK_IP;

            if (con != null && con.getRemotePeer() != null)
                srcAddr = con.getRemotePeer().calculateHash().getData();
            else if (pkt.getOptionalFrom() != null)
                srcAddr = pkt.getOptionalFrom().calculateHash().getData();
            else
                srcAddr = UNK_IP;
        } else {
            if (con != null) {
                srcAddr = new byte[4];
                srcAddr[0] = 127;
                srcAddr[1] = 0;
                System.arraycopy(con.getSession().getMyDestination().calculateHash().getData(), 0, srcAddr, 2, 2);
            } else
                srcAddr = MY_UNK_IP;

            if (con != null && con.getRemotePeer() != null)
                dstAddr = con.getRemotePeer().calculateHash().getData();
            else
                dstAddr = UNK_IP;
        }

        // calculate and output the correct IP header checksum to keep the analyzers happy
        int checksum = length;
        checksum = update(checksum, IP_HEADER_1);
        checksum = update(checksum, IP_HEADER_2);
        checksum = update(checksum, srcAddr, 4);
        checksum = update(checksum, dstAddr, 4);
        DataHelper.writeLong(_fos, 2, checksum ^ 0xffff);

        // IPs
        _fos.write(srcAddr, 0, 4);
        _fos.write(dstAddr, 0, 4);

        // TCP header 20 bytes total
        // src and dst port 4 bytes
        // the rcv ID is the source, and the send ID is the dest.
        DataHelper.writeLong(_fos, 2, pkt.getReceiveStreamId() & 0xffff);
        DataHelper.writeLong(_fos, 2, pkt.getSendStreamId() & 0xffff);

        // seq and acks 8 bytes
        long seq;
        // wireshark wants the seq # in a SYN packet to be one less than the first data packet,
        // so let's set it to 0. ???????????
        if (pkt.isFlagSet(Packet.FLAG_SYNCHRONIZE))
            seq = 0xffffffffL;
        else
            seq = pkt.getSequenceNum();
        DataHelper.writeLong(_fos, 4, seq);
        long acked = 0;
        if (con != null) {
            acked = getLowestAckedThrough(pkt);
        }
        DataHelper.writeLong(_fos, 4, acked);

        // offset and flags 2 bytes
        int flags = 0;
        if (pkt.isFlagSet(Packet.FLAG_CLOSE))
            flags |= 0x01;
        if (pkt.isFlagSet(Packet.FLAG_SYNCHRONIZE))
            flags |= 0x02;
        if (pkt.isFlagSet(Packet.FLAG_RESET))
            flags |= 0x04;
        if (!pkt.isFlagSet(Packet.FLAG_NO_ACK))
            flags |= 0x10;
        // offset byte
        int osb = (5 + (optLen / 4)) << 4;
        DataHelper.writeLong(_fos, 1, osb); // 5 + optLen/4 32-byte words
        DataHelper.writeLong(_fos, 1, flags);

        // window size 2 bytes
        long window = ConnectionOptions.getInitialWindowSize();
        long msgSize = ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE;
        if (con != null) {
            // calculate the receive window, which doesn't have an exact streaming equivalent
            if (isInbound) {
                // Inbound pkt: his rcv buffer ~= our outbound window
                // try to represent what he thinks the window is, we don't really know
                // this isn't really right, the lastsendid can get way ahead
                window = con.getLastSendId() + con.getOptions().getWindowSize() - acked;
            } else {
                // Ourbound pkt: our rcv buffer ~= his outbound window
                // TODO just use a recent high unackedIn count?
                // following is from ConnectionPacketHandler
                // this is not interesting, we have lots of buffers
                long ready = con.getInputStream().getHighestReadyBlockId();
                int available = con.getOptions().getInboundBufferSize() - con.getInputStream().getTotalReadySize();
                int allowedBlocks = available/con.getOptions().getMaxMessageSize();
                window = (ready + allowedBlocks) - pkt.getSequenceNum();
            }
            if (window <= 1)
                window = 2; // TCP min
            msgSize = con.getOptions().getMaxMessageSize();
        }
        // messages -> bytes
        window *= msgSize;
        // for now we don't spoof window scaling
        if (window > 65535)
            window = 65535;
        DataHelper.writeLong(_fos, 2, window);

        // checksum and urgent pointer 4 bytes
        DataHelper.writeLong(_fos, 4, 0);

        // TCP option block
        if (optLen > 0)
            _fos.write(options, 0, optLen);

        // some data
        if (includeLen > 0)
            _fos.write(pkt.getPayload().getData(), 0, includeLen);
        if (pkt.isFlagSet(Packet.FLAG_CLOSE))
            _fos.flush();
    }

    /**
     * Calculate the lowest sequence number that has been acked through.
     *
     * @param pkt the packet containing ack information
     * @param con the connection
     * @return the lowest acked-through sequence number (at least 0)
     */
    private static long getLowestAckedThrough(Packet pkt) {
        long[] nacks = pkt.getNacks();
        long lowest = pkt.getAckThrough(); // can return -1 but we increment below
        if (nacks != null) {
            for (int i = 0; i < nacks.length; i++) {
                if (nacks[i] - 1 < lowest)
                    lowest = nacks[i] - 1;
            }
        }
        // I2P ack is of current seq number; TCP is next expected seq number
        // should be >= 0 now
        lowest++;
        // just in case
        return Math.max(0, lowest);
    }

    /**
     * Builder for TCP option blocks in the pcap output.
     */
    private static class Options {
        private final byte[] _b;
        private int _len;

        public Options() {
            _b = new byte[MAX_OPTION_LEN];
        }

        /**
         * Returns the raw option data buffer (40 bytes long).
         * The caller must use {@link #size()} to get the actual used length.
         *
         * @return the option data buffer
         */
        public byte[] getData() { return _b; }

        /**
         * Returns the actual option length, rounded up to the next 4-byte boundary.
         *
         * @return the padded option length in bytes
         */
        public int size() { return ((_len + 3) / 4) * 4; }

        /**
         * Add a zero-data option.
         *
         * @param type the option type byte
         */
        public void add(byte type) {
             add(type, 0, 0);
        }

        /**
         * Add an option with integer data.
         *
         * @param type the option type byte
         * @param datalen the length of the data in bytes
         * @param data the option data value
         */
        public void add(byte type, int datalen, int data) {
            // no room? drop silently
            if (_len + datalen + 2 > MAX_OPTION_LEN)
                return;
            _b[_len++] = type;
            _b[_len++] = (byte) (datalen + 2);
            if (datalen > 0) {
                for (int i = datalen - 1; i >= 0; i--)
                    _b[_len++] = (byte) ((data >> (i * 8)) & 0xff);
            }
            // end-of-options mark
            if (_len < MAX_OPTION_LEN)
                _b[_len] = OPTION_END;
        }
    }

    /**
     * One's complement 2-byte checksum update.
     *
     * @param checksum the running checksum
     * @param b the data to incorporate
     * @return the updated checksum
     */
    private static int update(int checksum, byte[] b) {
        return update(checksum, b, b.length);
    }

    /**
     * One's complement 2-byte checksum update.
     *
     * @param checksum the running checksum
     * @param b the data to incorporate
     * @param len the number of bytes to use from b
     * @return the updated checksum
     */
    private static int update(int checksum, byte[] b, int len) {
        int rv = checksum;
        for (int i = 0; i < len; i += 2) {
            rv += ((b[i] << 8) & 0xff00) | (b[i+1] & 0xff);
            if (rv > 0xffff) {
                rv &= 0xffff;
                rv++;
            }
        }
        return rv;
    }
}
