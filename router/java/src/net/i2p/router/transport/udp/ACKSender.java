package net.i2p.router.transport.udp;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Blocking thread that is given peers by the inboundFragment pool, sending out
 * any outstanding ACKs.
 * The ACKs are sent directly to UDPSender,
 * bypassing OutboundMessageFragments and PacketPusher.
 */
class ACKSender implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final PacketBuilder _builder;
    /** list of peers (PeerState) who we have received data from but not yet ACKed to */
    private final BlockingQueue<PeerState> _peersToACK;
    private volatile boolean _alive;
    private static final long POISON_PS = -9999999999l;

    /** how frequently do we want to send ACKs to a peer? */
    static final int ACK_FREQUENCY = 150;

    public ACKSender(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(ACKSender.class);
        _transport = transport;
        _peersToACK = new LinkedBlockingQueue<PeerState>();
        _builder = new PacketBuilder(_context, transport);
        _alive = true;
        _context.statManager().createRateStat("udp.sendACKCount", "Number of ACK messages sent to a peer", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.ackFrequency", "How long ago we sent an ACK to this peer", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.sendACKRemaining", "Remaining ACKs to send when we ACK a peer", "Transport [UDP]", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.abortACK", "How often already sent (piggybacked) ACKs scheduled", "Transport [UDP]", UDPTransport.RATES);
    }

    /**
     *  Add to the queue.
     *  For speed, don't check for duplicates here.
     *  The runner will remove them in its own thread.
     */
    public void ackPeer(PeerState peer) {
        if (_alive)
            _peersToACK.offer(peer);
    }

    public synchronized void startup() {
        _alive = true;
        _peersToACK.clear();
        I2PThread t = new I2PThread(this, "UDPACKSender", true);
        t.setPriority(I2PThread.MAX_PRIORITY - 1);
        t.start();
    }

    public synchronized void shutdown() {
        _alive = false;
        PeerState poison = new PeerState(_context, _transport, new byte[4], 0, null, false, 0);
        poison.setTheyRelayToUsAs(POISON_PS);
        _peersToACK.offer(poison);
        for (int i = 1; i <= 5 && !_peersToACK.isEmpty(); i++) {
            try {
//                Thread.sleep(i * 50);
                Thread.sleep(i * 30);
            } catch (InterruptedException ie) {}
        }
        _peersToACK.clear();
    }

    private static long ackFrequency(long timeSinceACK, long rtt) {
        // if we are actively pumping lots of data to them, we can depend upon
        // the unsentACKThreshold to figure out when to send an ACK instead of
        // using the timer, so we can set the timeout/frequency higher
        if (timeSinceACK < 2*1000)
            return Math.max(rtt/2, ACK_FREQUENCY);
        else
            return ACK_FREQUENCY;
    }

    public void run() {
        try {
            run2();
        } finally {
            // prevent OOM on thread death
            if (_alive) {
                _alive = false;
                _log.error("ACK Sender died");
            }
        }
    }

    private void run2() {
        // we use a Set to strip out dups that come in on the Queue
        Set<PeerState> notYet = new HashSet<PeerState>();
        while (_alive) {
            PeerState peer = null;
            long now = 0;
            long remaining = -1;
            long wanted = 0;

                while (_alive) {
                    // Pull from the queue until we find one ready to ack
                    // Any that are not ready we will put back on the queue
                    PeerState cur = null;
                    try {
                        if (notYet.isEmpty())
                            // wait forever
                            cur = _peersToACK.take();
                        else
                            // Don't wait if nothing there, just put everybody back and sleep below
                            cur = _peersToACK.poll();
                    } catch (InterruptedException ie) {}

                    if (cur != null) {
                        if (cur.getTheyRelayToUsAs() == POISON_PS)
                            return;
                        wanted = cur.getWantedACKSendSince();
                        now = _context.clock().now();
                        long delta = wanted + ackFrequency(now-cur.getLastACKSend(), cur.getRTT()) - now;
                        if (wanted <= 0) {
                            // it got acked by somebody - discard, remove any dups, and go around again
                            notYet.remove(cur);
                        } else if ( (delta <= 0) || (cur.unsentACKThresholdReached()) ) {
                            // found one to ack
                            peer = cur;
                            notYet.remove(cur); // in case a dup
                            try {
                                // bulk operations may throw an exception
                                _peersToACK.addAll(notYet);
                            } catch (NoSuchElementException nsee) {}
                            notYet.clear();
                            break;
                        } else {
                            // not yet, go around again
                            // moving from the Queue to the Set and then back removes duplicates
                            boolean added = notYet.add(cur);
                            if (added && _log.shouldLog(Log.DEBUG))
                                _log.debug("Pending ACK (delta: " + delta + ") for " + cur);
                        }
                    } else if (!notYet.isEmpty()) {
                        // put them all back and wait a while
                        try {
                            // bulk operations may throw an exception
                            _peersToACK.addAll(notYet);
                        } catch (RuntimeException e) {}
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Sleeping... [Pending size = " + notYet.size() + "]");
                        notYet.clear();
                        try {
                            // sleep a little longer than the divided frequency,
                            // so it will be ready after we circle around a few times
                            Thread.sleep(5 + (ACK_FREQUENCY / 3));
                        } catch (InterruptedException ie) {}
                    } // else go around again where we will wait at take()
                } // inner while()

            if (peer != null) {
                long lastSend = peer.getLastACKSend();
                // set above before the break
                //long wanted = peer.getWantedACKSendSince();
                List<ACKBitfield> ackBitfields = peer.retrieveACKBitfields(false);

                if (wanted < 0) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Why are we acking something they don't want? [Remaining=" + remaining + ", Peer=" + peer + ", Bitfields=" + ackBitfields + "]");
                    continue;
                }

                if (!ackBitfields.isEmpty()) {
                    _context.statManager().addRateData("udp.sendACKCount", ackBitfields.size());
                    if (remaining > 0)
                        _context.statManager().addRateData("udp.sendACKRemaining", remaining);
                    // set above before the break
                    //now = _context.clock().now();
                    if (lastSend < 0)
                        lastSend = now - 1;
                    _context.statManager().addRateData("udp.ackFrequency", now-lastSend, now-wanted);
                    //_context.statManager().getStatLog().addData(peer.getRemoteHostId().toString(), "udp.peer.sendACKCount", ackBitfields.size());
                    UDPPacket ack = _builder.buildACK(peer, ackBitfields);
                    ack.markType(1);
                    ack.setFragmentCount(-1);
                    ack.setMessageType(PacketBuilder.TYPE_ACK);

                    if (_log.shouldLog(Log.INFO))
                        _log.info("Sending to " + peer + ":\n* " + ackBitfields);
                    // locking issues, we ignore the result, and acks are small,
                    // so don't even bother allocating
                    //peer.allocateSendingBytes(ack.getPacket().getLength(), true);
                    // ignore whether its ok or not, its a bloody ack.  this should be fixed, probably.
                    _transport.send(ack);

                    if ( (wanted > 0) && (wanted <= peer.getWantedACKSendSince()) ) {
                        // still full packets left to be ACKed, since wanted time
                        // is reset by retrieveACKBitfields when all of the IDs are
                        // removed
                        if (_log.shouldInfo())
                            _log.info("Precautionary rerequest ACK for peer " + peer);
                        ackPeer(peer);
                    }
                } else {
                    _context.statManager().addRateData("udp.abortACK", 1);
                }
            }
        }
    }
}
