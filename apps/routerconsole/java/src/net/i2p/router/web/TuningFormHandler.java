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
        t = new Tunable("profileOrganizer.maxFastPeers", "maxFastPeers", HAS_RANGE | HAS_DEFAULT);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.minHighCapacityPeers", "minHighCapPeers", HAS_RANGE | HAS_DEFAULT);
        TUNED.add(t);
        t = new Tunable("profileOrganizer.maxHighCapacityPeers", "maxHighCapPeers", HAS_RANGE | HAS_DEFAULT);
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
        t = new Tunable("i2p.tunnel.requestThrottle.sustainedHighLoadMs", "i2pTunnelRequestthrottleSustainedhighloadms", HAS_RANGE | HAS_DEFAULT | HAS_OVERRIDE);
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
    public void setAckFrequencyDefault(String v) { _formValues.put("ackFrequencyDefault", v); }
    public void setAckFrequencyMax(String v) { _formValues.put("ackFrequencyMax", v); }
    public void setAckFrequencyMin(String v) { _formValues.put("ackFrequencyMin", v); }
    public void setAckFrequencyOverride(String v) { _formValues.put("ackFrequencyOverride", v); }
    public void setAckFrequencyStep(String v) { _formValues.put("ackFrequencyStep", v); }
    public void setBuildFirstHopTimeoutDefault(String v) { _formValues.put("buildFirstHopTimeoutDefault", v); }
    public void setBuildFirstHopTimeoutMax(String v) { _formValues.put("buildFirstHopTimeoutMax", v); }
    public void setBuildFirstHopTimeoutMin(String v) { _formValues.put("buildFirstHopTimeoutMin", v); }
    public void setBuildFirstHopTimeoutOverride(String v) { _formValues.put("buildFirstHopTimeoutOverride", v); }
    public void setBuildFirstHopTimeoutStep(String v) { _formValues.put("buildFirstHopTimeoutStep", v); }
    public void setBuildHandlerMaxQueueDefault(String v) { _formValues.put("buildHandlerMaxQueueDefault", v); }
    public void setBuildHandlerMaxQueueMax(String v) { _formValues.put("buildHandlerMaxQueueMax", v); }
    public void setBuildHandlerMaxQueueMin(String v) { _formValues.put("buildHandlerMaxQueueMin", v); }
    public void setBuildHandlerMaxQueueOverride(String v) { _formValues.put("buildHandlerMaxQueueOverride", v); }
    public void setBuildHandlerMaxQueueStep(String v) { _formValues.put("buildHandlerMaxQueueStep", v); }
    public void setBuildRequestTimeoutDefault(String v) { _formValues.put("buildRequestTimeoutDefault", v); }
    public void setBuildRequestTimeoutMax(String v) { _formValues.put("buildRequestTimeoutMax", v); }
    public void setBuildRequestTimeoutMin(String v) { _formValues.put("buildRequestTimeoutMin", v); }
    public void setBuildRequestTimeoutOverride(String v) { _formValues.put("buildRequestTimeoutOverride", v); }
    public void setBuildRequestTimeoutStep(String v) { _formValues.put("buildRequestTimeoutStep", v); }
    public void setCodelIntervalDefault(String v) { _formValues.put("codelIntervalDefault", v); }
    public void setCodelIntervalMax(String v) { _formValues.put("codelIntervalMax", v); }
    public void setCodelIntervalMin(String v) { _formValues.put("codelIntervalMin", v); }
    public void setCodelIntervalOverride(String v) { _formValues.put("codelIntervalOverride", v); }
    public void setCodelIntervalStep(String v) { _formValues.put("codelIntervalStep", v); }
    public void setCodelTargetDefault(String v) { _formValues.put("codelTargetDefault", v); }
    public void setCodelTargetMax(String v) { _formValues.put("codelTargetMax", v); }
    public void setCodelTargetMin(String v) { _formValues.put("codelTargetMin", v); }
    public void setCodelTargetOverride(String v) { _formValues.put("codelTargetOverride", v); }
    public void setCodelTargetStep(String v) { _formValues.put("codelTargetStep", v); }
    public void setCongestionAvoidanceGrowthDefault(String v) { _formValues.put("congestionAvoidanceGrowthDefault", v); }
    public void setCongestionAvoidanceGrowthOverride(String v) { _formValues.put("congestionAvoidanceGrowthOverride", v); }
    public void setDataMessageTimeoutDefault(String v) { _formValues.put("dataMessageTimeoutDefault", v); }
    public void setDataMessageTimeoutMax(String v) { _formValues.put("dataMessageTimeoutMax", v); }
    public void setDataMessageTimeoutMin(String v) { _formValues.put("dataMessageTimeoutMin", v); }
    public void setDataMessageTimeoutOverride(String v) { _formValues.put("dataMessageTimeoutOverride", v); }
    public void setDataMessageTimeoutStep(String v) { _formValues.put("dataMessageTimeoutStep", v); }
    public void setEdhPrecalcMinDefault(String v) { _formValues.put("edhPrecalcMinDefault", v); }
    public void setEdhPrecalcMinMax(String v) { _formValues.put("edhPrecalcMinMax", v); }
    public void setEdhPrecalcMinMin(String v) { _formValues.put("edhPrecalcMinMin", v); }
    public void setEdhPrecalcMinOverride(String v) { _formValues.put("edhPrecalcMinOverride", v); }
    public void setEdhPrecalcMinStep(String v) { _formValues.put("edhPrecalcMinStep", v); }
    public void setGoodDeficitThrottleDefault(String v) { _formValues.put("goodDeficitThrottleDefault", v); }
    public void setGoodDeficitThrottleMax(String v) { _formValues.put("goodDeficitThrottleMax", v); }
    public void setGoodDeficitThrottleMin(String v) { _formValues.put("goodDeficitThrottleMin", v); }
    public void setGoodDeficitThrottleOverride(String v) { _formValues.put("goodDeficitThrottleOverride", v); }
    public void setGoodDeficitThrottleStep(String v) { _formValues.put("goodDeficitThrottleStep", v); }
    public void setIbEstablishTimeDefault(String v) { _formValues.put("ibEstablishTimeDefault", v); }
    public void setIbEstablishTimeMax(String v) { _formValues.put("ibEstablishTimeMax", v); }
    public void setIbEstablishTimeMin(String v) { _formValues.put("ibEstablishTimeMin", v); }
    public void setIbEstablishTimeOverride(String v) { _formValues.put("ibEstablishTimeOverride", v); }
    public void setIbEstablishTimeStep(String v) { _formValues.put("ibEstablishTimeStep", v); }
    public void setIbMsgsPerPumpDefault(String v) { _formValues.put("ibMsgsPerPumpDefault", v); }
    public void setIbMsgsPerPumpMax(String v) { _formValues.put("ibMsgsPerPumpMax", v); }
    public void setIbMsgsPerPumpMin(String v) { _formValues.put("ibMsgsPerPumpMin", v); }
    public void setIbMsgsPerPumpOverride(String v) { _formValues.put("ibMsgsPerPumpOverride", v); }
    public void setIbMsgsPerPumpStep(String v) { _formValues.put("ibMsgsPerPumpStep", v); }
    public void setImmediateAckDelayDefault(String v) { _formValues.put("immediateAckDelayDefault", v); }
    public void setImmediateAckDelayMax(String v) { _formValues.put("immediateAckDelayMax", v); }
    public void setImmediateAckDelayMin(String v) { _formValues.put("immediateAckDelayMin", v); }
    public void setImmediateAckDelayOverride(String v) { _formValues.put("immediateAckDelayOverride", v); }
    public void setImmediateAckDelayStep(String v) { _formValues.put("immediateAckDelayStep", v); }
    public void setInitialAckDelayDefault(String v) { _formValues.put("initialAckDelayDefault", v); }
    public void setInitialAckDelayMax(String v) { _formValues.put("initialAckDelayMax", v); }
    public void setInitialAckDelayMin(String v) { _formValues.put("initialAckDelayMin", v); }
    public void setInitialAckDelayOverride(String v) { _formValues.put("initialAckDelayOverride", v); }
    public void setInitialAckDelayStep(String v) { _formValues.put("initialAckDelayStep", v); }
    public void setInitialResendDelayDefault(String v) { _formValues.put("initialResendDelayDefault", v); }
    public void setInitialResendDelayMax(String v) { _formValues.put("initialResendDelayMax", v); }
    public void setInitialResendDelayMin(String v) { _formValues.put("initialResendDelayMin", v); }
    public void setInitialResendDelayOverride(String v) { _formValues.put("initialResendDelayOverride", v); }
    public void setInitialResendDelayStep(String v) { _formValues.put("initialResendDelayStep", v); }
    public void setInitialRTODefault(String v) { _formValues.put("initialRTODefault", v); }
    public void setInitialRTOMax(String v) { _formValues.put("initialRTOMax", v); }
    public void setInitialRTOMin(String v) { _formValues.put("initialRTOMin", v); }
    public void setInitialRTOOverride(String v) { _formValues.put("initialRTOOverride", v); }
    public void setInitialRTOStep(String v) { _formValues.put("initialRTOStep", v); }
    public void setInitialWindowSizeDefault(String v) { _formValues.put("initialWindowSizeDefault", v); }
    public void setInitialWindowSizeMax(String v) { _formValues.put("initialWindowSizeMax", v); }
    public void setInitialWindowSizeMin(String v) { _formValues.put("initialWindowSizeMin", v); }
    public void setInitialWindowSizeOverride(String v) { _formValues.put("initialWindowSizeOverride", v); }
    public void setInitialWindowSizeStep(String v) { _formValues.put("initialWindowSizeStep", v); }
    public void setMaxConcurrentEstablishDefault(String v) { _formValues.put("maxConcurrentEstablishDefault", v); }
    public void setMaxConcurrentEstablishMax(String v) { _formValues.put("maxConcurrentEstablishMax", v); }
    public void setMaxConcurrentEstablishMin(String v) { _formValues.put("maxConcurrentEstablishMin", v); }
    public void setMaxConcurrentEstablishOverride(String v) { _formValues.put("maxConcurrentEstablishOverride", v); }
    public void setMaxConcurrentEstablishStep(String v) { _formValues.put("maxConcurrentEstablishStep", v); }
    public void setMaxFastPeersDefault(String v) { _formValues.put("maxFastPeersDefault", v); }
    public void setMaxFastPeersMax(String v) { _formValues.put("maxFastPeersMax", v); }
    public void setMaxFastPeersMin(String v) { _formValues.put("maxFastPeersMin", v); }
    public void setMaxFastPeersStep(String v) { _formValues.put("maxFastPeersStep", v); }
    public void setMaxHighCapPeersDefault(String v) { _formValues.put("maxHighCapPeersDefault", v); }
    public void setMaxHighCapPeersMax(String v) { _formValues.put("maxHighCapPeersMax", v); }
    public void setMaxHighCapPeersMin(String v) { _formValues.put("maxHighCapPeersMin", v); }
    public void setMaxHighCapPeersStep(String v) { _formValues.put("maxHighCapPeersStep", v); }
    public void setMaxInboundBufferDefault(String v) { _formValues.put("maxInboundBufferDefault", v); }
    public void setMaxInboundBufferMax(String v) { _formValues.put("maxInboundBufferMax", v); }
    public void setMaxInboundBufferMin(String v) { _formValues.put("maxInboundBufferMin", v); }
    public void setMaxInboundBufferOverride(String v) { _formValues.put("maxInboundBufferOverride", v); }
    public void setMaxInboundBufferStep(String v) { _formValues.put("maxInboundBufferStep", v); }
    public void setMaxParticipatingTunnelsDefault(String v) { _formValues.put("maxParticipatingTunnelsDefault", v); }
    public void setMaxParticipatingTunnelsMax(String v) { _formValues.put("maxParticipatingTunnelsMax", v); }
    public void setMaxParticipatingTunnelsMin(String v) { _formValues.put("maxParticipatingTunnelsMin", v); }
    public void setMaxParticipatingTunnelsOverride(String v) { _formValues.put("maxParticipatingTunnelsOverride", v); }
    public void setMaxParticipatingTunnelsStep(String v) { _formValues.put("maxParticipatingTunnelsStep", v); }
    public void setMaxProfilesDefault(String v) { _formValues.put("maxProfilesDefault", v); }
    public void setMaxProfilesMax(String v) { _formValues.put("maxProfilesMax", v); }
    public void setMaxProfilesMin(String v) { _formValues.put("maxProfilesMin", v); }
    public void setMaxProfilesOverride(String v) { _formValues.put("maxProfilesOverride", v); }
    public void setMaxProfilesStep(String v) { _formValues.put("maxProfilesStep", v); }
    public void setMaxResendDelayDefault(String v) { _formValues.put("maxResendDelayDefault", v); }
    public void setMaxResendDelayMax(String v) { _formValues.put("maxResendDelayMax", v); }
    public void setMaxResendDelayMin(String v) { _formValues.put("maxResendDelayMin", v); }
    public void setMaxResendDelayOverride(String v) { _formValues.put("maxResendDelayOverride", v); }
    public void setMaxResendDelayStep(String v) { _formValues.put("maxResendDelayStep", v); }
    public void setMaxRetransmissionsDefault(String v) { _formValues.put("maxRetransmissionsDefault", v); }
    public void setMaxRetransmissionsMax(String v) { _formValues.put("maxRetransmissionsMax", v); }
    public void setMaxRetransmissionsMin(String v) { _formValues.put("maxRetransmissionsMin", v); }
    public void setMaxRetransmissionsOverride(String v) { _formValues.put("maxRetransmissionsOverride", v); }
    public void setMaxRetransmissionsStep(String v) { _formValues.put("maxRetransmissionsStep", v); }
    public void setMaxRTODefault(String v) { _formValues.put("maxRTODefault", v); }
    public void setMaxRTOMax(String v) { _formValues.put("maxRTOMax", v); }
    public void setMaxRTOMin(String v) { _formValues.put("maxRTOMin", v); }
    public void setMaxRTOOverride(String v) { _formValues.put("maxRTOOverride", v); }
    public void setMaxRTOStep(String v) { _formValues.put("maxRTOStep", v); }
    public void setMaxRttDefault(String v) { _formValues.put("maxRttDefault", v); }
    public void setMaxRttMax(String v) { _formValues.put("maxRttMax", v); }
    public void setMaxRttMin(String v) { _formValues.put("maxRttMin", v); }
    public void setMaxRttOverride(String v) { _formValues.put("maxRttOverride", v); }
    public void setMaxRttStep(String v) { _formValues.put("maxRttStep", v); }
    public void setMaxSlowStartWindowDefault(String v) { _formValues.put("maxSlowStartWindowDefault", v); }
    public void setMaxSlowStartWindowMax(String v) { _formValues.put("maxSlowStartWindowMax", v); }
    public void setMaxSlowStartWindowMin(String v) { _formValues.put("maxSlowStartWindowMin", v); }
    public void setMaxSlowStartWindowOverride(String v) { _formValues.put("maxSlowStartWindowOverride", v); }
    public void setMaxSlowStartWindowStep(String v) { _formValues.put("maxSlowStartWindowStep", v); }
    public void setMinFastPeersDefault(String v) { _formValues.put("minFastPeersDefault", v); }
    public void setMinFastPeersMax(String v) { _formValues.put("minFastPeersMax", v); }
    public void setMinFastPeersMin(String v) { _formValues.put("minFastPeersMin", v); }
    public void setMinFastPeersOverride(String v) { _formValues.put("minFastPeersOverride", v); }
    public void setMinFastPeersStep(String v) { _formValues.put("minFastPeersStep", v); }
    public void setMinHighCapPeersDefault(String v) { _formValues.put("minHighCapPeersDefault", v); }
    public void setMinHighCapPeersMax(String v) { _formValues.put("minHighCapPeersMax", v); }
    public void setMinHighCapPeersMin(String v) { _formValues.put("minHighCapPeersMin", v); }
    public void setMinHighCapPeersStep(String v) { _formValues.put("minHighCapPeersStep", v); }
    public void setMinResendDelayDefault(String v) { _formValues.put("minResendDelayDefault", v); }
    public void setMinResendDelayOverride(String v) { _formValues.put("minResendDelayOverride", v); }
    public void setMlkemPrecalcMinDefault(String v) { _formValues.put("mlkemPrecalcMinDefault", v); }
    public void setMlkemPrecalcMinMax(String v) { _formValues.put("mlkemPrecalcMinMax", v); }
    public void setMlkemPrecalcMinMin(String v) { _formValues.put("mlkemPrecalcMinMin", v); }
    public void setMlkemPrecalcMinOverride(String v) { _formValues.put("mlkemPrecalcMinOverride", v); }
    public void setMlkemPrecalcMinStep(String v) { _formValues.put("mlkemPrecalcMinStep", v); }
    public void setNetDBMaxConcurrentDefault(String v) { _formValues.put("netDBMaxConcurrentDefault", v); }
    public void setNetDBMaxConcurrentMax(String v) { _formValues.put("netDBMaxConcurrentMax", v); }
    public void setNetDBMaxConcurrentMin(String v) { _formValues.put("netDBMaxConcurrentMin", v); }
    public void setNetDBMaxConcurrentOverride(String v) { _formValues.put("netDBMaxConcurrentOverride", v); }
    public void setNetDBMaxConcurrentStep(String v) { _formValues.put("netDBMaxConcurrentStep", v); }
    public void setNetDBSearchLimitDefault(String v) { _formValues.put("netDBSearchLimitDefault", v); }
    public void setNetDBSearchLimitMax(String v) { _formValues.put("netDBSearchLimitMax", v); }
    public void setNetDBSearchLimitMin(String v) { _formValues.put("netDBSearchLimitMin", v); }
    public void setNetDBSearchLimitOverride(String v) { _formValues.put("netDBSearchLimitOverride", v); }
    public void setNetDBSearchLimitStep(String v) { _formValues.put("netDBSearchLimitStep", v); }
    public void setNetDBSingleSearchTimeDefault(String v) { _formValues.put("netDBSingleSearchTimeDefault", v); }
    public void setNetDBSingleSearchTimeMax(String v) { _formValues.put("netDBSingleSearchTimeMax", v); }
    public void setNetDBSingleSearchTimeMin(String v) { _formValues.put("netDBSingleSearchTimeMin", v); }
    public void setNetDBSingleSearchTimeOverride(String v) { _formValues.put("netDBSingleSearchTimeOverride", v); }
    public void setNetDBSingleSearchTimeStep(String v) { _formValues.put("netDBSingleSearchTimeStep", v); }
    public void setNtcpQueueCapacityDefault(String v) { _formValues.put("ntcpQueueCapacityDefault", v); }
    public void setNtcpQueueCapacityMax(String v) { _formValues.put("ntcpQueueCapacityMax", v); }
    public void setNtcpQueueCapacityMin(String v) { _formValues.put("ntcpQueueCapacityMin", v); }
    public void setNtcpQueueCapacityOverride(String v) { _formValues.put("ntcpQueueCapacityOverride", v); }
    public void setNtcpQueueCapacityStep(String v) { _formValues.put("ntcpQueueCapacityStep", v); }
    public void setNtcpThreadsDefault(String v) { _formValues.put("ntcpThreadsDefault", v); }
    public void setNtcpThreadsMax(String v) { _formValues.put("ntcpThreadsMax", v); }
    public void setNtcpThreadsMin(String v) { _formValues.put("ntcpThreadsMin", v); }
    public void setNtcpThreadsOverride(String v) { _formValues.put("ntcpThreadsOverride", v); }
    public void setNtcpThreadsStep(String v) { _formValues.put("ntcpThreadsStep", v); }
    public void setObEstablishTimeDefault(String v) { _formValues.put("obEstablishTimeDefault", v); }
    public void setObEstablishTimeMax(String v) { _formValues.put("obEstablishTimeMax", v); }
    public void setObEstablishTimeMin(String v) { _formValues.put("obEstablishTimeMin", v); }
    public void setObEstablishTimeOverride(String v) { _formValues.put("obEstablishTimeOverride", v); }
    public void setObEstablishTimeStep(String v) { _formValues.put("obEstablishTimeStep", v); }
    public void setObMsgsPerPumpDefault(String v) { _formValues.put("obMsgsPerPumpDefault", v); }
    public void setObMsgsPerPumpMax(String v) { _formValues.put("obMsgsPerPumpMax", v); }
    public void setObMsgsPerPumpMin(String v) { _formValues.put("obMsgsPerPumpMin", v); }
    public void setObMsgsPerPumpOverride(String v) { _formValues.put("obMsgsPerPumpOverride", v); }
    public void setObMsgsPerPumpStep(String v) { _formValues.put("obMsgsPerPumpStep", v); }
    public void setPassiveFlushDelayDefault(String v) { _formValues.put("passiveFlushDelayDefault", v); }
    public void setPassiveFlushDelayMax(String v) { _formValues.put("passiveFlushDelayMax", v); }
    public void setPassiveFlushDelayMin(String v) { _formValues.put("passiveFlushDelayMin", v); }
    public void setPassiveFlushDelayOverride(String v) { _formValues.put("passiveFlushDelayOverride", v); }
    public void setPassiveFlushDelayStep(String v) { _formValues.put("passiveFlushDelayStep", v); }
    public void setPeerOutboundQueueDefault(String v) { _formValues.put("peerOutboundQueueDefault", v); }
    public void setPeerOutboundQueueMax(String v) { _formValues.put("peerOutboundQueueMax", v); }
    public void setPeerOutboundQueueMin(String v) { _formValues.put("peerOutboundQueueMin", v); }
    public void setPeerOutboundQueueOverride(String v) { _formValues.put("peerOutboundQueueOverride", v); }
    public void setPeerOutboundQueueStep(String v) { _formValues.put("peerOutboundQueueStep", v); }
    public void setPerTunnelBweDivisorDefault(String v) { _formValues.put("perTunnelBweDivisorDefault", v); }
    public void setPerTunnelBweDivisorMax(String v) { _formValues.put("perTunnelBweDivisorMax", v); }
    public void setPerTunnelBweDivisorMin(String v) { _formValues.put("perTunnelBweDivisorMin", v); }
    public void setPerTunnelBweDivisorOverride(String v) { _formValues.put("perTunnelBweDivisorOverride", v); }
    public void setPerTunnelBweDivisorStep(String v) { _formValues.put("perTunnelBweDivisorStep", v); }
    public void setReplenishFrequencyDefault(String v) { _formValues.put("replenishFrequencyDefault", v); }
    public void setReplenishFrequencyMax(String v) { _formValues.put("replenishFrequencyMax", v); }
    public void setReplenishFrequencyMin(String v) { _formValues.put("replenishFrequencyMin", v); }
    public void setReplenishFrequencyOverride(String v) { _formValues.put("replenishFrequencyOverride", v); }
    public void setReplenishFrequencyStep(String v) { _formValues.put("replenishFrequencyStep", v); }
    public void setRequeueTimeDefault(String v) { _formValues.put("requeueTimeDefault", v); }
    public void setRequeueTimeMax(String v) { _formValues.put("requeueTimeMax", v); }
    public void setRequeueTimeMin(String v) { _formValues.put("requeueTimeMin", v); }
    public void setRequeueTimeOverride(String v) { _formValues.put("requeueTimeOverride", v); }
    public void setRequeueTimeStep(String v) { _formValues.put("requeueTimeStep", v); }
    public void setSelectorLoopDelayDefault(String v) { _formValues.put("selectorLoopDelayDefault", v); }
    public void setSelectorLoopDelayMax(String v) { _formValues.put("selectorLoopDelayMax", v); }
    public void setSelectorLoopDelayMin(String v) { _formValues.put("selectorLoopDelayMin", v); }
    public void setSelectorLoopDelayOverride(String v) { _formValues.put("selectorLoopDelayOverride", v); }
    public void setSelectorLoopDelayStep(String v) { _formValues.put("selectorLoopDelayStep", v); }
    public void setSlowStartGrowthDefault(String v) { _formValues.put("slowStartGrowthDefault", v); }
    public void setSlowStartGrowthOverride(String v) { _formValues.put("slowStartGrowthOverride", v); }
    public void setThreadsDefault(String v) { _formValues.put("threadsDefault", v); }
    public void setThreadsMax(String v) { _formValues.put("threadsMax", v); }
    public void setThreadsMin(String v) { _formValues.put("threadsMin", v); }
    public void setThreadsOverride(String v) { _formValues.put("threadsOverride", v); }
    public void setThreadsStep(String v) { _formValues.put("threadsStep", v); }
    public void setThrottleRejectExponentDefault(String v) { _formValues.put("throttleRejectExponentDefault", v); }
    public void setThrottleRejectExponentMax(String v) { _formValues.put("throttleRejectExponentMax", v); }
    public void setThrottleRejectExponentMin(String v) { _formValues.put("throttleRejectExponentMin", v); }
    public void setThrottleRejectExponentOverride(String v) { _formValues.put("throttleRejectExponentOverride", v); }
    public void setThrottleRejectExponentStep(String v) { _formValues.put("throttleRejectExponentStep", v); }
    public void setTransitThrottleFactorDefault(String v) { _formValues.put("transitThrottleFactorDefault", v); }
    public void setTransitThrottleFactorMax(String v) { _formValues.put("transitThrottleFactorMax", v); }
    public void setTransitThrottleFactorMin(String v) { _formValues.put("transitThrottleFactorMin", v); }
    public void setTransitThrottleFactorOverride(String v) { _formValues.put("transitThrottleFactorOverride", v); }
    public void setTransitThrottleFactorStep(String v) { _formValues.put("transitThrottleFactorStep", v); }
    public void setTunnelGrowthFactorDefault(String v) { _formValues.put("tunnelGrowthFactorDefault", v); }
    public void setTunnelGrowthFactorMax(String v) { _formValues.put("tunnelGrowthFactorMax", v); }
    public void setTunnelGrowthFactorMin(String v) { _formValues.put("tunnelGrowthFactorMin", v); }
    public void setTunnelGrowthFactorOverride(String v) { _formValues.put("tunnelGrowthFactorOverride", v); }
    public void setTunnelGrowthFactorStep(String v) { _formValues.put("tunnelGrowthFactorStep", v); }
    public void setUdpHandlerThreadsDefault(String v) { _formValues.put("udpHandlerThreadsDefault", v); }
    public void setUdpHandlerThreadsMax(String v) { _formValues.put("udpHandlerThreadsMax", v); }
    public void setUdpHandlerThreadsMin(String v) { _formValues.put("udpHandlerThreadsMin", v); }
    public void setUdpHandlerThreadsOverride(String v) { _formValues.put("udpHandlerThreadsOverride", v); }
    public void setUdpHandlerThreadsStep(String v) { _formValues.put("udpHandlerThreadsStep", v); }
    public void setWestwoodDecayFactorDefault(String v) { _formValues.put("westwoodDecayFactorDefault", v); }
    public void setWestwoodDecayFactorMax(String v) { _formValues.put("westwoodDecayFactorMax", v); }
    public void setWestwoodDecayFactorMin(String v) { _formValues.put("westwoodDecayFactorMin", v); }
    public void setWestwoodDecayFactorOverride(String v) { _formValues.put("westwoodDecayFactorOverride", v); }
    public void setWestwoodDecayFactorStep(String v) { _formValues.put("westwoodDecayFactorStep", v); }
    public void setWriterQueueSizeDefault(String v) { _formValues.put("writerQueueSizeDefault", v); }
    public void setWriterQueueSizeMax(String v) { _formValues.put("writerQueueSizeMax", v); }
    public void setWriterQueueSizeMin(String v) { _formValues.put("writerQueueSizeMin", v); }
    public void setWriterQueueSizeOverride(String v) { _formValues.put("writerQueueSizeOverride", v); }
    public void setWriterQueueSizeStep(String v) { _formValues.put("writerQueueSizeStep", v); }
    public void setXdhPreCalcMinDefault(String v) { _formValues.put("xdhPreCalcMinDefault", v); }
    public void setXdhPreCalcMinMax(String v) { _formValues.put("xdhPreCalcMinMax", v); }
    public void setXdhPreCalcMinMin(String v) { _formValues.put("xdhPreCalcMinMin", v); }
    public void setXdhPreCalcMinOverride(String v) { _formValues.put("xdhPreCalcMinOverride", v); }
    public void setXdhPreCalcMinStep(String v) { _formValues.put("xdhPreCalcMinStep", v); }

    // Full-coverage params (all Tuner params exposed)
    public void setConnectTimeoutMultiplierDefault(String v) { _formValues.put("connectTimeoutMultiplierDefault", v); }
    public void setConnectTimeoutMultiplierMax(String v) { _formValues.put("connectTimeoutMultiplierMax", v); }
    public void setConnectTimeoutMultiplierMin(String v) { _formValues.put("connectTimeoutMultiplierMin", v); }
    public void setConnectTimeoutMultiplierOverride(String v) { _formValues.put("connectTimeoutMultiplierOverride", v); }
    public void setConnectTimeoutMultiplierStep(String v) { _formValues.put("connectTimeoutMultiplierStep", v); }
    public void setI2cpInternalqueuesizeDefault(String v) { _formValues.put("i2cpInternalqueuesizeDefault", v); }
    public void setI2cpInternalqueuesizeMax(String v) { _formValues.put("i2cpInternalqueuesizeMax", v); }
    public void setI2cpInternalqueuesizeMin(String v) { _formValues.put("i2cpInternalqueuesizeMin", v); }
    public void setI2cpInternalqueuesizeOverride(String v) { _formValues.put("i2cpInternalqueuesizeOverride", v); }
    public void setI2cpInternalqueuesizeStep(String v) { _formValues.put("i2cpInternalqueuesizeStep", v); }
    public void setI2pRouterHandlerthreadpriorityDefault(String v) { _formValues.put("i2pRouterHandlerthreadpriorityDefault", v); }
    public void setI2pRouterHandlerthreadpriorityMax(String v) { _formValues.put("i2pRouterHandlerthreadpriorityMax", v); }
    public void setI2pRouterHandlerthreadpriorityMin(String v) { _formValues.put("i2pRouterHandlerthreadpriorityMin", v); }
    public void setI2pRouterHandlerthreadpriorityOverride(String v) { _formValues.put("i2pRouterHandlerthreadpriorityOverride", v); }
    public void setI2pRouterHandlerthreadpriorityStep(String v) { _formValues.put("i2pRouterHandlerthreadpriorityStep", v); }
    public void setI2pRouterMaxdispatchageDefault(String v) { _formValues.put("i2pRouterMaxdispatchageDefault", v); }
    public void setI2pRouterMaxdispatchageMax(String v) { _formValues.put("i2pRouterMaxdispatchageMax", v); }
    public void setI2pRouterMaxdispatchageMin(String v) { _formValues.put("i2pRouterMaxdispatchageMin", v); }
    public void setI2pRouterMaxdispatchageOverride(String v) { _formValues.put("i2pRouterMaxdispatchageOverride", v); }
    public void setI2pRouterMaxdispatchageStep(String v) { _formValues.put("i2pRouterMaxdispatchageStep", v); }
    public void setI2pStreamingInactivitytimeoutDefault(String v) { _formValues.put("i2pStreamingInactivitytimeoutDefault", v); }
    public void setI2pStreamingInactivitytimeoutMax(String v) { _formValues.put("i2pStreamingInactivitytimeoutMax", v); }
    public void setI2pStreamingInactivitytimeoutMin(String v) { _formValues.put("i2pStreamingInactivitytimeoutMin", v); }
    public void setI2pStreamingInactivitytimeoutOverride(String v) { _formValues.put("i2pStreamingInactivitytimeoutOverride", v); }
    public void setI2pStreamingInactivitytimeoutStep(String v) { _formValues.put("i2pStreamingInactivitytimeoutStep", v); }
    public void setI2pStreamingMaxsynresendsDefault(String v) { _formValues.put("i2pStreamingMaxsynresendsDefault", v); }
    public void setI2pStreamingMaxsynresendsMax(String v) { _formValues.put("i2pStreamingMaxsynresendsMax", v); }
    public void setI2pStreamingMaxsynresendsMin(String v) { _formValues.put("i2pStreamingMaxsynresendsMin", v); }
    public void setI2pStreamingMaxsynresendsOverride(String v) { _formValues.put("i2pStreamingMaxsynresendsOverride", v); }
    public void setI2pStreamingMaxsynresendsStep(String v) { _formValues.put("i2pStreamingMaxsynresendsStep", v); }
    public void setI2pStreamingMaxwindowsizeDefault(String v) { _formValues.put("i2pStreamingMaxwindowsizeDefault", v); }
    public void setI2pStreamingMaxwindowsizeMax(String v) { _formValues.put("i2pStreamingMaxwindowsizeMax", v); }
    public void setI2pStreamingMaxwindowsizeMin(String v) { _formValues.put("i2pStreamingMaxwindowsizeMin", v); }
    public void setI2pStreamingMaxwindowsizeOverride(String v) { _formValues.put("i2pStreamingMaxwindowsizeOverride", v); }
    public void setI2pStreamingMaxwindowsizeStep(String v) { _formValues.put("i2pStreamingMaxwindowsizeStep", v); }
    public void setI2pStreamingMinpacingrateDefault(String v) { _formValues.put("i2pStreamingMinpacingrateDefault", v); }
    public void setI2pStreamingMinpacingrateMax(String v) { _formValues.put("i2pStreamingMinpacingrateMax", v); }
    public void setI2pStreamingMinpacingrateMin(String v) { _formValues.put("i2pStreamingMinpacingrateMin", v); }
    public void setI2pStreamingMinpacingrateOverride(String v) { _formValues.put("i2pStreamingMinpacingrateOverride", v); }
    public void setI2pStreamingMinpacingrateStep(String v) { _formValues.put("i2pStreamingMinpacingrateStep", v); }
    public void setI2pStreamingRtomultiplierDefault(String v) { _formValues.put("i2pStreamingRtomultiplierDefault", v); }
    public void setI2pStreamingRtomultiplierMax(String v) { _formValues.put("i2pStreamingRtomultiplierMax", v); }
    public void setI2pStreamingRtomultiplierMin(String v) { _formValues.put("i2pStreamingRtomultiplierMin", v); }
    public void setI2pStreamingRtomultiplierOverride(String v) { _formValues.put("i2pStreamingRtomultiplierOverride", v); }
    public void setI2pStreamingRtomultiplierStep(String v) { _formValues.put("i2pStreamingRtomultiplierStep", v); }
    public void setI2pTunnelBuildMaxlookuplimitDefault(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitDefault", v); }
    public void setI2pTunnelBuildMaxlookuplimitMax(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitMax", v); }
    public void setI2pTunnelBuildMaxlookuplimitMin(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitMin", v); }
    public void setI2pTunnelBuildMaxlookuplimitOverride(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitOverride", v); }
    public void setI2pTunnelBuildMaxlookuplimitStep(String v) { _formValues.put("i2pTunnelBuildMaxlookuplimitStep", v); }
    public void setI2pTunnelBuildPercentlookuplimitDefault(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitDefault", v); }
    public void setI2pTunnelBuildPercentlookuplimitMax(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitMax", v); }
    public void setI2pTunnelBuildPercentlookuplimitMin(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitMin", v); }
    public void setI2pTunnelBuildPercentlookuplimitOverride(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitOverride", v); }
    public void setI2pTunnelBuildPercentlookuplimitStep(String v) { _formValues.put("i2pTunnelBuildPercentlookuplimitStep", v); }
    public void setI2pTunnelParticipatingthrottleLoadweightDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightDefault", v); }
    public void setI2pTunnelParticipatingthrottleLoadweightMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightMax", v); }
    public void setI2pTunnelParticipatingthrottleLoadweightMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightMin", v); }
    public void setI2pTunnelParticipatingthrottleLoadweightOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightOverride", v); }
    public void setI2pTunnelParticipatingthrottleLoadweightStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleLoadweightStep", v); }
    public void setI2pTunnelParticipatingthrottleMaxlimitDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitDefault", v); }
    public void setI2pTunnelParticipatingthrottleMaxlimitMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitMax", v); }
    public void setI2pTunnelParticipatingthrottleMaxlimitMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitMin", v); }
    public void setI2pTunnelParticipatingthrottleMaxlimitOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitOverride", v); }
    public void setI2pTunnelParticipatingthrottleMaxlimitStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleMaxlimitStep", v); }
    public void setI2pTunnelParticipatingthrottleMinlimitDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitDefault", v); }
    public void setI2pTunnelParticipatingthrottleMinlimitMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitMax", v); }
    public void setI2pTunnelParticipatingthrottleMinlimitMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitMin", v); }
    public void setI2pTunnelParticipatingthrottleMinlimitOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitOverride", v); }
    public void setI2pTunnelParticipatingthrottleMinlimitStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleMinlimitStep", v); }
    public void setI2pTunnelParticipatingthrottlePercentlimitDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitDefault", v); }
    public void setI2pTunnelParticipatingthrottlePercentlimitMax(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitMax", v); }
    public void setI2pTunnelParticipatingthrottlePercentlimitMin(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitMin", v); }
    public void setI2pTunnelParticipatingthrottlePercentlimitOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitOverride", v); }
    public void setI2pTunnelParticipatingthrottlePercentlimitStep(String v) { _formValues.put("i2pTunnelParticipatingthrottlePercentlimitStep", v); }
    public void setI2pTunnelParticipatingthrottleRejectsteepnessDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessDefault", v); }
    public void setI2pTunnelParticipatingthrottleRejectsteepnessMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessMax", v); }
    public void setI2pTunnelParticipatingthrottleRejectsteepnessMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessMin", v); }
    public void setI2pTunnelParticipatingthrottleRejectsteepnessOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessOverride", v); }
    public void setI2pTunnelParticipatingthrottleRejectsteepnessStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectsteepnessStep", v); }
    public void setI2pTunnelParticipatingthrottleRejectthresholdDefault(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdDefault", v); }
    public void setI2pTunnelParticipatingthrottleRejectthresholdMax(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdMax", v); }
    public void setI2pTunnelParticipatingthrottleRejectthresholdMin(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdMin", v); }
    public void setI2pTunnelParticipatingthrottleRejectthresholdOverride(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdOverride", v); }
    public void setI2pTunnelParticipatingthrottleRejectthresholdStep(String v) { _formValues.put("i2pTunnelParticipatingthrottleRejectthresholdStep", v); }
    public void setI2pTunnelRequestthrottleBurst1sthresholdDefault(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdDefault", v); }
    public void setI2pTunnelRequestthrottleBurst1sthresholdMax(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdMax", v); }
    public void setI2pTunnelRequestthrottleBurst1sthresholdMin(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdMin", v); }
    public void setI2pTunnelRequestthrottleBurst1sthresholdOverride(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdOverride", v); }
    public void setI2pTunnelRequestthrottleBurst1sthresholdStep(String v) { _formValues.put("i2pTunnelRequestthrottleBurst1sthresholdStep", v); }
    public void setI2pTunnelRequestthrottleHighloadcpupctDefault(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctDefault", v); }
    public void setI2pTunnelRequestthrottleHighloadcpupctMax(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctMax", v); }
    public void setI2pTunnelRequestthrottleHighloadcpupctMin(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctMin", v); }
    public void setI2pTunnelRequestthrottleHighloadcpupctOverride(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctOverride", v); }
    public void setI2pTunnelRequestthrottleHighloadcpupctStep(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadcpupctStep", v); }
    public void setI2pTunnelRequestthrottleHighloadlagmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsDefault", v); }
    public void setI2pTunnelRequestthrottleHighloadlagmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsMax", v); }
    public void setI2pTunnelRequestthrottleHighloadlagmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsMin", v); }
    public void setI2pTunnelRequestthrottleHighloadlagmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsOverride", v); }
    public void setI2pTunnelRequestthrottleHighloadlagmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleHighloadlagmsStep", v); }
    public void setI2pTunnelRequestthrottleLoadweightDefault(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightDefault", v); }
    public void setI2pTunnelRequestthrottleLoadweightMax(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightMax", v); }
    public void setI2pTunnelRequestthrottleLoadweightMin(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightMin", v); }
    public void setI2pTunnelRequestthrottleLoadweightOverride(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightOverride", v); }
    public void setI2pTunnelRequestthrottleLoadweightStep(String v) { _formValues.put("i2pTunnelRequestthrottleLoadweightStep", v); }
    public void setI2pTunnelRequestthrottleMaxlimitDefault(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitDefault", v); }
    public void setI2pTunnelRequestthrottleMaxlimitMax(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitMax", v); }
    public void setI2pTunnelRequestthrottleMaxlimitMin(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitMin", v); }
    public void setI2pTunnelRequestthrottleMaxlimitOverride(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitOverride", v); }
    public void setI2pTunnelRequestthrottleMaxlimitStep(String v) { _formValues.put("i2pTunnelRequestthrottleMaxlimitStep", v); }
    public void setI2pTunnelRequestthrottleMinlimitDefault(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitDefault", v); }
    public void setI2pTunnelRequestthrottleMinlimitMax(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitMax", v); }
    public void setI2pTunnelRequestthrottleMinlimitMin(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitMin", v); }
    public void setI2pTunnelRequestthrottleMinlimitOverride(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitOverride", v); }
    public void setI2pTunnelRequestthrottleMinlimitStep(String v) { _formValues.put("i2pTunnelRequestthrottleMinlimitStep", v); }
    public void setI2pTunnelRequestthrottleModerateloadcpupctDefault(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctDefault", v); }
    public void setI2pTunnelRequestthrottleModerateloadcpupctMax(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctMax", v); }
    public void setI2pTunnelRequestthrottleModerateloadcpupctMin(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctMin", v); }
    public void setI2pTunnelRequestthrottleModerateloadcpupctOverride(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctOverride", v); }
    public void setI2pTunnelRequestthrottleModerateloadcpupctStep(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadcpupctStep", v); }
    public void setI2pTunnelRequestthrottleModerateloadlagmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsDefault", v); }
    public void setI2pTunnelRequestthrottleModerateloadlagmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsMax", v); }
    public void setI2pTunnelRequestthrottleModerateloadlagmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsMin", v); }
    public void setI2pTunnelRequestthrottleModerateloadlagmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsOverride", v); }
    public void setI2pTunnelRequestthrottleModerateloadlagmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleModerateloadlagmsStep", v); }
    public void setI2pTunnelRequestthrottlePercentlimitDefault(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitDefault", v); }
    public void setI2pTunnelRequestthrottlePercentlimitMax(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitMax", v); }
    public void setI2pTunnelRequestthrottlePercentlimitMin(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitMin", v); }
    public void setI2pTunnelRequestthrottlePercentlimitOverride(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitOverride", v); }
    public void setI2pTunnelRequestthrottlePercentlimitStep(String v) { _formValues.put("i2pTunnelRequestthrottlePercentlimitStep", v); }
    public void setI2pTunnelRequestthrottleRejectsteepnessDefault(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessDefault", v); }
    public void setI2pTunnelRequestthrottleRejectsteepnessMax(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessMax", v); }
    public void setI2pTunnelRequestthrottleRejectsteepnessMin(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessMin", v); }
    public void setI2pTunnelRequestthrottleRejectsteepnessOverride(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessOverride", v); }
    public void setI2pTunnelRequestthrottleRejectsteepnessStep(String v) { _formValues.put("i2pTunnelRequestthrottleRejectsteepnessStep", v); }
    public void setI2pTunnelRequestthrottleRejectthresholdDefault(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdDefault", v); }
    public void setI2pTunnelRequestthrottleRejectthresholdMax(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdMax", v); }
    public void setI2pTunnelRequestthrottleRejectthresholdMin(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdMin", v); }
    public void setI2pTunnelRequestthrottleRejectthresholdOverride(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdOverride", v); }
    public void setI2pTunnelRequestthrottleRejectthresholdStep(String v) { _formValues.put("i2pTunnelRequestthrottleRejectthresholdStep", v); }
    public void setI2pTunnelRequestthrottleSustainedhighloadmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsDefault", v); }
    public void setI2pTunnelRequestthrottleSustainedhighloadmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsMax", v); }
    public void setI2pTunnelRequestthrottleSustainedhighloadmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsMin", v); }
    public void setI2pTunnelRequestthrottleSustainedhighloadmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsOverride", v); }
    public void setI2pTunnelRequestthrottleSustainedhighloadmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedhighloadmsStep", v); }
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsDefault(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsDefault", v); }
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsMax(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsMax", v); }
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsMin(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsMin", v); }
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsOverride(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsOverride", v); }
    public void setI2pTunnelRequestthrottleSustainedmoderateloadmsStep(String v) { _formValues.put("i2pTunnelRequestthrottleSustainedmoderateloadmsStep", v); }
    public void setI2pTunnelSocketconnecttimeoutDefault(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutDefault", v); }
    public void setI2pTunnelSocketconnecttimeoutMax(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutMax", v); }
    public void setI2pTunnelSocketconnecttimeoutMin(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutMin", v); }
    public void setI2pTunnelSocketconnecttimeoutOverride(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutOverride", v); }
    public void setI2pTunnelSocketconnecttimeoutStep(String v) { _formValues.put("i2pTunnelSocketconnecttimeoutStep", v); }
    public void setI2pTunnelTargetbufferDefault(String v) { _formValues.put("i2pTunnelTargetbufferDefault", v); }
    public void setI2pTunnelTargetbufferMax(String v) { _formValues.put("i2pTunnelTargetbufferMax", v); }
    public void setI2pTunnelTargetbufferMin(String v) { _formValues.put("i2pTunnelTargetbufferMin", v); }
    public void setI2pTunnelTargetbufferOverride(String v) { _formValues.put("i2pTunnelTargetbufferOverride", v); }
    public void setI2pTunnelTargetbufferStep(String v) { _formValues.put("i2pTunnelTargetbufferStep", v); }
    public void setI2pTunnelUntestedmultiplierDefault(String v) { _formValues.put("i2pTunnelUntestedmultiplierDefault", v); }
    public void setI2pTunnelUntestedmultiplierMax(String v) { _formValues.put("i2pTunnelUntestedmultiplierMax", v); }
    public void setI2pTunnelUntestedmultiplierMin(String v) { _formValues.put("i2pTunnelUntestedmultiplierMin", v); }
    public void setI2pTunnelUntestedmultiplierOverride(String v) { _formValues.put("i2pTunnelUntestedmultiplierOverride", v); }
    public void setI2pTunnelUntestedmultiplierStep(String v) { _formValues.put("i2pTunnelUntestedmultiplierStep", v); }
    public void setI2ptunnelClientrunnerMaxDefault(String v) { _formValues.put("i2ptunnelClientrunnerMaxDefault", v); }
    public void setI2ptunnelClientrunnerMaxMax(String v) { _formValues.put("i2ptunnelClientrunnerMaxMax", v); }
    public void setI2ptunnelClientrunnerMaxMin(String v) { _formValues.put("i2ptunnelClientrunnerMaxMin", v); }
    public void setI2ptunnelClientrunnerMaxOverride(String v) { _formValues.put("i2ptunnelClientrunnerMaxOverride", v); }
    public void setI2ptunnelClientrunnerMaxStep(String v) { _formValues.put("i2ptunnelClientrunnerMaxStep", v); }
    public void setMaxLsLookupTimeDefault(String v) { _formValues.put("maxLsLookupTimeDefault", v); }
    public void setMaxLsLookupTimeMax(String v) { _formValues.put("maxLsLookupTimeMax", v); }
    public void setMaxLsLookupTimeMin(String v) { _formValues.put("maxLsLookupTimeMin", v); }
    public void setMaxLsLookupTimeOverride(String v) { _formValues.put("maxLsLookupTimeOverride", v); }
    public void setMaxLsLookupTimeStep(String v) { _formValues.put("maxLsLookupTimeStep", v); }
    public void setMaxRiLookupTimeDefault(String v) { _formValues.put("maxRiLookupTimeDefault", v); }
    public void setMaxRiLookupTimeMax(String v) { _formValues.put("maxRiLookupTimeMax", v); }
    public void setMaxRiLookupTimeMin(String v) { _formValues.put("maxRiLookupTimeMin", v); }
    public void setMaxRiLookupTimeOverride(String v) { _formValues.put("maxRiLookupTimeOverride", v); }
    public void setMaxRiLookupTimeStep(String v) { _formValues.put("maxRiLookupTimeStep", v); }
    public void setNtcpEstablishTimeoutDefault(String v) { _formValues.put("ntcpEstablishTimeoutDefault", v); }
    public void setNtcpEstablishTimeoutMax(String v) { _formValues.put("ntcpEstablishTimeoutMax", v); }
    public void setNtcpEstablishTimeoutMin(String v) { _formValues.put("ntcpEstablishTimeoutMin", v); }
    public void setNtcpEstablishTimeoutOverride(String v) { _formValues.put("ntcpEstablishTimeoutOverride", v); }
    public void setNtcpEstablishTimeoutStep(String v) { _formValues.put("ntcpEstablishTimeoutStep", v); }
    public void setNtcpFailsafeIterationfreqDefault(String v) { _formValues.put("ntcpFailsafeIterationfreqDefault", v); }
    public void setNtcpFailsafeIterationfreqMax(String v) { _formValues.put("ntcpFailsafeIterationfreqMax", v); }
    public void setNtcpFailsafeIterationfreqMin(String v) { _formValues.put("ntcpFailsafeIterationfreqMin", v); }
    public void setNtcpFailsafeIterationfreqOverride(String v) { _formValues.put("ntcpFailsafeIterationfreqOverride", v); }
    public void setNtcpFailsafeIterationfreqStep(String v) { _formValues.put("ntcpFailsafeIterationfreqStep", v); }
    public void setNtcpMaxwritebufsDefault(String v) { _formValues.put("ntcpMaxwritebufsDefault", v); }
    public void setNtcpMaxwritebufsMax(String v) { _formValues.put("ntcpMaxwritebufsMax", v); }
    public void setNtcpMaxwritebufsMin(String v) { _formValues.put("ntcpMaxwritebufsMin", v); }
    public void setNtcpMaxwritebufsOverride(String v) { _formValues.put("ntcpMaxwritebufsOverride", v); }
    public void setNtcpMaxwritebufsStep(String v) { _formValues.put("ntcpMaxwritebufsStep", v); }
    public void setNtcpReaderThreadsDefault(String v) { _formValues.put("ntcpReaderThreadsDefault", v); }
    public void setNtcpReaderThreadsMax(String v) { _formValues.put("ntcpReaderThreadsMax", v); }
    public void setNtcpReaderThreadsMin(String v) { _formValues.put("ntcpReaderThreadsMin", v); }
    public void setNtcpReaderThreadsOverride(String v) { _formValues.put("ntcpReaderThreadsOverride", v); }
    public void setNtcpReaderThreadsStep(String v) { _formValues.put("ntcpReaderThreadsStep", v); }
    public void setNtcpSendpoolCapacityDefault(String v) { _formValues.put("ntcpSendpoolCapacityDefault", v); }
    public void setNtcpSendpoolCapacityMax(String v) { _formValues.put("ntcpSendpoolCapacityMax", v); }
    public void setNtcpSendpoolCapacityMin(String v) { _formValues.put("ntcpSendpoolCapacityMin", v); }
    public void setNtcpSendpoolCapacityOverride(String v) { _formValues.put("ntcpSendpoolCapacityOverride", v); }
    public void setNtcpSendpoolCapacityStep(String v) { _formValues.put("ntcpSendpoolCapacityStep", v); }
    public void setNtcpWriterThreadsDefault(String v) { _formValues.put("ntcpWriterThreadsDefault", v); }
    public void setNtcpWriterThreadsMax(String v) { _formValues.put("ntcpWriterThreadsMax", v); }
    public void setNtcpWriterThreadsMin(String v) { _formValues.put("ntcpWriterThreadsMin", v); }
    public void setNtcpWriterThreadsOverride(String v) { _formValues.put("ntcpWriterThreadsOverride", v); }
    public void setNtcpWriterThreadsStep(String v) { _formValues.put("ntcpWriterThreadsStep", v); }
    public void setRdnsCorepoolsizeDefault(String v) { _formValues.put("rdnsCorepoolsizeDefault", v); }
    public void setRdnsCorepoolsizeMax(String v) { _formValues.put("rdnsCorepoolsizeMax", v); }
    public void setRdnsCorepoolsizeMin(String v) { _formValues.put("rdnsCorepoolsizeMin", v); }
    public void setRdnsCorepoolsizeOverride(String v) { _formValues.put("rdnsCorepoolsizeOverride", v); }
    public void setRdnsCorepoolsizeStep(String v) { _formValues.put("rdnsCorepoolsizeStep", v); }
    public void setRedMaxDropProbDefault(String v) { _formValues.put("redMaxDropProbDefault", v); }
    public void setRedMaxDropProbMax(String v) { _formValues.put("redMaxDropProbMax", v); }
    public void setRedMaxDropProbMin(String v) { _formValues.put("redMaxDropProbMin", v); }
    public void setRedMaxDropProbOverride(String v) { _formValues.put("redMaxDropProbOverride", v); }
    public void setRedMaxDropProbStep(String v) { _formValues.put("redMaxDropProbStep", v); }
    public void setRedMaxThresholdDefault(String v) { _formValues.put("redMaxThresholdDefault", v); }
    public void setRedMaxThresholdMax(String v) { _formValues.put("redMaxThresholdMax", v); }
    public void setRedMaxThresholdMin(String v) { _formValues.put("redMaxThresholdMin", v); }
    public void setRedMaxThresholdOverride(String v) { _formValues.put("redMaxThresholdOverride", v); }
    public void setRedMaxThresholdStep(String v) { _formValues.put("redMaxThresholdStep", v); }
    public void setRedMinThresholdDefault(String v) { _formValues.put("redMinThresholdDefault", v); }
    public void setRedMinThresholdMax(String v) { _formValues.put("redMinThresholdMax", v); }
    public void setRedMinThresholdMin(String v) { _formValues.put("redMinThresholdMin", v); }
    public void setRedMinThresholdOverride(String v) { _formValues.put("redMinThresholdOverride", v); }
    public void setRedMinThresholdStep(String v) { _formValues.put("redMinThresholdStep", v); }
    public void setRouterBuildhandlerthreadsDefault(String v) { _formValues.put("routerBuildhandlerthreadsDefault", v); }
    public void setRouterBuildhandlerthreadsMax(String v) { _formValues.put("routerBuildhandlerthreadsMax", v); }
    public void setRouterBuildhandlerthreadsMin(String v) { _formValues.put("routerBuildhandlerthreadsMin", v); }
    public void setRouterBuildhandlerthreadsOverride(String v) { _formValues.put("routerBuildhandlerthreadsOverride", v); }
    public void setRouterBuildhandlerthreadsStep(String v) { _formValues.put("routerBuildhandlerthreadsStep", v); }
    public void setRouterDefaultprocessingtimethrottleDefault(String v) { _formValues.put("routerDefaultprocessingtimethrottleDefault", v); }
    public void setRouterDefaultprocessingtimethrottleMax(String v) { _formValues.put("routerDefaultprocessingtimethrottleMax", v); }
    public void setRouterDefaultprocessingtimethrottleMin(String v) { _formValues.put("routerDefaultprocessingtimethrottleMin", v); }
    public void setRouterDefaultprocessingtimethrottleOverride(String v) { _formValues.put("routerDefaultprocessingtimethrottleOverride", v); }
    public void setRouterDefaultprocessingtimethrottleStep(String v) { _formValues.put("routerDefaultprocessingtimethrottleStep", v); }
    public void setTunnelBuildMaxconcurrentDefault(String v) { _formValues.put("tunnelBuildMaxconcurrentDefault", v); }
    public void setTunnelBuildMaxconcurrentMax(String v) { _formValues.put("tunnelBuildMaxconcurrentMax", v); }
    public void setTunnelBuildMaxconcurrentMin(String v) { _formValues.put("tunnelBuildMaxconcurrentMin", v); }
    public void setTunnelBuildMaxconcurrentOverride(String v) { _formValues.put("tunnelBuildMaxconcurrentOverride", v); }
    public void setTunnelBuildMaxconcurrentStep(String v) { _formValues.put("tunnelBuildMaxconcurrentStep", v); }
    public void setTunnelPeerselectionActivitywindowmultiplierDefault(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierDefault", v); }
    public void setTunnelPeerselectionActivitywindowmultiplierMax(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierMax", v); }
    public void setTunnelPeerselectionActivitywindowmultiplierMin(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierMin", v); }
    public void setTunnelPeerselectionActivitywindowmultiplierOverride(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierOverride", v); }
    public void setTunnelPeerselectionActivitywindowmultiplierStep(String v) { _formValues.put("tunnelPeerselectionActivitywindowmultiplierStep", v); }
    public void setTunnelPoolBackoffmsDefault(String v) { _formValues.put("tunnelPoolBackoffmsDefault", v); }
    public void setTunnelPoolBackoffmsMax(String v) { _formValues.put("tunnelPoolBackoffmsMax", v); }
    public void setTunnelPoolBackoffmsMin(String v) { _formValues.put("tunnelPoolBackoffmsMin", v); }
    public void setTunnelPoolBackoffmsOverride(String v) { _formValues.put("tunnelPoolBackoffmsOverride", v); }
    public void setTunnelPoolBackoffmsStep(String v) { _formValues.put("tunnelPoolBackoffmsStep", v); }
    public void setTunnelPoolFailurethresholdDefault(String v) { _formValues.put("tunnelPoolFailurethresholdDefault", v); }
    public void setTunnelPoolFailurethresholdMax(String v) { _formValues.put("tunnelPoolFailurethresholdMax", v); }
    public void setTunnelPoolFailurethresholdMin(String v) { _formValues.put("tunnelPoolFailurethresholdMin", v); }
    public void setTunnelPoolFailurethresholdOverride(String v) { _formValues.put("tunnelPoolFailurethresholdOverride", v); }
    public void setTunnelPoolFailurethresholdStep(String v) { _formValues.put("tunnelPoolFailurethresholdStep", v); }
    public void setTunnelPumperQueuecapacityDefault(String v) { _formValues.put("tunnelPumperQueuecapacityDefault", v); }
    public void setTunnelPumperQueuecapacityMax(String v) { _formValues.put("tunnelPumperQueuecapacityMax", v); }
    public void setTunnelPumperQueuecapacityMin(String v) { _formValues.put("tunnelPumperQueuecapacityMin", v); }
    public void setTunnelPumperQueuecapacityOverride(String v) { _formValues.put("tunnelPumperQueuecapacityOverride", v); }
    public void setTunnelPumperQueuecapacityStep(String v) { _formValues.put("tunnelPumperQueuecapacityStep", v); }
    public void setTunnelPumperThreadsDefault(String v) { _formValues.put("tunnelPumperThreadsDefault", v); }
    public void setTunnelPumperThreadsMax(String v) { _formValues.put("tunnelPumperThreadsMax", v); }
    public void setTunnelPumperThreadsMin(String v) { _formValues.put("tunnelPumperThreadsMin", v); }
    public void setTunnelPumperThreadsOverride(String v) { _formValues.put("tunnelPumperThreadsOverride", v); }
    public void setTunnelPumperThreadsStep(String v) { _formValues.put("tunnelPumperThreadsStep", v); }
    public void setTunnelTestjobMaxqueuedDefault(String v) { _formValues.put("tunnelTestjobMaxqueuedDefault", v); }
    public void setTunnelTestjobMaxqueuedMax(String v) { _formValues.put("tunnelTestjobMaxqueuedMax", v); }
    public void setTunnelTestjobMaxqueuedMin(String v) { _formValues.put("tunnelTestjobMaxqueuedMin", v); }
    public void setTunnelTestjobMaxqueuedOverride(String v) { _formValues.put("tunnelTestjobMaxqueuedOverride", v); }
    public void setTunnelTestjobMaxqueuedStep(String v) { _formValues.put("tunnelTestjobMaxqueuedStep", v); }
    public void setTunnelTestjobMaxtestdelayDefault(String v) { _formValues.put("tunnelTestjobMaxtestdelayDefault", v); }
    public void setTunnelTestjobMaxtestdelayMax(String v) { _formValues.put("tunnelTestjobMaxtestdelayMax", v); }
    public void setTunnelTestjobMaxtestdelayMin(String v) { _formValues.put("tunnelTestjobMaxtestdelayMin", v); }
    public void setTunnelTestjobMaxtestdelayOverride(String v) { _formValues.put("tunnelTestjobMaxtestdelayOverride", v); }
    public void setTunnelTestjobMaxtestdelayStep(String v) { _formValues.put("tunnelTestjobMaxtestdelayStep", v); }
    public void setTunnelTestjobMintestdelayDefault(String v) { _formValues.put("tunnelTestjobMintestdelayDefault", v); }
    public void setTunnelTestjobMintestdelayMax(String v) { _formValues.put("tunnelTestjobMintestdelayMax", v); }
    public void setTunnelTestjobMintestdelayMin(String v) { _formValues.put("tunnelTestjobMintestdelayMin", v); }
    public void setTunnelTestjobMintestdelayOverride(String v) { _formValues.put("tunnelTestjobMintestdelayOverride", v); }
    public void setTunnelTestjobMintestdelayStep(String v) { _formValues.put("tunnelTestjobMintestdelayStep", v); }
    public void setUdpEstablishMaxqueuedoutboundDefault(String v) { _formValues.put("udpEstablishMaxqueuedoutboundDefault", v); }
    public void setUdpEstablishMaxqueuedoutboundMax(String v) { _formValues.put("udpEstablishMaxqueuedoutboundMax", v); }
    public void setUdpEstablishMaxqueuedoutboundMin(String v) { _formValues.put("udpEstablishMaxqueuedoutboundMin", v); }
    public void setUdpEstablishMaxqueuedoutboundOverride(String v) { _formValues.put("udpEstablishMaxqueuedoutboundOverride", v); }
    public void setUdpEstablishMaxqueuedoutboundStep(String v) { _formValues.put("udpEstablishMaxqueuedoutboundStep", v); }
    public void setUdpMessagereceiverThreadsDefault(String v) { _formValues.put("udpMessagereceiverThreadsDefault", v); }
    public void setUdpMessagereceiverThreadsMax(String v) { _formValues.put("udpMessagereceiverThreadsMax", v); }
    public void setUdpMessagereceiverThreadsMin(String v) { _formValues.put("udpMessagereceiverThreadsMin", v); }
    public void setUdpMessagereceiverThreadsOverride(String v) { _formValues.put("udpMessagereceiverThreadsOverride", v); }
    public void setUdpMessagereceiverThreadsStep(String v) { _formValues.put("udpMessagereceiverThreadsStep", v); }
    public void setUdpPeerConcurrentmaxmessagesDefault(String v) { _formValues.put("udpPeerConcurrentmaxmessagesDefault", v); }
    public void setUdpPeerConcurrentmaxmessagesMax(String v) { _formValues.put("udpPeerConcurrentmaxmessagesMax", v); }
    public void setUdpPeerConcurrentmaxmessagesMin(String v) { _formValues.put("udpPeerConcurrentmaxmessagesMin", v); }
    public void setUdpPeerConcurrentmaxmessagesOverride(String v) { _formValues.put("udpPeerConcurrentmaxmessagesOverride", v); }
    public void setUdpPeerConcurrentmaxmessagesStep(String v) { _formValues.put("udpPeerConcurrentmaxmessagesStep", v); }
    public void setUdpPeerInitconcurrentmsgsDefault(String v) { _formValues.put("udpPeerInitconcurrentmsgsDefault", v); }
    public void setUdpPeerInitconcurrentmsgsMax(String v) { _formValues.put("udpPeerInitconcurrentmsgsMax", v); }
    public void setUdpPeerInitconcurrentmsgsMin(String v) { _formValues.put("udpPeerInitconcurrentmsgsMin", v); }
    public void setUdpPeerInitconcurrentmsgsOverride(String v) { _formValues.put("udpPeerInitconcurrentmsgsOverride", v); }
    public void setUdpPeerInitconcurrentmsgsStep(String v) { _formValues.put("udpPeerInitconcurrentmsgsStep", v); }
    public void setUdpPeerInitrtoDefault(String v) { _formValues.put("udpPeerInitrtoDefault", v); }
    public void setUdpPeerInitrtoMax(String v) { _formValues.put("udpPeerInitrtoMax", v); }
    public void setUdpPeerInitrtoMin(String v) { _formValues.put("udpPeerInitrtoMin", v); }
    public void setUdpPeerInitrtoOverride(String v) { _formValues.put("udpPeerInitrtoOverride", v); }
    public void setUdpPeerInitrtoStep(String v) { _formValues.put("udpPeerInitrtoStep", v); }
    public void setUdpPeerMaxrtoDefault(String v) { _formValues.put("udpPeerMaxrtoDefault", v); }
    public void setUdpPeerMaxrtoMax(String v) { _formValues.put("udpPeerMaxrtoMax", v); }
    public void setUdpPeerMaxrtoMin(String v) { _formValues.put("udpPeerMaxrtoMin", v); }
    public void setUdpPeerMaxrtoOverride(String v) { _formValues.put("udpPeerMaxrtoOverride", v); }
    public void setUdpPeerMaxrtoStep(String v) { _formValues.put("udpPeerMaxrtoStep", v); }
    public void setUdpPeerMaxsendwindowDefault(String v) { _formValues.put("udpPeerMaxsendwindowDefault", v); }
    public void setUdpPeerMaxsendwindowMax(String v) { _formValues.put("udpPeerMaxsendwindowMax", v); }
    public void setUdpPeerMaxsendwindowMin(String v) { _formValues.put("udpPeerMaxsendwindowMin", v); }
    public void setUdpPeerMaxsendwindowOverride(String v) { _formValues.put("udpPeerMaxsendwindowOverride", v); }
    public void setUdpPeerMaxsendwindowStep(String v) { _formValues.put("udpPeerMaxsendwindowStep", v); }
    public void setUdpPeerMinconcurrentmsgsDefault(String v) { _formValues.put("udpPeerMinconcurrentmsgsDefault", v); }
    public void setUdpPeerMinconcurrentmsgsMax(String v) { _formValues.put("udpPeerMinconcurrentmsgsMax", v); }
    public void setUdpPeerMinconcurrentmsgsMin(String v) { _formValues.put("udpPeerMinconcurrentmsgsMin", v); }
    public void setUdpPeerMinconcurrentmsgsOverride(String v) { _formValues.put("udpPeerMinconcurrentmsgsOverride", v); }
    public void setUdpPeerMinconcurrentmsgsStep(String v) { _formValues.put("udpPeerMinconcurrentmsgsStep", v); }
    public void setUdpPeerMinrtoDefault(String v) { _formValues.put("udpPeerMinrtoDefault", v); }
    public void setUdpPeerMinrtoMax(String v) { _formValues.put("udpPeerMinrtoMax", v); }
    public void setUdpPeerMinrtoMin(String v) { _formValues.put("udpPeerMinrtoMin", v); }
    public void setUdpPeerMinrtoOverride(String v) { _formValues.put("udpPeerMinrtoOverride", v); }
    public void setUdpPeerMinrtoStep(String v) { _formValues.put("udpPeerMinrtoStep", v); }
    public void setUdpPeerOutboundmsgexpirationDefault(String v) { _formValues.put("udpPeerOutboundmsgexpirationDefault", v); }
    public void setUdpPeerOutboundmsgexpirationMax(String v) { _formValues.put("udpPeerOutboundmsgexpirationMax", v); }
    public void setUdpPeerOutboundmsgexpirationMin(String v) { _formValues.put("udpPeerOutboundmsgexpirationMin", v); }
    public void setUdpPeerOutboundmsgexpirationOverride(String v) { _formValues.put("udpPeerOutboundmsgexpirationOverride", v); }
    public void setUdpPeerOutboundmsgexpirationStep(String v) { _formValues.put("udpPeerOutboundmsgexpirationStep", v); }
    public void setUdpPeerPostrtowindowmtusDefault(String v) { _formValues.put("udpPeerPostrtowindowmtusDefault", v); }
    public void setUdpPeerPostrtowindowmtusMax(String v) { _formValues.put("udpPeerPostrtowindowmtusMax", v); }
    public void setUdpPeerPostrtowindowmtusMin(String v) { _formValues.put("udpPeerPostrtowindowmtusMin", v); }
    public void setUdpPeerPostrtowindowmtusOverride(String v) { _formValues.put("udpPeerPostrtowindowmtusOverride", v); }
    public void setUdpPeerPostrtowindowmtusStep(String v) { _formValues.put("udpPeerPostrtowindowmtusStep", v); }
    public void setUdpPeerSentmessagescleantimeDefault(String v) { _formValues.put("udpPeerSentmessagescleantimeDefault", v); }
    public void setUdpPeerSentmessagescleantimeMax(String v) { _formValues.put("udpPeerSentmessagescleantimeMax", v); }
    public void setUdpPeerSentmessagescleantimeMin(String v) { _formValues.put("udpPeerSentmessagescleantimeMin", v); }
    public void setUdpPeerSentmessagescleantimeOverride(String v) { _formValues.put("udpPeerSentmessagescleantimeOverride", v); }
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
