package net.i2p.router.tunnel;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterThrottleImpl;
import net.i2p.router.Service;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.tunnel.pool.PooledTunnelCreatorConfig;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SyntheticREDQueue;

/**
 * Handle the actual processing and forwarding of messages through the various tunnels.
 *
 *<pre>
 *  For each type of tunnel, it creates a chain of handlers, as follows:
 *
 *  Following tunnels are created by us:
 *
 *    Outbound Gateway &gt; 0 hops:
 *       PumpedTunnelGateway
 *         BatchedRouterPreprocessor -&gt; OutboundSender -&gt; OutboundReceiver -&gt; OutNetMessagePool
 *
 *    Outbound zero-hop Gateway+Endpoint:
 *       TunnelGatewayZeroHop
 *         OutboundMessageDistributor -&gt; OutNetMessagePool
 *
 *    Inbound Endpoint &gt; 0 hops:
 *       TunnelParticipant
 *        RouterFragmentHandler -&gt;  InboundEndpointProcessor -&gt; InboundMessageDistributor -&gt; InNetMessagePool
 *
 *    Inbound zero-hop Gateway+Endpoint:
 *       TunnelGatewayZeroHop
 *         InboundMessageDistributor -&gt; InNetMessagePool
 *
 *
 *  Following tunnels are NOT created by us:
 *
 *    Participant (not gateway or endpoint)
 *       TunnelParticipant
 *         HopProcessor -&gt; OutNetMessagePool
 *
 *    Outbound Endpoint &gt; 0 hops:
 *       OutboundTunnelEndpoint
 *         RouterFragmentHandler -&gt; HopProcessor -&gt; OutboundMessageDistributor -&gt; OutNetMessagePool
 *
 *    Inbound Gateway &gt; 0 hops:
 *       ThrottledPumpedTunnelGateway
 *         BatchedRouterPreprocessor -&gt; InboundSender -&gt; InboundGatewayReceiver -&gt; OutNetMessagePool
 *
 *</pre>
 */
public class TunnelDispatcher implements Service {
    private final RouterContext _context;
    private final Log _log;

    /** Map of outbound gateways we created */
    private final ConcurrentHashMap<TunnelId, TunnelGateway> _outboundGateways = new ConcurrentHashMap<>();

    /**
     * @return true if we currently have an outbound gateway for the given TunnelId.
     * This helps callers determine if a tunnel is ready for outbound dispatch.
     */
    public boolean hasOutboundGateway(TunnelId tid) {
        return _outboundGateways.containsKey(tid);
    }

    /** Map of outbound endpoints we joined */
    private final ConcurrentHashMap<TunnelId, OutboundTunnelEndpoint> _outboundEndpoints = new ConcurrentHashMap<>();

    /** Map of tunnel participants (either IBEP or middle hop) */
    private final ConcurrentHashMap<TunnelId, TunnelParticipant> _participants = new ConcurrentHashMap<>();

    /** Map of inbound gateways (IBGW or zero-hop tunnels) */
    private final ConcurrentHashMap<TunnelId, TunnelGateway> _inboundGateways = new ConcurrentHashMap<>();

    /** Configurations for tunnels we are participating in but not creating */
    private final ConcurrentHashMap<TunnelId, HopConfig> _participatingConfig = new ConcurrentHashMap<>();

    /** Timestamp of the last expiration of a participating tunnel */
    private volatile long _lastParticipatingExpiration;

    /** Validator used for tunnel IVs */
    private BloomFilterIVValidator _validator;

    /** Job to expire tunnels we are participating in */
    private final LeaveTunnel _leaveJob;

    /** Pumper used to drive tunnel message processing */
    private final TunnelGatewayPumper _pumper;

    /** Recently expired tunnel IDs to suppress warnings */
    private final ConcurrentHashMap<TunnelId, Long> _recentlyExpired = new ConcurrentHashMap<>();

    /** Tunnels currently being removed - prevents duplicate removal calls */
    private final ConcurrentHashMap<TunnelId, Long> _beingRemoved = new ConcurrentHashMap<>();

    /** Config identity codes currently being removed - more robust tracking */
    private final Set<Integer> _configsBeingRemoved = ConcurrentHashMap.newKeySet();

    /** Time window (in ms) for suppressing warnings on recently expired tunnels */
    private static final long RECENT_EXPIRY_WINDOW_MS = 30_000;

    /** Max pending configs in LeaveTunnel queue to prevent unbounded growth */
    private static final int MAX_PENDING_CONFIGS = 50000;

