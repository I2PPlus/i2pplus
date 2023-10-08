package net.i2p.router.networkdb.kademlia;

import java.util.Date;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Called after a match to a db search is found
 *
 * Used only by SearchJob which is only used by ExploreJob
 */
class SearchUpdateReplyFoundJob extends JobImpl implements ReplyJob {
    private final Log _log;
    private I2NPMessage _message;
    private final Hash _peer;
    private final SearchState _state;
    private final KademliaNetworkDatabaseFacade _facade;
    private final SearchJob _job;
    private final TunnelInfo _outTunnel;
    private final TunnelInfo _replyTunnel;
    private final boolean _isFloodfillPeer;
    private final long _sentOn;

    public SearchUpdateReplyFoundJob(RouterContext context, RouterInfo peer,
                                     SearchState state, KademliaNetworkDatabaseFacade facade,
                                     SearchJob job) {
        this(context, peer, state, facade, job, null, null);
    }

    public SearchUpdateReplyFoundJob(RouterContext context, RouterInfo peer,
                                     SearchState state, KademliaNetworkDatabaseFacade facade,
                                     SearchJob job, TunnelInfo outTunnel, TunnelInfo replyTunnel) {
        super(context);
        _log = context.logManager().getLog(SearchUpdateReplyFoundJob.class);
        _peer = peer.getIdentity().getHash();
        _isFloodfillPeer = FloodfillNetworkDatabaseFacade.isFloodfill(peer);
        _state = state;
        _facade = facade;
        _job = job;
        _outTunnel = outTunnel;
        _replyTunnel = replyTunnel;
        _sentOn = System.currentTimeMillis();
    }

    public String getName() { return "Update Kademlia Search Reply Found "; }

    public void runJob() {
        if (_isFloodfillPeer)
            _job.decrementOutstandingFloodfillSearches();

        I2NPMessage message = _message;
        if (_log.shouldInfo())
            _log.info("[Job " + getJobId() + "] Reply from [" + _peer.toBase64().substring(0,6)
                      + "] with " + message.getClass().getSimpleName().replace("Database", "Db").replace("Message", "Msg"));

        long howLong = System.currentTimeMillis() - _sentOn;
        // assume requests are 1KB (they're almost always much smaller, but tunnels have a fixed size)
        int msgSize = 1024;

        if (_replyTunnel != null) {
            for (int i = 0; i < _replyTunnel.getLength(); i++)
                getContext().profileManager().tunnelDataPushed(_replyTunnel.getPeer(i), howLong, msgSize);
            _replyTunnel.incrementVerifiedBytesTransferred(msgSize);
        }
        if (_outTunnel != null) {
            for (int i = 0; i < _outTunnel.getLength(); i++)
                getContext().profileManager().tunnelDataPushed(_outTunnel.getPeer(i), howLong, msgSize);
            _outTunnel.incrementVerifiedBytesTransferred(msgSize);
        }

        int type = message.getType();
        if (type == DatabaseStoreMessage.MESSAGE_TYPE) {
            long timeToReply = _state.dataFound(_peer);
            DatabaseStoreMessage msg = (DatabaseStoreMessage)message;
            DatabaseEntry entry = msg.getEntry();
            try {
                if (entry.isRouterInfo()) {
                    RouterInfo ri = (RouterInfo) entry;
                    _facade.store(ri.getHash(), ri);
                }
                if (entry.isLeaseSet()) {
                    LeaseSet ls = (LeaseSet) entry;
                    _facade.store(ls.getHash(), ls);
                }
            } catch (UnsupportedCryptoException iae) {
                // don't blame the peer
                getContext().profileManager().dbLookupSuccessful(_peer, timeToReply);
                _state.abort();
                // searchNext() will call fail()
            } catch (IllegalArgumentException iae) {
                if (_log.shouldWarn()) {
                    if (iae.getMessage().contains("published over")) {
                        _log.warn("Received stale RouterInfo from [" + _peer.toBase64().substring(0,6) + "] \n* " + iae.getMessage());
                    } else {
                        _log.warn("Received INVALID data packet from [" + _peer.toBase64().substring(0,6) + "] \n* " + iae.getMessage());
                        // blame the peer
                        getContext().profileManager().dbLookupReply(_peer, 0, 0, 1, 0, timeToReply);
                    }
                }
            }
        } else if (type == DatabaseSearchReplyMessage.MESSAGE_TYPE) {
            _job.replyFound((DatabaseSearchReplyMessage)message, _peer);
        } else {
            if (_log.shouldError())
                _log.error("[Job" + getJobId() + "] What?! Reply job matched a strange message: " + message);
            return;
        }

        _job.searchNext();
    }

    public void setMessage(I2NPMessage message) { _message = message; }
}
