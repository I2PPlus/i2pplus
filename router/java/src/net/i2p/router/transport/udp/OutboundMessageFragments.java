package net.i2p.router.transport.udp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterInfo;
import net.i2p.stat.RateConstants;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.PacketBuilder2.Fragment;
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
    private final CopyOnWriteArrayList<PeerState> _activePeers = new CopyOnWriteArrayList<>();
    private int _peerIndex = 0;
    private final CopyOnWriteArrayList<PeerState> _peersToRemove = new CopyOnWriteArrayList<>();
    private final Object _waitLock = new Object();
    private volatile boolean _alive;
    private final PacketBuilder2 _builder2;
    /**
     *  Maximum number of times to send a packet before failing it.
     */
    static final int MAX_VOLLEYS = 10;
    private static final int MAX_WAIT = SystemVersion.isSlow() ? 1000 : 500;
    /**
     *  Floor on any wait taken here.
     *
     *  <p>A round that allocated nothing has to cost a bounded wait. It used to cost none:
     *  see {@link #foldSendDelay}.
     */
    static final int MIN_WAIT_MS = 10;
    /** Counter for periodic aggregate stat emission */
    private int _statEmitCounter;

    /**
     *  Reusable consumed-fragment marker for {@link #preparePackets}. Reused
     *  across volleys (cleared per call) instead of allocating a fresh BitSet
     *  every volley; {@link BitSet#set} grows it as needed, so growth is
     *  amortized to the largest volley ever seen.
     *  <p>
     *  Only reachable from {@link #getNextVolley()} on the single
     *  PacketPusher thread, so no synchronization is needed.
     */
    private BitSet _consumed = new BitSet();

    /**
     * OutboundMessageFragments.
     */
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
        _context.statManager().createRequiredRateStat("udp.sendConfirmTime", "Time (ms) to send a message and get the ACK", "Transport [UDP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("udp.sendConfirmVolley", "Number of times fragments need to be sent before ACK", "Transport [UDP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("udp.sendExpired", "Number of SSU messages expired before ACK", "Transport [UDP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("udp.sendFailed", "Number of times a failed message was pushed", "Transport [UDP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRateStat("udp.sendFragmentsPerPacket", "Fragments sent in a data packet", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendRejected", "What volley we were on when peer was throttled", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendVolleyTime", "Time (ms) to send a full volley", "Transport [UDP]", UDPTransport.RATES);
        // Registered here rather than beside udp.avgRTO in UDPTransport: this
        // class owns the periodic aggregate emission for the averages, and
        // addRateData() silently drops samples for stats nobody registered,
        // which is how udp.pathUnverified was written but never reported.
        // Required, not createRateStat(): StatManager.createRateStat() is a
        // no-op in the router unless stat.full is set, so the non-required
        // form would leave the stat unregistered (and dropped) by default.
        _context.statManager().createRequiredRateStat("udp.avgEffectiveRTO",
                "Average effective retransmission timeout across peers, clamped to [MIN_RTO,MAX_RTO] (ms)",
                "Transport [UDP]", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        _context.statManager().createRequiredRateStat("udp.pathUnverified",
                "Send window (CWIN bytes) when an unverified-path send freeze began",
                "Transport [UDP]", UDPTransport.RATES);
    }

    /**
     * Start the message pool.
     */
    public synchronized void startup() { _alive = true; }

    /**
     * Shut down the message pool.
     */
    public synchronized void shutdown() {
        _alive = false;
        _activePeers.clear();
        nudge();
    }

    /**
     *  Remove a peer from the active outbound list and drop its pending messages.
     */
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
        // Remove from pending-removal list first, in case a new message
        // arrives while finishMessages() queued this peer for removal.
        // Without this, removeAll() could remove a peer that just got a new message.
        _peersToRemove.remove(peer);
        boolean added = _activePeers.addIfAbsent(peer);
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
            nudge();
        }
    }

    /**
     * Fetch all the packets for a message volley.
     *
     * <p>Blocks while no peer can send, then gives up after at most {@link #MAX_WAIT}
     * rather than waiting indefinitely, so this returns even when nothing became sendable.
     *
     * <p>NOT thread-safe. Called by the PacketPusher thread only.
     *
     * <p>If this is ever changed to retry internally instead of returning null, it must
     * also break on thread interruption. Shutdown interrupts the pusher, and once that
     * interrupt has been consumed every later wait returns immediately, which would turn
     * the retry into a spin - the bug {@link #foldSendDelay} documents.
     *
     * @return the packets to send, or null when there is nothing to send. Null does not
     *         mean shutdown: a round can allocate states whose fragments are all already
     *         acked, leaving {@link #preparePackets} with nothing to push, and that case
     *         is transient. Callers must treat null as "retry later" and must not spin.
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
            PeerState p;
            try {
                p = _activePeers.get(_peerIndex++);
            } catch (IndexOutOfBoundsException e) {
                // Concurrent dropPeer() shrank the list
                _peerIndex = 0;
                continue;
            }
            peersProcessed++;

            // Clean up completed messages and allocate sends in one pass
            states = p.finishAndAllocate(now);
            if (!p.hasOutbound()) {
                if (!_peersToRemove.contains(p))
                    _peersToRemove.add(p);
                // Eager cleanup to prevent accumulation of dead PeerState references.
                // CopyOnWriteArrayList.removeAll() copies the backing array, so batch
                // at a reasonable threshold to balance copy cost vs memory pressure.
                if (_peersToRemove.size() >= 32) {
                    _activePeers.removeAll(_peersToRemove);
                    _peersToRemove.clear();
                }
                continue;
            }
            if (states != null) {
                peer = p;
                break;
            }

            // Track the soonest time this peer will be ready
            nextSendDelay = foldSendDelay(nextSendDelay, p.getNextDelay(now));

            // If we've gone through all peers, wait or retry
            if (peersProcessed >= _activePeers.size()) {
                // Batch remove peers to reduce CopyOnWriteArrayList copying
                if (!_peersToRemove.isEmpty()) {
                    _activePeers.removeAll(_peersToRemove);
                    _peersToRemove.clear();
                }

                if (nextSendDelay > 0) {
                    int toWait = roundWaitMs(nextSendDelay);
                    waitForMessages(toWait);
                    peersProcessed = 0;
                    nextSendDelay = Integer.MAX_VALUE;
                } else {
                    // Defensive: foldSendDelay floors every reported delay at MIN_WAIT_MS,
                    // so a round that reached a peer cannot land here with zero. Reached
                    // only if the list changed under us mid-round. Still take the floor,
                    // because the alternative - reset the index and carry straight on -
                    // is a flat spin through peers that have nothing to give.
                    _peerIndex = 0;
                    peersProcessed = 0;
                    nextSendDelay = Integer.MAX_VALUE;
                    waitForMessages(MIN_WAIT_MS);
                }
                // Emit aggregate transport stats every full round-robin cycle
                if (++_statEmitCounter >= 10) {
                    _statEmitCounter = 0;
                    long[] agg = PeerState.getAggregateStats(_activePeers);
                    if (agg != null) {
                        _context.statManager().addRateData("udp.avgSendWindow", agg[0]);
                        _context.statManager().addRateData("udp.avgRTO", agg[1]);
                        _context.statManager().addRateData("udp.avgConcurrentMsgs", agg[2]);
                        // Only the clamp matters when scheduling retransmits, so
                        // track it beside the raw estimate instead of only in it.
                        _context.statManager().addRateData("udp.avgEffectiveRTO", agg[3]);
                    }
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
            _waitLock.notifyAll();
        }
    }

/**
 *  Build the packets for one volley.
 *
 *  @param states the states a peer offered, never null here
 *  @param peer the peer they belong to
 *  @return the packets to send, or null when there is nothing to send: null states
 *         or peer, or every offered state pushed no fragment because all of its
 *         fragments are already acked. The last case is transient, which is why
 *         {@link #getNextVolley} can return null without being shut down.
     */
    private List<UDPPacket> preparePackets(List<OutboundMessageState> states, PeerState peer) {
        if (states == null || peer == null) {
            return null;
        }

        // build the list of fragments to send
        // A state usually contributes 1-2 fragments on a retransmit volley;
        // size 8 covered one, but a chain wide in states serialized the growth.
        List<Fragment> toSend = new ArrayList<>(Math.max(8, states.size() * 2));
        for (int i = 0; i < states.size(); i++) {
            OutboundMessageState state = states.get(i);
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
                _context.statManager().addRateData("udp.retransmitEvents", 1);
                if (_log.shouldInfo()) {
                    _log.info("Retransmitting..." + state + " to " + peer);
                }
                _context.statManager().addRateData("udp.sendVolleyTime", lifetime, queued);
            }
        }

        if (toSend.isEmpty()) {
            return null;
        }

        List<UDPPacket> rv = new ArrayList<>(toSend.size());

        // Greedy fragment grouping logic — index-based to avoid List copy + shift per removal
        _consumed.clear();
        int remaining = toSend.size();
        int maxPacketSize = PacketBuilder2.getMaxDataSize(peer);

        while (remaining > 0) {
            // Most data packets carry only a few fragments; pre-size to the
            // greedy picker's typical high-water mark rather than growing from 0.
            List<Fragment> sendNext = new ArrayList<>(Math.min(remaining, 4));
            int curTotalDataSize = 0;

            for (int i = 0; i < toSend.size(); i++) {
                if (_consumed.get(i)) continue;
                Fragment next = toSend.get(i);
                OutboundMessageState state = next.state;
                int nextDataSize = state.fragmentSize(next.num);
                if (next.num > 0) {
                    nextDataSize += SSU2Util.DATA_FOLLOWON_EXTRA_SIZE;
                }

                // If this is the first fragment in the packet, add the first fragment header
                nextDataSize += SSU2Payload.BLOCK_HEADER_SIZE;

                // Check if it fits
                if (curTotalDataSize + nextDataSize <= maxPacketSize || sendNext.isEmpty()) {
                    sendNext.add(next);
                    curTotalDataSize += nextDataSize;
                    _consumed.set(i);
                    remaining--;
                }
            }

            if (!sendNext.isEmpty()) {
                UDPPacket pkt = null;
                if (peer instanceof PeerState2) {
                    try {
                        pkt = _builder2.buildPacket(sendNext, (PeerState2) peer);
                    } catch (IOException ioe) {
                        pkt = null;
                    }
                }

                if (pkt != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Sent UDP packet with " + sendNext.size() + " fragments (" + curTotalDataSize +
                                   " data bytes)\n* Target: " + peer);
                    }
                    _context.statManager().addRateData("udp.sendFragmentsPerPacket", sendNext.size());
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Building UDP packet FAIL for " + DataHelper.toString(sendNext) + " to: " + peer);
                    }
                    // Don't retry in this volley — peer session is dead/invalid.
                    // Fragments remain in OutboundMessageState and will be
                    // retried on the next getNextVolley() cycle.
                    _context.statManager().addRateData("udp.sendFailed", 1);
                    break;
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
            _log.debug("Sent " + toSend.size() + " fragments of " + states.size() +
                       " messages in " + sent + " packets\n* Target: " + peer);
        }

        return rv;
    }

/**
 *  Fold one peer's reported delay into the smallest delay seen so far this round.
 *
 *  <p>A peer reports {@code 0} to mean it wants a prompt retry: its retransmit timer has
 *  elapsed, or it is holding a message past its lifetime and should be failed out. That is a
 *  reason to retry soon, not a reason not to wait.
 *
 *  <p>Folding {@code 0} in literally is what made this loop burn a core. The round minimum
 *  started at {@link Integer#MAX_VALUE} and was only ever lowered, so a single peer reporting
 *  {@code 0} pinned it there for everyone. The end-of-round test is {@code nextSendDelay > 0},
 *  which then failed and took the branch that re-scanned the same peers with no blocking at
 *  all, once per iteration, for as long as that peer stayed in that state.
 *
 *  @param runningMin smallest delay seen so far this round
 *  @param reportedDelay what this peer reported
 *  @return the updated minimum, never below {@link #MIN_WAIT_MS} and never zero
 *  @since 0.9.71+
 */
    static int foldSendDelay(int runningMin, int reportedDelay) {
        if (reportedDelay <= 0) {return Math.min(runningMin, MIN_WAIT_MS);}
        return Math.min(runningMin, reportedDelay);
    }

    /**
     *  How long to block after a full round allocated nothing.
     *
     *  <p>Always at least {@link #MIN_WAIT_MS}, so a round that produced nothing cannot turn
     *  into a spin, and never more than {@link #MAX_WAIT}, so one peer reporting a long delay
     *  cannot stall the pool.
     *
     *  @param roundMin result of folding every peer's delay this round
     *  @return milliseconds to block
     *  @since 0.9.71+
 */
    static int roundWaitMs(int roundMin) {
        return Math.min(Math.max(roundMin, MIN_WAIT_MS), MAX_WAIT);
    }

private void waitForMessages() {
    synchronized (_waitLock) {
        try {
            _waitLock.wait(MAX_WAIT);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
            Thread.currentThread().interrupt();
            if (_log.shouldDebug()) {
                _log.debug("Woken up while waiting");
            }
        }
    }
}


}
