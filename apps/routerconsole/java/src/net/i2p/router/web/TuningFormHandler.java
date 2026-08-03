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
