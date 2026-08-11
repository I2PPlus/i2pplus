/* ConnectionAcceptor - Accepts connections and routes them to sub-acceptors.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer2;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts incoming I2P connections and routes them to the appropriate PeerAcceptor.
 *
 * <p>This class manages the main I2P server socket that listens for incoming peer connections and
 * routes them to the correct PeerAcceptor based on the torrent's info hash. It handles:
 *
 * <ul>
 *   <li>Listening on the I2P server socket for incoming connections
 *   <li>Basic connection validation and handshake parsing
 *   <li>Routing connections to the appropriate PeerAcceptor
 *   <li>Connection rate limiting and blacklisting of misbehaving peers
 *   <li>Thread management for accepting connections
 *   <li>Cleanup of stale connection data
 * </ul>
 *
 * <p>The acceptor supports multi-torrent operation by working with a PeerCoordinatorSet to
 * determine which torrent a connection belongs to based on the info hash in the BitTorrent
 * handshake.
 *
 * <p>When multi-dest is enabled, each torrent additionally runs a {@code TorrentAcceptLoop} on
 * its own server socket, so that incoming connections are received on the torrent's own
 * destination.
 *
 * <p>Connections that repeatedly fail protocol validation may be temporarily blacklisted to prevent
 * abuse.
 *
 * @since 0.1.0
 */
class ConnectionAcceptor implements Runnable {
    private final Log _log =
            I2PAppContext.getGlobalContext().logManager().getLog(ConnectionAcceptor.class);
    private final PeerAcceptor peeracceptor;
    private Thread thread;
    private final I2PSnarkUtil _util;
    private final TorrentDest _td;
    private final ObjectCounter<Hash> _badCounter = new ObjectCounter<>();
    private final Map<Hash, String> _badReasons = new ConcurrentHashMap<>();
    private final SimpleTimer2.TimedEvent _cleaner;
    /** Accept loops per destination, shared by the torrents pooled on it. */
    private final Map<TorrentDest, TorrentAcceptLoop> _torrentAcceptors = new ConcurrentHashMap<>();
    /** Stops the accept loop */
    private volatile boolean stop;
    /** Protocol errors within a window before the client is rejected. */
    private static final int MAX_BAD = 3;
    private static final long BAD_CLEAN_INTERVAL = 15 * (long) 60 * 1000;
    /** Maximum concurrent incoming connections in handshake */
    private static final int MAX_HANDLERS = 64;
    private final AtomicInteger activeHandlers = new AtomicInteger();

    /** Multitorrent. Caller MUST call startAccepting() */
    public ConnectionAcceptor(I2PSnarkUtil util, PeerCoordinatorSet set) {
        _util = util;
        _td = null;
        _cleaner = new Cleaner();
        peeracceptor = new PeerAcceptor(set);
    }

    /** May be called even when already running. May be called to start up again after halt(). */
    public synchronized void startAccepting() {
        stop = false;
        if (_log.shouldWarn()) {
            _log.warn(
                    "[I2PSnark] ConnectionAcceptor: Start accepting new thread? "
                            + (thread == null));
        }
        if (thread == null) {
            thread = new I2PAppThread(this, "SnarkAcceptor");
            thread.setDaemon(true);
            thread.start();
            _cleaner.reschedule(BAD_CLEAN_INTERVAL, false);
        }
    }

    /** Unused (single torrent). Do NOT call startAccepting(). */
    public ConnectionAcceptor(I2PSnarkUtil util, PeerAcceptor peeracceptor) {
        this.peeracceptor = peeracceptor;
        _util = util;
        _td = null;
        thread = new I2PAppThread(this, "SnarkAcceptor");
        thread.setDaemon(true);
        thread.start();
        _cleaner = new Cleaner();
    }

    /**
     * Single torrent on its own destination. Do NOT call startAccepting().
     *
     * @since 0.9.71+
     */
    public ConnectionAcceptor(I2PSnarkUtil util, PeerAcceptor peeracceptor, TorrentDest td) {
        this.peeracceptor = peeracceptor;
        _util = util;
        _td = td;
        thread = new I2PAppThread(this, "SnarkAcceptor");
        thread.setDaemon(true);
        thread.start();
        _cleaner = new Cleaner();
    }

    /** May be restarted later with startAccepting(). */
    public synchronized void halt() {
        if (stop) return;
        stop = true;
        locked_halt();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
        for (TorrentAcceptLoop loop : _torrentAcceptors.values()) {
            loop.halt();
        }
        _torrentAcceptors.clear();
    }

