/* TrackerClient - Class that informs a tracker and gets new peers.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConvertToHash;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import org.klomp.snark.bencode.InvalidBEncodingException;
import org.klomp.snark.dht.DHT;

/**
 * Handles communication with BitTorrent trackers and DHT to discover new peers.
 *
 * <p>This class manages tracker announcements and peer discovery by:
 *
 * <ul>
 *   <li>Announcing torrent status to HTTP/HTTPS trackers
 *   <li>Processing tracker responses and extracting peer information
 *   <li>Managing UDP tracker communication
 *   <li>Integrating with DHT for trackerless peer discovery
 *   <li>Handling Peer Exchange (PEX) when available
 *   <li>Managing announcement intervals and retry logic
 *   <li>Processing various tracker events (started, completed, stopped)
 * </ul>
 *
 * <p><strong>Threading Model:</strong> The start() method creates a thread that runs one complete
 * announcement cycle through all trackers, PEX, and DHT, then queues a new timed event and exits.
 * This approach prevents thread accumulation while ensuring regular announcements.
 *
 * <p>The client can be restarted after halt() and handles various tracker responses including
 * registration failures and error conditions.
 *
 * @author Mark Wielaard (mark@klomp.org)
 * @since 0.1.0
 */
public class TrackerClient implements Runnable {
    private final Log _log;
    private static final String NO_EVENT = "";
    private static final String STARTED_EVENT = "started";
    private static final String COMPLETED_EVENT = "completed";
    private static final String STOPPED_EVENT = "stopped";
    private static final String NOT_REGISTERED = "torrent not registered"; // bytemonsoon
    private static final String NOT_REGISTERED_2 = "torrent not found"; // diftracker
    private static final String NOT_REGISTERED_3 = "torrent unauthorised"; // vuze
    private static final String ERROR_GOT_HTML = "received html (invalid response)"; // fake return

    private static final int SLEEP = 5; // 5 minutes.
    private static final int DELAY_MIN = 2000; // 2 secs.
    private static final int DELAY_RAND = 6 * 1000;
    private static final int MAX_REGISTER_FAILS = 15; // * INITIAL_SLEEP = 15m to register
    private static final int INITIAL_SLEEP = 90 * 1000;
    private static final int MAX_CONSEC_FAILS = 10; // slow down after this
    private static final int LONG_SLEEP = 10 * 60 * 1000; // sleep a while after lots of fails
    private static final long MIN_TRACKER_ANNOUNCE_INTERVAL = 10 * 60 * 1000;
    private static final long MIN_DHT_ANNOUNCE_INTERVAL = 15 * 60 * 1000;

    /** No guidance in BEP 5; standard practice is K (=8) */
    private static final int DHT_ANNOUNCE_PEERS = 8;

    public static final int PORT = 6881;
    private static final int DEFAULT_UDP_TRACKER_PORT = 6969;
    private static final int MAX_TRACKERS = 12;
    // tracker.welterde.i2p
    private static final Hash DSA_ONLY_TRACKER =
            ConvertToHash.getHash("cfmqlafjfmgkzbt4r3jsfyhgsr5abgxryl6fnz3d3y5a365di5aa.b32.i2p");

    private final I2PSnarkUtil _util;
    // non-final for reinitialize()
    private MetaInfo meta;
    private final String infoHash;
    private final String peerID;
    private final String additionalTrackerURL;
    private final PeerCoordinator coordinator;
    private final Snark snark;
    private final int port;
    private final String _threadName;

    private volatile boolean stop = true;
    private volatile boolean started;
    private volatile boolean _initialized;
    private volatile int _runCount;
    // running thread so it can be interrupted
    private volatile Thread _thread;
    // queued event so it can be cancelled
    private volatile SimpleTimer2.TimedEvent _event;
    // these 2 used in loop()
    private volatile boolean runStarted;
    private volatile int consecutiveFails;
    // if we don't want anything else.
    // Not necessarily seeding, as we may have skipped some files.
    private boolean completed;
    private volatile boolean _fastUnannounce;
    private long lastDHTAnnounce;
    private final List<TCTracker> trackers;
    private final List<TCTracker> backupTrackers;
    private long _startedOn;

    /**
     * Call start() to start it.
     *
     * @param meta null if in magnet mode
     * @param additionalTrackerURL may be null, from the ?tr= param in magnet mode, otherwise
     *     ignored
     */
    public TrackerClient(
            I2PSnarkUtil util,
            MetaInfo meta,
            String additionalTrackerURL,
            PeerCoordinator coordinator,
            Snark snark) {
        super();
        // Set unique name.
        byte[] hash = snark.getInfoHash();
        _threadName = "TrackerClient " + I2PSnarkUtil.toHex(hash);
        _util = util;
        _log = util.getContext().logManager().getLog(TrackerClient.class);
        this.meta = meta;
        this.additionalTrackerURL = additionalTrackerURL;
        this.coordinator = coordinator;
        this.snark = snark;

        this.port = PORT; // (port == -1) ? 9 : port;
        this.infoHash = urlencode(hash);
        this.peerID = urlencode(snark.getID());
        this.trackers = new ArrayList<TCTracker>(2);
        this.backupTrackers = new ArrayList<TCTracker>(2);
    }

