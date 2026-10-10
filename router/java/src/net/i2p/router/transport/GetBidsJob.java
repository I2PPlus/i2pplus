package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;

import net.i2p.router.Tuner;
import net.i2p.util.Log;

/**
 * Retrieves transport bids for outbound message delivery.
 * Retrieve a set of bids for a particular outbound message, and if any are found
 * that meet the message's requirements, register the message as in process and
 * pass it on to the transport for processing
 */
class GetBidsJob {

    /**
     * Constructor. Bid collection is driven entirely by the static getBids()
     * from the context, transport manager and message it is handed, so an
     * instance carries no state.
     */
    GetBidsJob() {}

    /**
     * Transport bids for a message and send it if a suitable bid is found
     * @param context the router context, used for stats, banlist and clock lookups
     * @param tmgr the transport manager asked to collect the bids from every available transport
     * @param msg the outbound message to bid on, dispatched on the lowest acceptable bid
     */
    static void getBids(RouterContext context, TransportManager tmgr, OutNetMessage msg) {
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
                // No transport was even attempted, so nothing here is evidence of
                // misbehaviour: it means we hold no usable route to the peer. Their
                // addresses may be stale, may advertise only a transport we lack,
                // or they may be behind NAT we cannot traverse. This branch used to
                // banlist them for "No transports", which punished a peer for our
                // own reachability gap and outlived their return. Repeats are not a
                // stronger signal -- a peer behind restrictive NAT lands here on
                // every attempt, forever -- so counting them would penalise exactly
                // the participants least able to route back to us. Record the stat
                // and stop. TransportManager's unreachable branch is the matching
                // decision once a transport has actually been tried and failed.
                context.statManager().addRateData("transport.bidFailNoTransports", msg.getLifetime());
                if (log.shouldLog(Log.DEBUG))
                    log.debug("No transport bid for [" + to.toBase64().substring(0,6)
                              + "]; no route on file -> not banning");
            } else if (failedCount >= tmgr.getTransportCount()) {
                // Every transport was tried and every one failed. This branch does
                // carry an attempt, which is why it is the one that would justify
                // escalating -- but a single dispatch failing is still weak evidence,
                // so it records a stat and leaves the judgement to the peer profile.
                context.statManager().addRateData("transport.bidFailAllTransports", msg.getLifetime());
            }
            fail(context, msg);
        } else {
            if (log.shouldInfo())
                log.info("Attempting to send on transport [" + bid.getTransport().getStyle() + "]: " + bid);
            bid.getTransport().send(msg);
        }
    }

    /**
     * Fail a message and trigger failure callbacks
     * @param context the router context, whose job queue receives the failure callbacks
     * @param msg the outbound message being dropped; it is unregistered from the message registry
     */
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
     * Mark a string for extraction by xgettext and translation.
     * Use this only in static initializers.
     * It does not translate!
     * @return s
     */
    private static final String _x(String s) {
        return s;
    }

}
