package net.i2p.router.tunnel;

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
    /** The router context. */
    private final RouterContext _context;
    /** Logger for the outbound tunnel endpoint. */
    private final Log _log;
    /** The hop configuration of our tunnel. */
    private final HopConfig _config;
    /** Processes the messages for the previous hop. */
    private final HopProcessor _processor;
    /** Reassembles the fragmented lease sets. */
    private final FragmentHandler _handler;
    /** Distributes the messages we relay onward. */
    private final OutboundMessageDistributor _outDistributor;
    /** Queue estimating the outbound share used while relaying. */
    private final SyntheticREDQueue _partBWE;
    /** Lease set store messages relayed. */
    private int _lsdsm;
    /** Router info store messages relayed. */
    private int _ridsm;
    /** Other messages relayed. */
    private int _i2npmsg;
    /** Total messages relayed. */
    private int _totalmsg;

    /**
     * Binds the context, config, and processor, sizing the RED queue to the allocated bandwidth.
     *
     * @param ctx the router context
     * @param config the hop configuration, whose allocated bandwidth sizes the RED queue and
     *               may be raised to the per-tunnel maximum when it is still at its estimate
     * @param processor the inbound hop processor, used to decrypt each message before handling
     */
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

    /**
     * Decrypt and honor the instructions of an incoming tunnel data message.
     * @param msg the tunnel data message, already registered with the message registry
     * @param recvFrom the hash to blame for a decryption failure, the previous hop
     */
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        _config.incrementProcessedMessages();
        byte[] data = msg.getData();
        boolean ok = _processor.process(data, 0, data.length, recvFrom);
        if (!ok) {
            // invalid IV
            // If we pass it on to the handler, it will fail
            // If we don't, the data buf won't get released from the cache... that's ok
            if (_log.shouldInfo())
                _log.info("Invalid IV -> Dropping message at outbound endpoint " + _config.toShortString());
            return;
        }
        ok = _handler.receiveTunnelMessage(data, 0, data.length);
        if (!ok) {
            // blame previous hop
            Hash h = _config.getReceiveFrom();
            if (h != null) {
                if (_log.shouldWarn())
                    _log.warn(toString() + " -> Blaming the previous hop for the failed tunnel" +
                              " * Penalty: 50% * Peer: [" + h.toBase64().substring(0,6) + "]");
                _context.profileManager().tunnelFailed(h, 50);
            }
        }
    }

    /** Handler for the reassembled lease sets. */
    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {

        /** Handles a message reassembled from fragments. */
        private DefragmentedHandler() {}

        /**
         * Warning - as of 0.9.63, msg will be an UnknownI2NPMessage,
         * and must be converted before handling locally.
         */
        @Override
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
                _log.info("Full message received from outbound tunnel " + _config.toShortString() +
                          " -> Forwarding to [" + toRouter.toBase64().substring(0,6) + "]" +
                          (toTunnel != null ? ':' + toTunnel.getTunnelId() : "") +
                          "\n* Type: " + msg.getType() + " * MsgID: " + msg.getUniqueId());
            }
            if (toTunnel == null) {
                int msgtype = msg.getType();
                if (msgtype == DatabaseStoreMessage.MESSAGE_TYPE) {
                    DatabaseStoreMessage dsm = (DatabaseStoreMessage)msg;
                    if (!dsm.getEntry().isLeaseSet()) {
                        _ridsm++;
                        _context.statManager().addRateData("tunnel.outboundTunnelEndpointFwdRIDSM", 1);
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Outbound endpoint RouterInfo DbStoreMsg"
                                      + "\n* Count: " + _ridsm + " of " + _totalmsg
                                      + " * From tunnel: " + _config.getReceiveTunnelId()
                                      + " * To router: [" + toRouter.toBase64().substring(0,6) + "]"
                                      + "\n* " + dsm);
                    } else {
                        _lsdsm++;
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Outbound endpoint LeaseSet DbStoreMsg"
                                      + "\n* Count: " + _lsdsm + " of " + _totalmsg
                                      + " * From tunnel: " + _config.getReceiveTunnelId()
                                      + " * To router: [" + toRouter.toBase64().substring(0,6) + "]"
                                      + "\n* " + dsm);
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
            _outDistributor.distribute(msg, toRouter, toTunnel);
        }
    }

    /**
     * Tunnel summary for log messages.
     *
     * @since 0.9.8
     */
    @Override
    public String toString() {
        return "OutboundEndpoint [TunnelId " + _config.getReceiveTunnelId() + "]";
    }
}
