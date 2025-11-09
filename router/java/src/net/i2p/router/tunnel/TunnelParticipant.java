package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterThrottleImpl;
import net.i2p.util.Log;
import net.i2p.util.SyntheticREDQueue;

/**
 * Participate in a tunnel at a location other than the gateway or outbound
 * endpoint.  This participant should be provided with the necessary processor
 * if it is an inbound tunnel endpoint, and that will enable the
 * InboundMessageDistributor to receive defragmented and decrypted messages,
 * which it will then selectively forward.
 */
class TunnelParticipant {
    private final RouterContext _context;
    private final Log _log;
    private final HopConfig _config;
    private final HopProcessor _processor;
    private final InboundEndpointProcessor _inboundEndpointProcessor;
    private final InboundMessageDistributor _inboundDistributor;
    private final FragmentHandler _handler;
    private final SyntheticREDQueue _partBWE;
    private RouterInfo _nextHopCache;

    private static final long MAX_LOOKUP_TIME = 15*1000;
    /** for next hop when a tunnel is first created */
    private static final long LONG_MAX_LOOKUP_TIME = 25*1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_PARTICIPATING;
    /** @since 0.9.68 from BuildHandler */
    static final int DEFAULT_BW_PER_TUNNEL_ESTIMATE = RouterThrottleImpl.DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE * 2048 / (10*60);

