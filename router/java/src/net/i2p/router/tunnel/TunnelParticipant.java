package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterThrottleImpl;
import net.i2p.util.Log;
import net.i2p.util.SyntheticREDQueue;

/**
 * Participates in a tunnel at an intermediate hop (not gateway or endpoint).
 * Handles message dispatching, bandwidth throttling, fragment reassembly,
 * and next-hop routing.
 *
 * Optimized for hosting many tunnels efficiently.
 */
class TunnelParticipant {
    private final RouterContext _context;
    private final Log _log;
    private final HopConfig _config;
    private final HopProcessor _processor;

    private static String formatBandwidth(int bps) {
        if (bps >= 1000000000) {
            return String.format("%.2fGB/s", bps / 1000000000.0);
        } else if (bps >= 1000000) {
            return String.format("%.2fMB/s", bps / 1000000.0);
        } else if (bps >= 1000) {
            return String.format("%.2fKB/s", bps / 1000.0);
        } else {
            return bps + "B/s";
        }
    }
    private final InboundEndpointProcessor _inboundEndpointProcessor;
    private final InboundMessageDistributor _inboundDistributor;
    private final FragmentHandler _handler;
    private final SyntheticREDQueue _partBWE;

    private volatile RouterInfo _nextHopCache;

    private static final long MAX_LOOKUP_TIME = 15 * 1000;
    private static final long LONG_MAX_LOOKUP_TIME = 25 * 1000;
    private static final int PRIORITY = OutNetMessage.PRIORITY_PARTICIPATING;
    // 200 messages * 2KB in 10 minutes = 340 Bps - optimized for high bandwidth contexts
    static final int DEFAULT_BW_PER_TUNNEL_ESTIMATE = RouterThrottleImpl.DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE * 2048 / (10 * 60);

