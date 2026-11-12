/* PeerCheckTasks - TimerTask that checks for good/bad up/downloaders.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import org.klomp.snark.dht.DHT;

/**
 * TimerTask that checks for good/bad up/downloader. Works together with the PeerCoordinator to
 * select which Peers get (un)choked.
 */
class PeerCheckerTask implements Runnable {
    /** Transfer rate implied by one check per period, in bytes per second. */
    private static final long KILOPERSECOND = 1024 * (PeerCoordinator.CHECK_PERIOD / 1000);

    /** The coordinator whose peers are checked. */
    private final PeerCoordinator coordinator;
    /** The torrent the peers belong to. */
    private final I2PSnarkUtil _util;
    /** Logger for this class. */
    private final Log _log;
    /** Chooses which peers to check. */
    private final Random random;
    /** How many times the task has run. */
    private int _runCount;
    /**
     * Random phase for the work that runs every Nth pass - closing files, announcing to
     * the local DHT - so two torrents started at the same time do not do it in lockstep.
     * Combined with the jittered checker cadence, this spreads the periodic work of a
     * set of torrents over time instead of bunching it.
     *
     * @since 0.9.72+
     */
    private final int _phase;

    /**
     * Creates a new checker task for the given coordinator.
     *
     * @param util the snark utility
     * @param coordinator the coordinator to check
     */
    PeerCheckerTask(I2PSnarkUtil util, PeerCoordinator coordinator) {
        _util = util;
        _log = util.getContext().logManager().getLog(PeerCheckerTask.class);
        random = util.getContext().random();
        _phase = random.nextInt(480);
        this.coordinator = coordinator;
    }

