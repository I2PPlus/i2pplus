package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Job to handle an inbound GarlicMessage received outside of a tunnel.
 * <p>
 * Decrypts the garlic message and processes each contained clove. Local-message cloves are
 * added directly to the inbound message pool, while cloves destined for other routers or tunnels
 * are forwarded accordingly.
 * <p>
 * This class handles garlic messages received directly (not down a tunnel), e.g., in floodfill routers
 * receiving network database messages.
 * <p>
 * Implements {@link GarlicMessageReceiver.CloveReceiver} to receive and process individual cloves.
 * Public visibility is necessary for integration with the job queue system.
 */
public class HandleGarlicMessageJob extends JobImpl implements GarlicMessageReceiver.CloveReceiver {
    private final Log _log;
    private final GarlicMessage _message;
    private final long _msgIDBloomXorLocal;
    private final long _msgIDBloomXorRouter;
    private final long _msgIDBloomXorTunnel;

    private static final int ROUTER_PRIORITY = OutNetMessage.PRIORITY_LOWEST;
    private static final int TUNNEL_PRIORITY = OutNetMessage.PRIORITY_LOWEST;

    /**
     * Constructs a job to handle an inbound garlic message.
     *
     * @param context              router context providing core services
     * @param msg                  garlic message to process
     * @param from                 ignored (sender identity)
     * @param fromHash             ignored (sender hash)
     * @param msgIDBloomXorLocal   long unique to router/session for XOR with message ID in local inbound messages
     * @param msgIDBloomXorRouter  long unique to router/session for XOR with message ID in router-targeted messages
     * @param msgIDBloomXorTunnel  long unique to router/session for XOR with message ID in tunnel-targeted messages
     */
    public HandleGarlicMessageJob(final RouterContext context, final GarlicMessage msg,
                                 RouterIdentity from, Hash fromHash,
                                 final long msgIDBloomXorLocal, final long msgIDBloomXorRouter, final long msgIDBloomXorTunnel) {
        super(context);
        _log = context.logManager().getLog(HandleGarlicMessageJob.class);
        if (_log.shouldDebug()) {
            _log.debug("Garlic Message not down a tunnel from [" + from + "]");
        }
        _message = msg;
        _msgIDBloomXorLocal = msgIDBloomXorLocal;
        _msgIDBloomXorRouter = msgIDBloomXorRouter;
        _msgIDBloomXorTunnel = msgIDBloomXorTunnel;
    }

    /**
     * Returns a descriptive name for this job.
     *
     * @return Name of the job.
     */
    @Override
    public String getName() {
        return "Handle Inbound Garlic Message";
    }

    /**
     * Entry point for the job. Creates an instance of GarlicMessageReceiver configured to
     * use this job as the clove receiver, then processes the garlic message.
     */
    @Override
    public void runJob() {
        GarlicMessageReceiver receiver = new GarlicMessageReceiver(getContext(), this);
        receiver.receive(_message);
    }

    /**
     * Handle each garlic clove based on its delivery instructions.
     * <p>
     * Local delivery cloves are added to the inbound message pool with a XOR mask for the message ID.
     * Router delivery cloves are either queued locally or forwarded directly.
     * Tunnel delivery cloves are wrapped in a TunnelGatewayMessage and forwarded.
     *
     * @param instructions  Delivery instructions specifying the target for this clove.
     * @param data          The message contained in the clove.
     */
    @Override
    public void handleClove(final DeliveryInstructions instructions, final I2NPMessage data) {
        switch (instructions.getDeliveryMode()) {
            case DeliveryInstructions.DELIVERY_MODE_LOCAL:
                if (_log.shouldDebug()) {
                    _log.debug("Local delivery instructions for clove: " + data);
                }
                // Add message to inbound pool with local XOR mask
                getContext().inNetMessagePool().add(data, null, null, _msgIDBloomXorLocal);
                return;

            case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
                // Not expected for messages not received down a tunnel - warn for potential bugs
                if (_log.shouldWarn()) {
                    _log.warn("Message didn't come down a tunnel, not forwarding to a destination: "
                            + instructions + "\n" + data);
                }
                return;

            case DeliveryInstructions.DELIVERY_MODE_ROUTER:
                if (getContext().routerHash().equals(instructions.getRouter())) {
                    if (_log.shouldDebug()) {
                        _log.debug("Router delivery instructions targeting us");
                    }
                    // Add message to inbound pool for this router with router XOR mask
                    getContext().inNetMessagePool().add(data, null, null, _msgIDBloomXorRouter);
                } else {
                    if (_log.shouldDebug()) {
                        _log.debug("Router delivery instructions targeting ["
                                + instructions.getRouter().toBase64().substring(0, 6) + "] for " + data);
                    }
                    // Forward message directly to target router with a 10 second timeout and low priority
                    SendMessageDirectJob job = new SendMessageDirectJob(getContext(), data,
                            instructions.getRouter(),
                            10_000, ROUTER_PRIORITY, _msgIDBloomXorRouter);
                    job.runJob();
                }
                return;

            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                TunnelGatewayMessage gwMessage = new TunnelGatewayMessage(getContext());
                gwMessage.setMessage(data);
                gwMessage.setTunnelId(instructions.getTunnelId());
                gwMessage.setMessageExpiration(data.getMessageExpiration());
                if (_log.shouldDebug()) {
                    _log.debug("Tunnel delivery instructions targeting ["
                            + instructions.getRouter().toBase64().substring(0, 6) + "] for " + data);
                }
                // Forward message wrapped in tunnel gateway message to target router with a 10 second timeout and low priority
                SendMessageDirectJob tunnelJob = new SendMessageDirectJob(getContext(), gwMessage,
                        instructions.getRouter(),
                        10_000, TUNNEL_PRIORITY, _msgIDBloomXorTunnel);
                tunnelJob.runJob();
                return;

            default:
                _log.error("Unknown instruction " + instructions.getDeliveryMode() + ": " + instructions);
                return;
        }
    }

    /**
     * Called when this job has been dropped (e.g. due to queue overload).
     * Records an error in message history indicating the message was dropped.
     */
    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(),
                _message.getClass().getName(),
                "Dropped due to overload");
    }
}
