/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

/**
 * I2P server tunnel that forwards I2P connections to local TCP services.
 * <p>
 * Listens for I2P connections and creates corresponding TCP connections
 * to local destinations. Supports SSL/TLS, connection filtering, port-based
 * destination mapping, and asynchronous connection handling via thread pools.
 * <p>
 * Manages I2PSocketManager lifecycle, connection timeouts, and provides
 * framework for specialized servers like HTTP and bidirectional tunnels.
 * Supports both standard and filtered operation modes.
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.IncomingConnectionFilter;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.client.streaming.StatefulConnectionFilter;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.access.FilterFactory;
import net.i2p.i2ptunnel.access.InvalidDefinitionException;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/** Base I2P tunnel server for handling incoming connections.
 * <p>
 * I2PTunnelServer is the foundation for all I2P server tunnels that accept
 * incoming I2P connections and forward them to local TCP services. It manages
 * the lifecycle of I2PSocketManager, handles connection acceptance, and
 * provides connection forwarding to configured local destinations.
 * </p>
 * <p>
 * <b>Key Features:</b>
 * <ul>
 *   <li>Accepts incoming I2P connections via I2PServerSocket</li>
 *   <li>Forwards connections to configurable local TCP destinations</li>
 *   <li>Supports SSL/TLS connections via I2PSSLSocketFactory</li>
 *   <li>Provides port-based destination mapping (targetForPort.xx)</li>
 *   <li>Implements connection filtering via StatefulConnectionFilter</li>
 *   <li>Uses thread pool for concurrent connection handling</li>
 * </ul>
 * </p>
 * <p>
 * <b>Connection Flow:</b>
 * <ol>
 *   <li>Client connects to I2P destination via I2PServerSocket</li>
 *   <li>blockingHandle() is called with the I2PSocket</li>
 *   <li>Local TCP socket is created via getSocket()</li>
 *   <li>I2PTunnelRunner handles bidirectional data streaming</li>
 * </ol>
 * </p>
 * <p>
 * <b>Subclasses:</b> I2PTunnelHTTPServer, I2PTunnelIRCServer, and others
 * extend this class to provide protocol-specific handling while reusing
 * the core connection management infrastructure.
 * </p>
 *
 * @see I2PTunnelHTTPServer
 * @see I2PTunnelIRCServer
 * @see I2PSocketManager
 */
public class I2PTunnelServer extends I2PTunnelTask implements Runnable {

    protected final Log _log;
    protected final I2PSocketManager sockMgr;
    protected volatile I2PServerSocket i2pss;

    private final Object lock = new Object();
    protected final Object slock = new Object();
    protected final Object sslLock = new Object();

    protected InetAddress remoteHost;
    protected int remotePort;
    protected final Logging l;
    private I2PSSLSocketFactory _sslFactory;
    private static final long DEFAULT_READ_TIMEOUT = -1;
    /** default timeout - override if desired */
    protected long readTimeout = DEFAULT_READ_TIMEOUT;
    public static final String PROP_USE_SSL = "useSSL";
    public static final String PROP_UNIQUE_LOCAL = "enableUniqueLocal";
    /** @since 0.9.30 */
    public static final String PROP_ALT_PKF = "altPrivKeyFile";
    private ExecutorService _executor;
    protected volatile ThreadPoolExecutor _clientExecutor;
    private static int CORES = SystemVersion.getCores();
    private static int MIN_THREADS = Math.max(CORES / 2, 8);
    private static int MAX_THREADS = 4096;
    private static int KEEP_ALIVE = 30; // seconds
    private final Map<Integer, InetSocketAddress> _socketMap = new ConcurrentHashMap<Integer, InetSocketAddress>(4);
    private volatile StatefulConnectionFilter _filter;

    /* the following are required for http bidir server */
    protected I2PTunnelTask task;
    protected boolean bidir;
    protected static volatile long __serverId = 0;
    private int DEFAULT_LOCALPORT = 4488;
    protected int localPort = DEFAULT_LOCALPORT;

