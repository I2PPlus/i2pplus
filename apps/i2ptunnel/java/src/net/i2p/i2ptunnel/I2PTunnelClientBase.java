/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;

/**
 * Abstract base class for I2P client tunnels with common functionality.
 * <p>
 * Manages I2PSocketManager creation, local server socket setup,
 * connection threading, and framework for shared and non-shared client tunnels.
 * Handles SSL configuration, connection timeouts, and provides abstract methods
 * for connection-specific implementations.
 * <p>
 * Extended by specific tunnel types like HTTP clients, SOCKS proxies,
 * and standard TCP tunnels.
 */
public abstract class I2PTunnelClientBase extends I2PTunnelTask implements Runnable {

    /** The logger */
    protected final Log _log;
    /** The application context */
    protected final I2PAppContext _context;
    /** The logging instance */
    protected final Logging l;
    /** Default connect timeout */
    static final long DEFAULT_CONNECT_TIMEOUT = (long) 30*1000;
    /**
     *  Legacy cap on consecutive connect timeouts before abandoning tunnel
     *  failover. The leg walk no longer stops on a timeout count — every
     *  configured leg is tried while the pool has capacity — so the walk is
     *  now bounded by {@link #REQUEST_CONNECT_DEADLINE_MS} instead. Retained
     *  as a documented legacy bound.
     *  @since 0.9.71+
     */
    static final int MAX_TIMEOUT_FAILOVER = 2;
    /**
     *  Single wall-clock budget for establishing one proxied request.
     *  Naming lookups, every tunnel-failover leg, retry sleeps, and outer
     *  connect re-walks all draw from one deadline computed from this budget
     *  instead of each keeping an independent limit, so a quantity-4 leg walk
     *  plus outer retries can no longer stack well past two minutes before
     *  the request gives up.
     *  @since 0.9.71+
     */
    static final long REQUEST_CONNECT_DEADLINE_MS = 120 * 1000;
    /**
     *  Deadline sentinel for callers that have not adopted the shared
     *  request budget (SOCKS, IRC, DCC, raw {@code createI2PSocket}): they
     *  keep their original per-call limits because this sentinel never reads
     *  as expired and never clamps.
     *  @since 0.9.71+
     */
    static final long NO_DEADLINE = Long.MAX_VALUE;
    /** Client ID counter */
    private static final AtomicLong __clientId = new AtomicLong();
    /** This client's ID */
    protected long _clientId;
    /** Guards sockMgr and mySockets */
    protected final Object sockLock = new Object();
    /** The socket manager */
    protected I2PSocketManager sockMgr; // should be final and use a factory. LINT
    /** List of active sockets */
    protected final List<I2PSocket> mySockets = new ArrayList<>();
    /** Whether we own our destination */
    protected boolean _ownDest;
    /** The destination */
    protected Destination dest;
    /** Local port */
    private volatile int localPort;
    /** Handler name */
    private final String _handlerName;

    /**
     *  Protected for I2Ping since 0.9.11. Not for use outside package.
     */
    protected boolean listenerReady;
    /** Server socket */
    protected ServerSocket ss;
    /** Start lock */
    private final Object startLock = new Object();
    /** Whether start is running */
    private boolean startRunning;
    /** Whether we are building tunnels */
    private volatile boolean _buildingTunnels;
    /** Tunnel builder thread */
    private volatile Thread _tunnelBuilder;
    /** Private key file path */
    private String privKeyFile;
    /** true if we are chained from a server. */
    private boolean chained;
    /** Thread pool executor */
    protected volatile ThreadPoolExecutor _executor;
    /** true if we created _executor ourselves (TCG was null) and must shut it down on close */
    private volatile boolean _ownExecutor;
    /** Property name: max concurrently active client connections handled by one client tunnel */
    public static final String PROP_MAX_CONNECTIONS = "i2ptunnel.maxConnections";
    /** Default socket open timeout in ms, after which close() will proceed even with active sockets */
    public static final long DEFAULT_SOCKET_OPEN_TIMEOUT = 30000;
    /** Default cap on concurrently handled client connections.
     *  <p>
     *  The accept/connect path runs on an unbounded {@link I2PTunnelClientBase.BlockingRunner} pool, so a flood of
     *  inbound peer connections (e.g. tracker announces/scrapes) can spawn unlimited parallel
     *  connect attempts - each with its own retry loop - starving legitimate browsing streams.
     *  A hard concurrent-process cap sheds excess inbound load instead of amplifying it.
     *  @since 0.9.71+
     */
    public static final int DEFAULT_MAX_CONNECTIONS = 256;
    /** Resolved concurrent connection cap for this tunnel. Guards #manageConnection. */
    private volatile int _maxConnections;
    /**
     *  True when this tunnel's config explicitly set
     *  {@value #PROP_MAX_CONNECTIONS}. When false the tunnel inherits the
     *  Tuner-managed default cap (see {@link #getEffectiveMaxConnections()}),
     *  so the Tuner can raise/lower the floor without a tunnel restart. An
     *  explicit override always wins over the Tuner default.
     */
    private volatile boolean _maxConnectionsCustomized;
    /** Live reservation counter shared by the connections of this tunnel. */
    private final AtomicInteger _activeConnections = new AtomicInteger();
    /** Socket open timeout in ms; close() will proceed after this even with active sockets */
    private final long _socketOpenTimeout = DEFAULT_SOCKET_OPEN_TIMEOUT;
    /** Time the tunnel was opened, for timeout checks in close() */
    private long _openStarted;
    /** this is ONLY for shared clients */
    private static I2PSocketManager socketManager;

    /**
     *  Only destroy and replace a static shared client socket manager if it's been connected before
     *  @since 0.9.20
     */
    private enum SocketManagerState { INIT, CONNECTED }
    /** Socket manager state */
    private static SocketManagerState socketManagerState = SocketManagerState.INIT;
    /** Property for SSL enable */
    public static final String PROP_USE_SSL = I2PTunnelServer.PROP_USE_SSL;

    /**
     *  This constructor is used to add a client to an existing socket manager.
     *  <p>
     *  As of 0.9.21 this does NOT open the local socket. You MUST call
     *  {@link #startRunning()} for that. The local socket will be opened
     *  immediately (ignoring the <code>i2cp.delayOpen</code> option).
     *
     *  @param localPort if 0, use any port, get actual port selected with getLocalPort()
     *  @param l the logging instance
     *  @param sktMgr the existing socket manager
     *  @param tunnel the I2PTunnel instance
     *  @param notifyThis the event dispatcher for notifications
     *  @param clientId the client identifier
     */
    public I2PTunnelClientBase(int localPort, Logging l, I2PSocketManager sktMgr,
            I2PTunnel tunnel, EventDispatcher notifyThis, long clientId )
            throws IllegalArgumentException {
        super(localPort + " (uninitialized)", notifyThis, tunnel);
        chained = true;
        sockMgr = sktMgr;
        _clientId = clientId;
        _handlerName = "chained";
        this.localPort = localPort;
        this.l = l;
        _ownDest = true; // == ! shared client
        _context = tunnel.getContext();
        _log = _context.logManager().getLog(getClass());
        // chained tunnel: no client options yet, seed the gate with the built-in default cap.
        // This is only an initial seed; once manageConnection() begins admitting
        // connections it consults getEffectiveMaxConnections(), which for an
        // un-customized tunnel reads the Tuner-managed default (starting at
        // DEFAULT_MAX_CONNECTIONS) so runtime Tuner adjustments apply without a restart.
        _maxConnections = DEFAULT_MAX_CONNECTIONS;
        _maxConnectionsCustomized = false;
    }

