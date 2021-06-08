package net.i2p.router.tunnel;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.InboundTunnelBuildMessage;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.ShortTunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelDataMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.i2np.VariableTunnelBuildMessage;
import net.i2p.data.i2np.VariableTunnelBuildReplyMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.Service;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Handle the actual processing and forwarding of messages through the
 * various tunnels.
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
    /** us */
    private final ConcurrentHashMap<TunnelId, TunnelGateway> _outboundGateways;
    private final ConcurrentHashMap<TunnelId, OutboundTunnelEndpoint> _outboundEndpoints;
    /** regular participant or IBEP of our own tunnel */
    private final ConcurrentHashMap<TunnelId, TunnelParticipant> _participants;
    /** regular IBGW or our own zero-hop inbound */
    private final ConcurrentHashMap<TunnelId, TunnelGateway> _inboundGateways;
    /** anything we did not create - IBGW, OBEP, or middle hop */
    private final ConcurrentHashMap<TunnelId, HopConfig> _participatingConfig;
    /** what is the date/time on which the last non-locally-created tunnel expires? */
    private long _lastParticipatingExpiration;
    private BloomFilterIVValidator _validator;
    private final LeaveTunnel _leaveJob;
    /** what is the date/time we last deliberately dropped a tunnel? **/
    //private long _lastDropTime;
    private final TunnelGatewayPumper _pumper;
    private final Object _joinParticipantLock = new Object();

    /** for shouldDropParticipatingMessage() */
    enum Location {OBEP, PARTICIPANT, IBGW}

    private static final long[] RATES = { 60*1000, 10*60*1000l, 60*60*1000l };

    /** Creates a new instance of TunnelDispatcher */
    public TunnelDispatcher(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelDispatcher.class);
        _outboundGateways = new ConcurrentHashMap<TunnelId, TunnelGateway>();
        _outboundEndpoints = new ConcurrentHashMap<TunnelId, OutboundTunnelEndpoint>();
        _participants = new ConcurrentHashMap<TunnelId, TunnelParticipant>();
        _inboundGateways = new ConcurrentHashMap<TunnelId, TunnelGateway>();
        _participatingConfig = new ConcurrentHashMap<TunnelId, HopConfig>();
        _pumper = new TunnelGatewayPumper(ctx);
        _leaveJob = new LeaveTunnel(ctx);
        ctx.statManager().createRequiredRateStat("tunnel.participatingTunnels", "Tunnels routed for others", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.dispatchOutboundPeer", "Outbound messages targeting a peer", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.dispatchOutboundTunnel", "Outbound messages targeting a tunnel", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.dispatchInbound", "Messages we sent through our Tunnel Gateway", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.dispatchParticipant", "Messages we sent through a tunnel we are participating in", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.dispatchEndpoint", "Messages received as Outbound Endpoint of a tunnel", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinOutboundGateway","Tunnels joined as Outbound Gateway", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinOutboundGatewayZeroHop", "Zero hop tunnels joined as Outbound Gateway", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinInboundEndpoint", "Tunnels joined as Inbound Endpoint", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinInboundEndpointZeroHop", "Zero hop tunnels joined as Inbound Endpoint", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinParticipant", "Tunnels joined as participant", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinOutboundEndpoint", "Tunnels joined as Outbound Endpoint", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.joinInboundGateway", "Tunnels joined as Inbound Gateway", "Tunnels [Participating]", RATES);
        //ctx.statManager().createRateStat("tunnel.dispatchGatewayTime", "Time to dispatch a TunnelGatewayMessage", "Tunnels", RATES);
        //ctx.statManager().createRateStat("tunnel.dispatchDataTime", "Time to dispatch a TunnelDataMessage", "Tunnels", RATES);
        //ctx.statManager().createRateStat("tunnel.dispatchOutboundTime", "Time to dispatch an outbound message", "Tunnels", RATES);
        //ctx.statManager().createRateStat("tunnel.dispatchOutboundZeroHopTime", "Time to dispatch an outbound message through a zero hop tunnel", "Tunnels", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.participating InBps", "In (B/s) for Participating tunnels ", "Tunnels [Participating]", RATES);
        ctx.statManager().createRequiredRateStat("tunnel.participating OutBps", "Out (B/s) for Participating tunnels", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.participatingMessageDropped", "Dropped participating messages (share limit exceeded)", "Tunnels [Participating]", RATES);
        // count for console
        ctx.statManager().createRequiredRateStat("tunnel.participatingMessageCount", "Total 1KB participating messages", "Tunnels [Participating]", RATES);
        // estimate for RouterThrottleImpl
        ctx.statManager().createRequiredRateStat("tunnel.participatingMessageCountAvgPerTunnel", "Estimated participating messages per tunnel lifetime", "Tunnels [Participating]", new long[] { 60*1000l });
        ctx.statManager().createRateStat("tunnel.ownedMessageCount", "Messages sent through a tunnel we created", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.failedCompletelyMessages", "Messages sent through a prematurely failed tunnel", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.failedPartially", "Messages sent through a partially failed tunnel", "Tunnels", RATES);
        // following are for BatchedPreprocessor
        ctx.statManager().createRateStat("tunnel.batchMultipleCount", "Messages batched into a tunnel message", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchDelay", "Messages pending when the batching waited", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchDelaySent", "Messages flushed when the batching delay completed", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchCount", "Number of groups of messages flushed together", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchDelayAmount", "Delay before flushing the batch", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchFlushRemaining", "Messages remaining after flush", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.writeDelay", "Message processing time after reaching gateway (ms)", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchSmallFragments", "Number of outgoing pad bytes in small fragments", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchFullFragments", "Outgoing tunnel messages using full data area", "Tunnels [BatchedPreprocessor]", RATES);
        ctx.statManager().createRateStat("tunnel.batchFragmentation", "Average number of fragments per message", "Tunnels [BatchedPreprocessor]", RATES);
        // following is for OutboundMessageDistributor
        ctx.statManager().createRateStat("tunnel.distributeLookupSuccess", "Success rate for deferred lookup (outbound distribution)", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.dropAtOBEP", "Tunnels droppped at Outbound Endpoint (throttled)", "Tunnels", RATES);
        // following is for OutboundReceiver
        ctx.statManager().createRateStat("tunnel.outboundLookupSuccess", "Success rate for deferred lookup (outbound receive)", "Tunnels", RATES);
        // following is for InboundGatewayReceiver
        ctx.statManager().createRateStat("tunnel.inboundLookupSuccess", "Success rate for deferred lookup (inbound receive)", "Tunnels", RATES);
        // following is for TunnelParticipant
        ctx.statManager().createRateStat("tunnel.participantLookupSuccess", "Success rate for deferred lookup (tunnel participant)", "Tunnels [Participating]", RATES);
        // following is for BuildMessageProcessor
        ctx.statManager().createRateStat("tunnel.buildRequestDup", "How often we get duplicate build request messages", "Tunnels [Participating]", RATES);
        ctx.statManager().createRateStat("tunnel.buildRequestBadReplyKey", "Build requests with bad reply keys", "Tunnels", RATES);
        // following are for FragmentHandler
        ctx.statManager().createRateStat("tunnel.smallFragments", "Number of pad bytes in small fragments", "Tunnels [FragmentHandler]", RATES);
        ctx.statManager().createRateStat("tunnel.fullFragments", "Tunnel messages using full data area", "Tunnels [FragmentHandler]", RATES);
        ctx.statManager().createRateStat("tunnel.fragmentedComplete", "Fragments in a completely received message", "Tunnels [FragmentHandler]", RATES);
        ctx.statManager().createRateStat("tunnel.fragmentedDropped", "Total dropped fragments", "Tunnels [FragmentHandler]", RATES);
        ctx.statManager().createRateStat("tunnel.corruptMessage", "Total corrupt messages received", "Tunnels [FragmentHandler]", RATES);
        // following are for InboundMessageDistributor
        ctx.statManager().createRateStat("tunnel.dropDangerousClientTunnelMessage", "Dropped dangerous Inbound messages", "Tunnels", RATES);
        ctx.statManager().createRateStat("tunnel.dropDangerousExplTunnelMessage", "Dropped dangerous Exploratory messages", "Tunnels [Exploratory]", RATES);
        ctx.statManager().createRateStat("tunnel.handleLoadClove", "How often we load test cloves", "Tunnels", RATES);
        // following is for PumpedTunnelGateway
        ctx.statManager().createRateStat("tunnel.dropGatewayOverflow", "Dropped messages at gateway (queue full)", "Tunnels", RATES);
    }

    /** for IBGW */
    private TunnelGateway.QueuePreprocessor createPreprocessor(HopConfig cfg) {
        //if (true)
            return new BatchedRouterPreprocessor(_context, cfg);
        //else
        //    return new TrivialRouterPreprocessor(_context);
    }

    /** for OBGW */
    private TunnelGateway.QueuePreprocessor createPreprocessor(TunnelCreatorConfig cfg) {
        //if (true)
            return new BatchedRouterPreprocessor(_context, cfg);
        //else
        //    return new TrivialRouterPreprocessor(_context);
    }

    /**
     * We are the outbound gateway - we created this tunnel
     *
     *  @return success; false if Tunnel ID is a duplicate
     */
    public boolean joinOutbound(TunnelCreatorConfig cfg) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Outbound Gateway built successfully: " + cfg);
        TunnelGateway gw;
        if (cfg.getLength() > 1) {
            TunnelGateway.QueuePreprocessor preproc = createPreprocessor(cfg);
            TunnelGateway.Sender sender = new OutboundSender(_context, cfg);
            TunnelGateway.Receiver receiver = new OutboundReceiver(_context, cfg);
            //TunnelGateway gw = new TunnelGateway(_context, preproc, sender, receiver);
            gw = new PumpedTunnelGateway(_context, preproc, sender, receiver, _pumper);
        } else {
            gw = new TunnelGatewayZeroHop(_context, cfg);
        }
        TunnelId outId = cfg.getConfig(0).getSendTunnel();
        if (_outboundGateways.putIfAbsent(outId, gw) != null)
            return false;
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
     *  @return success; false if Tunnel ID is a duplicate
     */
    public boolean joinInbound(TunnelCreatorConfig cfg) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Inbound Endpoint built successfully " + cfg);

        if (cfg.getLength() > 1) {
            TunnelParticipant participant = new TunnelParticipant(_context, new InboundEndpointProcessor(_context, cfg, _validator));
            TunnelId recvId = cfg.getConfig(cfg.getLength()-1).getReceiveTunnel();
            if (_participants.putIfAbsent(recvId, participant) != null)
                return false;
            _context.statManager().addRateData("tunnel.joinInboundEndpoint", 1);
            _context.messageHistory().tunnelJoined("inboundEndpoint", cfg);
        } else {
            TunnelGatewayZeroHop gw = new TunnelGatewayZeroHop(_context, cfg);
            TunnelId recvId = cfg.getConfig(0).getReceiveTunnel();
            if (_inboundGateways.putIfAbsent(recvId, gw) != null)
                return false;
            _context.statManager().addRateData("tunnel.joinInboundEndpointZeroHop", 1);
            _context.messageHistory().tunnelJoined("inboundEndpointZeroHop", cfg);
        }
        return true;
    }

    /**
     * We are a participant in this tunnel, but not as the endpoint or gateway
     *
     *  @return success; false if Tunnel ID is a duplicate
     */
    public boolean joinParticipant(HopConfig cfg) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Joining tunnel as participant " + cfg);
        TunnelId recvId = cfg.getReceiveTunnel();
        TunnelParticipant participant = new TunnelParticipant(_context, cfg, new HopProcessor(_context, cfg, _validator));
        synchronized (_joinParticipantLock) {
            if (_participatingConfig.putIfAbsent(recvId, cfg) != null)
                return false;
            if (_participants.putIfAbsent(recvId, participant) != null) {
                _participatingConfig.remove(recvId);
                return false;
            }
        }
        _context.messageHistory().tunnelJoined("participant", cfg);
        _context.statManager().addRateData("tunnel.joinParticipant", 1);
        if (cfg.getExpiration() > _lastParticipatingExpiration)
            _lastParticipatingExpiration = cfg.getExpiration();
        _leaveJob.add(cfg);
        return true;
    }

    /**
     * We are the outbound endpoint in this tunnel, and did not create it
     *
     *  @return success; false if Tunnel ID is a duplicate
     */
    public boolean joinOutboundEndpoint(HopConfig cfg) {
        if (_log.shouldLog(Log.INFO))
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
        return true;
    }

    /**
     * We are the inbound gateway in this tunnel, and did not create it
     *
     *  @return success; false if Tunnel ID is a duplicate
     */
    public boolean joinInboundGateway(HopConfig cfg) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Joining tunnel as Inbound Gateway " + cfg);
        TunnelGateway.QueuePreprocessor preproc = createPreprocessor(cfg);
        TunnelGateway.Sender sender = new InboundSender(_context, cfg);
        TunnelGateway.Receiver receiver = new InboundGatewayReceiver(_context, cfg);
        //TunnelGateway gw = new TunnelGateway(_context, preproc, sender, receiver);
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
        return true;
    }

    public int getParticipatingCount() {
        return _participatingConfig.size();
    }

    /**
     *  Get a new random send tunnel ID that isn't a dup.
     *  Note that we do not keep track of IDs for pending builds so this
     *  does not fully prevent joinOutbound() from failing later.
     *  @since 0.9.5
     */
    public TunnelId getNewOBGWID() {
        long id;
        TunnelId rv;
        do {
            id = 1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE);
            rv = new TunnelId(id);
        } while (_outboundGateways.containsKey(rv));
        return rv;
    }

    /**
     *  Get a new random receive tunnel ID that isn't a dup.
     *  Not for zero hop tunnels.
     *  Note that we do not keep track of IDs for pending builds so this
     *  does not fully prevent joinInbound() from failing later.
     *  @since 0.9.5
     */
    public TunnelId getNewIBEPID() {
        long id;
        TunnelId rv;
        do {
            id = 1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE);
            rv = new TunnelId(id);
        } while (_participants.containsKey(rv));
        return rv;
    }

    /**
     *  Get a new random receive tunnel ID that isn't a dup.
     *  For zero hop tunnels only.
     *  Note that we do not keep track of IDs for pending builds so this
     *  does not fully prevent joinInbound() from failing later.
     *  @since 0.9.5
     */
    public TunnelId getNewIBZeroHopID() {
        long id;
        TunnelId rv;
        do {
            id = 1 + _context.random().nextLong(TunnelId.MAX_ID_VALUE);
            rv = new TunnelId(id);
        } while (_inboundGateways.containsKey(rv));
        return rv;
    }

    /*******  may be used for congestion control later...
    public int getParticipatingInboundGatewayCount() {
        return _inboundGateways.size();
    }
    *******/

    /** what is the date/time on which the last non-locally-created tunnel expires? */
    public long getLastParticipatingExpiration() { return _lastParticipatingExpiration; }

    /**
     * We no longer want to participate in this tunnel that we created
     */
    public void remove(TunnelCreatorConfig cfg) {
        if (cfg.isInbound()) {
            TunnelId recvId = cfg.getConfig(cfg.getLength()-1).getReceiveTunnel();
            if (_log.shouldLog(Log.INFO))
                _log.info("Removing our own Inbound tunnel\n* " + cfg);
            TunnelParticipant participant = _participants.remove(recvId);
            if (participant == null) {
                _inboundGateways.remove(recvId);
            } else {
                // update stats based off getCompleteCount() + getFailedCount()
                // skip last hop (us)
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
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Removing our own Outbound tunnel\n*  " + cfg);
            TunnelId outId = cfg.getConfig(0).getSendTunnel();
            TunnelGateway gw = _outboundGateways.remove(outId);
            if (gw != null) {
                // update stats based on gw.getMessagesSent()
            }
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
    }

    /**
     * No longer participate in the tunnel that someone asked us to be a member of
     *
     */
    public void remove(HopConfig cfg) {
        TunnelId recvId = cfg.getReceiveTunnel();

        boolean removed = (null != _participatingConfig.remove(recvId));
        if (removed) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Removing Participating tunnel config " + cfg /* , new Exception() */ );
        } else {
            // this is normal, this can get called twice
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Participating in tunnel, but no longer listed in participatingConfig? " + cfg); /* , new Exception() */
        }

        removed = (null != _participants.remove(recvId));
        if (removed) return;
        removed = (null != _inboundGateways.remove(recvId));
        if (removed) return;
        _outboundEndpoints.remove(recvId);
    }

    /**
     * We are participating in a tunnel (perhaps we're even the endpoint), so
     * take the message and do what it says.  If there are later hops, that
     * means encrypt a layer and forward it on.  If there aren't later hops,
     * how we handle it depends upon whether we created it or not.  If we didn't,
     * simply honor the instructions.  If we did, unwrap all the layers of
     * encryption and honor those instructions (within reason).
     *
     */
    public void dispatch(TunnelDataMessage msg, Hash recvFrom) {
        //long before = System.currentTimeMillis();
        TunnelParticipant participant = _participants.get(msg.getTunnelIdObj());
        if (participant != null) {
            // we are either just a random participant or the inbound endpoint
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Dispatch to: " + participant + " [MsgID " + msg.getUniqueId() + "] from ["
                           + recvFrom.toBase64().substring(0,6) + "]");
            _context.messageHistory().tunnelDispatched(msg.getUniqueId(), msg.getTunnelId(), "participant");
            participant.dispatch(msg, recvFrom);
            _context.statManager().addRateData("tunnel.dispatchParticipant", 1);
        } else {
            OutboundTunnelEndpoint endpoint = _outboundEndpoints.get(msg.getTunnelIdObj());
            if (endpoint != null) {
                // we are the outobund endpoint
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Dispatch where we are the Outbound Endpoint:\n* " + endpoint + ": "
                               + msg + " from [" + recvFrom.toBase64().substring(0,6) + "]");
                _context.messageHistory().tunnelDispatched(msg.getUniqueId(), msg.getTunnelId(), "outbound endpoint");
                endpoint.dispatch(msg, recvFrom);

                _context.statManager().addRateData("tunnel.dispatchEndpoint", 1);
            } else {
                // Somewhat common, probably due to somebody with large clock skew?
                _context.messageHistory().droppedTunnelDataMessageUnknown(msg.getUniqueId(), msg.getTunnelId());
                int level = (_context.router().getUptime() > 10*60*1000 ? Log.WARN : Log.DEBUG);
                if (_log.shouldLog(level))
                    _log.log(level, "No matching participant/endpoint for [TunnelID " + msg.getTunnelId()
                             + "]\n* Expires: " + DataHelper.formatDuration(msg.getMessageExpiration()-_context.clock().now())
                             + "\n* Existing: " + _participants.size() + " / " + _outboundEndpoints.size());
            }
        }

        //long dispatchTime = System.currentTimeMillis() - before;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Dispatch data time: " + dispatchTime + " participant? " + participant);
        //_context.statManager().addRateData("tunnel.dispatchDataTime", dispatchTime, dispatchTime);
    }

    /** High for now, just to prevent long-lived-message attacks */
    private static final long MAX_FUTURE_EXPIRATION = 3*60*1000 + Router.CLOCK_FUDGE_FACTOR;

    /**
     * We are the inbound tunnel gateway, so encrypt it as necessary and forward
     * it on.
     *
     */
    public void dispatch(TunnelGatewayMessage msg) {
        long before = _context.clock().now();
        TunnelGateway gw = _inboundGateways.get(msg.getTunnelId());
        if (gw != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Dispatch where we are the Inbound gateway:\n* " + gw + ": " + msg);
            long minTime = before - Router.CLOCK_FUDGE_FACTOR;
            long maxTime = before + MAX_FUTURE_EXPIRATION;
            if ( (msg.getMessageExpiration() < minTime) || (msg.getMessage().getMessageExpiration() < minTime) ||
                 (msg.getMessageExpiration() > maxTime) || (msg.getMessage().getMessageExpiration() > maxTime) ) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Not dispatching GatewayMessage for [TunnelID " + msg.getTunnelId().getTunnelId()
                               + "]\n* Wrapper's expiration -> " + DataHelper.formatDuration(msg.getMessageExpiration()-before)
                               + " and/or content expiration -> " + DataHelper.formatDuration(msg.getMessage().getMessageExpiration()-before)
                               + "\n* Type: " + msg.getMessage().getClass().getSimpleName() + "[MsgID " + msg.getUniqueId() + "/" + msg.getMessage().getUniqueId() + "]");
                return;
            }
            //_context.messageHistory().tunnelDispatched("message " + msg.getUniqueId() + "/" + msg.getMessage().getUniqueId() + " on tunnel "
            //                                               + msg.getTunnelId().getTunnelId() + " as inbound gateway");
            _context.messageHistory().tunnelDispatched(msg.getUniqueId(), msg.getMessage().getUniqueId(), msg.getTunnelId().getTunnelId(), "inbound gateway");
            gw.add(msg);
            _context.statManager().addRateData("tunnel.dispatchInbound", 1);
        } else {
            _context.messageHistory().droppedTunnelGatewayMessageUnknown(msg.getUniqueId(), msg.getTunnelId().getTunnelId());
            int level = (_context.router().getUptime() > 10*60*1000 ? Log.WARN : Log.INFO);
            if (_log.shouldLog(level))
                _log.log(level, "No matching tunnel for [TunnelID " + msg.getTunnelId().getTunnelId()
                           + "]\n* Gateway message expires: "
                           + DataHelper.formatDuration(msg.getMessageExpiration()-_context.clock().now())
                           + "/"
                           + DataHelper.formatDuration(msg.getMessage().getMessageExpiration()-_context.clock().now())
                           + " [MsgID " + msg.getUniqueId()
                           + "/" + msg.getMessage().getUniqueId()
                           + "] Type: " + msg.getMessage().getClass().getSimpleName()
                           + "; Existing: " + _inboundGateways.size());
        }

        //long dispatchTime = _context.clock().now() - before;

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Dispatch in gw time: " + dispatchTime + " gateway? " + gw);
        //_context.statManager().addRateData("tunnel.dispatchGatewayTime", dispatchTime, dispatchTime);
    }

    /**
     * We are the outbound tunnel gateway (we created it), so wrap up this message
     * with instructions to be forwarded to the targetPeer when it reaches the
     * endpoint.
     *
     * @param msg raw message to deliver to the target peer
     * @param outboundTunnel tunnel to send the message out, or null for direct
     * @param targetPeer peer to receive the message
     */
    public void dispatchOutbound(I2NPMessage msg, TunnelId outboundTunnel, Hash targetPeer) {
        dispatchOutbound(msg, outboundTunnel, null, targetPeer);
    }

    /**
     * We are the outbound tunnel gateway (we created it), so wrap up this message
     * with instructions to be forwarded to the targetTunnel on the targetPeer when
     * it reaches the endpoint.
     *
     * @param msg raw message to deliver to the targetTunnel on the targetPeer
     * @param outboundTunnel tunnel to send the message out
     * @param targetTunnel tunnel on the targetPeer to deliver the message to, or null for direct
     * @param targetPeer gateway to the tunnel to receive the message
     */
    public void dispatchOutbound(I2NPMessage msg, TunnelId outboundTunnel, TunnelId targetTunnel, Hash targetPeer) {
        if (outboundTunnel == null) throw new IllegalArgumentException("null outbound tunnel?");
        long before = _context.clock().now();
        TunnelGateway gw = _outboundGateways.get(outboundTunnel);
        if (gw != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Dispatch Outbound through " + outboundTunnel.getTunnelId()
                           + ": " + msg);
            if (msg.getMessageExpiration() < before - Router.CLOCK_FUDGE_FACTOR) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Why are we sending a tunnel message that expired "
                               + (before-msg.getMessageExpiration()) + "ms ago? "
                               + msg, new Exception("cause"));
                return;
            } else if (msg.getMessageExpiration() < before) {
                // nonfatal, as long as it was remotely created
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Why are we sending a tunnel message that expired "
                               + (before-msg.getMessageExpiration()) + "ms ago? "
                               + msg, new Exception("cause"));
            } else if (msg.getMessageExpiration() > before + MAX_FUTURE_EXPIRATION) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Why are we sending a tunnel message that expires "
                               + (msg.getMessageExpiration() - before) + "ms from now? "
                               + msg, new Exception("cause"));
                return;
            }
            long tid1 = outboundTunnel.getTunnelId();
            long tid2 = (targetTunnel != null ? targetTunnel.getTunnelId() : -1);
            _context.messageHistory().tunnelDispatched(msg.getUniqueId(), tid1, tid2, targetPeer, "Outbound gateway");
            gw.add(msg, targetPeer, targetTunnel);
            if (targetTunnel == null)
                _context.statManager().addRateData("tunnel.dispatchOutboundPeer", 1);
            else
                _context.statManager().addRateData("tunnel.dispatchOutboundTunnel", 1);
        } else {
            _context.messageHistory().droppedTunnelGatewayMessageUnknown(msg.getUniqueId(), outboundTunnel.getTunnelId());

            //int level = (_context.router().getUptime() > 10*60*1000 ? Log.ERROR : Log.WARN);
            int level = Log.WARN;
            if (_log.shouldLog(level))
                _log.log(level, "No matching Outbound tunnel for id=" + outboundTunnel
                           + ": existing = " + _outboundGateways.size(), new Exception("src"));
        }

        //long dispatchTime = _context.clock().now() - before;
        //if (dispatchTime > 1000) {
        //    if (_log.shouldLog(Log.WARN))
        //        _log.warn("slow? took " + dispatchTime + " to dispatch " + msg + " out " + outboundTunnel + " in " + gw);
        //}
        //if (gw instanceof TunnelGatewayZeroHop)
        //    _context.statManager().addRateData("tunnel.dispatchOutboundZeroHopTime", dispatchTime, dispatchTime);
        //else
        //    _context.statManager().addRateData("tunnel.dispatchOutboundTime", dispatchTime, dispatchTime);
    }

    /**
     *  Only for console TunnelRenderer.
     *  @return a copy
     */
    public List<HopConfig> listParticipatingTunnels() {
        return new ArrayList<HopConfig>(_participatingConfig.values());
    }

    /**
     * Generate a current estimate of usage per-participating-tunnel lifetime.
     * The router code calls this every 'ms' millisecs.
     * This is better than waiting until the tunnel expires to update the rate,
     * as we want this to be current because it's an important part of
     * the throttle code.
     * Stay a little conservative by taking the counts only for tunnels 1-10m old
     * and computing the average from that.
     */
    public void updateParticipatingStats(int ms) {
        int partCount = _context.tunnelManager().getParticipatingCount();
        long count = 0;
        long bw = 0;
        //long bwOut = 0;
        long tcount = 0;
        long tooYoung = _context.clock().now() - 60*1000;
        long tooOld = tooYoung - 9*60*1000;
        for (HopConfig cfg : _participatingConfig.values()) {
            long c = cfg.getAndResetRecentMessagesCount();
            bw += c;
            //bwOut += cfg.getRecentSentMessagesCount();
            long created = cfg.getCreation();
            if (created > tooYoung || created < tooOld)
                continue;
            tcount++;
            count += c;
        }
        // This is an estimate of the average number of participating messages per tunnel
        // in a tunnel lifetime, used only by RouterThrottleImpl
        // 10 minutes / 50 seconds = 12
        if (tcount > 0)
            count = count * (10*60*1000 / ms) / tcount;
        _context.statManager().addRateData("tunnel.participatingMessageCountAvgPerTunnel", count, ms);
        // This is a straight count of the total participating messages, used in the router console
        _context.statManager().addRateData("tunnel.participatingMessageCount", bw, ms);
        // Bandwidth in bits per second
        _context.statManager().addRateData("tunnel.participating InBps", bw*1024/(ms/1000), ms);
        // moved to FIFOBandwidthRefiller
        //_context.statManager().addRateData("tunnel.participating OutBps", bwOut*1024/(ms/1000), ms);
        _context.statManager().addRateData("tunnel.participatingTunnels", partCount);
    }

    /**
     * Implement random early discard (RED) to enforce the share bandwidth limit.
     * For now, this does not enforce the available bandwidth,
     * we leave that to Throttle.
     * This is similar to the code in ../RouterThrottleImpl.java
     * We drop in proportion to how far over the limit we are.
     * Perhaps an exponential function would be better?
     *
     * The drop probability is adjusted for the size of the message.
     * At this stage, participants and IBGWs see a standard 1024 byte message.
     * OBEPs however may see a wide variety of sizes.
     *
     * Network-wise, it's most efficient to drop OBEP messages, because they
     * are unfragmented and we know their size. Therefore we drop the big ones
     * and we drop a single wrapped I2CP message, not a fragment of one or more messages.
     * Also, the OBEP is the earliest identifiable hop in the message's path
     * (a plain participant could be earlier or later, but on average is later)
     *
     * @param loc message hop location
     * @param type I2NP message type
     * @param length the length of the message
     */
    public boolean shouldDropParticipatingMessage(Location loc, int type, int length) {
        if (length <= 0)
            return false;

        // increase the drop probability for OBEP,
        // (except lower it for tunnel build messages type 21/22/23/24),
        // and lower it for IBGW, for network efficiency
        float factor;
        if (loc == Location.OBEP) {
            // we don't need to check for VTBRM/TBRM as that happens at tunnel creation
            if (type == VariableTunnelBuildMessage.MESSAGE_TYPE || type == TunnelBuildMessage.MESSAGE_TYPE ||
                type == ShortTunnelBuildMessage.MESSAGE_TYPE)
                factor = 1 / 1.5f;
            else
                factor = 1.5f;
        } else if (loc == Location.IBGW) {
            // we don't need to check for VTBM/TBM as that happens at tunnel creation
            if (type == VariableTunnelBuildReplyMessage.MESSAGE_TYPE || type == TunnelBuildReplyMessage.MESSAGE_TYPE ||
                type == OutboundTunnelBuildReplyMessage.MESSAGE_TYPE || type == InboundTunnelBuildMessage.MESSAGE_TYPE)
                factor = 1 / (1.5f * 1.5f * 1.5f);
            else
                factor = 1 / 1.5f;
        } else {
            factor = 1.0f;
        }
        boolean reject = ! _context.bandwidthLimiter().sentParticipatingMessage(length, factor);
        if (reject) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Dropping Participating message [factor=" + factor
                          + ' ' + loc + ' ' + type + ' ' + length + "]");
            }
            _context.statManager().addRateData("tunnel.participatingMessageDropped", 1);
        }
        return reject;
    }

    //private static final int DROP_BASE_INTERVAL = 40 * 1000;
    //private static final int DROP_RANDOM_BOOST = 10 * 1000;

    /**
     * If a router is too overloaded to build its own tunnels,
     * the build executor may call this.
     */