    /**
     *  Non-blocking
     *
     * @param privData Base64-encoded private key data,
     *                 format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, String privData, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(privData));
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        buildSocketMap(tunnel.getClientOptions());
        sockMgr = createManager(bais);
    }

    /**
     *  Non-blocking
     *
     * @param privkey file containing the private key data,
     *                format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param privkeyname the name of the privKey file, just for logging
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, File privkey, String privkeyname, Logging l,
                           EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        buildSocketMap(tunnel.getClientOptions());
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(privkey);
            sockMgr = createManager(fis);
        } catch (IOException ioe) {
            _log.error("Cannot read private key data for " + privkeyname, ioe);
            notifyEvent("openServerResult", "error");
            throw new IllegalArgumentException("Error starting server", ioe);
        } finally {
            if (fis != null) {
                try {fis.close();}
                catch (IOException ioe) {}
            }
        }
    }

    /**
     *  Non-blocking
     *
     * @param privData stream containing the private key data,
     *                 format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param privkeyname the name of the privKey file, just for logging
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public I2PTunnelServer(InetAddress host, int port, InputStream privData, String privkeyname, Logging l,  EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("server at " + host + ':' + port, notifyThis, tunnel);
        _log = tunnel.getContext().logManager().getLog(getClass());
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        buildSocketMap(tunnel.getClientOptions());
        sockMgr = createManager(privData);
    }

    /**
     *  Non-blocking
     *
     *  @param sktMgr the existing socket manager
     *  @since 0.8.9
     */
    public I2PTunnelServer(InetAddress host, int port, I2PSocketManager sktMgr,
                           Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("server at " + host + ':' + port, notifyThis, tunnel);
        this.l = l;
        this.remoteHost = host;
        this.remotePort = port;
        _log = tunnel.getContext().logManager().getLog(getClass());
        buildSocketMap(tunnel.getClientOptions());
        sockMgr = sktMgr;
        open = true;
    }

    private static final int RETRY_DELAY = 15*1000;
    private static final int MAX_RETRIES = 20;

    /**
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     * @since 0.9.8
     */
    private I2PSocketManager createManager(InputStream privData) {
        Properties props = new Properties();
        props.putAll(getTunnel().getClientOptions());
        int portNum = I2PClient.DEFAULT_LISTEN_PORT;
        if (getTunnel().port != null) {
            try {portNum = Integer.parseInt(getTunnel().port);}
            catch (NumberFormatException nfe) {
                _log.error("Invalid port specified [" + getTunnel().port + "], reverting to " + portNum);
            }
        }

        if (getTunnel().filterDefinition != null) {
            File filterDefinition = new File(getTunnel().filterDefinition);
            I2PAppContext context = getTunnel().getContext();
            try {_filter = FilterFactory.createFilter(context, filterDefinition);}
            catch (IOException | InvalidDefinitionException bad) {
                String msg = "Bad filter definition file: " + filterDefinition + " - filtering disabled: " + bad.getMessage();
                _log.error(msg, bad);
                l.log("▲ WARNING: " + msg);
                _filter = null;
            }
        }

        IncomingConnectionFilter filter = _filter == null ? IncomingConnectionFilter.ALLOW : _filter;

        try {
            I2PSocketManager rv = I2PSocketManagerFactory.createDisconnectedManager(privData, getTunnel().host,
                                                                                    portNum, props, filter);
            rv.setName("I2PTunnel Server");
            getTunnel().addSession(rv.getSession());
            String alt = props.getProperty(PROP_ALT_PKF);
            if (alt != null) {addSubsession(rv, alt);}
            return rv;
        } catch (I2PSessionException ise) {
            throw new IllegalArgumentException("Can't create socket manager", ise);
        } finally {
            try {privData.close();}
            catch (IOException ioe) {}
        }
    }

