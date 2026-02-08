package net.i2p.router.tunnel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.i2np.UnknownI2NPMessage;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SyntheticREDQueue;

/**
 * We are the end of an outbound tunnel that we did not create.
 * Gather fragments and honor the instructions as received.
 */
class OutboundTunnelEndpoint {
    private final RouterContext _context;
    private final Log _log;
    private final HopConfig _config;
    private final HopProcessor _processor;
    private final FragmentHandler _handler;
    private final OutboundMessageDistributor _outDistributor;
    private final SyntheticREDQueue _partBWE;
    private int _lsdsm, _ridsm, _i2npmsg, _totalmsg;

    private static final int CORRUPT_THRESHOLD = 3;
    private static final long CORRUPT_WINDOW_MS = 10 * 60 * 1000;
    private static final long BAN_DURATION_MS = 8 * 60 * 60 * 1000;
    private final Map<Hash, Deque<Long>> _corruptTimestamps = new ConcurrentHashMap<Hash, Deque<Long>>();

    public OutboundTunnelEndpoint(RouterContext ctx, HopConfig config, HopProcessor processor) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundTunnelEndpoint.class);
        _config = config;
        _processor = processor;
        _handler = new FragmentHandler(ctx, new DefragmentedHandler(), false);
        int oldAllocated = _config.getAllocatedBW();
        int allocated = oldAllocated;
        int shareBps = 1000 * TunnelDispatcher.getShareBandwidth(_context);
        int reasonableMax = shareBps / 2;
        if (oldAllocated <= TunnelParticipant.DEFAULT_BW_PER_TUNNEL_ESTIMATE || 
            oldAllocated < reasonableMax / 10) {
            allocated = _context.tunnelDispatcher().getMaxPerTunnelBandwidth(TunnelDispatcher.Location.OBEP);
            _config.setAllocatedBW(allocated);
        }
        int effectiveBw = allocated;
        // Dynamic RED thresholds scaled to bandwidth - handle bursts without drops
        int minThreshold = Math.max(2048, effectiveBw / 4);
        int maxThreshold = Math.max(8192, effectiveBw);
        _partBWE = new SyntheticREDQueue(_context, effectiveBw, minThreshold, maxThreshold);
        _outDistributor = new OutboundMessageDistributor(ctx, OutNetMessage.PRIORITY_PARTICIPATING, _partBWE);
        _totalmsg = _lsdsm = _ridsm = _i2npmsg = 0;
    }

    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        byte[] data = msg.getData();
        _config.incrementProcessedMessages();
        _config.addProcessedBytes(data.length);
        boolean ok = _processor.process(data, 0, data.length, recvFrom);
        if (!ok) {
            // invalid IV
            // If we pass it on to the handler, it will fail
            // If we don't, the data buf won't get released from the cache... that's ok
            if (_log.shouldInfo())
                _log.info("Invalid IV, dropping at Outbound Endpoint... " + _config);
            return;
        }
        ok = _handler.receiveTunnelMessage(data, 0, data.length);
        if (!ok) {
            Hash h = _config.getReceiveFrom();
            if (h != null) {
                if (_log.shouldWarn())
                    _log.warn("Tunnel from " + toString() + " failed -> Blaming [" + h.toBase64().substring(0,6) + "] -> 50%");
                _context.profileManager().tunnelFailed(h, 50);
                trackCorruptAndBan(h);
            }
        }
    }

    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {

        /**
         *  Warning - as of 0.9.63, msg will be an UnknownI2NPMessage,
         *  and must be converted before handling locally.
         */
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _totalmsg++;
            if (toRouter == null) {
                // Delivery type LOCAL is not supported at the OBEP
                // We don't have any use for it yet.
                // Don't send to OutboundMessageDistributor.distribute() which will NPE or fail
                if (_log.shouldWarn())
                    _log.warn("Dropping messsage at Outbound Endpoint -> Unsupported delivery instruction type (LOCAL)");
                return;
            }

            int type = msg.getType();
            if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
                // If UnknownI2NPMessage, convert it.
                // See FragmentHandler.receiveComplete()
                if (msg instanceof UnknownI2NPMessage) {
                    try {
                        UnknownI2NPMessage umsg = (UnknownI2NPMessage) msg;
                        msg = umsg.convert();
                    } catch (I2NPMessageException ime) {
                        if (_log.shouldInfo())
                            _log.info("Unable to convert to standard message class at zero-hop Inbound Gateway \n* " + ime.getMessage());
                        return;
                    }
                }
                DatabaseStoreMessage dsm = (DatabaseStoreMessage) msg;
                DatabaseEntry entry = dsm.getEntry();
                if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
                    long now = _context.clock().now();
                    long date = entry.getDate();
                    if (date < now - 60*60*1000L) {
                        if (_log.shouldWarn()) {
                            _log.warn("Dropping " + (toTunnel == null ? "DIRECT" : "") +
                                      " DbStoreMsg of stale RouterInfo [" + dsm.getKey().toBase64().substring(0,6) +
                                      "] at Outbound Endpoint to Router [" + toRouter.toBase64().substring(0,6) + "]");
                        }
                        return;
                    } else if (date > now + 2*60*1000L) {
                        if (_log.shouldWarn()) {
                            _log.warn("Dropping " + (toTunnel == null ? "DIRECT" : "") +
                                      " DbStoreMsg of future RouterInfo [" + dsm.getKey().toBase64().substring(0,6) +
                                      "] at Outbound Endpoint to Router [" + toRouter.toBase64().substring(0,6) + "]");
                        }
                        return;
                    }
                }
            }

            if (_log.shouldInfo()) {
                _log.info("Full message received from Outbound tunnel " + _config + "\n* " + msg +
                          " to be forwarded to [" + toRouter.toBase64().substring(0,6) + "]" +
                          (toTunnel != null ? ":" + toTunnel.getTunnelId() : ""));
            }
            if (toTunnel == null) {
                int msgtype = msg.getType();
                if (msgtype == DatabaseStoreMessage.MESSAGE_TYPE) {
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage)msg;
                    if (!dsm.getEntry().isLeaseSet()) {
                        _ridsm++;
                        _context.statManager().addRateData("tunnel.outboundTunnelEndpointFwdRIDSM", 1);
                        if (_log.shouldLog(Log.INFO))
                            _log.info("OutboundEndpoint RouterInfo DbStoreMsg (Count: " +
                                      _ridsm + "/" + _totalmsg + ") from [TunnelId " + _config.getReceiveTunnelId() + "] " +
                                      "to Router [" + toRouter.toBase64().substring(0,6) + "]\n* " + dsm);
                    } else {
                        _lsdsm++;
                        if (_log.shouldLog(Log.INFO))
                            _log.info("OutboundEndpoint LeaseSet DbStoreMsg (Count: " + _lsdsm + "/" + _totalmsg + ") " +
                                      "from [TunnelId " + _config.getReceiveTunnelId() + "] to Router " +
                                      toRouter.toBase64().substring(0,6) + "]\n* " + dsm);
                    }
                } else {
                    _i2npmsg++;
                    if (_log.shouldLog(Log.INFO))
                        _log.info("OutboundEndpoint I2NP Message (Count: " + _i2npmsg + "/" + _totalmsg + ") from [TunnelId " +
                                  _config.getReceiveTunnelId() + "] to Router [" + toRouter.toBase64().substring(0,6) + "]\n* " + msg);
                }
            }
            int size = msg.getMessageSize();
            // don't drop it if we are the target
            boolean toUs = _context.routerHash().equals(toRouter);
            if (!toUs) {
                if (_context.tunnelDispatcher().shouldDropParticipatingMessage(TunnelDispatcher.Location.OBEP, type, size, _partBWE)) {
                    return;
                }
            }
            // this overstates the stat somewhat, but ok for now
            //int kb = (size + 1023) / 1024;
            //for (int i = 0; i < kb; i++)
            //    _config.incrementSentMessages();
            _outDistributor.distribute(msg, toRouter, toTunnel);
        }
    }

    private void trackCorruptAndBan(Hash peer) {
        long now = _context.clock().now();
        Deque<Long> timestamps = _corruptTimestamps.get(peer);
        if (timestamps == null) {
            timestamps = new ArrayDeque<Long>();
            _corruptTimestamps.put(peer, timestamps);
        }
        while (!timestamps.isEmpty() && timestamps.peekFirst() < now - CORRUPT_WINDOW_MS) {
            timestamps.pollFirst();
        }
        timestamps.addLast(now);
        if (timestamps.size() >= CORRUPT_THRESHOLD) {
            if (_log.shouldWarn())
                _log.warn("Banning [" + peer.toBase64().substring(0,6) + "] for corrupt fragments (" + timestamps.size() + " in " + (CORRUPT_WINDOW_MS/60000) + " min)");
            _context.banlist().banlistRouter(peer, "Corrupt tunnel fragments", null, null, now + BAN_DURATION_MS);
            timestamps.clear();
        }
    }

    /** @since 0.9.8 */
    @Override
    public String toString() {
        return "OutboundEndpoint [TunnelId " + _config.getReceiveTunnelId() + "]";
    }
}