    /**
     * Start an accept loop on a torrent's destination, adding the torrent to the
     * destination's loop when one already runs for a pooled destination. Multitorrent only.
     *
     * @param td the torrent's destination
     * @param coordinator the torrent's peer coordinator
     * @since 0.9.71+
     */
    public void addTorrentAcceptor(TorrentDest td, PeerCoordinator coordinator) {
        TorrentAcceptLoop loop = _torrentAcceptors.get(td);
        if (loop == null) {
            PeerCoordinatorSet set = new PeerCoordinatorSet();
            set.add(coordinator);
            TorrentAcceptLoop created = new TorrentAcceptLoop(td, set);
            loop = _torrentAcceptors.putIfAbsent(td, created);
            if (loop == null) {
                loop = created;
                loop.start();
            } else {
                loop.add(coordinator);
            }
        } else {
            loop.add(coordinator);
        }
    }

    /**
     * Remove a torrent from its destination's accept loop, halting the loop when it no
     * longer serves any torrent. Multitorrent only.
     *
     * @param td the torrent's destination, or null if it never got one
     * @param coordinator the torrent's peer coordinator
     * @since 0.9.71+
     */
    public void removeTorrentAcceptor(TorrentDest td, PeerCoordinator coordinator) {
        if (td == null || coordinator == null) {
            return;
        }
        TorrentAcceptLoop loop = _torrentAcceptors.get(td);
        if (loop != null) {
            loop.remove(coordinator);
            if (loop.isEmpty()) {
                _torrentAcceptors.remove(td, loop);
                loop.halt();
            }
        }
    }

    /**
     * Caller must synch
     *
     * @since 0.9.9
     */
    private void locked_halt() {
        I2PServerSocket ss = getServerSocket();
        if (ss != null) {
            try {
                ss.close();
            } catch (I2PException ioe) { /* ignored */ }
        }
        _badCounter.clear();
        _badReasons.clear();
        _cleaner.cancel();
    }

    /**
     * The server socket for this acceptor's destination, or null.
     *
     * @return the server socket for this acceptor's destination, or null
     */
    private I2PServerSocket getServerSocket() {
        if (_td != null) {
            return _td.getServerSocket();
        }
        return _util.getServerSocket();
    }

    /**
     * The destination of this acceptor, or null.
     *
     * @return the destination of this acceptor, or null
     */
    private Destination getMyDestination() {
        if (_td != null) {
            return _td.getMyDestination();
        }
        return _util.getMyDestination();
    }