    /**
     *  Add a non-DSA_SHA1 subsession to the DSA_SHA1 server if necessary.
     *
     *  @return subsession, or null if none was added
     *  @since 0.9.30
     */
    private I2PSession addSubsession(I2PSocketManager sMgr, String alt) {
        File altFile = TunnelController.filenameToFile(alt);
        if (altFile == null) {return null;}
        I2PSession sess = sMgr.getSession();
        if (sess.getMyDestination().getSigType() != SigType.DSA_SHA1) {return null;}
        Properties props = new Properties();
        props.putAll(getTunnel().getClientOptions());
        // fixme get actual sig type
        String name = props.getProperty("inbound.nickname");
        if (name != null) {props.setProperty("inbound.nickname", name + " (EdDSA)");}
        name = props.getProperty("outbound.nickname");
        if (name != null) {props.setProperty("outbound.nickname", name + " (EdDSA)");}
        props.setProperty(I2PClient.PROP_SIGTYPE, "EdDSA_SHA512_Ed25519");
        FileInputStream privData = null;
        try {
            privData = new FileInputStream(altFile);
            I2PSession rv = sMgr.addSubsession(privData, props);
            if (rv.isOffline()) {
                long exp = rv.getOfflineExpiration();
                long remaining = exp - getTunnel().getContext().clock().now();
                // if expires before the LS expires...
                if (remaining <= 10*60*1000) {
                    String msg;
                    if (remaining > 0) {
                        msg = "Offline signature for tunnel " + name + " alternate destination expires in " + DataHelper.formatTime(exp);
                    } else {
                        msg = "Offline signature for tunnel " + name + " alternate destination expired " + DataHelper.formatTime(exp);
                    }
                    _log.log(Log.CRIT, msg);
                    throw new IllegalArgumentException(msg);
                }
                if (remaining < 60*24*60*60*1000L) {
                    String msg = "Offline signature for tunnel " + name + " alternate destination expires in " + DataHelper.formatDuration(remaining);
                    _log.warn(msg);
                    l.log("▲ WARNING: " + msg);
                }
            }
            return rv;
        } catch (IOException ioe) {
            _log.error("Failed to add sub-session", ioe);
            return null;
        } catch (I2PSessionException ise) {
            _log.error("Failed to add sub-session", ise);
            return null;
        } finally {
            if (privData != null) {
                try {privData.close();}
                catch (IOException ioe) {}
            }
        }
    }

