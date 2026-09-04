package net.i2p.i2ptunnel;

import static net.i2p.app.ClientAppState.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.I2PAppContext;
import net.i2p.app.*;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataHelper;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SystemVersion;

/**
 * Coordinates tunnel groups within the JVM, managing configuration persistence.
 * <p>
 * Loads and stores tunnel configurations to disk, builds new tunnels on request.
 * Entry point from clients.config.
 */
public class TunnelControllerGroup implements ClientApp {
    private final Log _log;
    private volatile ClientAppState _state;
    private final I2PAppContext _context;
    private final ClientAppManager _mgr;
    private static volatile TunnelControllerGroup instance;
    /** Default config file name */
    static final String DEFAULT_CONFIG_FILE = "i2ptunnel.config";
    /** Config directory for split config files */
    private static final String CONFIG_DIR = "i2ptunnel.config.d";
    /** Config key prefix */
    private static final String PREFIX = "tunnel.";

    private final List<TunnelController> _controllers;
    private final ReadWriteLock _controllersLock;
    private boolean _controllersLoaded;
    private final String _configFile;
    private final String _configDirectory;

    /** Service name for ClientApp registration */
    private static final String REGISTERED_NAME = "i2ptunnel";

    /**
     *  Flag to indicate if delayed server tunnel shutdown is in progress.
     *  Set by shutdownDelayedServers() and checked by GracefulShutdown.
     *  @since 0.9.68+
     */
    private static volatile boolean delayedShutdownInProgress = false;

    /**
     *  Timestamp when delayed shutdown started, used to calculate remaining wait time.
     *  @since 0.9.68+
     */
    private static volatile long delayedShutdownStartTime = 0;

    /**
     *  Flag to signal that delayed shutdown should be cancelled.
     *  Set by cancelDelayedShutdown() and checked by shutdown tasks.
     *  @since 0.9.68+
     */
    private static volatile boolean cancelDelayedShutdown = false;

    /**
     *  Executor service for delayed shutdown tasks, used for cancellation.
     *  @since 0.9.68+
     */
    private ExecutorService _delayedShutdownExecutor;

    /** Session ownership map for preventing premature session close */
    private final Map<I2PSession, Set<TunnelController>> _sessions;

    /** Pool of socket handlers for all clients */
    private ThreadPoolExecutor _executor;
    private static final AtomicLong _executorThreadCount = new AtomicLong();
    private final Object _executorLock = new Object();
    /** how long to wait before dropping an idle thread */
    private static final long HANDLER_KEEPALIVE_MS = (long) 30*1000;

    /** Shared bounded executor for server tunnel connection handlers */
    private ThreadPoolExecutor _serverExecutor;
    private static final AtomicLong _serverExecutorThreadCount = new AtomicLong();
    private final Object _serverExecutorLock = new Object();
    /** Server handler idle keepalive */
    private static final long SERVER_KEEPALIVE_MS = (long) 30*1000;

    /** Tuned by Tuner */
    private static volatile int serverHandlerThreads = Math.max(SystemVersion.getCores(), 4);
    /** Tuned by Tuner */
    private static volatile int clientRunnerMax = 1024;
    /** Tuned by Tuner: how many inbound connections may wait in the server handler queue */
    private static volatile int serverBacklogQueueCapacity = 1024;

    /**
     *  The number of threads handling connections to server tunnels.
     *  @return the server handler threads
     */
    public static int getServerHandlerThreads() { return serverHandlerThreads; }
    /**
     *  Clamp and set the number of server handler threads, 2 to 512.
     *  The high ceiling lets the Tuner drain the shared server-handler pool
     *  under sustained inbound load (e.g. a high-volume server tunnel) where
     *  workers stall on slow inbound I2P writes rather than finishing quickly.
     *  @param val the desired count
     */
    public static void setServerHandlerThreads(int val) {
        serverHandlerThreads = Math.max(2, Math.min(512, val));
    }
    /**
     *  The maximum number of connections that may wait in the server handler
     *  bounded queue before overflow rejection ({@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}).
     *  @return the queue capacity
     *  @since 0.9.71+
     */
    public static int getServerBacklogQueueCapacity() { return serverBacklogQueueCapacity; }
    /**
     *  Clamp and set the server handler queue capacity, 16 to 65536.
     *  The Tuner I2PTunnelServerBacklogParam stays within this same range.
     *  @param val the desired capacity
     *  @since 0.9.71+
     */
    public static void setServerBacklogQueueCapacity(int val) {
        serverBacklogQueueCapacity = Math.max(16, Math.min(65536, val));
    }
    /**
     *  The maximum number of concurrent client connections.
     *  @return the client runner max
     */
    public static int getClientRunnerMax() { return clientRunnerMax; }
    /** Ceiling for the concurrent client worker pool. */
    private static final int CLIENT_RUNNER_MAX_MAX = 16384;
    /**
     *  Clamp and set the maximum concurrent client connections, 4 to 16384.
     *  The Tuner I2PTunnelClientRunnerMaxParam stays within this same range.
     *
     *  <p>Enforces a one-way invariant against the Tuner-managed admission gate
     *  ({@link #clientDefaultMaxConnections}): the worker pool may never be shrunk
     *  below what the gate admits. Connections are served thread-per-connection on a
     *  {@link java.util.concurrent.SynchronousQueue} (no queuing), so every admitted
     *  connection needs its own worker; if the runner were allowed to collapse below
     *  the gate, bursts would overflow the worker pool and be closed with zero bytes
     *  as empty responses. Flooring the runner at the gate keeps admission and
     *  serving capacity in the same ballpark; the runner may still grow above the
     *  gate to absorb bursts.
     *
     *  @param val the desired maximum
     *  @since 0.9.71+
     */
    public static void setClientRunnerMax(int val) {
        int floor = clientDefaultMaxConnections;
        clientRunnerMax = Math.max(floor, Math.min(CLIENT_RUNNER_MAX_MAX, val));
    }

    /** Low clamp for the Tuner-managed default client cap. */
    private static final int CLIENT_MAX_CONNECTIONS_MIN = 256;
    /** High clamp for the Tuner-managed default client cap. */
    private static final int CLIENT_MAX_CONNECTIONS_MAX = 16384;