    public void run() {
        _runCount++;
        List<Peer> peerList = coordinator.peerList();
        if (peerList.isEmpty() || coordinator.halted()) {
            coordinator.setRateHistory(0, 0);
            return;
        }

        // Re-queue requested pieces that have received no data for PIECE_STALL_TIMEOUT
        coordinator.checkStalledPieces(System.currentTimeMillis());

        // Calculate total uploading and worst downloader.
        long worstdownload = Long.MAX_VALUE;
        Peer worstDownloader = null;

        int uploaders = 0;
        int interestedUploaders = 0;
        int removedCount = 0;

        long uploaded = 0;
        long downloaded = 0;

        // Keep track of peers we remove now,
        // we will add them back to the end of the list.
        List<Peer> removed = new ArrayList<>();
        int uploadLimit = coordinator.allowedUploaders();
        boolean overBWLimit = coordinator.overUpBWLimit();
        // end game is per-torrent state, so decide it once for the whole pass
        boolean endGame = coordinator.inEndGame();
        if (_log.shouldDebug())
            _log.debug("START peers: " + peerList.size() + " uploaders: "
                       + coordinator.getUploaders() + " interested: "
                       + coordinator.getInterestedUploaders() + " limit: " + uploadLimit
                       + " overBW? " + overBWLimit);
        DHT dht = _util.getDHTForTorrent(coordinator.getInfoHash());
        boolean fetchComments = _util.utCommentsEnabled();
        int i = 0;
        for (Peer peer : peerList) {
            i++;

            // Remove dying peers
            if (!peer.isConnected()) {
                continue;
            }

            long inactiveTime = peer.getInactiveTime();
            // note that streaming is configured to disconnect at 4 minutes
            // so this probably won't happen here.
            if (inactiveTime > peer.getMaxInactiveTime()) {
                if (_log.shouldInfo())
                    _log.info("Disconnecting an idle peer -> Inactive too long * Peer: "
                              + SnarkLogFormatter.peer(peer)
                              + " * Idle: " + DataHelper.formatDuration(peer.getInactiveTime()));
                peer.disconnect();
                continue;
            }

            // we only count choking AND interested, so as not to steal a slot
            // from some other torrent
            if (peer.isInterested() && !peer.isChoking()) uploaders++;

            long upload = peer.getUploaded();
            uploaded += upload;
            long download = peer.getDownloaded();
            downloaded += download;
            peer.setRateHistory();

            if (_log.shouldDebug()) {
                // A per-peer tally, so the rates go on their own line - the value is
                // bytes per second, KILOPERSECOND being the rate one check per period makes
                _log.debug("Peer check -> " + SnarkLogFormatter.peer(peer)
                           + " * We want: " + peer.isInteresting()
                           + " * We choke: " + peer.isChoking()
                           + "\n* Peer wants: " + peer.isInterested()
                           + " * Peer chokes: " + peer.isChoked()
                           + " * Up: " + upload * 1024 / KILOPERSECOND + " B/s"
                           + " * Down: " + download * 1024 / KILOPERSECOND + " B/s");
            }

            // Choke a percentage of them rather than all so it isn't so drastic...
            // choke 3/8 of the time when seeding and 1/4 when leeching
            boolean overBWLimitChoke =
                    upload > 0
                            && ((overBWLimit
                                            && (random.nextInt(8)
                                                    > (coordinator.completed() ? 4 : 5)))
                                    || (coordinator.overUpBWLimit(uploaded)));

            // A peer that still holds a piece we want is a source for the last pieces,
            // not just a sink, so the choke cascade below leaves it alone unless we are
            // over our upload bandwidth limit. The scan of the wanted pieces is only
            // worth making while the end game is on.
            boolean peerHasNeededPiece = endGame && coordinator.hasSomethingWeNeed(peer);
            boolean spareSource =
                    spareEndgameSource(endGame, peerHasNeededPiece, overBWLimitChoke);

            // If we are at our max uploaders and we have lots of other
            // interested peers try to make some room.
            // (Note use of coordinator.uploaders)
            int cup = coordinator.getUploaders();
            if (((cup == uploadLimit && coordinator.getInterestedAndChoking() > 0)
                            || cup > uploadLimit
                            || overBWLimitChoke)
                    && !peer.isChoking()
                    && !spareSource) {
                // Check if it still wants pieces from us.
                if (!peer.isInterested()) {
                    // Note that we only choke if we are over our limits,
                    // so a peer may remain unchoked even if uninterested.
                    if (_log.shouldDebug()) _log.debug("Choking uninterested peer "
                                                       + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.decrementUploaders(false);

                    // Put it at the back of the list
                    removed.add(peer);
                } else if (overBWLimitChoke) {
                    if (_log.shouldDebug())
                        _log.debug("Bandwidth limit reached, choking peer "
                                   + SnarkLogFormatter.peer(peer) + " ("
                                   + upload + "/" + uploaded + ")");
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;

                    // Put it at the back of the list for fairness, even though we won't be
                    // unchoking this time
                    removed.add(peer);
                } else if (peer.isInteresting() && peer.isChoked()) {
                    // If they are choking us make someone else a downloader
                    if (_log.shouldDebug())
                        _log.debug("Choking peer that's choking us " + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;

                    // Put it at the back of the list
                    removed.add(peer);
                } else if (!peer.isInteresting()
                        && !coordinator.completed()
                        &&
                        // give new peers a better chance to get their first two pieces
                        (peer.completed() >= 2 || random.nextInt(4) == 0)) {
                    // If they aren't interesting make someone else a downloader
                    if (_log.shouldDebug()) _log.debug("Choking uninteresting peer "
                                                       + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;

                    // Put it at the back of the list
                    removed.add(peer);
                } else if (peer.isInterested() && peer.isSnubbing()) {
                    // Peer wants our pieces but never requests any; free the upload slot
                    if (_log.shouldDebug()) _log.debug("Choking snubbing peer "
                                                       + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;

                    // Put it at the back of the list
                    removed.add(peer);
                } else if (peer.isInteresting() && !peer.isChoked()
                        && uploaderNotDelivering(download, peer.getDownloadRate())) {
                    // We are downloading but didn't receive anything...
                    if (_log.shouldDebug())
                        _log.debug("Choking an uploader that has sent us nothing -> Peer: "
                                   + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;
                    // Put it at the back of the list
                    removed.add(peer);
                } else if (peer.isInteresting() && !peer.isChoked() && download < worstdownload) {
                    // Make sure download is good if we are uploading
                    worstdownload = download;
                    worstDownloader = peer;
                } else if (upload < worstdownload
                        && coordinator.completed()
                        &&
                        // give new peers a better chance to get their first four pieces
                        (peer.completed() >= 4 || random.nextInt(8) == 0)) {
                    // Make sure upload is good if we are seeding
                    worstdownload = upload;
                    worstDownloader = peer;
                }
            }
            peer.retransmitRequests();
            int count = _runCount + i;
            // send PEX, about every 12 minutes
            if ((count % 17) == 0 && !peer.isCompleted()) coordinator.sendPeers(peer);
            // send Comment Request, about every 30 minutes
            if (fetchComments && (count % 47) == 0) coordinator.sendCommentReq(peer);
            // cheap failsafe for seeds connected to seeds, stop pinging and hopefully
            // the inactive checker (above) will eventually disconnect it
            if ((count & 0x01) == 0 &&
                inactiveTime > 29*1000 &&
                (coordinator.getNeededLength() > 0 || !peer.isCompleted()))
                peer.keepAlive();
            // announce them to local tracker (TrackerClient does this too)
            if (dht != null && ((_runCount + _phase) % 5) == 0) {
                dht.announce(
                        coordinator.getInfoHash(),
                        peer.getPeerID().getDestHash(),
                        peer.isCompleted());
            }
        } // for peer

        // Resync actual uploaders value
        // (can shift a bit by disconnecting peers)
        coordinator.setUploaders(uploaders, interestedUploaders);

        // Remove the worst downloader if needed. (uploader if seeding)
        if (((uploaders == uploadLimit && coordinator.getInterestedAndChoking() > 0)
                        || uploaders > uploadLimit)
                && worstDownloader != null) {
            if (_log.shouldDebug())
                _log.debug("Choking worst downloader [" + worstDownloader + "]");

            worstDownloader.setChoking(true);
            coordinator.decrementUploaders(worstDownloader.isInterested());
            removedCount++;

            // Put it at the back of the list
            removed.add(worstDownloader);
        }

        boolean coordOver = coordinator.overUpBWLimit(uploaded);
        synchronized (coordinator.peers) {
            if ((!overBWLimit) && !coordOver) {
                // Optimistically unchoke a peer
                // must be called inside synch
                coordinator.unchokePeer();
            }
            // Put peers back at the end of the list that we removed earlier.
            for (Peer peer : removed) {
                if (coordinator.peers.remove(peer)) coordinator.peers.add(peer);
            }
        }

        coordinator.addInterestedAndChoking(removedCount);

        // store the rates
        coordinator.setRateHistory(uploaded, downloaded);
        if (_log.shouldDebug())
            _log.debug("END peers: " + peerList.size() + " uploaders: " + uploaders
                       + " interested: " + interestedUploaders);

        // close out unused files, but we don't need to do it every time
        Storage storage = coordinator.getStorage();
        if (storage != null) {
            // The more files a torrent has, the more often we call the cleaner,
            // to keep from running out of FDs
            int files = storage.getFileCount();
            int skip;
            if (files == 1) skip = 6;
            else if (files <= 4) skip = 4;
            else if (files <= 20) skip = 3;
            else if (files <= 50) skip = 2;
            else skip = 1;

            if (((_runCount + _phase) % skip) == 0) storage.cleanRAFs();
        }

        // announce ourselves to local tracker (TrackerClient does this too)
        if (dht != null && ((_runCount + _phase) % 16) == 0) {
            dht.announce(coordinator.getInfoHash(), coordinator.completed());
        }
    }

    /**
     * Whether the choke cascade must leave this peer alone because it is a source for the
     * last pieces. In the end game the peers holding the fewest pieces left are the only
     * sources there are, and a choked peer has no reason to keep sending them, so the
     * cost of choking one is a stalled download while the benefit is a single upload slot
     * we are barely using. Only the upload bandwidth limit outranks that - it is a hard
     * cap on what the router can send, not a preference.
     *
     * @param inEndGame true while pieces are still wanted and only a few are left
     * @param peerHasNeededPiece true if this peer's bitfield covers a piece we still want
     * @param overBWLimitChoke true if this peer would be choked to stay inside the
     *                         router's upload bandwidth limit
     * @return true to skip this peer when choosing whom to choke
     * @since 0.9.72+
     */
    static boolean spareEndgameSource(boolean inEndGame, boolean peerHasNeededPiece,
                                      boolean overBWLimitChoke) {
        return inEndGame && peerHasNeededPiece && !overBWLimitChoke;
    }

    /**
     * Whether a peer we are downloading from has failed to deliver often enough to be
     * worth choking. One empty check period is not a verdict: a peer serving several
     * torrents at once, or filling one block of a piece shared with other peers, can go
     * several passes without sending us anything, and choking it then is how a nearly
     * complete download stalls.
     *
     * @param downloadThisPass bytes received from the peer since the last check
     * @param downloadRate the peer's average download rate over the rate window, in bytes
     *                     per second
     * @return true if the whole rate window is empty
     * @since 0.9.72+
     */
    static boolean uploaderNotDelivering(long downloadThisPass, long downloadRate) {
        return downloadThisPass == 0 && downloadRate == 0;
    }
}