/*******
    public void dropBiggestParticipating() {

       List<HopConfig> partTunnels = listParticipatingTunnels();
       if ((partTunnels == null) || (partTunnels.isEmpty())) {
           if (_log.shouldLog(Log.ERROR))
               _log.error("Not dropping tunnel, since partTunnels was null or had 0 items!");
           return;
       }

       long periodWithoutDrop = _context.clock().now() - _lastDropTime;
       if (periodWithoutDrop < DROP_BASE_INTERVAL) {
           if (_log.shouldLog(Log.WARN))
               _log.warn("Not dropping tunnel, since last drop was " + periodWithoutDrop + " ms ago!");
           return;
       }

       HopConfig biggest = null;
       HopConfig current = null;

       long biggestMessages = 0;
       long biggestAge = -1;
       double biggestRate = 0;

       for (int i=0; i<partTunnels.size(); i++) {

           current = partTunnels.get(i);

           long currentMessages = current.getProcessedMessagesCount();
           long currentAge = (_context.clock().now() - current.getCreation());
           double currentRate = ((double) currentMessages / (currentAge / 1000));

           // Determine if this is the biggest, but don't include tunnels
           // with less than 20 messages (unpredictable rates)
           if ((currentMessages > 20) && ((biggest == null) || (currentRate > biggestRate))) {
               // Update our profile of the biggest
               biggest = current;
               biggestMessages = currentMessages;
               biggestAge = currentAge;
               biggestRate = currentRate;
           }
       }

       if (biggest == null) {
           if (_log.shouldLog(Log.ERROR))
               _log.error("Not dropping tunnel, since no suitable tunnel was found.");
           return;
       }

       if (_log.shouldLog(Log.WARN))
           _log.warn("Dropping tunnel with " + biggestRate + " messages/s and " + biggestMessages +
                      " messages, last drop was " + (periodWithoutDrop / 1000) + " s ago.");
       remove(biggest);
       _lastDropTime = _context.clock().now() + _context.random().nextInt(DROP_RANDOM_BOOST);
    }
******/

    /** startup */
    public synchronized void startup() {
        // Note that we only use the validator for participants and OBEPs, not IBGWs, so
        // this BW estimate will be high by about 33% assuming 2-hop tunnels average
        _validator = new BloomFilterIVValidator(_context, getShareBandwidth(_context));
    }

    /** @return in KBps */
    public static int getShareBandwidth(RouterContext ctx) {
        int irateKBps = ctx.bandwidthLimiter().getInboundKBytesPerSecond();
        int orateKBps = ctx.bandwidthLimiter().getOutboundKBytesPerSecond();
        double pct = ctx.router().getSharePercentage();
        return (int) (pct * Math.min(irateKBps, orateKBps));
    }

    public synchronized void shutdown() {
        if (_validator != null)
            _validator.destroy();
        _validator = null;
        _pumper.stopPumping();
        _outboundGateways.clear();
        _outboundEndpoints.clear();
        _participants.clear();
        _inboundGateways.clear();
        _participatingConfig.clear();
        _leaveJob.clear();
    }

    public void restart() {
        shutdown();
        startup();
    }

    /** @deprecated moved to router console */
    @Deprecated
    public void renderStatusHTML(Writer out) throws IOException {}

    /**
     *  Expire participants.
     *  For efficiency, we keep the HopConfigs in a FIFO, and assume that
     *  tunnels expire (roughly) in the same order as they are added.
     *  As tunnels have a fixed expiration from now, that's a good assumption -
     *  see BuildHandler.handleReq().
     */
    private class LeaveTunnel extends JobImpl {
        private final LinkedBlockingQueue<HopConfig> _configs;

        public LeaveTunnel(RouterContext ctx) {
            super(ctx);
            _configs = new LinkedBlockingQueue<HopConfig>();
            // 10 min no tunnels accepted + 10 min tunnel expiration
            getTiming().setStartAfter(ctx.clock().now() + 20*60*1000);
            getContext().jobQueue().addJob(LeaveTunnel.this);
        }

        private static final int LEAVE_BATCH_TIME = 10*1000;

        public void add(HopConfig cfg) {
            _configs.offer(cfg);
        }

        public void clear() {
            _configs.clear();
        }

        public String getName() { return "Expire Participating Tunnels"; }
        public void runJob() {
            HopConfig cur = null;
            long now = getContext().clock().now() + LEAVE_BATCH_TIME; // leave all expiring in next 10 sec
            long nextTime = now + 10*60*1000;
            while ((cur = _configs.peek()) != null) {
                long exp = cur.getExpiration() + (2 * Router.CLOCK_FUDGE_FACTOR) + LEAVE_BATCH_TIME;
                if (exp < now) {
                    _configs.poll();
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Expiring Participating tunnel: " + cur);
                    remove(cur);
                } else {
                    if (exp < nextTime)
                        nextTime = exp;
                    break;
                }
            }
            getTiming().setStartAfter(nextTime);
            getContext().jobQueue().addJob(LeaveTunnel.this);
        }
    }
}