    /**
     *  The Tuner-managed default concurrent-connection cap for client tunnels that
     *  do not configure an explicit {@value I2PTunnelClientBase#PROP_MAX_CONNECTIONS}
     *  override. Kept separate from {@link #clientRunnerMax} (the shared HTTP proxy
     *  executor ceiling) because that cap bounds the worker pool, whereas this cap
     *  gates the accept loop from over-dispatching before a handler thread exists.
     *
     *  <p>An explicit per-tunnel override always wins over this default; the Tuner
     *  can only move the floor for tunnels that leave the cap at default. Raised
     *  under sustained load to defer connection shedding (which the browser reports
     *  as an empty response), lowered under idle to conserve memory/FDs.
     *
     *  @since 0.9.71+
     */
    private static volatile int clientDefaultMaxConnections = I2PTunnelClientBase.DEFAULT_MAX_CONNECTIONS;

    /**
     *  The Tuner-managed default concurrent-connection cap for unset client tunnels.
     *  @return the default caps, 256 to 16384
     *  @since 0.9.71+
     */
    public static int getClientDefaultMaxConnections() { return clientDefaultMaxConnections; }
    /**
     *  Clamp and set the Tuner-managed default concurrent-connection cap, 256 to 16384.
     *  The Tuner {@code I2PTunnelClientMaxConnectionsParam} stays within this range.
     *  @param val the desired default cap
     *  @since 0.9.71+
     */
    public static void setClientDefaultMaxConnections(int val) {
        clientDefaultMaxConnections = Math.max(CLIENT_MAX_CONNECTIONS_MIN, Math.min(CLIENT_MAX_CONNECTIONS_MAX, val));
    }

    /** Socket connect timeout in ms, tuned by Tuner */
    private static volatile int socketConnectTimeout = 10000;

    /**
     *  The socket connect timeout in milliseconds.
     *  @return the socket connect timeout
     */
    public static int getSocketConnectTimeout() { return socketConnectTimeout; }
    /**
     *  Clamp and set the socket connect timeout, 5000 to 120000 ms.
     *  @param val the timeout in milliseconds
     */
    public static void setSocketConnectTimeout(int val) {
        socketConnectTimeout = Math.max(5000, Math.min(120000, val));
    }

    /** Rate stat intervals */
    static final long[] RATES = {60*1000L, 10*60*1000L, 60*60*1000L};

    /**
     *  In I2PAppContext will instantiate if necessary and always return non-null.
     *  As of 0.9.4, when in RouterContext, will return null
     *  if the TCG has not yet been started by the router.
     *  As of 0.9.41, that's true for Android as well.
     *
     *  In Android, this should be used for all calls except from LoadClientsJob,
     *  as we do not want to instantiate TCG too early. Android must do null
     *  checks on the return value.
     *
     *  @throws IllegalArgumentException if unable to load from i2ptunnel.config
     *  @return the instance
     */
    public static TunnelControllerGroup getInstance() {
        synchronized (TunnelControllerGroup.class) {
            if (instance == null && !SystemVersion.isAndroid()) {
                I2PAppContext ctx = I2PAppContext.getGlobalContext();
                if (!ctx.isRouterContext()) {
                    instance = new TunnelControllerGroup(ctx, null, null);
                    instance.startup();
                }
            } // else wait for the router to start it
            return instance;
        }
    }

    /**
     *  In I2PAppContext will instantiate if necessary and always return non-null.
     *  When in RouterContext, will return null (except in Android)
     *  if the TCG has not yet been started by the router.
     *  In Android, if the old instance uses a stale context, it will replace it.
     *
     *  In Android, this should only be called from LoadClientsJob, as we do not
     *  want to instantiate TCG too early.
     *
     *  @throws IllegalArgumentException if unable to load from i2ptunnel.config
     *  @return the instance
     *  @since 0.9.41
     */
    public static TunnelControllerGroup getInstance(I2PAppContext ctx) {
        synchronized (TunnelControllerGroup.class) {
            if (instance == null) {
                if (SystemVersion.isAndroid() || !ctx.isRouterContext()) {
                    instance = new TunnelControllerGroup(ctx, null, null);
                    instance.startup();
                } // else wait for the router to start it
            } else {
                if (SystemVersion.isAndroid() && instance._context != ctx) {
                    ctx.logManager().getLog(TunnelControllerGroup.class).warn("Old context in TunnelControllerGroup");
                    instance.shutdown();
                    instance = new TunnelControllerGroup(ctx, null, null);
                 }
            }
            return instance;
        }
    }

    /**
     *  Instantiation only. Caller must call startup().
     *  Config file problems will not throw exception until startup().
     *
     *  @param mgr may be null
     *  @param args zero or one args, which may be one config file or one config
     *  directory. If not absolute will be relative to the context's config dir,
     *              if empty or null, the default is i2ptunnel.config for a
     *              config file and i2ptunnel.config.d for a config directory
     *  @throws IllegalArgumentException if too many args
     *  @since 0.9.4
     */
    public TunnelControllerGroup(I2PAppContext context, ClientAppManager mgr, String[] args) {
        _state = UNINITIALIZED;
        _context = context;
        _mgr = mgr;
        _log = _context.logManager().getLog(TunnelControllerGroup.class);
        _controllers = new ArrayList<>();
        _controllersLock = new ReentrantReadWriteLock(true);
        if (args == null || args.length <= 0) {
            _configFile = DEFAULT_CONFIG_FILE;
            _configDirectory = CONFIG_DIR;
        } else if (args.length == 1) {
            String[] answer = setupArguments(args);
            _configFile = answer[0];
            _configDirectory = answer[1];
        } else {
            throw new IllegalArgumentException("Usage: TunnelControllerGroup [filename] [configdirectory] ");
        }
        _sessions = new HashMap<>(4);
        synchronized (TunnelControllerGroup.class) {
            if (instance == null) {
                instance = this;
            } else {
                _log.logAlways(Log.WARN, "New TunnelControllerGroup, now you have two");
                if (_log.shouldWarn())
                    _log.warn("I did it", new Exception());
            }
        }
        _state = INITIALIZED;
    }

    /**
     * Reads argument list and returns two strings that can be used to
     * instantiate _configFile and _configDirectory. After calling this
     * method, the returned Strings must be assigned to _configFile and
     * _configDirectory.
     *
     * @param args must be the args passed to TunnelControllerGroup.
     * @return an array of exactly 2 strings, where [0] is the the value for
     * _configFile and [1] is the value for _configDirectory
     */
    private String[] setupArguments(String[] args){
        String configFile = DEFAULT_CONFIG_FILE;
        String configDirectory = CONFIG_DIR;
        File check = resolveConfig(args[0]);
        if (check.isFile()) {
            configFile = args[0];
        } else if (check.isDirectory()) {
            configDirectory = args[0];
        }
        return new String[]{configFile, configDirectory};
    }