    /** Location in the tunnel for RED logic */
    public enum Location { OBEP, PARTICIPANT, IBGW }

    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR, RateConstants.ONE_DAY };

    /** Lock used when joining tunnels as participant, endpoint, or gateway */
    private final Object _joinParticipantLock = new Object();

    /** Total bandwidth allocated for participating tunnels */
    private final AtomicInteger _allocatedBW = new AtomicInteger();

    /** High for now, just to prevent long-lived-message attacks */
    private static final long MAX_FUTURE_EXPIRATION = 3 * 60 * 1000 + Router.CLOCK_FUDGE_FACTOR;

    /**
     * Creates a new instance of TunnelDispatcher
     */
    public TunnelDispatcher(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelDispatcher.class);
        _pumper = new TunnelGatewayPumper(ctx);
        _leaveJob = new LeaveTunnel(ctx);

        // Initialize stats
        initializeStats();

        // Schedule periodic cleanup for TunnelIdManager and static maps
        ctx.simpleTimer2().addPeriodicEvent(new PeriodicCleanup(), CLEANUP_INTERVAL, CLEANUP_INTERVAL);
    }

    private static final long CLEANUP_INTERVAL = 60 * 1000;

    private class PeriodicCleanup implements SimpleTimer.TimedEvent {
        public void timeReached() {
            _context.tunnelIdManager().cleanup();
            int cleared = TestJob.cleanup();
            cleared += cleanupStaticMaps();
            cleared += cleanupExpiredTunnels();
            if (cleared > 0 && _log.shouldDebug()) {
                _log.debug("Periodic cleanup cleared " + cleared + " entries from static maps");
            }
        }
    }

    private int cleanupStaticMaps() {
        int cleared = 0;
        long now = _context.clock().now();
        long staleCutoff = now - 10 * 60 * 1000; // 10 minutes

        for (Iterator<Map.Entry<TunnelId, Long>> it = _beingRemoved.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<TunnelId, Long> entry = it.next();
            if (entry.getValue() < staleCutoff) {
                it.remove();
                cleared++;
            }
        }

        for (Iterator<Map.Entry<TunnelId, Long>> it = _recentlyExpired.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<TunnelId, Long> entry = it.next();
            if (entry.getValue() < staleCutoff) {
                it.remove();
                cleared++;
            }
        }

        return cleared;
    }

    /**
     * Initializes all stats tracked by this class
     */
    private void initializeStats() {
        _context.statManager().createRequiredRateStat("tunnel.participatingTunnels", "Tunnels routed for others", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.dispatchOutboundPeer", "Outbound messages targeting a peer", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.dispatchOutboundTunnel", "Outbound messages targeting a tunnel", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.dispatchInbound", "Messages we sent through our Tunnel Gateway", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.dispatchParticipant", "Messages we sent through a tunnel we are participating in", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.dispatchEndpoint", "Messages received as Outbound Endpoint of a tunnel", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.joinOutboundGateway", "Tunnels joined as Outbound Gateway", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.cache.outboundGateways", "Outbound gateway cache size", "Tunnels [Memory]", RATES);
        _context.statManager().createRateStat("tunnel.cache.outboundEndpoints", "Outbound endpoint cache size", "Tunnels [Memory]", RATES);
        _context.statManager().createRateStat("tunnel.cache.participants", "Participant cache size", "Tunnels [Memory]", RATES);
        _context.statManager().createRateStat("tunnel.cache.inboundGateways", "Inbound gateway cache size", "Tunnels [Memory]", RATES);
        _context.statManager().createRateStat("tunnel.cache.participatingConfig", "Participating config cache size", "Tunnels [Memory]", RATES);
        _context.statManager().createRateStat("tunnel.joinOutboundGatewayZeroHop", "Zero hop tunnels joined as Outbound Gateway", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.joinInboundEndpoint", "Tunnels joined as Inbound Endpoint", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.joinInboundEndpointZeroHop", "Zero hop tunnels joined as Inbound Endpoint", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.joinParticipant", "Tunnels joined as participant", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.joinOutboundEndpoint", "Tunnels joined as Outbound Endpoint", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.joinInboundGateway", "Tunnels joined as Inbound Gateway", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.participating InBps", "In (B/s) for Participating tunnels", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.participating OutBps", "Out (B/s) for Participating tunnels", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("tunnel.participatingMessageDropped", "Dropped participating messages (share limit exceeded)", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.participatingMessageCount", "Total 1KB participating messages", "Tunnels [Participating]", RATES);
        _context.statManager().createRequiredRateStat("tunnel.participatingMessageCountAvgPerTunnel", "Estimated participating messages per tunnel lifetime", "Tunnels [Participating]", new long[] { 60 * 1000, 20 * 60 * 1000 });
        _context.statManager().createRateStat("tunnel.ownedMessageCount", "Messages sent through a tunnel we created", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.failedCompletelyMessages", "Messages sent through a prematurely failed tunnel", "Tunnels", RATES);
        _context.statManager().createRateStat("tunnel.failedPartially", "Messages sent through a partially failed tunnel", "Tunnels", RATES);
    }

    /**
     * Create a preprocessor for an inbound tunnel gateway
     */
    private TunnelGateway.QueuePreprocessor createPreprocessor(HopConfig cfg) {
        return new BatchedRouterPreprocessor(_context, cfg);
    }

    /**
     * Create a preprocessor for an outbound tunnel gateway
     */
    private TunnelGateway.QueuePreprocessor createPreprocessor(TunnelCreatorConfig cfg) {
        return new BatchedRouterPreprocessor(_context, cfg);
    }

    /**
     * Returns the timestamp of the last participating tunnel expiration.
     */
    public long getLastParticipatingExpiration() {
        return _lastParticipatingExpiration;
    }

    /**
     * We are the outbound gateway - we created this tunnel
     *
     * @return true if successful, false if tunnel ID is a duplicate
     */
    public boolean joinOutbound(PooledTunnelCreatorConfig cfg) {
        if (_log.shouldInfo())
            _log.info("Outbound Gateway built successfully: " + cfg);

        TunnelGateway gw;
        if (cfg.getLength() > 1) {
            TunnelGateway.QueuePreprocessor preproc = createPreprocessor(cfg);
            TunnelGateway.Sender sender = new OutboundSender(_context, cfg);
            TunnelGateway.Receiver receiver = new OutboundReceiver(_context, cfg);
            gw = new PumpedTunnelGateway(_context, preproc, sender, receiver, _pumper);
        } else {
            gw = new TunnelGatewayZeroHop(_context, cfg);
        }

        TunnelId outId = cfg.getConfig(0).getSendTunnel();
        if (_outboundGateways.putIfAbsent(outId, gw) != null) {
            // Cleanup leaked objects from duplicate tunnel ID
            if (gw instanceof PumpedTunnelGateway) {
                _pumper.removeGateway((PumpedTunnelGateway) gw);
            }
            gw.destroy();
            return false;
        }

        if (cfg.getLength() > 1) {
            _context.statManager().addRateData("tunnel.joinOutboundGateway", 1);
            _context.messageHistory().tunnelJoined("outbound", cfg);
        } else {
            _context.statManager().addRateData("tunnel.joinOutboundGatewayZeroHop", 1);
            _context.messageHistory().tunnelJoined("outboundZeroHop", cfg);
        }

        return true;
    }

    /**
     * We are the inbound endpoint - we created this tunnel
     *
     * @return true if successful, false if tunnel ID is a duplicate
     */
    public synchronized boolean joinInbound(TunnelCreatorConfig cfg) {
        if (_log.shouldInfo())
            _log.info("Inbound Endpoint built successfully " + cfg);

        if (cfg.getLength() > 1) {
            TunnelParticipant participant = new TunnelParticipant(_context, new InboundEndpointProcessor(_context, cfg, _validator));
            TunnelId recvId = cfg.getConfig(cfg.getLength() - 1).getReceiveTunnel();
            if (_participants.putIfAbsent(recvId, participant) != null) {
                // Cleanup leaked objects from duplicate tunnel ID
                participant.destroy();
                return false;
            }
            _context.statManager().addRateData("tunnel.joinInboundEndpoint", 1);
            _context.messageHistory().tunnelJoined("inboundEndpoint", cfg);
        } else {
            TunnelGatewayZeroHop gw = new TunnelGatewayZeroHop(_context, cfg);
            TunnelId recvId = cfg.getConfig(0).getReceiveTunnel();
            if (_inboundGateways.putIfAbsent(recvId, gw) != null) {
                // Cleanup leaked objects from duplicate tunnel ID
                gw.destroy();
                return false;
            }
            _context.statManager().addRateData("tunnel.joinInboundEndpointZeroHop", 1);
            _context.messageHistory().tunnelJoined("inboundEndpointZeroHop", cfg);
        }
        return true;
    }

    /**
     * We are a participant in this tunnel, but not as the endpoint or gateway
     *
     * @return true if successful, false if tunnel ID is a duplicate
     */
    public boolean joinParticipant(HopConfig cfg) {
        if (_log.shouldInfo()) {
            _log.info("Joining tunnel as participant " + cfg);
        }
        TunnelId recvId = cfg.getReceiveTunnel();
        TunnelParticipant participant = new TunnelParticipant(_context, cfg, new HopProcessor(_context, cfg, _validator));

        synchronized (_joinParticipantLock) {
            if (_participatingConfig.putIfAbsent(recvId, cfg) != null) {
                participant.destroy();
                return false;
            }
            if (_participants.putIfAbsent(recvId, participant) != null) {
                _participatingConfig.remove(recvId);
                participant.destroy();
                return false;
            }
        }
        return true;
    }

    public boolean joinOutboundEndpoint(HopConfig cfg) {
        if (_log.shouldInfo())
            _log.info("Joining tunnel as Outbound Endpoint " + cfg);
        TunnelId recvId = cfg.getReceiveTunnel();
        OutboundTunnelEndpoint endpoint = new OutboundTunnelEndpoint(_context, cfg, new HopProcessor(_context, cfg, _validator));

        synchronized (_joinParticipantLock) {
            if (_participatingConfig.putIfAbsent(recvId, cfg) != null)
                return false;
            if (_outboundEndpoints.putIfAbsent(recvId, endpoint) != null) {
                _participatingConfig.remove(recvId);
                return false;
            }
        }

        _context.messageHistory().tunnelJoined("outboundEndpoint", cfg);
        _context.statManager().addRateData("tunnel.joinOutboundEndpoint", 1);

        if (cfg.getExpiration() > _lastParticipatingExpiration)
            _lastParticipatingExpiration = cfg.getExpiration();

        _leaveJob.add(cfg);
        _allocatedBW.addAndGet(cfg.getAllocatedBW());

        return true;
    }

    /**
     * We are the inbound gateway in this tunnel, and did not create it
     *
     * @return true if successful, false if tunnel ID is a duplicate
     */
    public boolean joinInboundGateway(HopConfig cfg) {
        if (_log.shouldInfo())
            _log.info("Joining tunnel as Inbound Gateway " + cfg);
        TunnelGateway.QueuePreprocessor preproc = createPreprocessor(cfg);
        TunnelGateway.Sender sender = new InboundSender(_context, cfg);
        TunnelGateway.Receiver receiver = new InboundGatewayReceiver(_context, cfg);
        TunnelGateway gw = new ThrottledPumpedTunnelGateway(_context, preproc, sender, receiver, _pumper, cfg);
        TunnelId recvId = cfg.getReceiveTunnel();

        synchronized (_joinParticipantLock) {
            if (_participatingConfig.putIfAbsent(recvId, cfg) != null)
                return false;
            if (_inboundGateways.putIfAbsent(recvId, gw) != null) {
                _participatingConfig.remove(recvId);
                return false;
            }
        }

        _context.messageHistory().tunnelJoined("inboundGateway", cfg);
        _context.statManager().addRateData("tunnel.joinInboundGateway", 1);

        if (cfg.getExpiration() > _lastParticipatingExpiration)
            _lastParticipatingExpiration = cfg.getExpiration();

        _leaveJob.add(cfg);
        _allocatedBW.addAndGet(cfg.getAllocatedBW());

        return true;
    }

    /**
     * Get the total bandwidth allocated for participating tunnels
     */
    public int getAllocatedBW() {
        return _allocatedBW.get();
    }

    /**
     * Free bandwidth allocation when a tunnel is removed
     * @param bw the bandwidth to free (will be subtracted from allocated total)
     */
    public void freeBandwidth(int bw) {
        if (bw > 0) {
            _allocatedBW.addAndGet(-bw);
        }
    }

    /**
     * Get the number of participating tunnels
     */
    public int getParticipatingCount() {
        return _participatingConfig.size();
    }

    /**
     * Get the number of outbound gateways
     */
    public int getOutboundGatewayCount() {
        return _outboundGateways.size();
    }

    /**
     * Get the number of inbound gateways
     */
    public int getInboundGatewayCount() {
        return _inboundGateways.size();
    }

    /**
     * Get the number of outbound endpoints
     */
    public int getOutboundEndpointCount() {
        return _outboundEndpoints.size();
    }

    /**
     * Clean up expired tunnels from dispatcher maps.
     * This is a defensive measure to remove tunnels that may have been orphaned
     * due to race conditions or queue overflow.
     * @return number of tunnels cleaned up
     */
    public int cleanupExpiredTunnels() {
        int cleaned = 0;
        long now = _context.clock().now();
        long cutoff = now - 30000; // 30 seconds - aggressive cleanup for orphaned tunnels
        List<TunnelId> tunnelIdsToClean = new ArrayList<>();

        // Clean up _participatingConfig - remove tunnels that have expired
        Iterator<Map.Entry<TunnelId, HopConfig>> pcit = _participatingConfig.entrySet().iterator();
        while (pcit.hasNext()) {
            Map.Entry<TunnelId, HopConfig> entry = pcit.next();
            HopConfig cfg = entry.getValue();
            if (cfg != null && cfg.getExpiration() < cutoff) {
                pcit.remove();
                _participants.remove(entry.getKey());
                _allocatedBW.addAndGet(0 - cfg.getAllocatedBW());
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
                if (_log.shouldDebug()) {
                    _log.debug("Cleaned up expired participating tunnel: " + entry.getKey());
                }
            }
        }

        // Clean up _outboundEndpoints - remove expired endpoints
        Iterator<Map.Entry<TunnelId, OutboundTunnelEndpoint>> oeit = _outboundEndpoints.entrySet().iterator();
        while (oeit.hasNext()) {
            Map.Entry<TunnelId, OutboundTunnelEndpoint> entry = oeit.next();
            HopConfig hopCfg = _participatingConfig.get(entry.getKey());
            if (hopCfg == null || hopCfg.getExpiration() < cutoff) {
                oeit.remove();
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
            }
        }

        // Clean up _inboundGateways - remove expired or destroyed gateways
        Iterator<Map.Entry<TunnelId, TunnelGateway>> ibit = _inboundGateways.entrySet().iterator();
        while (ibit.hasNext()) {
            Map.Entry<TunnelId, TunnelGateway> entry = ibit.next();
            TunnelGateway gw = entry.getValue();
            boolean shouldRemove = false;
            if (gw instanceof TunnelGatewayZeroHop) {
                TunnelGatewayZeroHop zeroHop = (TunnelGatewayZeroHop) gw;
                if (zeroHop.isDestroyed()) {
                    shouldRemove = true;
                } else {
                    TunnelCreatorConfig tcc = zeroHop.getConfig();
                    if (tcc == null) {
                        shouldRemove = true;
                    } else if (tcc.getExpiration() < cutoff) {
                        shouldRemove = true;
                    }
                }
            } else if (gw instanceof PumpedTunnelGateway) {
                PumpedTunnelGateway pumped = (PumpedTunnelGateway) gw;
                if (pumped.isDestroyed()) {
                    shouldRemove = true;
                } else {
                    HopConfig hopCfg = _participatingConfig.get(entry.getKey());
                    if (hopCfg != null && hopCfg.getExpiration() < cutoff) {
                        shouldRemove = true;
                    } else if (hopCfg == null) {
                        shouldRemove = true;
                    }
                }
            }
            if (shouldRemove) {
                if (gw instanceof PumpedTunnelGateway) {
                    _pumper.removeGateway((PumpedTunnelGateway) gw);
                }
                gw.destroy();
                ibit.remove();
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
            }
        }

        // Clean up _outboundGateways - remove expired or destroyed gateways
        Iterator<Map.Entry<TunnelId, TunnelGateway>> obit = _outboundGateways.entrySet().iterator();
        while (obit.hasNext()) {
            Map.Entry<TunnelId, TunnelGateway> entry = obit.next();
            TunnelGateway gw = entry.getValue();
            boolean shouldRemove = false;
            if (gw instanceof TunnelGatewayZeroHop) {
                TunnelGatewayZeroHop zeroHop = (TunnelGatewayZeroHop) gw;
                if (zeroHop.isDestroyed()) {
                    shouldRemove = true;
                } else {
                    TunnelCreatorConfig tcc = zeroHop.getConfig();
                    if (tcc == null) {
                        shouldRemove = true;
                    } else if (tcc.getExpiration() < cutoff) {
                        shouldRemove = true;
                    }
                }
            } else if (gw instanceof PumpedTunnelGateway) {
                PumpedTunnelGateway pumped = (PumpedTunnelGateway) gw;
                if (pumped.isDestroyed()) {
                    shouldRemove = true;
                } else {
                    HopConfig hopCfg = _participatingConfig.get(entry.getKey());
                    if (hopCfg != null && hopCfg.getExpiration() < cutoff) {
                        shouldRemove = true;
                    } else if (hopCfg == null) {
                        shouldRemove = true;
                    }
                }
            }
            if (shouldRemove) {
                if (gw instanceof PumpedTunnelGateway) {
                    _pumper.removeGateway((PumpedTunnelGateway) gw);
                }
                gw.destroy();
                obit.remove();
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
            }
        }

        // Clean up _participants - remove participants with expired tunnels
        Iterator<Map.Entry<TunnelId, TunnelParticipant>> partit = _participants.entrySet().iterator();
        while (partit.hasNext()) {
            Map.Entry<TunnelId, TunnelParticipant> entry = partit.next();
            HopConfig hopCfg = _participatingConfig.get(entry.getKey());
            if (hopCfg == null || hopCfg.getExpiration() < cutoff) {
                partit.remove();
                if (entry.getValue() != null) {
                    entry.getValue().destroy();
                }
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
            }
        }

        // Clean up _recentlyExpired - remove old entries
        int prevSize = _recentlyExpired.size();
        _recentlyExpired.entrySet().removeIf(e -> e.getValue() < cutoff);
        int cleanedRecently = prevSize - _recentlyExpired.size();
        cleaned += cleanedRecently;

        // Clean up TunnelIds from the canonical cache to prevent memory leak
        for (TunnelId tid : tunnelIdsToClean) {
            _context.tunnelIdManager().remove(tid);
        }

        if ((cleaned - cleanedRecently) > 0 && _log.shouldInfo()) {
            _log.info("Cleaned up " + cleaned + " expired tunnels from dispatcher maps (recentlyExpired: " + cleanedRecently + ")");
        }
        return cleaned;
    }

    /**
     * Aggressive cleanup - removes tunnels regardless of expiration.
     * This is an emergency measure to prevent memory leaks.
     * @return number of tunnels removed
     */
    public int aggressiveCleanup() {
        int cleaned = 0;
        long now = _context.clock().now();
        List<TunnelId> tunnelIdsToClean = new ArrayList<>();

        // Clean up tunnels that have been around too long (potential orphans)
        // Use creation time if available, otherwise use expiration
        Iterator<Map.Entry<TunnelId, HopConfig>> pcit = _participatingConfig.entrySet().iterator();
        while (pcit.hasNext()) {
            Map.Entry<TunnelId, HopConfig> entry = pcit.next();
            HopConfig cfg = entry.getValue();
            if (cfg != null) {
                // If tunnel is older than 10 minutes, it's likely orphaned
                long age = now - cfg.getCreation();
                if (age > 10 * 60 * 1000 || cfg.getExpiration() < now) {
                    pcit.remove();
                    _participants.remove(entry.getKey());
                    _allocatedBW.addAndGet(0 - cfg.getAllocatedBW());
                    tunnelIdsToClean.add(entry.getKey());
                    cleaned++;
                    if (_log.shouldDebug()) {
                        _log.debug("Aggressive cleanup removed tunnel: " + entry.getKey() +
                                   " age=" + (age/60000) + "min expired=" + (cfg.getExpiration() < now));
                    }
                }
            }
        }

        // Clean up orphaned outbound gateways (zero-hop tunnels we own)
        Iterator<Map.Entry<TunnelId, TunnelGateway>> obit = _outboundGateways.entrySet().iterator();
        while (obit.hasNext()) {
            Map.Entry<TunnelId, TunnelGateway> entry = obit.next();
            TunnelGateway gw = entry.getValue();
            boolean shouldRemove = false;
            if (gw instanceof TunnelGatewayZeroHop) {
                TunnelGatewayZeroHop zeroHop = (TunnelGatewayZeroHop) gw;
                // Remove if destroyed OR too old (older than max tunnel lifetime + buffer)
                if (zeroHop.isDestroyed()) {
                    shouldRemove = true;
                } else {
                    long age = now - zeroHop.getCreated();
                    if (age > 12 * 60 * 1000) {  // 12 min > 10 min max lifetime
                        shouldRemove = true;
                    }
                }
            } else if (gw instanceof PumpedTunnelGateway) {
                PumpedTunnelGateway pumped = (PumpedTunnelGateway) gw;
                if (pumped.isDestroyed()) {
                    shouldRemove = true;
                }
            }
            if (shouldRemove) {
                obit.remove();
                if (gw instanceof PumpedTunnelGateway) {
                    _pumper.removeGateway((PumpedTunnelGateway) gw);
                }
                gw.destroy();
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
            }
        }

        // Clean up orphaned inbound gateways (zero-hop tunnels we own)
        Iterator<Map.Entry<TunnelId, TunnelGateway>> ibit = _inboundGateways.entrySet().iterator();
        while (ibit.hasNext()) {
            Map.Entry<TunnelId, TunnelGateway> entry = ibit.next();
            TunnelGateway gw = entry.getValue();
            boolean shouldRemove = false;
            if (gw instanceof TunnelGatewayZeroHop) {
                TunnelGatewayZeroHop zeroHop = (TunnelGatewayZeroHop) gw;
                if (zeroHop.isDestroyed()) {
                    shouldRemove = true;
                } else {
                    long age = now - zeroHop.getCreated();
                    if (age > 12 * 60 * 1000) {
                        shouldRemove = true;
                    }
                }
            } else if (gw instanceof PumpedTunnelGateway) {
                PumpedTunnelGateway pumped = (PumpedTunnelGateway) gw;
                if (pumped.isDestroyed()) {
                    shouldRemove = true;
                }
            }
            if (shouldRemove) {
                ibit.remove();
                if (gw instanceof PumpedTunnelGateway) {
                    _pumper.removeGateway((PumpedTunnelGateway) gw);
                }
                gw.destroy();
                tunnelIdsToClean.add(entry.getKey());
                cleaned++;
            }
        }

        // Clean up _beingRemoved map to prevent unbounded growth
        // Remove entries older than 5 minutes (they should be processed by now)
        long beingRemovedCutoff = now - 5 * 60 * 1000;
        Iterator<Map.Entry<TunnelId, Long>> brit = _beingRemoved.entrySet().iterator();
        while (brit.hasNext()) {
            Map.Entry<TunnelId, Long> entry = brit.next();
            if (entry.getValue() < beingRemovedCutoff) {
                brit.remove();
                tunnelIdsToClean.add(entry.getKey());
            }
        }

        // Clean up TunnelIds from the canonical cache to prevent memory leak
        for (TunnelId tid : tunnelIdsToClean) {
            _context.tunnelIdManager().remove(tid);
        }

        if (cleaned > 0 && _log.shouldInfo()) {
            _log.info("Aggressive cleanup removed " + cleaned + " orphaned tunnels from all maps");
        }
        return cleaned;
    }

    /**
     * Diagnostic method to detect potential memory leaks in tunnel dispatcher maps.
     * Call periodically from a maintenance job to catch leaks early.
     *
     * @return diagnostic string describing the state of all maps
     */
    public String diagnoseMemoryLeaks() {
        StringBuilder sb = new StringBuilder();
        sb.append("TunnelDispatcher Memory Leak Diagnostic:\n");
        sb.append("  _outboundGateways: ").append(_outboundGateways.size()).append("\n");
        sb.append("  _outboundEndpoints: ").append(_outboundEndpoints.size()).append("\n");
        sb.append("  _participants: ").append(_participants.size()).append("\n");
        sb.append("  _inboundGateways: ").append(_inboundGateways.size()).append("\n");
        sb.append("  _participatingConfig: ").append(_participatingConfig.size()).append("\n");
        sb.append("  _recentlyExpired: ").append(_recentlyExpired.size()).append("\n");

        int totalMaps = _outboundGateways.size() + _outboundEndpoints.size() +
                        _participants.size() + _inboundGateways.size() +
                        _participatingConfig.size();
        int uniqueByConfig = _participatingConfig.size();
        int discrepancy = totalMaps - uniqueByConfig;
        if (discrepancy > 10) {
            sb.append("  WARNING: Large discrepancy between total map entries and config count: ")
              .append(discrepancy).append("\n");
        }

        return sb.toString();
    }

    /**
     * Get a new random send tunnel ID that isn't a duplicate
     */
    public TunnelId getNewOBGWID() {
        TunnelId rv;
        do {
            rv = _context.tunnelIdManager().generateNewId();
        } while (_outboundGateways.containsKey(rv));
        return rv;
    }

    /**
     * Get a new random receive tunnel ID that isn't a duplicate
     */
    public TunnelId getNewIBEPID() {
        TunnelId rv;
        do {
            rv = _context.tunnelIdManager().generateNewId();
        } while (_participants.containsKey(rv));
        return rv;
    }

    /**
     * Get a new random receive tunnel ID that isn't a duplicate (zero hop)
     */
    public TunnelId getNewIBZeroHopID() {
        TunnelId rv;
        do {
            rv = _context.tunnelIdManager().generateNewId();
        } while (_inboundGateways.containsKey(rv));
        return rv;
    }

    /**
     * Remove a tunnel we created
     * @param cfg the tunnel configuration
     */
    public void remove(TunnelCreatorConfig cfg) {
        remove(cfg, "unspecified");
    }

    /**
     * Remove a tunnel we created
     * @param cfg the tunnel configuration
     * @param reason why the tunnel is being removed
     */
    public void remove(TunnelCreatorConfig cfg, String reason) {
        // Use config identity for tracking - works even if tunnel IDs aren't assigned yet
        int cfgKey = System.identityHashCode(cfg);
        
        // Check if already being removed - prevent duplicate calls using config identity
        if (_configsBeingRemoved.contains(cfgKey)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping duplicate remove for config: " + cfg + " reason: " + reason);
            }
            return;
        }
        
        TunnelId recvId = null;
        TunnelId sendId = null;
        if (cfg.isInbound()) {
            recvId = cfg.getConfig(cfg.getLength() - 1).getReceiveTunnel();
        } else {
            sendId = cfg.getConfig(0).getSendTunnel();
        }

        // Try to mark in TunnelIdManager if we have tunnel IDs
        TunnelId checkId = recvId != null ? recvId : sendId;
        if (checkId != null) {
            // Use TunnelIdManager for cross-instance tracking
            if (!_context.tunnelIdManager().tryMarkForRemoval(checkId)) {
                // Already being removed by another path
                if (_log.shouldDebug()) {
                    _log.debug("Skipping duplicate remove (TunnelIdManager) for tunnel: " + checkId);
                }
                return;
            }
        }
        
        // Mark as being removed using config identity
        _configsBeingRemoved.add(cfgKey);

        //if (_log.shouldInfo()) {
        //    _log.info("Removing tunnel: reason=" + reason +
        //              ", isInbound=" + cfg.isInbound() +
        //              ", length=" + cfg.getLength() +
        //              ", recvId=" + (cfg.getLength() > 0 ? cfg.getConfig(cfg.getLength() - 1).getReceiveTunnel() : "n/a") +
        //              ", sendId=" + (cfg.getLength() > 0 ? cfg.getConfig(0).getSendTunnel() : "n/a"));
        //}

        if (cfg.isInbound()) {
            recvId = cfg.getConfig(cfg.getLength() - 1).getReceiveTunnel();
            if (recvId == null) {
                if (_log.shouldWarn()) {
                    _log.warn("Cannot remove " + cfg + " -> receiveTunnel is null");
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("Removing " + cfg + " -> " + reason);
                TunnelParticipant participant = _participants.remove(recvId);
                if (participant != null) {
                    participant.destroy();
                    // Update peer profiles
                    for (int i = 0; i < cfg.getLength() - 1; i++) {
                        Hash peer = cfg.getPeer(i);
                        PeerProfile profile = _context.profileOrganizer().getProfile(peer);
                        if (profile != null) {
                            int ok = participant.getCompleteCount();
                            int fail = participant.getFailedCount();
                            profile.getTunnelHistory().incrementProcessed(ok, fail);
                        }
                    }
                }
                // Always remove from inboundGateways - this was a bug causing memory leak
                // where gateways were only removed when participant was null
                TunnelGateway removed = _inboundGateways.remove(recvId);
                if (removed != null) {
                    removed.destroy();
                    if (_log.shouldDebug()) {
                        _log.debug("Removed inbound gateway: " + recvId);
                    }
                }
            }
        } else {
            sendId = cfg.getConfig(0).getSendTunnel();
            if (sendId == null) {
                if (_log.shouldWarn()) {
                    _log.warn("Cannot remove " + cfg + " -> sendTunnel is null");
                }
            } else {
                TunnelGateway gw = _outboundGateways.remove(sendId);
                if (gw != null) {
                    // update stats based on gw.getMessagesSent()
                }
                // Remove from pumper queues to prevent memory leak
                if (gw instanceof PumpedTunnelGateway) {
                    _pumper.removeGateway((PumpedTunnelGateway) gw);
                }
                // Destroy all gateway types including zero-hop
                if (gw != null) {
                    gw.destroy();
                }
                if (_log.shouldDebug()) {
                    _log.debug("Removed outbound gateway: " + sendId);
                }
            }
        }

        // Always add to recently expired to handle delayed messages
        if (recvId != null) {
            addRecentlyExpired(recvId);
            _context.tunnelIdManager().remove(recvId);
        }
        if (sendId != null) {
            addRecentlyExpired(sendId);
            _context.tunnelIdManager().remove(sendId);
        }

        long msgs = cfg.getProcessedMessagesCount();
        int failures = cfg.getTunnelFailures();
        boolean failed = cfg.getTunnelFailed();
        _context.statManager().addRateData("tunnel.ownedMessageCount", msgs, failures);
        if (failed) {
            _context.statManager().addRateData("tunnel.failedCompletelyMessages", msgs, failures);
        } else if (failures > 0) {
            _context.statManager().addRateData("tunnel.failedPartiallyMessages", msgs, failures);
        }

        // Keep the beingRemoved flag for a while to prevent duplicate calls
        // The flag will be cleaned up by aggressiveCleanup or when map grows too large
        // This prevents the same tunnel from being processed multiple times
    }

    /**
     * Remove a tunnel we're participating in
     */
    public void remove(HopConfig cfg) {
        TunnelId recvId = cfg.getReceiveTunnel();
        TunnelId sendId = cfg.getSendTunnel();

        // Check if already being removed - prevent duplicate calls
        TunnelId checkId = recvId != null ? recvId : sendId;
        if (checkId != null) {
            if (_beingRemoved.putIfAbsent(checkId, _context.clock().now()) != null) {
                // Already being removed - skip duplicate call
                if (_log.shouldDebug()) {
                    _log.debug("Skipping duplicate remove(HopConfig) call for tunnel: " + checkId);
                }
                return;
            }
        }

        // Remove from ALL maps unconditionally (idempotent) to prevent leaks
        // when tunnels exist in multiple maps
        boolean removedConfig = _participatingConfig.remove(recvId) != null;
        TunnelParticipant participant = _participants.remove(recvId);
        boolean removedParticipant = participant != null;
        if (participant != null) {
            participant.destroy();
        }

        // Remove from inboundGateways and cleanup pumper for ThrottledPumpedTunnelGateway
        TunnelGateway ibGw = _inboundGateways.remove(recvId);
        boolean removedInboundGateway = ibGw != null;
        if (ibGw instanceof PumpedTunnelGateway) {
            _pumper.removeGateway((PumpedTunnelGateway) ibGw);
            ((PumpedTunnelGateway) ibGw).destroy();
        }

        OutboundTunnelEndpoint endpoint = _outboundEndpoints.remove(recvId);
        boolean removedEndpoint = endpoint != null;
        if (endpoint != null) {
            endpoint.destroy();
        }

        // Also cleanup _outboundGateways by send tunnel ID to prevent leaks
        // when we're the outbound gateway for a participating tunnel
        boolean removedOutboundGateway = false;
        if (sendId != null) {
            TunnelGateway obGw = _outboundGateways.remove(sendId);  // Direct remove, don't check first
            removedOutboundGateway = obGw != null;
            if (obGw != null) {
                if (obGw instanceof PumpedTunnelGateway) {
                    _pumper.removeGateway((PumpedTunnelGateway) obGw);
                    ((PumpedTunnelGateway) obGw).destroy();
                } else {
                    obGw.destroy();
                }
            }
        }

        // Always add to recentlyExpired if IDs exist, to handle delayed messages
        // This is idempotent - safe to call even if already removed
        if (recvId != null) {
            addRecentlyExpired(recvId);
            _context.tunnelIdManager().remove(recvId);
        }
        if (sendId != null) {
            addRecentlyExpired(sendId);
            _context.tunnelIdManager().remove(sendId);
        }

        if (removedConfig || removedParticipant || removedInboundGateway || removedEndpoint || removedOutboundGateway) {
            if (_log.shouldDebug()) {
                _log.debug("Removed from config=" + removedConfig + ", participant=" + removedParticipant +
                           ", inboundGW=" + removedInboundGateway + ", endpoint=" + removedEndpoint +
                           ", outboundGW=" + removedOutboundGateway +
                           ": recvId=" + recvId + (sendId != null ? ", sendId=" + sendId : ""));
            }
        }
    }

    /**
     * Remove a HopConfig from the LeaveTunnel queue to prevent memory leak.
     * This is called when tunnels are dropped early (e.g., by IdleTunnelMonitor).
     * @param cfg the hop config to remove from the expiration queue
     */
    public void removeFromExpirationQueue(HopConfig cfg) {
        if (cfg != null) {
            _leaveJob.remove(cfg);
        }
    }

    /**
     * Remove tunnel from all dispatcher maps using tunnel ID.
     * Used by ExpireLocalTunnelsJob when config is no longer available.
     * @param tunnelId the tunnel ID to remove
     */
    public void removeFromMaps(TunnelId tunnelId) {
        if (tunnelId == null) {
            return;
        }
        // Remove from all maps - we don't know which type, so try all
        TunnelGateway obGw = _outboundGateways.remove(tunnelId);
        if (obGw instanceof PumpedTunnelGateway) {
            _pumper.removeGateway((PumpedTunnelGateway) obGw);
        }
        if (obGw != null) {
            obGw.destroy();
        }

        TunnelGateway ibGw = _inboundGateways.remove(tunnelId);
        if (ibGw instanceof PumpedTunnelGateway) {
            _pumper.removeGateway((PumpedTunnelGateway) ibGw);
        }
        if (ibGw != null) {
            ibGw.destroy();
        }

        TunnelParticipant participant = _participants.remove(tunnelId);
        if (participant != null) {
            participant.destroy();
        }
        OutboundTunnelEndpoint endpoint = _outboundEndpoints.remove(tunnelId);
        if (endpoint != null) {
            endpoint.destroy();
        }
        _participatingConfig.remove(tunnelId);

        addRecentlyExpired(tunnelId);
        _context.tunnelIdManager().remove(tunnelId);

        if (_log.shouldDebug()) {
            _log.debug("Removed tunnel from dispatcher maps using ID: " + tunnelId);
        }
    }

    private void addRecentlyExpired(TunnelId id) {
        _recentlyExpired.put(id, _context.clock().now());
    }

    /**
     * Dispatch a TunnelDataMessage to the appropriate participant or endpoint
     */
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        TunnelId id = msg.getTunnelIdObj();
        Long added = _recentlyExpired.get(id);
        if (added != null && _context.clock().now() - added < RECENT_EXPIRY_WINDOW_MS) {
            _context.messageHistory().droppedTunnelDataMessageUnknown(msg.getUniqueId(), msg.getTunnelId());
            return;
        }
        TunnelParticipant participant = _participants.get(id);
        if (participant != null) {
            if (_log.shouldDebug())
                _log.debug("Dispatching [MsgID " + msg.getUniqueId() + "] to " + participant + " from [" + recvFrom.toBase64().substring(0, 6) + "]");
            _context.messageHistory().tunnelDispatched(msg.getUniqueId(), msg.getTunnelId(), "participant");
            participant.dispatch(msg, recvFrom);
            _context.statManager().addRateData("tunnel.dispatchParticipant", 1);
        } else {
            OutboundTunnelEndpoint endpoint = _outboundEndpoints.get(id);
            if (endpoint != null) {
                if (_log.shouldDebug())
                    _log.debug("Dispatch where we are the Outbound Endpoint:\n* " + endpoint + ": " + msg + " from [" + recvFrom.toBase64().substring(0, 6) + "]");
                _context.messageHistory().tunnelDispatched(msg.getUniqueId(), msg.getTunnelId(), "outbound endpoint");
                endpoint.dispatch(msg, recvFrom);
                _context.statManager().addRateData("tunnel.dispatchEndpoint", 1);
            } else {
                _context.messageHistory().droppedTunnelDataMessageUnknown(msg.getUniqueId(), msg.getTunnelId());
                if (_log.shouldInfo())
                    _log.info("No matching participant/endpoint for [TunnelID " + msg.getTunnelId() + "] -> Expires: " +
                               DataHelper.formatDuration(msg.getMessageExpiration() - _context.clock().now()) +
                               "\n* Current participants: " + _participants.size() + " / Outbound endpoints: " + _outboundEndpoints.size());
            }
        }
    }

    /**
     * Dispatch a TunnelGatewayMessage to the appropriate gateway
     */
    public void dispatch(TunnelGatewayMessage msg) {
        TunnelId id = msg.getTunnelId();
        Long added = _recentlyExpired.get(id);
        if (added != null && _context.clock().now() - added < RECENT_EXPIRY_WINDOW_MS) {
            _context.messageHistory().droppedTunnelDataMessageUnknown(msg.getUniqueId(), id.getTunnelId());
            return;
        }

        long before = _context.clock().now();
        TunnelGateway gw = _inboundGateways.get(id);
        I2NPMessage submsg = msg.getMessage();
        if (submsg == null)
            throw new IllegalArgumentException("TunnelGatewayMessage is null");

        if (gw != null) {
            if (_log.shouldDebug())
                _log.debug("Dispatch where we are the Inbound gateway [" + gw + "]" + msg);

            long minTime = before - Router.CLOCK_FUDGE_FACTOR;
            long maxTime = before + MAX_FUTURE_EXPIRATION;
            long exp = msg.getMessageExpiration();
            long subexp = submsg.getMessageExpiration();

            if (exp < minTime || subexp < minTime || exp > maxTime || subexp > maxTime) {
                if (_log.shouldInfo())
                    _log.info("Not dispatching GatewayMessage for [TunnelID " + id.getTunnelId() +
                               "]\n* Wrapper's expiration -> " + DataHelper.formatDuration(exp - before) +
                               " and/or content expiration -> " + DataHelper.formatDuration(subexp - before) +
                               "\n* Type: " + submsg.getType() + " [MsgID " + id + "/" + submsg.getUniqueId() + "]");
                return;
            }

            _context.messageHistory().tunnelDispatched(msg.getUniqueId(), submsg.getUniqueId(), id.getTunnelId(), "Inbound gateway");
            gw.add(msg);
            _context.statManager().addRateData("tunnel.dispatchInbound", 1);
        } else {
            _context.messageHistory().droppedTunnelGatewayMessageUnknown(msg.getUniqueId(), id.getTunnelId());
            if (_log.shouldInfo())
                _log.info("No matching tunnel for [TunnelID " + id.getTunnelId() +
                          "]\n* Gateway message expires: " +
                          DataHelper.formatDuration(msg.getMessageExpiration() - before) + "/" +
                          DataHelper.formatDuration(submsg.getMessageExpiration() - before) +
                          " [MsgID " + id + "/" + msg.getMessage().getUniqueId() +
                          "] Type: " + submsg.getType() +
                          "; Current Inbound gateways: " + _inboundGateways.size());
        }
    }

    /**
     * Dispatch an outbound message through a tunnel
     */
    public void dispatchOutbound(I2NPMessage msg, TunnelId outboundTunnel, Hash targetPeer) {
        dispatchOutbound(msg, outboundTunnel, null, targetPeer);
    }

    public void dispatchOutbound(I2NPMessage msg, TunnelId outboundTunnel, TunnelId targetTunnel, Hash targetPeer) {
        if (outboundTunnel == null) throw new IllegalArgumentException("null outbound tunnel?");
        long now = _context.clock().now();
        long age = now - msg.getMessageExpiration();
        TunnelGateway gw = _outboundGateways.get(outboundTunnel);

        // Tunnel not found - check if it was recently removed (handle delayed messages)
        if (gw == null) {
            Long removedAt = _recentlyExpired.get(outboundTunnel);
            if (removedAt != null && (now - removedAt) < RECENT_EXPIRY_WINDOW_MS) {
                // Tunnel was recently removed, this is expected for delayed messages
                // Don't warn, just drop silently
                return;
            }
            // Tunnel was replaced (not just expired) - tunnel pool returned stale reference
            // This happens when tunnel was rebuilt between selection and dispatch
            // Silently drop - no point warning since we can't control the race
            if (_log.shouldDebug()) {
                _log.debug("Tunnel replaced before dispatch: could not find [TunnelId " + outboundTunnel + "]");
            }
            _context.messageHistory().droppedTunnelGatewayMessageUnknown(msg.getUniqueId(), outboundTunnel.getTunnelId());
            return;
        }

        // Increase expiration cutoff during attacks to handle increased latency
        double buildSuccess = _context.profileOrganizer().getTunnelBuildSuccess();
        boolean isUnderAttack = buildSuccess < 0.40;
        long expirationCutoff = isUnderAttack ? 90 * 1000L : Router.CLOCK_FUDGE_FACTOR;

        if (gw != null) {
            if (_log.shouldDebug()) {
                _log.debug("Dispatch Outbound through " + outboundTunnel.getTunnelId() + " -> " + msg);
            }

            if (msg.getMessageExpiration() < now - expirationCutoff) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping tunnel message that expired " +
                                (now - msg.getMessageExpiration()) + "ms ago (Cutoff: " + (expirationCutoff/1000) + "s) -> " + msg);
                }
                return;
            } else if (msg.getMessageExpiration() < now) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping stale tunnel message  -> Expired " + age + "ms ago (Cutoff: " + (expirationCutoff/1000) + "s)\n* " + msg);
                }
            } else if (msg.getMessageExpiration() > now + MAX_FUTURE_EXPIRATION) {
                if (_log.shouldWarn()) {
                    _log.warn("Dropping tunnel message that expires " + age + "ms in the future [!] (Cutoff: " +
                                MAX_FUTURE_EXPIRATION / 1000 + "s) \n* " + msg);
                }
                return;
            }

            msg.setMessageExpiration(now + 20_000); // reset expiry to 20s from now
            long tid1 = outboundTunnel.getTunnelId();
            long tid2 = (targetTunnel != null ? targetTunnel.getTunnelId() : -1);
            _context.messageHistory().tunnelDispatched(msg.getUniqueId(), tid1, tid2, targetPeer, "Outbound gateway");
            gw.add(msg, targetPeer, targetTunnel);

            if (targetTunnel == null) {
                _context.statManager().addRateData("tunnel.dispatchOutboundPeer", 1);
            } else {
                _context.statManager().addRateData("tunnel.dispatchOutboundTunnel", 1);
            }
        } else {
            // Debug logging to diagnose tunnel ID mismatch
            if (_log.shouldWarn()) {
                StringBuilder sb = new StringBuilder();
                sb.append("No matching Outbound tunnel for [TunnelId ").append(outboundTunnel);
                sb.append("] from ").append(_outboundGateways.size()).append(" Outbound gateways");
                if (!_outboundGateways.isEmpty()) {
                    sb.append(" (available IDs: ");
                    int count = 0;
                    for (TunnelId id : _outboundGateways.keySet()) {
                        if (count++ > 10) { sb.append("..."); break; }
                        sb.append(id).append(" ");
                    }
                    sb.append(")");
                }
                _log.warn(sb.toString(), new Exception("src"));
            }
            _context.messageHistory().droppedTunnelGatewayMessageUnknown(msg.getUniqueId(), outboundTunnel.getTunnelId());
        }
    }

    /**
     * Get a list of participating tunnels (for console display)
     */
    public List<HopConfig> listParticipatingTunnels() {
        return new ArrayList<>(_participatingConfig.values());
    }

    /**
     * Update stats for participating tunnels
     */
    public void updateParticipatingStats(int ms) {
        int partCount = _context.tunnelManager().getParticipatingCount();
        long count = 0;
        long bw = 0;
        long tcount = 0;
        long now = _context.clock().now();
        long tooYoung = now - 60 * 1000;
        long tooOld = tooYoung - 9 * 60 * 1000;

        for (HopConfig cfg : _participatingConfig.values()) {
            long c = cfg.getAndResetRecentMessagesCount();
            bw += c;
            long created = cfg.getCreation();
            if (created > tooYoung || created < tooOld)
                continue;
            tcount++;
            count += c;
        }

        if (tcount > 0)
            count = count * (10 * 60 * 1000 / ms) / tcount;

        _context.statManager().addRateData("tunnel.participatingMessageCountAvgPerTunnel", count, ms);
        _context.statManager().addRateData("tunnel.participatingMessageCount", bw, ms);
        _context.statManager().addRateData("tunnel.participating InBps", bw * 1024 / (ms / 1000), ms);
        _context.statManager().addRateData("tunnel.participatingTunnels", partCount);
    }

    /**
     * Implement RED (Random Early Discard) to enforce bandwidth limits
     */
    boolean shouldDropParticipatingMessage(Location loc, int type, int length, SyntheticREDQueue bwe) {
        if (length <= 0) return false;

        // Enable adaptive throttling to prevent queue overflow during congestion
        // Configurable via router.transitThrottleFactor property (default: 0.1f)
        // 0.0f disables all RED-based dropping
        // 1.0f is aggressive (up to 100% drop at high load)
        float factor = _context.getProperty("router.transitThrottleFactor", 0.1f);

        int percentage = (int) Math.min(Math.round((factor - 1.0f) * 100.0f), 100.0f);

        if (bwe != null && !bwe.offer(length, factor)) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping participating message (per-tunnel limit)" +
                          (percentage > 0 ? " -> Drop probability: " + Math.min(percentage, 100) + "%" : "") +
                          "\n* Location: " + loc + ", Type: " + type + ", Length: " + length +
                          ", BWE: " + bwe);
            }
            return true;
        }

        boolean reject = !_context.bandwidthLimiter().sentParticipatingMessage(length, factor);
        if (reject) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping participating message (global bandwidth limit)" +
                          (percentage > 0 ? " -> Drop probability: " + Math.min(percentage, 100) + "%" : "") +
                          "\n* Location: " + loc + ", Type: " + type + ", Length: " + length);
            }
            _context.statManager().addRateData("tunnel.participatingMessageDropped", 1);
        }
        return reject;
    }

    /**
     * Get the max bandwidth per tunnel
     */
    int getMaxPerTunnelBandwidth(Location loc) {
        final int MAX_TUNNELS_THRESHOLD = 4000;
        int maxTunnels = _context.getProperty(RouterThrottleImpl.PROP_MAX_TUNNELS,
                                              RouterThrottleImpl.DEFAULT_MAX_TUNNELS);
        int max = _context.bandwidthLimiter().getMaxShareBandwidth();

        // Maximum bandwidth allocation - completely remove per-tunnel limits
        return Integer.MAX_VALUE;
    }

    /**
     * Start up the TunnelDispatcher
     */
    public synchronized void startup() {
        _validator = new BloomFilterIVValidator(_context, getShareBandwidth(_context));
    }

    /**
     * Shut down TunnelDispatcher
     */
    public synchronized void shutdown() {
        if (_validator != null) {
            _validator.destroy();
            _validator = null;
        }
        _pumper.stopPumping();
        _context.statManager().addRateData("tunnel.cache.outboundGateways", _outboundGateways.size());
        _context.statManager().addRateData("tunnel.cache.outboundEndpoints", _outboundEndpoints.size());
        _context.statManager().addRateData("tunnel.cache.participants", _participants.size());
        _context.statManager().addRateData("tunnel.cache.inboundGateways", _inboundGateways.size());
        _context.statManager().addRateData("tunnel.cache.participatingConfig", _participatingConfig.size());
        _outboundGateways.clear();
        _outboundEndpoints.clear();
        _participants.clear();
        _inboundGateways.clear();
        _participatingConfig.clear();
        _leaveJob.clear();
    }

    /**
     * Restart the TunnelDispatcher
     */
    public void restart() {
        shutdown();
        startup();
    }

    /**
     * Render status HTML (deprecated)
     */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {}

    /**
     * Get the current bandwidth share in KBps
     */
    public static int getShareBandwidth(RouterContext ctx) {
        int irateKBps = ctx.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = ctx.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = ctx.router().getSharePercentage();
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }

    /**
     * Job to expire tunnels we are participating in
     */
    private class LeaveTunnel extends JobImpl {
        private final LinkedBlockingQueue<HopConfig> _configs = new LinkedBlockingQueue<>(MAX_PENDING_CONFIGS);

        public LeaveTunnel(RouterContext ctx) {
            super(ctx);
            getTiming().setStartAfter(ctx.clock().now() + 10 * 60 * 1000);
            ctx.jobQueue().addJob(this);
        }

        public void add(HopConfig cfg) {
            if (!_configs.offer(cfg)) {
                drainOverflow();
                if (!_configs.offer(cfg)) {
                    // Queue is full - only remove if tunnel is already expired to prevent memory leak
                    // Don't remove valid tunnels just because queue is backed up
                    long now = getContext().clock().now();
                    long exp = cfg.getExpiration() + (3 * Router.CLOCK_FUDGE_FACTOR / 2);
                    if (exp < now) {
                        LeaveTunnel.this.remove(cfg);
                        _allocatedBW.addAndGet(0 - cfg.getAllocatedBW());
                        if (_log.shouldWarn()) {
                            _log.warn("Dropping expired tunnel config - queue full, removed from maps: " + cfg.getReceiveTunnel());
                        }
                    } else {
                        // Tunnel is still valid, just log the queue backup
                        if (_log.shouldWarn()) {
                            _log.warn("Queue full for valid tunnel (not removed): " + cfg.getReceiveTunnel() + " expires in " + ((cfg.getExpiration() - now)/1000) + "s");
                        }
                    }
                }
            }
        }

        private void drainOverflow() {
            int drained = 0;
            int targetDrain = MAX_PENDING_CONFIGS / 4;
            long now = getContext().clock().now();
            while (drained < targetDrain) {
                HopConfig cur = _configs.peek();
                if (cur == null) break;
                long exp = cur.getExpiration() + (3 * Router.CLOCK_FUDGE_FACTOR / 2) + 1000;
                if (exp < now) {
                    _configs.poll();
                    drained++;
                } else {
                    break;
                }
            }
            if (drained > 0 && _log.shouldWarn()) {
                _log.warn("Drained " + drained + " expired configs from overflow queue");
            }
        }

        public void clear() {
            _configs.clear();
        }

        /**
         * Remove a specific HopConfig from the queue to prevent memory leak.
         * This is called when tunnels are dropped early (e.g., by IdleTunnelMonitor).
         * @param cfg the hop config to remove
         */
        public void remove(HopConfig cfg) {
            if (cfg != null) {
                _configs.remove(cfg);
            }
        }

        public String getName() {
            return "Expire Participating Tunnels";
        }

        public void runJob() {
            long nextTime = 0;
            boolean jobFailed = false;
            try {
                nextTime = runJobInternal();
            } catch (Throwable t) {
                jobFailed = true;
                if (_log.shouldError()) {
                    _log.error("LeaveTunnel job failed", t);
                }
            }
            if (nextTime <= 0) {
                nextTime = getContext().clock().now() + 10 * 60 * 1000;
            }
            getTiming().setStartAfter(nextTime);
            getContext().jobQueue().addJob(this);
        }

        private long runJobInternal() {
            long now = getContext().clock().now() + 1000;
            long nextTime = now + 10 * 60 * 1000;

            // Scale expiration more gradually with tunnel count to reduce churn
            // High tunnel counts expire faster to maintain pool health
            int count = getParticipatingCount();
            if (count > 4000) {nextTime = now + 60 * 1000;}          // 4000+: 1 min
            else if (count > 2500) {nextTime = now + 90 * 1000;}     // 2500-4000: 1.5 min
            else if (count > 1500) {nextTime = now + 120 * 1000;}    // 1500-2500: 2 min
            else if (count > 1000) {nextTime = now + 180 * 1000;}    // 1000-1500: 3 min
            else if (count > 750) {nextTime = now + 240 * 1000;}     // 750-1000: 4 min
            else if (count > 500) {nextTime = now + 300 * 1000;}     // 500-750: 5 min
            else {nextTime = now + 600 * 1000;}                      // 0-500: 10 min

            while (true) {
                HopConfig cur = _configs.peek();
                if (cur == null) break;

                long exp = cur.getExpiration() + (3 * Router.CLOCK_FUDGE_FACTOR / 2) + 1000;
                if (exp < now) {
                    _configs.poll();
                    if (_log.shouldInfo())
                        _log.info("Expiring Participating tunnel... " + cur);
                    remove(cur);
                    _allocatedBW.addAndGet(0 - cur.getAllocatedBW());
                } else {
                    if (exp < nextTime) nextTime = exp;
                    break;
                }
            }

            long expiryCutoff = now - RECENT_EXPIRY_WINDOW_MS;
            _recentlyExpired.entrySet().removeIf(e -> e.getValue() < expiryCutoff);

            return nextTime;
        }
    }
}