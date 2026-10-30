package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;


/**
 * Processes DatabaseSearchReplyMessage responses from floodfill peers.
 * <p>
 * Validates reply messages and extracts router information for search operations.
 * Implements security measures to prevent exploitation through malformed
 * or malicious search replies, including authentication verification and nonce checking.
 * <p>
 * Tracks statistics on good, bad, and duplicate replies to identify
 * potentially unreliable peers and adjust search behavior accordingly.
 * <p>
 * Thread-safe implementation with comprehensive logging for debugging
 * and monitoring of peer reliability patterns.
 *
 * @since 0.9.16
 */
class SearchReplyJob extends JobImpl {
    /** The search reply being processed. */
    private final DatabaseSearchReplyMessage _msg;
    /** The log for the reply job. */
    private final Log _log;
    /** The peer that sent the reply. */
    private final Hash _peer;
    /** Peers seen in the reply. */
    private int _seenPeers;
    /** New peers added from the reply. */
    private int _newPeers;
    /** Duplicate peers in the reply. */
    private int _duplicatePeers;
    /** How long the search took. */
    private final long _duration;
    /** The search job this reply answers. */
    private final SearchJob _searchJob;

    /**
     * Opens a log and clamps a non-positive duration to zero.
     *
     * @param enclosingContext the router context this job runs in
     * @param job the search job that requested the reply, credited with any peers found
     * @param message the reply carrying the peer hashes to process
     * @param peer the router that sent the reply
     * @param duration the time in milliseconds the search had been running when the reply
     *                 arrived; negative or zero is stored as 0
     */
    public SearchReplyJob(RouterContext enclosingContext, SearchJob job, DatabaseSearchReplyMessage message, Hash peer, long duration) {
        super(enclosingContext);
        _log = enclosingContext.logManager().getLog(getClass());
        _searchJob = job;
        _msg = message;
        _peer = peer;
        if (duration > 0) {_duration = duration;}
        else {_duration = 0;}
    }

    /**
     * Name of this job.
     *
     * @return the fixed label "Process Kademlia Search Reply", used to identify
     *         this job in logs and job statistics
     */
    public String getName() { return "Process Kademlia Search Reply"; }

    /**
     * Process the peers referenced in the search reply.
     */
    public void runJob() {
        int count = Math.min(_msg.getNumReplies(), 2 * SingleLookupJob.MAX_TO_FOLLOW);
        for (int i = 0; i < count; i++) {processPeer(i);}

        if (count == 0 && _log.shouldDebug())
            _log.debug("DbSearchReply received with no routers referenced");

        // either they didn't tell us anything new or we have verified
        // (or failed to verify) all of them.  we're done
        getContext().profileManager().dbLookupReply(_peer, _newPeers, _seenPeers, 0, _duplicatePeers, _duration);
        if (_newPeers > 0) {_searchJob.newPeersFound(_newPeers);}
    }

    /**
     * Processes one peer of the reply.
     *
     * @param curIndex the index of the peer
     */
    private void processPeer(int curIndex) {
            Hash peer = _msg.getReply(curIndex);

            boolean shouldAdd = false;

            RouterInfo info = getContext().netDb().lookupRouterInfoLocally(peer);
            if (info == null) {
                // if the peer is giving us lots of bad peer references,
                // Don't try to fetch them.

                boolean sendsBadInfo = getContext().profileOrganizer().peerSendsBadReplies(_peer);
                if (!sendsBadInfo) {
                    // we don't need to search for everthing we're given here - only ones that
                    // are next in our search path...
                    // note: no need to think about banlisted targets in the netdb search, given
                    //       the floodfill's behavior
                    // This keeps us from continually chasing blocklisted floodfills
                    if (getContext().banlist().isBanlisted(peer)) {
                        /* blocklisted target; never add it to the search path */
                    } else {
                        shouldAdd = true;
                    }
                } else {
                    if (_log.shouldInfo())
                        _log.info("Peer [" + _peer.toBase64().substring(0,6) + "] sends us bad replies -> Reply verification skipped");
                    getContext().statManager().addRateData("netDb.searchReplyValidationSkipped", 1);
                }
            }

            if (_searchJob.wasAttempted(peer)) {_duplicatePeers++;}
            if (_log.shouldDebug())
                _log.debug("DbSearchReply received on search, referencing Router [" + peer.toBase64().substring(0,6) +
                           "] - Already known? " + (info != null));
            if (shouldAdd) {
                if (_searchJob.add(peer)) {_newPeers++;}
                else {_seenPeers++;}
            }
    }
}