    /** not an inbound endpoint */
    public TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor) {
        this(ctx, config, processor, null);
    }

    /** inbound endpoint */
    public TunnelParticipant(RouterContext ctx, InboundEndpointProcessor inEndProc) {
        this(ctx, null, null, inEndProc);
    }

    /**
     * @param config may be null (inbound endpoint if null)
     * @param processor may be null (inbound endpoint if null)
     * @param inEndProc may be null (inbound endpoint if non-null)
     */
    private TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor, InboundEndpointProcessor inEndProc) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelParticipant.class);
        _config = config;
        _processor = processor;
        if ((config == null) || (config.getSendTo() == null)) {
            _handler = new FragmentHandler(ctx, new DefragmentedHandler(), true);
        } else {_handler = null;} // final

        _inboundEndpointProcessor = inEndProc;
        if (inEndProc != null) {
            _inboundDistributor = new InboundMessageDistributor(ctx, inEndProc.getDestination());
        } else {_inboundDistributor = null;} // final

        if ((_config != null) && (_config.getSendTo() != null)) {
            _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (_nextHopCache == null) {
                _context.netDb().lookupRouterInfo(_config.getSendTo(), new Found(_context), null, LONG_MAX_LOOKUP_TIME);
            }
        }
        if (inEndProc == null) {
            int max = _config.getAllocatedBW();
            if (max <= DEFAULT_BW_PER_TUNNEL_ESTIMATE) {
                max = _context.tunnelDispatcher().getMaxPerTunnelBandwidth(TunnelDispatcher.Location.PARTICIPANT);
                _config.setAllocatedBW(max);
            }
            _partBWE = new SyntheticREDQueue(_context, max);
        } else {
            _partBWE = null;
        }
        // all createRateStat() in TunnelDispatcher
    }

    private class Found extends JobImpl {
        public Found(RouterContext ctx) { super(ctx);}
        public String getName() {return "Verify Next Hop Info Found";}
        public void runJob() {
            if (_nextHopCache == null) {
                _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                // nothing for failure since fail job is null
                _context.statManager().addRateData("tunnel.participantLookupSuccess", 1);
            }
        }
    }

    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        boolean ok = false;
        byte[] data = msg.getData();
        if (_processor != null) {ok = _processor.process(data, 0, data.length, recvFrom);}
        else if (_inboundEndpointProcessor != null) {
            ok = _inboundEndpointProcessor.retrievePreprocessedData(data, 0, data.length, recvFrom);
        }

        if (!ok) {
            if (_log.shouldInfo()) {
                _log.warn("Failed to dispatch " + msg + "\n* Processor: " + _processor + "\n* Inbound Endpoint: " +
                          _inboundEndpointProcessor);
            } else if (_log.shouldWarn()) {
                _log.warn("Failed to dispatch " + msg + " via " + (_processor != null ? _processor : "NULL tunnel"));
            }
            if (_config != null) {_config.incrementProcessedMessages();}
            return;
        }

        if ((_config != null) && (_config.getSendTo() != null)) {
            _config.incrementProcessedMessages();
            RouterInfo ri = _nextHopCache;
            if (ri != null) {
                send(_config, msg, ri);
                if (_log.shouldDebug()) {
                    _log.debug("Dispatched " + msg + " directly to nextHop [" + _config.getSendTo().toBase64().substring(0,6) + "]");
                }
            } else {
                ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                _context.netDb().lookupRouterInfo(_config.getSendTo(), new SendJob(_context, msg),
                                                  new TimeoutJob(_context, msg), MAX_LOOKUP_TIME);
                // It should be rare to forget the router info for the next peer
                if (_log.shouldInfo()) {
                    _log.info("Looking up nextHop [" + _config.getSendTo().toBase64().substring(0,6) + "] for " + msg);
                }
            }
        } else {
            TunnelCreatorConfig cfg = _inboundEndpointProcessor.getConfig(); // IBEP
            cfg.incrementProcessedMessages();
            ok = _handler.receiveTunnelMessage(data, 0, data.length);
            if (ok && _log.shouldDebug()) {
                String configStr = _config != null ? _config.toString() : "Inbound Endpoint";
                _log.debug("Received fragment on " + configStr + ": " + msg);
            }
            else if (!ok) {
                // blame everybody equally
                int lenm1 = cfg.getLength() - 1;
                if (lenm1 > 0) {
                    int pct = 100 / (lenm1);
                    for (int i = 0; i < lenm1; i++) {
                        Hash h = cfg.getPeer(i);
                        if (_log.shouldWarn()) {
                            _log.warn("Tunnel from " + toString() + " failed -> Blaming [" + h.toBase64().substring(0,6) + "] -> " + pct + '%');
                        }
                        _context.profileManager().tunnelFailed(h, pct);
                    }
                }
            }
        }
    }

    /** getCompleteCount */
    public int getCompleteCount() {
        if (_handler != null) {return _handler.getCompleteCount();}
        else {return 0;}
    }

    public int getFailedCount() {
        if (_handler != null) {return _handler.getFailedCount();}
        else {return 0;}
    }

    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            if (_log.shouldDebug()) {
                String configStr = _config != null ? _config.toString() : "Inbound Endpoint";
                _log.debug("Receive complete on " + configStr + ": " + msg);
            }
            _inboundDistributor.distribute(msg, toRouter, toTunnel);
        }
    }

    private void send(HopConfig config, TunnelDataMessage msg, RouterInfo ri) {
        if (_context.tunnelDispatcher().shouldDropParticipatingMessage(TunnelDispatcher.Location.PARTICIPANT,
                                                                       TunnelDataMessage.MESSAGE_TYPE, 1024, _partBWE)) {
            return;
        }
        //_config.incrementSentMessages();
        long oldId = msg.getUniqueId();
        long newId = _context.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        _context.messageHistory().wrap("TunnelDataMessage", oldId, "TunnelDataMessage", newId);
        msg.setUniqueId(newId);
        msg.setMessageExpiration(_context.clock().now() + 10*1000);
        msg.setTunnelId(config.getSendTunnel());
        OutNetMessage m = new OutNetMessage(_context, msg, msg.getMessageExpiration(), PRIORITY, ri);
        if (_log.shouldDebug()) {
            String configStr = _config != null ? _config.toString() : "Inbound Endpoint";
            _log.debug("Forward on from " + configStr + ": " + msg);
        }
        _context.outNetMessagePool().add(m);
    }

    private class SendJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public SendJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() {return "Participant send after lookup";}

        public void runJob() {
            if (_nextHopCache != null) {send(_config, _msg, _nextHopCache);}
            else {
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                int stat;
                if (ri != null) {
                    _nextHopCache = ri;
                    send(_config, _msg, ri);
                    stat = 1;
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Lookup of nextHop [" + _config.getSendTo().toBase64().substring(0,6) + "] failed! " +
                                  "Where do we go for " + _config + "?  -> Message dropped: " + _msg);
                    }
                    stat = 0;
                }
                _context.statManager().addRateData("tunnel.participantLookupSuccess", stat);
            }
        }
    }

    private class TimeoutJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public TimeoutJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() {return "Participant next hop lookup timeout";}

        public void runJob() {
            if (_nextHopCache != null) {return;}

            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                _nextHopCache = ri;
                if (_log.shouldWarn()) {
                    _log.warn("Lookup of nextHop [" + _config.getSendTo().toBase64().substring(0,6) + "] failed, but we found it!! " +
                              "Where do we go for " + _config + "? -> Message dropped: " + _msg);
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("Lookup of nextHop [" + _config.getSendTo().toBase64().substring(0,6) + "] failed! " +
                              "Where do we go for " + _config + "? -> Message dropped: " + _msg);
            }
            _context.statManager().addRateData("tunnel.participantLookupSuccess", 0);
        }
    }

    @Override
    public String toString() {
        if (_config != null) {
            StringBuilder buf = new StringBuilder(64);
            buf.append("participant at ").append(_config.toString());
            return buf.toString();
        } else {return "Inbound Endpoint";}
    }

}
