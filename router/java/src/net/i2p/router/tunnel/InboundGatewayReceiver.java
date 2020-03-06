package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;

/**
 *  Handle messages at the IBGW.
 *  Not used for zero-hop IBGWs.
 */
class InboundGatewayReceiver implements TunnelGateway.Receiver {
    private final RouterContext _context;
    private final HopConfig _config;
    private RouterInfo _target;

    private static final long MAX_LOOKUP_TIME = 15*1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_PARTICIPATING;

    public InboundGatewayReceiver(RouterContext ctx, HopConfig cfg) {
        _context = ctx;
        _config = cfg;
        // all createRateStat() in TunnelDispatcher
    }

    public long receiveEncrypted(byte[] encrypted) {
        return receiveEncrypted(encrypted, false);
    }

    public long receiveEncrypted(byte[] encrypted, boolean alreadySearched) {
        if (!alreadySearched)
            _config.incrementProcessedMessages();
        if (_target == null) {
            _target = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (_target == null) {
                // It should be rare to forget the router info for the next peer
                ReceiveJob j = null;
                if (alreadySearched)
                    _context.statManager().addRateData("tunnel.inboundLookupSuccess", 0);
                else
                    j = new ReceiveJob(_context, encrypted);
                _context.netDb().lookupRouterInfo(_config.getSendTo(), j, j, MAX_LOOKUP_TIME);
                return -1;
            }
        }
        if (alreadySearched)
            _context.statManager().addRateData("tunnel.inboundLookupSuccess", 1);

        // We do this before the preprocessor now (i.e. before fragmentation)
        //if (_context.tunnelDispatcher().shouldDropParticipatingMessage("IBGW", encrypted.length))
        //    return -1;
        //_config.incrementSentMessages();
        _context.bandwidthLimiter().sentParticipatingMessage(1024);
        TunnelDataMessage msg = new TunnelDataMessage(_context);
        msg.setData(encrypted);
        msg.setTunnelId(_config.getSendTunnel());

        OutNetMessage out = new OutNetMessage(_context, msg, msg.getMessageExpiration(), PRIORITY, _target);
        _context.outNetMessagePool().add(out);
        return msg.getUniqueId();
    }

    /**
     * The next hop
     * @return non-null
     * @since 0.9.3
     */
    public Hash getSendTo() {
        return _config.getSendTo();
    }

    private class ReceiveJob extends JobImpl {
        private final byte[] _encrypted;

        public ReceiveJob(RouterContext ctx, byte data[]) {
            super(ctx);
            _encrypted = data;
        }

        public String getName() { return "Lookup IBGW First Hop"; }

        public void runJob() {
            receiveEncrypted(_encrypted, true);
        }
    }
}
