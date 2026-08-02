package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.BanLogger;
import net.i2p.router.Banlist;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.TransportImpl;

import net.i2p.router.Tuner;
import net.i2p.util.Log;

/**
 * Retrieves transport bids for outbound message delivery.
 * Retrieve a set of bids for a particular outbound message, and if any are found
 * that meet the message's requirements, register the message as in process and
 * pass it on to the transport for processing
 *
 */
class GetBidsJob {
    private static volatile BanLogger _banLogger;

    /** Transport bids for a message and send it if a suitable bid is found */
    static void getBids(RouterContext context, TransportManager tmgr, OutNetMessage msg) {
        // Ensure BanLogger is initialized
        BanLogger bl = _banLogger;
        if (bl == null) {
            synchronized (GetBidsJob.class) {
                bl = _banLogger;
                if (bl == null) {
                    bl = new BanLogger();
                    bl.initialize(context);
                    _banLogger = bl;
                }
            }
        }

        if (msg.getFailedTransportCount() > 1) {
            context.statManager().addRateData("transport.bidFailAllTransports", msg.getLifetime());
            fail(context, msg);
            return;
        }

        int maxAge = Tuner.getMaxDispatchAgeMs();
        if (maxAge > 0 && msg.getLifetime() > maxAge) {
            context.statManager().addRateData("transport.dispatchExpired", msg.getLifetime());
            fail(context, msg);
            return;
        }
        Log log = context.logManager().getLog(GetBidsJob.class);
        RouterInfo target = msg.getTarget();
        if (target == null) {
            context.statManager().addRateData("transport.bidFailNullTarget", msg.getLifetime());
            fail(context, msg);
            return;
        }
        Hash to = target.getIdentity().getHash();
        msg.timestamp("Bid");

        if (context.banlist().isBanlisted(to)) {
            if (log.shouldInfo())
                log.info("Attempted to send message to banlisted peer [" + to.toBase64().substring(0,6) + "]");
            context.statManager().addRateData("transport.bidFailBanlisted", msg.getLifetime());
            fail(context, msg);
            return;
        }

        Hash us = context.routerHash();
        if (to.equals(us)) {
            if (log.shouldError())
                log.error("Send a message to ourselves? nuh uh..." + msg, new Exception("I did it"));
            context.statManager().addRateData("transport.bidFailSelf", msg.getLifetime());
            fail(context, msg);
            return;
        }

        TransportBid bid = tmgr.getNextBid(msg);
        if (bid == null) {
            int failedCount = msg.getFailedTransportCount();
            if (failedCount == 0) {
                context.statManager().addRateData("transport.bidFailNoTransports", msg.getLifetime());
                // This used to be "no common transports" but it is almost always no transports at all
                String ipPort = TransportImpl.getRouterIPPort(msg.getTarget());
                String banReason = _x("No transports");
                context.banlist().banlistRouter(to, "" + banReason);
                // Log to sessionbans.txt with IP address (use default duration)
                _banLogger.logBan(to, ipPort, banReason, Banlist.BANLIST_DURATION_MS);
            } else if (failedCount >= tmgr.getTransportCount()) {
                context.statManager().addRateData("transport.bidFailAllTransports", msg.getLifetime());
            }
            fail(context, msg);
        } else {
            if (log.shouldInfo())
                log.info("Attempting to send on transport [" + bid.getTransport().getStyle() + "]: " + bid);
            bid.getTransport().send(msg);
        }
    }

    /** Fail a message and trigger failure callbacks */
    static void fail(RouterContext context, OutNetMessage msg) {
        if (msg.getOnFailedSendJob() != null) {
            context.jobQueue().addJob(msg.getOnFailedSendJob());
        }
        if (msg.getOnFailedReplyJob() != null) {
            context.jobQueue().addJob(msg.getOnFailedReplyJob());
        }
        MessageSelector selector = msg.getReplySelector();
        if (selector != null) {
            context.messageRegistry().unregisterPending(msg);
        }

        context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash());

        msg.discardData();
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }

}
