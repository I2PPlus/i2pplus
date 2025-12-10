package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.HandleDatabaseLookupMessageJob;

/**
 * Processes database lookup messages received by floodfill routers.
 * <p>
 * Handles incoming DatabaseLookupMessage requests from remote peers, performing
 * database searches and sending appropriate replies. Extends the base lookup
 * handling with floodfill-specific logic including query acceptance criteria
 * and capability-based response behavior.
 * <p>
 * Determines whether to answer all queries based on floodfill status and
 * capability flags. When not operating as floodfill, includes the local
 * RouterInfo in responses to inform peers of the capability change and
 * reduce misdirected queries.
 */
public class HandleFloodfillDatabaseLookupMessageJob extends HandleDatabaseLookupMessageJob {
    public HandleFloodfillDatabaseLookupMessageJob(RouterContext ctx, DatabaseLookupMessage receivedMessage, RouterIdentity from, Hash fromHash, long msgIDBloomXor) {
        super(ctx, receivedMessage, from, fromHash, msgIDBloomXor);
    }

    /**
     * @return are we floodfill
     * We don't really answer all queries if this is true,
     * since floodfills don't have the whole keyspace any more,
     * see ../HTLMJ for discussion
     */
    @Override
    protected boolean answerAllQueries() {
        if (!getContext().netDb().floodfillEnabled()) return false;
        return FloodfillNetworkDatabaseFacade.isFloodfill(getContext().router().getRouterInfo());
    }

    /**
     * We extend this here to send our routerInfo back as well, if we are not floodfill.
     * This gets the word out to routers that we are no longer floodfill, so they
     * will stop bugging us.
     */
    @Override
    protected void sendClosest(Hash key, Set<Hash> routerInfoSet, Hash toPeer, TunnelId replyTunnel) {
        super.sendClosest(key, routerInfoSet, toPeer, replyTunnel);

        // go away, you got the wrong guy, send our RI back unsolicited
        if (!getContext().netDb().floodfillEnabled()) {
            // We could just call sendData(myhash, myri, toPeer, replyTunnel) but
            // that would increment the netDb.lookupsHandled and netDb.lookupsMatched stats
            DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
            RouterInfo me = getContext().router().getRouterInfo();
            msg.setEntry(me);
            sendMessage(msg, toPeer, replyTunnel);
        }
    }
}
