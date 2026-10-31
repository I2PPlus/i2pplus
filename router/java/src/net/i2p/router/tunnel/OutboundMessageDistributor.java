package net.i2p.router.tunnel;

import java.util.HashSet;
import java.util.Set;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.i2np.UnknownI2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SyntheticREDQueue;
import net.i2p.util.SystemVersion;

/**
 * When a message arrives at the outbound tunnel endpoint, this distributor
 * honors the instructions.
 */
class OutboundMessageDistributor {
    /** The router context. */
    private final RouterContext _context;
    /** Send priority of the distributed messages. */
    private final int _priority;
    /** Logger for the message distributor. */
    private final Log _log;
    /** The routers the message was already sent to. */
    private final Set<Hash> _toRouters;
    /** Queue estimating the outbound share, only for somebody else's endpoint. */
    private final SyntheticREDQueue _partBWE;
    /** New routers added in the current period. */
    private int _newRouterCount;
    /** When the new router period started. */
    private long _newRouterTime;

    /** Time allowed for one distribution. */
    private static final long MAX_DISTRIBUTE_TIME = 15*1000L;
    /** Most routers sent to in one period. */
    private static final int MAX_ROUTERS_PER_PERIOD = SystemVersion.isSlow() ? 32 : 64;
    /** Length of the new router allowance period. */
    private static final long NEW_ROUTER_PERIOD = SystemVersion.isSlow() ? 30*1000L : 15*1000L;

    /**
     * Create a new outbound message distributor.
     *
     * @param ctx our router context
     * @param priority OutNetMessage.PRIORITY_PARTICIPATING for somebody else's OBEP, or
     *                 OutNetMessage.PRIORITY_MY_DATA for our own zero-hop OBGW/EP
     */
    public OutboundMessageDistributor(RouterContext ctx, int priority) {
        this(ctx, priority, null);
    }

    /**
     * Create a new outbound message distributor with bandwidth estimation.
     *
     * @param ctx our router context
     * @param priority OutNetMessage.PRIORITY_PARTICIPATING for somebody else's OBEP, or
     *                 OutNetMessage.PRIORITY_MY_DATA for our own zero-hop OBGW/EP
     * @param bwe null for none
     * @since 0.9.68
     */
    public OutboundMessageDistributor(RouterContext ctx, int priority, SyntheticREDQueue bwe) {
        _context = ctx;
        _priority = priority;
        _log = ctx.logManager().getLog(OutboundMessageDistributor.class);
        if (priority <= OutNetMessage.PRIORITY_PARTICIPATING) {
            _toRouters = new HashSet<>(4);
            _toRouters.add(ctx.routerHash());
        } else {_toRouters = null;}
        _partBWE = bwe;
        // all createRateStat() in TunnelDispatcher
    }

    /**
     * Warning - as of 0.9.63, msg will be an UnknownI2NPMessage,
     * and must be converted before handling locally.
     *
     * @param msg the message to deliver onward
     * @param target the router the message is addressed to
     */
    public void distribute(I2NPMessage msg, Hash target) {
        distribute(msg, target, null);
    }

    /**
     * Warning - as of 0.9.63, msg will be an UnknownI2NPMessage,
     * and must be converted before handling locally.
     *
     * @param msg the message to deliver onward
     * @param target the router the message is addressed to
     * @param tunnel tunnel to wrap the message in, or null to send it bare
     */
    public void distribute(I2NPMessage msg, Hash target, TunnelId tunnel) {
        if (shouldDrop(target)) {
            _context.statManager().addRateData("tunnel.dropAtOBEP", 1);
            if (_log.shouldInfo()) {
                 _log.info("Dropping an I2NP message at the outbound endpoint -> New connection throttle"
                           + "\n* Type: " + msg.getType()
                           + " * Target: [" + target.toBase64().substring(0,6) + "]"
                           + "\n* Tunnel: " +
                           (tunnel != null ? tunnel.getTunnelId() : "null"));
            }
            return;
        }
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(target);
        if (info == null) {
            if (_toRouters != null) {
                // only if not zero-hop
                // credit our lookup message as part. traffic
                if (_context.tunnelDispatcher().shouldDropParticipatingMessage(TunnelDispatcher.Location.OBEP, DatabaseLookupMessage.MESSAGE_TYPE, 1024, _partBWE)) {
                    if (_log.shouldInfo()) {
                        _log.info("Dropping I2NPMessage [type " + msg.getType() + "] to [" + target.toBase64().substring(0,6) + "] " +
                                  "at Outbound Endpoint -> Lookup bandwidth throttle");
                    }
                    return;
                }
            }
            if (_log.shouldInfo())
                _log.info("Outbound distributor to [" + target.toBase64().substring(0,6)
                           + "]" + (tunnel != null ? "." + tunnel.getTunnelId() + "" : "")
                           + " -> No local info, searching...");
            // TODO - should we set the search timeout based on the message timeout,
            // or is that a bad idea due to clock skews?
            _context.netDb().lookupRouterInfo(target, new DistributeJob(_context, msg, target, tunnel), null, MAX_DISTRIBUTE_TIME);
        } else {distribute(msg, info, tunnel);}
    }

