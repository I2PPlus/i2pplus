/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.i2ptunnel.access.FilterFactory;
import net.i2p.i2ptunnel.access.InvalidDefinitionException;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

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
    private static int MAX_BACKLOG = 1024;
    private static int KEEP_ALIVE = 30; // seconds
    private static int BUFFER_SIZE = 32 * 1024;
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
    private static final int MAX_RETRIES = 30;

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
     * Uses a bounded thread pool with a buffer to improve resource management and throughput.
     */
    public void run() {
        i2pss = sockMgr.getServerSocket();

        if (_log.shouldInfo()) {
            _log.info("Starting async executor with buffered thread pool for server " + remoteHost + ':' + remotePort);
        }

        // Initialize thread pool with balanced size; use a LinkedBlockingQueue for buffering incoming tasks.
        ThreadPoolExecutor connectionExecutor = new ThreadPoolExecutor(
            MIN_THREADS,
            MAX_THREADS,
            KEEP_ALIVE,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_BACKLOG), // buffer queue to handle bursts
            runnable -> {
                Thread t = new Thread(runnable);
                t.setName("Server:" + remotePort);
                t.setDaemon(true);
                return t;
            },
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    if (r instanceof Handler) {
                        I2PSocket socket = ((Handler) r).getSocket();
                        try {
                            socket.close();
                        } catch (IOException ioe) {
                            _log.debug("Error closing socket rejected by thread pool", ioe);
                        }
                        _log.warn("Connection from " + socket.getPeerDestination().calculateHash() +
                                  " rejected -> Max (" + (MAX_THREADS + MAX_BACKLOG) + ") concurrent connections reached");
                    } else {
                        _log.warn("Unknown runnable rejected by thread pool: " + r);
                    }
                }
            }
        );

        try {
            // Retrieve or fallback to a client executor
            TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
            if (tcg != null) {
                _clientExecutor = tcg.getClientExecutor();
            } else {
                _clientExecutor = new TunnelControllerGroup.CustomThreadPoolExecutor();
            }

            // Main accept loop
            while (open) {
                I2PSocket i2ps = null;
                try {
                    I2PServerSocket ci2pss = i2pss; // server socket
                    if (ci2pss == null) {
                        throw new I2PException("I2PServerSocket closed");
                    }

                    // Accept blocks until a connection arrives
                    i2ps = ci2pss.accept();

                    final I2PSocket socketToHandle = i2ps;

                    // Submit handling asynchronously
                    CompletableFuture.runAsync(() -> {
                        try {
                            new Handler(socketToHandle).run();
                        } catch (Exception e) {
                            _log.warn("Exception in async handler for " + remoteHost + ':' + remotePort, e);
                            try {
                                socketToHandle.close();
                            } catch (IOException ioe) {
                                _log.debug("Error closing socket after handler exception", ioe);
                            }
                        }
                    }, connectionExecutor).exceptionally(ex -> {
                        // Log and handle any unexpected failure in the async task
                        _log.warn("Async handler task failure for " + remoteHost + ':' + remotePort, ex);
                        return null;
                    });

                } catch (RejectedExecutionException ree) {
                    // When max threads or queue is full, reject new tasks
                    _log.warn("Max " + MAX_THREADS + " connections exceeded on " +
                              remoteHost.toString().replace("/", "") + ':' + remotePort + " -> Ignoring request");
                    if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) { _log.debug("Error closing rejected socket", ioe); }

                } catch (RouterRestartException rre) {
                    _log.warn("Waiting for router restart...");
                    if (i2ps != null) try {i2ps.close(); } catch (IOException ioe) {}
                    // Wait before reconnecting after a restart
                    try {
                        Thread.sleep(2 * 60 * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    _log.warn("Reconnecting to router after restart");
                    i2pss = sockMgr.getServerSocket(); // attempt reconnect

                } catch (I2PException ipe) {
                    // Severe error: log, stop tunnel, and break loop
                    String s = "Error accepting server socket connection - KILLING THE TUNNEL SERVER!";
                    _log.log(Log.CRIT, s, ipe);
                    l.log("✖ " + s + ": " + ipe.getMessage());
                    TunnelController tc = getTunnel().getController();
                    if (tc != null) {
                        tc.stopTunnel();
                    } else {
                        close(true);
                    }
                    if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                    break;
                } catch (ConnectException ce) {
                    // Similar handling as above
                    if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                    if (!open) break;
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
                    // No timeout set, ignore
                    if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                } catch (RuntimeException e) {
                    // Catch-all for unexpected runtime issues
                    if (_log.shouldError()) {
                        _log.error("Uncaught exception accepting", e);
                    }
                    if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
                    // brief pause before resuming
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            // Properly shutdown the connection thread pool
            if (connectionExecutor != null && !connectionExecutor.isTerminated()) {
                shutdownAndAwaitTermination(connectionExecutor);
            }
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

        public Handler(I2PSocket socket) {
            _i2ps = socket;
        }

        /**
         * Return the wrapped I2PSocket for rejection handling
         */
        public I2PSocket getSocket() {
            return _i2ps;
        }

        @Override
        public void run() {
            try {
                blockingHandle(_i2ps);
            } catch (Throwable t) {
                _log.error("Uncaught error in I2PTunnel server", t);
            }
        }
    }

    /**
     * Handles an incoming I2P socket connection.
     *
     * <p>This method is invoked from a limited-size thread pool by {@link Handler#run()},
     * except when used in a standard server (i.e., this class with no extensions determined by {@link #getUsePool()}),
     * in which case it runs directly in the acceptor thread (see {@link #run()}).</p>
     *
     * <p>Implementations of this method (including overrides) must spawn handler threads quickly and return promptly
     * to avoid blocking the calling thread. Blocking during header processing or socket setup (e.g., in protocols like HTTP or IRC)
     * risks exhausting the thread pool and degrading server responsiveness.</p>
     *
     * <p>Thread management and concurrency behavior can be influenced through the following configuration properties:
     * {@code PROP_USE_POOL}, {@code DEFAULT_USE_POOL}, {@code PROP_HANDLER_COUNT}, and {@code DEFAULT_HANDLER_COUNT}.</p>
     *
     * @param socket the incoming {@link I2PSocket} representing the connection to be processed
     *
     * @implNote For optimal performance and scalability, consider offloading any blocking or long-running operations
     * to separate threads or async handlers, as this method directly impacts connection acceptance throughput.
     */
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldInfo()) {
            _log.info("Incoming connection to " + toString().replace("/", "") + " (port " + socket.getLocalPort() + ")" +
                      "\n* From: " + socket.getPeerDestination().calculateHash() + " port " + socket.getPort());
        }
        long afterAccept = getTunnel().getContext().clock().now();
        long afterSocket = -1;

        try {
            // Set read timeout on the incoming socket to avoid indefinite blocking
            socket.setReadTimeout(readTimeout);

            // Create a new outgoing socket to the remote peer
            Socket s = getSocket(socket.getPeerDestination().calculateHash(), socket.getLocalPort());

            // Optional: Tune socket performance preferences for this connection
            s.setPerformancePreferences(1, 2, 3);  // Example: prioritize low latency and bandwidth

            // Optional: Set socket buffer sizes for throughput improvement
            int bufferSize = BUFFER_SIZE;
            s.setReceiveBufferSize(bufferSize);
            s.setSendBufferSize(bufferSize);

            afterSocket = getTunnel().getContext().clock().now();

            // Delegate to client executor pool for actual handling to avoid blocking here
            Thread t = new I2PTunnelRunner(s, socket, slock, null, null, null, (I2PTunnelRunner.FailCallback) null);
            _clientExecutor.execute(t);

            long afterHandle = getTunnel().getContext().clock().now();
            long timeToHandle = afterHandle - afterAccept;
            // Log only if handling takes unusually long to avoid excessive log overhead
            if ((timeToHandle > 1500) && (_log.shouldInfo())) {
                _log.info("Took a while (" + timeToHandle + "ms) to handle the request for " + remoteHost + ':' + remotePort +
                          "\n* Socket create: " + (afterSocket-afterAccept) + "ms");
            }
        } catch (SocketException ex) {
            int port = socket.getLocalPort();
            try {
                socket.reset();
            } catch (IOException ioe) {
                _log.debug("IOException during socket reset for port " + port, ioe);
            }
            if (_log.shouldError()) {
                _log.error("SocketException connecting to server " + getSocketString(port), ex);
            }
        } catch (IOException ex) {
            _log.error("IOException while waiting for I2PConnections", ex);
        }
    }

    /**
     * Get a regular or SSL socket depending on config and the incoming port.
     *
     * <p>Allows configuring a specific target host:port mapping for incoming ports with the option
     * "targetForPort.{port}=host:port". For SSL, avoids SSL-over-SSL on certain ports (e.g., 443 and 22).</p>
     *
     * @param from may be used to construct a unique local address since version 0.9.13
     * @param incomingPort the local port for which a socket is requested
     * @return a connected Socket, either SSL or plain depending on configuration and port
     * @throws IOException on socket creation or address resolution failure
     */
    protected Socket getSocket(Hash from, int incomingPort) throws IOException {
        InetAddress host = remoteHost;
        int port = remotePort;
        if (incomingPort != 0 && !_socketMap.isEmpty()) {
            InetSocketAddress isa = _socketMap.get(Integer.valueOf(incomingPort));
            if (isa != null) {
                host = isa.getAddress();
                if (host == null) {
                    throw new IOException("Cannot resolve " + isa.getHostName());
                }
                port = isa.getPort();
            }
        }
        // Avoid SSL-over-SSL on ports commonly used with SSL or SSH
        boolean force = incomingPort == 443 || incomingPort == 22;
        Socket s = getSocket(from, host, port, force);
        // Tune send/receive buffer sizes to reduce system call overhead and improve throughput
        int bufSize = BUFFER_SIZE;  // 64 KB buffer size chosen empirically
        s.setReceiveBufferSize(bufSize);
        s.setSendBufferSize(bufSize);
        // Hint JVM/network stack for latency and bandwidth preferences
        s.setPerformancePreferences(1, 2, 3);

        return s;
    }

    /**
     * Returns a string representing the target host:port for logging purposes.
     *
     * @param incomingPort incoming port used to look up mapped target addresses
     * @return human-readable hostname:port string for logging
     */
    protected String getSocketString(int incomingPort) {
        if (incomingPort != 0 && !_socketMap.isEmpty()) {
            InetSocketAddress isa = _socketMap.get(Integer.valueOf(incomingPort));
            if (isa != null) {
                // Remove leading slash for cleaner logging output
                return isa.toString().replace("/", "");
            }
        }
        return remoteHost.toString().replace("/", "") + ':' + remotePort;
    }

    /**
     * Get a regular or SSL socket depending on config.
     *
     * @param from may be used to construct local address since 0.9.13
     * @param remoteHost remote address to connect to
     * @param remotePort remote port to connect to
     * @return a new Socket connected to the remote host and port
     * @throws IOException on socket errors
     * @since 0.9.9
     */
    protected Socket getSocket(Hash from, InetAddress remoteHost, int remotePort) throws IOException {
        // Delegate to main getSocket method without forcing non-SSL
        return getSocket(from, remoteHost, remotePort, false);
    }

    /**
     * Get a regular or SSL socket depending on config, with option to force non-SSL.
     *
     * <p>If SSL is enabled and not forced off, creates an SSL socket from a cached SSL factory.</p>
     * <p>If SSL is disabled or forced off, creates a plain socket. If the connection is loopback and
     * configured for unique local addresses, constructs the local address accordingly.</p>
     *
     * @param from may be used to construct local address since 0.9.13
     * @param remoteHost remote address to connect to
     * @param remotePort remote port to connect to
     * @param forceNonSSL whether to override config and create a plain socket instead of SSL
     * @return a new Socket or SSLSocket connected to the remote host and port
     * @throws IOException on socket errors or SSL initialization failures
     * @since 0.9.50
     */
    private Socket getSocket(Hash from, InetAddress remoteHost, int remotePort, boolean forceNonSSL) throws IOException {
        String opt = getTunnel().getClientOptions().getProperty(PROP_USE_SSL);
        if (!forceNonSSL && Boolean.parseBoolean(opt)) {
            // Lazily initialize SSL factory with thread safety
            synchronized(sslLock) {
                if (_sslFactory == null) {
                    try {
                        _sslFactory = new I2PSSLSocketFactory(getTunnel().getContext(),
                                                               true, "certificates/i2ptunnel");
                    } catch (GeneralSecurityException gse) {
                        // Wrap security exceptions as IOExceptions to conform to signature
                        IOException ioe = new IOException("SSL initialization failed");
                        ioe.initCause(gse);
                        throw ioe;
                    }
                }
            }
            Socket sslSocket = _sslFactory.createSocket(remoteHost, remotePort);
            // Apply same performance tuning to SSL socket
            sslSocket.setReceiveBufferSize(BUFFER_SIZE);
            sslSocket.setSendBufferSize(BUFFER_SIZE);
            sslSocket.setPerformancePreferences(1, 2, 3);

            return sslSocket;
        } else {
            boolean unique = Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_UNIQUE_LOCAL));
            if (unique && remoteHost.isLoopbackAddress()) {
                // Construct a unique local address using 'from' to prevent collisions on loopback
                byte[] addr;
                if (remoteHost instanceof Inet4Address) {
                    addr = new byte[4];
                    addr[0] = 127; // IPv4 loopback prefix
                    System.arraycopy(from.getData(), 0, addr, 1, 3);
                } else {
                    addr = new byte[16];
                    addr[0] = (byte) 0xfd; // Unique local IPv6 prefix
                    System.arraycopy(from.getData(), 0, addr, 1, 15);
                }
                InetAddress local = InetAddress.getByAddress(addr);

                Socket socket = new Socket(remoteHost, remotePort, local, 0);
                socket.setReceiveBufferSize(BUFFER_SIZE);
                socket.setSendBufferSize(BUFFER_SIZE);
                socket.setPerformancePreferences(1, 2, 3);
                return socket;
            } else {
                Socket socket = new Socket(remoteHost, remotePort);
                socket.setReceiveBufferSize(BUFFER_SIZE);
                socket.setSendBufferSize(BUFFER_SIZE);
                socket.setPerformancePreferences(1, 2, 3);
                return socket;
            }
        }
    }

}
