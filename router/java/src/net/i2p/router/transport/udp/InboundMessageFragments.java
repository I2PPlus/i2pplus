package net.i2p.router.transport.udp;

import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.util.DecayingBloomFilter;
import net.i2p.router.util.DecayingHashSet;
import net.i2p.util.Log;

/**
 * Organize the received data message fragments, feeding completed messages
 * to the {@link MessageReceiver} and telling the {@link ACKSender} of new
 * peers to ACK.  In addition, it drops failed fragments and keeps a
 * minimal list of the most recently completed messages (even though higher
 * up in the router we have full blown replay detection, its nice to have a
 * basic line of defense here).
 *
 */
class InboundMessageFragments /*implements UDPTransport.PartialACKSource */{
    private final RouterContext _context;
    private final Log _log;
    /** list of message IDs recently received, so we can ignore in flight dups */
    private DecayingBloomFilter _recentlyCompletedMessages;
    private final OutboundMessageFragments _outbound;
    private final UDPTransport _transport;
    private final ACKSender _ackSender;
    private final MessageReceiver _messageReceiver;
    private volatile boolean _alive;

    /** decay the recently completed every 20 seconds */
    private static final int DECAY_PERIOD = 10*1000;

    public InboundMessageFragments(RouterContext ctx, OutboundMessageFragments outbound, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(InboundMessageFragments.class);
        //_inboundMessages = new HashMap(64);
        _outbound = outbound;
        _transport = transport;
        _ackSender = new ACKSender(_context, _transport);
        _messageReceiver = new MessageReceiver(_context, _transport);
        _context.statManager().createRateStat("udp.receivedCompleteTime", "Time taken to receive a full message", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivedCompleteFragments", "How many fragments go in a fully received message", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivedACKs", "How many messages were ACKed at a time", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ignoreRecentDuplicate", "Take note that we received a packet for a recently completed message", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveMessagePeriod", "Time taken to pull the message fragments out of a packet", "Transport [UDP]", UDPTransport.RATES);
        //_context.statManager().createRateStat("udp.receiveACKPeriod", "Time taken to pull the ACKs out of a packet", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePiggyback", "How many acks were included in a packet with data fragments (time = # data fragments)", "Transport [UDP]", UDPTransport.RATES);
    }

    public synchronized void startup() {
        _alive = true;
        // may want to extend the DecayingBloomFilter so we can use a smaller
        // array size (currently its tuned for 10 minute rates for the
        // messageValidator)
        _recentlyCompletedMessages = new DecayingHashSet(_context, DECAY_PERIOD, 4, "UDPIMF");
        _ackSender.startup();
        _messageReceiver.startup();
    }

    public synchronized void shutdown() {
        _alive = false;
        if (_recentlyCompletedMessages != null)
            _recentlyCompletedMessages.stopDecaying();
        _recentlyCompletedMessages = null;
        _ackSender.shutdown();
        _messageReceiver.shutdown();
    }

    public boolean isAlive() { return _alive; }

    /**
     * Pull the fragments and ACKs out of the authenticated data packet
     */
    public void receiveData(PeerState from, UDPPacketReader.DataReader data) {
        try {
            rcvData(from, data);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad packet from: " + from, dfe);
        } catch (IndexOutOfBoundsException ioobe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Bad packet from: " + from, ioobe);
        }
    }

    /**
     * Pull the fragments and ACKs out of the authenticated data packet
     */
    private void rcvData(PeerState from, UDPPacketReader.DataReader data) throws DataFormatException {
        //long beforeMsgs = _context.clock().now();
        int fragmentsIncluded = receiveMessages(from, data);
        //long afterMsgs = _context.clock().now();
        int acksIncluded = receiveACKs(from, data);
        //long afterACKs = _context.clock().now();

        from.packetReceived(data.getPacketSize());
        // each of these was less than 0.1 ms
        //_context.statManager().addRateData("udp.receiveMessagePeriod", afterMsgs-beforeMsgs, afterACKs-beforeMsgs);
        //_context.statManager().addRateData("udp.receiveACKPeriod", afterACKs-afterMsgs, afterACKs-beforeMsgs);
        if ( (fragmentsIncluded > 0) && (acksIncluded > 0) )
            _context.statManager().addRateData("udp.receivePiggyback", acksIncluded, fragmentsIncluded);
    }