    public synchronized void start() {
        if (!stop) {
            if (_log.shouldWarn()) {
                _log.warn("Already started: " + _threadName);
            }
            return;
        }
        stop = false;
        consecutiveFails = 0;
        runStarted = false;
        _fastUnannounce = false;
        snark.setTrackerProblems(null);
        _thread = new I2PAppThread(this, _threadName + " #" + (++_runCount), true);
        _thread.start();
        started = true;
    }

    public boolean halted() {
        return stop;
    }

    public boolean started() {
        return started;
    }

    /**
     * Interrupts this Thread to stop it.
     *
     * @param fast if true, limit the life of the unannounce threads
     */
    public synchronized void halt(boolean fast) {
        boolean wasStopped = stop;
        if (wasStopped) {
            if (_log.shouldWarn()) {
                _log.warn("Already stopped: " + _threadName);
            }
        } else {
            if (_log.shouldWarn()) {
                _log.warn("Stopping: " + _threadName);
            }
            stop = true;
        }
        SimpleTimer2.TimedEvent e = _event;
        if (e != null) {
            if (_log.shouldDebug()) {
                _log.debug("Cancelling next announce " + _threadName);
            }
            e.cancel();
            _event = null;
        }
        Thread t = _thread;
        if (t != null) {
            if (_log.shouldDebug()) {
                _log.debug("Interrupting " + t.getName());
            }
            t.interrupt();
        }
        _fastUnannounce = true;
        if (!wasStopped) {
            unannounce();
        }
    }

    private void queueLoop(long delay) {
        _event = new Runner(delay);
    }

    private class Runner extends SimpleTimer2.TimedEvent {
        public Runner(long delay) {
            super(_util.getContext().simpleTimer2(), delay);
        }

        public void timeReached() {
            _event = null;
            _thread =
                    new I2PAppThread(TrackerClient.this, _threadName + " #" + (++_runCount), true);
            _thread.start();
        }
    }

    private boolean verifyConnected() {
        while (!stop && !_util.connected()) {
            boolean ok = _util.connect();
            if (!ok) {
                try {
                    Thread.sleep(30 * 1000);
                } catch (InterruptedException ie) {
                }
            }
        }
        return !stop && _util.connected();
    }