    /**
     * Throttle msgs to unconnected routers after we hit
     * the limit of new routers in a given time period.
     *
     * @param target the destination router's hash
     * @return true if the throttle has been exceeded and the message should
     *         be dropped, false to pass it on
     * @since 0.9.12
     */
    private boolean shouldDrop(Hash target) {
        if (_toRouters == null)
            return false;
        synchronized(this) {
            if (!_toRouters.add(target) || _context.commSystem().isEstablished(target) || ++_newRouterCount <= Math.min(MAX_ROUTERS_PER_PERIOD, 64))
                return false;
            long now = _context.clock().now();
            if (_newRouterTime < now - NEW_ROUTER_PERIOD) {
                // latest guy is outside previous period
                _newRouterCount = 1;
                _newRouterTime = now;
                return false;
            }
            // rarely get here at current limits
            _toRouters.remove(target);
        }
        return true;
    }

    /**
     * Warning - as of 0.9.63, msg will be an UnknownI2NPMessage,
     * and must be converted before handling locally.
     *
     * @param msg the message to distribute
     * @param target the router the message is destined for
     * @param tunnel the tunnel id the message arrived on, for the reply path
     */
    private void distribute(I2NPMessage msg, RouterInfo target, TunnelId tunnel) {
        boolean toUs = _context.routerHash().equals(target.getIdentity().calculateHash());
        if (toUs) {
            // If UnknownI2NPMessage, convert it.
            // See FragmentHandler.receiveComplete()
            if (msg instanceof UnknownI2NPMessage) {
                try {
                    UnknownI2NPMessage umsg = (UnknownI2NPMessage) msg;
                    msg = umsg.convert();
                } catch (I2NPMessageException ime) {
                    if (_log.shouldInfo())
                        _log.info("Unable to convert to standard message class at zero-hop Inbound Gateway \n* " + ime.getMessage());
                    return;
                }
            }
        }

        if (tunnel != null) {
            TunnelGatewayMessage t = new TunnelGatewayMessage(_context);
            t.setMessage(msg);
            t.setTunnelId(tunnel);
            t.setMessageExpiration(msg.getMessageExpiration());
            msg = t;
        }

        if (toUs) {
            if (_log.shouldDebug())
                _log.debug("Queueing Inbound message to ourselves: " + msg);
            _context.inNetMessagePool().add(msg, null, null, 0);
        } else {
            OutNetMessage out = new OutNetMessage(_context, msg, _context.clock().now() + MAX_DISTRIBUTE_TIME, _priority, target);

            if (_log.shouldDebug())
                _log.debug("Queueing Outbound message to: [" + target.getIdentity().calculateHash().toBase64().substring(0,6) + "]");
            _context.outNetMessagePool().add(out);
        }
    }

    /** Job sending one message to the next routers. */
    private class DistributeJob extends JobImpl {
        /** The message to distribute. */
        private final I2NPMessage _message;
        /** The hash the message searches for. */
        private final Hash _target;
        /** The tunnel the reply comes back on. */
        private final TunnelId _tunnel;

        /**
         * Builds the distribution job.
         *
         * @param ctx the router context
         * @param msg the message to distribute
         * @param target the hash the message searches for
         * @param id the tunnel the reply comes back on
         */
        public DistributeJob(RouterContext ctx, I2NPMessage msg, Hash target, TunnelId id) {
            super(ctx);
            _message = msg;
            _target = target;
            _tunnel = id;
        }

        public String getName() { return "Distribute OBEP after Lookup"; }

        public void runJob() {
            RouterInfo info = getContext().netDb().lookupRouterInfoLocally(_target);
            int stat;
            if (info != null) {
                if (_log.shouldDebug())
                    _log.debug("Lookup succeeded for Outbound distributor to [" + _target.toBase64().substring(0,6) + "]" +
                               (_tunnel != null ? " for [TunnelID " + _tunnel.getTunnelId() + "]" : ""));
                distribute(_message, info, _tunnel);
                stat = 1;
            } else {
                if (_log.shouldWarn())
                    _log.warn("Lookup failed for Outbound distributor to [" + _target.toBase64().substring(0,6) + "]" +
                              (_tunnel != null ? " for [TunnelID " + _tunnel.getTunnelId() + "]" : ""));
                stat = 0;
            }
            _context.statManager().addRateData("tunnel.distributeLookupSuccess", stat);
        }
    }
}
