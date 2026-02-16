package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.SyntheticREDQueue;

/**
 * Specialized pumped tunnel gateway for inbound gateways (IBGWs) that throttles messages
 * by checking bandwidth usage before queuing them.
 *
 * This class drops messages early based on bandwidth heuristics to more efficiently
 * preserve network and system resources compared to processing fragments.
 *
 * The drop decision is made here instead of deeper in the tunnel to allow dropping
 * whole I2NP messages, where message type and size are known accurately.
 *
 * Thread safety and behavior otherwise follows PumpedTunnelGateway.
 *
 * @since 0.7.9
 */
class ThrottledPumpedTunnelGateway extends PumpedTunnelGateway {
    /** Configuration and statistics for this hop, used to count dropped messages */
    private HopConfig _config;

    /** Synthetic Random Early Drop queue to estimate bandwidth usage and perform drops */
    private SyntheticREDQueue _partBWE;

    /**
     * Constructs a ThrottledPumpedTunnelGateway with bandwidth throttling.
     *
     * Allocated bandwidth value is retrieved from config or set to reasonable default.
     *
     * @param context RouterContext
     * @param preprocessor QueuePreprocessor for the gateway
     * @param sender Encrypting Sender for fragments
     * @param receiver Receiving entity consuming encrypted messages
     * @param pumper TunnelGatewayPumper managing pumping threads
     * @param config HopConfig containing bandwidth and message statistics
     */
    public ThrottledPumpedTunnelGateway(RouterContext context, QueuePreprocessor preprocessor, Sender sender,
                                        Receiver receiver, TunnelGatewayPumper pumper, HopConfig config) {
        super(context, preprocessor, sender, receiver, pumper);
        _config = config;
        int oldAllocated = _config.getAllocatedBW();
        int allocated = oldAllocated;
        int shareBps = 1000 * TunnelDispatcher.getShareBandwidth(_context);
        int reasonableMax = shareBps / 2;
        if (oldAllocated <= TunnelParticipant.DEFAULT_BW_PER_TUNNEL_ESTIMATE || 
            oldAllocated < reasonableMax / 10) {
            allocated = _context.tunnelDispatcher().getMaxPerTunnelBandwidth(TunnelDispatcher.Location.IBGW);
            _config.setAllocatedBW(allocated);
        }
        int effectiveBw = allocated;
        // Dynamic RED thresholds scaled to bandwidth - handle bursts without drops
        int minThreshold = Math.max(2048, effectiveBw / 4);
        int maxThreshold = Math.max(8192, effectiveBw);
        _partBWE = new SyntheticREDQueue(context, effectiveBw, minThreshold, maxThreshold);
    }

    /**
     * Adds a message to the inbound gateway after a bandwidth-based drop check.
     *
     * Drops messages early based on estimated size and bandwidth usage, tracking statistics.
     *
     * We do this here instead of in the InboundGatewayReceiver because it is
     * much smarter to drop whole I2NP messages, where we know the message type
     * and length, rather than tunnel messages containing I2NP fragments.
     *
     * Hard to do this exactly, but we'll assume 2:1 batching for the purpose
     * of estimating outgoing size. We assume that it's the outbound bandwidth that
     * is the issue...
     *
     * @param msg the I2NP message to add
     * @param toRouter optional target router after the endpoint
     * @param toTunnel optional target tunnel after the endpoint
     */
    @Override
    public void add(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
        // Estimate size with overheads to better match actual transmitted size
        int size = msg.getMessageSize() + 60;
        if (size > 1024) {
            // Additional fragmentation overhead for larger messages
            size += 28 * (size / 1024);
        } else if (size < 512) {
            size = 512; // Minimum size for small message batching assumption
        }

        if (_context.tunnelDispatcher().shouldDropParticipatingMessage(TunnelDispatcher.Location.IBGW, msg.getType(), size, _partBWE)) {
            // Increment dropped message counters; this may slightly overstate statistics but is acceptable
            int kb = (size + 1023) / 1024;
            for (int i = 0; i < kb; i++) {
                _config.incrementProcessedMessages();
            }
            return;  // Drop the message without queuing
        }

        add(new PendingGatewayMessage(msg, toRouter, toTunnel));
    }

    /**
     * Human-readable description differentiating inbound gateway and including tunnel ID.
     *
     * @return String description of this inbound pumped tunnel gateway
     * @since 0.9.8
     */
    @Override
    public String toString() {
        return "IBGW " + _config.getReceiveTunnelId();
    }

    /**
     * Destroy this gateway and release all resources.
     * Nulls references to enable timely garbage collection.
     * @since 0.9.68+
     */
    @Override
    public void destroy() {
        super.destroy();
        _config = null;
        _partBWE = null;
    }
}