    /**
     * Effectively unused, would only be called if we changed I2CP host/port, which is hidden in the
     * gui if in router context
     */
    public synchronized void restart() {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        } else {
            startAccepting();
        }
    }

    /**
     * Returns the port this acceptor is listening on.
     *
     * @return the port number
     */
    public int getPort() {
        return TrackerClient.PORT;
    }

    @Override
    public void run() {
        try {
            run2();
        } finally {
            synchronized (this) {
                thread = null;
            }
        }
    }

    private void run2() {
        while (!stop) {
            I2PServerSocket serverSocket = getServerSocket();
            while ((serverSocket == null) && (!stop)) {
                if (_td == null && !(_util.isConnecting() || _util.connected())) {
                    stop = true;
                    break;
                }
                try {
                    Thread.sleep((long) 10 * 1000);
                } catch (InterruptedException ie) { /* ignored */ }
                serverSocket = getServerSocket();
            }
            if (stop) {
                break;
            }
            try {
                I2PSocket socket = serverSocket.accept();
                if (socket == null) {
                    continue;
                }
                handleSocket(socket, getMyDestination(), peeracceptor);
            } catch (RouterRestartException rre) {
                I2PAppContext ctx = I2PAppContext.getGlobalContext();
                String msg = "Waiting for I2P router restart...";
                if (_log.shouldWarn()) {
                    _log.warn("[I2PSnark] " + msg, rre);
                }
                if (!ctx.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
                try {
                    Thread.sleep(2 * (long) 60 * 1000);
                } catch (InterruptedException ie) { /* ignored */ }
                while (true) {
                    if (_util.connected() || _util.connect()) {
                        break;
                    }
                    try {
                        Thread.sleep((long) 60 * 1000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                msg = "Router restarted";
                if (_log.shouldWarn()) {
                    _log.warn("[I2PSnark] " + msg);
                }
                if (!ctx.isRouterContext()) {
                    System.out.println(" • " + msg);
                }
            } catch (I2PException ioe) {
                int level = stop ? Log.WARN : Log.ERROR;
                if (_log.shouldLog(level)) {
                    _log.log(level, "[I2PSnark] Error while accepting", ioe);
                }
                synchronized (this) {
                    if (!stop) {
                        locked_halt();
                        thread = null;
                        stop = true;
                    }
                }
            } catch (ConnectException ioe) {
                /*
                 * This is presumed to be due to socket closing by I2PSnarkUtil.disconnect(),
                 * which does not currently call our halt(), although it should
                 */
                if (_log.shouldWarn()) {
                    _log.warn("[I2PSnark] Error while accepting", ioe);
                }
                synchronized (this) {
                    if (!stop) {
                        locked_halt();
                        thread = null;
                        stop = true;
                    }
                }
            } catch (IOException ioe) {
                int level = stop ? Log.WARN : Log.ERROR;
                if (_log.shouldLog(level)) {
                    _log.log(level, "[I2PSnark] Error while accepting", ioe);
                }
                synchronized (this) {
                    if (!stop) {
                        locked_halt();
                        thread = null;
                        stop = true;
                    }
                }
            }
            // catch oom?
        }
        if (_log.shouldWarn()) {
            _log.warn("[I2PSnark] ConnectionAcceptor closed");
        }
    }

    /**
     * Validate an accepted socket and dispatch it to a handler thread.
     *
     * @param socket the accepted socket
     * @param myDest the destination of this acceptor, to drop connections from ourselves
     * @param pa the peer acceptor to route the connection to
     */
    private void handleSocket(I2PSocket socket, Destination myDest, PeerAcceptor pa) {
        if (socket.getPeerDestination().equals(myDest)) {
            _log.error("[I2PSnark] Dropping incoming connection from our own router");
            try {
                socket.reset();
            } catch (IOException ioe) { /* ignored */ }
            return;
        }
        Hash h = socket.getPeerDestination().calculateHash();
        if (socket.getLocalPort() == 80) {
            _badCounter.increment(h);
            _badReasons.put(h, "incoming HTTP connection");
            if (_log.shouldWarn()) {
                _log.warn(
                        "[I2PSnark] Dropping incoming HTTP connection from client ["
                                + h.toBase32().substring(0, 8)
                                + "]");
            }
            try {
                socket.reset();
            } catch (IOException ioe) { /* ignored */ }
            return;
        }
        int bad = _badCounter.count(h);
        if (bad >= MAX_BAD) {
            if (_log.shouldWarn()) {
                String reason = _badReasons.get(h);
                _log.warn(
                        "[I2PSnark] Rejecting incoming connection from client ["
                                + h.toBase32().substring(0, 8)
                                + "] after "
                                + bad
                                + " failures (Max is "
                                + MAX_BAD
                                + "), last reason: "
                                + (reason != null ? reason : "unknown"));
            }
            try {
                socket.reset();
            } catch (IOException ioe) { /* ignored */ }
            return;
        }
        if (activeHandlers.get() >= MAX_HANDLERS) {
            if (_log.shouldWarn()) {
                _log.warn(
                        "[I2PSnark] Too many incoming connections in handshake ("
                                + activeHandlers.get()
                                + "), dropping connection from ["
                                + h.toBase32().substring(0, 8)
                                + "]");
            }
            try {
                socket.close();
            } catch (IOException ioe) { /* ignored */ }
            return;
        }
        activeHandlers.incrementAndGet();
        Thread t = new I2PAppThread(new Handler(socket, pa), "SnarkIncoming");
        t.start();
    }

    /**
     * Accept loop for a destination, serving every torrent pooled on it, routed by the info
     * hash in the handshake. Multitorrent only.
     *
     * @since 0.9.71+
     */
    private class TorrentAcceptLoop implements Runnable {
        private final TorrentDest _td;
        private final PeerAcceptor _pa;
        private final PeerCoordinatorSet _set;
        private volatile boolean _stop;
        private Thread _thread;

        public TorrentAcceptLoop(TorrentDest td, PeerCoordinatorSet set) {
            _td = td;
            _set = set;
            _pa = new PeerAcceptor(set);
        }

        /**
         * Add a torrent's coordinator to this loop's routing set.
         *
         * @param coordinator the coordinator
         */
        public void add(PeerCoordinator coordinator) {
            _set.add(coordinator);
        }

        /**
         * Remove a torrent's coordinator from this loop's routing set.
         *
         * @param coordinator the coordinator
         */
        public void remove(PeerCoordinator coordinator) {
            _set.remove(coordinator);
        }

        /**
         * Whether no torrents are routed through this loop.
         *
         * @return true when no torrents are routed through this loop
         */
        public boolean isEmpty() {
            return !_set.iterator().hasNext();
        }

        public synchronized void start() {
            if (_thread != null) {
                return;
            }
            _stop = false;
            _thread = new I2PAppThread(this, "SnarkAcceptor-" + _td.getKey().substring(0, 6));
            _thread.setDaemon(true);
            _thread.start();
        }

        public synchronized void halt() {
            if (_stop) {
                return;
            }
            _stop = true;
            I2PServerSocket ss = _td.getServerSocket();
            if (ss != null) {
                try {
                    ss.close();
                } catch (I2PException ioe) { /* ignored */ }
            }
            Thread t = _thread;
            if (t != null) {
                t.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                runLoop();
            } finally {
                synchronized (this) {
                    _thread = null;
                }
            }
        }

        private void runLoop() {
            while (!_stop) {
                I2PServerSocket ss = _td.getServerSocket();
                if (ss == null) {
                    sleep(10 * (long) 1000);
                    continue;
                }
                Destination myDest = _td.getMyDestination();
                if (myDest == null) {
                    sleep(10 * (long) 1000);
                    continue;
                }
                try {
                    I2PSocket socket = ss.accept();
                    if (socket != null) {
                        handleSocket(socket, myDest, _pa);
                    }
                } catch (RouterRestartException rre) {
                    // the session may reconnect; wait and retry
                    sleep(2 * (long) 60 * 1000);
                } catch (ConnectException ce) {
                    // presumed due to the socket closing on halt() or session teardown
                    sleep(10 * (long) 1000);
                } catch (I2PException ioe) {
                    if (_log.shouldWarn()) {
                        _log.warn("[I2PSnark] Error while accepting", ioe);
                    }
                    sleep(10 * (long) 1000);
                } catch (IOException ioe) {
                    if (_log.shouldWarn()) {
                        _log.warn("[I2PSnark] Error while accepting", ioe);
                    }
                    sleep(10 * (long) 1000);
                }
            }
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                _stop = true;
                Thread.currentThread().interrupt();
            }
        }
    }

    private class Handler implements Runnable {
        private final I2PSocket _socket;
        private final PeerAcceptor _pa;

        public Handler(I2PSocket socket, PeerAcceptor pa) {
            _socket = socket;
            _pa = pa;
        }

        @Override
        public void run() {
            try {
                InputStream in = _socket.getInputStream();
                OutputStream out = _socket.getOutputStream();
                // this is for the readahead in PeerAcceptor.connection()
                in = new BufferedInputStream(in);
                if (_log.shouldDebug()) {
                    _log.debug(
                            "[I2PSnark] Handling socket from ["
                                    + _socket.getPeerDestination().calculateHash()
                                    + "]");
                }
                _pa.connection(_socket, in, out);
                // A successful handshake shows the client is healthy; drop
                // any protocol-error count so only serial failures blacklist
                _badCounter.clear(_socket.getPeerDestination().calculateHash());
                _badReasons.remove(_socket.getPeerDestination().calculateHash());
            } catch (PeerAcceptor.ProtocolException ihe) {
                Hash h = _socket.getPeerDestination().calculateHash();
                _badCounter.increment(h);
                _badReasons.put(h, "protocol error: " + ihe.getMessage());
                if (_log.shouldInfo()) {
                    _log.info(
                            "[I2PSnark] Protocol error from ["
                                    + _socket.getPeerDestination().calculateHash()
                                    + "]",
                            ihe);
                }
                try {
                    _socket.reset();
                } catch (IOException ignored) { /* ignored */ }
            } catch (IOException ioe) {
                if (_log.shouldDebug()) {
                    _log.debug(
                            "[I2PSnark] Error handling connection from ["
                                    + _socket.getPeerDestination().calculateHash()
                                    + "]",
                            ioe);
                }
                try {
                    _socket.reset();
                } catch (IOException ignored) { /* ignored */ }
            } finally {
                activeHandlers.decrementAndGet();
            }
        }
    }

    /**
     * @since 0.9.1
     */
    private class Cleaner extends SimpleTimer2.TimedEvent {

        public Cleaner() {
            super(_util.getContext().simpleTimer2());
        }

        public void timeReached() {
            if (stop) {
                return;
            }
            _badCounter.clear();
            schedule(BAD_CLEAN_INTERVAL);
        }
    }
}