    /**
     * Warning, blocks while connecting to router and building tunnels;
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we can't create a socketManager
     * @since 0.9.8
     */
    private void connectManager() {
        int retries = 0;
        boolean warnedAboutExpiry = false;
        boolean warnedAboutConnection = false;
        boolean warnedAboutSubsession = false;
        boolean tunnelsReadyLogged = false;
        Properties props = getTunnel().getClientOptions();

        I2PSession session = sockMgr.getSession();
        if (session.isOffline()) {
            long exp = session.getOfflineExpiration();
            long remaining = exp - getTunnel().getContext().clock().now();
            String name = props.getProperty("inbound.nickname");
            if (name == null) {
                name = props.getProperty("outbound.nickname");
                if (name == null) {name = "";}
            }
            // if expires before the LS expires...
            if (remaining <= 10*60*1000) {
                String msg;
                if (remaining > 0) {
                    msg = "Offline signature for tunnel " + name + " expires in " + DataHelper.formatTime(exp);
                } else {
                    msg = "Offline signature for tunnel " + name + " expired " + DataHelper.formatTime(exp);
                }
                _log.log(Log.CRIT, msg);
                throw new IllegalArgumentException(msg);
            }
            if (remaining < 60*24*60*60*1000L && !warnedAboutExpiry) {
                String msg = "Offline signature for tunnel " + name + " expires in " + DataHelper.formatDuration(remaining);
                _log.warn(msg);
                l.log("▲ WARNING: " + msg);
                warnedAboutExpiry = true;
            }
        }
        while (session.isClosed()) {
            try {
                session.connect();
                // Now connect the subsessions, if any
                List<I2PSession> subs = sockMgr.getSubsessions();
                if (!subs.isEmpty()) {
                    for (I2PSession sub : subs) {
                        try {
                            sub.connect();
                            if (_log.shouldInfo()) {_log.info("Connected sub-session " + sub);}
                        } catch (I2PSessionException ise) {
                            if (!warnedAboutSubsession) {
                                String msg = "Unable to connect sub-session " + sub;
                                this.l.log("▲ WARNING: " + msg);
                                _log.error(msg, ise);
                                warnedAboutSubsession = true;
                            }
                        }
                    }
                }
            } catch (I2PSessionException ise) {
                if (!warnedAboutConnection) {
                    // try to make this error sensible as it will happen...
                    String portNum = getTunnel().port;
                    if (portNum == null) {portNum = Integer.toString(I2PClient.DEFAULT_LISTEN_PORT);}
                    String msg;
                    if (getTunnel().getContext().isRouterContext()) {
                        msg = "✖ Unable to build tunnels for server at " + remoteHost.getHostAddress() + ':' + remotePort;
                    } else {
                        msg = "✖ Unable to connect to the router at " + getTunnel().host + ':' + portNum +
                              " and build tunnels for server at " + remoteHost.getHostAddress() + ':' + remotePort;
                    }
                    String exmsg = ise.getMessage();
                    boolean fail = exmsg != null && exmsg.contains("session limit exceeded");
                    if (!fail && ++retries < MAX_RETRIES) {
                        msg += " -> Retrying in " + (RETRY_DELAY / 1000) + " seconds...";
                        this.l.log(msg);
                        _log.error(msg);
                    } else {
                        msg += " -> Giving up (maximum retries exceeded)";
                        this.l.log(msg);
                        _log.log(Log.CRIT, msg, ise);
                        throw new IllegalArgumentException(msg, ise);
                    }
                    warnedAboutConnection = true;
                }
            }
        }

        if (!tunnelsReadyLogged) {
            String nickname = tunnel.getClientOptions().getProperty("inbound.nickname");
            String type = props.getProperty("type");
            String readyMsg = "✔ Tunnels ready for: " + nickname + " [" + (type != null ? type + " server" : "Server") +
                              " on " + remoteHost.getHostAddress() + ':' + remotePort + "]";
            l.log(readyMsg);
            tunnelsReadyLogged = true;
        }
        notifyEvent("openServerResult", "ok");
        open = true;
    }

    /**
     * Start running the I2PTunnelServer.
     * Warning, blocks while connecting to router and building tunnels;
     *
     * @throws IllegalArgumentException if the I2CP configuration is b0rked so
     *                                  badly that we cant create a socketManager
     */
    public synchronized void startRunning() {
        connectManager();
        StatefulConnectionFilter filter = _filter;
        if (filter != null) {filter.start();}
        boolean isDaemon = getTunnel().getContext().isRouterContext(); // prevent JVM exit when running outside the router
        Thread t = new I2PAppThread(this, "Server " + remoteHost + ':' + remotePort, isDaemon);
        t.start();
    }

    /**
     * Set the read idle timeout for newly-created connections (in milliseconds).
     * After this time expires without data being reached from the I2P network,
     * the connection itself will be closed.
     *
     * Less than or equal to 0 means forever.
     * Default -1 (forever) as of 0.9.36 for standard tunnels, but extending classes may override.
     * Prior to that, default was 5 minutes, but did not work due to streaming bugs.
     *
     * Applies only to future connections; calling this does not affect existing connections.
     *
     * @param ms in ms
     */
    public void setReadTimeout(long ms) {readTimeout = ms;}

    /**
     * Get the read idle timeout for newly-created connections (in milliseconds).
     *
     * Less than or equal to 0 means forever.
     * Default -1 (forever) as of 0.9.36 for standard tunnels, but extending classes may override.
     * Prior to that, default was 5 minutes, but did not work due to streaming bugs.
     *
     * @return The read timeout used for connections
     */
    public long getReadTimeout() {return readTimeout;}