    /**
     * Pull out all the data fragments and shove them into InboundMessageStates.
     * Along the way, if any state expires, or a full message arrives, move it
     * appropriately.
     *
     * @return number of data fragments included
     */
    private int receiveMessages(PeerState from, UDPPacketReader.DataReader data) throws DataFormatException {
        int fragments = data.readFragmentCount();
        if (fragments <= 0) return fragments;
        Hash fromPeer = from.getRemotePeer();

        Map<Long, InboundMessageState> messages = from.getInboundMessages();

        for (int i = 0; i < fragments; i++) {
            long mid = data.readMessageId(i);
            Long messageId = Long.valueOf(mid);

            if (_recentlyCompletedMessages.isKnown(mid)) {
                // Only update stats for the first fragment,
                // otherwise it wildly overstates things
                if (data.readMessageFragmentNum(i) == 0) {
                    _context.statManager().addRateData("udp.ignoreRecentDuplicate", 1);
                    from.messageFullyReceived(messageId, -1);
                    _ackSender.ackPeer(from);
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Message received is a duplicate: " + mid + " dups: "
                                  + _recentlyCompletedMessages.getCurrentDuplicateCount() + " out of "
                                  + _recentlyCompletedMessages.getInsertedCount());
                    _context.messageHistory().droppedInboundMessage(mid, from.getRemotePeer(), "dup");
                }
                continue;
            }

            InboundMessageState state;
            boolean messageComplete = false;
            boolean messageExpired = false;
            boolean fragmentOK;
            boolean partialACK = false;

            synchronized (messages) {
                boolean isNew = false;
                state = messages.get(messageId);
                if (state == null) {
                    state = new InboundMessageState(_context, mid, fromPeer, data, i);
                    isNew = true;
                    fragmentOK = true;
                    // we will add to messages shortly if it isn't complete
                } else {
                    fragmentOK = state.receiveFragment(data, i);
                }


                if (state.isComplete()) {
                    messageComplete = true;
                    if (!isNew)
                        messages.remove(messageId);
                } else if (state.isExpired()) {
                    messageExpired = true;
                    if (!isNew)
                        messages.remove(messageId);
                } else {
                    partialACK = true;
                    if (isNew)
                        messages.put(messageId, state);
                }
            }

            if (messageComplete) {
                _recentlyCompletedMessages.add(mid);
                from.messageFullyReceived(messageId, state.getCompleteSize());
                _ackSender.ackPeer(from);

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Complete message received\n* " + state);

                _context.statManager().addRateData("udp.receivedCompleteTime", state.getLifetime(), state.getLifetime());
                if (state.getFragmentCount() > 0)
                    _context.statManager().addRateData("udp.receivedCompleteFragments", state.getFragmentCount(), state.getLifetime());

                // this calls state.releaseResources(), all state access must be before this
                _messageReceiver.receiveMessage(state);
            } else if (messageExpired) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Message expired while only being partially read\n* " + state);
                _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "expired while partially read: " + state.toString());
                // all state access must be before this
                state.releaseResources();
            } else if (partialACK) {
                // not expired but not yet complete... lets queue up a partial ACK
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Queueing up a partial ACK for peer: " + from + " for " + state);
                from.messagePartiallyReceived();
                _ackSender.ackPeer(from);
            }

            // TODO: Why give up on other fragments if one is bad?
            if (!fragmentOK)
                break;
        }
        from.expireInboundMessages();
        return fragments;
    }

    /**
     *  @return the number of bitfields in the ack? why?
     */
    private int receiveACKs(PeerState from, UDPPacketReader.DataReader data) throws DataFormatException {
        int rv = 0;
        boolean newAck = false;
        ModifiableLong highestSeqNumAcked = new ModifiableLong(-1);
        if (data.readACKsIncluded()) {
            int ackCount = data.readACKCount();
            if (ackCount > 0) {
                rv += ackCount;
                _context.statManager().addRateData("udp.receivedACKs", ackCount);
                //_context.statManager().getStatLog().addData(from.getRemoteHostId().toString(), "udp.peer.receiveACKCount", acks.length, 0);

                for (int i = 0; i < ackCount; i++) {
                    long id = data.readACK(i);
                    if (from.acked(id, highestSeqNumAcked)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("First full ACK of [MsgID " + id + "] received from [" + from.getRemotePeer().toBase64().substring(0,6) + "]");
                        newAck = true;
                    //} else if (_log.shouldLog(Log.DEBUG)) {
                    //    _log.debug("Dup full ACK of message " + id + " received from " + from.getRemotePeer());
                    }
                }
            } else {
                _log.error("Received ACKs with no acks?! " + data);
            }
        }
        if (data.readACKBitfieldsIncluded()) {
            ACKBitfield bitfields[] = data.readACKBitfields();
            if (bitfields != null) {
                rv += bitfields.length;
                //_context.statManager().getStatLog().addData(from.getRemoteHostId().toString(), "udp.peer.receivePartialACKCount", bitfields.length, 0);

                for (int i = 0; i < bitfields.length; i++) {
                    if (from.acked(bitfields[i], highestSeqNumAcked)) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Partial ACK received: " + bitfields[i] + " from [" + from.getRemotePeer().toBase64().substring(0,6) + "]");
                        newAck = true;
                    }
                }
            }
        }
        if (data.readECN())
            from.ECNReceived();
        else
            from.dataReceived();

        long highest = highestSeqNumAcked.value;
        if (highest >= 0) {
            boolean retx = from.highestSeqNumAcked(highest);
            if (retx)
                newAck = true;
        }

        // Wake up the packet pusher if it is sleeping.
        // By calling add(), this also is a failsafe against possible
        // races in OutboundMessageFragments.
/*
        if (newAck && from.getOutboundMessageCount() > 0)
            _outbound.add(from, 0);
*/

        return rv;
    }

    /**
     * Modifiable Long, no locking
     * @since 0.9.49
     */
    public static class ModifiableLong {
        public long value;
        public ModifiableLong(long val) { value = val; }
    }
}