    /**
     *  Resolve a config path to an absolute file under the config dir.
     *
     *  @param name the config file or directory path
     *  @return the resolved file
     */
    private File resolveConfig(String name) {
        File file = new File(name);
        if (!file.isAbsolute())
            file = new File(_context.getConfigDir(), name);
        return file;
    }

    /**
     *  Load the tunnel configs from a file, for running standalone.
     *  @param args one arg, the config file, if not absolute will be relative to the context's config dir,
     *              if no args, the default is i2ptunnel.config
     *  @throws IllegalArgumentException if unable to load from config from file
     */
    public static void main(String[] args) {
        synchronized (TunnelControllerGroup.class) {
            if (instance != null) return; // already loaded through the web
            instance = new TunnelControllerGroup(I2PAppContext.getGlobalContext(), null, args);
            instance.startup();
        }
    }

    /**
     * Helper
     * @return the context
     * @since 0.9.49
     */
    public I2PAppContext getContext() {
        return _context;
    }

    /**
     *  ClientApp interface
     *  @throws IllegalArgumentException if unable to load config from file
     *  @since 0.9.4
     */
    public void startup() {
        File configFile = resolveConfig(_configFile);
        try {
            if (_log.shouldInfo())
                _log.info("Configuring tunnels from " + configFile);
            loadControllers(configFile);
        } catch (IllegalArgumentException iae) {
            if (DEFAULT_CONFIG_FILE.equals(configFile.getName()) && !_context.isRouterContext()) {
                // for i2ptunnel command line
                synchronized (this) {
                    _controllersLoaded = true;
                }
                _log.logAlways(Log.WARN, "Not in router context and no preconfigured tunnels");
            } else {
                throw iae;
            }
        }
        startControllers();
        if (_mgr != null)
            _mgr.register(this);
            // RouterAppManager registers its own shutdown hook
        else
            _context.addShutdownTask(new Shutdown());
    }

    /**
     * ClientApp interface
     * @return the state
     * @since 0.9.4
     */
    @Override
    public ClientAppState getState() {
        return _state;
    }

    /**
     *  The registered name of this tunnel group, for the ClientApp interface.
     *  @return the name
     *  @since 0.9.4
     */
    @Override
    public String getName() {
        return REGISTERED_NAME;
    }

    /**
     *  The display name of this tunnel group, for the ClientApp interface.
     *  @return the display name
     *  @since 0.9.4
     */
    @Override
    public String getDisplayName() {
        return REGISTERED_NAME;
    }