     /**
      *  Note that the tunnel can be reopened after this by calling startRunning().
      *  This does not release all resources. In particular, the I2PSocketManager remains
      *  and it may have timer threads that continue running.
      *
      *  To release all resources permanently, call destroy().
      *
      *  @return true if the tunnel was closed successfully, false if connections exist and forced is false
      */
    public synchronized boolean close(boolean forced) {
        if (!open) return true;
        synchronized (lock) {
            if (!forced && sockMgr.listSockets().size() != 0) {
                l.log("There are still active connections!");
                for (I2PSocket skt : sockMgr.listSockets()) {l.log("->" + skt);}
                return false;
            }
            open = false;
            try {
                if (i2pss != null) {
                    i2pss.close();
                    i2pss = null;
                }
                I2PSession session = sockMgr.getSession();
                getTunnel().removeSession(session);
                session.destroySession();
            } catch (I2PException ex) {_log.error("Error destroying the session", ex);}
            if (_executor != null) {
                _executor.shutdownNow();
            }
            return true;
        }
    }

    void shutdownAndAwaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS))
                    _log.error("Executor did not terminate");
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     *  Note that the tunnel cannot be reopened after this by calling startRunning(),
     *  as it will destroy the underlying socket manager.
     *  This releases all resources.
     *
     *  @since 0.9.17
     */
    @Override
    public synchronized boolean destroy() {
        close(true);
        sockMgr.destroySocketManager();
        StatefulConnectionFilter filter = _filter;
        if (filter != null) {filter.stop();}
        return true;
    }

    /**
     *  Update the I2PSocketManager.
     *  And since 0.9.15, the target host and port.
     *
     *  @since 0.9.1
     */
    @Override
    public void optionsUpdated(I2PTunnel tunnel) {
        if (getTunnel() != tunnel || sockMgr == null) {return;}
        Properties props = tunnel.getClientOptions();
        sockMgr.setDefaultOptions(sockMgr.buildOptions(props));
        // see TunnelController.setSessionOptions()
        String h = props.getProperty(TunnelController.PROP_TARGET_HOST);
        if (h != null) {
            try {remoteHost = InetAddress.getByName(h);}
            catch (UnknownHostException uhe) {l.log("✖ Unknown host: " + h);}
        }
        String p = props.getProperty(TunnelController.PROP_TARGET_PORT);
        if (p != null) {
            try {
                int port = Integer.parseInt(p);
                if (port > 0 && port <= 65535) {remotePort = port;}
                else {l.log("✖ Bad port: " + port);}
            } catch (NumberFormatException nfe) {l.log("✖ Bad port: " + p);}
        }
        buildSocketMap(props);
    }

    /**
     *  Update the ports map.
     *
     *  @since 0.9.9
     */
    private void buildSocketMap(Properties props) {
        _socketMap.clear();
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String key = (String) e.getKey();
            if (key.startsWith("targetForPort.")) {
                key = key.substring("targetForPort.".length());
                try {
                    int myPort = Integer.parseInt(key);
                    String host = (String) e.getValue();
                    int colon = host.indexOf(':');
                    int port = Integer.parseInt(host.substring(colon + 1));
                    host = host.substring(0, colon);
                    InetSocketAddress isa = new InetSocketAddress(host, port);
                    if (isa.isUnresolved()) {
                        l.log("▲ Warning - cannot resolve address for port " + key + ": " + host);
                    }
                    _socketMap.put(Integer.valueOf(myPort), isa);
                } catch (NumberFormatException nfe) {
                    l.log("✖ Bad socket spec for port " + key + ": " + e.getValue());
                } catch (IndexOutOfBoundsException ioobe) {
                    l.log("✖ Bad socket spec for port " + key + ": " + e.getValue());
                }
            }
        }
    }

    /**
     * Runs the I2PTunnelServer to accept and handle incoming connections asynchronously.
     *
     * Accepts connections in a loop, submitting each to the thread pool via CompletableFuture.
     * Handles expected exceptions with retries and logs errors. On shutdown, gracefully terminates the pool.
     */
    public void run() {
        i2pss = sockMgr.getServerSocket();
        if (_log.shouldInfo()) {
            _log.info("Starting async executor with cached thread pool for server " + remoteHost + ':' + remotePort);
        }

        ThreadPoolExecutor connectionExecutor = new ThreadPoolExecutor(
            MIN_THREADS,
            MAX_THREADS,
            KEEP_ALIVE,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread t = new Thread(runnable);
                t.setName("Server:" + remotePort);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
        );

        TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
        if (tcg != null) {
            _clientExecutor = tcg.getClientExecutor();
        } else {
            // Fallback executor
            _clientExecutor = new TunnelControllerGroup.CustomThreadPoolExecutor();
        }

        while (open) {
            I2PSocket i2ps = null;
            try {
                I2PServerSocket ci2pss = i2pss;
                if (ci2pss == null) {
                    throw new I2PException("I2PServerSocket closed");
                }
                i2ps = ci2pss.accept(); // blocking call

                final I2PSocket socketToHandle = i2ps;

                try {
                    CompletableFuture.runAsync(() -> {
                        try {
                            new Handler(socketToHandle).run();
                        } catch (Exception e) {
                            _log.warn("Exception in async handler for " + remoteHost + ':' + remotePort, e);
                            try {
                                socketToHandle.close();
                            } catch (IOException ioe) {
                                // Ignored, socket already errored
                            }
                        }
                    }, connectionExecutor).exceptionally(ex -> {
                        _log.warn("Async build handler task failed for " + remoteHost + ':' + remotePort, ex);
                        return null;
                    });
                } catch (RejectedExecutionException ree) {
                    _log.warn("Max " + MAX_THREADS + " connections exceeded on " +
                               remoteHost.toString().replace("/", "") + ':' + remotePort + " -> Ignoring request");
                    try {
                        socketToHandle.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }

            } catch (RouterRestartException rre) {
                _log.warn("Waiting for router restart...");
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                try {
                    Thread.sleep(2 * 60 * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                _log.warn("Reconnecting to router after restart");
                i2pss = sockMgr.getServerSocket();
            } catch (I2PException ipe) {
                _log.warn("Error accepting server socket connection, attempting to recover...", ipe);
                if (i2ps != null) {
                    try {
                        i2ps.close();
                    } catch (IOException ioe) {
                    }
                }
                try {
                    Thread.sleep(10 * 1000);
                    i2pss = sockMgr.getServerSocket();
                    if (i2pss == null) {
                        throw new I2PException("Failed to recreate server socket");
                    }
                    _log.info("Successfully recovered server socket");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (I2PException re) {
                    _log.error("Failed to recover server socket, destroying tunnel", re);
                    TunnelController tc = getTunnel().getController();
                    if (tc != null) {
                        tc.stopTunnel();
                    } else {
                        close(true);
                    }
                    break;
                }
            } catch (ConnectException ce) {
                if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                if (!open) {
                    break;
                }
                if (_log.shouldError()) {
                    _log.error("Error accepting server socket connection \n* " + ce.getMessage());
                }
                try {
                    Thread.sleep(2 * 60 * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                i2pss = sockMgr.getServerSocket();
            } catch (SocketTimeoutException ste) {
                // ignored, we never set timeout
                if (i2ps != null) {
                    try {
                        i2ps.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
            } catch (RuntimeException e) {
                if (_log.shouldError()) {
                    _log.error("Uncaught exception accepting", e);
                }
                if (i2ps != null) {
                    try {
                        i2ps.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (connectionExecutor != null && !connectionExecutor.isTerminated()) {
            shutdownAndAwaitTermination(connectionExecutor);
        }
    }

    /** just to set the name and set Daemon */
    private static class CustomThreadFactory implements ThreadFactory {
        private final String _name;
        private final AtomicLong _executorThreadCount = new AtomicLong();

        public CustomThreadFactory(String name) {_name = name;}

        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName(_name + "[" + _executorThreadCount.incrementAndGet() + "]");
            rv.setDaemon(true);
            return rv;
        }
    }

    /**
     * Run the blockingHandler.
     */
    private class Handler implements Runnable {
        private final I2PSocket _i2ps;

        public Handler(I2PSocket socket) {_i2ps = socket;}

        public void run() {
            try {blockingHandle(_i2ps);}
            catch (Throwable t) {_log.error("Uncaught error in I2PTunnel server", t);}
        }
    }

    /**
     *  This is run in a thread from a limited-size thread pool via Handler.run(),
     *  except for a standard server (this class, no extension, as determined in getUsePool()),
     *  it is run directly in the acceptor thread (see run()).
     *
     *  Handles an incoming I2P connection by forwarding it to the local TCP destination.
     * <p>
     * This method is called by the server socket's acceptance thread when a new
     * I2P connection arrives. It creates a local TCP socket connection and spawns
     * an I2PTunnelRunner to handle bidirectional data streaming between the
     * I2P and TCP connections.
     * </p>
     * <p>
     * <b>Implementation Notes:</b>
     * <ul>
     *   <li>Subclasses that override this must call super.blockingHandle() or implement</li>
     *       full connection handling themselves</li>
     *   <li>For protocol-specific handling (HTTP, IRC), spawn a handler thread and return</li>
     *   <li>For simple forwarding, this base implementation handles all cases</li>
     *   <li>Performance: local connections are fast enough to handle synchronously</li>
     * </ul>
     * </p>
     * <p>
     * <b>Error Handling:</b> SocketException and IOException are caught and logged,
     * with the socket being reset to notify the peer of the failure.
     * </p>
     *
     * @param socket the incoming I2P connection from a remote peer; never null
     * @see #getSocket(Hash, int)
     * @see I2PTunnelRunner
     */
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldInfo()) {
            _log.info("Incoming connection to " + toString().replace("/", "") + " (port " + socket.getLocalPort() + ")" +
                      "\n* From: " + socket.getPeerDestination().calculateHash() + " port " + socket.getPort());
        }
        long afterAccept = getTunnel().getContext().clock().now();
        long afterSocket = -1;
        // local is fast, so synchronously. Does not need that many threads.
        try {
            socket.setReadTimeout(readTimeout);
            Socket s = getSocket(socket.getPeerDestination().calculateHash(), socket.getLocalPort());
            afterSocket = getTunnel().getContext().clock().now();
            Thread t = new I2PTunnelRunner(s, socket, slock, null, null,
                                           null, (I2PTunnelRunner.FailCallback) null);
            _clientExecutor.execute(t);

            long afterHandle = getTunnel().getContext().clock().now();
            long timeToHandle = afterHandle - afterAccept;
            if ((timeToHandle > 1500) && (_log.shouldInfo())) {
                _log.info("Took a while (" + timeToHandle + "ms) to handle the request for " + remoteHost + ':' + remotePort +
                          "\n* Socket create: " + (afterSocket-afterAccept) + "ms");
            }
        } catch (SocketException ex) {
            int port = socket.getLocalPort();
            try {socket.reset();}
            catch (IOException ioe) {}
            if (_log.shouldError()) {_log.error("Error connecting to server " + getSocketString(port));}
        } catch (IOException ex) {_log.error("Error while waiting for I2PConnections", ex);}
    }

    /**
     *  Creates a TCP socket connection to the configured destination.
     * <p>
     * This method resolves the target host and port for the incoming connection,
     * supporting port-based destination mapping via the client options. If a port
     * has a specific mapping (targetForPort.xx=host:port), that destination is used;
     * otherwise, the default remoteHost and remotePort are used.
     * </p>
     * <p>
     * <b>Port Mapping Configuration:</b>
     * <pre>
     * targetForPort.80=localhost:8080
     * targetForPort.443=localhost:8443
     * </pre>
     * </p>
     * <p>
     * <b>Special Ports:</b> Ports 443 and 22 are automatically flagged for non-SSL
     * handling to prevent SSL-over-SSL issues.
     * </p>
     *
     * @param from the hash of the peer's destination; used for local address binding
     * @param incomingPort the port on which the I2P connection was received
     * @return a connected TCP Socket to the appropriate local destination
     * @throws IOException if the socket cannot be created or connected
     * @see #getSocket(Hash, InetAddress, int)
     * @since 0.9.9
     */
    protected Socket getSocket(Hash from, int incomingPort) throws IOException {
        if (from == null) {
            throw new IOException("Peer destination hash is null");
        }
        InetAddress host = remoteHost;
        int port = remotePort;
        if (incomingPort != 0 && !_socketMap.isEmpty()) {
            InetSocketAddress isa = _socketMap.get(Integer.valueOf(incomingPort));
            if (isa != null) {
                host = isa.getAddress();
                if (host == null) {throw new IOException("Cannot resolve " + isa.getHostName());}
                port = isa.getPort();
            }
        }
        // Don't do SSL-over-SSL
        boolean force = incomingPort == 443 || incomingPort == 22;
        return getSocket(from, host, port, force);
    }

    /**
     *  Gets a human-readable string representation of the target socket.
     * <p>
     * This method is used for logging and error messages when socket creation
     * fails. It returns the configured destination address, taking into account
     * any port-specific mappings that may have been configured.
     * </p>
     * <p>
     * The returned string is formatted as "host:port" without any protocol prefix.
     * </p>
     *
     * @param incomingPort the incoming I2P connection port for lookup
     * @return the target host and port as a string, or default if not configured
     * @see #getSocket(Hash, int)
     * @since 0.9.62
     */
    protected String getSocketString(int incomingPort) {
        if (incomingPort != 0 && !_socketMap.isEmpty()) {
            InetSocketAddress isa = _socketMap.get(Integer.valueOf(incomingPort));
            if (isa != null) {return isa.toString().replace("/", "");}
        }
        if (remoteHost == null) {
            return "unknown:" + remotePort;
        }
        return remoteHost.toString().replace("/", "") + ':' + remotePort;
    }

    /**
     *  Creates a TCP socket connection to the specified destination.
     * <p>
     * This convenience method delegates to getSocket() with forceNonSSL=false,
     * allowing SSL configuration to be controlled by client options.
     * </p>
     *
     * @param from the hash of the peer's destination
     * @param remoteHost the target InetAddress to connect to
     * @param remotePort the target port to connect to
     * @return a connected TCP Socket
     * @throws IOException if the socket cannot be created or connected
     * @see #getSocket(Hash, int)
     * @since 0.9.9
     */
    protected Socket getSocket(Hash from, InetAddress remoteHost, int remotePort) throws IOException {
        return getSocket(from, remoteHost, remotePort, false);
    }

    /**
     *  Get a regular or SSL socket depending on config.
     *  The SSL config applies to all hosts/ports, unless forced off.
     *
     *  @param forceNonSSL override config
     *  @since 0.9.50
     */
    private Socket getSocket(Hash from, InetAddress remoteHost, int remotePort, boolean forceNonSSL) throws IOException {
        String opt = getTunnel().getClientOptions().getProperty(PROP_USE_SSL);
        if (!forceNonSSL && Boolean.parseBoolean(opt)) {
            synchronized(sslLock) {
                if (_sslFactory == null) {
                    try {
                        _sslFactory = new I2PSSLSocketFactory(getTunnel().getContext(),
                                                               true, "certificates/i2ptunnel");
                    } catch (GeneralSecurityException gse) {
                        IOException ioe = new IOException("SSL Fail");
                        ioe.initCause(gse);
                        throw ioe;
                    }
                }
            }
            return _sslFactory.createSocket(remoteHost, remotePort);
        } else {
            // as suggested in https://lists.torproject.org/pipermail/tor-dev/2014-March/00657
            boolean unique = Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_UNIQUE_LOCAL));
            if (unique && remoteHost != null && remoteHost.isLoopbackAddress()) {
                byte[] addr;
                if (remoteHost instanceof Inet4Address) {
                    addr = new byte[4];
                    addr[0] = 127;
                    System.arraycopy(from.getData(), 0, addr, 1, 3);
                } else {
                    addr = new byte[16];
                    addr[0] = (byte) 0xfd;
                    System.arraycopy(from.getData(), 0, addr, 1, 15);
                }
                InetAddress local = InetAddress.getByAddress(addr);
                // Javadocs say local port of 0 allowed in Java 7.
                // Not clear if supported in Java 6 or not.
                return new Socket(remoteHost, remotePort, local, 0);
            } else {return new Socket(remoteHost, remotePort);}
        }
    }

}