    /**
     * Setup the first time only, then one pass (usually) through the trackers, PEX, and DHT. This
     * will take several seconds to several minutes.
     */
    public void run() {
        long begin = _util.getContext().clock().now();
        if (_log.shouldDebug()) _log.debug("Start " + Thread.currentThread().getName());
        try {
            if (!_initialized) {
                setup();
            }
            if (trackers.isEmpty() && _util.getDHT() == null) {
                stop = true;
                this.snark.addMessage(
                        _util.getString(
                                "No valid trackers for {0} - enable opentrackers or DHT?",
                                this.snark.getBaseName()));
                _log.error("No valid trackers for " + this.snark.getBaseName());
                this.snark.stopTorrent();
                return;
            }
            if (!_initialized) {
                _initialized = true;
                // FIXME only when starting everybody at once, not for a single torrent
                long delay = _util.getContext().random().nextInt(30 * 1000);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                }
            }
            loop();
        } finally {
            // don't hold ref
            _thread = null;
            if (_log.shouldDebug())
                _log.debug(
                        "Finish "
                                + Thread.currentThread().getName()
                                + " after "
                                + DataHelper.formatDuration(
                                        _util.getContext().clock().now() - begin));
        }
    }

    /**
     * Call after editing torrent
     *
     * @since 0.9.57
     */
    public synchronized void reinitialize() {
        if (!_initialized || !stop) {
            return;
        }
        trackers.clear();
        backupTrackers.clear();
        meta = snark.getMetaInfo();
        setup();
    }

    /**
     * Do this one time only (not every time it is started). Unless torrent was edited.
     *
     * @since 0.9.1
     */
    private void setup() {
        // Construct the list of trackers for this torrent,
        // starting with the primary one listed in the metainfo,
        // followed by the secondary open trackers
        // It's painful, but try to make sure if an open tracker is also
        // the primary tracker, that we don't add it twice.
        String primary = null;
        if (meta != null) {
            primary = meta.getAnnounce();
        } else if (additionalTrackerURL != null) {
            primary = additionalTrackerURL;
        }
        Set<Hash> trackerHashes = new HashSet<Hash>(8);

        // primary tracker
        if (primary != null) {
            if (isNewValidTracker(trackerHashes, primary)) {
                trackers.add(new TCTracker(primary, true));
                if (_log.shouldDebug())
                    _log.debug("Announce: [" + primary + "] [InfoHash " + infoHash + "]");
            } else {
                if (_log.shouldWarn())
                    _log.warn(
                            "Skipping invalid or non-i2p announce: "
                                    + primary
                                    + "\n* + Torrent: "
                                    + snark.getBaseName());
            }
        } else {
            if (_log.shouldWarn()) _log.warn("No primary announce for " + snark.getBaseName());
        }

        // announce list
        // We completely ignore the BEP 12 processing rules
        if (meta != null && !meta.isPrivate()) {
            List<String> urls = new ArrayList<String>(16);
            List<List<String>> list = meta.getAnnounceList();
            if (list != null) {
                for (List<String> llist : list) {
                    for (String url : llist) {
                        urls.add(url);
                    }
                }
            }
            // configured open trackers
            urls.addAll(_util.getOpenTrackers());
            if (urls.size() > 1) {
                Collections.shuffle(trackers, _util.getContext().random());
                if (_util.udpEnabled()) {
                    Collections.sort(urls, new URLComparator());
                } // sort the list to put udp first so it will trump http
            }

            for (String url : urls) {
                if (!isNewValidTracker(trackerHashes, url)) {
                    continue;
                }
                // first one is primary if we don't have a primary
                trackers.add(new TCTracker(url, trackers.isEmpty()));
                if (_log.shouldDebug()) {
                    _log.debug(
                            "Additional announce: [" + url + "] for [InfoHash " + infoHash + "]");
                }
            }
        }

        // backup trackers if DHT needs bootstrapping
        if (trackers.isEmpty() && (meta == null || !meta.isPrivate())) {
            List<String> tlist = _util.getBackupTrackers();
            for (int i = 0; i < tlist.size(); i++) {
                String url = tlist.get(i);
                if (!isNewValidTracker(trackerHashes, url)) continue;
                backupTrackers.add(new TCTracker(url, false));
                if (_log.shouldDebug())
                    _log.debug("Backup announce: [" + url + "] for [InfoHash " + infoHash + "]");
            }
            if (backupTrackers.isEmpty()) {
                backupTrackers.add(new TCTracker(SnarkManager.DEFAULT_BACKUP_TRACKER, false));
            } else if (trackers.size() > 1) {
                Collections.shuffle(backupTrackers, _util.getContext().random());
            }
        }
        this.completed = coordinator.getLeft() == 0;
        _startedOn = _util.getContext().clock().now();
    }

    /**
     * @param existing the ones we already know about
     * @param ann an announce URL non-null
     * @return true if ann is valid and new; adds to existing if returns true
     * @since 0.9.5
     */
    private boolean isNewValidTracker(Set<Hash> existing, String ann) {
        Hash h = getHostHash(ann);
        if (h == null) {
            if (_log.shouldWarn())
                _log.warn("Bad announce URL: [" + ann + "] \n* Torrent:" + snark.getBaseName());
            return false;
        }
        // comment this out if tracker.welterde.i2p upgrades
        if (h.equals(DSA_ONLY_TRACKER)) {
            Destination dest = _util.getMyDestination();
            if (dest != null && dest.getSigType() != SigType.DSA_SHA1) {
                if (_log.shouldWarn())
                    _log.warn(
                            "Skipping incompatible tracker: "
                                    + ann
                                    + "\n* Torrent: "
                                    + snark.getBaseName());
                return false;
            }
        }
        if (existing.size() >= MAX_TRACKERS) {
            if (_log.shouldInfo())
                _log.info(
                        "Not using announce URL, we have enough: ["
                                + ann
                                + "] \n* Torrent: "
                                + snark.getBaseName());
            return false;
        }
        boolean rv = existing.add(h);
        if (!rv) {
            if (_log.shouldInfo())
                _log.info(
                        "Duplicate announce URL: ["
                                + ann
                                + "] \n* Torrent: "
                                + snark.getBaseName());
        }
        return rv;
    }

    /**
     * Announce to all the trackers, get peers from PEX and DHT, then queue up a SimpleTimer2 event.
     * This will take several seconds to several minutes.
     *
     * @since 0.9.1
     */
    private void loop() {
        try {
            // normally this will only go once, then call queueLoop() and return
            while (!stop) {
                if (!verifyConnected()) {
                    stop = true;
                    return;
                }

                int webPeers = getWebPeers();

                // Local DHT tracker announce
                DHT dht = _util.getDHT();
                if (dht != null && (meta == null || !meta.isPrivate()))
                    dht.announce(snark.getInfoHash(), coordinator.completed());

                int oldSeenPeers = snark.getTrackerSeenPeers();
                int maxSeenPeers = 0;
                if (!trackers.isEmpty()) {
                    maxSeenPeers = getPeersFromTrackers(trackers);
                    // fast update for UI at startup
                    if (maxSeenPeers > oldSeenPeers) snark.setTrackerSeenPeers(maxSeenPeers);
                }
                int p = getPeersFromPEX();
                if (p > maxSeenPeers) maxSeenPeers = p;
                p = getPeersFromDHT();
                if (p > maxSeenPeers) {
                    maxSeenPeers = p;
                    // fast update for UI at startup
                    if (maxSeenPeers > oldSeenPeers) snark.setTrackerSeenPeers(maxSeenPeers);
                }
                // backup if DHT needs bootstrapping
                if (trackers.isEmpty()
                        && !backupTrackers.isEmpty()
                        && dht != null
                        && dht.size() < 16) {
                    p = getPeersFromTrackers(backupTrackers);
                    if (p > maxSeenPeers) maxSeenPeers = p;
                }

                // we could try and total the unique peers but that's too hard for now
                snark.setTrackerSeenPeers(maxSeenPeers + webPeers);

                if (stop) return;

                try {
                    // Sleep some minutes...
                    // Sleep the minimum interval for all the trackers, but 60s minimum
                    int delay;
                    Random r = _util.getContext().random();
                    int random = r.nextInt(120 * 1000);
                    if (completed && runStarted) delay = 3 * SLEEP * 60 * 1000 + random;
                    else if (snark.getTrackerProblems() != null
                            && ++consecutiveFails < MAX_CONSEC_FAILS) delay = INITIAL_SLEEP;
                    else if ((!runStarted) && _runCount < MAX_CONSEC_FAILS) delay = INITIAL_SLEEP;
                    else
                        // sleep a while, when we wake up we will contact only the trackers whose
                        // intervals have passed
                        delay = SLEEP * 60 * 1000 + random;

                    if (delay > 20 * 1000) {
                        // put ourselves on SimpleTimer2
                        if (_log.shouldDebug())
                            _log.debug(
                                    "Requeueing in "
                                            + DataHelper.formatDuration(delay)
                                            + ": "
                                            + Thread.currentThread().getName());
                        queueLoop(delay);
                        return;
                    } else if (delay > 0) {
                        Thread.sleep(delay);
                    }
                } catch (InterruptedException interrupt) {
                }
            } // *** end of while loop
        } // try
        catch (Throwable t) {
            _log.error("TrackerClient: " + t, t);
            if (t instanceof OutOfMemoryError) throw (OutOfMemoryError) t;
        }
    }

    /**
     * @return max peers seen
     */
    private int getPeersFromTrackers(List<TCTracker> trckrs) {
        long left = coordinator.getLeft(); // -1 in magnet mode

        // First time we got a complete download?
        boolean newlyCompleted;
        if (!completed && left == 0) {
            completed = true;
            newlyCompleted = true;
        } else {
            newlyCompleted = false;
        }

        // *** loop once for each tracker
        int maxSeenPeers = 0;
        for (TCTracker tr : trckrs) {
            if ((!stop)
                    && (!tr.stop)
                    && (completed || coordinator.needOutboundPeers() || !tr.started)
                    && (newlyCompleted
                            || System.currentTimeMillis() > tr.lastRequestTime + tr.interval)) {
                try {
                    long uploaded = coordinator.getUploaded();
                    long downloaded = coordinator.getDownloaded();
                    long len = snark.getTotalLength();
                    if (len > 0 && downloaded > len) {
                        downloaded = len;
                    }
                    left = coordinator.getLeft();
                    TrackerInfo info;
                    if (tr.isUDP) {
                        int event;
                        if (!tr.started) {
                            event = UDPTrackerClient.EVENT_STARTED;
                        } else if (newlyCompleted) {
                            event = UDPTrackerClient.EVENT_COMPLETED;
                        } else {
                            event = UDPTrackerClient.EVENT_NONE;
                        }
                        info = doRequest(tr, uploaded, downloaded, left, event);
                    } else {
                        String event;
                        if (!tr.started) {
                            event = STARTED_EVENT;
                        } else if (newlyCompleted) {
                            event = COMPLETED_EVENT;
                        } else {
                            event = NO_EVENT;
                        }
                        info = doRequest(tr, infoHash, peerID, uploaded, downloaded, left, event);
                    }
                    snark.setTrackerProblems(null);
                    tr.trackerProblems = null;
                    tr.registerFails = 0;
                    tr.consecutiveFails = 0;
                    if (tr.isPrimary) {
                        consecutiveFails = 0;
                    }
                    runStarted = true;
                    tr.started = true;
                    tr.seenPeers = info.getPeerCount();
                    // update rising number quickly
                    if (snark.getTrackerSeenPeers() < tr.seenPeers) {
                        snark.setTrackerSeenPeers(tr.seenPeers);
                    }
                    // auto stop
                    // These are very high thresholds for now, not configurable, just for update
                    // torrent
                    if (completed
                            && tr.isPrimary
                            && snark.isAutoStoppable()
                            && !snark.isChecking()
                            && info.getSeedCount() > 100
                            && coordinator.getPeerCount() <= 0
                            && _util.getContext().clock().now() > _startedOn + 30 * 60 * 1000
                            && snark.getTotalLength() > 0
                            && uploaded >= snark.getTotalLength() / 2) {

                        if (_log.shouldWarn()) {
                            _log.warn("Auto stopping " + snark.getBaseName());
                        }
                        snark.setAutoStoppable(false);
                        snark.stopTorrent();
                        return tr.seenPeers;
                    }

                    Set<Peer> peers = info.getPeers();
                    // Pass everybody over to our tracker
                    DHT dht = _util.getDHT();
                    if (dht != null) {
                        for (Peer peer : peers) {
                            dht.announce(
                                    snark.getInfoHash(),
                                    peer.getPeerID().getDestHash(),
                                    false); // TODO actual seed/leech status
                        }
                    }

                    if (coordinator.needOutboundPeers()) {
                        // We only want to talk to new people if we need things
                        // from them (duh)
                        List<Peer> ordered = new ArrayList<Peer>(peers);
                        Random r = _util.getContext().random();
                        Collections.shuffle(ordered, r);
                        Iterator<Peer> it = ordered.iterator();
                        while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
                            Peer cur = it.next();
                            // FIXME if id == us || dest == us continue;
                            // only delay if we actually make an attempt to add peer
                            if (coordinator.addPeer(cur) && it.hasNext()) {
                                int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                                try {
                                    Thread.sleep(delay);
                                } catch (InterruptedException ie) {
                                }
                            }
                        }
                    }
                } catch (IOException ioe) {
                    // Probably not fatal (if it doesn't last too long...)
                    if (_log.shouldWarn()) {
                        _log.warn("Error communicating with tracker [" +
                                  trackerB32ToHostname(tr.announce) + "]");
                    }
                    tr.trackerProblems = ioe.getMessage();
                    // Don't show secondary tracker problems to the user
                    // ... and only if we don't have any peers at all. Otherwise, PEX/DHT will save
                    // us.
                    if (tr.isPrimary
                            && coordinator.getPeers() <= 0
                            && (!completed
                                    || _util.getDHT() == null
                                    || _util.getDHT().size() <= 0)) {
                        snark.setTrackerProblems(tr.trackerProblems);
                    }
                    String tplc = tr.trackerProblems.toLowerCase(Locale.US);
                    if (tplc.startsWith(NOT_REGISTERED)
                            || tplc.startsWith(NOT_REGISTERED_2)
                            || tplc.startsWith(NOT_REGISTERED_3)
                            || tplc.startsWith(ERROR_GOT_HTML)) {
                        // Give a guy some time to register it if using opentrackers too
                        if (tr.registerFails++ > MAX_REGISTER_FAILS
                                || !completed /* no use retrying if we aren't seeding */
                                || tplc.startsWith(ERROR_GOT_HTML) /* fake msg from doRequest() */
                                || (!tr.isPrimary && tr.registerFails > MAX_REGISTER_FAILS / 2)) {
                            if (_log.shouldWarn()) {
                                _log.warn(
                                        "No longer announcing to "
                                                + tr.announce
                                                + " : "
                                                + tr.trackerProblems
                                                + " after "
                                                + tr.registerFails
                                                + " failures");
                            }
                            tr.stop = true;
                        }
                    }
                    if (++tr.consecutiveFails == MAX_CONSEC_FAILS) {
                        tr.seenPeers = 0;
                        if (tr.interval < LONG_SLEEP) {
                            tr.interval = LONG_SLEEP;
                        } // slow down
                    }
                }
            } else {
                if (_log.shouldInfo()) {
                    _log.info(
                            "Not announcing to "
                                    + tr.announce
                                    + "\n* Last announce: "
                                    + new Date(tr.lastRequestTime)
                                    + " (interval: "
                                    + DataHelper.formatDuration(tr.interval)
                                    + ")");
                }
            }
            if ((!tr.stop) && maxSeenPeers < tr.seenPeers) {
                maxSeenPeers = tr.seenPeers;
            }
        } // End of trackers loop here
        return maxSeenPeers;
    }

    private String trackerB32ToHostname(String url) {
        if (url == null) return null;

        String result = url.replace("http://", "");

        result = result.replace("ahsplxkbhemefwvvml7qovzl5a2b5xo5i7lyai7ntdunvcyfdtna.b32.i2p", "tracker2.postman.i2p");
        result = result.replace("lnQ6yoBTxQuQU8EQ1FlF395ITIQF-HGJxUeFvzETLFnoczNjQvKDbtSB7aHhn853zjVXrJBgwlB9sO57KakBDaJ50lUZgVPhjlI19TgJ-CxyHhHSCeKx5JzURdEW-ucdONMynr-b2zwhsx8VQCJwCEkARvt21YkOyQDaB9IdV8aTAmP~PUJQxRwceaTMn96FcVenwdXqleE16fI8CVFOV18jbJKrhTOYpTtcZKV4l1wNYBDwKgwPx5c0kcrRzFyw5~bjuAKO~GJ5dR7BQsL7AwBoQUS4k1lwoYrG1kOIBeDD3XF8BWb6K3GOOoyjc1umYKpur3G~FxBuqtHAsDRICkEbKUqJ9mPYQlTSujhNxiRIW-oLwMtvayCFci99oX8MvazPS7~97x0Gsm-onEK1Td9nBdmq30OqDxpRtXBimbzkLbR1IKObbg9HvrKs3L-kSyGwTUmHG9rSQSoZEvFMA-S0EXO~o4g21q1oikmxPMhkeVwQ22VHB0-LZJfmLr4SAAAA.i2p", "tracker2.postman.i2p");
        result = result.replace("w7tpbzncbcocrqtwwm3nezhnnsw4ozadvi2hmvzdhrqzfxfum7wa.b32.i2p", "opentracker.dg2.i2p");
        result = result.replace("afuuortfaqejkesne272krqvmafn65mhls6nvcwv3t7l2ic2p4kq.b32.i2p", "lyoko.i2p");
        result = result.replace("s5ikrdyjwbcgxmqetxb3nyheizftms7euacuub2hic7defkh3xhq.b32.i2p", "tracker.thebland.i2p");
        result = result.replace("nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p", "torrfreedom.i2p");
        result = result.replace("by7luzwhx733fhc5ug2o75dcaunblq2ztlshzd7qvptaoa73nqua.b32.i2p", "opentracker.skank.i2p");
        result = result.replace("punzipidirfqspstvzpj6gb4tkuykqp6quurj6e23bgxcxhdoe7q.b32.i2p", "opentracker.r4sas.i2p");
        result = result.replace("qimlze77z7w32lx2ntnwkuqslrzlsqy7774v3urueuarafyqik5a.b32.i2p", "sigmatracker.i2p");

        return result;
    }

    /**
     * @return max peers seen
     */
    private int getPeersFromPEX() {
        // Get peers from PEX
        int rv = 0;
        if (coordinator.needOutboundPeers() && (meta == null || !meta.isPrivate()) && !stop) {
            Set<PeerID> pids = coordinator.getPEXPeers();
            if (!pids.isEmpty()) {
                if (_log.shouldInfo()) {
                    _log.info("Received " + pids.size() + " from PEX");
                }
                List<Peer> peers = new ArrayList<Peer>(pids.size());
                for (PeerID pID : pids) {
                    peers.add(
                            new Peer(pID, snark.getID(), snark.getInfoHash(), snark.getMetaInfo()));
                }
                Random r = _util.getContext().random();
                Collections.shuffle(peers, r);
                Iterator<Peer> it = peers.iterator();
                while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
                    Peer cur = it.next();
                    if (coordinator.addPeer(cur) && it.hasNext()) {
                        int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                        }
                    }
                }
                rv = pids.size();
                pids.clear();
            }
        } else if (_log.shouldInfo()) {
            _log.info("Not requesting PEX peers for [" + infoHash + "]");
        }
        return rv;
    }

    /**
     * @return max peers seen
     */
    private int getPeersFromDHT() {
        // Get peers from DHT
        // FIXME this needs to be in its own thread
        int rv = 0;
        DHT dht = _util.getDHT();
        if (dht != null
                && (meta == null || !meta.isPrivate())
                && (!stop)
                && (meta == null
                        || _util.getContext().clock().now()
                                > lastDHTAnnounce + MIN_DHT_ANNOUNCE_INTERVAL)) {
            int numwant;
            if (!coordinator.needOutboundPeers()) {
                numwant = 1;
            } else {
                numwant = _util.getMaxConnections();
            }
            Collection<Hash> hashes =
                    dht.getPeersAndAnnounce(
                            snark.getInfoHash(),
                            numwant,
                            5 * 60 * 1000,
                            DHT_ANNOUNCE_PEERS,
                            3 * 60 * 1000,
                            coordinator.completed(),
                            numwant <= 1);
            if (!hashes.isEmpty()) {
                runStarted = true;
                lastDHTAnnounce = _util.getContext().clock().now();
                rv = hashes.size();
            } else {
                lastDHTAnnounce = 0;
            }
            if (_log.shouldInfo()) {
                _log.info(
                        "Received "
                                + hashes.size()
                                + " peer hashes from DHT"
                                + (_log.shouldDebug() ? "\n* DHT Peers: " + hashes : ""));
            }

            // Now try these peers
            if ((!stop) && !hashes.isEmpty()) {
                List<Peer> peers = new ArrayList<Peer>(hashes.size());
                for (Hash h : hashes) {
                    try {
                        PeerID pID = new PeerID(h.getData(), _util);
                        peers.add(
                                new Peer(
                                        pID,
                                        snark.getID(),
                                        snark.getInfoHash(),
                                        snark.getMetaInfo()));
                    } catch (InvalidBEncodingException ibe) {
                    }
                }
                Random r = _util.getContext().random();
                Collections.shuffle(peers, r);
                Iterator<Peer> it = peers.iterator();
                while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
                    Peer cur = it.next();
                    if (coordinator.addPeer(cur) && it.hasNext()) {
                        int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                        }
                    }
                }
            }
        } else if (_log.shouldInfo()) {
            _log.info("Not getting DHT peers");
        }
        return rv;
    }

    /**
     * @return valid web peers from metainfo
     * @since 0.9.49
     */
    private int getWebPeers() {
        if (meta == null) {
            return 0;
        }
        if (coordinator.getNeededLength() <= 0) {
            return 0;
        } // Prevent connecting out to a webseed for comments only
        List<String> urls = meta.getWebSeedURLs();
        if (urls == null || urls.isEmpty()) {
            return 0;
        }
        List<Peer> peers = new ArrayList<Peer>(urls.size());
        for (String url : urls) {
            Hash h = getHostHash(url);
            if (h == null) {
                continue;
            }
            try {
                PeerID pID = new PeerID(h.getData(), _util);
                byte[] id = new byte[20];
                System.arraycopy(WebPeer.IDBytes, 0, id, 0, 12);
                System.arraycopy(h.getData(), 0, id, 12, 8);
                pID.setID(id);
                URI uri = new URI(url);
                String host = uri.getHost();
                if (host == null) {
                    continue;
                }
                if (coordinator.isWebPeerBanned(host)) {
                    if (_log.shouldWarn()) {
                        _log.warn("Skipping banned webseed " + url + "...");
                    }
                    continue;
                }
                peers.add(new WebPeer(coordinator, uri, pID, snark.getMetaInfo()));
            } catch (InvalidBEncodingException ibe) {
            } catch (URISyntaxException use) {
            }
        }

        if (peers.isEmpty()) {
            return 0;
        }
        Random r = _util.getContext().random();
        if (peers.size() > 1) {
            Collections.shuffle(peers, r);
        }
        Iterator<Peer> it = peers.iterator();
        while ((!stop) && it.hasNext() && coordinator.needOutboundPeers()) {
            Peer cur = it.next();
            if (coordinator.addPeer(cur) && it.hasNext()) {
                int delay = r.nextInt(DELAY_RAND) + DELAY_MIN;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                }
            }
        }
        return peers.size();
    }

    /**
     * Creates a thread for each tracker in parallel if tunnel is still open
     *
     * @since 0.9.1
     */
    private void unannounce() {
        DHT dht = _util.getDHT(); // Local DHT tracker unannounce
        if (dht != null) {
            dht.unannounce(snark.getInfoHash());
        }
        int i = 0;
        for (TCTracker tr : trackers) {
            if (_util.connected() && tr.started && (!tr.stop) && tr.trackerProblems == null) {
                try {
                    (new I2PAppThread(new Unannouncer(tr), _threadName + " U" + (++i), true))
                            .start();
                } catch (OutOfMemoryError oom) {
                    tr.reset();
                } // probably ran out of threads, ignore
            } else {
                tr.reset();
            }
        }
    }

    /**
     * Send "stopped" to a single tracker
     *
     * @since 0.9.1
     */
    private class Unannouncer implements Runnable {
        private final TCTracker tr;

        public Unannouncer(TCTracker tr) {
            this.tr = tr;
        }

        public void run() {
            if (_log.shouldDebug()) {
                _log.debug("Running unannounce " + _threadName + " to " + tr.announce);
            }
            long uploaded = coordinator.getUploaded();
            long downloaded = coordinator.getDownloaded();
            long len = snark.getTotalLength();
            if (len > 0 && downloaded > len) {
                downloaded = len;
            }
            long left = coordinator.getLeft();
            try {
                // Don't try to restart I2CP connection just to say goodbye
                if (_util.connected()) {
                    if (tr.started && (!tr.stop) && tr.trackerProblems == null) {
                        if (tr.isUDP) {
                            doRequest(
                                    tr, uploaded, downloaded, left, UDPTrackerClient.EVENT_STOPPED);
                        } else {
                            doRequest(
                                    tr,
                                    infoHash,
                                    peerID,
                                    uploaded,
                                    downloaded,
                                    left,
                                    STOPPED_EVENT);
                        }
                    }
                }
            } catch (IOException ioe) {
                /* ignored */
            }
            tr.reset();
        }
    }

    /**
     * HTTP - blocking
     *
     * <p>Note: IOException message text gets displayed in the UI
     */
    private TrackerInfo doRequest(
            TCTracker tr,
            String infoHash,
            String peerID,
            long uploaded,
            long downloaded,
            long left,
            String event)
            throws IOException {
        StringBuilder buf = new StringBuilder(512);
        buf.append(tr.announce);
        if (tr.announce.contains("?")) {
            buf.append('&');
        } else {
            buf.append('?');
        }
        buf.append("info_hash=")
                .append(infoHash)
                .append("&peer_id=")
                .append(peerID)
                .append("&port=")
                .append(port)
                .append("&ip=")
                .append(_util.getOurIPString())
                .append(".i2p")
                .append("&uploaded=")
                .append(uploaded)
                .append("&downloaded=")
                .append(downloaded)
                .append("&left=");
        // What do we send for left in magnet mode? Can we omit it?
        if (left >= 0) {
            buf.append(left);
        } else {
            buf.append('1');
        }
        buf.append("&compact=1"); // NOTE: opentracker will return 400 for &compact alone
        if (!event.equals(NO_EVENT)) {
            buf.append("&event=").append(event);
        }
        buf.append("&numwant=");
        boolean small =
                left == 0 || event.equals(STOPPED_EVENT) || !coordinator.needOutboundPeers();
        if (small) {
            buf.append('0');
        } else {
            buf.append(_util.getMaxConnections());
        }
        String s = buf.toString();
        if (_log.shouldDebug()) {
            _log.debug("Sending TrackerClient request\n* URL: " + s);
        }

        tr.lastRequestTime = System.currentTimeMillis();
        // Don't wait for a response to stopped when shutting down
        boolean fast = _fastUnannounce && event.equals(STOPPED_EVENT);
        byte[] fetched =
                _util.get(s, true, fast ? -1 : 0, small ? 128 : 1024, small ? 1024 : 32 * 1024);
        if (fetched == null) {
            throw new IOException("No response from " + tr.host);
        }
        if (fetched.length == 0) {
            throw new IOException("No data from " + tr.host);
        }
        // The HTML check only works if we didn't exceed the maxium fetch size specified in get(),
        // otherwise we already threw an IOE.
        if (fetched[0] == '<') {
            throw new IOException(ERROR_GOT_HTML + " from " + tr.host);
        }

        InputStream in = new ByteArrayInputStream(fetched);
        TrackerInfo info =
                new TrackerInfo(in, snark.getID(), snark.getInfoHash(), snark.getMetaInfo(), _util);
        if (_log.shouldInfo()) {
            _log.info("TrackerClient " + tr.host + " response: " + info);
        }

        String failure = info.getFailureReason();
        if (failure != null) {
            throw new IOException(tr.host + " response: " + failure);
        }
        tr.interval = Math.max(MIN_TRACKER_ANNOUNCE_INTERVAL, info.getInterval() * 1000l);
        return info;
    }

    /**
     * UDP - blocking
     *
     * @return null if _fastUnannounce && event == STOPPED
     * @since 0.9.54
     */
    private TrackerInfo doRequest(
            TCTracker tr, long uploaded, long downloaded, long left, int event) throws IOException {
        UDPTrackerClient udptc = _util.getUDPTrackerClient();
        if (udptc == null) {
            throw new IOException("no UDPTC");
        }
        if (_log.shouldLog(Log.INFO)) {
            _log.info("Sending UDPTrackerClient request");
        }
        tr.lastRequestTime = System.currentTimeMillis();
        // Don't wait for a response to stopped when shutting down
        boolean fast = _fastUnannounce && event == UDPTrackerClient.EVENT_STOPPED;
        long maxWait = fast ? 5 * 1000 : 60 * 1000;
        boolean small =
                left == 0
                        || event == UDPTrackerClient.EVENT_STOPPED
                        || !coordinator.needOutboundPeers();
        int numWant = small ? 0 : _util.getMaxConnections();
        UDPTrackerClient.TrackerResponse fetched =
                udptc.announce(
                        snark.getInfoHash(),
                        snark.getID(),
                        numWant,
                        maxWait,
                        tr.host,
                        tr.port,
                        downloaded,
                        left,
                        uploaded,
                        event,
                        fast);
        if (fast) {
            return null;
        }
        if (fetched == null) {
            throw new IOException("UDP announce error to: " + tr.host);
        }
        TrackerInfo info =
                new TrackerInfo(
                        fetched.getPeers(),
                        fetched.getInterval(),
                        fetched.getSeedCount(),
                        fetched.getLeechCount(),
                        fetched.getFailureReason(),
                        snark.getID(),
                        snark.getInfoHash(),
                        snark.getMetaInfo(),
                        _util);
        if (_log.shouldLog(Log.INFO)) {
            _log.info("TrackerClient response: " + info);
        }
        String failure = info.getFailureReason();
        if (failure != null) {
            throw new IOException(failure);
        }
        tr.interval = Math.max(MIN_TRACKER_ANNOUNCE_INTERVAL, info.getInterval() * 1000l);
        return info;
    }

    /**
     * Very lazy byte[] to URL encoder. Just encodes almost everything, even some "normal" chars. By
     * not encoding about 1/4 of the chars, we make random data like hashes about 16% smaller.
     *
     * <p>RFC1738: 0-9a-zA-Z$-_.+!*'(), Us: 0-9a-zA-Z
     */
    public static String urlencode(byte[] bs) {
        StringBuilder sb = new StringBuilder(bs.length * 3);
        for (int i = 0; i < bs.length; i++) {
            int c = bs[i] & 0xFF;
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                sb.append((char) c);
            } else {
                sb.append('%');
                if (c < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(c));
            }
        }
        return sb.toString();
    }

    /**
     * @param ann an announce URL, may be null, returns false if null
     * @return true for i2p hosts only
     * @since 0.7.12
     */
    public static boolean isValidAnnounce(String ann) {
        if (ann == null) {
            return false;
        }
        URI url;
        try {
            url = new URI(ann);
        } catch (URISyntaxException use) {
            return false;
        }
        String path = url.getPath();
        if (path == null || !path.startsWith("/")) {
            return false;
        }
        String scheme = url.getScheme();
        if (!("http".equals(scheme) || "udp".equals(scheme))) {
            return false;
        }
        String host = url.getHost();
        return host != null && (host.endsWith(".i2p") || host.equals("i2p"));
    }

    /**
     * This also validates the URL.
     *
     * @param ann an announce URL non-null
     * @return a Hash for i2p hosts only, null otherwise
     * @since 0.9.5
     */
    private Hash getHostHash(String ann) {
        URI url;
        try {
            url = new URI(ann);
        } catch (URISyntaxException use) {
            return null;
        }
        String scheme = url.getScheme();
        if (!("http".equals(scheme) || (_util.udpEnabled() && "udp".equals(scheme)))) {
            return null;
        }
        String host = url.getHost();
        if (host == null) {
            // URI can't handle b64dest or b64dest.i2p if it contains '~'
            // but it doesn't throw an exception, just returns a null host
            if (ann.startsWith("http://") && ann.length() >= 7 + 516 && ann.contains("~")) {
                ann = ann.substring(7);
                int slash = ann.indexOf('/');
                if (slash >= 516) {
                    ann = ann.substring(0, slash);
                    if (ann.endsWith(".i2p")) {
                        ann = ann.substring(0, ann.length() - 4);
                    }
                    return ConvertToHash.getHash(ann);
                }
            }
            return null;
        }
        if (host.endsWith(".i2p")) {
            String path = url.getPath();
            if (path == null || !path.startsWith("/")) {
                return null;
            }
            return ConvertToHash.getHash(host);
        }
        if (host.equals("i2p")) {
            String path = url.getPath();
            if (path == null || path.length() < 517 || !path.startsWith("/")) {
                return null;
            }
            String[] parts = DataHelper.split(path.substring(1), "[/\\?&;]", 2);
            return ConvertToHash.getHash(parts[0]);
        }
        return null;
    }

    /**
     * UDP before HTTP
     *
     * @since 0.9.67
     */
    private static class URLComparator implements Comparator<String> {
        public int compare(String l, String r) {
            boolean ul = l.startsWith("udp://");
            boolean ur = r.startsWith("udp://");
            if (ul && !ur) {
                return -1;
            }
            if (ur && !ul) {
                return -1;
            }
            return 0;
        }
    }

    private static class TCTracker {
        final String announce;
        final String host;
        final boolean isPrimary;
        final boolean isUDP;
        final int port;
        long interval;
        long lastRequestTime;
        String trackerProblems;
        boolean stop;
        boolean started;
        int registerFails;
        int consecutiveFails;
        int seenPeers;

        /**
         * @param a must be a valid http URL with a path, or a udp URL (path is ignored)
         * @param p true if primary
         */
        public TCTracker(String a, boolean p) {
            announce = a;
            URI url;
            try {
                url = new URI(a);
                isUDP = "udp".equals(url.getScheme());
                host = url.getHost();
                int pt = url.getPort();
                if (pt < 0) {
                    pt = isUDP ? DEFAULT_UDP_TRACKER_PORT : 80;
                }
                port = pt;
            } catch (URISyntaxException use) {
                throw new IllegalArgumentException(use);
            } // shouldn't happen, already validated
            isPrimary = p;
            interval = INITIAL_SLEEP;
        }

        /**
         * Call before restarting
         *
         * @since 0.9.1
         */
        public void reset() {
            lastRequestTime = 0;
            trackerProblems = null;
            stop = false;
            started = false;
            registerFails = 0;
            consecutiveFails = 0;
            seenPeers = 0;
        }
    }
}
