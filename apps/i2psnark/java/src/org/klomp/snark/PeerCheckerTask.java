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

    /**
     * How often, in checker passes, to send a peer our PEX list. One pass is
     * {@link PeerCoordinator#CHECK_PERIOD}, so 17 passes is a little over a minute.
     * Sent to a completed peer too: a seed is the peer that most needs to hear about
     * the downloaders we know, and withholding PEX from seeds is one reason a seeding
     * torrent ends up with nobody to upload to.
     */
    static final int PEX_PERIOD = 17;

    /**
     * How often, in checker passes, to announce a connected peer to the local DHT so other
     * nodes can find it. One pass is {@link PeerCoordinator#CHECK_PERIOD}, so 120 passes is
     * ten minutes. The announce is per peer, so a short period multiplies into tens of
     * thousands of datagrams per window for no extra discoverability: a DHT peer entry only
     * has to stay fresh for well under half an hour.
     */
    static final int DHT_ANNOUNCE_PERIOD = 120;

    /**
     * How often, in checker passes, to offer a peer a comment request. One pass is
     * {@link PeerCoordinator#CHECK_PERIOD}, so 47 passes is a little under four minutes.
     * This only rotates which peer is offered; {@code PeerCoordinator.sendCommentReq()}
     * enforces the real interval between requests.
     */
    static final int COMMENT_PERIOD = 47;

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
     * Whether this pass sends the peer our PEX list. Deliberately independent of whether
     * the peer already has every piece: a seed is the peer that most needs to hear about
     * the downloaders we know.
     *
     * @param count the checker pass counter for this peer
     * @param period how many passes two PEX sends are spaced by
     * @return true to send PEX to this peer on this pass
     * @since 0.9.72+
     */
    static boolean sendPEX(int count, int period) {
        return count % period == 0;
    }

    /**
     * Whether this pass announces a connected peer to the local DHT.
     *
     * @param count the checker pass counter for this torrent, phase included
     * @param period how many passes two announces are spaced by
     * @return true to announce on this pass
     * @since 0.9.72+
     */
    static boolean announcePeerToDHT(int count, int period) {
        return count % period == 0;
    }

    /**
     * Whether this torrent does the swarm-side work at all: sending PEX, asking for
     * comments, and announcing itself and its peers to the local DHT. A metadata lookup
     * holds no data for the torrent, is deleted minutes after it resolves, and is not a
     * member of the swarm it is asking about, so each of those messages is traffic it
     * never earns - and the announces are the ones a node refuses for want of a token.
     * The pass keeps its peer bookkeeping: a metadata source that goes silent still has
     * to be dropped for another to be tried.
     *
     * @param metadataOnly true for a transient metadata-lookup torrent
     * @return true to send the swarm-side messages
     * @since 0.9.72+
     */
    static boolean servesSwarm(boolean metadataOnly) {
        return !metadataOnly;
    }

    /**
     * How many checker periods this pass's byte counts cover. A metadata lookup's checker
     * runs at a multiple of the download cadence, while the rate ring divides every sample
     * by a single period, so an unscaled lookup sample would read that many times too high.
     *
     * @param metadataOnly true for a transient metadata-lookup torrent
     * @return the number of checker periods the sample covers, 1 or more
     * @since 0.9.72+
     */
    static int ratePeriods(boolean metadataOnly) {
        return metadataOnly ? PeerCoordinator.LOOKUP_CHECK_MULTIPLIER : 1;
    }

    /**
     * Whether the checker has to choke someone to make an upload slot available. It does
     * when the torrent is at its uploader ceiling and peers are waiting for a slot, or is
     * over that ceiling - which happens while the global upload limit is exceeded, where
     * the ceiling sits one below the current count on purpose. The ceiling comes from
     * {@link PeerCoordinator#uploaderCeiling()}, not from
     * {@link PeerCoordinator#allowedUploaders()}: that one is always one above the count
     * the torrent holds, so the comparison could never be true and the cascade never ran.
     *
     * @param uploaders how many uploaders the pass counts now
     * @param ceiling the uploader ceiling from {@link PeerCoordinator#uploaderCeiling()}
     * @param interestedAndChoking peers interested in us that we are choking, so peers
     *                             waiting for a slot
     * @return true to choke a peer to make room
     * @since 0.9.72+
     */
    static boolean makesRoom(int uploaders, int ceiling, int interestedAndChoking) {
        return uploaders == ceiling && interestedAndChoking > 0
                || uploaders > ceiling;
    }

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
        int uploadLimit = coordinator.uploaderCeiling();
        boolean overBWLimit = coordinator.overUpBWLimit();
        // end game is per-torrent state, so decide it once for the whole pass
        boolean endGame = coordinator.inEndGame();
        // no "pass starting" line - the pass logs its outcome at the end, and the
        // two lines printed the same keys twice
        DHT dht = _util.getDHTForTorrent(coordinator.getInfoHash());
        // a metadata lookup is not a member of the swarm it is asking about - see servesSwarm()
        boolean metadataOnly = coordinator.isMetadataOnly();
        boolean swarm = servesSwarm(metadataOnly);
        boolean fetchComments = swarm && _util.utCommentsEnabled();
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

            // The same pair of counts PeerCoordinator keeps incrementally: every unchoked
            // peer holds an upload slot, and only the interested ones count against the
            // global limit - see getInterestedUploaders().
            if (!peer.isChoking()) {
                uploaders++;
                if (peer.isInterested()) {
                    interestedUploaders++;
                }
            }

            long upload = peer.getUploaded();
            uploaded += upload;
            long download = peer.getDownloaded();
            downloaded += download;
            peer.setRateHistory();

            if (_log.shouldDebug()) {
                // A per-peer tally, so the rates go on their own line - the value is
                // bytes per second, KILOPERSECOND being the rate one check per period makes
                _log.debug("Peer check of " + SnarkLogFormatter.peer(peer)
                           + " -> " + ourState(peer.isInteresting(), peer.isChoking())
                           + "\n* " + theirState(peer.isInterested(), peer.isChoked())
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
            if ((makesRoom(cup, uploadLimit, coordinator.getInterestedAndChoking())
                            || overBWLimitChoke)
                    && !peer.isChoking()
                    && !spareSource) {
                // Check if it still wants pieces from us.
                if (!peer.isInterested()) {
                    // Note that we only choke if we are over our limits,
                    // so a peer may remain unchoked even if uninterested.
                    if (_log.shouldDebug())
                        _log.debug("Choking the peer -> It wants nothing from us"
                                   + " * Peer: " + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    coordinator.decrementUploaders(false);

                    // Put it at the back of the list
                    removed.add(peer);
                } else if (overBWLimitChoke) {
                    if (_log.shouldDebug())
                        _log.debug("Choking the peer -> Over the upload limit"
                                   + " * Peer: " + SnarkLogFormatter.peer(peer)
                                   + "\n* From this peer: " + upload + " bytes"
                                   + " * Total: " + uploaded + " bytes");
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
                        _log.debug("Choking the peer -> It is choking us"
                                   + " * Peer: " + SnarkLogFormatter.peer(peer));
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
                    if (_log.shouldDebug())
                        _log.debug("Choking the peer -> We want nothing from it"
                                   + " * Peer: " + SnarkLogFormatter.peer(peer));
                    peer.setChoking(true);
                    uploaders--;
                    interestedUploaders--;
                    coordinator.decrementUploaders(true);
                    removedCount++;

                    // Put it at the back of the list
                    removed.add(peer);
                } else if (peer.isInterested() && peer.isSnubbing()) {
                    // Peer wants our pieces but never requests any; free the upload slot
                    if (_log.shouldDebug())
                        _log.debug("Choking the peer -> It is snubbing us"
                                   + " * Peer: " + SnarkLogFormatter.peer(peer));
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
                        _log.debug("Choking the peer -> It has sent us nothing"
                                   + " * Peer: " + SnarkLogFormatter.peer(peer));
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
            // send PEX, a little over a minute apart
            if (swarm && sendPEX(count, PEX_PERIOD)) coordinator.sendPeers(peer);
            // ask for comments on this pass; the coordinator holds the real interval
            // between requests to one per COMMENT_REQ_INTERVAL, so this only offers
            // the next peer in line once that interval has passed
            if (fetchComments && (count % COMMENT_PERIOD) == 0) coordinator.sendCommentReq(peer);
            // cheap failsafe for seeds connected to seeds, stop pinging and hopefully
            // the inactive checker (above) will eventually disconnect it
            if ((count & 0x01) == 0 &&
                inactiveTime > 29*1000 &&
                (coordinator.getNeededLength() > 0 || !peer.isCompleted()))
                peer.keepAlive();
            // announce them to local tracker (TrackerClient does this too)
            if (swarm && dht != null
                    && announcePeerToDHT(_runCount + _phase, DHT_ANNOUNCE_PERIOD)) {
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
        if (makesRoom(uploaders, uploadLimit, coordinator.getInterestedAndChoking())
                && worstDownloader != null) {
            if (_log.shouldDebug())
                _log.debug("Choking the peer -> Worst transfer rate"
                           + " * Peer: " + SnarkLogFormatter.peer(worstDownloader));

            worstDownloader.setChoking(true);
            coordinator.decrementUploaders(worstDownloader.isInterested());
            removedCount++;

            // Put it at the back of the list
            removed.add(worstDownloader);
        }

        synchronized (coordinator.peers) {
            // Move the peers choked this pass to the tail of the list first, so the
            // unchoke below works down the list from the head and does not pick one of
            // them back up again in the same pass.
            for (Peer peer : removed) {
                if (coordinator.peers.remove(peer)) coordinator.peers.add(peer);
            }
            // Called whether or not a slot may open: it is the only thing that recounts the
            // peers waiting for one, and it holds its own bandwidth-limit check, which
            // covers the over-limit tests this pass used to make here - a torrent alone
            // over the limit is necessarily over it in total.
            coordinator.unchokePeer(removed);
        }

        // store the rates - a lookup's pass covers more than one period, so the sample
        // is scaled back to what one period would have carried
        coordinator.setRateHistory(uploaded, downloaded, ratePeriods(metadataOnly));
        if (_log.shouldDebug()) {
            // one line for the pass instead of a START and an END pair printing the
            // same keys twice, and the keys say what they count; the two states that
            // only matter when they are abnormal get their own lines
            _log.debug("Peer check pass -> Peers checked: " + peerList.size()
                       + " * Uploaders: " + uploaders + " of " + uploadLimit
                       + " * Interested: " + interestedUploaders
                       + (removedCount > 0 ? "\n* Choked: " + removedCount + " peers" : "")
                       + (overBWLimit ? "\n* Over the upload limit" : ""));
        }

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
        if (swarm && dht != null && ((_runCount + _phase) % 16) == 0) {
            dht.announce(coordinator.getInfoHash(), coordinator.completed());
        }
    }

    /**
     * Our half of the choke pair, in words. The two flags are logged as prose because
     * {@code We want: false} says nothing to a reader who has not the field names in
     * front of them, and the console renders a bare true or false as a glyph.
     *
     * @param interesting true if the peer holds pieces we want
     * @param choking true if we are throttling the peer
     * @return the clause naming what we want from the peer and what we do to it
     * @since 0.9.72+
     */
    static String ourState(boolean interesting, boolean choking) {
        return (interesting ? "We want pieces from it" : "We want nothing from it")
               + ", "
               + (choking ? "we are choking it" : "we have unchoked it");
    }

    /**
     * The peer's half of the choke pair, in words, for the same reason as
     * {@link #ourState(boolean, boolean)}.
     *
     * @param interested true if the peer wants pieces from us
     * @param choked true if the peer is throttling us
     * @return the clause naming what the peer wants from us and what it does to us
     * @since 0.9.72+
     */
    static String theirState(boolean interested, boolean choked) {
        return (interested ? "It wants pieces from us" : "It wants nothing from us")
               + ", "
               + (choked ? "it is choking us" : "it has unchoked us");
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