    /**
     *  Change the client app state.
     *  @param state the new state
     */
    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    /**
     *  Change the client app state and notify the manager.
     *  @param state the new state
     *  @param e optional cause
     */
    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, e);
    }

    /** Warning - destroys the singleton */
    private class Shutdown implements Runnable {
        @Override
        public void run() {shutdown();}
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public void shutdown(String[] args) {
        shutdown(true);
    }

    /**
     *  Warning - destroys the singleton!
     *  Caller must root a new context before calling instance() or main() again.
     *  Agressively kill and null everything to reduce memory usage in the JVM
     *  after stopping, and to recognize what must be reinitialized on restart (Android)
     *
     *  @param waitForDelayed true to wait for shutdown-delayed tunnels, false for immediate
     *  @since 0.8.8
     */
    public void shutdown(boolean waitForDelayed) {
        synchronized (this) {
            if (_state != STARTING && _state != RUNNING)
                return;
            changeState(STOPPING);
            if (_mgr != null)
                _mgr.unregister(this);
        }

        if (waitForDelayed) {
            shutdownDelayedServers();
        }

        synchronized (this) {
            unloadControllers();
            synchronized (TunnelControllerGroup.class) {
                if (instance == this)
                    instance = null;
            }
            killServerExecutor();
            killClientExecutor();
            changeState(STOPPED);
        }
    }

    /**
     *  Convenience method - calls shutdown(true) to wait for.
     *  @since 0.9.68+
     */
    public synchronized void shutdown() {
        shutdown(true);
    }

    /**
     *  Prepare for graceful shutdown by stopping delayed server tunnels.
     *  This should be called during graceful shutdown period, before
     *  final shutdown is triggered. It stops server tunnels with delays
     *  but does not unload controllers or release resources.
     *  @since 0.9.68+
     */
    public void prepareGracefulShutdown() {
        shutdownDelayedServers();
    }

    /**
     *  Check if delayed server tunnel shutdown is currently in progress.
     *  @return true if shutdownDelayedServers() is currently executing
     *  @since 0.9.68+
     */
    public static boolean isDelayedShutdownInProgress() {
        return delayedShutdownInProgress;
    }

    /**
     *  Get the maximum configured shutdown delay among all server tunnels.
     *  @return maximum delay in seconds, or 0 if no servers have delays configured
     *  @since 0.9.68+
     */
    public int getMaxShutdownDelay() {
        _controllersLock.readLock().lock();
        try {
            int maxDelay = 0;
            for (TunnelController controller : _controllers) {
                if (!controller.isClient() && controller.getIsRunning()) {
                    int delayMax = controller.getShutdownDelayMax();
                    if (delayMax > maxDelay) {
                        maxDelay = delayMax;
                    }
                }
            }
            return maxDelay;
        } finally {
            _controllersLock.readLock().unlock();
        }
    }

    /**
     *  Get the remaining time until the longest-delayed server tunnel will stop.
     *  @return remaining delay in seconds, or 0 if shutdown not in progress or delay has passed
     *  @since 0.9.68+
     */
    public static int getRemainingShutdownDelay() {
        if (!delayedShutdownInProgress || delayedShutdownStartTime <= 0) {
            return 0;
        }
        TunnelControllerGroup tcg = getInstance();
        if (tcg == null) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - delayedShutdownStartTime) / 1000;
        int maxDelay = tcg.getMaxShutdownDelay();
        int remaining = (int) (maxDelay - elapsed);
        return Math.max(0, remaining);
    }

    /**
     *  Cancel a delayed server tunnel shutdown and restart any servers that were stopped.
     *  This should be called when the user cancels a graceful router shutdown/restart.
     *  @since 0.9.68+
     */
    public void cancelDelayedShutdown() {
        if (!delayedShutdownInProgress) {
            return;
        }
        cancelDelayedShutdown = true;
        if (_delayedShutdownExecutor != null) {
            _delayedShutdownExecutor.shutdownNow();
        }
    }

    /**
     *  Stop server tunnels with shutdown delays independently.
     *  Each server with a delay gets its own timer and stops independently.
     *  @since 0.9.68+
     */
    private void shutdownDelayedServers() {
        List<TunnelController> delayedServers = getDelayedServers();
        if (delayedServers.isEmpty()) {
            return;
        }

        int maxDelay = 0;
        for (TunnelController controller : delayedServers) {
            int delayMax = controller.getShutdownDelayMax();
            if (delayMax > maxDelay) {
                maxDelay = delayMax;
            }
        }

        delayedShutdownInProgress = true;
        delayedShutdownStartTime = System.currentTimeMillis();
        cancelDelayedShutdown = false;
        List<TunnelController> stoppedServers = Collections.synchronizedList(new ArrayList<>());
        _delayedShutdownExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TunShutdown");
            t.setDaemon(true);
            return t;
        });

        scheduleDelayedShutdowns(delayedServers, stoppedServers);

        _delayedShutdownExecutor.shutdown();
        try {
            long maxWait = Math.min(maxDelay + 30, 300) * 1000L;
            boolean completed = _delayedShutdownExecutor.awaitTermination(maxWait, TimeUnit.MILLISECONDS);
            long elapsed = (System.currentTimeMillis() - delayedShutdownStartTime) / 1000;
            if (completed) {
                if (_log.shouldInfo())
                    _log.info("All delayed servers stopped in " + elapsed + "s");
            } else {
                if (_log.shouldWarn())
                    _log.warn("Timeout waiting for servers to stop after " + elapsed + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanupShutdown(stoppedServers);
        }
    }

    /**
     * Collect the running server tunnels that have a shutdown delay.
     *
     * @return the delayed servers, empty if none
     */
    private List<TunnelController> getDelayedServers() {
        _controllersLock.readLock().lock();
        List<TunnelController> delayedServers;
        try {
            delayedServers = new ArrayList<>();
            for (TunnelController controller : _controllers) {
                if (!controller.isClient() && controller.getIsRunning()) {
                    int delayMin = controller.getShutdownDelayMin();
                    int delayMax = controller.getShutdownDelayMax();
                    if (delayMax > delayMin && delayMin >= 0) {
                        delayedServers.add(controller);
                    }
                }
            }
        } finally {
            _controllersLock.readLock().unlock();
        }
        return delayedServers;
    }

    /**
     * Submit one shutdown task per delayed server, each waiting a
     * random delay in the configured range before stopping its tunnel.
     *
     * @param delayedServers the servers to stop
     * @param stoppedServers the list to record the stopped servers in
     */
    private void scheduleDelayedShutdowns(List<TunnelController> delayedServers, List<TunnelController> stoppedServers) {
        for (TunnelController controller : delayedServers) {
            int delayMin = controller.getShutdownDelayMin();
            int delayMax = controller.getShutdownDelayMax();
            int delay = delayMin + RandomSource.getInstance().nextInt(Math.max(1, delayMax - delayMin));
            final TunnelController tc = controller;
            final String name = controller.getName();
            final int actualDelay = delay;
            final List<TunnelController> stoppedList = stoppedServers;
            _delayedShutdownExecutor.submit(new I2PAppThread(new Runnable() {
                public void run() {
                    if (actualDelay > 0) {
                        for (int i = 0; i < actualDelay; i++) {
                            if (cancelDelayedShutdown) {
                                return;
                            }
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    if (cancelDelayedShutdown) {
                        return;
                    }
                    tc.stopTunnel();
                    stoppedList.add(tc);
                }
            }, "Shutdown timer for " + name));
        }
    }

    /**
     * Stop the shutdown executor, restarting any servers that were
     * stopped if the shutdown was cancelled.
     *
     * @param stoppedServers the servers stopped by the shutdown tasks
     */
    private void cleanupShutdown(List<TunnelController> stoppedServers) {
        _delayedShutdownExecutor.shutdownNow();
        if (cancelDelayedShutdown) {
            synchronized(stoppedServers) {
                for (TunnelController tc : stoppedServers) {
                    tc.startTunnelBackground();
                    if (_log.shouldInfo())
                        _log.info("Restarted " + tc.getName() + " after shutdown cancelled");
                }
            }
        }
        delayedShutdownInProgress = false;
        delayedShutdownStartTime = 0;
        cancelDelayedShutdown = false;
        _delayedShutdownExecutor = null;
    }

    /**
     *  Detects whether a migration to split configuration files should/will/has
     *  happened based on the platform and installation type. Does not tell
     *  whether a migration has actually occurred.
     *
     *  @returns true if a migration is relevant to the platform, false if not
     *  @since 0.9.42
     */
    private boolean shouldMigrate() {
        try {
            if (_context.isRouterContext() && !SystemVersion.isAndroid() &&
                !_context.getConfigDir().getCanonicalPath().equals(_context.getBaseDir().getCanonicalPath())) {
                return true;
            }
        } catch (IOException ioe) { /* ignored */ }
        return false;
    }

    /**
     * Load up all of the tunnels configured in the given file.
     * Prior to 0.9.20, also started the tunnels.
     * As of 0.9.20, does not start the tunnels, you must call startup()
     * or getInstance() instead of loadControllers().
     *
     * DEPRECATED for use outside this class. Use startup() or getInstance().
     *
     * @throws IllegalArgumentException if unable to load from file
     */
    public synchronized void loadControllers(File cfgFile) {
        if (_controllersLoaded)
            return;

        loadControllers(cfgFile, shouldMigrate());
    }

    /**
     *  Load the controllers from the config file.
     *  @param shouldMigrate migrate to, and load from, i2ptunnel.config.d
     *  @throws IllegalArgumentException if unable to load from file
     *  @since 0.9.42
     */
    private synchronized void loadControllers(File cfgFile, boolean shouldMigrate) {
        if (_log.shouldInfo())
            _log.info("Getting controllers from config file " + cfgFile);
        File dir = new SecureDirectory(_context.getConfigDir(), CONFIG_DIR);
        List<Properties> props = null;
        if (cfgFile.exists()) {
            try {
                props = loadConfig(cfgFile);
                if (shouldMigrate && !dir.exists()) {
                    boolean ok = migrate(props, cfgFile, dir);
                    if (!ok) {
                        shouldMigrate = false;
                    } else {
                        _log.logAlways(Log.WARN, "Migrated tunnel configurations to " + dir + " from " + cfgFile);
                    }
                } else {
                    _log.logAlways(Log.WARN, "Not migrating tunnel configurations");
                }
            } catch (IOException ioe) {
                if (_log.shouldError())
                    _log.error("Unable to load the controllers from " + cfgFile.getAbsolutePath());
                throw new IllegalArgumentException("Unable to load the controllers from " + cfgFile, ioe);
            }
        } else if (!shouldMigrate) {
                throw new IllegalArgumentException("Unable to load the controllers from " + cfgFile);
        }
        _controllersLock.writeLock().lock();
        try {
            if (shouldMigrate && dir.isDirectory()) {
                List<File> fileList = listFiles();
                for (File f : fileList) {
                    try {
                        props = loadConfig(f);
                        if (!props.isEmpty()) {
                            for (Properties cfg : props) {
                                String type = cfg.getProperty("type");
                                if (type == null)
                                    continue;
                                TunnelController controller = new TunnelController(cfg, "");
                                _controllers.add(controller);
                            }
                            if (_log.shouldInfo()) {
                                _log.info("Loaded application config from " + f.toString());
                            }
                        } else {
                            if (_log.shouldError())
                                _log.error("Error loading the client app properties from " + f);
                        }
                    } catch (IOException ioe) {
                        if (_log.shouldError())
                            _log.error("Error loading the client app properties from " + f + ' '+ ioe);
                    }
                }
            } else if (props != null) {
                // use what we got from i2ptunnel.config
                for (Properties cfg : props) {
                    String type = cfg.getProperty("type");
                    if (type == null)
                        continue;
                    TunnelController controller = new TunnelController(cfg, "");
                    _controllers.add(controller);
                }
            }
        } finally {
            _controllersLock.writeLock().unlock();
        }

        _controllersLoaded = true;
        int i = _controllers.size();
        if (i > 0) {
            if (_log.shouldInfo())
                _log.info(i + " controllers loaded from " + cfgFile);
        } else {
            _log.logAlways(Log.WARN, "No i2ptunnel configurations found in " + cfgFile + " or " + dir);
        }
    }

    /*
     * Migrate tunnels from file to individual files in dir
     *
     * @return success
     * @since 0.9.42
     */
    private boolean migrate(List<Properties> tunnels, File from, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs())
            return false;
        boolean ok = true;
        int i = 0;
        for (Properties props : tunnels) {
            String tname = props.getProperty("name");
            if (tname == null)
                tname = "tunnel";
            else
                tname = sanitize(tname);
            String name = i + "-" + tname + "-i2ptunnel.config";
            if (i < 10)
                name = '0' + name;
            File f = new File(dir, name);
            props.setProperty(TunnelController.PROP_CONFIG_FILE, f.getAbsolutePath());
            try {
                DataHelper.storeProps(props, f);
            } catch (IOException ioe) {
                if (_log.shouldError())
                    _log.error("Error migrating the i2ptunnel configuration to " + f, ioe);
                ok = false;
            }
            i++;
        }
        if (ok && !FileUtil.rename(from, new File(from.getAbsolutePath() + ".bak"))) {
            if (!from.delete() && _log.shouldWarn())
                _log.warn("Error migrating i2ptunnel config: unable to remove " + from);
        }
        return ok;
    }

    /**
     * Start all of the tunnels. Must call loadControllers() first.
     * @since 0.9.20
     */
    private synchronized void startControllers() {
        changeState(STARTING);
        I2PAppThread startupThread = new I2PAppThread(new StartControllers(), "StartTuns");
        startupThread.start();
        changeState(RUNNING);
    }

    /** Start all configured tunnels */
    private class StartControllers implements Runnable {
        @Override
        public void run() {
            synchronized(TunnelControllerGroup.this) {
                _controllersLock.readLock().lock();
                try {
                    if (_controllers.size() <= 0) {
                        _log.logAlways(Log.WARN, "No configured tunnels to start");
                        return;
                    }
                    for (TunnelController controller : _controllers) {
                        if (!controller.getStartOnLoad())
                            continue;
                        if (!controller.isClient()) {
                            int delayMin = controller.getStartupDelayMin();
                            int delayMax = controller.getStartupDelayMax();
                            if (delayMax > delayMin && delayMin >= 0) {
                                int delay = delayMin + RandomSource.getInstance().nextInt(Math.max(1, delayMax - delayMin));
                                String name = controller.getName();
                                String type = controller.getType();
                                String host = controller.getTargetHost();
                                String port = controller.getTargetPort();
                                String typeDesc;
                                if ("httpserver".equals(type)) {
                                    typeDesc = "HTTP Server";
                                } else if ("ircserver".equals(type)) {
                                    typeDesc = "IRC Server";
                                } else if ("httpbidirserver".equals(type)) {
                                    typeDesc = "Bidirectional HTTP Server";
                                } else if ("streamrserver".equals(type)) {
                                    typeDesc = "Streamr Server";
                                } else {
                                    typeDesc = "Server";
                                }
                                controller.changeState(TunnelController.TunnelState.DELAYED_START_PENDING);
                                controller.setStartupDelayEndTime(delay * 1000L);
                                final String msg = "‣ Delaying startup of " + name + " [" + typeDesc + " on " + host + ":" + port + "] for " + delay + "s...";
                                final TunnelController tc = controller;
                                new I2PAppThread(new Runnable() {
                                    public void run() {
                                        tc.log(msg);
                                        try {
                                            Thread.sleep((long) delay * 1000);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            tc.log("‣ Startup cancelled for " + name);
                                            return;
                                        }
                                        synchronized(tc) {
                                            if (tc.getState() != TunnelController.TunnelState.DELAYED_START_PENDING) {
                                                return;
                                            }
                                        }
                                        tc.log("‣ Starting " + name);
                                        tc.startTunnelBackground();
                                    }
                                }, "Tunnel startup delay for " + name).start();
                                continue;
                            }
                        }
                        controller.startTunnelBackground();
                    }
                } finally {
                    _controllersLock.readLock().unlock();
                }
            }
        }
    }

    /**
     * Stop all tunnels, reload config, and restart those configured to do so.
     * WARNING - Does NOT simply reload the configuration!!! This is probably not what you want.
     * This does not return or clear the controller messages.
     *
     * @throws IllegalArgumentException if unable to reload config file
     */
    public synchronized void reloadControllers() {
        unloadControllers();
        File cfgFile = resolveConfig(_configFile);
        loadControllers(cfgFile);
        startControllers();
    }

    /**
     * Stop and remove reference to all known tunnels (but don't delete any config file or do other silly things)
     *
     */
    public synchronized void unloadControllers() {
        if (!_controllersLoaded)
            return;

        _controllersLock.writeLock().lock();
        try {
            destroyAllControllers();
            _controllers.clear();
        } finally {
            _controllersLock.writeLock().unlock();
        }

        _controllersLoaded = false;
        if (_log.shouldInfo())
            _log.info("All controllers stopped and unloaded");
    }

    /**
     * Add the given tunnel to the set of known controllers (but don't add it to a config file or start it or anything)
     */
    public synchronized void addController(TunnelController controller) {
        _controllersLock.writeLock().lock();
        try {
            _controllers.add(controller);
        } finally {
            _controllersLock.writeLock().unlock();
        }
    }

    /**
     * Stop and remove the given tunnel.
     * Side effect - clears all messages the controller.
     * Does NOT delete the configuration - must call saveConfig() or removeConfig() also.
     *
     * @return list of messages from the controller as it is stopped
     */
    public synchronized List<String> removeController(TunnelController controller) {
        if (controller == null) return new ArrayList<>();
        controller.stopTunnel();
        List<String> msgs = controller.clearMessages();
        _controllersLock.writeLock().lock();
        try {
            _controllers.remove(controller);
        } finally {
            _controllersLock.writeLock().unlock();
        }
        msgs.add("Tunnel " + controller.getName() + " removed");
        return msgs;
    }

    /**
     * Stop all tunnels. May be restarted.
     * Side effect - clears all messages from all controllers.
     *
     * @return list of messages the tunnels generate when stopped
     */
    public synchronized List<String> stopAllControllers() {
        List<String> msgs = new ArrayList<>();
        _controllersLock.readLock().lock();
        try {
            for (TunnelController controller : _controllers) {
                controller.stopTunnel();
                msgs.addAll(controller.clearMessages());
            }
            if (_log.shouldInfo())
                _log.info(_controllers.size() + " controllers stopped");
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     *  Stop all tunnels. They may not be restarted, you must reload.
     *  Caller must synch. Caller must clear controller list.
     *
     *  @since 0.9.17
     */
    private void destroyAllControllers() {
        for (TunnelController controller : _controllers) {
            controller.destroyTunnel();
        }
        if (_log.shouldInfo())
            _log.info(_controllers.size() + " controllers stopped");
    }

    /**
     * Start all tunnels.
     * Side effect - clears all messages from all controllers.
     *
     * @return list of messages the tunnels generate when started
     */
    public synchronized List<String> startAllControllers() {
        List<String> msgs = new ArrayList<>();
        _controllersLock.readLock().lock();
        try {
            for (TunnelController controller : _controllers) {
                controller.startTunnelBackground();
                msgs.addAll(controller.clearMessages());
            }

            if (_log.shouldInfo())
                _log.info(_controllers.size() + " controllers started");
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     * Restart all tunnels.
     * Side effect - clears all messages from all controllers.
     *
     * @return list of messages the tunnels generate when restarted
     */
    public synchronized List<String> restartAllControllers() {
        List<String> msgs = new ArrayList<>();
        _controllersLock.readLock().lock();
        try {
            for (TunnelController controller : _controllers) {
                controller.restartTunnel();
                msgs.addAll(controller.clearMessages());
            }
            if (_log.shouldInfo())
                _log.info(_controllers.size() + " controllers restarted");
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     * Fetch and clear all outstanding messages from any of the known tunnels.
     *
     * @return list of messages the tunnels have generated
     */
    public List<String> clearAllMessages() {
        List<String> msgs = new ArrayList<>();
        _controllersLock.readLock().lock();
        try {
            for (TunnelController controller : _controllers) {
                msgs.addAll(controller.clearMessages());
            }
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     * Save the configuration of all known tunnels to the default config
     * file
     *
     * @deprecated use saveConfig(TunnelController) or removeConfig(TunnelController)
     */
    @Deprecated
    public void saveConfig() throws IOException {
        _controllersLock.readLock().lock();
        if (shouldMigrate()) {
            try {
                for (TunnelController controller : _controllers) {
                    saveConfig(controller);
                }
            } finally {
                _controllersLock.readLock().unlock();
            }
        } else {
            try {
                File cfgFile = resolveConfig(_configFile);
                saveConfig(cfgFile);
            } finally {
                _controllersLock.readLock().unlock();
            }
        }
    }

    /**
     * Save the configuration of all known tunnels to the given file
     * @deprecated
     */
    @Deprecated
    public synchronized void saveConfig(String cfgFile) throws IOException {
        saveConfig(new File(cfgFile));
    }

    /**
     * Save the configuration of all known tunnels to the given file.
     * Side effect: for split config, sets "confFile" property to absolute path.
     * @since 0.9.42
     */
    private synchronized void saveConfig(File cfgFile) throws IOException {
        File parent = cfgFile.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
        Properties map = new OrderedProperties();
        _controllersLock.readLock().lock();
        try {
            int i = 0;
            for (TunnelController controller : _controllers) {
                Properties cur = controller.getConfig(PREFIX + i + ".");
                map.putAll(cur);
                i++;
            }
            map.setProperty(TunnelController.PROP_CONFIG_FILE, cfgFile.getAbsolutePath());
        } finally {
            _controllersLock.readLock().unlock();
        }
        DataHelper.storeProps(map, cfgFile);
    }

    /**
     * Save the configuration of this tunnel only, may be new.
     * Side effect: for split config, sets "confFile" property to absolute path.
     * @since 0.9.42
     */
    public synchronized void saveConfig(TunnelController tc) throws IOException {
        if (!shouldMigrate()){
            saveConfig();
            return;
        }
        if (_log.shouldInfo())
            _log.info("Saving tunnel configuration for " + tc);
        Properties inputController = new OrderedProperties();
        inputController.putAll(tc.getConfig(""));
        File cfgFile = assureConfigFile(tc);
        inputController.setProperty(TunnelController.PROP_CONFIG_FILE, cfgFile.getAbsolutePath());
        DataHelper.storeProps(inputController, cfgFile);
        tc.setConfig(inputController, "");
    }

    /**
     * Remove the configuration of this tunnel only
     * @since 0.9.42
     */
    public synchronized void removeConfig(TunnelController tc) throws IOException {
        File cfgFile = assureConfigFile(tc);
        if (!FileUtil.rename(cfgFile, new File(cfgFile.getAbsolutePath() + ".bak")) &&
            !cfgFile.delete() && _log.shouldWarn())
            _log.warn("could not delete config file" + cfgFile.toString());
        if (!shouldMigrate())
            saveConfig();
    }

    /** From i2psnark Storage.java */
    private static final char[] ILLEGAL = new char[] {
        '<', '>', ':', '"', '/', '\\', '|', '?', '*',
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
        0x7f,
        0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
        0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
        // unicode newlines
        0x2028, 0x2029
    };

    /**
     * Replace problematic characters in file name
     * @since 0.9.42
     */
    private static String sanitize(String rv) {
        for (int i = 0; i < ILLEGAL.length; i++) {
            if (rv.indexOf(ILLEGAL[i]) >= 0)
                rv = rv.replace(ILLEGAL[i], '_');
        }
        return rv;
    }

    /**
     * return the config File associated with a TunnelController or a default
     * File based on the tunnel name.
     *
     * @return the File ready for use
     * @since 0.9.42
     */
    private synchronized File assureConfigFile(TunnelController tc) {
        File file = tc.getConfigFile();
        if (file != null)
            return file;
        Properties inputController = tc.getConfig("");
        String fileName = inputController.getProperty("name");
        if (fileName == null)
            fileName = "New Tunnel";
        else
            fileName = sanitize(fileName);
        String configFileName = _controllers.size() + "-" + fileName + "-i2ptunnel.config";
        if (_controllers.size() < 10)
            configFileName = '0' + configFileName;
        File folder = resolveConfig(_configDirectory);
        file = new File(folder, configFileName);
        tc.setConfigFile(file);
        return file;
    }

    /**
     * List all the config files in the config directory, or the monolithic
     * config file, if they exist
     *
     * @return non-null list of files, sorted
     * @since 0.9.42
     */
    private List<File> listFiles() {
        File folder = resolveConfig(_configDirectory);
        if (_log.shouldInfo())
            _log.info("Seeking controller configs in " + folder.toString());
        File[] listOfFiles = folder.listFiles(new FileSuffixFilter(".config"));
        List<File> files = new ArrayList<>();
        if (listOfFiles != null && listOfFiles.length > 0){
            for (File afile : listOfFiles) {
                files.add(afile);
                if (_log.shouldInfo())
                    _log.info("Found controller config " + afile.toString());
            }
            Collections.sort(files);
        } else {
            File cfgFile = resolveConfig(_configFile);
            files.add(cfgFile);
        }
        return files;
    }

    /**
     * Load up the config data from either type of config file automatically.
     * Side effect: for split config, sets "confFile" property to absolute path.
     *
     * @return non-null, properties loaded, one for each tunnel
     * @throws IOException if unable to load from file
     * @since 0.9.42
     */
    private synchronized List<Properties> loadConfig(File cfgFile) throws IOException {
        Properties config = new Properties();
        DataHelper.loadProps(config, cfgFile);
        for (String key : config.stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                if (_log.shouldDebug())
                    _log.debug("Found monolithic config file " + cfgFile);
                return splitMonolithicConfig(config);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Found split config file " + cfgFile);
                List<Properties> rv = new ArrayList<>(1);
                config.setProperty(TunnelController.PROP_CONFIG_FILE, cfgFile.getAbsolutePath());
                rv.add(config);
                return rv;
            }
        }
        throw new IOException("No config found in " + cfgFile);
    }

    /**
     * Split up the config data loaded from a single file, this is the old version for the
     * numbered config file, into properties one for each tunnel.
     *
     * @return non-null, properties loaded, one for each tunnel
     * @throws IOException if unable to load from file
     * @since 0.9.42
     */
    private List<Properties> splitMonolithicConfig(Properties config) {
        List<Properties> rv = new ArrayList<>();
        int i = 0;
        while (true) {
            String prefix = PREFIX + i + ".";
            Properties p = new OrderedProperties();
            for (Map.Entry<Object, Object> e : config.entrySet()) {
                String key = (String) e.getKey();
                if (key.startsWith(prefix)) {
                    key = key.substring(prefix.length());
                    String val = (String) e.getValue();
                    p.setProperty(key, val);
                }
            }
            if (p.isEmpty())
                break;
            rv.add(p);
            i++;
        }
        return rv;
    }

    /**
     * Retrieve a list of tunnels known.
     *
     * Side effect: if the tunnels have not been loaded from config yet, they
     * will be.
     *
     * @return list of TunnelController objects
     * @throws IllegalArgumentException if unable to load config from file
     */
    public List<TunnelController> getControllers() {
        List<TunnelController> rv = new ArrayList<>();
        File cfgFile = resolveConfig(_configFile);
        rv.addAll(getControllers(cfgFile));
        return rv;
     }

    /**
     * Retrieve a list of tunnels known.
     *
     * Side effect: if the tunnels have not been loaded from config yet, they
     * will be.
     *
     * @return list of TunnelController objects
     * @throws IllegalArgumentException if unable to load config from file
     * @since 0.9.42
     */
    private List<TunnelController> getControllers(File cfgFile) {
        synchronized (this) {
            if (!_controllersLoaded)
                loadControllers(cfgFile);
        }

        _controllersLock.readLock().lock();
        try {
            return new ArrayList<>(_controllers);
        } finally {
            _controllersLock.readLock().unlock();
        }
    }

    /**
     * Note the fact that the controller is using the session so that
     * it isn't destroyed prematurely.
     *
     */
    void acquire(TunnelController controller, I2PSession session) {
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners == null) {
                owners = new HashSet<>(2);
                _sessions.put(session, owners);
            }
            owners.add(controller);
        }
        if (_log.shouldInfo())
            _log.info("Acquiring session " + session + "\n* For: " + controller);

    }

    /**
     * Note the fact that the controller is no longer using the session, and if
     * no other controllers are using it, destroy the session.
     *
     */
    void release(TunnelController controller, I2PSession session) {
        boolean shouldClose = false;
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners != null) {
                owners.remove(controller);
                if (owners.isEmpty()) {
                    if (_log.shouldInfo())
                        _log.info("After releasing session " + session + " by " + controller + ", no more owners remain");
                    shouldClose = true;
                    _sessions.remove(session);
                } else {
                    if (_log.shouldInfo())
                        _log.info("After releasing session " + session + " by " + controller + ", " + owners.size() + " owners remain");
                    shouldClose = false;
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("After releasing session " + session + " by " + controller + ", no owners were even known?!");
                shouldClose = true;
            }
        }
        if (shouldClose) {
            try {
                session.destroySession();
                if (_log.shouldInfo())
                    _log.info("Session destroyed: " + session);
            } catch (I2PSessionException ise) {
                if (_log.shouldError())
                    _log.error("Error closing the client session", ise);
            }
        }
    }

    /**
     *  The executor pool for client tunnel tasks.
     *  @return non-null
     *  @since 0.9.8 Moved from I2PTunnelClientBase in 0.9.18
     */
    ThreadPoolExecutor getClientExecutor() {
        synchronized (_executorLock) {
            if (_executor == null) {
                _executor = new CustomThreadPoolExecutor();
                I2PAppContext ctx = _context;
                if (ctx != null) {
                    ctx.statManager().createRequiredRateStat("i2ptunnel.clientRunner.activeThreads", "Client runner active threads", "I2PTunnel", RATES);
                }
            } else if (_executor.getMaximumPoolSize() != clientRunnerMax) {
                resizeClientExecutor(clientRunnerMax);
            }
            // Sample the real load for Tuner feedback. This is the *observed* active-thread
            // count, not the configured cap - the old poolSize stat was written by the very
            // resize it fed back into, producing a self-fulfilling ramp to the ceiling.
            I2PAppContext ctx = _context;
            if (ctx != null) {
                ctx.statManager().addRateData("i2ptunnel.clientRunner.activeThreads", _executor.getActiveCount());
            }
        }
        return _executor;
    }

    /**
     *  Shared bounded executor for server tunnel connection handlers.
     *  Tasks are short-lived (µs-scale), so core threads handle bursts via a queue.
     *  Overflow throws {@link java.util.concurrent.RejectedExecutionException}
     *  (AbortPolicy) rather than running the handler inline on the accept thread,
     *  so the accept loop can never be pinned by a write-blocked handler.
     *
     *  @return non-null
     */
    ThreadPoolExecutor getServerExecutor() {
        synchronized (_serverExecutorLock) {
            if (_serverExecutor == null) {
                _serverExecutor = createServerExecutor(serverHandlerThreads, _serverExecutorThreadCount);
                I2PAppContext ctx = _context;
                if (ctx != null) {
                    ctx.statManager().createRequiredRateStat("i2ptunnel.serverHandler.queueDepth", "Server handler tasks waiting", "I2PTunnel", RATES);
                    ctx.statManager().createRequiredRateStat("i2ptunnel.serverHandler.active", "Server handler active threads", "I2PTunnel", RATES);
                    ctx.statManager().createRequiredRateStat("i2ptunnel.serverHandler.threads", "Server handler thread count", "I2PTunnel", RATES);
                    ctx.statManager().createRequiredRateStat("i2ptunnel.serverHandler.blockingHandleTime", "Handler socket connect time (ms)", "I2PTunnel", RATES);
                    ctx.statManager().createRequiredRateStat("i2ptunnel.serverHandler.socketConnectTime", "Socket connect time (ms)", "I2PTunnel", RATES);
                }
            } else if (_serverExecutor.getCorePoolSize() != serverHandlerThreads) {
                resizeServerExecutor(serverHandlerThreads);
            }
        }
        return _serverExecutor;
    }

    /**
     *  Create the shared, bounded executor that runs inbound server-tunnel
     *  connection handlers.
     *
     *  <p>Handlers are dispatched off the accept thread so a single slow
     *  connection cannot stall connection admission. Overflow uses
     *  {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}: when the fixed worker set and its
     *  bounded queue are saturated, the submission throws
     *  {@link java.util.concurrent.RejectedExecutionException} instead of
     *  executing the handler inline on the caller. Inline execution (the former
     *  {@code CallerRunsPolicy}) would run a write-blocked handler on the
     *  {@code I2PTunnelServer.run()} accept loop, pinning {@code accept()}, which
     *  stops draining the streaming SYN queue and makes every fresh connection
     *  attempt expire and be RESET under load. The rejection is surfaced to
     *  {@code I2PTunnelServer.run()}, which closes the connection but keeps
     *  accepting new ones.
     *
     *  <p>Exposed package-visible (static) so tests can assert the overflow
     *  semantics without a live {@link I2PAppContext}.
     *
     *  @param threads the fixed core/max worker count
     *  @param index   an {@link AtomicLong} counter used to name worker threads
     *  @return a never-null, bounded executor with {@code AbortPolicy}
     *  @since 0.9.71+
     */
    static ThreadPoolExecutor createServerExecutor(int threads, AtomicLong index) {
        return new ThreadPoolExecutor(
            threads, threads,
            SERVER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(serverBacklogQueueCapacity),
            r -> {
                Thread t = new Thread(r);
                t.setName("TunnelServer." + index.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     *  Resize the shared server executor pool. Called by Tuner.
     */
    void resizeServerExecutor(int newThreads) {
        synchronized (_serverExecutorLock) {
            if (_serverExecutor != null && !_serverExecutor.isShutdown()) {
                if (newThreads > _serverExecutor.getMaximumPoolSize()) {
                    _serverExecutor.setMaximumPoolSize(newThreads);
                    _serverExecutor.setCorePoolSize(newThreads);
                } else {
                    _serverExecutor.setCorePoolSize(newThreads);
                    _serverExecutor.setMaximumPoolSize(newThreads);
                }
                I2PAppContext ctx = _context;
                if (ctx != null)
                    ctx.statManager().addRateData("i2ptunnel.serverHandler.threads", newThreads);
            }
        }
    }

    /**
     *  Shutdown the server executor
     */
    private void killServerExecutor() {
        killExecutor(_serverExecutorLock, "Server");
    }

    /**
     *  Shutdown the client executor
     */
    private void killClientExecutor() {
        killExecutor(_executorLock, "Client");
    }

    /**
     *  Shutdown an executor, waiting for termination.
     *
     *  @param lock the monitor guarding the executor
     *  @param name "Server" or "Client" for logging
     */
    private void killExecutor(Object lock, String name) {
        synchronized (lock) {
            ThreadPoolExecutor executor = name.equals("Server") ? _serverExecutor : _executor;
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        if (!executor.awaitTermination(60, TimeUnit.SECONDS))
                            _log.error(name + " executor did not terminate");
                    }
                } catch (InterruptedException ie) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                if (name.equals("Server")) {_serverExecutor = null;}
                else {_executor = null;}
            }
        }
    }

    /**
     *  Resize the client executor max pool size. Called by Tuner.
     */
    void resizeClientExecutor(int newMax) {
        synchronized (_executorLock) {
            if (_executor != null && !_executor.isShutdown()) {
                _executor.setMaximumPoolSize(newMax);
                I2PAppContext ctx = _context;
                if (ctx != null)
                    ctx.statManager().addRateData("i2ptunnel.clientRunner.activeThreads", _executor.getActiveCount());
            }
        }
    }

    /** Thread pool executor for I2P tunnel client handlers */
    static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        /** Create with default configuration */
        public CustomThreadPoolExecutor() {
             super(0, clientRunnerMax, HANDLER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                   new SynchronousQueue<>(), new CustomThreadFactory());
        }
    }

    /** Thread factory that sets daemon flag and names threads */
    private static class CustomThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("TunnelClientRunner." + _executorThreadCount.incrementAndGet());
            rv.setDaemon(true);
            return rv;
        }
    }
}
