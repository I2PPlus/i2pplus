package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Pattern;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Tuner;
import net.i2p.router.Tuner.ParamSnapshot;
import net.i2p.router.transport.Transport;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.router.web.HelperBase;

/**
 * Helper for the transport tuning page (tuning.jsp).
 * Renders tunable param tables with inline SVG sparklines.
 *
 * @since 0.9.70+
 */
public class TuningHelper extends HelperBase {

    private static final int SPARK_W = 140;
    private static final int SPARK_H = 36;
    private static final Pattern UNDERSCORE_SPLIT = Pattern.compile("_");
    private String _nonce;

    public void setNonce(String nonce) { _nonce = nonce; }

    // human-readable labels for raw param names
    private static final Map<String, String> DISPLAY_NAMES = new HashMap<>();
    static {
        DISPLAY_NAMES.put("ACK_FREQUENCY", _x("Acknowledgement Frequency"));
        DISPLAY_NAMES.put("DATA_MESSAGE_TIMEOUT", _x("Data Message Timeout"));
        DISPLAY_NAMES.put("MAX_OB_ESTABLISH_TIME", _x("Outbound Establish Timeout"));
        DISPLAY_NAMES.put("MAX_IB_ESTABLISH_TIME", _x("Inbound Establish Timeout"));
        DISPLAY_NAMES.put("NTCP_ESTABLISH_TIMEOUT", _x("NTCP Establish Timeout"));
        DISPLAY_NAMES.put("REQUEUE_TIME", _x("Tunnel Requeue Delay"));
        DISPLAY_NAMES.put("REPLENISH_FREQUENCY", _x("Tunnel Replenish Frequency"));
        DISPLAY_NAMES.put("SELECTOR_LOOP_DELAY", _x("Tunnel Selector Loop Delay"));
        DISPLAY_NAMES.put("MAX_OB_MSGS_PER_PUMP", _x("Outbound Messages Per Pump"));
        DISPLAY_NAMES.put("MAX_IB_MSGS_PER_PUMP", _x("Inbound Messages Per Pump"));
        DISPLAY_NAMES.put("INITIAL_WINDOW_SIZE", _x("Initial Congestion Window"));
        DISPLAY_NAMES.put("INITIAL_RTO", _x("Initial RTO"));
        DISPLAY_NAMES.put("INITIAL_ACK_DELAY", _x("ACK Delay"));
        DISPLAY_NAMES.put("PASSIVE_FLUSH_DELAY", _x("Nagle Flush Delay"));
        DISPLAY_NAMES.put("i2p.streaming.maxSlowStartWindow", _x("Slow Start Window Cap"));
        DISPLAY_NAMES.put("i2p.streaming.maxSynResends", _x("Max SYN Sends"));
        DISPLAY_NAMES.put("i2p.streaming.maxRTO", _x("Max RTO"));
        DISPLAY_NAMES.put("i2p.streaming.maxResendDelay", _x("Max Resend Delay"));
        DISPLAY_NAMES.put("i2p.streaming.maxRetransmissions", _x("Max Retransmissions"));
        DISPLAY_NAMES.put("CLIENT_WRITER_QUEUE_SIZE", _x("Writer Queue Size"));
        DISPLAY_NAMES.put("CODEL_TARGET", _x("CoDel Target Delay"));
        DISPLAY_NAMES.put("CODEL_INTERVAL", _x("CoDel Interval"));
        DISPLAY_NAMES.put("WESTWOOD_DECAY_FACTOR", _x("Westwood Decay Factor"));
        DISPLAY_NAMES.put("RED_MIN_THRESHOLD", _x("RED Minimum Queue Threshold"));
        DISPLAY_NAMES.put("RED_MAX_THRESHOLD", _x("RED Maximum Queue Threshold"));
        DISPLAY_NAMES.put("RED_MAX_DROP_PROB", _x("RED Maximum Drop Probability"));
        DISPLAY_NAMES.put("crypto.x25519.precalcMin", _x("X25519 Precalculate Minimum"));
        DISPLAY_NAMES.put("crypto.edh.precalcMin", _x("EDH Precalculate Minimum"));
        DISPLAY_NAMES.put("crypto.mlkem.precalcMin", _x("ML-KEM Precalculate Minimum"));
        DISPLAY_NAMES.put("ntcp.sendFinisher.maxThreads", _x("NTCP Maximum Send Threads"));
        DISPLAY_NAMES.put("ntcp.sendFinisher.threads", _x("NTCP Send Finisher Threads"));
        DISPLAY_NAMES.put("ntcp.sendFinisher.queueCapacity", _x("NTCP Send Queue Capacity"));
        DISPLAY_NAMES.put("udp.messageReceiver.threads", _x("UDP Message Receiver Threads"));
        DISPLAY_NAMES.put("udp.packetHandler.maxThreads", _x("UDP Maximum Handler Threads"));
        DISPLAY_NAMES.put("rdns.corePoolSize", _x("rDNS Executor Threads"));
        DISPLAY_NAMES.put("router.peerOutboundQueueSize", _x("Peer Outbound Queue Size"));
        DISPLAY_NAMES.put("router.transitThrottleFactor", _x("Transit Throttle Factor"));
        DISPLAY_NAMES.put("router.throttleRejectExponent", _x("Throttle Reject Exponent"));
        DISPLAY_NAMES.put("router.maxParticipatingTunnels", _x("Maximum Participating Tunnels"));
        DISPLAY_NAMES.put("router.buildHandlerMaxQueue", _x("Build Handler Maximum Queue"));
        DISPLAY_NAMES.put("i2p.tunnel.goodDeficitThrottle", _x("Rebuild Throttle Interval"));
        DISPLAY_NAMES.put("router.tunnel.perTunnelBweDivisor", _x("Per-Tunnel Bandwidth Divisor"));
        DISPLAY_NAMES.put("router.tunnelGrowthFactor", _x("Tunnel Growth Tolerance"));
        DISPLAY_NAMES.put("netdb.searchLimit", _x("Peers Per Search"));
        DISPLAY_NAMES.put("netdb.maxConcurrent", _x("Max Concurrent Searches"));
        DISPLAY_NAMES.put("netdb.singleSearchTime", _x("Search Timeout"));
        DISPLAY_NAMES.put("MAX_LS_LOOKUP_TIME", _x("LeaseSet Lookup Timeout"));
        DISPLAY_NAMES.put("MAX_RI_LOOKUP_TIME", _x("RouterInfo Lookup Timeout"));
        DISPLAY_NAMES.put("i2np.udp.maxConcurrentEstablish", _x("Max Concurrent Handshakes"));
        DISPLAY_NAMES.put("profileOrganizer.maxProfiles", _x("Maximum Peer Profiles"));
        DISPLAY_NAMES.put("profileOrganizer.minFastPeers", _x("Minimum Fast Peers"));
        DISPLAY_NAMES.put("profileOrganizer.maxFastPeers", _x("Maximum Fast Peers"));
        DISPLAY_NAMES.put("profileOrganizer.minHighCapacityPeers", _x("Minimum High Capacity Peers"));
        DISPLAY_NAMES.put("profileOrganizer.maxHighCapacityPeers", _x("Maximum High Capacity Peers"));
        DISPLAY_NAMES.put("i2p.tunnel.build.requestTimeout", _x("Tunnel Build Request Timeout"));
        DISPLAY_NAMES.put("i2p.tunnel.build.firstHopTimeout", _x("Tunnel Build First Hop Timeout"));
        DISPLAY_NAMES.put("tunnel.build.maxConcurrent", _x("Max Concurrent Tunnel Builds"));
        DISPLAY_NAMES.put("i2p.tunnel.build.maxLookupLimit", _x("Max Concurrent RI Lookups"));
        DISPLAY_NAMES.put("i2p.tunnel.build.percentLookupLimit", _x("Next-Hop Lookup %"));
        DISPLAY_NAMES.put("tunnel.testJob.maxQueued", _x("Max Concurrent Test Jobs"));
        DISPLAY_NAMES.put("tunnel.testJob.minTestDelay", _x("Min Delay Between Tests"));
        DISPLAY_NAMES.put("tunnel.testJob.maxTestDelay", _x("Max Delay Between Tests"));
        DISPLAY_NAMES.put("ntcp.reader.threads", _x("NTCP Reader Threads"));
        DISPLAY_NAMES.put("ntcp.writer.threads", _x("NTCP Writer Threads"));
        DISPLAY_NAMES.put("ntcp.failsafe.iterationFreq", _x("NTCP Pumper Failsafe Interval"));
        DISPLAY_NAMES.put("udp.peer.concurrentMaxMessages", _x("Max Concurrent Peer Messages"));
        DISPLAY_NAMES.put("udp.peer.initConcurrentMsgs", _x("Initial UDP Concurrent Messages"));
        DISPLAY_NAMES.put("udp.peer.minConcurrentMsgs", _x("Min UDP Concurrent Messages"));
        DISPLAY_NAMES.put("udp.peer.initRTO", _x("Initial UDP RTO"));
        DISPLAY_NAMES.put("udp.peer.minRTO", _x("Min UDP RTO"));
        DISPLAY_NAMES.put("udp.peer.maxRTO", _x("Max UDP RTO"));
        DISPLAY_NAMES.put("udp.peer.maxSendWindow", _x("Max UDP Send Window"));
        DISPLAY_NAMES.put("udp.peer.postRTOWindowMTUs", _x("Post-RTO Window Restart"));
        DISPLAY_NAMES.put("ntcp.sendPool.capacity", _x("NTCP Send Pool Capacity"));
        DISPLAY_NAMES.put("i2cp.internalQueueSize", _x("Internal Queue Size"));
        DISPLAY_NAMES.put("udp.peer.sentMessagesCleanTime", _x("Sent Messages Clean Time"));
        DISPLAY_NAMES.put("udp.peer.outboundMsgExpiration", _x("Outbound Message Expiration"));
        DISPLAY_NAMES.put("udp.establish.maxQueuedOutbound", _x("Max Pending Handshakes"));
        DISPLAY_NAMES.put("ntcp.maxWriteBufs", _x("NTCP Max Write Buffers Per Connection"));
        DISPLAY_NAMES.put("i2p.streaming.minResendDelay", _x("Min Resend Delay"));
        DISPLAY_NAMES.put("i2p.streaming.congestionAvoidanceGrowthRateFactor", _x("Congestion Avoidance Growth Rate"));
        DISPLAY_NAMES.put("i2p.streaming.slowStartGrowthRateFactor", _x("Slow Start Growth Rate"));
        DISPLAY_NAMES.put("i2p.streaming.maxRtt", _x("RTT Cap"));
        DISPLAY_NAMES.put("i2p.streaming.initialResendDelay", _x("Initial Resend Delay"));
        DISPLAY_NAMES.put("i2p.streaming.immediateAckDelay", _x("Dup ACK Delay"));
        DISPLAY_NAMES.put("i2p.streaming.inactivityTimeout", _x("Inactivity Timeout"));
        DISPLAY_NAMES.put("i2p.router.maxDispatchAge", _x("Max Message Queue Age"));
        DISPLAY_NAMES.put("i2p.router.handlerThreadPriority", _x("I/O Thread Priority"));
        DISPLAY_NAMES.put("tunnel.pumper.queueCapacity", _x("Pumper Queue Capacity"));
        DISPLAY_NAMES.put("tunnel.pumper.threads", _x("Pumper Threads"));
        DISPLAY_NAMES.put("i2ptunnel.serverHandler.threads", _x("Server Handler Threads"));
        DISPLAY_NAMES.put("router.buildHandlerThreads", _x("Build Handler Threads"));
        DISPLAY_NAMES.put("i2ptunnel.clientRunner.max", _x("Client Runner Max Threads"));
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.minLimit", _x("Participating Throttle Min"));
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.maxLimit", _x("Participating Throttle Max"));
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.percentLimit", _x("Participating Throttle Percent"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.minLimit", _x("Request Throttle Min"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.maxLimit", _x("Request Throttle Max"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.percentLimit", _x("Request Throttle Percent"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.burst1sThreshold", _x("Request Throttle Burst Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.highLoadLagMs", _x("High-Load Lag Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.highLoadCpuPct", _x("High-Load CPU Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.moderateLoadLagMs", _x("Moderate-Load Lag Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.moderateLoadCpuPct", _x("Moderate-Load CPU Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.sustainedHighLoadMs", _x("Sustained High-Load Window"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.sustainedModerateLoadMs", _x("Sustained Moderate-Load Window"));
        DISPLAY_NAMES.put("tunnel.peerSelection.activityWindowMultiplier", _x("Peer Activity Window Multiplier"));
        DISPLAY_NAMES.put("tunnel.pool.failureThreshold", _x("Pool Build Failure Threshold"));
        DISPLAY_NAMES.put("tunnel.pool.backoffMs", _x("Pool Build Backoff"));
        DISPLAY_NAMES.put("i2p.tunnel.targetBuffer", _x("Pool Spare Tunnel Buffer"));
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.rejectThreshold", _x("Transit Reject Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.rejectSteepness", _x("Transit Reject Steepness"));
        DISPLAY_NAMES.put("i2p.tunnel.participatingThrottle.loadWeight", _x("Transit Load Weight"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.rejectThreshold", _x("Request Reject Threshold"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.rejectSteepness", _x("Request Reject Steepness"));
        DISPLAY_NAMES.put("i2p.tunnel.requestThrottle.loadWeight", _x("Request Load Weight"));
        DISPLAY_NAMES.put("i2p.streaming.rtoMultiplier", _x("RTO Backoff Multiplier"));
        DISPLAY_NAMES.put("i2p.tunnel.socketConnectTimeout", _x("Socket Connect Timeout"));
        DISPLAY_NAMES.put("i2p.tunnel.untestedMultiplier", _x("Untested Tunnel Cap Multiplier"));
        DISPLAY_NAMES.put("router.defaultProcessingTimeThrottle", _x("Message Processing Throttle"));
    }

    // brief purpose descriptions (<=120 chars)
    private static final Map<String, String> PARAM_DESCRIPTIONS = new HashMap<>();
    static {
        PARAM_DESCRIPTIONS.put("ACK_FREQUENCY", _x("Data packets between each ACK."));
        PARAM_DESCRIPTIONS.put("DATA_MESSAGE_TIMEOUT", _x("Time before a message is declared lost (ms)."));
        PARAM_DESCRIPTIONS.put("MAX_OB_ESTABLISH_TIME", _x("Outbound SSU handshake timeout (ms)."));
        PARAM_DESCRIPTIONS.put("MAX_IB_ESTABLISH_TIME", _x("Inbound SSU handshake timeout (ms)."));
        PARAM_DESCRIPTIONS.put("NTCP_ESTABLISH_TIMEOUT", _x("NTCP2 handshake timeout (ms)."));
        PARAM_DESCRIPTIONS.put("REQUEUE_TIME", _x("Pumper idle wait before re-checking work (ms)."));
        PARAM_DESCRIPTIONS.put("REPLENISH_FREQUENCY", _x("Bandwidth token refill interval (ms)."));
        PARAM_DESCRIPTIONS.put("SELECTOR_LOOP_DELAY", _x("NTCP selector sleep between loops (ms)."));
        PARAM_DESCRIPTIONS.put("MAX_OB_MSGS_PER_PUMP", _x("Outbound messages batched per gateway pump."));
        PARAM_DESCRIPTIONS.put("MAX_IB_MSGS_PER_PUMP", _x("Inbound messages batched per gateway pump."));
        PARAM_DESCRIPTIONS.put("INITIAL_WINDOW_SIZE", _x("Starting congestion window (packets)."));
        PARAM_DESCRIPTIONS.put("INITIAL_RTO", _x("Starting retransmission timeout (ms)."));
        PARAM_DESCRIPTIONS.put("INITIAL_ACK_DELAY", _x("ACK delay for piggybacking (ms)."));
        PARAM_DESCRIPTIONS.put("PASSIVE_FLUSH_DELAY", _x("Nagle flush delay (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxSlowStartWindow", _x("Max congestion window during slow start."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxSynResends", _x("Total SYN attempts before giving up on a handshake."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRTO", _x("Max retransmission timeout (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxResendDelay", _x("Max time between retransmissions (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRetransmissions", _x("Retransmissions before dropping the stream."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.inactivityTimeout", _x("Max idle time before dropping a stream (ms)."));
        PARAM_DESCRIPTIONS.put("CLIENT_WRITER_QUEUE_SIZE", _x("I2CP write queue depth."));
        PARAM_DESCRIPTIONS.put("CODEL_TARGET", _x("CoDel drop target delay (ms)."));
        PARAM_DESCRIPTIONS.put("CODEL_INTERVAL", _x("CoDel measurement window (ms)."));
        PARAM_DESCRIPTIONS.put("WESTWOOD_DECAY_FACTOR", _x("Westwood EWMA smoothing factor."));
        PARAM_DESCRIPTIONS.put("RED_MIN_THRESHOLD", _x("Min RED queue size before probabilistic drops (bytes)."));
        PARAM_DESCRIPTIONS.put("RED_MAX_THRESHOLD", _x("Max RED queue size for full drops (bytes)."));
        PARAM_DESCRIPTIONS.put("RED_MAX_DROP_PROB", _x("Max RED drop probability when queue is full (ppm)."));
        PARAM_DESCRIPTIONS.put("crypto.x25519.precalcMin", _x("Precomputed X25519 key pool minimum."));
        PARAM_DESCRIPTIONS.put("crypto.edh.precalcMin", _x("Precomputed EDH key pool minimum."));
        PARAM_DESCRIPTIONS.put("crypto.mlkem.precalcMin", _x("Precomputed ML-KEM key pool minimum."));
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.maxThreads", _x("Thread pool cap for NTCP send completion."));
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.threads", _x("Active threads completing NTCP sends."));
        PARAM_DESCRIPTIONS.put("ntcp.sendFinisher.queueCapacity", _x("NTCP send finalizer queue size."));
        PARAM_DESCRIPTIONS.put("udp.packetHandler.maxThreads", _x("Thread pool cap for UDP packet dispatch."));
        PARAM_DESCRIPTIONS.put("udp.messageReceiver.threads", _x("Threads reassembling UDP messages from fragments."));
        PARAM_DESCRIPTIONS.put("rdns.corePoolSize", _x("Threads performing reverse DNS lookups."));
        PARAM_DESCRIPTIONS.put("router.peerOutboundQueueSize", _x("Per-peer outbound queue size."));
        PARAM_DESCRIPTIONS.put("router.transitThrottleFactor", _x("Transit rejection aggressiveness (50-100%)."));
        PARAM_DESCRIPTIONS.put("router.throttleRejectExponent", _x("Steepness of the transit rejection curve."));
        PARAM_DESCRIPTIONS.put("router.maxParticipatingTunnels", _x("Transit tunnel capacity."));
        PARAM_DESCRIPTIONS.put("router.buildHandlerMaxQueue", _x("Max queued build requests."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.goodDeficitThrottle", _x("Delay between rebuild rounds when pools are healthy (ms)."));
        PARAM_DESCRIPTIONS.put("router.tunnel.perTunnelBweDivisor", _x("Divides bandwidth among transit tunnels."));
        PARAM_DESCRIPTIONS.put("router.tunnelGrowthFactor", _x("How aggressively new tunnels are accepted before throttling."));
        PARAM_DESCRIPTIONS.put("netdb.searchLimit", _x("Peers queried per iterative search."));
        PARAM_DESCRIPTIONS.put("netdb.maxConcurrent", _x("Max simultaneous NetDB lookups."));
        PARAM_DESCRIPTIONS.put("netdb.singleSearchTime", _x("Per-peer lookup timeout (ms)."));
        PARAM_DESCRIPTIONS.put("MAX_LS_LOOKUP_TIME", _x("Adaptive deadline cap for LeaseSet lookups (ms)."));
        PARAM_DESCRIPTIONS.put("MAX_RI_LOOKUP_TIME", _x("Adaptive deadline cap for RouterInfo lookups (ms)."));
        PARAM_DESCRIPTIONS.put("i2np.udp.maxConcurrentEstablish", _x("Max simultaneous SSU handshakes."));
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxProfiles", _x("Memory cap for peer profiles."));
        PARAM_DESCRIPTIONS.put("profileOrganizer.minFastPeers", _x("Min fast peers in routing table."));
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxFastPeers", _x("Max fast peers in routing table."));
        PARAM_DESCRIPTIONS.put("profileOrganizer.minHighCapacityPeers", _x("Min high-capacity peers in routing table."));
        PARAM_DESCRIPTIONS.put("profileOrganizer.maxHighCapacityPeers", _x("Max high-capacity peers in routing table."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.requestTimeout", _x("Build reply timeout (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.firstHopTimeout", _x("First-hop build forward timeout (ms)."));
        PARAM_DESCRIPTIONS.put("tunnel.build.maxConcurrent", _x("Max concurrent tunnel builds."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.maxLookupLimit", _x("Max concurrent RI lookups during builds."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.build.percentLookupLimit", _x("Concurrent next-hop lookup limit as % of transit tunnels."));
        PARAM_DESCRIPTIONS.put("tunnel.testJob.maxQueued", _x("Max concurrent tunnel test jobs."));
        PARAM_DESCRIPTIONS.put("tunnel.testJob.minTestDelay", _x("Min interval between tunnel tests (ms)."));
        PARAM_DESCRIPTIONS.put("tunnel.testJob.maxTestDelay", _x("Max interval between tunnel tests (ms)."));
        PARAM_DESCRIPTIONS.put("ntcp.reader.threads", _x("Threads decrypting inbound NTCP data."));
        PARAM_DESCRIPTIONS.put("ntcp.writer.threads", _x("Threads encrypting NTCP messages."));
        PARAM_DESCRIPTIONS.put("ntcp.failsafe.iterationFreq", _x("Failsafe scan interval for stuck NTCP pumps (ms)."));
        PARAM_DESCRIPTIONS.put("udp.peer.concurrentMaxMessages", _x("In-flight message limit per UDP peer."));
        PARAM_DESCRIPTIONS.put("udp.peer.initConcurrentMsgs", _x("Initial concurrent messages per peer."));
        PARAM_DESCRIPTIONS.put("udp.peer.minConcurrentMsgs", _x("Min concurrent messages per peer."));
        PARAM_DESCRIPTIONS.put("udp.peer.initRTO", _x("Initial retransmission timeout (ms)."));
        PARAM_DESCRIPTIONS.put("udp.peer.minRTO", _x("Min retransmission timeout (ms)."));
        PARAM_DESCRIPTIONS.put("udp.peer.maxRTO", _x("Max retransmission timeout (ms)."));
        PARAM_DESCRIPTIONS.put("udp.peer.maxSendWindow", _x("Max unacknowledged messages per peer."));
        PARAM_DESCRIPTIONS.put("udp.peer.postRTOWindowMTUs", _x("Window restart size (MTUs) after RTO collapse."));
        PARAM_DESCRIPTIONS.put("ntcp.sendPool.capacity", _x("NTCP send pool queue size."));
        PARAM_DESCRIPTIONS.put("i2cp.internalQueueSize", _x("I2CP session buffer size."));
        PARAM_DESCRIPTIONS.put("udp.peer.sentMessagesCleanTime", _x("Interval between sweeps of ACKed sent-messages."));
        PARAM_DESCRIPTIONS.put("udp.peer.outboundMsgExpiration", _x("Max age for undelivered outbound messages."));
        PARAM_DESCRIPTIONS.put("udp.establish.maxQueuedOutbound", _x("Pending outbound handshake queue."));
        PARAM_DESCRIPTIONS.put("ntcp.maxWriteBufs", _x("Write buffer per NTCP connection."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.minResendDelay", _x("Min time between retransmissions (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.congestionAvoidanceGrowthRateFactor", _x("Congestion avoidance growth rate."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.slowStartGrowthRateFactor", _x("Window multiplier per RTT during slow start."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.maxRtt", _x("Upper bound on RTT estimate (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.initialResendDelay", _x("Delay before first retransmit (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.immediateAckDelay", _x("Delay for dup or OOO packet ACKs (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.router.maxDispatchAge", _x("Max message age in queue before drop (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.router.handlerThreadPriority", _x("Dynamic priority for I/O handler threads."));
        PARAM_DESCRIPTIONS.put("tunnel.pumper.queueCapacity", _x("Gateway pumper queue size."));
        PARAM_DESCRIPTIONS.put("tunnel.pumper.threads", _x("Gateway pumper thread count."));
        PARAM_DESCRIPTIONS.put("i2ptunnel.serverHandler.threads", _x("Handler threads for incoming I2PTunnel connections."));
        PARAM_DESCRIPTIONS.put("router.buildHandlerThreads", _x("Thread pool for inbound build request processing."));
        PARAM_DESCRIPTIONS.put("i2ptunnel.clientRunner.max", _x("Ceiling for client proxy thread pool."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.minLimit", _x("Min transit tunnels any peer can hold."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.maxLimit", _x("Max transit tunnels a single peer can hold."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.percentLimit", _x("Max share of transit tunnels for a single peer."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.minLimit", _x("Min inbound requests accepted from any peer."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.maxLimit", _x("Max inbound requests accepted from any peer."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.percentLimit", _x("Max share of tunnel requests from a single peer."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.burst1sThreshold", _x("Burst threshold: requests per second before a ban."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.highLoadLagMs", _x("Job queue lag triggering high-load request gating (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.highLoadCpuPct", _x("System load percent triggering high-load request gating."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.moderateLoadLagMs", _x("Job queue lag triggering moderate-load peer disconnect (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.moderateLoadCpuPct", _x("System load percent triggering moderate-load disconnect."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.sustainedHighLoadMs", _x("Duration of high load before gating requests (ms)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.sustainedModerateLoadMs", _x("Duration of moderate load before declining requests (ms)."));
        PARAM_DESCRIPTIONS.put("tunnel.peerSelection.activityWindowMultiplier", _x("Widens peer recency window to re-admit peers when builds fail."));
        PARAM_DESCRIPTIONS.put("tunnel.pool.failureThreshold", _x("Consecutive failures before pool backoff."));
        PARAM_DESCRIPTIONS.put("tunnel.pool.backoffMs", _x("Cooldown duration after pool failure threshold, in ms."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.targetBuffer", _x("Target spare tunnel count per pool."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.untestedMultiplier", _x("Widens untested tunnel cap when testing stalls."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.rejectThreshold", _x("Load threshold where probabilistic transit rejection starts (%)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.rejectSteepness", _x("Steepness of the transit rejection probability curve."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.participatingThrottle.loadWeight", _x("Load score multiplier for transit rejection (% of computed load)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.rejectThreshold", _x("Load threshold where probabilistic request throttling starts (%)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.rejectSteepness", _x("Steepness of the request throttling probability curve."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.requestThrottle.loadWeight", _x("Load score multiplier for request throttling (% of computed load)."));
        PARAM_DESCRIPTIONS.put("i2p.streaming.rtoMultiplier", _x("RTO growth multiplier per retransmission timeout (%, 100 = flat)."));
        PARAM_DESCRIPTIONS.put("i2p.tunnel.socketConnectTimeout", _x("TCP socket connect timeout for tunnel server handler threads (ms)."));
        PARAM_DESCRIPTIONS.put("router.defaultProcessingTimeThrottle", _x("Max average message delay before rejecting transit (ms)."));
    }

    // display order for subsystems (alphabetical)
    private static final String[] SUBSYSTEM_ORDER = {
        Tuner.SUB_CONGESTION,
        Tuner.SUB_CRYPTO,
        Tuner.SUB_I2CP,
        Tuner.SUB_NETDB,
        Tuner.SUB_PEER,
        Tuner.SUB_ROUTER,
        Tuner.SUB_STREAMING,
        Tuner.SUB_TRANSIT,
        Tuner.SUB_TUNNEL,
        Tuner.SUB_TRANSPORT
    };

    // single-word section headings
    private static final Map<String, String> SECTION_LABELS = new LinkedHashMap<>();
    static {
        SECTION_LABELS.put(Tuner.SUB_TRANSPORT, _x("Transport"));
        SECTION_LABELS.put(Tuner.SUB_TRANSIT, _x("Transit"));
        SECTION_LABELS.put(Tuner.SUB_TUNNEL, _x("Tunnel"));
        SECTION_LABELS.put(Tuner.SUB_STREAMING, _x("Streaming"));
        SECTION_LABELS.put(Tuner.SUB_I2CP, _x("I2CP"));
        SECTION_LABELS.put(Tuner.SUB_CONGESTION, _x("Congestion"));
        SECTION_LABELS.put(Tuner.SUB_CRYPTO, _x("Crypto"));
        SECTION_LABELS.put(Tuner.SUB_ROUTER, _x("Router"));
        SECTION_LABELS.put(Tuner.SUB_NETDB, _x("NetDB"));
        SECTION_LABELS.put(Tuner.SUB_PEER, _x("Peers"));
    }

    /**
     * Main content: editable tables of tunable params with sparklines.
     */
    public String getTuning() {
        Tuner tuner = getTuner();
        if (tuner == null)
            return "<div class=infowarn id=tuning>" + _t("Auto-Tuning is not available") + "</div>";

        List<ParamSnapshot> snaps = tuner.getSnapshots();
        double healthScore = tuner.getHealthScore();

        // group by subsystem for ordering
        Map<String, List<ParamSnapshot>> groups = new LinkedHashMap<>();
        for (String sub : SUBSYSTEM_ORDER)
            groups.put(sub, new ArrayList<>());
        for (ParamSnapshot s : snaps) {
            List<ParamSnapshot> list = groups.get(s.subsystem);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(s.subsystem, list);
            }
            list.add(s);
        }
        // Pre-compute display names for sorting
        Map<String, String> dn = new HashMap<>();
        for (ParamSnapshot s : snaps) {
            String display = DISPLAY_NAMES.get(s.name);
            dn.put(s.name, display != null ? display : s.name);
        }
        // Sort each group by display name
        for (List<ParamSnapshot> list : groups.values()) {
            Collections.sort(list, new Comparator<ParamSnapshot>() {
                @Override
                public int compare(ParamSnapshot a, ParamSnapshot b) {
                    return dn.get(a.name).compareToIgnoreCase(dn.get(b.name));
                }
            });
        }

        StringBuilder buf = new StringBuilder(8192);
        buf.append("<div class=main id=tuning>");

        // System health + purpose paragraph
        buf.append("<p class=infohelp>")
           .append(_t("Auto-Tuning continuously monitors router performance and adjusts parameters to optimize throughput, reduce latency, and maintain stability."))
           .append(' ').append(_t("Uncheck the Auto box on any row to pin that parameter and prevent further adjustments.")).append(" <span id=health>");
        if (!Double.isNaN(healthScore)) {
            String healthLabel;
            if (healthScore >= 0.8) {
                healthLabel = _t("Healthy");
            } else if (healthScore >= 0.5) {
                healthLabel = _t("Fair");
            } else {
                healthLabel = _t("Degraded");
            }
            int pct = (int)(healthScore * 100);
            buf.append(_t("Current system health:")).append(" <b>")
               .append(healthLabel).append(" (").append(pct).append("%)</b>.");
        } else {
            buf.append(_t("System health assessment is not yet available."));
        }
        buf.append("</span></p>");

        // Defer health rings for 5 min after restart so all have consistent data
        boolean deferRings = _context.router().getUptime() < 5 * 60 * 1000;
        if (deferRings) {
            healthScore = Double.NaN;
        }

        // Subsystem ring chart dashboard
        buf.append(renderSubsystemRings(tuner, deferRings));

        buf.append("<form id=tuningform method=POST target=processForm>")
           .append("<input type=hidden name=nonce value=\"").append(_nonce != null ? _nonce : "").append("\">")
           .append("<div class=tablewrap><table id=tuningtable><thead>")
           .append("<tr>")
           .append("<th class=parameter>").append(_t("Parameter")).append("</th>")
           .append("<th class=value>").append(_t("Current")).append("</th>")
           .append("<th class=default>").append(_t("Default")).append("</th>")
           .append("<th class=min>").append(_t("Min")).append("</th>")
           .append("<th class=max>").append(_t("Max")).append("</th>")
           .append("<th class=step>").append(_t("Step")).append("</th>")
           .append("<th class=history>").append(_t("History")).append("</th>")
           .append("<th class=auto>").append(_t("Auto")).append("</th>")
           .append("</tr></thead>");

        for (Map.Entry<String, List<ParamSnapshot>> entry : groups.entrySet()) {
            List<ParamSnapshot> params = entry.getValue();
            if (params.isEmpty()) continue;

            String sectionLabel = SECTION_LABELS.get(entry.getKey());
            if (sectionLabel != null)
                buf.append("<thead class=section><tr><td>").append(_t(sectionLabel)).append("</td><td colspan=7></td></tr></thead>");

            buf.append("<tbody>");

            for (ParamSnapshot s : params) {
                String prefix = toFormPrefix(s.name);
                String sparkSvg = renderSparkline(s.valueHistory, s.defaultValue);

                buf.append("<tr data-prefix=\"").append(prefix).append("\" data-current=\"").append(s.currentValue).append("\">")
                   .append("<td class=parameter title=\"").append(esc(s.name)).append("\">").append(esc(getDisplayName(s.name)));
                String desc = PARAM_DESCRIPTIONS.get(s.name);
                if (desc != null) {
                    buf.append("<br><span class=paramdesc>").append(esc(_t(desc))).append("</span>");
                }
                buf.append("</td>");

                // col2: value (with parenthesized delta when different from default)
                buf.append("<td class=value>").append(s.currentValue);
                if (s.currentValue != s.defaultValue) {
                    buf.append("<br><span class=delta>").append(s.currentValue - s.defaultValue >= 0 ? "+" : "")
                       .append(s.currentValue - s.defaultValue).append("</span>");
                }
                buf.append("</td>");

                // col3: editable default value
                buf.append("<td class=default><input type=number size=6 name=\"").append(prefix).append("Default\" value=\"").append(s.defaultValue).append("\"></td>");

                buf.append("<td class=min><input type=number size=6 name=\"").append(prefix).append("Min\" value=\"").append(s.min).append("\"></td>")
                   .append("<td class=max><input type=number size=6 name=\"").append(prefix).append("Max\" value=\"").append(s.max).append("\"></td>")
                   .append("<td class=step><input type=number size=4 name=\"").append(prefix).append("Step\" value=\"").append(s.step).append("\"></td>")
                   .append("<td class=history>").append(sparkSvg).append("</td>")
                   .append("<td class=auto>")
                   // hidden field sends current value when checkbox unchecked
                   .append("<input type=hidden name=\"").append(prefix).append("Override\" value=\"").append(s.currentValue).append("\">")
                   // checkbox sends -1 when checked (auto-tuning on)
                   .append("<input type=checkbox class=\"optbox slider\" name=\"").append(prefix).append("Override\" value=\"-1\"")
                   .append(s.autoTuning ? " checked" : "").append("></td>")
                   .append("</tr>");
            }

            buf.append("</tbody>");
        }

        buf.append("<tr><td class=optionsave colspan=8><input type=submit name=action class=accept style=float:left value=\"")
           .append(_t("Restore Defaults"))
           .append("\"> <input type=submit name=action class=accept value=\"")
           .append(_t("Save"))
           .append("\"></td></tr></table></form></div>");
        return buf.toString();
    }

    /**
     * Convert a param name like "ACK_FREQUENCY" or "i2p.streaming.maxSlowStartWindow"
     * to a form prefix like "ackFrequency" or "maxSlowStartWindow".
     * Strips domain prefixes (i2p., crypto., ntcp., udp., router., etc.).
     */
    private static String toFormPrefix(String name) {
        // strip domain prefix (e.g. "i2p.streaming." -> "maxSlowStartWindow")
        String bare = name;
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < name.length() - 1)
            bare = name.substring(lastDot + 1);

        String[] parts = UNDERSCORE_SPLIT.split(bare);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].toLowerCase();
            if (i == 0) {
                sb.append(p);
            } else {
                sb.append(Character.toUpperCase(p.charAt(0)))
                  .append(p.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Return a human-readable display name for a raw param name,
     * falling back to the raw name if unmapped.
     */
    private String getDisplayName(String name) {
        String display = DISPLAY_NAMES.get(name);
        if (display == null) return name;
        return _t(display);
    }

    /**
     * Render an inline SVG sparkline.
     * Diverging bar chart: center line = default value.
     * Green bars above = value above default (tuned up).
     * Red bars below = value below default (tuned down).
     */
    private static String renderSparkline(int[] values, int defaultValue) {
        int n = values.length;
        if (n < 2) return "<span class=\"spark init\">Collecting...</span>";

        int pad = 4;
        int pw = SPARK_W - pad * 2;
        int ph = SPARK_H - pad * 2;
        int midY = pad + ph / 2;

        StringBuilder sb = new StringBuilder(512);
        sb.append("<svg class=\"spark\" viewBox=\"0 0 ").append(SPARK_W).append(" ").append(SPARK_H).append("\">");

        // center line = default value
        sb.append("<line x1=\"").append(pad).append("\" y1=\"").append(midY)
          .append("\" x2=\"").append(pad + pw).append("\" y2=\"").append(midY)
          .append("\" stroke=\"#888\" stroke-width=\"0.5\" opacity=\"0.5\"/>");

        // compute scale: max deviation from default in either direction
        int maxDev = 1;
        for (int i = 0; i < n; i++) {
            int dev = Math.abs(values[i] - defaultValue);
            if (dev > maxDev) maxDev = dev;
        }

        int halfH = ph / 2;

        // diverging bars: green above center, red below
        // newest on left, oldest on right
        int barW = Math.max(1, pw / n);
        for (int i = 0; i < n; i++) {
            int bx = pad + ((n - 1 - i) * pw) / n;
            int dev = values[i] - defaultValue;
            int bh = (int)((double) Math.abs(dev) / maxDev * halfH);
            if (bh < 1 && dev != 0) bh = 1;
            String color = "var(--graphbar)";
            int barY = (dev >= 0) ? midY - bh : midY;
            sb.append("<rect x=\"").append(bx).append("\" y=\"").append(barY)
              .append("\" width=\"").append(barW).append("\" height=\"").append(Math.max(1, bh))
              .append("\" fill=\"").append(color).append("\"/>");
        }

        sb.append("</svg>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Render the subsystem ring chart dashboard.
     * Returns an HTML div with a grid of SVG ring charts, one per subsystem.
     * NaN-scored subsystems show a gray "collecting..." ring.
     *
     * @since 0.9.70+
     */
    private String renderSubsystemRings(Tuner tuner, boolean defer) {
        List<Tuner.SubsystemScore> scores = tuner.getSubsystemScores();
        if (scores.isEmpty()) return "";
        if (defer) {
            for (Tuner.SubsystemScore ss : scores)
                ss.score = Double.NaN;
        }

        StringBuilder buf = new StringBuilder(2048);
        buf.append("<div id=tuningstats>");

        for (Tuner.SubsystemScore ss : scores) {
            if (Double.isNaN(ss.score)) {
                buf.append(RingRenderer.renderRingCell(-1, ss.label, "\u2014", ss.details));
            } else {
                int pct = (int) (ss.score * 100);
                String pctStr = pct + "%";
                buf.append(RingRenderer.renderRingCell(ss.score, ss.label, pctStr, ss.details));
            }
        }

        buf.append("</div>");
        return buf.toString();
    }

    /**
     *  Get the SSU transport tuner instance.
     *
     * @return the UDPTransport's Tuner, or null
     */
    private Tuner getTuner() {
        if (_context == null) return null;
        CommSystemFacade cs = _context.commSystem();
        if (cs == null) return null;
        SortedMap<String, Transport> transports = cs.getTransports();
        Transport udp = transports.get(UDPTransport.STYLE);
        if (udp instanceof UDPTransport) {
            return ((UDPTransport) udp).getTuner();
        }
        return null;
    }
}
