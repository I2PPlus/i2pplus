package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.tunnel.pool.PooledTunnelCreatorConfig;
import net.i2p.util.Log;

/**
 * We are the outbound gateway - we created this outbound tunnel.
 * Receive the outbound message after it has been preprocessed and encrypted,
 * then forward it on to the first hop in the tunnel.
 *
 * Not used for zero-hop OBGWs.
 */
class OutboundReceiver implements TunnelGateway.Receiver {
    private final RouterContext _context;
    private final Log _log;
    private final PooledTunnelCreatorConfig _config;
    private RouterInfo _nextHopCache;
    private final int _priority;
    // same job used for all messages
    private final JobImpl _sendFailJob;

    private static final long MAX_LOOKUP_TIME = 15*1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_MY_DATA;

    public OutboundReceiver(RouterContext ctx, PooledTunnelCreatorConfig cfg) {
        _context = ctx;
        _log = ctx.logManager().getLog(OutboundReceiver.class);
        _config = cfg;
        _nextHopCache = _context.floodfillNetDb().lookupRouterInfoLocally(_config.getPeer(1));
        _priority = PRIORITY + cfg.getPriority();
        _sendFailJob = new SendFailedJob(ctx);
        // all createRateStat() in TunnelDispatcher
    }

    public long receiveEncrypted(byte encrypted[]) {
        TunnelDataMessage msg = new TunnelDataMessage(_context);
        msg.setData(encrypted);
        msg.setTunnelId(_config.getConfig(0).getSendTunnel());

        if (_log.shouldDebug())
            _log.debug("Received encrypted message, sending out via " + _config + ": " + msg);
        RouterInfo ri = _nextHopCache;
        if (ri == null) {
            ri = _context.floodfillNetDb().lookupRouterInfoLocally(_config.getPeer(1));
            _nextHopCache = ri;
        }
        if (ri != null) {
            send(msg, ri);
            return msg.getUniqueId();
        } else {
            // It should be rare to forget the router info for a peer in our own tunnel.
            if (_log.shouldWarn())
                _log.warn("Lookup of [" + _config.getPeer(1).toBase64().substring(0,6) + "] required for " + msg);
            _context.floodfillNetDb().lookupRouterInfo(_config.getPeer(1), new SendJob(_context, msg),
                                              new LookupFailedJob(_context), MAX_LOOKUP_TIME);
            return -1;
        }
    }

    /**
     * The next hop
     * @return non-null
     * @since 0.9.3
     */
    public Hash getSendTo() {
        return _config.getPeer(1);
    }

    private void send(TunnelDataMessage msg, RouterInfo ri) {
        if (_log.shouldDebug())
            _log.debug("Forwarding encrypted message via " + _config + " [MsgID" + msg.getUniqueId() + "]");
        OutNetMessage m = new OutNetMessage(_context, msg, msg.getMessageExpiration(), _priority, ri);
        // set a job to fail the tunnel if we can't send the message
        m.setOnFailedSendJob(_sendFailJob);
        _context.outNetMessagePool().add(m);
        _config.incrementProcessedMessages();
    }

    private class SendJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public SendJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() { return "Send to OBGW after Lookup"; }

        public void runJob() {
            RouterInfo ri = _context.floodfillNetDb().lookupRouterInfoLocally(_config.getPeer(1));
            if (_log.shouldDebug())
                _log.debug("Lookup of [" + _config.getPeer(1).toBase64().substring(0,6) + "] successful? " + (ri != null));
            int stat;
            if (ri != null) {
                _nextHopCache = ri;
                send(_msg, ri);
                stat = 1;
            } else {
                stat = 0;
            }
            _context.statManager().addRateData("tunnel.outboundLookupSuccess", stat);
        }
    }

    /**
     *  Immediately fail the tunnel if the lookup fails.
     *  This should be very rare, we should always have the RI locally.
     */
    private class LookupFailedJob extends JobImpl {
        public LookupFailedJob(RouterContext ctx) {
            super(ctx);
        }

        public String getName() { return "Timeout OBGW Lookup"; }

        public void runJob() {
            if (_log.shouldWarn())
                _log.warn("Lookup of [" + _config.getPeer(1).toBase64().substring(0,6) + "] failed for " + _config);
            _context.statManager().addRateData("tunnel.outboundLookupSuccess", 0);
            _config.tunnelFailedFirstHop();
        }
    }

    /**
     *  Immediately fail the tunnel if the send fails
     *
     *  @since 0.9.53
     */
    private class SendFailedJob extends JobImpl {
        public SendFailedJob(RouterContext ctx) {
            super(ctx);
        }

        public String getName() { return "OBGW Send Failure"; }

        public void runJob() {
            if (_log.shouldWarn())
                _log.warn("Send to [" + _config.getPeer(1).toBase64().substring(0,6) + "] failed for " + _config);
            _config.tunnelFailedFirstHop();
        }
    }
}
