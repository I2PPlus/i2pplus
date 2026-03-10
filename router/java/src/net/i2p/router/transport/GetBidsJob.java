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
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.BanLogger;
import net.i2p.router.Banlist;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Retrieves transport bids for outbound message delivery.
 * Retrieve a set of bids for a particular outbound message, and if any are found
 * that meet the message's requirements, register the message as in process and
 * pass it on to the transport for processing
 *
 */
class GetBidsJob extends JobImpl {
    private final Log _log;
    private final TransportManager _tmgr;
    private final OutNetMessage _msg;
    private static BanLogger _banLogger;

    /**
     *  @deprecated unused, see static getBids()
     */
    @Deprecated
    public GetBidsJob(RouterContext ctx, TransportManager tmgr, OutNetMessage msg) {
        super(ctx);
        _log = ctx.logManager().getLog(GetBidsJob.class);
        _tmgr = tmgr;
        _msg = msg;
    }

    public String getName() { return "Fetch bids for a message to be delivered"; }
    public void runJob() {
        getBids(getContext(), _tmgr, _msg);
    }

    static void getBids(RouterContext context, TransportManager tmgr, OutNetMessage msg) {
        // Ensure BanLogger is initialized
        if (_banLogger == null) {
            _banLogger = new BanLogger();
            _banLogger.initialize(context);
        }

        if (msg.getFailedTransportCount() > 1) {
            context.statManager().addRateData("transport.bidFailAllTransports", msg.getLifetime());
            fail(context, msg);
            return;
        }
        Log log = context.logManager().getLog(GetBidsJob.class);
        Hash to = msg.getTarget().getIdentity().getHash();
        msg.timestamp("Bid");

        if (context.banlist().isBanlisted(to)) {
            if (log.shouldInfo())
                log.info("Attempted to send message to banlisted peer [" + to.toBase64().substring(0,6) + "]");
            //context.messageRegistry().peerFailed(to);
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
                String ipPort = getRouterIPPort(msg.getTarget());
                String banReason = _x("No transports");
                context.banlist().banlistRouter(to, " <b>➜</b> " + banReason);
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

    /**
     * Extract IP address and port from RouterInfo for logging to sessionbans.txt.
     * Returns IP:PORT format for IPv4 or [IPv6]:PORT format for IPv6.
     *
     * @param router the RouterInfo to extract from
     * @return IP:PORT string or empty string if not available
     */
    private static String getRouterIPPort(RouterInfo router) {
        if (router == null) { return ""; }
        try {
            for (RouterAddress addr : router.getAddresses()) {
                if (addr != null && addr.getHost() != null) {
                    String ip = addr.getHost();
                    int port = addr.getPort();
                    if (port > 0) {
                        // Check if it's IPv6 address
                        if (ip.contains(":") && !ip.startsWith("[")) {
                            // IPv6 address needs brackets
                            return "[" + ip + "]:" + port;
                        } else {
                            return ip + ":" + port;
                        }
                    } else {
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return "";
    }
}
