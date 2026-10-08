package net.i2p.router.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Tuner;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.udp.UDPTransport;

/**
 * Form handler for the transport tuning page.
 * Persists min/max/step ranges to autotune.config without restart.
 *
 * @since 0.9.70+
 */
public class TuningFormHandler extends FormHandler {

    /**
     * Construct a handler for one tuning-page submission; submitted field
     * values arrive through the setters below and are applied by processForm().
     */
    public TuningFormHandler() {}

    /** Tunable accepts Min/Max/Step range fields. */
    private static final int HAS_RANGE = 1;
    /** Tunable accepts a Default field. */
    private static final int HAS_DEFAULT = 2;
    /** Tunable accepts an Override (auto/manual) field. */
    private static final int HAS_OVERRIDE = 4;

    /** A tunable parameter: property name, form-field prefix, and accepted suffix fields. */
    private static final class Tunable {
        final String prop;
        final String prefix;
        final int flags;

        Tunable(String prop, String prefix, int flags) {
            this.prop = prop;
            this.prefix = prefix;
            this.flags = flags;
        }
    }

    private static final List<Tunable> TUNED = new ArrayList<>(64);
    static {
        Tunable t;
        // Transport
        t = new Tunable("ACK_FREQUENCY", "ackFrequency", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("DATA_MESSAGE_TIMEOUT", "dataMessageTimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("MAX_OB_ESTABLISH_TIME", "obEstablishTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("MAX_IB_ESTABLISH_TIME", "ibEstablishTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2np.udp.maxConcurrentEstablish", "maxConcurrentEstablish", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Tunnel
        t = new Tunable("REQUEUE_TIME", "requeueTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("REPLENISH_FREQUENCY", "replenishFrequency", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("SELECTOR_LOOP_DELAY", "selectorLoopDelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("MAX_OB_MSGS_PER_PUMP", "obMsgsPerPump", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("MAX_IB_MSGS_PER_PUMP", "ibMsgsPerPump", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Streaming
        t = new Tunable("INITIAL_WINDOW_SIZE", "initialWindowSize", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("INITIAL_RTO", "initialRTO", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("INITIAL_ACK_DELAY", "initialAckDelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("PASSIVE_FLUSH_DELAY", "passiveFlushDelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("MAX_STREAMS", "maxStreams", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // I2CP
        t = new Tunable("CLIENT_WRITER_QUEUE_SIZE", "writerQueueSize", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // CoDel
        t = new Tunable("CODEL_TARGET", "codelTarget", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("CODEL_INTERVAL", "codelInterval", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Westwood
        t = new Tunable("WESTWOOD_DECAY_FACTOR", "westwoodDecayFactor", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Streaming (continued)
        t = new Tunable("i2p.streaming.maxSlowStartWindow", "maxSlowStartWindow", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.maxInboundBuffer", "maxInboundBuffer", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Buffers & Threads
        t = new Tunable("crypto.x25519.precalcMin", "xdhPreCalcMin", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("crypto.edh.precalcMin", "edhPrecalcMin", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("crypto.mlkem.precalcMin", "mlkemPrecalcMin", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.sendFinisher.threads", "ntcpThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.sendFinisher.queueCapacity", "ntcpQueueCapacity", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.packetHandler.maxThreads", "udpHandlerThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.peerOutboundQueueSize", "peerOutboundQueue", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Router Core
        t = new Tunable("router.transitThrottleFactor", "transitThrottleFactor", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.throttleRejectExponent", "throttleRejectExponent", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.maxParticipatingTunnels", "maxParticipatingTunnels", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.buildHandlerMaxQueue", "buildHandlerMaxQueue", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.goodDeficitThrottle", "goodDeficitThrottle", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.tunnel.perTunnelBweDivisor", "perTunnelBweDivisor", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.tunnelGrowthFactor", "tunnelGrowthFactor", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.serverHandler.threads", "threads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.serverHandler.queueCapacity", "serverBacklogQueue", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.server.threads", "serverThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Streaming congestion
        t = new Tunable("i2p.streaming.maxRTO", "maxRTO", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.maxResendDelay", "maxResendDelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.maxRetransmissions", "maxRetransmissions", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.maxRtt", "maxRtt", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.initialResendDelay", "initialResendDelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.immediateAckDelay", "immediateAckDelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.minResendDelay", "minResendDelay", HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.congestionAvoidanceGrowthRateFactor", "congestionAvoidanceGrowth", HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.slowStartGrowthRateFactor", "slowStartGrowth", HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // NetDB
        t = new Tunable("netdb.searchLimit", "netDBSearchLimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("netdb.maxConcurrent", "netDBMaxConcurrent", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("netdb.singleSearchTime", "netDBSingleSearchTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("netdb.maxSearchTime", "netDBMaxSearchTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("netdb.resendTimeout", "netDBResendTimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("netdb.leaseResendCount", "netDBLeaseResend", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("router.exploreBredth", "exploreBredth", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Peer management
        t = new Tunable("profileOrganizer.maxProfiles", "maxProfiles", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.minFastPeers", "minFastPeers", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.lossyThreshold", "lossyThreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.maxFastPeers", "maxFastPeers", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.minHighCapacityPeers", "minHighCapPeers", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.maxHighCapacityPeers", "maxHighCapPeers", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Build timeouts
        t = new Tunable("i2p.tunnel.build.requestTimeout", "buildRequestTimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.build.firstHopTimeout", "buildFirstHopTimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Streaming
        t = new Tunable("CONNECT_TIMEOUT_MULTIPLIER", "connectTimeoutMultiplier", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// NetDB
        t = new Tunable("MAX_LS_LOOKUP_TIME", "maxLsLookupTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("MAX_RI_LOOKUP_TIME", "maxRiLookupTime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Transport
        t = new Tunable("NTCP_ESTABLISH_TIMEOUT", "ntcpEstablishTimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Congestion
        t = new Tunable("RED_MAX_DROP_PROB", "redMaxDropProb", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("RED_MAX_THRESHOLD", "redMaxThreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("RED_MIN_THRESHOLD", "redMinThreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// I2CP
        t = new Tunable("i2cp.internalQueueSize", "i2cpInternalqueuesize", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Router
        t = new Tunable("i2p.router.handlerThreadPriority", "i2pRouterHandlerthreadpriority", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.router.maxDispatchAge", "i2pRouterMaxdispatchage", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Streaming
        t = new Tunable("i2p.streaming.inactivityTimeout", "i2pStreamingInactivitytimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.maxSynResends", "i2pStreamingMaxsynresends", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.maxWindowSize", "i2pStreamingMaxwindowsize", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.minPacingRate", "i2pStreamingMinpacingrate", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.rtoMultiplier", "i2pStreamingRtomultiplier", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Tunnel
        t = new Tunable("i2p.tunnel.build.maxLookupLimit", "i2pTunnelBuildMaxlookuplimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.build.percentLookupLimit", "i2pTunnelBuildPercentlookuplimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Transit throttles
        t = new Tunable("i2p.tunnel.participatingThrottle.loadWeight", "i2pTunnelParticipatingthrottleLoadweight", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.participatingThrottle.maxLimit", "i2pTunnelParticipatingthrottleMaxlimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.participatingThrottle.minLimit", "i2pTunnelParticipatingthrottleMinlimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.participatingThrottle.percentLimit", "i2pTunnelParticipatingthrottlePercentlimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.participatingThrottle.rejectSteepness", "i2pTunnelParticipatingthrottleRejectsteepness", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.participatingThrottle.rejectThreshold", "i2pTunnelParticipatingthrottleRejectthreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.burst1sThreshold", "i2pTunnelRequestthrottleBurst1sthreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.highLoadCpuPct", "i2pTunnelRequestthrottleHighloadcpupct", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.highLoadLagMs", "i2pTunnelRequestthrottleHighloadlagms", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.loadWeight", "i2pTunnelRequestthrottleLoadweight", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.maxLimit", "i2pTunnelRequestthrottleMaxlimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.minLimit", "i2pTunnelRequestthrottleMinlimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.moderateLoadCpuPct", "i2pTunnelRequestthrottleModerateloadcpupct", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.moderateLoadLagMs", "i2pTunnelRequestthrottleModerateloadlagms", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.percentLimit", "i2pTunnelRequestthrottlePercentlimit", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.rejectSteepness", "i2pTunnelRequestthrottleRejectsteepness", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.rejectThreshold", "i2pTunnelRequestthrottleRejectthreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.requestThrottle.sustainedModerateLoadMs", "i2pTunnelRequestthrottleSustainedmoderateloadms", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Tunnel
        t = new Tunable("i2p.tunnel.socketConnectTimeout", "i2pTunnelSocketconnecttimeout", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.targetBuffer", "i2pTunnelTargetbuffer", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.untestedMultiplier", "i2pTunnelUntestedmultiplier", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.clientRunner.max", "i2ptunnelClientrunnerMax", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.maxConnections", "i2ptunnelMaxconnections", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Transport
        t = new Tunable("ntcp.failsafe.iterationFreq", "ntcpFailsafeIterationfreq", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.maxWriteBufs", "ntcpMaxwritebufs", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.reader.threads", "ntcpReaderThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.sendPool.capacity", "ntcpSendpoolCapacity", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.writer.threads", "ntcpWriterThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("rdns.corePoolSize", "rdnsCorepoolsize", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Router
        t = new Tunable("router.buildHandlerThreads", "routerBuildhandlerthreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Transit throttles
        t = new Tunable("router.defaultProcessingTimeThrottle", "routerDefaultprocessingtimethrottle", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Tunnel
        t = new Tunable("tunnel.build.maxConcurrent", "tunnelBuildMaxconcurrent", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.peerSelection.activityWindowMultiplier", "tunnelPeerselectionActivitywindowmultiplier", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.pool.backoffMs", "tunnelPoolBackoffms", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.pool.failureThreshold", "tunnelPoolFailurethreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        // Params that were Tuner-tunable but absent from this whitelist, so the
        // console rendered their rows read-only: getFormPrefix() returned null
        // and no input was emitted. Listed here to make them editable. Prefixes
        // follow the existing lower-case, dot-stripped convention and must stay
        // unique across TUNED because the POST handler maps field -> property
        // through this same list.
        t = new Tunable("i2p.tunnel.ivFilterM", "i2ptunnelivFilterM", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.build.staleThreshold", "tunnelbuildstaleThreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.build.concurrencyThrottle", "tunnelbuildconcurrencyThrottle", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.build.firstHopCooldown", "tunnelbuildfirstHopCooldown", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.build.firstHopThreshold", "tunnelbuildfirstHopThreshold", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.streaming.receiveWorkerThreads", "i2pstreamingreceiveWorkerThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.serverIO.threads", "i2ptunnelserverIOthreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2ptunnel.serverIO.stallTimeoutMs", "i2ptunnelserverIOstallTimeoutMs", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("ntcp.pumper.maxIdleLps", "ntcppummermaxIdleLps", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.pumper.queueCapacity", "tunnelPumperQueuecapacity", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.pumper.threads", "tunnelPumperThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.testJob.maxQueued", "tunnelTestjobMaxqueued", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.testJob.maxTestDelay", "tunnelTestjobMaxtestdelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("tunnel.testJob.minTestDelay", "tunnelTestjobMintestdelay", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.testJob.maxTestPeriod", "tunnelTestjobMaxtestperiod", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("i2p.tunnel.testJob.minTestPeriod", "tunnelTestjobMintestperiod", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
// Transport
        t = new Tunable("udp.establish.maxQueuedOutbound", "udpEstablishMaxqueuedoutbound", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.messageReceiver.threads", "udpMessagereceiverThreads", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.concurrentMaxMessages", "udpPeerConcurrentmaxmessages", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.initConcurrentMsgs", "udpPeerInitconcurrentmsgs", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.initRTO", "udpPeerInitrto", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.maxRTO", "udpPeerMaxrto", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.maxSendWindow", "udpPeerMaxsendwindow", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.minConcurrentMsgs", "udpPeerMinconcurrentmsgs", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.minRTO", "udpPeerMinrto", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.outboundMsgExpiration", "udpPeerOutboundmsgexpiration", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.postRTOWindowMTUs", "udpPeerPostrtowindowmtus", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
        t = new Tunable("udp.peer.sentMessagesCleanTime", "udpPeerSentmessagescleantime", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
        TUNED.add(t);
    }

    /** Form field values submitted via jsp:setProperty, keyed by field name. */
    private final Map<String, String> _formValues = new HashMap<>(128);

    // setters, called by jsp:setProperty for each submitted form field
    /**
     * Record the Default control for the ACK_FREQUENCY Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setAckFrequencyDefault(String v) { _formValues.put("ackFrequencyDefault", v); }
    /**
     * Record the Max control for the ACK_FREQUENCY Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setAckFrequencyMax(String v) { _formValues.put("ackFrequencyMax", v); }
    /**
     * Record the Min control for the ACK_FREQUENCY Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setAckFrequencyMin(String v) { _formValues.put("ackFrequencyMin", v); }
    /**
     * Record the Override control for the ACK_FREQUENCY Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setAckFrequencyOverride(String v) { _formValues.put("ackFrequencyOverride", v); }
    /**
     * Record the Step control for the ACK_FREQUENCY Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setAckFrequencyStep(String v) { _formValues.put("ackFrequencyStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.build.firstHopTimeout Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setBuildFirstHopTimeoutDefault(String v) { _formValues.put("buildFirstHopTimeoutDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.build.firstHopTimeout Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setBuildFirstHopTimeoutMax(String v) { _formValues.put("buildFirstHopTimeoutMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.build.firstHopTimeout Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setBuildFirstHopTimeoutMin(String v) { _formValues.put("buildFirstHopTimeoutMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.build.firstHopTimeout
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setBuildFirstHopTimeoutOverride(String v) { _formValues.put("buildFirstHopTimeoutOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.build.firstHopTimeout Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setBuildFirstHopTimeoutStep(String v) { _formValues.put("buildFirstHopTimeoutStep", v); }
    /**
     * Record the Default control for the router.buildHandlerMaxQueue Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setBuildHandlerMaxQueueDefault(String v) { _formValues.put("buildHandlerMaxQueueDefault", v); }
    /**
     * Record the Max control for the router.buildHandlerMaxQueue Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setBuildHandlerMaxQueueMax(String v) { _formValues.put("buildHandlerMaxQueueMax", v); }
    /**
     * Record the Min control for the router.buildHandlerMaxQueue Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setBuildHandlerMaxQueueMin(String v) { _formValues.put("buildHandlerMaxQueueMin", v); }
    /**
     * Record the Override control for the router.buildHandlerMaxQueue Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setBuildHandlerMaxQueueOverride(String v) { _formValues.put("buildHandlerMaxQueueOverride", v); }
    /**
     * Record the Step control for the router.buildHandlerMaxQueue Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setBuildHandlerMaxQueueStep(String v) { _formValues.put("buildHandlerMaxQueueStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.build.requestTimeout Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setBuildRequestTimeoutDefault(String v) { _formValues.put("buildRequestTimeoutDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.build.requestTimeout Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setBuildRequestTimeoutMax(String v) { _formValues.put("buildRequestTimeoutMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.build.requestTimeout Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setBuildRequestTimeoutMin(String v) { _formValues.put("buildRequestTimeoutMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.build.requestTimeout Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setBuildRequestTimeoutOverride(String v) { _formValues.put("buildRequestTimeoutOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.build.requestTimeout Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setBuildRequestTimeoutStep(String v) { _formValues.put("buildRequestTimeoutStep", v); }
    /**
     * Record the Default control for the CODEL_INTERVAL Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setCodelIntervalDefault(String v) { _formValues.put("codelIntervalDefault", v); }
    /**
     * Record the Max control for the CODEL_INTERVAL Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setCodelIntervalMax(String v) { _formValues.put("codelIntervalMax", v); }
    /**
     * Record the Min control for the CODEL_INTERVAL Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setCodelIntervalMin(String v) { _formValues.put("codelIntervalMin", v); }
    /**
     * Record the Override control for the CODEL_INTERVAL Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setCodelIntervalOverride(String v) { _formValues.put("codelIntervalOverride", v); }
    /**
     * Record the Step control for the CODEL_INTERVAL Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setCodelIntervalStep(String v) { _formValues.put("codelIntervalStep", v); }
    /**
     * Record the Default control for the CODEL_TARGET Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setCodelTargetDefault(String v) { _formValues.put("codelTargetDefault", v); }
    /**
     * Record the Max control for the CODEL_TARGET Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setCodelTargetMax(String v) { _formValues.put("codelTargetMax", v); }
    /**
     * Record the Min control for the CODEL_TARGET Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setCodelTargetMin(String v) { _formValues.put("codelTargetMin", v); }
    /**
     * Record the Override control for the CODEL_TARGET Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setCodelTargetOverride(String v) { _formValues.put("codelTargetOverride", v); }
    /**
     * Record the Step control for the CODEL_TARGET Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setCodelTargetStep(String v) { _formValues.put("codelTargetStep", v); }
    /**
     * Record the Default control for the
     * i2p.streaming.congestionAvoidanceGrowthRateFactor Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setCongestionAvoidanceGrowthDefault(String v) { _formValues.put("congestionAvoidanceGrowthDefault", v); }
    /**
     * Record the Override control for the
     * i2p.streaming.congestionAvoidanceGrowthRateFactor Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setCongestionAvoidanceGrowthOverride(String v) { _formValues.put("congestionAvoidanceGrowthOverride", v); }
    /**
     * Record the Default control for the DATA_MESSAGE_TIMEOUT Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setDataMessageTimeoutDefault(String v) { _formValues.put("dataMessageTimeoutDefault", v); }
    /**
     * Record the Max control for the DATA_MESSAGE_TIMEOUT Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setDataMessageTimeoutMax(String v) { _formValues.put("dataMessageTimeoutMax", v); }
    /**
     * Record the Min control for the DATA_MESSAGE_TIMEOUT Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setDataMessageTimeoutMin(String v) { _formValues.put("dataMessageTimeoutMin", v); }
    /**
     * Record the Override control for the DATA_MESSAGE_TIMEOUT Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setDataMessageTimeoutOverride(String v) { _formValues.put("dataMessageTimeoutOverride", v); }
    /**
     * Record the Step control for the DATA_MESSAGE_TIMEOUT Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setDataMessageTimeoutStep(String v) { _formValues.put("dataMessageTimeoutStep", v); }
    /**
     * Record the Default control for the crypto.edh.precalcMin Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setEdhPrecalcMinDefault(String v) { _formValues.put("edhPrecalcMinDefault", v); }
    /**
     * Record the Max control for the crypto.edh.precalcMin Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setEdhPrecalcMinMax(String v) { _formValues.put("edhPrecalcMinMax", v); }
    /**
     * Record the Min control for the crypto.edh.precalcMin Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setEdhPrecalcMinMin(String v) { _formValues.put("edhPrecalcMinMin", v); }
    /**
     * Record the Override control for the crypto.edh.precalcMin Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setEdhPrecalcMinOverride(String v) { _formValues.put("edhPrecalcMinOverride", v); }
    /**
     * Record the Step control for the crypto.edh.precalcMin Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setEdhPrecalcMinStep(String v) { _formValues.put("edhPrecalcMinStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.goodDeficitThrottle Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setGoodDeficitThrottleDefault(String v) { _formValues.put("goodDeficitThrottleDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.goodDeficitThrottle Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setGoodDeficitThrottleMax(String v) { _formValues.put("goodDeficitThrottleMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.goodDeficitThrottle Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setGoodDeficitThrottleMin(String v) { _formValues.put("goodDeficitThrottleMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.goodDeficitThrottle Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setGoodDeficitThrottleOverride(String v) { _formValues.put("goodDeficitThrottleOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.goodDeficitThrottle Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setGoodDeficitThrottleStep(String v) { _formValues.put("goodDeficitThrottleStep", v); }
    /**
     * Record the Default control for the MAX_IB_ESTABLISH_TIME Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setIbEstablishTimeDefault(String v) { _formValues.put("ibEstablishTimeDefault", v); }
    /**
     * Record the Max control for the MAX_IB_ESTABLISH_TIME Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setIbEstablishTimeMax(String v) { _formValues.put("ibEstablishTimeMax", v); }
    /**
     * Record the Min control for the MAX_IB_ESTABLISH_TIME Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setIbEstablishTimeMin(String v) { _formValues.put("ibEstablishTimeMin", v); }
    /**
     * Record the Override control for the MAX_IB_ESTABLISH_TIME Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setIbEstablishTimeOverride(String v) { _formValues.put("ibEstablishTimeOverride", v); }
    /**
     * Record the Step control for the MAX_IB_ESTABLISH_TIME Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setIbEstablishTimeStep(String v) { _formValues.put("ibEstablishTimeStep", v); }
    /**
     * Record the Default control for the MAX_IB_MSGS_PER_PUMP Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setIbMsgsPerPumpDefault(String v) { _formValues.put("ibMsgsPerPumpDefault", v); }
    /**
     * Record the Max control for the MAX_IB_MSGS_PER_PUMP Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setIbMsgsPerPumpMax(String v) { _formValues.put("ibMsgsPerPumpMax", v); }
    /**
     * Record the Min control for the MAX_IB_MSGS_PER_PUMP Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setIbMsgsPerPumpMin(String v) { _formValues.put("ibMsgsPerPumpMin", v); }
    /**
     * Record the Override control for the MAX_IB_MSGS_PER_PUMP Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setIbMsgsPerPumpOverride(String v) { _formValues.put("ibMsgsPerPumpOverride", v); }
    /**
     * Record the Step control for the MAX_IB_MSGS_PER_PUMP Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setIbMsgsPerPumpStep(String v) { _formValues.put("ibMsgsPerPumpStep", v); }
    /**
     * Record the Default control for the i2p.streaming.immediateAckDelay Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setImmediateAckDelayDefault(String v) { _formValues.put("immediateAckDelayDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.immediateAckDelay Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setImmediateAckDelayMax(String v) { _formValues.put("immediateAckDelayMax", v); }
    /**
     * Record the Min control for the i2p.streaming.immediateAckDelay Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setImmediateAckDelayMin(String v) { _formValues.put("immediateAckDelayMin", v); }
    /**
     * Record the Override control for the i2p.streaming.immediateAckDelay Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setImmediateAckDelayOverride(String v) { _formValues.put("immediateAckDelayOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.immediateAckDelay Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setImmediateAckDelayStep(String v) { _formValues.put("immediateAckDelayStep", v); }
    /**
     * Record the Default control for the INITIAL_ACK_DELAY Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setInitialAckDelayDefault(String v) { _formValues.put("initialAckDelayDefault", v); }
    /**
     * Record the Max control for the INITIAL_ACK_DELAY Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setInitialAckDelayMax(String v) { _formValues.put("initialAckDelayMax", v); }
    /**
     * Record the Min control for the INITIAL_ACK_DELAY Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setInitialAckDelayMin(String v) { _formValues.put("initialAckDelayMin", v); }
    /**
     * Record the Override control for the INITIAL_ACK_DELAY Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setInitialAckDelayOverride(String v) { _formValues.put("initialAckDelayOverride", v); }
    /**
     * Record the Step control for the INITIAL_ACK_DELAY Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setInitialAckDelayStep(String v) { _formValues.put("initialAckDelayStep", v); }
    /**
     * Record the Default control for the i2p.streaming.initialResendDelay Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setInitialResendDelayDefault(String v) { _formValues.put("initialResendDelayDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.initialResendDelay Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setInitialResendDelayMax(String v) { _formValues.put("initialResendDelayMax", v); }
    /**
     * Record the Min control for the i2p.streaming.initialResendDelay Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setInitialResendDelayMin(String v) { _formValues.put("initialResendDelayMin", v); }
    /**
     * Record the Override control for the i2p.streaming.initialResendDelay
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setInitialResendDelayOverride(String v) { _formValues.put("initialResendDelayOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.initialResendDelay Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setInitialResendDelayStep(String v) { _formValues.put("initialResendDelayStep", v); }
    /**
     * Record the Default control for the INITIAL_RTO Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setInitialRTODefault(String v) { _formValues.put("initialRTODefault", v); }
    /**
     * Record the Max control for the INITIAL_RTO Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setInitialRTOMax(String v) { _formValues.put("initialRTOMax", v); }
    /**
     * Record the Min control for the INITIAL_RTO Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setInitialRTOMin(String v) { _formValues.put("initialRTOMin", v); }
    /**
     * Record the Override control for the INITIAL_RTO Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setInitialRTOOverride(String v) { _formValues.put("initialRTOOverride", v); }
    /**
     * Record the Step control for the INITIAL_RTO Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setInitialRTOStep(String v) { _formValues.put("initialRTOStep", v); }
    /**
     * Record the Default control for the INITIAL_WINDOW_SIZE Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setInitialWindowSizeDefault(String v) { _formValues.put("initialWindowSizeDefault", v); }
    /**
     * Record the Max control for the INITIAL_WINDOW_SIZE Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setInitialWindowSizeMax(String v) { _formValues.put("initialWindowSizeMax", v); }
    /**
     * Record the Min control for the INITIAL_WINDOW_SIZE Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setInitialWindowSizeMin(String v) { _formValues.put("initialWindowSizeMin", v); }
    /**
     * Record the Override control for the INITIAL_WINDOW_SIZE Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setInitialWindowSizeOverride(String v) { _formValues.put("initialWindowSizeOverride", v); }
    /**
     * Record the Step control for the INITIAL_WINDOW_SIZE Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setInitialWindowSizeStep(String v) { _formValues.put("initialWindowSizeStep", v); }
    /**
     * Record the Default control for the i2np.udp.maxConcurrentEstablish Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxConcurrentEstablishDefault(String v) { _formValues.put("maxConcurrentEstablishDefault", v); }
    /**
     * Record the Max control for the i2np.udp.maxConcurrentEstablish Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxConcurrentEstablishMax(String v) { _formValues.put("maxConcurrentEstablishMax", v); }
    /**
     * Record the Min control for the i2np.udp.maxConcurrentEstablish Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxConcurrentEstablishMin(String v) { _formValues.put("maxConcurrentEstablishMin", v); }
    /**
     * Record the Override control for the i2np.udp.maxConcurrentEstablish Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxConcurrentEstablishOverride(String v) { _formValues.put("maxConcurrentEstablishOverride", v); }
    /**
     * Record the Step control for the i2np.udp.maxConcurrentEstablish Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxConcurrentEstablishStep(String v) { _formValues.put("maxConcurrentEstablishStep", v); }
    /**
     * Record the Default control for the profileOrganizer.maxFastPeers Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxFastPeersDefault(String v) { _formValues.put("maxFastPeersDefault", v); }
    /**
     * Record the Max control for the profileOrganizer.maxFastPeers Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxFastPeersMax(String v) { _formValues.put("maxFastPeersMax", v); }
    /**
     * Record the Min control for the profileOrganizer.maxFastPeers Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxFastPeersMin(String v) { _formValues.put("maxFastPeersMin", v); }
    /**
     * Record the Step control for the profileOrganizer.maxFastPeers Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxFastPeersStep(String v) { _formValues.put("maxFastPeersStep", v); }
    /**
     * Record the Default control for the profileOrganizer.maxHighCapacityPeers
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxHighCapPeersDefault(String v) { _formValues.put("maxHighCapPeersDefault", v); }
    /**
     * Record the Max control for the profileOrganizer.maxHighCapacityPeers
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxHighCapPeersMax(String v) { _formValues.put("maxHighCapPeersMax", v); }
    /**
     * Record the Min control for the profileOrganizer.maxHighCapacityPeers
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxHighCapPeersMin(String v) { _formValues.put("maxHighCapPeersMin", v); }
    /**
     * Record the Step control for the profileOrganizer.maxHighCapacityPeers
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxHighCapPeersStep(String v) { _formValues.put("maxHighCapPeersStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxInboundBuffer Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxInboundBufferDefault(String v) { _formValues.put("maxInboundBufferDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxInboundBuffer Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxInboundBufferMax(String v) { _formValues.put("maxInboundBufferMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxInboundBuffer Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxInboundBufferMin(String v) { _formValues.put("maxInboundBufferMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxInboundBuffer Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxInboundBufferOverride(String v) { _formValues.put("maxInboundBufferOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxInboundBuffer Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxInboundBufferStep(String v) { _formValues.put("maxInboundBufferStep", v); }
    /**
     * Record the Default control for the MAX_STREAMS Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxStreamsDefault(String v) { _formValues.put("maxStreamsDefault", v); }
    /**
     * Record the Max control for the MAX_STREAMS Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxStreamsMax(String v) { _formValues.put("maxStreamsMax", v); }
    /**
     * Record the Min control for the MAX_STREAMS Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxStreamsMin(String v) { _formValues.put("maxStreamsMin", v); }
    /**
     * Record the Override control for the MAX_STREAMS Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxStreamsOverride(String v) { _formValues.put("maxStreamsOverride", v); }
    /**
     * Record the Step control for the MAX_STREAMS Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxStreamsStep(String v) { _formValues.put("maxStreamsStep", v); }
    /**
     * Record the Default control for the router.maxParticipatingTunnels Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxParticipatingTunnelsDefault(String v) { _formValues.put("maxParticipatingTunnelsDefault", v); }
    /**
     * Record the Max control for the router.maxParticipatingTunnels Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxParticipatingTunnelsMax(String v) { _formValues.put("maxParticipatingTunnelsMax", v); }
    /**
     * Record the Min control for the router.maxParticipatingTunnels Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxParticipatingTunnelsMin(String v) { _formValues.put("maxParticipatingTunnelsMin", v); }
    /**
     * Record the Override control for the router.maxParticipatingTunnels Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxParticipatingTunnelsOverride(String v) { _formValues.put("maxParticipatingTunnelsOverride", v); }
    /**
     * Record the Step control for the router.maxParticipatingTunnels Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxParticipatingTunnelsStep(String v) { _formValues.put("maxParticipatingTunnelsStep", v); }
    /**
     * Record the Default control for the profileOrganizer.maxProfiles Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxProfilesDefault(String v) { _formValues.put("maxProfilesDefault", v); }
    /**
     * Record the Max control for the profileOrganizer.maxProfiles Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxProfilesMax(String v) { _formValues.put("maxProfilesMax", v); }
    /**
     * Record the Min control for the profileOrganizer.maxProfiles Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxProfilesMin(String v) { _formValues.put("maxProfilesMin", v); }
    /**
     * Record the Override control for the profileOrganizer.maxProfiles Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxProfilesOverride(String v) { _formValues.put("maxProfilesOverride", v); }
    /**
     * Record the Step control for the profileOrganizer.maxProfiles Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxProfilesStep(String v) { _formValues.put("maxProfilesStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxResendDelay Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxResendDelayDefault(String v) { _formValues.put("maxResendDelayDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxResendDelay Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxResendDelayMax(String v) { _formValues.put("maxResendDelayMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxResendDelay Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxResendDelayMin(String v) { _formValues.put("maxResendDelayMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxResendDelay Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxResendDelayOverride(String v) { _formValues.put("maxResendDelayOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxResendDelay Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxResendDelayStep(String v) { _formValues.put("maxResendDelayStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxRetransmissions Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxRetransmissionsDefault(String v) { _formValues.put("maxRetransmissionsDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxRetransmissions Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxRetransmissionsMax(String v) { _formValues.put("maxRetransmissionsMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxRetransmissions Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxRetransmissionsMin(String v) { _formValues.put("maxRetransmissionsMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxRetransmissions
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxRetransmissionsOverride(String v) { _formValues.put("maxRetransmissionsOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxRetransmissions Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxRetransmissionsStep(String v) { _formValues.put("maxRetransmissionsStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxRTO Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxRTODefault(String v) { _formValues.put("maxRTODefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxRTO Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxRTOMax(String v) { _formValues.put("maxRTOMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxRTO Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxRTOMin(String v) { _formValues.put("maxRTOMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxRTO Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxRTOOverride(String v) { _formValues.put("maxRTOOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxRTO Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxRTOStep(String v) { _formValues.put("maxRTOStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxRtt Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxRttDefault(String v) { _formValues.put("maxRttDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxRtt Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxRttMax(String v) { _formValues.put("maxRttMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxRtt Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxRttMin(String v) { _formValues.put("maxRttMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxRtt Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxRttOverride(String v) { _formValues.put("maxRttOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxRtt Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxRttStep(String v) { _formValues.put("maxRttStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxSlowStartWindow Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxSlowStartWindowDefault(String v) { _formValues.put("maxSlowStartWindowDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxSlowStartWindow Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxSlowStartWindowMax(String v) { _formValues.put("maxSlowStartWindowMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxSlowStartWindow Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxSlowStartWindowMin(String v) { _formValues.put("maxSlowStartWindowMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxSlowStartWindow
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxSlowStartWindowOverride(String v) { _formValues.put("maxSlowStartWindowOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxSlowStartWindow Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxSlowStartWindowStep(String v) { _formValues.put("maxSlowStartWindowStep", v); }
    /**
     * Record the Default control for the profileOrganizer.minFastPeers Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMinFastPeersDefault(String v) { _formValues.put("minFastPeersDefault", v); }
    /**
     * Record the Max control for the profileOrganizer.minFastPeers Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMinFastPeersMax(String v) { _formValues.put("minFastPeersMax", v); }
    /**
     * Record the Min control for the profileOrganizer.minFastPeers Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMinFastPeersMin(String v) { _formValues.put("minFastPeersMin", v); }
    /**
     * Record the Override control for the profileOrganizer.minFastPeers Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMinFastPeersOverride(String v) { _formValues.put("minFastPeersOverride", v); }
    /**
     * Record the Step control for the profileOrganizer.minFastPeers Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMinFastPeersStep(String v) { _formValues.put("minFastPeersStep", v); }
    /**
     * Record the Default control for the profileOrganizer.minHighCapacityPeers
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMinHighCapPeersDefault(String v) { _formValues.put("minHighCapPeersDefault", v); }
    /**
     * Record the Max control for the profileOrganizer.minHighCapacityPeers
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMinHighCapPeersMax(String v) { _formValues.put("minHighCapPeersMax", v); }
    /**
     * Record the Min control for the profileOrganizer.minHighCapacityPeers
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMinHighCapPeersMin(String v) { _formValues.put("minHighCapPeersMin", v); }
    /**
     * Record the Step control for the profileOrganizer.minHighCapacityPeers
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMinHighCapPeersStep(String v) { _formValues.put("minHighCapPeersStep", v); }
    /**
     * Record the Default control for the i2p.streaming.minResendDelay Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMinResendDelayDefault(String v) { _formValues.put("minResendDelayDefault", v); }
    /**
     * Record the Override control for the i2p.streaming.minResendDelay Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMinResendDelayOverride(String v) { _formValues.put("minResendDelayOverride", v); }
    /**
     * Record the Default control for the crypto.mlkem.precalcMin Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMlkemPrecalcMinDefault(String v) { _formValues.put("mlkemPrecalcMinDefault", v); }
    /**
     * Record the Max control for the crypto.mlkem.precalcMin Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMlkemPrecalcMinMax(String v) { _formValues.put("mlkemPrecalcMinMax", v); }
    /**
     * Record the Min control for the crypto.mlkem.precalcMin Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMlkemPrecalcMinMin(String v) { _formValues.put("mlkemPrecalcMinMin", v); }
    /**
     * Record the Override control for the crypto.mlkem.precalcMin Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMlkemPrecalcMinOverride(String v) { _formValues.put("mlkemPrecalcMinOverride", v); }
    /**
     * Record the Step control for the crypto.mlkem.precalcMin Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMlkemPrecalcMinStep(String v) { _formValues.put("mlkemPrecalcMinStep", v); }
    /**
     * Record the Default control for the netdb.maxConcurrent Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNetDBMaxConcurrentDefault(String v) { _formValues.put("netDBMaxConcurrentDefault", v); }
    /**
     * Record the Max control for the netdb.maxConcurrent Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNetDBMaxConcurrentMax(String v) { _formValues.put("netDBMaxConcurrentMax", v); }
    /**
     * Record the Min control for the netdb.maxConcurrent Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNetDBMaxConcurrentMin(String v) { _formValues.put("netDBMaxConcurrentMin", v); }
    /**
     * Record the Override control for the netdb.maxConcurrent Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNetDBMaxConcurrentOverride(String v) { _formValues.put("netDBMaxConcurrentOverride", v); }
    /**
     * Record the Step control for the netdb.maxConcurrent Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNetDBMaxConcurrentStep(String v) { _formValues.put("netDBMaxConcurrentStep", v); }
    /**
     * Record the Default control for the netdb.searchLimit Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNetDBSearchLimitDefault(String v) { _formValues.put("netDBSearchLimitDefault", v); }
    /**
     * Record the Max control for the netdb.searchLimit Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNetDBSearchLimitMax(String v) { _formValues.put("netDBSearchLimitMax", v); }
    /**
     * Record the Min control for the netdb.searchLimit Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNetDBSearchLimitMin(String v) { _formValues.put("netDBSearchLimitMin", v); }
    /**
     * Record the Override control for the netdb.searchLimit Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNetDBSearchLimitOverride(String v) { _formValues.put("netDBSearchLimitOverride", v); }
    /**
     * Record the Step control for the netdb.searchLimit Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNetDBSearchLimitStep(String v) { _formValues.put("netDBSearchLimitStep", v); }
    /**
     * Record the Default control for the netdb.singleSearchTime Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNetDBSingleSearchTimeDefault(String v) { _formValues.put("netDBSingleSearchTimeDefault", v); }
    /**
     * Record the Max control for the netdb.singleSearchTime Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNetDBSingleSearchTimeMax(String v) { _formValues.put("netDBSingleSearchTimeMax", v); }
    /**
     * Record the Min control for the netdb.singleSearchTime Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNetDBSingleSearchTimeMin(String v) { _formValues.put("netDBSingleSearchTimeMin", v); }
    /**
     * Record the Override control for the netdb.singleSearchTime Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNetDBSingleSearchTimeOverride(String v) { _formValues.put("netDBSingleSearchTimeOverride", v); }
    /**
     * Record the Step control for the netdb.singleSearchTime Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNetDBSingleSearchTimeStep(String v) { _formValues.put("netDBSingleSearchTimeStep", v); }
    /**
     * Record the Default control for the ntcp.sendFinisher.queueCapacity Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpQueueCapacityDefault(String v) { _formValues.put("ntcpQueueCapacityDefault", v); }
    /**
     * Record the Max control for the ntcp.sendFinisher.queueCapacity Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpQueueCapacityMax(String v) { _formValues.put("ntcpQueueCapacityMax", v); }
    /**
     * Record the Min control for the ntcp.sendFinisher.queueCapacity Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpQueueCapacityMin(String v) { _formValues.put("ntcpQueueCapacityMin", v); }
    /**
     * Record the Override control for the ntcp.sendFinisher.queueCapacity Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpQueueCapacityOverride(String v) { _formValues.put("ntcpQueueCapacityOverride", v); }
    /**
     * Record the Step control for the ntcp.sendFinisher.queueCapacity Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpQueueCapacityStep(String v) { _formValues.put("ntcpQueueCapacityStep", v); }
    /**
     * Record the Default control for the ntcp.sendFinisher.threads Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpThreadsDefault(String v) { _formValues.put("ntcpThreadsDefault", v); }
    /**
     * Record the Max control for the ntcp.sendFinisher.threads Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpThreadsMax(String v) { _formValues.put("ntcpThreadsMax", v); }
    /**
     * Record the Min control for the ntcp.sendFinisher.threads Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpThreadsMin(String v) { _formValues.put("ntcpThreadsMin", v); }
    /**
     * Record the Override control for the ntcp.sendFinisher.threads Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpThreadsOverride(String v) { _formValues.put("ntcpThreadsOverride", v); }
    /**
     * Record the Step control for the ntcp.sendFinisher.threads Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpThreadsStep(String v) { _formValues.put("ntcpThreadsStep", v); }
    /**
     * Record the Default control for the MAX_OB_ESTABLISH_TIME Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setObEstablishTimeDefault(String v) { _formValues.put("obEstablishTimeDefault", v); }
    /**
     * Record the Max control for the MAX_OB_ESTABLISH_TIME Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setObEstablishTimeMax(String v) { _formValues.put("obEstablishTimeMax", v); }
    /**
     * Record the Min control for the MAX_OB_ESTABLISH_TIME Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setObEstablishTimeMin(String v) { _formValues.put("obEstablishTimeMin", v); }
    /**
     * Record the Override control for the MAX_OB_ESTABLISH_TIME Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setObEstablishTimeOverride(String v) { _formValues.put("obEstablishTimeOverride", v); }
    /**
     * Record the Step control for the MAX_OB_ESTABLISH_TIME Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setObEstablishTimeStep(String v) { _formValues.put("obEstablishTimeStep", v); }
    /**
     * Record the Default control for the MAX_OB_MSGS_PER_PUMP Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setObMsgsPerPumpDefault(String v) { _formValues.put("obMsgsPerPumpDefault", v); }
    /**
     * Record the Max control for the MAX_OB_MSGS_PER_PUMP Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setObMsgsPerPumpMax(String v) { _formValues.put("obMsgsPerPumpMax", v); }
    /**
     * Record the Min control for the MAX_OB_MSGS_PER_PUMP Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setObMsgsPerPumpMin(String v) { _formValues.put("obMsgsPerPumpMin", v); }
    /**
     * Record the Override control for the MAX_OB_MSGS_PER_PUMP Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setObMsgsPerPumpOverride(String v) { _formValues.put("obMsgsPerPumpOverride", v); }
    /**
     * Record the Step control for the MAX_OB_MSGS_PER_PUMP Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setObMsgsPerPumpStep(String v) { _formValues.put("obMsgsPerPumpStep", v); }
    /**
     * Record the Default control for the PASSIVE_FLUSH_DELAY Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setPassiveFlushDelayDefault(String v) { _formValues.put("passiveFlushDelayDefault", v); }
    /**
     * Record the Max control for the PASSIVE_FLUSH_DELAY Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setPassiveFlushDelayMax(String v) { _formValues.put("passiveFlushDelayMax", v); }
    /**
     * Record the Min control for the PASSIVE_FLUSH_DELAY Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setPassiveFlushDelayMin(String v) { _formValues.put("passiveFlushDelayMin", v); }
    /**
     * Record the Override control for the PASSIVE_FLUSH_DELAY Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setPassiveFlushDelayOverride(String v) { _formValues.put("passiveFlushDelayOverride", v); }
    /**
     * Record the Step control for the PASSIVE_FLUSH_DELAY Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setPassiveFlushDelayStep(String v) { _formValues.put("passiveFlushDelayStep", v); }
    /**
     * Record the Default control for the router.peerOutboundQueueSize Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setPeerOutboundQueueDefault(String v) { _formValues.put("peerOutboundQueueDefault", v); }
    /**
     * Record the Max control for the router.peerOutboundQueueSize Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setPeerOutboundQueueMax(String v) { _formValues.put("peerOutboundQueueMax", v); }
    /**
     * Record the Min control for the router.peerOutboundQueueSize Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setPeerOutboundQueueMin(String v) { _formValues.put("peerOutboundQueueMin", v); }
    /**
     * Record the Override control for the router.peerOutboundQueueSize Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setPeerOutboundQueueOverride(String v) { _formValues.put("peerOutboundQueueOverride", v); }
    /**
     * Record the Step control for the router.peerOutboundQueueSize Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setPeerOutboundQueueStep(String v) { _formValues.put("peerOutboundQueueStep", v); }
    /**
     * Record the Default control for the router.tunnel.perTunnelBweDivisor
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setPerTunnelBweDivisorDefault(String v) { _formValues.put("perTunnelBweDivisorDefault", v); }
    /**
     * Record the Max control for the router.tunnel.perTunnelBweDivisor Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setPerTunnelBweDivisorMax(String v) { _formValues.put("perTunnelBweDivisorMax", v); }
    /**
     * Record the Min control for the router.tunnel.perTunnelBweDivisor Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setPerTunnelBweDivisorMin(String v) { _formValues.put("perTunnelBweDivisorMin", v); }
    /**
     * Record the Override control for the router.tunnel.perTunnelBweDivisor
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setPerTunnelBweDivisorOverride(String v) { _formValues.put("perTunnelBweDivisorOverride", v); }
    /**
     * Record the Step control for the router.tunnel.perTunnelBweDivisor Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setPerTunnelBweDivisorStep(String v) { _formValues.put("perTunnelBweDivisorStep", v); }
    /**
     * Record the Default control for the REPLENISH_FREQUENCY Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setReplenishFrequencyDefault(String v) { _formValues.put("replenishFrequencyDefault", v); }
    /**
     * Record the Max control for the REPLENISH_FREQUENCY Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setReplenishFrequencyMax(String v) { _formValues.put("replenishFrequencyMax", v); }
    /**
     * Record the Min control for the REPLENISH_FREQUENCY Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setReplenishFrequencyMin(String v) { _formValues.put("replenishFrequencyMin", v); }
    /**
     * Record the Override control for the REPLENISH_FREQUENCY Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setReplenishFrequencyOverride(String v) { _formValues.put("replenishFrequencyOverride", v); }
    /**
     * Record the Step control for the REPLENISH_FREQUENCY Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setReplenishFrequencyStep(String v) { _formValues.put("replenishFrequencyStep", v); }
    /**
     * Record the Default control for the REQUEUE_TIME Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRequeueTimeDefault(String v) { _formValues.put("requeueTimeDefault", v); }
    /**
     * Record the Max control for the REQUEUE_TIME Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRequeueTimeMax(String v) { _formValues.put("requeueTimeMax", v); }
    /**
     * Record the Min control for the REQUEUE_TIME Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRequeueTimeMin(String v) { _formValues.put("requeueTimeMin", v); }
    /**
     * Record the Override control for the REQUEUE_TIME Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRequeueTimeOverride(String v) { _formValues.put("requeueTimeOverride", v); }
    /**
     * Record the Step control for the REQUEUE_TIME Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRequeueTimeStep(String v) { _formValues.put("requeueTimeStep", v); }
    /**
     * Record the Default control for the SELECTOR_LOOP_DELAY Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setSelectorLoopDelayDefault(String v) { _formValues.put("selectorLoopDelayDefault", v); }
    /**
     * Record the Max control for the SELECTOR_LOOP_DELAY Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setSelectorLoopDelayMax(String v) { _formValues.put("selectorLoopDelayMax", v); }
    /**
     * Record the Min control for the SELECTOR_LOOP_DELAY Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setSelectorLoopDelayMin(String v) { _formValues.put("selectorLoopDelayMin", v); }
    /**
     * Record the Override control for the SELECTOR_LOOP_DELAY Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setSelectorLoopDelayOverride(String v) { _formValues.put("selectorLoopDelayOverride", v); }
    /**
     * Record the Step control for the SELECTOR_LOOP_DELAY Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setSelectorLoopDelayStep(String v) { _formValues.put("selectorLoopDelayStep", v); }
    /**
     * Record the Default control for the
     * i2p.streaming.slowStartGrowthRateFactor Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setSlowStartGrowthDefault(String v) { _formValues.put("slowStartGrowthDefault", v); }
    /**
     * Record the Override control for the
     * i2p.streaming.slowStartGrowthRateFactor Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setSlowStartGrowthOverride(String v) { _formValues.put("slowStartGrowthOverride", v); }
    /**
     * Record the Default control for the i2ptunnel.serverHandler.threads Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setThreadsDefault(String v) { _formValues.put("threadsDefault", v); }
    /**
     * Record the Max control for the i2ptunnel.serverHandler.threads Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setThreadsMax(String v) { _formValues.put("threadsMax", v); }
    /**
     * Record the Min control for the i2ptunnel.serverHandler.threads Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setThreadsMin(String v) { _formValues.put("threadsMin", v); }
    /**
     * Record the Override control for the i2ptunnel.serverHandler.threads Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setThreadsOverride(String v) { _formValues.put("threadsOverride", v); }
    /**
     * Record the Step control for the i2ptunnel.serverHandler.threads Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setThreadsStep(String v) { _formValues.put("threadsStep", v); }
    /**
     * Record the Default control for the i2ptunnel.serverHandler.queueCapacity
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setServerBacklogQueueDefault(String v) { _formValues.put("serverBacklogQueueDefault", v); }
    /**
     * Record the Max control for the i2ptunnel.serverHandler.queueCapacity
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setServerBacklogQueueMax(String v) { _formValues.put("serverBacklogQueueMax", v); }
    /**
     * Record the Min control for the i2ptunnel.serverHandler.queueCapacity
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setServerBacklogQueueMin(String v) { _formValues.put("serverBacklogQueueMin", v); }
    /**
     * Record the Override control for the i2ptunnel.serverHandler.queueCapacity
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setServerBacklogQueueOverride(String v) { _formValues.put("serverBacklogQueueOverride", v); }
    /**
     * Record the Step control for the i2ptunnel.serverHandler.queueCapacity
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setServerBacklogQueueStep(String v) { _formValues.put("serverBacklogQueueStep", v); }
    /**
     * Record the Default control for the i2ptunnel.server.threads Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setServerThreadsDefault(String v) { _formValues.put("serverThreadsDefault", v); }
    /**
     * Record the Max control for the i2ptunnel.server.threads Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setServerThreadsMax(String v) { _formValues.put("serverThreadsMax", v); }
    /**
     * Record the Min control for the i2ptunnel.server.threads Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setServerThreadsMin(String v) { _formValues.put("serverThreadsMin", v); }
    /**
     * Record the Override control for the i2ptunnel.server.threads Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setServerThreadsOverride(String v) { _formValues.put("serverThreadsOverride", v); }
    /**
     * Record the Step control for the i2ptunnel.server.threads Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setServerThreadsStep(String v) { _formValues.put("serverThreadsStep", v); }
    /**
     * Record the Default control for the router.throttleRejectExponent Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setThrottleRejectExponentDefault(String v) { _formValues.put("throttleRejectExponentDefault", v); }
    /**
     * Record the Max control for the router.throttleRejectExponent Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setThrottleRejectExponentMax(String v) { _formValues.put("throttleRejectExponentMax", v); }
    /**
     * Record the Min control for the router.throttleRejectExponent Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setThrottleRejectExponentMin(String v) { _formValues.put("throttleRejectExponentMin", v); }
    /**
     * Record the Override control for the router.throttleRejectExponent Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setThrottleRejectExponentOverride(String v) { _formValues.put("throttleRejectExponentOverride", v); }
    /**
     * Record the Step control for the router.throttleRejectExponent Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setThrottleRejectExponentStep(String v) { _formValues.put("throttleRejectExponentStep", v); }
    /**
     * Record the Default control for the router.transitThrottleFactor Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTransitThrottleFactorDefault(String v) { _formValues.put("transitThrottleFactorDefault", v); }
    /**
     * Record the Max control for the router.transitThrottleFactor Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTransitThrottleFactorMax(String v) { _formValues.put("transitThrottleFactorMax", v); }
    /**
     * Record the Min control for the router.transitThrottleFactor Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTransitThrottleFactorMin(String v) { _formValues.put("transitThrottleFactorMin", v); }
    /**
     * Record the Override control for the router.transitThrottleFactor Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTransitThrottleFactorOverride(String v) { _formValues.put("transitThrottleFactorOverride", v); }
    /**
     * Record the Step control for the router.transitThrottleFactor Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTransitThrottleFactorStep(String v) { _formValues.put("transitThrottleFactorStep", v); }
    /**
     * Record the Default control for the router.tunnelGrowthFactor Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelGrowthFactorDefault(String v) { _formValues.put("tunnelGrowthFactorDefault", v); }
    /**
     * Record the Max control for the router.tunnelGrowthFactor Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelGrowthFactorMax(String v) { _formValues.put("tunnelGrowthFactorMax", v); }
    /**
     * Record the Min control for the router.tunnelGrowthFactor Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelGrowthFactorMin(String v) { _formValues.put("tunnelGrowthFactorMin", v); }
    /**
     * Record the Override control for the router.tunnelGrowthFactor Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelGrowthFactorOverride(String v) { _formValues.put("tunnelGrowthFactorOverride", v); }
    /**
     * Record the Step control for the router.tunnelGrowthFactor Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelGrowthFactorStep(String v) { _formValues.put("tunnelGrowthFactorStep", v); }
    /**
     * Record the Default control for the udp.packetHandler.maxThreads Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpHandlerThreadsDefault(String v) { _formValues.put("udpHandlerThreadsDefault", v); }
    /**
     * Record the Max control for the udp.packetHandler.maxThreads Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpHandlerThreadsMax(String v) { _formValues.put("udpHandlerThreadsMax", v); }
    /**
     * Record the Min control for the udp.packetHandler.maxThreads Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpHandlerThreadsMin(String v) { _formValues.put("udpHandlerThreadsMin", v); }
    /**
     * Record the Override control for the udp.packetHandler.maxThreads Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpHandlerThreadsOverride(String v) { _formValues.put("udpHandlerThreadsOverride", v); }
    /**
     * Record the Step control for the udp.packetHandler.maxThreads Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpHandlerThreadsStep(String v) { _formValues.put("udpHandlerThreadsStep", v); }
    /**
     * Record the Default control for the WESTWOOD_DECAY_FACTOR Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setWestwoodDecayFactorDefault(String v) { _formValues.put("westwoodDecayFactorDefault", v); }
    /**
     * Record the Max control for the WESTWOOD_DECAY_FACTOR Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setWestwoodDecayFactorMax(String v) { _formValues.put("westwoodDecayFactorMax", v); }
    /**
     * Record the Min control for the WESTWOOD_DECAY_FACTOR Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setWestwoodDecayFactorMin(String v) { _formValues.put("westwoodDecayFactorMin", v); }
    /**
     * Record the Override control for the WESTWOOD_DECAY_FACTOR Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setWestwoodDecayFactorOverride(String v) { _formValues.put("westwoodDecayFactorOverride", v); }
    /**
     * Record the Step control for the WESTWOOD_DECAY_FACTOR Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setWestwoodDecayFactorStep(String v) { _formValues.put("westwoodDecayFactorStep", v); }
    /**
     * Record the Default control for the CLIENT_WRITER_QUEUE_SIZE Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setWriterQueueSizeDefault(String v) { _formValues.put("writerQueueSizeDefault", v); }
    /**
     * Record the Max control for the CLIENT_WRITER_QUEUE_SIZE Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setWriterQueueSizeMax(String v) { _formValues.put("writerQueueSizeMax", v); }
    /**
     * Record the Min control for the CLIENT_WRITER_QUEUE_SIZE Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setWriterQueueSizeMin(String v) { _formValues.put("writerQueueSizeMin", v); }
    /**
     * Record the Override control for the CLIENT_WRITER_QUEUE_SIZE Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setWriterQueueSizeOverride(String v) { _formValues.put("writerQueueSizeOverride", v); }
    /**
     * Record the Step control for the CLIENT_WRITER_QUEUE_SIZE Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setWriterQueueSizeStep(String v) { _formValues.put("writerQueueSizeStep", v); }
    /**
     * Record the Default control for the crypto.x25519.precalcMin Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setXdhPreCalcMinDefault(String v) { _formValues.put("xdhPreCalcMinDefault", v); }
    /**
     * Record the Max control for the crypto.x25519.precalcMin Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setXdhPreCalcMinMax(String v) { _formValues.put("xdhPreCalcMinMax", v); }
    /**
     * Record the Min control for the crypto.x25519.precalcMin Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setXdhPreCalcMinMin(String v) { _formValues.put("xdhPreCalcMinMin", v); }
    /**
     * Record the Override control for the crypto.x25519.precalcMin Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setXdhPreCalcMinOverride(String v) { _formValues.put("xdhPreCalcMinOverride", v); }
    /**
     * Record the Step control for the crypto.x25519.precalcMin Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setXdhPreCalcMinStep(String v) { _formValues.put("xdhPreCalcMinStep", v); }

    // Full-coverage params (all Tuner params exposed)
    /**
     * Record the Default control for the CONNECT_TIMEOUT_MULTIPLIER Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setConnectTimeoutMultiplierDefault(String v) { _formValues.put("connectTimeoutMultiplierDefault", v); }
    /**
     * Record the Max control for the CONNECT_TIMEOUT_MULTIPLIER Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setConnectTimeoutMultiplierMax(String v) { _formValues.put("connectTimeoutMultiplierMax", v); }
    /**
     * Record the Min control for the CONNECT_TIMEOUT_MULTIPLIER Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setConnectTimeoutMultiplierMin(String v) { _formValues.put("connectTimeoutMultiplierMin", v); }
    /**
     * Record the Override control for the CONNECT_TIMEOUT_MULTIPLIER Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setConnectTimeoutMultiplierOverride(String v) { _formValues.put("connectTimeoutMultiplierOverride", v); }
    /**
     * Record the Step control for the CONNECT_TIMEOUT_MULTIPLIER Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setConnectTimeoutMultiplierStep(String v) { _formValues.put("connectTimeoutMultiplierStep", v); }
    /**
     * Record the Default control for the i2cp.internalQueueSize Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2cpInternalqueuesizeDefault(String v) { _formValues.put("i2cpInternalqueuesizeDefault", v); }
    /**
     * Record the Max control for the i2cp.internalQueueSize Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2cpInternalqueuesizeMax(String v) { _formValues.put("i2cpInternalqueuesizeMax", v); }
    /**
     * Record the Min control for the i2cp.internalQueueSize Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2cpInternalqueuesizeMin(String v) { _formValues.put("i2cpInternalqueuesizeMin", v); }
    /**
     * Record the Override control for the i2cp.internalQueueSize Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2cpInternalqueuesizeOverride(String v) { _formValues.put("i2cpInternalqueuesizeOverride", v); }
    /**
     * Record the Step control for the i2cp.internalQueueSize Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2cpInternalqueuesizeStep(String v) { _formValues.put("i2cpInternalqueuesizeStep", v); }
    /**
     * Record the Default control for the i2p.router.handlerThreadPriority Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pRouterHandlerthreadpriorityDefault(String v) { _formValues.put("i2pRouterHandlerthreadpriorityDefault", v); }
    /**
     * Record the Max control for the i2p.router.handlerThreadPriority Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pRouterHandlerthreadpriorityMax(String v) { _formValues.put("i2pRouterHandlerthreadpriorityMax", v); }
    /**
     * Record the Min control for the i2p.router.handlerThreadPriority Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pRouterHandlerthreadpriorityMin(String v) { _formValues.put("i2pRouterHandlerthreadpriorityMin", v); }
    /**
     * Record the Override control for the i2p.router.handlerThreadPriority
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pRouterHandlerthreadpriorityOverride(String v) { _formValues.put("i2pRouterHandlerthreadpriorityOverride", v); }
    /**
     * Record the Step control for the i2p.router.handlerThreadPriority Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pRouterHandlerthreadpriorityStep(String v) { _formValues.put("i2pRouterHandlerthreadpriorityStep", v); }
    /**
     * Record the Default control for the i2p.router.maxDispatchAge Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pRouterMaxdispatchageDefault(String v) { _formValues.put("i2pRouterMaxdispatchageDefault", v); }
    /**
     * Record the Max control for the i2p.router.maxDispatchAge Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pRouterMaxdispatchageMax(String v) { _formValues.put("i2pRouterMaxdispatchageMax", v); }
    /**
     * Record the Min control for the i2p.router.maxDispatchAge Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pRouterMaxdispatchageMin(String v) { _formValues.put("i2pRouterMaxdispatchageMin", v); }
    /**
     * Record the Override control for the i2p.router.maxDispatchAge Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pRouterMaxdispatchageOverride(String v) { _formValues.put("i2pRouterMaxdispatchageOverride", v); }
    /**
     * Record the Step control for the i2p.router.maxDispatchAge Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pRouterMaxdispatchageStep(String v) { _formValues.put("i2pRouterMaxdispatchageStep", v); }
    /**
     * Record the Default control for the i2p.streaming.inactivityTimeout Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pStreamingInactivitytimeoutDefault(String v) { _formValues.put("i2pStreamingInactivitytimeoutDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.inactivityTimeout Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pStreamingInactivitytimeoutMax(String v) { _formValues.put("i2pStreamingInactivitytimeoutMax", v); }
    /**
     * Record the Min control for the i2p.streaming.inactivityTimeout Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pStreamingInactivitytimeoutMin(String v) { _formValues.put("i2pStreamingInactivitytimeoutMin", v); }
    /**
     * Record the Override control for the i2p.streaming.inactivityTimeout Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pStreamingInactivitytimeoutOverride(String v) { _formValues.put("i2pStreamingInactivitytimeoutOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.inactivityTimeout Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pStreamingInactivitytimeoutStep(String v) { _formValues.put("i2pStreamingInactivitytimeoutStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxSynResends Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pStreamingMaxsynresendsDefault(String v) { _formValues.put("i2pStreamingMaxsynresendsDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxSynResends Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pStreamingMaxsynresendsMax(String v) { _formValues.put("i2pStreamingMaxsynresendsMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxSynResends Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pStreamingMaxsynresendsMin(String v) { _formValues.put("i2pStreamingMaxsynresendsMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxSynResends Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pStreamingMaxsynresendsOverride(String v) { _formValues.put("i2pStreamingMaxsynresendsOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxSynResends Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pStreamingMaxsynresendsStep(String v) { _formValues.put("i2pStreamingMaxsynresendsStep", v); }
    /**
     * Record the Default control for the i2p.streaming.maxWindowSize Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pStreamingMaxwindowsizeDefault(String v) { _formValues.put("i2pStreamingMaxwindowsizeDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.maxWindowSize Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pStreamingMaxwindowsizeMax(String v) { _formValues.put("i2pStreamingMaxwindowsizeMax", v); }
    /**
     * Record the Min control for the i2p.streaming.maxWindowSize Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pStreamingMaxwindowsizeMin(String v) { _formValues.put("i2pStreamingMaxwindowsizeMin", v); }
    /**
     * Record the Override control for the i2p.streaming.maxWindowSize Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pStreamingMaxwindowsizeOverride(String v) { _formValues.put("i2pStreamingMaxwindowsizeOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.maxWindowSize Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pStreamingMaxwindowsizeStep(String v) { _formValues.put("i2pStreamingMaxwindowsizeStep", v); }
    /**
     * Record the Default control for the i2p.streaming.minPacingRate Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pStreamingMinpacingrateDefault(String v) { _formValues.put("i2pStreamingMinpacingrateDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.minPacingRate Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pStreamingMinpacingrateMax(String v) { _formValues.put("i2pStreamingMinpacingrateMax", v); }
    /**
     * Record the Min control for the i2p.streaming.minPacingRate Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pStreamingMinpacingrateMin(String v) { _formValues.put("i2pStreamingMinpacingrateMin", v); }
    /**
     * Record the Override control for the i2p.streaming.minPacingRate Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pStreamingMinpacingrateOverride(String v) { _formValues.put("i2pStreamingMinpacingrateOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.minPacingRate Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pStreamingMinpacingrateStep(String v) { _formValues.put("i2pStreamingMinpacingrateStep", v); }
    /**
     * Record the Default control for the i2p.streaming.rtoMultiplier Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pStreamingRtomultiplierDefault(String v) { _formValues.put("i2pStreamingRtomultiplierDefault", v); }
    /**
     * Record the Max control for the i2p.streaming.rtoMultiplier Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pStreamingRtomultiplierMax(String v) { _formValues.put("i2pStreamingRtomultiplierMax", v); }
    /**
     * Record the Min control for the i2p.streaming.rtoMultiplier Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pStreamingRtomultiplierMin(String v) { _formValues.put("i2pStreamingRtomultiplierMin", v); }
    /**
     * Record the Override control for the i2p.streaming.rtoMultiplier Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pStreamingRtomultiplierOverride(String v) { _formValues.put("i2pStreamingRtomultiplierOverride", v); }
    /**
     * Record the Step control for the i2p.streaming.rtoMultiplier Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pStreamingRtomultiplierStep(String v) { _formValues.put("i2pStreamingRtomultiplierStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.build.maxLookupLimit Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelBuildMaxlookuplimitDefault(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.build.maxLookupLimit Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelBuildMaxlookuplimitMax(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.build.maxLookupLimit Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelBuildMaxlookuplimitMin(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.build.maxLookupLimit Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelBuildMaxlookuplimitOverride(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.build.maxLookupLimit Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelBuildMaxlookuplimitStep(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.build.percentLookupLimit
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelBuildPercentlookuplimitDefault(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.build.percentLookupLimit Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelBuildPercentlookuplimitMax(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.build.percentLookupLimit Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelBuildPercentlookuplimitMin(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.build.percentLookupLimit
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelBuildPercentlookuplimitOverride(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.build.percentLookupLimit Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelBuildPercentlookuplimitStep(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.participatingThrottle.loadWeight Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleLoadweightDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.participatingThrottle.loadWeight Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleLoadweightMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.participatingThrottle.loadWeight Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleLoadweightMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.participatingThrottle.loadWeight Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelParticipatingthrottleLoadweightOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.participatingThrottle.loadWeight Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleLoadweightStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.participatingThrottle.maxLimit Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMaxlimitDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.participatingThrottle.maxLimit
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMaxlimitMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.participatingThrottle.maxLimit
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMaxlimitMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.participatingThrottle.maxLimit Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelParticipatingthrottleMaxlimitOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.participatingThrottle.maxLimit
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMaxlimitStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.participatingThrottle.minLimit Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMinlimitDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.participatingThrottle.minLimit
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMinlimitMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.participatingThrottle.minLimit
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMinlimitMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.participatingThrottle.minLimit Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelParticipatingthrottleMinlimitOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.participatingThrottle.minLimit
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleMinlimitStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.participatingThrottle.percentLimit Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottlePercentlimitDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.participatingThrottle.percentLimit Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottlePercentlimitMax(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.participatingThrottle.percentLimit Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottlePercentlimitMin(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.participatingThrottle.percentLimit Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelParticipatingthrottlePercentlimitOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.participatingThrottle.percentLimit Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottlePercentlimitStep(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.participatingThrottle.rejectSteepness Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectsteepnessDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.participatingThrottle.rejectSteepness Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectsteepnessMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.participatingThrottle.rejectSteepness Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectsteepnessMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.participatingThrottle.rejectSteepness Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelParticipatingthrottleRejectsteepnessOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.participatingThrottle.rejectSteepness Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectsteepnessStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.participatingThrottle.rejectThreshold Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectthresholdDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.participatingThrottle.rejectThreshold Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectthresholdMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.participatingThrottle.rejectThreshold Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectthresholdMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.participatingThrottle.rejectThreshold Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelParticipatingthrottleRejectthresholdOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.participatingThrottle.rejectThreshold Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelParticipatingthrottleRejectthresholdStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.burst1sThreshold Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleBurst1sthresholdDefault(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.requestThrottle.burst1sThreshold Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleBurst1sthresholdMax(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.requestThrottle.burst1sThreshold Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleBurst1sthresholdMin(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.burst1sThreshold Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleBurst1sthresholdOverride(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.requestThrottle.burst1sThreshold Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleBurst1sthresholdStep(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.highLoadCpuPct Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadcpupctDefault(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.highLoadCpuPct
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadcpupctMax(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.highLoadCpuPct
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadcpupctMin(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.highLoadCpuPct Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleHighloadcpupctOverride(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.requestThrottle.highLoadCpuPct
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadcpupctStep(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.highLoadLagMs Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadlagmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.highLoadLagMs
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadlagmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.highLoadLagMs
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadlagmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.highLoadLagMs Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleHighloadlagmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.requestThrottle.highLoadLagMs
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleHighloadlagmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.requestThrottle.loadWeight
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleLoadweightDefault(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.loadWeight
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleLoadweightMax(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.loadWeight
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleLoadweightMin(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.requestThrottle.loadWeight
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleLoadweightOverride(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.requestThrottle.loadWeight
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleLoadweightStep(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.requestThrottle.maxLimit
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMaxlimitDefault(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.maxLimit Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMaxlimitMax(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.maxLimit Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMaxlimitMin(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.requestThrottle.maxLimit
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleMaxlimitOverride(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.requestThrottle.maxLimit Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMaxlimitStep(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.requestThrottle.minLimit
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMinlimitDefault(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.minLimit Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMinlimitMax(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.minLimit Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMinlimitMin(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.requestThrottle.minLimit
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleMinlimitOverride(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.requestThrottle.minLimit Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleMinlimitStep(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.moderateLoadCpuPct Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadcpupctDefault(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.requestThrottle.moderateLoadCpuPct Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadcpupctMax(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.requestThrottle.moderateLoadCpuPct Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadcpupctMin(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.moderateLoadCpuPct Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleModerateloadcpupctOverride(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.requestThrottle.moderateLoadCpuPct Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadcpupctStep(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.moderateLoadLagMs Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadlagmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.requestThrottle.moderateLoadLagMs Tuner param: the ceiling the
     * auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadlagmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.requestThrottle.moderateLoadLagMs Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadlagmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.moderateLoadLagMs Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleModerateloadlagmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.requestThrottle.moderateLoadLagMs Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleModerateloadlagmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.percentLimit Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottlePercentlimitDefault(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.percentLimit
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottlePercentlimitMax(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.percentLimit
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottlePercentlimitMin(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.percentLimit Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottlePercentlimitOverride(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.requestThrottle.percentLimit
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottlePercentlimitStep(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.rejectSteepness Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectsteepnessDefault(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.rejectSteepness
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectsteepnessMax(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.rejectSteepness
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectsteepnessMin(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.rejectSteepness Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleRejectsteepnessOverride(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.requestThrottle.rejectSteepness Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectsteepnessStep(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.rejectThreshold Tuner param: the default the
     * auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectthresholdDefault(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.requestThrottle.rejectThreshold
     * Tuner param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectthresholdMax(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.requestThrottle.rejectThreshold
     * Tuner param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectthresholdMin(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.rejectThreshold Tuner param: negative resumes
     * auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleRejectthresholdOverride(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.requestThrottle.rejectThreshold Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleRejectthresholdStep(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdStep", v); }
    /**
     * Record the Default control submitted under the
     * i2pTunnelRequestthrottleSustainedhighloadms form prefix. No Tuner param
     * carries that prefix, so TUNED holds no entry for it and processForm()
     * never consumes the value.
     * @param v raw Default input text, never read
     */
    public void setI2pTunnelRequestthrottleSustainedhighloadmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsDefault", v); }
    /**
     * Record the Max control submitted under the
     * i2pTunnelRequestthrottleSustainedhighloadms form prefix. No Tuner param
     * carries that prefix, so TUNED holds no entry for it and processForm()
     * never consumes the value.
     * @param v raw Max input text, never read
     */
    public void setI2pTunnelRequestthrottleSustainedhighloadmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsMax", v); }
    /**
     * Record the Min control submitted under the
     * i2pTunnelRequestthrottleSustainedhighloadms form prefix. No Tuner param
     * carries that prefix, so TUNED holds no entry for it and processForm()
     * never consumes the value.
     * @param v raw Min input text, never read
     */
    public void setI2pTunnelRequestthrottleSustainedhighloadmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsMin", v); }
    /**
     * Record the Override control submitted under the
     * i2pTunnelRequestthrottleSustainedhighloadms form prefix. No Tuner param
     * carries that prefix, so TUNED holds no entry for it and processForm()
     * never consumes the value.
     * @param v raw Override input text, never read
     */
    public void setI2pTunnelRequestthrottleSustainedhighloadmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsOverride", v); }
    /**
     * Record the Step control submitted under the
     * i2pTunnelRequestthrottleSustainedhighloadms form prefix. No Tuner param
     * carries that prefix, so TUNED holds no entry for it and processForm()
     * never consumes the value.
     * @param v raw Step input text, never read
     */
    public void setI2pTunnelRequestthrottleSustainedhighloadmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsStep", v); }
    /**
     * Record the Default control for the
     * i2p.tunnel.requestThrottle.sustainedModerateLoadMs Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsDefault", v); }
    /**
     * Record the Max control for the
     * i2p.tunnel.requestThrottle.sustainedModerateLoadMs Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsMax", v); }
    /**
     * Record the Min control for the
     * i2p.tunnel.requestThrottle.sustainedModerateLoadMs Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsMin", v); }
    /**
     * Record the Override control for the
     * i2p.tunnel.requestThrottle.sustainedModerateLoadMs Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsOverride", v); }
    /**
     * Record the Step control for the
     * i2p.tunnel.requestThrottle.sustainedModerateLoadMs Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.socketConnectTimeout Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelSocketconnecttimeoutDefault(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.socketConnectTimeout Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelSocketconnecttimeoutMax(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.socketConnectTimeout Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelSocketconnecttimeoutMin(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.socketConnectTimeout Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelSocketconnecttimeoutOverride(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.socketConnectTimeout Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelSocketconnecttimeoutStep(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.targetBuffer Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelTargetbufferDefault(String v) { _formValues.put("i2pTunnelTargetbufferDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.targetBuffer Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelTargetbufferMax(String v) { _formValues.put("i2pTunnelTargetbufferMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.targetBuffer Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelTargetbufferMin(String v) { _formValues.put("i2pTunnelTargetbufferMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.targetBuffer Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelTargetbufferOverride(String v) { _formValues.put("i2pTunnelTargetbufferOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.targetBuffer Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelTargetbufferStep(String v) { _formValues.put("i2pTunnelTargetbufferStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.untestedMultiplier Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2pTunnelUntestedmultiplierDefault(String v) { _formValues.put("i2pTunnelUntestedmultiplierDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.untestedMultiplier Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2pTunnelUntestedmultiplierMax(String v) { _formValues.put("i2pTunnelUntestedmultiplierMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.untestedMultiplier Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2pTunnelUntestedmultiplierMin(String v) { _formValues.put("i2pTunnelUntestedmultiplierMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.untestedMultiplier Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2pTunnelUntestedmultiplierOverride(String v) { _formValues.put("i2pTunnelUntestedmultiplierOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.untestedMultiplier Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2pTunnelUntestedmultiplierStep(String v) { _formValues.put("i2pTunnelUntestedmultiplierStep", v); }
    /**
     * Record the Default control for the i2ptunnel.clientRunner.max Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setI2ptunnelClientrunnerMaxDefault(String v) { _formValues.put("i2ptunnelClientrunnerMaxDefault", v); }
    /**
     * Record the Max control for the i2ptunnel.clientRunner.max Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setI2ptunnelClientrunnerMaxMax(String v) { _formValues.put("i2ptunnelClientrunnerMaxMax", v); }
    /**
     * Record the Min control for the i2ptunnel.clientRunner.max Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setI2ptunnelClientrunnerMaxMin(String v) { _formValues.put("i2ptunnelClientrunnerMaxMin", v); }
    /**
     * Record the Override control for the i2ptunnel.clientRunner.max Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setI2ptunnelClientrunnerMaxOverride(String v) { _formValues.put("i2ptunnelClientrunnerMaxOverride", v); }
    /**
     * Record the Step control for the i2ptunnel.clientRunner.max Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setI2ptunnelClientrunnerMaxStep(String v) { _formValues.put("i2ptunnelClientrunnerMaxStep", v); }
    /**
     * Record the Default control for the MAX_LS_LOOKUP_TIME Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxLsLookupTimeDefault(String v) { _formValues.put("maxLsLookupTimeDefault", v); }
    /**
     * Record the Max control for the MAX_LS_LOOKUP_TIME Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxLsLookupTimeMax(String v) { _formValues.put("maxLsLookupTimeMax", v); }
    /**
     * Record the Min control for the MAX_LS_LOOKUP_TIME Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxLsLookupTimeMin(String v) { _formValues.put("maxLsLookupTimeMin", v); }
    /**
     * Record the Override control for the MAX_LS_LOOKUP_TIME Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxLsLookupTimeOverride(String v) { _formValues.put("maxLsLookupTimeOverride", v); }
    /**
     * Record the Step control for the MAX_LS_LOOKUP_TIME Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxLsLookupTimeStep(String v) { _formValues.put("maxLsLookupTimeStep", v); }
    /**
     * Record the Default control for the MAX_RI_LOOKUP_TIME Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setMaxRiLookupTimeDefault(String v) { _formValues.put("maxRiLookupTimeDefault", v); }
    /**
     * Record the Max control for the MAX_RI_LOOKUP_TIME Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setMaxRiLookupTimeMax(String v) { _formValues.put("maxRiLookupTimeMax", v); }
    /**
     * Record the Min control for the MAX_RI_LOOKUP_TIME Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setMaxRiLookupTimeMin(String v) { _formValues.put("maxRiLookupTimeMin", v); }
    /**
     * Record the Override control for the MAX_RI_LOOKUP_TIME Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setMaxRiLookupTimeOverride(String v) { _formValues.put("maxRiLookupTimeOverride", v); }
    /**
     * Record the Step control for the MAX_RI_LOOKUP_TIME Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setMaxRiLookupTimeStep(String v) { _formValues.put("maxRiLookupTimeStep", v); }
    /**
     * Record the Default control for the NTCP_ESTABLISH_TIMEOUT Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpEstablishTimeoutDefault(String v) { _formValues.put("ntcpEstablishTimeoutDefault", v); }
    /**
     * Record the Max control for the NTCP_ESTABLISH_TIMEOUT Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpEstablishTimeoutMax(String v) { _formValues.put("ntcpEstablishTimeoutMax", v); }
    /**
     * Record the Min control for the NTCP_ESTABLISH_TIMEOUT Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpEstablishTimeoutMin(String v) { _formValues.put("ntcpEstablishTimeoutMin", v); }
    /**
     * Record the Override control for the NTCP_ESTABLISH_TIMEOUT Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpEstablishTimeoutOverride(String v) { _formValues.put("ntcpEstablishTimeoutOverride", v); }
    /**
     * Record the Step control for the NTCP_ESTABLISH_TIMEOUT Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpEstablishTimeoutStep(String v) { _formValues.put("ntcpEstablishTimeoutStep", v); }
    /**
     * Record the Default control for the ntcp.failsafe.iterationFreq Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpFailsafeIterationfreqDefault(String v) { _formValues.put("ntcpFailsafeIterationfreqDefault", v); }
    /**
     * Record the Max control for the ntcp.failsafe.iterationFreq Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpFailsafeIterationfreqMax(String v) { _formValues.put("ntcpFailsafeIterationfreqMax", v); }
    /**
     * Record the Min control for the ntcp.failsafe.iterationFreq Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpFailsafeIterationfreqMin(String v) { _formValues.put("ntcpFailsafeIterationfreqMin", v); }
    /**
     * Record the Override control for the ntcp.failsafe.iterationFreq Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpFailsafeIterationfreqOverride(String v) { _formValues.put("ntcpFailsafeIterationfreqOverride", v); }
    /**
     * Record the Step control for the ntcp.failsafe.iterationFreq Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpFailsafeIterationfreqStep(String v) { _formValues.put("ntcpFailsafeIterationfreqStep", v); }
    /**
     * Record the Default control for the ntcp.maxWriteBufs Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpMaxwritebufsDefault(String v) { _formValues.put("ntcpMaxwritebufsDefault", v); }
    /**
     * Record the Max control for the ntcp.maxWriteBufs Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpMaxwritebufsMax(String v) { _formValues.put("ntcpMaxwritebufsMax", v); }
    /**
     * Record the Min control for the ntcp.maxWriteBufs Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpMaxwritebufsMin(String v) { _formValues.put("ntcpMaxwritebufsMin", v); }
    /**
     * Record the Override control for the ntcp.maxWriteBufs Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpMaxwritebufsOverride(String v) { _formValues.put("ntcpMaxwritebufsOverride", v); }
    /**
     * Record the Step control for the ntcp.maxWriteBufs Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpMaxwritebufsStep(String v) { _formValues.put("ntcpMaxwritebufsStep", v); }
    /**
     * Record the Default control for the ntcp.reader.threads Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpReaderThreadsDefault(String v) { _formValues.put("ntcpReaderThreadsDefault", v); }
    /**
     * Record the Max control for the ntcp.reader.threads Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpReaderThreadsMax(String v) { _formValues.put("ntcpReaderThreadsMax", v); }
    /**
     * Record the Min control for the ntcp.reader.threads Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpReaderThreadsMin(String v) { _formValues.put("ntcpReaderThreadsMin", v); }
    /**
     * Record the Override control for the ntcp.reader.threads Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpReaderThreadsOverride(String v) { _formValues.put("ntcpReaderThreadsOverride", v); }
    /**
     * Record the Step control for the ntcp.reader.threads Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpReaderThreadsStep(String v) { _formValues.put("ntcpReaderThreadsStep", v); }
    /**
     * Record the Default control for the ntcp.sendPool.capacity Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpSendpoolCapacityDefault(String v) { _formValues.put("ntcpSendpoolCapacityDefault", v); }
    /**
     * Record the Max control for the ntcp.sendPool.capacity Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpSendpoolCapacityMax(String v) { _formValues.put("ntcpSendpoolCapacityMax", v); }
    /**
     * Record the Min control for the ntcp.sendPool.capacity Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpSendpoolCapacityMin(String v) { _formValues.put("ntcpSendpoolCapacityMin", v); }
    /**
     * Record the Override control for the ntcp.sendPool.capacity Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpSendpoolCapacityOverride(String v) { _formValues.put("ntcpSendpoolCapacityOverride", v); }
    /**
     * Record the Step control for the ntcp.sendPool.capacity Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpSendpoolCapacityStep(String v) { _formValues.put("ntcpSendpoolCapacityStep", v); }
    /**
     * Record the Default control for the ntcp.writer.threads Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setNtcpWriterThreadsDefault(String v) { _formValues.put("ntcpWriterThreadsDefault", v); }
    /**
     * Record the Max control for the ntcp.writer.threads Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setNtcpWriterThreadsMax(String v) { _formValues.put("ntcpWriterThreadsMax", v); }
    /**
     * Record the Min control for the ntcp.writer.threads Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setNtcpWriterThreadsMin(String v) { _formValues.put("ntcpWriterThreadsMin", v); }
    /**
     * Record the Override control for the ntcp.writer.threads Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setNtcpWriterThreadsOverride(String v) { _formValues.put("ntcpWriterThreadsOverride", v); }
    /**
     * Record the Step control for the ntcp.writer.threads Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setNtcpWriterThreadsStep(String v) { _formValues.put("ntcpWriterThreadsStep", v); }
    /**
     * Record the Default control for the rdns.corePoolSize Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRdnsCorepoolsizeDefault(String v) { _formValues.put("rdnsCorepoolsizeDefault", v); }
    /**
     * Record the Max control for the rdns.corePoolSize Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRdnsCorepoolsizeMax(String v) { _formValues.put("rdnsCorepoolsizeMax", v); }
    /**
     * Record the Min control for the rdns.corePoolSize Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRdnsCorepoolsizeMin(String v) { _formValues.put("rdnsCorepoolsizeMin", v); }
    /**
     * Record the Override control for the rdns.corePoolSize Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRdnsCorepoolsizeOverride(String v) { _formValues.put("rdnsCorepoolsizeOverride", v); }
    /**
     * Record the Step control for the rdns.corePoolSize Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRdnsCorepoolsizeStep(String v) { _formValues.put("rdnsCorepoolsizeStep", v); }
    /**
     * Record the Default control for the RED_MAX_DROP_PROB Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRedMaxDropProbDefault(String v) { _formValues.put("redMaxDropProbDefault", v); }
    /**
     * Record the Max control for the RED_MAX_DROP_PROB Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRedMaxDropProbMax(String v) { _formValues.put("redMaxDropProbMax", v); }
    /**
     * Record the Min control for the RED_MAX_DROP_PROB Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRedMaxDropProbMin(String v) { _formValues.put("redMaxDropProbMin", v); }
    /**
     * Record the Override control for the RED_MAX_DROP_PROB Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRedMaxDropProbOverride(String v) { _formValues.put("redMaxDropProbOverride", v); }
    /**
     * Record the Step control for the RED_MAX_DROP_PROB Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRedMaxDropProbStep(String v) { _formValues.put("redMaxDropProbStep", v); }
    /**
     * Record the Default control for the RED_MAX_THRESHOLD Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRedMaxThresholdDefault(String v) { _formValues.put("redMaxThresholdDefault", v); }
    /**
     * Record the Max control for the RED_MAX_THRESHOLD Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRedMaxThresholdMax(String v) { _formValues.put("redMaxThresholdMax", v); }
    /**
     * Record the Min control for the RED_MAX_THRESHOLD Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRedMaxThresholdMin(String v) { _formValues.put("redMaxThresholdMin", v); }
    /**
     * Record the Override control for the RED_MAX_THRESHOLD Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRedMaxThresholdOverride(String v) { _formValues.put("redMaxThresholdOverride", v); }
    /**
     * Record the Step control for the RED_MAX_THRESHOLD Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRedMaxThresholdStep(String v) { _formValues.put("redMaxThresholdStep", v); }
    /**
     * Record the Default control for the RED_MIN_THRESHOLD Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRedMinThresholdDefault(String v) { _formValues.put("redMinThresholdDefault", v); }
    /**
     * Record the Max control for the RED_MIN_THRESHOLD Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRedMinThresholdMax(String v) { _formValues.put("redMinThresholdMax", v); }
    /**
     * Record the Min control for the RED_MIN_THRESHOLD Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRedMinThresholdMin(String v) { _formValues.put("redMinThresholdMin", v); }
    /**
     * Record the Override control for the RED_MIN_THRESHOLD Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRedMinThresholdOverride(String v) { _formValues.put("redMinThresholdOverride", v); }
    /**
     * Record the Step control for the RED_MIN_THRESHOLD Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRedMinThresholdStep(String v) { _formValues.put("redMinThresholdStep", v); }
    /**
     * Record the Default control for the router.buildHandlerThreads Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRouterBuildhandlerthreadsDefault(String v) { _formValues.put("routerBuildhandlerthreadsDefault", v); }
    /**
     * Record the Max control for the router.buildHandlerThreads Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRouterBuildhandlerthreadsMax(String v) { _formValues.put("routerBuildhandlerthreadsMax", v); }
    /**
     * Record the Min control for the router.buildHandlerThreads Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRouterBuildhandlerthreadsMin(String v) { _formValues.put("routerBuildhandlerthreadsMin", v); }
    /**
     * Record the Override control for the router.buildHandlerThreads Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRouterBuildhandlerthreadsOverride(String v) { _formValues.put("routerBuildhandlerthreadsOverride", v); }
    /**
     * Record the Step control for the router.buildHandlerThreads Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRouterBuildhandlerthreadsStep(String v) { _formValues.put("routerBuildhandlerthreadsStep", v); }
    /**
     * Record the Default control for the router.defaultProcessingTimeThrottle
     * Tuner param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setRouterDefaultprocessingtimethrottleDefault(String v) { _formValues.put("routerDefaultprocessingtimethrottleDefault", v); }
    /**
     * Record the Max control for the router.defaultProcessingTimeThrottle Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setRouterDefaultprocessingtimethrottleMax(String v) { _formValues.put("routerDefaultprocessingtimethrottleMax", v); }
    /**
     * Record the Min control for the router.defaultProcessingTimeThrottle Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setRouterDefaultprocessingtimethrottleMin(String v) { _formValues.put("routerDefaultprocessingtimethrottleMin", v); }
    /**
     * Record the Override control for the router.defaultProcessingTimeThrottle
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setRouterDefaultprocessingtimethrottleOverride(String v) { _formValues.put("routerDefaultprocessingtimethrottleOverride", v); }
    /**
     * Record the Step control for the router.defaultProcessingTimeThrottle
     * Tuner param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setRouterDefaultprocessingtimethrottleStep(String v) { _formValues.put("routerDefaultprocessingtimethrottleStep", v); }
    /**
     * Record the Default control for the tunnel.build.maxConcurrent Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelBuildMaxconcurrentDefault(String v) { _formValues.put("tunnelBuildMaxconcurrentDefault", v); }
    /**
     * Record the Max control for the tunnel.build.maxConcurrent Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelBuildMaxconcurrentMax(String v) { _formValues.put("tunnelBuildMaxconcurrentMax", v); }
    /**
     * Record the Min control for the tunnel.build.maxConcurrent Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelBuildMaxconcurrentMin(String v) { _formValues.put("tunnelBuildMaxconcurrentMin", v); }
    /**
     * Record the Override control for the tunnel.build.maxConcurrent Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelBuildMaxconcurrentOverride(String v) { _formValues.put("tunnelBuildMaxconcurrentOverride", v); }
    /**
     * Record the Step control for the tunnel.build.maxConcurrent Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelBuildMaxconcurrentStep(String v) { _formValues.put("tunnelBuildMaxconcurrentStep", v); }
    /**
     * Record the Default control for the
     * tunnel.peerSelection.activityWindowMultiplier Tuner param: the default
     * the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelPeerselectionActivitywindowmultiplierDefault(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierDefault", v); }
    /**
     * Record the Max control for the
     * tunnel.peerSelection.activityWindowMultiplier Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelPeerselectionActivitywindowmultiplierMax(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierMax", v); }
    /**
     * Record the Min control for the
     * tunnel.peerSelection.activityWindowMultiplier Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelPeerselectionActivitywindowmultiplierMin(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierMin", v); }
    /**
     * Record the Override control for the
     * tunnel.peerSelection.activityWindowMultiplier Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelPeerselectionActivitywindowmultiplierOverride(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierOverride", v); }
    /**
     * Record the Step control for the
     * tunnel.peerSelection.activityWindowMultiplier Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelPeerselectionActivitywindowmultiplierStep(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierStep", v); }
    /**
     * Record the Default control for the tunnel.pool.backoffMs Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelPoolBackoffmsDefault(String v) { _formValues.put("tunnelPoolBackoffmsDefault", v); }
    /**
     * Record the Max control for the tunnel.pool.backoffMs Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelPoolBackoffmsMax(String v) { _formValues.put("tunnelPoolBackoffmsMax", v); }
    /**
     * Record the Min control for the tunnel.pool.backoffMs Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelPoolBackoffmsMin(String v) { _formValues.put("tunnelPoolBackoffmsMin", v); }
    /**
     * Record the Override control for the tunnel.pool.backoffMs Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelPoolBackoffmsOverride(String v) { _formValues.put("tunnelPoolBackoffmsOverride", v); }
    /**
     * Record the Step control for the tunnel.pool.backoffMs Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelPoolBackoffmsStep(String v) { _formValues.put("tunnelPoolBackoffmsStep", v); }
    /**
     * Record the Default control for the tunnel.pool.failureThreshold Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelPoolFailurethresholdDefault(String v) { _formValues.put("tunnelPoolFailurethresholdDefault", v); }
    /**
     * Record the Max control for the tunnel.pool.failureThreshold Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelPoolFailurethresholdMax(String v) { _formValues.put("tunnelPoolFailurethresholdMax", v); }
    /**
     * Record the Min control for the tunnel.pool.failureThreshold Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelPoolFailurethresholdMin(String v) { _formValues.put("tunnelPoolFailurethresholdMin", v); }
    /**
     * Record the Override control for the tunnel.pool.failureThreshold Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelPoolFailurethresholdOverride(String v) { _formValues.put("tunnelPoolFailurethresholdOverride", v); }
    /**
     * Record the Step control for the tunnel.pool.failureThreshold Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelPoolFailurethresholdStep(String v) { _formValues.put("tunnelPoolFailurethresholdStep", v); }
    /**
     * Record the Default control for the tunnel.pumper.queueCapacity Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelPumperQueuecapacityDefault(String v) { _formValues.put("tunnelPumperQueuecapacityDefault", v); }
    /**
     * Record the Max control for the tunnel.pumper.queueCapacity Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelPumperQueuecapacityMax(String v) { _formValues.put("tunnelPumperQueuecapacityMax", v); }
    /**
     * Record the Min control for the tunnel.pumper.queueCapacity Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelPumperQueuecapacityMin(String v) { _formValues.put("tunnelPumperQueuecapacityMin", v); }
    /**
     * Record the Override control for the tunnel.pumper.queueCapacity Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelPumperQueuecapacityOverride(String v) { _formValues.put("tunnelPumperQueuecapacityOverride", v); }
    /**
     * Record the Step control for the tunnel.pumper.queueCapacity Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelPumperQueuecapacityStep(String v) { _formValues.put("tunnelPumperQueuecapacityStep", v); }
    /**
     * Record the Default control for the tunnel.pumper.threads Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelPumperThreadsDefault(String v) { _formValues.put("tunnelPumperThreadsDefault", v); }
    /**
     * Record the Max control for the tunnel.pumper.threads Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelPumperThreadsMax(String v) { _formValues.put("tunnelPumperThreadsMax", v); }
    /**
     * Record the Min control for the tunnel.pumper.threads Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelPumperThreadsMin(String v) { _formValues.put("tunnelPumperThreadsMin", v); }
    /**
     * Record the Override control for the tunnel.pumper.threads Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelPumperThreadsOverride(String v) { _formValues.put("tunnelPumperThreadsOverride", v); }
    /**
     * Record the Step control for the tunnel.pumper.threads Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelPumperThreadsStep(String v) { _formValues.put("tunnelPumperThreadsStep", v); }
    /**
     * Record the Default control for the tunnel.testJob.maxQueued Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelTestjobMaxqueuedDefault(String v) { _formValues.put("tunnelTestjobMaxqueuedDefault", v); }
    /**
     * Record the Max control for the tunnel.testJob.maxQueued Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelTestjobMaxqueuedMax(String v) { _formValues.put("tunnelTestjobMaxqueuedMax", v); }
    /**
     * Record the Min control for the tunnel.testJob.maxQueued Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelTestjobMaxqueuedMin(String v) { _formValues.put("tunnelTestjobMaxqueuedMin", v); }
    /**
     * Record the Override control for the tunnel.testJob.maxQueued Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelTestjobMaxqueuedOverride(String v) { _formValues.put("tunnelTestjobMaxqueuedOverride", v); }
    /**
     * Record the Step control for the tunnel.testJob.maxQueued Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelTestjobMaxqueuedStep(String v) { _formValues.put("tunnelTestjobMaxqueuedStep", v); }
    /**
     * Record the Default control for the tunnel.testJob.maxTestDelay Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestdelayDefault(String v) { _formValues.put("tunnelTestjobMaxtestdelayDefault", v); }
    /**
     * Record the Max control for the tunnel.testJob.maxTestDelay Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestdelayMax(String v) { _formValues.put("tunnelTestjobMaxtestdelayMax", v); }
    /**
     * Record the Min control for the tunnel.testJob.maxTestDelay Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestdelayMin(String v) { _formValues.put("tunnelTestjobMaxtestdelayMin", v); }
    /**
     * Record the Override control for the tunnel.testJob.maxTestDelay Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelTestjobMaxtestdelayOverride(String v) { _formValues.put("tunnelTestjobMaxtestdelayOverride", v); }
    /**
     * Record the Step control for the tunnel.testJob.maxTestDelay Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestdelayStep(String v) { _formValues.put("tunnelTestjobMaxtestdelayStep", v); }
    /**
     * Record the Default control for the tunnel.testJob.minTestDelay Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelTestjobMintestdelayDefault(String v) { _formValues.put("tunnelTestjobMintestdelayDefault", v); }
    /**
     * Record the Max control for the tunnel.testJob.minTestDelay Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelTestjobMintestdelayMax(String v) { _formValues.put("tunnelTestjobMintestdelayMax", v); }
    /**
     * Record the Min control for the tunnel.testJob.minTestDelay Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelTestjobMintestdelayMin(String v) { _formValues.put("tunnelTestjobMintestdelayMin", v); }
    /**
     * Record the Override control for the tunnel.testJob.minTestDelay Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelTestjobMintestdelayOverride(String v) { _formValues.put("tunnelTestjobMintestdelayOverride", v); }
    /**
     * Record the Step control for the tunnel.testJob.minTestDelay Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelTestjobMintestdelayStep(String v) { _formValues.put("tunnelTestjobMintestdelayStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.testJob.maxTestPeriod Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestperiodDefault(String v) { _formValues.put("tunnelTestjobMaxtestperiodDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.testJob.maxTestPeriod Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestperiodMax(String v) { _formValues.put("tunnelTestjobMaxtestperiodMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.testJob.maxTestPeriod Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestperiodMin(String v) { _formValues.put("tunnelTestjobMaxtestperiodMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.testJob.maxTestPeriod
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelTestjobMaxtestperiodOverride(String v) { _formValues.put("tunnelTestjobMaxtestperiodOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.testJob.maxTestPeriod Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelTestjobMaxtestperiodStep(String v) { _formValues.put("tunnelTestjobMaxtestperiodStep", v); }
    /**
     * Record the Default control for the i2p.tunnel.testJob.minTestPeriod Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setTunnelTestjobMintestperiodDefault(String v) { _formValues.put("tunnelTestjobMintestperiodDefault", v); }
    /**
     * Record the Max control for the i2p.tunnel.testJob.minTestPeriod Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setTunnelTestjobMintestperiodMax(String v) { _formValues.put("tunnelTestjobMintestperiodMax", v); }
    /**
     * Record the Min control for the i2p.tunnel.testJob.minTestPeriod Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setTunnelTestjobMintestperiodMin(String v) { _formValues.put("tunnelTestjobMintestperiodMin", v); }
    /**
     * Record the Override control for the i2p.tunnel.testJob.minTestPeriod
     * Tuner param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setTunnelTestjobMintestperiodOverride(String v) { _formValues.put("tunnelTestjobMintestperiodOverride", v); }
    /**
     * Record the Step control for the i2p.tunnel.testJob.minTestPeriod Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setTunnelTestjobMintestperiodStep(String v) { _formValues.put("tunnelTestjobMintestperiodStep", v); }
    /**
     * Record the Default control for the udp.establish.maxQueuedOutbound Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpEstablishMaxqueuedoutboundDefault(String v) { _formValues.put("udpEstablishMaxqueuedoutboundDefault", v); }
    /**
     * Record the Max control for the udp.establish.maxQueuedOutbound Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpEstablishMaxqueuedoutboundMax(String v) { _formValues.put("udpEstablishMaxqueuedoutboundMax", v); }
    /**
     * Record the Min control for the udp.establish.maxQueuedOutbound Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpEstablishMaxqueuedoutboundMin(String v) { _formValues.put("udpEstablishMaxqueuedoutboundMin", v); }
    /**
     * Record the Override control for the udp.establish.maxQueuedOutbound Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpEstablishMaxqueuedoutboundOverride(String v) { _formValues.put("udpEstablishMaxqueuedoutboundOverride", v); }
    /**
     * Record the Step control for the udp.establish.maxQueuedOutbound Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpEstablishMaxqueuedoutboundStep(String v) { _formValues.put("udpEstablishMaxqueuedoutboundStep", v); }
    /**
     * Record the Default control for the udp.messageReceiver.threads Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpMessagereceiverThreadsDefault(String v) { _formValues.put("udpMessagereceiverThreadsDefault", v); }
    /**
     * Record the Max control for the udp.messageReceiver.threads Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpMessagereceiverThreadsMax(String v) { _formValues.put("udpMessagereceiverThreadsMax", v); }
    /**
     * Record the Min control for the udp.messageReceiver.threads Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpMessagereceiverThreadsMin(String v) { _formValues.put("udpMessagereceiverThreadsMin", v); }
    /**
     * Record the Override control for the udp.messageReceiver.threads Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpMessagereceiverThreadsOverride(String v) { _formValues.put("udpMessagereceiverThreadsOverride", v); }
    /**
     * Record the Step control for the udp.messageReceiver.threads Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpMessagereceiverThreadsStep(String v) { _formValues.put("udpMessagereceiverThreadsStep", v); }
    /**
     * Record the Default control for the udp.peer.concurrentMaxMessages Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerConcurrentmaxmessagesDefault(String v) { _formValues.put("udpPeerConcurrentmaxmessagesDefault", v); }
    /**
     * Record the Max control for the udp.peer.concurrentMaxMessages Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerConcurrentmaxmessagesMax(String v) { _formValues.put("udpPeerConcurrentmaxmessagesMax", v); }
    /**
     * Record the Min control for the udp.peer.concurrentMaxMessages Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerConcurrentmaxmessagesMin(String v) { _formValues.put("udpPeerConcurrentmaxmessagesMin", v); }
    /**
     * Record the Override control for the udp.peer.concurrentMaxMessages Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerConcurrentmaxmessagesOverride(String v) { _formValues.put("udpPeerConcurrentmaxmessagesOverride", v); }
    /**
     * Record the Step control for the udp.peer.concurrentMaxMessages Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerConcurrentmaxmessagesStep(String v) { _formValues.put("udpPeerConcurrentmaxmessagesStep", v); }
    /**
     * Record the Default control for the udp.peer.initConcurrentMsgs Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerInitconcurrentmsgsDefault(String v) { _formValues.put("udpPeerInitconcurrentmsgsDefault", v); }
    /**
     * Record the Max control for the udp.peer.initConcurrentMsgs Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerInitconcurrentmsgsMax(String v) { _formValues.put("udpPeerInitconcurrentmsgsMax", v); }
    /**
     * Record the Min control for the udp.peer.initConcurrentMsgs Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerInitconcurrentmsgsMin(String v) { _formValues.put("udpPeerInitconcurrentmsgsMin", v); }
    /**
     * Record the Override control for the udp.peer.initConcurrentMsgs Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerInitconcurrentmsgsOverride(String v) { _formValues.put("udpPeerInitconcurrentmsgsOverride", v); }
    /**
     * Record the Step control for the udp.peer.initConcurrentMsgs Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerInitconcurrentmsgsStep(String v) { _formValues.put("udpPeerInitconcurrentmsgsStep", v); }
    /**
     * Record the Default control for the udp.peer.initRTO Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerInitrtoDefault(String v) { _formValues.put("udpPeerInitrtoDefault", v); }
    /**
     * Record the Max control for the udp.peer.initRTO Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerInitrtoMax(String v) { _formValues.put("udpPeerInitrtoMax", v); }
    /**
     * Record the Min control for the udp.peer.initRTO Tuner param: the floor
     * the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerInitrtoMin(String v) { _formValues.put("udpPeerInitrtoMin", v); }
    /**
     * Record the Override control for the udp.peer.initRTO Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerInitrtoOverride(String v) { _formValues.put("udpPeerInitrtoOverride", v); }
    /**
     * Record the Step control for the udp.peer.initRTO Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerInitrtoStep(String v) { _formValues.put("udpPeerInitrtoStep", v); }
    /**
     * Record the Default control for the udp.peer.maxRTO Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerMaxrtoDefault(String v) { _formValues.put("udpPeerMaxrtoDefault", v); }
    /**
     * Record the Max control for the udp.peer.maxRTO Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerMaxrtoMax(String v) { _formValues.put("udpPeerMaxrtoMax", v); }
    /**
     * Record the Min control for the udp.peer.maxRTO Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerMaxrtoMin(String v) { _formValues.put("udpPeerMaxrtoMin", v); }
    /**
     * Record the Override control for the udp.peer.maxRTO Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerMaxrtoOverride(String v) { _formValues.put("udpPeerMaxrtoOverride", v); }
    /**
     * Record the Step control for the udp.peer.maxRTO Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerMaxrtoStep(String v) { _formValues.put("udpPeerMaxrtoStep", v); }
    /**
     * Record the Default control for the udp.peer.maxSendWindow Tuner param:
     * the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerMaxsendwindowDefault(String v) { _formValues.put("udpPeerMaxsendwindowDefault", v); }
    /**
     * Record the Max control for the udp.peer.maxSendWindow Tuner param: the
     * ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerMaxsendwindowMax(String v) { _formValues.put("udpPeerMaxsendwindowMax", v); }
    /**
     * Record the Min control for the udp.peer.maxSendWindow Tuner param: the
     * floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerMaxsendwindowMin(String v) { _formValues.put("udpPeerMaxsendwindowMin", v); }
    /**
     * Record the Override control for the udp.peer.maxSendWindow Tuner param:
     * negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerMaxsendwindowOverride(String v) { _formValues.put("udpPeerMaxsendwindowOverride", v); }
    /**
     * Record the Step control for the udp.peer.maxSendWindow Tuner param: the
     * largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerMaxsendwindowStep(String v) { _formValues.put("udpPeerMaxsendwindowStep", v); }
    /**
     * Record the Default control for the udp.peer.minConcurrentMsgs Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerMinconcurrentmsgsDefault(String v) { _formValues.put("udpPeerMinconcurrentmsgsDefault", v); }
    /**
     * Record the Max control for the udp.peer.minConcurrentMsgs Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerMinconcurrentmsgsMax(String v) { _formValues.put("udpPeerMinconcurrentmsgsMax", v); }
    /**
     * Record the Min control for the udp.peer.minConcurrentMsgs Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerMinconcurrentmsgsMin(String v) { _formValues.put("udpPeerMinconcurrentmsgsMin", v); }
    /**
     * Record the Override control for the udp.peer.minConcurrentMsgs Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerMinconcurrentmsgsOverride(String v) { _formValues.put("udpPeerMinconcurrentmsgsOverride", v); }
    /**
     * Record the Step control for the udp.peer.minConcurrentMsgs Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerMinconcurrentmsgsStep(String v) { _formValues.put("udpPeerMinconcurrentmsgsStep", v); }
    /**
     * Record the Default control for the udp.peer.minRTO Tuner param: the
     * default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerMinrtoDefault(String v) { _formValues.put("udpPeerMinrtoDefault", v); }
    /**
     * Record the Max control for the udp.peer.minRTO Tuner param: the ceiling
     * the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerMinrtoMax(String v) { _formValues.put("udpPeerMinrtoMax", v); }
    /**
     * Record the Min control for the udp.peer.minRTO Tuner param: the floor the
     * auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerMinrtoMin(String v) { _formValues.put("udpPeerMinrtoMin", v); }
    /**
     * Record the Override control for the udp.peer.minRTO Tuner param: negative
     * resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerMinrtoOverride(String v) { _formValues.put("udpPeerMinrtoOverride", v); }
    /**
     * Record the Step control for the udp.peer.minRTO Tuner param: the largest
     * change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerMinrtoStep(String v) { _formValues.put("udpPeerMinrtoStep", v); }
    /**
     * Record the Default control for the udp.peer.outboundMsgExpiration Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerOutboundmsgexpirationDefault(String v) { _formValues.put("udpPeerOutboundmsgexpirationDefault", v); }
    /**
     * Record the Max control for the udp.peer.outboundMsgExpiration Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerOutboundmsgexpirationMax(String v) { _formValues.put("udpPeerOutboundmsgexpirationMax", v); }
    /**
     * Record the Min control for the udp.peer.outboundMsgExpiration Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerOutboundmsgexpirationMin(String v) { _formValues.put("udpPeerOutboundmsgexpirationMin", v); }
    /**
     * Record the Override control for the udp.peer.outboundMsgExpiration Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerOutboundmsgexpirationOverride(String v) { _formValues.put("udpPeerOutboundmsgexpirationOverride", v); }
    /**
     * Record the Step control for the udp.peer.outboundMsgExpiration Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerOutboundmsgexpirationStep(String v) { _formValues.put("udpPeerOutboundmsgexpirationStep", v); }
    /**
     * Record the Default control for the udp.peer.postRTOWindowMTUs Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerPostrtowindowmtusDefault(String v) { _formValues.put("udpPeerPostrtowindowmtusDefault", v); }
    /**
     * Record the Max control for the udp.peer.postRTOWindowMTUs Tuner param:
     * the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerPostrtowindowmtusMax(String v) { _formValues.put("udpPeerPostrtowindowmtusMax", v); }
    /**
     * Record the Min control for the udp.peer.postRTOWindowMTUs Tuner param:
     * the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerPostrtowindowmtusMin(String v) { _formValues.put("udpPeerPostrtowindowmtusMin", v); }
    /**
     * Record the Override control for the udp.peer.postRTOWindowMTUs Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerPostrtowindowmtusOverride(String v) { _formValues.put("udpPeerPostrtowindowmtusOverride", v); }
    /**
     * Record the Step control for the udp.peer.postRTOWindowMTUs Tuner param:
     * the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerPostrtowindowmtusStep(String v) { _formValues.put("udpPeerPostrtowindowmtusStep", v); }
    /**
     * Record the Default control for the udp.peer.sentMessagesCleanTime Tuner
     * param: the default the auto-tuner starts from and reverts toward.
     * @param v raw text from the Default input, parsed by saveField()
     */
    public void setUdpPeerSentmessagescleantimeDefault(String v) { _formValues.put("udpPeerSentmessagescleantimeDefault", v); }
    /**
     * Record the Max control for the udp.peer.sentMessagesCleanTime Tuner
     * param: the ceiling the auto-tuner will not go above.
     * @param v raw text from the Max input, parsed by saveField()
     */
    public void setUdpPeerSentmessagescleantimeMax(String v) { _formValues.put("udpPeerSentmessagescleantimeMax", v); }
    /**
     * Record the Min control for the udp.peer.sentMessagesCleanTime Tuner
     * param: the floor the auto-tuner will not go below.
     * @param v raw text from the Min input, parsed by saveField()
     */
    public void setUdpPeerSentmessagescleantimeMin(String v) { _formValues.put("udpPeerSentmessagescleantimeMin", v); }
    /**
     * Record the Override control for the udp.peer.sentMessagesCleanTime Tuner
     * param: negative resumes auto-tuning, non-negative locks a value.
     * @param v form value: "-1" = auto, otherwise a manual lock
     */
    public void setUdpPeerSentmessagescleantimeOverride(String v) { _formValues.put("udpPeerSentmessagescleantimeOverride", v); }
    /**
     * Record the Step control for the udp.peer.sentMessagesCleanTime Tuner
     * param: the largest change the auto-tuner may apply per update.
     * @param v raw text from the Step input, parsed by saveField()
     */
    public void setUdpPeerSentmessagescleantimeStep(String v) { _formValues.put("udpPeerSentmessagescleantimeStep", v); }

    /**
     * Form-field prefix for a tunable param, used by TuningHelper to render the
     * row's input names so they match the setters in this class.
     * Params not listed here have no user overrides and are rendered read-only.
     * @param prop the Tuner param name
     * @return the prefix, or null if the param has no form controls
     */
    public static String getFormPrefix(String prop) {
        for (Tunable t : TUNED) {
            if (t.prop.equals(prop))
                return t.prefix;
        }
        return null;
    }

    /**
     * Save a single field if it is a valid integer.
     * @param changes map of property key to value
     * @param param the tunable property name
     * @param field the suffix, e.g. Min, Max, Step, Default
     * @param value the submitted form value, or null if absent
     */
    private void saveField(Map<String, String> changes, String param, String field,
                           String value) {
        if (value == null || value.isEmpty())
            return;
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            addFormError(_t("Invalid value") + ": " + param + "." + field + " = " + value);
            return;
        }
        String key = param + "." + field.toLowerCase();
        changes.put(key, String.valueOf(parsed));
    }

    /**
     * Apply an auto-tuning override.
     * @param tuner the Tuner instance
     * @param paramName the Tuner param name
     * @param value form value: "-1" = auto, numeric = manual lock
     */
    private void applyOverride(Tuner tuner, String paramName, String value) {
        if (value == null || value.isEmpty())
            return;
        try {
            int v = Integer.parseInt(value.trim());
            tuner.setOverride(paramName, v);
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    /**
     * Get the Tuner instance via the UDP transport.
     * @return the tuner
     */
    private Tuner getTuner() {
        if (_context == null) return null;
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) return null;
        SortedMap<String, Transport> transports = cs.getTransports();
        Transport udp = transports.get(UDPTransport.STYLE);
        if (udp instanceof UDPTransport)
            return ((UDPTransport) udp).getTuner();
        return null;
    }

    protected void processForm() {
        if (_action == null)
            return;

        // Restore Defaults: reset all params to factory defaults
        if (_action.equals(_t("Restore Defaults"))) {
            Tuner tuner = getTuner();
            if (tuner != null) {
                tuner.restoreDefaults();
                addFormNotice(_t("All parameters restored to factory defaults"));
            } else {
                addFormNotice(_t("Auto-Tuning is not available"));
            }
            return;
        }

        if (!_action.equals(_t("Save")))
            return;

        Map<String, String> changes = new HashMap<>();
        for (Tunable t : TUNED) {
            if ((t.flags & HAS_RANGE) != 0) {
                saveField(changes, t.prop, "Min", _formValues.get(t.prefix + "Min"));
                saveField(changes, t.prop, "Max", _formValues.get(t.prefix + "Max"));
                saveField(changes, t.prop, "Step", _formValues.get(t.prefix + "Step"));
            }
            if ((t.flags & HAS_DEFAULT) != 0)
                saveField(changes, t.prop, "Default", _formValues.get(t.prefix + "Default"));
        }

        // Process auto-tuning overrides (checkbox toggle)
        Tuner tuner = getTuner();
        if (tuner != null) {
            for (Tunable t : TUNED) {
                if ((t.flags & HAS_OVERRIDE) != 0)
                    applyOverride(tuner, t.prop, _formValues.get(t.prefix + "Override"));
            }
        }

        if (!changes.isEmpty()) {
            if (tuner != null) {
                Tuner.AutotuneConfig autotune = tuner.getAutotune();
                for (Map.Entry<String, String> entry : changes.entrySet()) {
                    autotune.setProperty(entry.getKey(), entry.getValue());
                }
                autotune.forceSave();
                addFormNotice(_t("Tuning ranges saved — changes take effect immediately"));
            } else {
                addFormNotice(_t("No changes to save"));
            }
        } else if (tuner != null) {
            addFormNotice(_t("Tuning overrides applied"));
        } else {
            addFormNotice(_t("No changes to save"));
        }
    }
}
