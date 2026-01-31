package net.i2p.router.transport.udp;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.PacketBuilder.Fragment;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Coordinate the outbound fragments and select the next one to be built.
 * This pool contains messages we are actively trying to send, essentially
 * doing a round robin across each message to send one fragment, as implemented
 * in {@link #getNextVolley()}.  This also honors per-peer throttling, taking
 * note of each peer's allocations.  If a message has each of its fragments
 * sent more than a certain number of times, it is failed out.  In addition,
 * this instance also receives notification of message ACKs from the
 * {@link InboundMessageFragments}, signaling that we can stop sending a
 * message.
 *
 */
class OutboundMessageFragments {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;

    /**
     *  List of peers currently sending outbound messages.
     *  Thread-safe for iteration and modification with reduced array copying.
     */
    private final List<PeerState> _activePeers = new CopyOnWriteArrayList<>();
    private int _peerIndex = 0;
    private final List<PeerState> _peersToRemove = new ArrayList<>();
    private final Object _waitLock = new Object();
    private volatile boolean _alive;
    private final PacketBuilder2 _builder2;
    static final int MAX_VOLLEYS = 10; // don't send a packet more than 10 times
    private static final int MAX_WAIT = SystemVersion.isSlow() ? 1000 : 500;

    /**
     * Thread-local pool for fragment lists to reduce GC churn.
     * Uses SoftReference to allow GC under memory pressure.
     * Each thread gets its own pool to avoid contention.
     */
    private static final ThreadLocal<SoftReference<ArrayList<Fragment>>> _fragmentListPool = 
        new ThreadLocal<SoftReference<ArrayList<Fragment>>>() {
            @Override
            protected SoftReference<ArrayList<Fragment>> initialValue() {
                return new SoftReference<>(new ArrayList<>(16));
            }
        };

    /**
     * Acquire a fragment list from the thread-local pool.
     * The list is cleared before return.
     *
     * @return a ready-to-use ArrayList for fragments
     */
    private static ArrayList<Fragment> acquireFragmentList() {
        SoftReference<ArrayList<Fragment>> ref = _fragmentListPool.get();
        ArrayList<Fragment> list = ref.get();
        if (list == null) {
            list = new ArrayList<>(16);
            _fragmentListPool.set(new SoftReference<>(list));
        } else {
            list.clear();
        }
        return list;
    }

    public OutboundMessageFragments(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundMessageFragments.class);
        _transport = transport;
        _builder2 = transport.getBuilder2();
        _alive = true;
        _context.statManager().createRateStat("udp.outboundActivePeers", "Number of peers we are actively sending to", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.memory.activePeers", "Memory usage tracking for active peers", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetsRetransmitted", "Lifetime (ms) of packets during retransmission", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.partialACKReceived", "Number of partially ACKed fragments", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.peerPacketsRetransmitted", "Resent packets during burst (period = pkts sent, lifetime)", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendAggressiveFailed", "Number of volleys a packet was sent before we gave up", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmFragments", "Fragments included in a fully ACKed message", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmTime", "Time (ms) to send a message and get the ACK", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendConfirmVolley", "Number of times fragments need to be sent before ACK", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendFailed", "Number of times a failed message was pushed", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendFragmentsPerPacket", "Fragments sent in a data packet", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendRejected", "What volley we were on when peer was throttled", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendVolleyTime", "Time (ms) to send a full volley", "Transport [UDP]", UDPTransport.RATES);
    }

    public synchronized void startup() { _alive = true; }

    public synchronized void shutdown() {
        _alive = false;
        _activePeers.clear();
        synchronized (_activePeers) {_activePeers.notify();}
    }

    void dropPeer(PeerState peer) {
        if (_log.shouldDebug()) {_log.debug("Dropping peer " + peer.getRemotePeer());}
        peer.dropOutbound();
        _activePeers.remove(peer);
    }

    /**
     * Add a new message to the active pool
     *
     */
    public void add(OutNetMessage msg) {
        RouterInfo target = msg.getTarget();
        if (target == null) {return;}

        PeerState peer = _transport.getPeerState(target.getIdentity().calculateHash());
        try { // will throw IAE if peer == null
            OutboundMessageState state = new OutboundMessageState(_context, msg, peer);
            peer.add(state);
            add(peer, state.getMinSendSize());
        } catch (IllegalArgumentException iae) {
            _transport.failed(msg, "Peer disconnected quickly");
            return;
        }
    }

    /**
     *  Short circuit the OutNetMessage, letting us send the establish
     *  complete message reliably.
     *  If you have multiple messages, use the list variant,
     *  so the messages may be bundled efficiently.
     */
    public void add(OutboundMessageState state, PeerState peer) {
        if (peer == null) {throw new RuntimeException("NULL peer for " + state);}
        peer.add(state);
        add(peer, state.getMinSendSize());
    }

    /**
     *  Short circuit the OutNetMessage, letting us send multiple messages
     *  reliably and efficiently.
     *  @since 0.9.24
     */
    public void add(List<OutboundMessageState> states, PeerState peer) {
        if (peer == null) {throw new RuntimeException("NULL peer");}
        int sz = states.size();
        int min = peer.fragmentSize();
        for (int i = 0; i < sz; i++) {
            OutboundMessageState state = states.get(i);
            peer.add(state);
            int fsz = state.getMinSendSize();
            if (fsz < min) {min = fsz;}
        }
        add(peer, min);
    }

    /**
     * Add the peer to the list of peers wanting to transmit something.
     * This wakes up the packet pusher if it is sleeping.
     *
     * Avoid synchronization where possible.
     * There are small chances of races.
     * There are larger chances of adding the PeerState "behind" where
     * the iterator is now... but these issues are the same as before concurrentification.
     *
     * @param size the minimum size we can send, or 0 to always notify
     * @since 0.8.9
     */
    public void add(PeerState peer, int size) {
        boolean added = _activePeers.add(peer);
        if (added) {
            if (_log.shouldDebug()) {
                _log.debug("Adding a new message to new peer [" + peer.getRemotePeer().toBase64().substring(0,6) + "]");
            }
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Adding a new message to an existing peer [" + peer.getRemotePeer().toBase64().substring(0,6) + "]");
            }
        }
        _context.statManager().addRateData("udp.outboundActivePeers", _activePeers.size());
        _context.statManager().addRateData("udp.memory.activePeers", _activePeers.size());

        // Avoid sync if possible ... no, this doesn't always work.
        // Also note that the iterator in getNextVolley may have alreay passed us, or not reflected the addition.
        if (added || size <= 0 || peer.getSendWindowBytesRemaining() >= size) {
            synchronized (_activePeers) {_activePeers.notify();}
        }
    }

    /**
     * Fetch all the packets for a message volley, blocking until there is a
     * message which can be fully transmitted (or the transport is shut down).
     *
     * NOT thread-safe. Called by the PacketPusher thread only.
     *
     * @return null only on shutdown
     */
    public List<UDPPacket> getNextVolley() {
        PeerState peer = null;
        List<OutboundMessageState> states = null;
        long now = _context.clock().now();
        int peersProcessed = 0;
        int nextSendDelay = Integer.MAX_VALUE;

        while (_alive && (states == null)) {
            // Reset peer index if we've gone through all peers
            if (_peerIndex >= _activePeers.size()) {
                _peerIndex = 0;
                if (_activePeers.isEmpty()) {
                    // Wait for new messages
                    waitForMessages();
                    continue;
                }
            }

            // Get the next peer in the round-robin list
            PeerState p = _activePeers.get(_peerIndex++);
            peersProcessed++;

            // Clean up completed messages
            int remaining = p.finishMessages(now);
            if (remaining <= 0) {
                _peersToRemove.add(p);
                // Immediate cleanup if list gets too large to prevent OOM
                if (_peersToRemove.size() > 1000) {
                    _activePeers.removeAll(_peersToRemove);
                    _peersToRemove.clear();
                }
                continue;
            }

            // Try to allocate fragments to send
            states = p.allocateSend(now);
            if (states != null) {
                peer = p;
                break;
            }

            // Track the soonest time this peer will be ready
            int delay = p.getNextDelay(now);
            if (delay < nextSendDelay) {
                nextSendDelay = delay;
            }

            // If we've gone through all peers, wait or retry
            if (peersProcessed >= _activePeers.size()) {
                // Batch remove peers to reduce CopyOnWriteArrayList copying
                if (!_peersToRemove.isEmpty()) {
                    _activePeers.removeAll(_peersToRemove);
                    _peersToRemove.clear();
                }

                if (nextSendDelay > 0) {
                    int toWait = Math.min(Math.max(nextSendDelay, 10), MAX_WAIT);
                    waitForMessages(toWait);
                    peersProcessed = 0;
                    nextSendDelay = Integer.MAX_VALUE;
                } else {
                    // Reset for next round
                    _peerIndex = 0;
                }
            }
        }

        if (peer == null || states == null) {
            // Cleanup _peersToRemove list before returning to prevent memory leak
            if (!_peersToRemove.isEmpty()) {
                _activePeers.removeAll(_peersToRemove);
                _peersToRemove.clear();
            }
            return null;
        }

        if (_log.shouldDebug()) {
            _log.debug("Sending to " + peer + ": " + DataHelper.toString(states));
        }

        return preparePackets(states, peer);
    }

    /**
     * Wakes up the packet pusher thread.
     * @since 0.9.48
     */
    void nudge() {
        synchronized (_waitLock) {
            _waitLock.notify();
        }
    }

    /**
     *  @return null if state or peer is null
     */
    private List<UDPPacket> preparePackets(List<OutboundMessageState> states, PeerState peer) {
        if (states == null || peer == null) {
            return null;
        }

        // build the list of fragments to send using pooled list
        List<Fragment> toSend = acquireFragmentList();
        for (OutboundMessageState state : states) {
            int queued = state.push(toSend);
            // per-state stats
            if (queued > 0 && state.getMaxSends() > 1) {
                int maxPktSz = state.fragmentSize(0);
                maxPktSz += SSU2Payload.BLOCK_HEADER_SIZE +
                            (peer.isIPv6() ? PacketBuilder2.MIN_IPV6_DATA_PACKET_OVERHEAD
                                           : PacketBuilder2.MIN_DATA_PACKET_OVERHEAD);
                peer.messageRetransmitted(queued, maxPktSz);
                long lifetime = state.getLifetime();
                int transmitted = peer.getPacketsTransmitted();
                _context.statManager().addRateData("udp.peerPacketsRetransmitted", peer.getPacketsTransmitted(), transmitted);
                _context.statManager().addRateData("udp.packetsRetransmitted", lifetime, transmitted);
                if (_log.shouldInfo()) {
                    _log.info("Retransmitting..." + state + " to " + peer);
                }
                _context.statManager().addRateData("udp.sendVolleyTime", lifetime, queued);
            }
        }

        if (toSend.isEmpty()) {
            return null;
        }

        int fragmentsToSend = toSend.size();
        List<UDPPacket> rv = new ArrayList<>(toSend.size());

        // Greedy fragment grouping logic - use pooled list for remaining
        List<Fragment> remaining = acquireFragmentList();
        remaining.addAll(toSend);
        int maxPacketSize = PacketBuilder2.getMaxDataSize(peer);

        while (!remaining.isEmpty()) {
            // Use pooled list for sendNext - cleared on each iteration
            List<Fragment> sendNext = acquireFragmentList();
            int curTotalDataSize = 0;

            for (Iterator<Fragment> it = remaining.iterator(); it.hasNext();) {
                Fragment next = it.next();
                OutboundMessageState state = next.state;
                int nextDataSize = state.fragmentSize(next.num);
                if (next.num > 0) {
                    nextDataSize += SSU2Util.DATA_FOLLOWON_EXTRA_SIZE;
                }

                // If this is the first fragment in the packet, add the first fragment header
                if (sendNext.isEmpty()) {
                    nextDataSize += SSU2Util.FIRST_FRAGMENT_HEADER_SIZE;
                } else {
                    nextDataSize += SSU2Util.DATA_FOLLOWON_EXTRA_SIZE;
                }

                // Check if it fits
                if (curTotalDataSize + nextDataSize <= maxPacketSize || sendNext.isEmpty()) {
                    sendNext.add(next);
                    curTotalDataSize += nextDataSize;
                    it.remove();
                }
            }

            if (!sendNext.isEmpty()) {
                UDPPacket pkt;
                try {
                    pkt = _builder2.buildPacket(sendNext, (PeerState2) peer);
                } catch (IOException ioe) {
                    pkt = null;
                }

                if (pkt != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Sent UDP packet with " + sendNext.size() + " fragments (" + curTotalDataSize +
                                   " data bytes)\n* Target: " + peer);
                    }
                    _context.statManager().addRateData("udp.sendFragmentsPerPacket", sendNext.size());
                } else {
                    if (_log.shouldWarn()) {
                        _log.info("Building UDP packet FAIL for " + DataHelper.toString(sendNext) + " to: " + peer);
                    }
                    continue;
                }

                // Set metadata for debugging and stats
                pkt.setFragmentCount(sendNext.size());
                if (!sendNext.isEmpty()) {
                    OutNetMessage msg = sendNext.get(0).state.getMessage();
                    int msgType = (msg != null) ? msg.getMessageTypeId() : -1;
                    pkt.setMessageType(msgType);
                }

                rv.add(pkt);
            }
        }

        int sent = rv.size();
        peer.packetsTransmitted(sent);
        peer.clearWantedACKSendSince();
        if (_log.shouldDebug()) {
            _log.debug("Sent " + fragmentsToSend + " fragments of " + states.size() +
                       " messages in " + sent + " packets\n* Target: " + peer);
        }

        return rv;
    }

private void removePeer(PeerState peer) {
    _activePeers.remove(peer);
    if (_log.shouldDebug()) {
        _log.debug("No more pending messages for [" + peer.getRemotePeer().toBase64().substring(0,6) + "]");
    }
}

private void waitForMessages() {
    synchronized (_waitLock) {
        try {
            _waitLock.wait(MAX_WAIT);
        } catch (InterruptedException ie) {
            if (_log.shouldDebug()) {
                _log.debug("Woken up while waiting");
            }
        }
    }
}

private void waitForMessages(int timeout) {
    synchronized (_waitLock) {
        try {
            _waitLock.wait(timeout);
        } catch (InterruptedException ie) {
            if (_log.shouldDebug()) {
                _log.debug("Woken up while waiting");
            }
        }
    }
}


}