    /**
     * The main constructor.
     * <p>
     * As of 0.9.21 this is fast, and does NOT connect the manager to the router,
     * or open the local socket. You MUST call startRunning() for that.
     * <p>
     * (0.9.20 claimed to be fast, but due to a bug it DID connect the manager
     * to the router. It did NOT open the local socket however, so it was still
     * necessary to call startRunning() for that.)
     *
     * @param localPort if 0, use any port, get actual port selected with getLocalPort()
     * @param ownDest whether to use an owned destination
     * @param l the logging instance
     * @param notifyThis the event dispatcher for notifications
     * @param handlerName the handler name
     * @param tunnel the I2PTunnel instance
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we can't create a socketManager
     */
    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l,
                               EventDispatcher notifyThis, String handlerName,
                               I2PTunnel tunnel) throws IllegalArgumentException {
        this(localPort, ownDest, l, notifyThis, handlerName, tunnel, null);
    }

    /**
     * Use this to build a client with a persistent private key.
     * <p>
     * As of 0.9.21 this is fast, and does NOT connect the manager to the router,
     * or open the local socket. You MUST call startRunning() for that.
     * <p>
     * (0.9.20 claimed to be fast, but due to a bug it DID connect the manager
     * to the router. It did NOT open the local socket however, so it was still
     * necessary to call startRunning() for that.)
     *
     * @param localPort if 0, use any port, get actual port selected with getLocalPort()
     * @param ownDest whether to use an owned destination
     * @param l the logging instance
     * @param notifyThis the event dispatcher for notifications
     * @param handlerName the handler name
     * @param tunnel the I2PTunnel instance
     * @param pkf Path to the private key file, or null to generate a transient key
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we can't create a socketManager
     */
    public I2PTunnelClientBase(int localPort, boolean ownDest, Logging l,
                               EventDispatcher notifyThis, String handlerName,
                               I2PTunnel tunnel, String pkf) throws IllegalArgumentException{
        super(localPort + " (uninitialized)", notifyThis, tunnel);
        _clientId = __clientId.incrementAndGet();
        this.localPort = localPort;
        this.l = l;
        _ownDest = ownDest; // == ! shared client
        _handlerName = handlerName;
        _context = tunnel.getContext();
        _log = _context.logManager().getLog(getClass());

        // Normalize path so we can find it
        if (pkf != null) {
            File keyFile = new File(pkf);
            if (!keyFile.isAbsolute()) {keyFile = new File(_context.getConfigDir(), pkf);}
            this.privKeyFile = keyFile.getAbsolutePath();
        }

        // No need to load the netDb with leaseSets for destinations that will never be looked up
        boolean dccEnabled = (this instanceof I2PTunnelIRCClient) &&
                              Boolean.parseBoolean(tunnel.getClientOptions().getProperty(I2PTunnelIRCClient.PROP_DCC));
        if (!dccEnabled) {
            tunnel.getClientOptions().setProperty("i2cp.dontPublishLeaseSet", "true");
        }
        if (tunnel.getClientOptions().getProperty("i2p.streaming.answerPings") == null) {
            tunnel.getClientOptions().setProperty("i2p.streaming.answerPings", "false");
        }
        String capStr = tunnel.getClientOptions().getProperty(PROP_MAX_CONNECTIONS);
        _maxConnections = resolveMaxConnections(capStr);
        // Only an explicitly configured positive value counts as a per-tunnel override.
        // When the property is absent the tunnel inherits the Tuner-managed default cap
        // (getEffectiveMaxConnections()), allowing the Tuner to adjust it at runtime.
        _maxConnectionsCustomized = capStr != null;
    }

    /**
     * Create the manager if it doesn't exist, AND connect it to the router and build tunnels.
     *
     * Sets the this.sockMgr field if it is null, or if we want a new one.
     * This may take a LONG time if building a new manager.
     *
     * We need a socket manager before getDefaultOptions() and most other things
     * @throws IllegalStateException if the I2CP configuration is b0rked so
     *                               badly that we cant create a socketManager
     */
    protected void verifySocketManager() {
        synchronized(sockLock) {
            I2PTunnel t = getTunnel();
            if (t == null) {
                if (_log.shouldWarn()) {
                    _log.warn("Tunnel is null in verifySocketManager");
                }
                throw new IllegalStateException("I2PTunnelClientBase not properly initialized: tunnel is null");
            }

            boolean newManager = false;
            // Other shared client could have destroyed it
            if (this.sockMgr == null || this.sockMgr.isDestroyed()) {newManager = true;}
            else {
                I2PSession sess = sockMgr.getSession();
                if (sess.isClosed() &&
                           Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.closeOnIdle")) &&
                           Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.newDestOnResume"))) {
                    // Build a new socket manager and a new dest if the session is closed.
                    getTunnel().removeSession(sess);
                    String msg = "Opening tunnels for: " + getTunnel().getClientOptions().getProperty("inbound.nickname") +
                                 " -> Activity detected on listening port";
                    if (_log.shouldInfo()) {_log.info(msg);}
                    /*
                     * Make sure the old one is closed - if it's shared client,
                     * it will be destroyed in getSocketManager() with the correct locking
                     */
                    boolean shouldDestroy;
                    synchronized(I2PTunnelClientBase.class) {shouldDestroy = sockMgr != socketManager;}
                    if (shouldDestroy) {sockMgr.destroySocketManager();}
                    newManager = true;
                }  // else the old socket manager will reconnect the old session if necessary
            }
            if (newManager) {
                if (_ownDest) {this.sockMgr = buildSocketManager();}
                else {this.sockMgr = getSocketManager();}
            }
        }
        connectManager();
    }

    /**
     * Returns the I2PSocketManager for this task.
     * For non-shared clients returns the instance socket manager.
     * For shared clients returns the shared static socket manager.
     * @return the socket manager
     * @since 0.9.63
     */
    @Override
    public I2PSocketManager getSocketManager() {
        if (_ownDest) {
            synchronized (sockLock) {
                I2PSocketManager mgr = sockMgr;
                if (mgr != null && !mgr.isDestroyed())
                    return mgr;
            }
            return null;
        }
        return getSocketManager(getTunnel(), this.privKeyFile);
    }

    /**
     * This is ONLY for shared clients.
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @param tunnel the I2PTunnel instance
     * @param pkf the private key file path, or null
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static synchronized I2PSocketManager getSocketManager(I2PTunnel tunnel, String pkf) {
        // shadows instance _log
        Log logger = tunnel.getContext().logManager().getLog(I2PTunnelClientBase.class);
        if (socketManager != null && !socketManager.isDestroyed()) {
            I2PSession s = socketManager.getSession();
            if (s.isClosed() && socketManagerState != SocketManagerState.INIT) {
                if (logger.shouldInfo()) {
                    logger.info("[" + tunnel.getClientOptions().getProperty("inbound.nickname") +
                              "] Building new socket manager as old one closed [s=" + s + "]");
                }
                tunnel.removeSession(s);
                // Make sure the old one is closed
                socketManager.destroySocketManager();
                socketManagerState = SocketManagerState.INIT;
                // We could be here a LONG time, holding the lock
                socketManager = buildSocketManager(tunnel, pkf);
            } else {
                if (logger.shouldInfo()) {
                    logger.info("[" + tunnel.getClientOptions().getProperty("inbound.nickname") +
                              "] Not building new socket manager as old one is open [s=" + s + "]");
                }
                // If some other tunnel created the session, we need to add it as our session too.
                // It's a Set in I2PTunnel
                tunnel.addSession(s);
            }
        } else {
            if (logger.shouldInfo()) {
                logger.info("[" + tunnel.getClientOptions().getProperty("inbound.nickname") +
                          "] Building new socket manager as there is none exists");
            }
            socketManager = buildSocketManager(tunnel, pkf);
        }
        return socketManager;
    }

    /**
     * For NON-SHARED clients (ownDest = true).
     *
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected I2PSocketManager buildSocketManager() {
        return buildSocketManager(getTunnel(), this.privKeyFile, this.l);
    }

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @param tunnel the I2PTunnel instance
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel) {
        return buildSocketManager(tunnel, null);
    }

    private static final int RETRY_DELAY = 15*1000;
    private static final int MAX_RETRIES = 50;

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @param tunnel the I2PTunnel instance
     * @param pkf absolute path or null
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel, String pkf) {
        return buildSocketManager(tunnel, pkf, null);
    }

    /**
     * As of 0.9.20 this is fast, and does NOT connect the manager to the router.
     * Call verifySocketManager() for that.
     *
     * @param tunnel the I2PTunnel instance
     * @param pkf absolute path or null
     * @param log the logging instance
     * @return non-null
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    protected static I2PSocketManager buildSocketManager(I2PTunnel tunnel, String pkf, Logging log) {
        // shadows instance _log
        Log logger = tunnel.getContext().logManager().getLog(I2PTunnelClientBase.class);
        Properties props = new Properties();
        props.putAll(tunnel.getClientOptions());
        int portNum = I2PClient.DEFAULT_LISTEN_PORT;
        if (tunnel.port != null) {
            try {portNum = Integer.parseInt(tunnel.port);}
            catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Invalid port specified: " + tunnel.port, nfe);
            }
        }

        I2PSocketManager sockManager = null;
        String nickname = tunnel.getClientOptions().getProperty("inbound.nickname");
        try {
            if (pkf != null) {
                // Persistent client dest
                try (FileInputStream fis = new FileInputStream(pkf)) {
                    sockManager = I2PSocketManagerFactory.createDisconnectedManager(fis, tunnel.host, portNum, props);
                }
            } else {
                sockManager = I2PSocketManagerFactory.createDisconnectedManager(null, tunnel.host, portNum, props);
            }
        } catch (I2PSessionException ise) {
            throw new IllegalArgumentException("Can't create socket manager", ise);
        } catch (IOException ioe) {
            String failMsg = "Error opening key file for " + nickname + " -> ";
            if (log != null) {log.log("✖ " + failMsg + " -> Please review console logs for more info");}
            logger.error(failMsg + ioe.getMessage());
            throw new IllegalArgumentException("Error opening key file", ioe);
        }
        sockManager.setName("Client");
        if (logger.shouldInfo()) {logger.info("[" + nickname + "] Built a new socket manager: " + sockManager.getSession());}
        tunnel.addSession(sockManager.getSession());
        return sockManager;
    }

    /**
     * Warning, blocks while connecting to router and building tunnels;
     * This may take a LONG time.
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     * @since 0.9.20
     */
    private void connectManager() {
        boolean closed = sockMgr.getSession().isClosed();
        if (!closed) {return;}
        int retries = 0;
        _buildingTunnels = true;
        _tunnelBuilder = Thread.currentThread();
        try {
            while (closed) {
                try {
                    sockMgr.getSession().connect();
                    synchronized(I2PTunnelClientBase.class) {
                        if (sockMgr == socketManager) {socketManagerState = SocketManagerState.CONNECTED;}
                    }
                } catch (I2PSessionException ise) {
                    // Check if session actually connected despite exception
                    // This prevents false error messages when connection succeeds after exception
                    // Declare variables early so they're available in both branches
                    String host = getTunnel().host;
                    String portNum = getTunnel().port;
                    if (portNum == null) {portNum = Integer.toString(I2PClient.DEFAULT_LISTEN_PORT);}
                    String hostAndPort = host + ":" + portNum;
                    Logging log = this.l;

                    if (!sockMgr.getSession().isClosed()) {
                        synchronized(I2PTunnelClientBase.class) {
                            if (sockMgr == socketManager) {socketManagerState = SocketManagerState.CONNECTED;}
                        }
                        // Log successful startup - no scary error messages during initial build phase
                        String startMsg = "✔ Tunnel started for client at " + hostAndPort;
                        if (log != null) {log.log(startMsg);}
                        break;  // exit retry loop - connection succeeded
                    }
                    // Shadows instance _log
                    Log logger = getTunnel().getContext().logManager().getLog(I2PTunnelClientBase.class);
                    String exmsg = ise.getMessage();
                    boolean fail = !_buildingTunnels || (exmsg != null && exmsg.toLowerCase().contains("session limit exceeded"));
                    if (!fail && ++retries < MAX_RETRIES) {
                        // Only log after multiple consecutive failures (3+) to avoid noise during normal startup
                        // Users don't need to see "retrying" messages - they'll see "Tunnel started" on success
                        if (retries >= 3 && log != null) {
                            log.log("Tunnel build struggling (" + retries + "/" + MAX_RETRIES + "), continuing...");
                        }
                    } else {
                        String msg;
                        if (getTunnel().getContext().isRouterContext()) {msg = "✖ Unable to build tunnels for client at " + hostAndPort;}
                        else {msg = "✖ Cannot build client tunnels: No connection to router at " + hostAndPort;}
                        String failMsg = msg + " -> All attempts failed";
                        if (log != null) {log.log(failMsg);}
                        logger.log(Log.CRIT, failMsg, ise);
                        throw new IllegalArgumentException(msg, ise);
                    }
                    try {Thread.sleep(RETRY_DELAY);}
                    catch (InterruptedException ie) {Thread.currentThread().interrupt(); break;}
                }
                // _buildingTunnels set to false by close()
               closed = _buildingTunnels && sockMgr.getSession().isClosed();
            }
        } finally {
            _buildingTunnels = false;
            _tunnelBuilder = null;
        }
    }

    /**
     * Get the local port.
     * @return the local port number
     */
    public final int getLocalPort() {return localPort;}

    /**
     * Get the listen host address.
     * @param l the logging instance
     * @return the InetAddress for the listen host, or null on error
     */
    protected final InetAddress getListenHost(Logging l) {
        I2PTunnel t = getTunnel();
        if (t == null) {
            if (_log.shouldError()) _log.error("Tunnel is null in getListenHost");
            l.log("✖ Internal error: tunnel not initialized");
            notifyEvent("openBaseClientResult", "error");
            return null;
        }
        try {return InetAddress.getByName(getTunnel().listenHost);}
        catch (UnknownHostException uhe) {
            l.log("✖ Could not find listen host to bind to [" + getTunnel().host + "]");
            if (_log.shouldError()) _log.error("Error finding host to bind to -> " + uhe.getMessage());
            notifyEvent("openBaseClientResult", "error");
            return null;
        }
    }

    /**
     * Actually open the local socket and start working on incoming connections.
     * *Must* be called by derived classes after initialization.
     *
     * This will be fast if i2cp.delayOpen is true, but could take
     * a LONG TIME if it is false, as it connects to the router and builds tunnels.
     *
     * Extending classes must check the value of boolean open after calling
     * super.startRunning(), if false then something went wrong.
     */
    public void startRunning() {
        I2PTunnel tun = getTunnel();
        if (tun == null) {
            _log.warn("Cannot start: tunnel is null");
            open = false;
            notifyEvent("openBaseClientResult", "error");
            return;
        }
        boolean openNow = !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty("i2cp.delayOpen"));
        if (openNow) {
            while (sockMgr == null) {
                verifySocketManager();
                if (sockMgr == null) {
                    _log.error("Unable to connect to router and build tunnels for [" + _handlerName + "]");
                    try {Thread.sleep((long) 10*1000);}
                    catch (InterruptedException ie) {Thread.currentThread().interrupt(); return;}
                }
            }
        } // else delay creating session until createI2PSocket() is called
        startup();
    }

    private void startup() {
        I2PTunnel tun = getTunnel();
        if (tun == null) {
            _log.warn("Tunnel is null in startup");
            open = false;
            notifyEvent("openBaseClientResult", "error");
            return;
        }
        if (_log.shouldDebug()) {_log.debug("Startup [ClientID " + _clientId + "]");}
        boolean isDaemon = tun.getContext().isRouterContext(); // prevent JVM exit when running outside the router
        open = true;
        _openStarted = _context.clock().now();
        Thread t = new I2PAppThread(this, "TunClnt." + localPort, isDaemon);
        t.start();
        synchronized (this) {
            while (!listenerReady && open) {
                try {wait();}
                catch (InterruptedException e) { /* ignored */ } // ignore
            }
        }

        boolean ready;
        synchronized (this) {
            ready = listenerReady;
        }
        if (open && ready) {
            if (localPort > 0) { // -1 for I2Ping
                boolean openNow = !Boolean.parseBoolean(tun.getClientOptions().getProperty("i2cp.delayOpen"));
                String nickname = tun.getClientOptions().getProperty("inbound.nickname");
                String readyMsg = "✔ Tunnels ready for: " + nickname + " [" + _handlerName + "]";
                if ((openNow || chained) && !_handlerName.contains("Ping")) {l.log(readyMsg);}
                else if (!_handlerName.contains("Ping")) {l.log(readyMsg + " -> Standby mode");}
            }
            notifyEvent("openBaseClientResult", "ok");
        } else {
            l.log("✖ Client error for " + tun.listenHost + ':' + localPort + " -> Check router logs");
            notifyEvent("openBaseClientResult", "error");
        }

        synchronized (startLock) {
            startRunning = true;
            startLock.notifyAll();
        }
    }

    /**
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     *
     * @return the default socket options
     */
    protected I2PSocketOptions getDefaultOptions() {
        if (sockMgr == null) {
            throw new IllegalStateException("Socket manager not initialized; call verifySocketManager() first");
        }
        Properties defaultOpts = getTunnel().getClientOptions();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT)) {
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
        return opts;
    }

    /**
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     * Do not use overrides for per-socket options.
     *
     * @param overrides the properties to override
     * @return the default socket options with overrides
     */
    protected I2PSocketOptions getDefaultOptions(Properties overrides) {
        if (sockMgr == null) {
            throw new IllegalStateException("Socket manager not initialized; call verifySocketManager() first");
        }
        Properties defaultOpts = new Properties();
        defaultOpts.putAll(getTunnel().getClientOptions());
        defaultOpts.putAll(overrides);
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if (!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT)) {
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
        return opts;
    }

    /**
     *  Update the I2PSocketManager.
     *
     *  @since 0.9.1
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel) {return;}
        I2PSocketManager sm = _ownDest ? sockMgr : socketManager;
        if (sm == null) {return;}
        Properties props = tunnel.getClientOptions();
        sm.setDefaultOptions(sm.buildOptions(props));
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to, non-null
     * @return a new I2PSocket
     * @throws I2PException if there is an I2P-related problem
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection times out
     */
    public I2PSocket createI2PSocket(Destination dest) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        return createI2PSocket(dest, 0);
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * @param dest The destination to connect to, non-null
     * @param port The destination port to connect to 0 - 65535
     * @return a new I2PSocket
     * @throws I2PException if there is an I2P-related problem
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection times out
     * @since 0.9.9
     */
    public I2PSocket createI2PSocket(Destination dest, int port) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        verifySocketManager();
        I2PSocketOptions opts = getDefaultOptions();
        opts.setPort(port);
        return createI2PSocket(dest, opts);
    }

    /**
     * Create a new I2PSocket towards to the specified destination,
     * adding it to the list of connections actually managed by this
     * tunnel.
     *
     * <p>With multiple inbound or outbound tunnels configured
     * ({@code inbound.quantity > 1} or {@code outbound.quantity > 1}),
     * the session's tunnel pool has multiple tunnels. If a connect attempt
     * fails with {@link NoRouteToHostException}, the method retries,
     * allowing the session's tunnel pool to select a different tunnel.
     * This provides robust delivery and automatic tunnel failover.
     *
     * @param dest The destination to connect to, non-null
     * @param opt Option to be used to open when opening the socket
     * @return a new I2PSocket
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection times out
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket createI2PSocket(Destination dest, I2PSocketOptions opt) throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        return createI2PSocket(dest, opt, NO_DEADLINE);
    }

    /**
     * Create a new I2PSocket bounded by a shared request deadline.
     * <p>
     * The HTTP proxy threads one request-scoped deadline through naming,
     * the failover walk, retry sleeps, and outer retries
     * ({@link #REQUEST_CONNECT_DEADLINE_MS}). The walk starts no leg once
     * the deadline has passed and shortens each leg's connect timeout to the
     * remaining budget, so the whole operation cannot exceed the deadline by
     * a full connect timeout. Because a deadline may shorten {@code opt} in
     * place, a caller passing one must not share the options object with
     * other requests.
     *
     * @param dest The destination to connect to, non-null
     * @param opt Option to be used to open the socket
     * @param deadlineMs absolute wall-clock deadline in ms since the epoch,
     *        or {@link #NO_DEADLINE} for no shared budget
     * @return a new I2PSocket
     * @throws I2PException if there is some other I2P-related problem
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable after retries
     * @throws InterruptedIOException if the connection times out
     * @since 0.9.71+
     */
    I2PSocket createI2PSocket(Destination dest, I2PSocketOptions opt, long deadlineMs)
            throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        if (dest == null) {throw new NullPointerException();}
        verifySocketManager();
        I2PSocket i2ps = createI2PSocketWithFailover(dest, opt, deadlineMs);
        synchronized (sockLock) {mySockets.add(i2ps);}
        return i2ps;
    }

    /**
     * Create a new I2PSocket with retry/failover across the session's tunnel pool.
     * <p>
     * With multiple inbound or outbound tunnels configured, the session's
     * tunnel pools have multiple tunnels available. If a connection attempt
     * fails with {@link NoRouteToHostException}, the method retries,
     * allowing the session's tunnel pool to select a different tunnel.
     * The walk is additionally bounded by {@code deadlineMs}: no new leg is
     * started after the deadline, and each leg's connect timeout is clamped
     * to the remaining budget. One pool-state snapshot is taken per failure
     * to decide whether another leg is worthwhile.
     *
     * @param dest The destination to connect to, non-null
     * @param opt Socket options
     * @param deadlineMs absolute wall-clock deadline in ms since the epoch,
     *        or {@link #NO_DEADLINE} for no shared budget
     * @return a new I2PSocket
     * @throws I2PException if there is some other I2P-related problem
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable after retries
     * @throws InterruptedIOException if the connection times out
     * @since 0.9.71+
     */
    private I2PSocket createI2PSocketWithFailover(Destination dest, I2PSocketOptions opt, long deadlineMs)
            throws I2PException, ConnectException, NoRouteToHostException, InterruptedIOException {
        // Determine number of tunnels from both inbound and outbound config
        // for retry count — both pools can have dead tunnels
        int tunnelCount = 1;
        I2PTunnel t = getTunnel();
        if (t != null) {
            String inQ = t.getClientOptions().getProperty("inbound.quantity");
            String outQ = t.getClientOptions().getProperty("outbound.quantity");
            int inCount = 1, outCount = 1;
            if (inQ != null) { try { inCount = Math.max(1, Integer.parseInt(inQ)); } catch (NumberFormatException nfe) { /* use 1 */ } }
            if (outQ != null) { try { outCount = Math.max(1, Integer.parseInt(outQ)); } catch (NumberFormatException nfe) { /* use 1 */ } }
            tunnelCount = Math.max(inCount, outCount);
        }
        NoRouteToHostException lastEx = null;
        int timeoutFailures = 0;
        for (int i = 0; i < tunnelCount; i++) {
            long now = System.currentTimeMillis();
            if (isDeadlineExpired(deadlineMs, now)) {
                if (lastEx != null) {break;}
                throw new NoRouteToHostException("Connect deadline expired before first attempt");
            }
            // Fit this leg into the time left so the walk cannot overshoot
            // the shared deadline by a full connect timeout.
            long legTimeoutMs = clampToDeadlineMs(opt.getConnectTimeout(), deadlineMs, now);
            if (legTimeoutMs > 0 && legTimeoutMs < opt.getConnectTimeout()) {
                opt.setConnectTimeout(legTimeoutMs);
            }
            try {
                I2PSocket s = sockMgr.connect(dest, opt);
                if (_log.shouldInfo() && i > 0) {
                    _log.info("Connected after retry " + i + " (tunnel failover)");
                }
                return s;
            } catch (NoRouteToHostException e) {
                lastEx = e;
                boolean timedOut = isConnectTimeout(e);
                if (timedOut) {timeoutFailures++;}
                int state = poolState();
                boolean poolDown = state <= -1;
                boolean poolBuilding = state == 0;
                boolean more = shouldContinueFailover(tunnelCount, i + 1, timeoutFailures, poolDown, poolBuilding);
                if (_log.shouldWarn()) {
                    _log.warn("Connect failed (tunnel " + i + "/" + tunnelCount + "): " + e.getMessage() +
                              (more ? ", retrying..." : ", giving up"));
                }
                if (!more) {break;}
            }
        }
        throw (lastEx != null) ? lastEx :
            new NoRouteToHostException("Failed to connect after " + tunnelCount + " attempts");
    }

    /**
     *  Whether another tunnel-failover attempt is worthwhile after a connect failure.
     *  <p>
     *  Stops immediately when the outbound pool is provably dead (no valid tunnels,
     *  none building) — further legs cannot succeed and each leg may burn the full
     *  connect timeout.  When a timeout occurred, walks <b>all</b> configured legs
     *  (up to {@code tunnelCount}) so a pool with several dead tunnels does not
     *  give up after {@link #MAX_TIMEOUT_FAILOVER} legs while healthy legs remain
     *  untried.  Non-timeout failures (NoRoute, refused) also walk all legs.
     *
     *  @param tunnelCount configured max failover legs (max inbound/outbound quantity)
     *  @param attemptsMade legs already attempted (1-based)
     *  @param timeoutFailures consecutive timeout-style failures so far
     *  @param poolDown true if {@link #poolIsDefinitivelyDown()} was true after the failure
     *  @return true to try the next tunnel leg
     *  @since 0.9.71+
     */
    static boolean shouldContinueFailover(int tunnelCount, int attemptsMade, int timeoutFailures, boolean poolDown) {
        return shouldContinueFailover(tunnelCount, attemptsMade, timeoutFailures, poolDown, false);
    }

    /**
     *  Whether another tunnel-failover attempt is worthwhile after a connect failure.
     *  <p>
     *  Stops immediately when the outbound pool is provably dead.  Otherwise walks
     *  all configured legs ({@code attemptsMade < tunnelCount}) regardless of how
     *  many were timeouts — giving up after {@link #MAX_TIMEOUT_FAILOVER} while
     *  healthy legs remain wastes the failover budget and surfaces "giving up" at
     *  tunnel 1/4.  {@code poolBuilding} no longer extends the timeout cap; it is
     *  retained for call-site compatibility and future tuning.
     *
     *  @param tunnelCount configured max failover legs (max inbound/outbound quantity)
     *  @param attemptsMade legs already attempted (1-based)
     *  @param timeoutFailures consecutive timeout-style failures so far
     *  @param poolDown true if {@link #poolIsDefinitivelyDown()} was true after the failure
     *  @param poolBuilding true when {@link #poolState()} reports builds in flight
     *  @return true to try the next tunnel leg
     *  @since 0.9.71+
     */
    static boolean shouldContinueFailover(int tunnelCount, int attemptsMade, int timeoutFailures,
                                          boolean poolDown, boolean poolBuilding) {
        if (poolDown) {return false;}
        // Walk every configured leg: a timeout on leg 1 must not prevent
        // trying legs 2..N when the pool still has capacity.
        return attemptsMade < tunnelCount;
    }

    /**
     *  Whether the outer HTTP connect loop should retry after a failure.
     *  createI2PSocketWithFailover already walked every tunnel leg; this
     *  decides whether to re-enter that walk.
     *
     *  <p>Policy: always allow at least one timeout retry so a pool that was
     *  mid-build when the first walk started can finish its replacements.
     *  While {@code poolBuilding}, allow a second timeout wait (the in-flight
     *  builds may still complete).  Stop immediately when the pool is
     *  provably dead, or when the general connect budget is exhausted.
     *
     *  @param connectAttempts total connect attempts so far (1-based after failure)
     *  @param timeoutConnectAttempts timeout-style attempts so far
     *  @param timedOut true when the failure classified as a connect timeout
     *  @param poolDown true if {@link #poolIsDefinitivelyDown()} was true
     *  @param poolBuilding true when {@link #poolState()} reports builds in flight
     *  @param browserClosed true if the browser already hung up (abort now)
     *  @return true to sleep and retry the outer connect loop
     *  @since 0.9.71+
     */
    static boolean shouldOuterRetryConnect(int connectAttempts, int timeoutConnectAttempts,
                                           boolean timedOut, boolean poolDown,
                                           boolean poolBuilding, boolean browserClosed) {
        if (browserClosed) {return false;}
        if (poolDown) {return false;}
        if (connectAttempts >= I2PTunnelHTTPClient.I2P_CONNECT_MAX_RETRIES) {return false;}
        if (timedOut) {
            int maxTimeout = poolBuilding ? 2 : 1;
            return timeoutConnectAttempts < maxTimeout;
        }
        return true;
    }

    /**
     *  Whether the outer HTTP connect loop should retry after a failure,
     *  given the single pool-state snapshot taken for that failure.
     *  <p>
     *  Both derived flags ({@code poolDown}, {@code poolBuilding}) come from
     *  one {@link #poolState()} reading so a failure never mixes two pool
     *  observations — the reflective pool query is relatively expensive and
     *  one failure must not repeat it.  Stops immediately when the shared
     *  request deadline has passed, regardless of remaining attempt budget.
     *
     *  @param connectAttempts total connect attempts so far (1-based after failure)
     *  @param timeoutConnectAttempts timeout-style attempts so far
     *  @param timedOut true when the failure classified as a connect timeout
     *  @param poolState the single {@link #poolState()} reading for this failure
     *  @param deadlineExpired true when the shared request deadline has passed
     *  @return true to sleep and retry the outer connect loop
     *  @since 0.9.71+
     */
    static boolean shouldOuterRetryConnect(int connectAttempts, int timeoutConnectAttempts,
                                           boolean timedOut, int poolState,
                                           boolean deadlineExpired) {
        if (deadlineExpired) {return false;}
        return shouldOuterRetryConnect(connectAttempts, timeoutConnectAttempts, timedOut,
                                       poolState <= -1, poolState == 0, false);
    }

    /**
     *  Compute the shared connect deadline for a request starting now.
     *
     *  @param nowMs current time in ms since the epoch
     *  @return the absolute deadline ({@code nowMs + REQUEST_CONNECT_DEADLINE_MS})
     *  @since 0.9.71+
     */
    static long connectDeadlineFrom(long nowMs) {
        return nowMs + REQUEST_CONNECT_DEADLINE_MS;
    }

    /**
     *  Whether a shared connect deadline has passed.
     *  Pure decision — no clock access, safe for unit tests.  With
     *  {@link #NO_DEADLINE} this is always false, so callers that have not
     *  adopted the shared budget keep their original behavior.
     *
     *  @param deadlineMs absolute deadline in ms since the epoch
     *  @param nowMs current time in ms since the epoch
     *  @return true when {@code nowMs} has reached or passed the deadline
     *  @since 0.9.71+
     */
    static boolean isDeadlineExpired(long deadlineMs, long nowMs) {
        return nowMs >= deadlineMs;
    }

    /**
     *  Clamp a requested timeout to the time left before a shared deadline.
     *  Used for naming lookups, retry sleeps, and per-leg connect timeouts so
     *  no single step can spend budget the rest of the request still needs.
     *  Pure decision — no clock access, safe for unit tests.
     *
     *  @param requestedMs the timeout the step would use with no deadline
     *  @param deadlineMs absolute deadline in ms since the epoch, or {@link #NO_DEADLINE}
     *  @param nowMs current time in ms since the epoch
     *  @return the requested timeout reduced to the remaining budget; 0 when
     *          the deadline has passed (callers must skip the step)
     *  @since 0.9.71+
     */
    static long clampToDeadlineMs(long requestedMs, long deadlineMs, long nowMs) {
        long remainingMs = isDeadlineExpired(deadlineMs, nowMs) ? 0 : deadlineMs - nowMs;
        return Math.min(requestedMs, remainingMs);
    }

    /**
     *  @return true if the cause chain looks like a connect/read timeout
     *          (streaming SYN give-up wrapped as NoRouteToHostException)
     *  @since 0.9.71+
     */
    static boolean isConnectTimeout(Throwable e) {
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 8) {
            String m = t.getMessage();
            if (m != null) {
                String lm = m.toLowerCase(Locale.US);
                if (lm.contains("timed out") || lm.contains("timeout")) {return true;}
            }
            Throwable cause = t.getCause();
            if (cause == t) {break;}
            t = cause;
        }
        return false;
    }

    /**
     *  @return true if the client outbound tunnel pool provably has no tunnels
     *          and none are being built, so further connect retries cannot succeed
     *  @since 0.9.71+
     */
    protected boolean poolIsDefinitivelyDown() {
        return poolState() <= -1;
    }

    /**
     *  Router-context only, best-effort check of the client outbound tunnel pool.
     *  Uses reflection so i2ptunnel compiles against core alone.
     *  The router creates a per-client pool keyed by session hash;
     *  getValidTunnelCount() counts non-failed, non-expired tunnels,
     *  getInProgressCount() counts builds in progress.
     *
     *  @return 1 if the pool has valid tunnels, 0 if it exists but is still
     *          building, -1 if it exists but is dead (no valid, none building),
     *          -2 if unknown (standalone client, no router pool)
     *  @since 0.9.71+
     */
    protected int poolState() {
        I2PAppContext ctx = getTunnel().getContext();
        if (ctx == null || !ctx.isRouterContext()) {return -2;}
        try {
            Object tm = ctx.getClass().getMethod("tunnelManager").invoke(ctx);
            if (tm == null) {return -2;}
            I2PSession session = sockMgr.getSession();
            if (session == null || session.getMyDestination() == null) {return -2;}
            Hash client = session.getMyDestination().calculateHash();
            Object pool = tm.getClass().getMethod("getOutboundPool", Hash.class).invoke(tm, client);
            if (pool == null) {return -2;}
            int valid = ((Number) pool.getClass().getMethod("getValidTunnelCount").invoke(pool)).intValue();
            int inProgress = ((Number) pool.getClass().getMethod("getInProgressCount").invoke(pool)).intValue();
            if (valid > 0) {return 1;}
            return inProgress > 0 ? 0 : -1;
        } catch (Exception e) {
            return -2;
        }
    }

    /**
     *  Non-final since 0.9.11.
     *  open will be true before being called.
     *  Any overrides must set listenerReady = true and then notifyAll() if setup is successful,
     *  and must call close() and then notifyAll() on failure or termination.
     */
    @Override
    public void run() {
        InetAddress addr = getListenHost(l);
        if (addr == null) {
            close(true);
            open = false;
            synchronized (this) {notifyAll();}
            return;
        }

        I2PTunnel t = getTunnel();
        if (t == null) {
            // should not happen, but be safe
            close(true);
            open = false;
            synchronized (this) {notifyAll();}
            return;
        }

        try {
            Properties opts = getTunnel().getClientOptions();
            ss = createServerSocket(opts, addr);

            // If a free port was requested, find out what we got
            if (localPort == 0) {localPort = ss.getLocalPort();}
            notifyEvent("clientLocalPort", Integer.valueOf(ss.getLocalPort()));
            // Notify constructor that port is ready
            synchronized (this) {
                listenerReady = true;
                notifyAll();
            }

            // Wait until we are authorized to process data
            waitForStartRunning();

            initializeExecutor();
            while (open) {
                Socket s = ss.accept();
                manageConnection(s);
            }
        } catch (IOException ex) {
            synchronized (sockLock) {mySockets.clear();}
            if (open) {
                String address = addr.getHostAddress();
                String msg = ex.getMessage().replace("java.net.BindException: ", "");
                msg = msg.replace("Address already used", "Address in use -> Ensure you only have one instance of I2P running");
                _log.error("Error listening for connections on " + address + ":" + localPort + " -> " + ex.getMessage());
                boolean wasStopped = close(true);
                if (wasStopped) {
                    try {Thread.sleep(500);}
                    catch (InterruptedException ie) {Thread.currentThread().interrupt(); /* ignored */ }
                    if (!open) {
                        _log.info("Tunnel on " + address + ":" + localPort + " restarting...");
                        notifyEvent("openBaseClientResult", "started");
                        return;
                    }
                }
                l.log("✖ Error listening for connections on " + address + ":" + localPort + " -> " + msg);
                notifyEvent("openBaseClientResult", "error");
            }
            synchronized (this) {notifyAll();}
        }
    }

    /**
     *  Create the local server socket, with SSL if enabled.
     *
     *  @param opts the client options
     *  @param addr the local bind address
     *  @return the bound server socket
     *  @throws IOException if the socket cannot be created
     */
    private ServerSocket createServerSocket(Properties opts, InetAddress addr) throws IOException {
        boolean useSSL = Boolean.parseBoolean(opts.getProperty(PROP_USE_SSL));
        if (!useSSL) {return new ServerSocket(localPort, 0, addr);}
        // Was already done in GeneralHelper.updateTunnelConfig() when saving the config.
        // We should never be generating the cert here.
        // Add the local interface and all targets to the cert.
        Set<String> altNames = new HashSet<>(4);
        String intfc = getTunnel().listenHost;
        if (intfc != null && !intfc.equals("0.0.0.0") && !intfc.equals("::") &&
            !intfc.equals("0:0:0:0:0:0:0:0")) {altNames.add(intfc);}

        // We can't easily get to the targetDestination property,
        // or the _addrs List in I2PTunnelClient, or the target argument in I2PTunnel from here,
        // but it shouldn't matter, we should never be generating the cert here.

        boolean wasCreated = SSLClientUtil.verifyKeyStore(opts, "", altNames);
        if (wasCreated) {
            // From here, we can't save the config.
            // We shouldn't get here, as SSL isn't the default, so it would be enabled via the GUI only.
            // If it was done manually, the keys will be regenerated at every startup, which is bad.
            _log.logAlways(Log.WARN, "Created new I2PTunnel SSL keys but can't save the config -> Disable and enable via I2PTunnel GUI");
        }
        SSLServerSocketFactory fact = SSLClientUtil.initializeFactory(opts);
        ServerSocket rv = fact.createServerSocket(localPort, 0, addr);
        I2PSSLSocketFactory.setProtocolsAndCiphers((SSLServerSocket) rv);
        return rv;
    }

    /**
     *  Wait until startRunning() authorizes connection processing.
     */
    private void waitForStartRunning() {
        synchronized (startLock) {
            while (!startRunning) {
                try {startLock.wait();}
                catch (InterruptedException ie) { /* ignored */ }
            }
        }
    }

    /**
     *  Get this tunnel's private runner pool from the tunnel controller group
     *  (or create one locally if the group was never started). The pool is a
     *  share of the global clientRunnerMax budget so another dest's flood
     *  cannot consume every runner thread.
     */
    private void initializeExecutor() {
        TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
        if (tcg != null) {
            // Customized tunnels pin their own ceiling; default tunnels pass 0 so
            // the pool tracks the live Tuner-managed default on every rebalance
            // instead of freezing the value captured at tunnel start.
            int ceiling = resolveRunnerCeiling(_maxConnectionsCustomized, getEffectiveMaxConnections());
            _executor = tcg.getClientRunnerExecutor(getTunnel(), ceiling);
        } else {
            /* Fallback in case TCG.getInstance() is null, never instantiated and we were not started by TCG.
             * Maybe a plugin loaded before TCG? Should be rare.
             * Locally owned, so we shut it down in close().
             */
            _executor = new TunnelControllerGroup.CustomThreadPoolExecutor();
            _ownExecutor = true;
        }
        // Create the shedding/failure observables once (idempotent). These count the
        // two ways a client connection ends with zero response bytes: exceed the
        // concurrent-connection gate (manageConnection) and an uncaught runner error
        // (BlockingRunner). Both surface to a proxy browser as an empty response, so
        // the Tuner's maxConnections param and post-mortems need them as rate stats.
        if (_context != null) {
            _context.statManager().createRequiredRateStat("i2ptunnel.clientConnectionShed",
                        "Client tunnel connections shed at cap", "I2PTunnel",
                        TunnelControllerGroup.RATES);
            _context.statManager().createRequiredRateStat("i2ptunnel.clientConnectionFailed",
                        "Client tunnel connections lost to uncaught error", "I2PTunnel",
                        TunnelControllerGroup.RATES);
            // Tuner I2PTunnelClientRunnerMaxParam primary — ensure present even
            // if the group never registered per-pool stats (fallback executor).
            _context.statManager().createRequiredRateStat("i2ptunnel.clientRunner.activeThreads",
                        "Client runner active threads", "I2PTunnel",
                        TunnelControllerGroup.RATES);
        }
    }

    /**
     *  Resolve the max-concurrent-connections cap from the tunnel's configured value.
     *  <p>
     *  Pure decision - no context access, safe for unit tests. Returns the configured
     *  value when it is a positive integer, otherwise the built-in default. A configured
     *  value of 0 or negative is rejected so a typo cannot disable the flood-shedding cap.
     *
     *  @param configured the raw integer string from {@link #PROP_MAX_CONNECTIONS}, may be null
     *  @return the connection cap to use: the parsed value if positive, else {@link #DEFAULT_MAX_CONNECTIONS}
     *  @since 0.9.71+
     */
    static int resolveMaxConnections(String configured) {
        if (configured == null) {return DEFAULT_MAX_CONNECTIONS;}
        try {
            int v = Integer.parseInt(configured.trim());
            if (v > 0) {return v;}
        } catch (NumberFormatException nfe) { /* fall through to default */ }
        return DEFAULT_MAX_CONNECTIONS;
    }

    /**
     *  The effective concurrent-connection cap for this tunnel at the moment the
     *  accept loop admits a connection. Reads the Tuner-managed default dynamically
     *  so the floor can move without a tunnel restart, unless this tunnel declared an
     *  explicit {@value #PROP_MAX_CONNECTIONS} override (which always wins).
     *
     *  <p>The Tuner raises this gate under sustained load so excess inbound
     *  connections are handed to executor threads instead of being shed (a shed close
     *  with zero bytes is what an HTTP proxy browser reports as an empty response),
     *  and lowers it back toward the floor when idle to bound memory/FD usage.
     *
     *  @return the effective cap: &gt;= 1 when gated, 0 meaning unlimited
     *  @since 0.9.71+
     */
    int getEffectiveMaxConnections() {
        return resolveEffectiveMaxConnections(_maxConnectionsCustomized, _maxConnections,
                                              TunnelControllerGroup.getClientDefaultMaxConnections());
    }

    /**
     *  Pure decision helper for {@link #getEffectiveMaxConnections()}: pick the
     *  per-tunnel override when one was explicitly configured, otherwise the
     *  Tuner-managed default cap. Package-visible and context-free so unit tests
     *  can exercise it without a live tunnel.
     *
     *  @param customized whether this tunnel declared an explicit
     *         {@value #PROP_MAX_CONNECTIONS} override
     *  @param ownCap      the tunnel's resolved cap ({@link #resolveMaxConnections} result)
     *  @param globalDefault the Tuner-managed default cap
     *  @return the effective cap: &gt;= 1 when gated, 0 meaning unlimited
     *  @since 0.9.71+
     */
    static int resolveEffectiveMaxConnections(boolean customized, int ownCap, int globalDefault) {
        if (customized) {return ownCap;}
        // No explicit override: fall through to the Tuner-managed default, but never
        // regress to an uninitialized (<= 0) shared value — keep the resolved ownCap.
        if (globalDefault <= 0) {return ownCap;}
        return globalDefault;
    }

    /**
     *  Ceiling to pin on this tunnel's runner pool at creation time.
     *  <p>
     *  Customized tunnels keep their explicit cap; un-customized tunnels pass 0
     *  so {@link TunnelControllerGroup} resolves the live Tuner default on each
     *  rebalance rather than freezing the start-time value (ceiling staleness).
     *
     *  @param customized true when the tunnel set its own maxConnections
     *  @param effectiveMax the tunnel's resolved effective maxConnections
     *  @return the explicit ceiling, or 0 to track the live default
     *  @since 0.9.71+
     */
    static int resolveRunnerCeiling(boolean customized, int effectiveMax) {
        return customized ? effectiveMax : 0;
    }

    /**
     *  Manage the connection just opened on the specified socket
     *
     * @param s Socket to take care of
     */
    protected void manageConnection(Socket s) {
        if (s == null) return;
        ThreadPoolExecutor tpe = _executor;
        if (tpe == null) {
            _log.error("No executor for socket!");
            writeShedResponse(s);
            return;
        }
        // Hard cap on concurrently handled connections. During an inbound flood (e.g.
        // tracker announces/scrapes) every accepted socket would otherwise spawn
        // unbounded parallel connect+retry work, starving legitimate streams. When the
        // cap is reached we shed the excess connection immediately instead of amplifying.
        int effectiveMax = getEffectiveMaxConnections();
        if (!I2PTunnelServer.acquireConnectionSlot(effectiveMax, _activeConnections)) {
            if (_log.shouldWarn()) {
                _log.warn("Connection limit reached; shedding new inbound connection (cap=" +
                          effectiveMax + ", active=" + _activeConnections.get() + ")");
            }
            // Rate-stat for Tuner feedback and post-mortem: events/min of shedding.
            // This is the observable the Tuner uses to raise the default cap before
            // connections are dropped (P3 instrumentation).
            if (_context != null) {_context.statManager().addRateData("i2ptunnel.clientConnectionShed", 1L);}
            writeShedResponse(s);
            return;
        }
        // Tuner feedback on the live per-tunnel pool (not the shared fallback).
        // Sampled under load on every admit so clientRunner.max can grow from
        // observed active threads, not only after a shed.
        TunnelControllerGroup.sampleRunnerActiveThreads(tpe);
        try {tpe.execute(new BlockingRunner(s));}
        catch (RejectedExecutionException ree) {
            // One load-aware rebalance + retry before shedding: an idle
            // sibling may shrink its claim and free budget for this pool.
            TunnelControllerGroup.rebalanceRunnerPoolsNow();
            try {
                tpe.execute(new BlockingRunner(s));
                TunnelControllerGroup.sampleRunnerActiveThreads(tpe);
                return;
            } catch (RejectedExecutionException ree2) {
                // Still full after rebalance. Return the slot and shed rather
                // than leaking the reservation.
                I2PTunnelServer.releaseConnectionSlot(_activeConnections);
                if (_log.shouldWarn()) {
                    _log.warn("Client pool rejected connection (executor full); shedding" +
                              " (max=" + tpe.getMaximumPoolSize() +
                              ", active=" + tpe.getActiveCount() +
                              ", queue=" + tpe.getQueue().size() +
                              ", cap=" + effectiveMax +
                              ", open=" + _activeConnections.get() + ')');
                }
                // Mirrors the cap-path shed stat: a rejection here would otherwise close
                // the socket with zero bytes (empty proxy response). Count it so the
                // Tuner grows the worker pool instead of holding back.
                if (_context != null) {_context.statManager().addRateData("i2ptunnel.clientConnectionShed", 1L);}
                writeShedResponse(s);
            }
        }
    }

    /**
     *  Best-effort shed response on the browser-facing socket, then close it.
     *  <p>
     *  The default is a silent close (non-HTTP tunnels). HTTP subclasses override
     *  this to emit a real status line — a cookie-capped meta-refresh page or a
     *  503 — so browsers never see an empty response for a shed connection.
     *
     *  @param s the accepted socket being shed; never null
     *  @since 0.9.71+
     */
    protected void writeShedResponse(Socket s) {
        try {s.close();}
        catch (IOException ioe) { /* ignored */ }
    }

    /**
     * Blocking runner, used during the connection establishment
     */
    private class BlockingRunner implements Runnable {
        private final Socket _s;
        public BlockingRunner(Socket s) { _s = s; }
        @Override
        public void run() {
            try {clientConnectionRun(_s);}
            catch (Throwable t) {
                /*
                 * Probably an IllegalArgumentException from connecting to the router
                 * in a delay-open or close-on-idle tunnel (in connectManager() above),
                 * or an uncaught streaming failure. Either ends by closing the browser
                 * socket with no response bytes, which a proxy reports as an empty
                 * response. Count it as a first-class observable (P3): if these are
                 * happening, this counter climbs even though the router logs are quiet.
                 */
                _log.error("Uncaught error in I2PTunnel client", t);
                if (_context != null) {_context.statManager().addRateData("i2ptunnel.clientConnectionFailed", 1L);}
                try {_s.close();}
                catch (IOException ioe) { /* ignored */ }
            } finally {
                // Return the concurrency slot so the next queued inbound connection can proceed.
                I2PTunnelServer.releaseConnectionSlot(_activeConnections);
            }
        }
    }

    /**
     *  Note that the tunnel can be reopened after this by calling startRunning().
     *  This may not release all resources. In particular, the I2PSocketManager remains
     *  and it may have timer threads that continue running.
     *
     *  To release all resources permanently, call destroy().
     *
     *  Does nothing if open is already false.
     *  Sets open = false but does not notifyAll().
     *
     *  @return success
     */
    public boolean close(boolean forced) {
        I2PTunnel t = getTunnel();
        String nickname = "unknown";
        if (t != null) {
            String tmp = t.getClientOptions().getProperty("inbound.nickname");
            if (tmp != null) nickname = tmp;
        }

        if (_log.shouldDebug()) {
            _log.debug("close() called: forced = " + forced + " open = " + open + " sockMgr = " + sockMgr);
        }
        if (forced) {
            Thread tb = _tunnelBuilder;
            if (tb != null) {
                _buildingTunnels = false;
                tb.interrupt();
            }
        }
        if (!open) return true;
        synchronized (sockLock) {
            if (sockMgr != null) {
                mySockets.retainAll(sockMgr.listSockets());
                if ((!forced) && (!mySockets.isEmpty())) {
                    if (_openStarted > 0 && (System.currentTimeMillis() - _openStarted) > _socketOpenTimeout) {
                        String timeoutMsg = "Timed out waiting for active connections to close in " + nickname + " tunnel, proceeding with close";
                        _log.warn(timeoutMsg);
                        l.log(timeoutMsg);
                    } else {
                        String noCloseMsg = "Not closing " + nickname + " tunnel -> Active connections remain...";
                        l.log(noCloseMsg);
                        _log.debug(noCloseMsg);
                        for (I2PSocket s : mySockets) {l.log(" -> " + s.toString());}
                        return false;
                    }
                }
                if (!chained) {
                    I2PSession session = sockMgr.getSession();
                    getTunnel().removeSession(session);
                    if (_ownDest) {
                        try {session.destroySession();}
                        catch (I2PException ex) { /* ignored */ }
                    }
                    // TCG will try to destroy it too
                } // else the app chaining to this one closes it!
            }
            if (!toString().contains("-1")) {l.log("‣ Stopping client tunnel: " + nickname + "…");} // hide from i2ping
            open = false;
            try {if (ss != null) ss.close();}
            catch (IOException ex) {
                if (_log.shouldDebug()) {_log.debug("Error closing tunnel " + nickname + " -> " + ex.getMessage());}
                return false;
            }
        }
        // shut down the executor only if we own it; TCG-owned per-tunnel pools
        // are shut down and deregistered so their budget share is freed.
        if (_ownExecutor) {
            ThreadPoolExecutor tpe = _executor;
            if (tpe != null) {tpe.shutdownNow();}
        } else {
            TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
            if (tcg != null && t != null) {tcg.runnerStopped(t);}
        }
        return true;
    }

    /**
     * Returns the socket open timeout in ms; close() will proceed after this
     * even with active sockets.
     * @since 0.9.71+
     */
    public long getSocketOpenTimeout() { return _socketOpenTimeout; }

    /**
     *  Note that the tunnel cannot be reopened after this by calling startRunning(),
     *  as it will destroy the underlying socket manager.
     *  This releases all resources if not a shared client.
     *  For shared client, the router will kill all the remaining streaming timers at shutdown.
     *
     *  @since 0.9.17
     */
    @Override
    public synchronized boolean destroy() {
        close(true);
        if (_ownDest) {
            I2PSocketManager sm = sockMgr;
            if (sm != null) {sm.destroySocketManager();}
        }
        return true;
    }

    /**
     * Close a socket silently.
     * @param s the socket to close
     */
    public static void closeSocket(Socket s) {
        try {s.close();}
        catch (IOException ex) { /* ignored */ }
    }

    /**
     * Manage a connection in a separate thread. This only works if
     * you do not override manageConnection().
     *
     * This is run in a thread from an unlimited-size thread pool,
     * so it may block or run indefinitely.
     *
     * @param s the socket to manage
     */
    protected abstract void clientConnectionRun(Socket s);

}