    /**
     * Construct for intermediate tunnel participant (not endpoint).
     */
    public TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor) {
        this(ctx, config, processor, null);
    }

    /**
     * Construct for inbound tunnel endpoint.
     */
    public TunnelParticipant(RouterContext ctx, InboundEndpointProcessor inEndProc) {
        this(ctx, null, null, inEndProc);
    }

    /**
     * Internal constructor shared by all variants.
     */
    private TunnelParticipant(RouterContext ctx, HopConfig config, HopProcessor processor,
                              InboundEndpointProcessor inEndProc) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelParticipant.class);
        _config = config;
        _processor = processor;
        _inboundEndpointProcessor = inEndProc;

        if ((config == null) || (config.getSendTo() == null)) {
            // Only inbound endpoints or endpoints needing fragment handling need this
            _handler = new FragmentHandler(ctx, new DefragmentedHandler(), true);
            _inboundDistributor = new InboundMessageDistributor(ctx, inEndProc.getDestination());
        } else {
            _handler = null;
            _inboundDistributor = null;
        }

        if (inEndProc == null && config != null) {
            // Set bandwidth and RED queue
            int oldAllocated = config.getAllocatedBW();
            int max = oldAllocated;
            int shareKBps = TunnelDispatcher.getShareBandwidth(ctx);
            if (_log.shouldInfo()) {
                _log.info("TunnelParticipant init - Allocated: " + formatBandwidth(oldAllocated) +
                          " DEFAULT: " + formatBandwidth(DEFAULT_BW_PER_TUNNEL_ESTIMATE) +
                          " Share: " + formatBandwidth(shareKBps * 1000));
            }
            int shareBps = 1000 * shareKBps;
            int reasonableMax = shareBps / 2;
            if (oldAllocated <= DEFAULT_BW_PER_TUNNEL_ESTIMATE || oldAllocated < reasonableMax / 10) {
                max = ctx.tunnelDispatcher().getMaxPerTunnelBandwidth(TunnelDispatcher.Location.PARTICIPANT);
                config.setAllocatedBW(max);
                if (_log.shouldInfo())
                    _log.info("Updated tunnel bandwidth from " + formatBandwidth(oldAllocated) + " to: " + formatBandwidth(max));
            }
            // Dynamic RED thresholds scaled to bandwidth - handle bursts without drops
            int minThreshold = Math.max(2048, max / 4);
            int maxThreshold = Math.max(8192, max);
            _partBWE = new SyntheticREDQueue(ctx, max, minThreshold, maxThreshold);
        } else {
            _partBWE = null;
        }

        if (config != null && config.getSendTo() != null) {
            _nextHopCache = ctx.netDb().lookupRouterInfoLocally(config.getSendTo());
            if (_nextHopCache == null) {
                ctx.netDb().lookupRouterInfo(config.getSendTo(), new Found(ctx), null, LONG_MAX_LOOKUP_TIME);
            }
        }
    }

    /**
     * Handle router info lookup completion.
     */
    private class Found extends JobImpl {
        public Found(RouterContext ctx) {
            super(ctx);
        }

        public String getName() {
            return "Verify Next Hop Info Found";
        }

        public void runJob() {
            if (_nextHopCache == null) {
                _nextHopCache = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                _context.statManager().addRateData("tunnel.participantLookupSuccess", 1);
            }
        }
    }

    /**
     * Process an incoming tunnel data message.
     */
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        byte[] data = msg.getData();
        boolean ok;

        if (_processor != null) {
            ok = _processor.process(data, 0, data.length, recvFrom);
        } else if (_inboundEndpointProcessor != null) {
            ok = _inboundEndpointProcessor.retrievePreprocessedData(data, 0, data.length, recvFrom);
        } else {
            ok = false;
        }

        if (!ok) {
            logDispatchFailure(msg);
            if (_config != null) {
                _config.incrementProcessedMessages();
                _config.addProcessedBytes(data.length);
            }
            return;
        }

        if (_config != null && _config.getSendTo() != null) {
            _config.incrementProcessedMessages();
            _config.addProcessedBytes(data.length);
            RouterInfo ri = _nextHopCache;
            if (ri != null) {
                send(_config, msg, ri);
                if (_log.shouldDebug()) {
                    _log.debug("Dispatched " + msg + " directly to next hop [" +
                               _config.getSendTo().toBase64().substring(0, 6) + "]");
                }
            } else {
                ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                _context.netDb().lookupRouterInfo(
                    _config.getSendTo(),
                    new SendJob(_context, msg),
                    new TimeoutJob(_context, msg),
                    MAX_LOOKUP_TIME
                );
                if (_log.shouldInfo()) {
                    _log.info("Looking up next hop [" +
                              _config.getSendTo().toBase64().substring(0, 6) + "] for " + msg);
                }
            }
        } else {
            TunnelCreatorConfig cfg = _inboundEndpointProcessor.getConfig();
            cfg.incrementProcessedMessages();
            ok = _handler.receiveTunnelMessage(data, 0, data.length);
            if (ok && _log.shouldDebug()) {
                _log.debug("Received fragment on Inbound Endpoint: " + msg);
            } else if (!ok) {
                blameTunnelPeers(cfg);
            }
        }
    }

    private void logDispatchFailure(TunnelDataMessage msg) {
        if (_log.shouldInfo()) {
            _log.warn("Failed to dispatch " + msg +
                      "\n* Processor: " + _processor +
                      "\n* Inbound Endpoint: " + _inboundEndpointProcessor);
        } else if (_log.shouldWarn()) {
            _log.warn("Failed to dispatch " + msg + " via " +
                      (_processor != null ? _processor : "NULL tunnel"));
        }
    }

    private void blameTunnelPeers(TunnelCreatorConfig cfg) {
        int lenm1 = cfg.getLength() - 1;
        if (lenm1 > 0) {
            int pct = 100 / lenm1;
            for (int i = 0; i < lenm1; i++) {
                Hash h = cfg.getPeer(i);
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel from " + toString() + " failed -> Blaming [" +
                              h.toBase64().substring(0, 6) + "] -> " + pct + '%');
                }
                _context.profileManager().tunnelFailed(h, pct);
            }
        }
    }

    public int getCompleteCount() {
        return _handler != null ? _handler.getCompleteCount() : 0;
    }

    public int getFailedCount() {
        return _handler != null ? _handler.getFailedCount() : 0;
    }

    /**
     * Clean up resources when tunnel expires or is no longer needed.
     * Cancels pending fragment timeouts and clears the fragment map.
     */
    public void destroy() {
        if (_handler != null) {
            _handler.destroy();
        }
    }

    /**
     * Callback for defragmented tunnel messages.
     */
    private class DefragmentedHandler implements FragmentHandler.DefragmentedReceiver {
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            if (_log.shouldDebug()) {
                _log.debug("Successful receipt on Inbound Endpoint of" + msg);
            }
            _inboundDistributor.distribute(msg, toRouter, toTunnel);
        }
    }

    /**
     * Forward a tunnel message to the next hop.
     * Unconditionally resets TTL to 10s unless the message is more than 60s stale.
     */
    private void send(HopConfig config, TunnelDataMessage msg, RouterInfo ri) {
        if (_context.tunnelDispatcher().shouldDropParticipatingMessage(
                TunnelDispatcher.Location.PARTICIPANT,
                TunnelDataMessage.MESSAGE_TYPE, 1024, _partBWE)) {
            return;
        }

        long now = _context.clock().now();
        long oldId = msg.getUniqueId();
        long newId = _context.random().nextLong(I2NPMessage.MAX_ID_VALUE);
        _context.messageHistory().wrap("TunnelDataMessage", oldId, "TunnelDataMessage", newId);
        msg.setUniqueId(newId);
        boolean shouldLogInfo = _log.shouldInfo();
        boolean shouldLogWarn = _log.shouldWarn();

        long originalExpiration = msg.getMessageExpiration();

        // Drop if expired more than 75s ago
        if (originalExpiration < now - 75_000) {
            if (shouldLogWarn) {
                long age = now - originalExpiration;
                _log.warn("Dropping stale" + msg + " -> Expired " + age + "ms ago (Cutoff: 75s)");
            }
            return;
        }

        // Always reset to 20s, but log why
        long expiration = now + 20_000;
        if (shouldLogInfo) {
            if (originalExpiration <= 0) {
                _log.info(msg + " had no expiration set -> Resetting to 20s...");
            } else if (originalExpiration < now) {
                long age = now - originalExpiration;
                _log.info(msg + " expired " + age + "ms ago -> Resetting to 20s...");
            }
        }

        // Update message and send
        msg.setMessageExpiration(expiration);
        msg.setTunnelId(config.getSendTunnel());

        OutNetMessage m = new OutNetMessage(_context, msg, expiration, PRIORITY, ri);
        _context.outNetMessagePool().add(m);
    }

    /**
     * Job to send a message after router lookup completes.
     */
    private class SendJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public SendJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() {
            return "Participant send after lookup";
        }

        public void runJob() {
            if (_nextHopCache != null) {
                send(_config, _msg, _nextHopCache);
            } else {
                RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
                int stat;
                if (ri != null) {
                    _nextHopCache = ri;
                    send(_config, _msg, ri);
                    stat = 1;
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Lookup of next hop [" + _config.getSendTo().toBase64().substring(0, 6) +
                                  "] failed! Where do we go for " + _config + "? -> Message dropped: " + _msg);
                    }
                    stat = 0;
                }
                _context.statManager().addRateData("tunnel.participantLookupSuccess", stat);
            }
        }
    }

    /**
     * Job to handle timeout for router lookup.
     */
    private class TimeoutJob extends JobImpl {
        private final TunnelDataMessage _msg;

        public TimeoutJob(RouterContext ctx, TunnelDataMessage msg) {
            super(ctx);
            _msg = msg;
        }

        public String getName() {
            return "Participant Next Hop Lookup Timeout";
        }

        public void runJob() {
            if (_nextHopCache != null) return;

            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(_config.getSendTo());
            if (ri != null) {
                _nextHopCache = ri;
                if (_log.shouldWarn()) {
                    _log.warn("Lookup of next hop [" + _config.getSendTo().toBase64().substring(0, 6) +
                              "] failed, but we found it!! -> " + _msg + " dropped");
                }
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Lookup of next hop [" + _config.getSendTo().toBase64().substring(0, 6) +
                              "] failed! -> " + _msg + " dropped");
                }
            }
            _context.statManager().addRateData("tunnel.participantLookupSuccess", 0);
        }
    }

    @Override
    public String toString() {
        return _config != null ? "participant at " + _config : "Inbound Endpoint";
    }
}