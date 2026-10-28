package net.i2p.router.update;

import static net.i2p.app.ClientAppState.*;
import static net.i2p.update.UpdateMethod.*;
import static net.i2p.update.UpdateType.*;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Collator;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.app.NotificationService;
import net.i2p.crypto.SU3File;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.Blocklist;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterVersion;
import net.i2p.router.app.RouterApp;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NewsHelper;
import net.i2p.router.web.PluginStarter;
import net.i2p.update.*;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * The central resource coordinating updates.
 * This must be registered with the context.
 * <p>
 * The UpdateManager starts and stops all updates,
 * prevents multiple updates as appropriate,
 * and controls notification to the user.
 * <p>
 * Version notes: For news and unsigned updates, use
 * {@link java.lang.Long#toString}modtime).
 *
 * @since 0.9.4
 */
public class ConsoleUpdateManager implements UpdateManager, RouterApp {

    /** The router context. */
    private final RouterContext _context;
    /** Logger for the console updates. */
    private final Log _log;
    /** The registered updaters that perform the downloads. */
    private final Collection<RegisteredUpdater> _registeredUpdaters;
    /** The registered checkers that look for new updates. */
    private final Collection<RegisteredChecker> _registeredCheckers;
    /** Post-processors, by update type. */
    private final Map<Integer, UpdatePostProcessor> _registeredPostProcessors;
    /** Active checking tasks. */
    private final Collection<UpdateTask> _activeCheckers;
    /** Active updating tasks, pointing to the next ones to try. */
    private final Map<UpdateTask, List<RegisteredUpdater>> _downloaders;
    /** As reported by checkers. */
    private final ConcurrentHashMap<UpdateItem, VersionAvailable> _available;
    /** Downloaded but NOT installed. */
    private final Map<UpdateItem, Version> _downloaded;
    /** Downloaded AND installed. */
    private final Map<UpdateItem, Version> _installed;
    /** True if torrent updates are allowed. */
    private final boolean _allowTorrent;
    /** Formatter for the percentage display. */
    private static final DecimalFormat _pct = new DecimalFormat("0.0%");
    /** The app manager used for restarts. */
    private final ClientAppManager _cmgr;
    /** Current state of the update manager app. */
    private volatile ClientAppState _state = UNINITIALIZED;

    /** Status message shown in the console, null if none. */
    private volatile String _status;
    /**
     * When {@link #_status} was last set, in ms. The sidebar shows a finished
     * update message for a bounded time only, so this is the age it measures
     * from; snapshotting it at set time keeps a later status change from
     * extending or shortening what is already on screen.
     */
    private volatile long _statusTime;
    /** True while an external restart is pending. */
    private volatile boolean _externalRestartPending;

    /** Default maximum time for one update attempt. */
    private static final long DEFAULT_MAX_TIME = 3*60*60*1000L;
    /** Default delay before the first update check. */
    private static final long DEFAULT_CHECK_TIME = (long) 90*1000;
    /** Time an update status message stays shown. */
    private static final long STATUS_CLEAN_TIME = 5 * (long) 60 * 1000; // 5 minutes sidebar notification persistence ?
    /** Interval between stale task cleanups. */
    private static final long TASK_CLEANER_TIME = 15 * (long) 60 * 1000;
    /** Config key enabling unsigned updates. */
    private static final String PROP_UNSIGNED_AVAILABLE = "router.updateUnsignedAvailable";
    /** Config key enabling dev SU3 updates. */
    private static final String PROP_DEV_SU3_AVAILABLE = "router.updateDevSU3Available";

    /** Config key enabling torrent updates. */
    private static final String PROP_ENABLE_TORRENT_UPDATES = "router.enableTorrentUpdates";
    /** Matches the detail of a verification failure message. */
    private static final Pattern FOR_CONTENT_PATTERN = Pattern.compile("for content.*");
    /** Default setting for torrent updates. */
    private static final boolean DEFAULT_ENABLE_TORRENT_UPDATES = false;

    /**
     * Creates the manager; the update handlers for the router, plugins, news
     * and the desktop GUI are registered and checks scheduled in {@link #startup()}.
     * @param ctx router context supplying the update policy, timers and logging
     * @param listener client app manager to register this with and to notify of state changes
     * @param args command line arguments, unused
     */
    public ConsoleUpdateManager(RouterContext ctx, ClientAppManager listener, String[] args) {
        _context = ctx;
        _cmgr = listener;
        _log = ctx.logManager().getLog(ConsoleUpdateManager.class);
        _registeredUpdaters = new ConcurrentHashSet<>();
        _registeredCheckers = new ConcurrentHashSet<>();
        _registeredPostProcessors = new ConcurrentHashMap<>(2);
        _activeCheckers = new ConcurrentHashSet<>();
        _downloaders = new ConcurrentHashMap<>();
        _available = new ConcurrentHashMap<>();
        _downloaded = new ConcurrentHashMap<>();
        _installed = new ConcurrentHashMap<>();
        _status = "";
        _allowTorrent = true;
        _state = INITIALIZED;
    }

    /**
     * Finds the registered console update manager app.
     *
     * @return the running instance, null if the app is not registered
     */
    public static ConsoleUpdateManager getInstance() {
        ClientAppManager cmgr = I2PAppContext.getGlobalContext().clientAppManager();
        if (cmgr == null) {return null;}
        return (ConsoleUpdateManager) cmgr.getRegisteredApp(APP_NAME);
    }

    /**
     * UpdateManager interface.
     */
    @Override
    public void start() {startup();}

    /**
     * ClientApp interface.
     * @since 0.9.12
     */
    @Override
    public synchronized void startup() {
        changeState(STARTING);
        notifyInstalled(NEWS, "", Long.toString(NewsHelper.lastUpdated(_context)));
        notifyInstalled(ROUTER_SIGNED, "", RouterVersion.VERSION);
        notifyInstalled(ROUTER_SIGNED_SU3, "", RouterVersion.VERSION);
        notifyInstalled(ROUTER_DEV_SU3, "", RouterVersion.FULL_VERSION);
        notifyInstalled(API, "", CoreVersion.PUBLISHED_VERSION);
        String blist = _context.getProperty(NewsFetcher.PROP_BLOCKLIST_TIME);
        if (blist != null) {notifyInstalled(BLOCKLIST, Blocklist.ID_FEED, blist);}
        // hack to init from the current news file... do this before we register Updaters
        // This will not kick off any Updaters as none are yet registered
        (new NewsFetcher(_context, this, Collections.<URI> emptyList())).checkForUpdates();
        for (String plugin : PluginStarter.getPlugins()) {
            Properties props = PluginStarter.pluginProperties(_context, plugin);
            String ver = props.getProperty("version");
            if (ver != null) {notifyInstalled(PLUGIN, plugin, ver);}
        }

        DummyHandler dh = new DummyHandler(_context, this);
        register((Checker)dh, TYPE_DUMMY, METHOD_DUMMY, 0);
        register((Updater)dh, TYPE_DUMMY, METHOD_DUMMY, 0);
        VersionAvailable dummyVA = new VersionAvailable("0", "", METHOD_DUMMY, Collections.<URI> emptyList());
        _available.put(new UpdateItem(TYPE_DUMMY, ""), dummyVA);
        // register news before router, so we don't fire off an update
        // right at instantiation if the news is already indicating a new version
        Checker c = new NewsHandler(_context, this);
        register(c, NEWS, HTTP, 0);
        register(c, NEWS_SU3, HTTP, 0);
        register(c, ROUTER_SIGNED, HTTP, 0);  // news is an update checker for the router
        Updater u = new UpdateHandler(_context, this);
        register(u, ROUTER_SIGNED, HTTP, 0);
        if (ConfigUpdateHandler.USE_SU3_UPDATE) {
            register(c, ROUTER_SIGNED_SU3, HTTP, 0);
            register(u, ROUTER_SIGNED_SU3, HTTP, 0);
        }

        UnsignedUpdateHandler uuh = new UnsignedUpdateHandler(_context, this);
        register((Checker)uuh, ROUTER_UNSIGNED, HTTP, 0);
        register((Updater)uuh, ROUTER_UNSIGNED, HTTP, 0);
        String newVersion = _context.getProperty(PROP_UNSIGNED_AVAILABLE);
        if (newVersion != null) {
            List<URI> updateSources = uuh.getUpdateSources();
            if (updateSources != null) {
                VersionAvailable newVA;
                newVA = new VersionAvailable(newVersion, "", HTTP, updateSources);
                _available.put(new UpdateItem(ROUTER_UNSIGNED, ""), newVA);
            }
        }

        DevSU3UpdateHandler dsuh = new DevSU3UpdateHandler(_context, this);
        register((Checker)dsuh, ROUTER_DEV_SU3, HTTP, 0);
        register((Updater)dsuh, ROUTER_DEV_SU3, HTTP, 0);
        newVersion = _context.getProperty(PROP_DEV_SU3_AVAILABLE);
        if (newVersion != null) {
            if (VersionComparator.comp(newVersion, RouterVersion.FULL_VERSION) > 0) {
                List<URI> updateSources = dsuh.getUpdateSources();
                if (updateSources != null) {
                    VersionAvailable newVA;
                    newVA = new VersionAvailable(newVersion, "", HTTP, updateSources);
                    _available.put(new UpdateItem(ROUTER_DEV_SU3, ""), newVA);
                }
            } else {_context.router().saveConfig(PROP_DEV_SU3_AVAILABLE, null);}
        }

        PluginUpdateHandler puh = new PluginUpdateHandler(_context, this);
        register((Checker)puh, PLUGIN, HTTP, 0);
        register((Updater)puh, PLUGIN, HTTP, 0);
        register((Updater)puh, PLUGIN, FILE, 0);
        new NewsTimerTask(_context, this);
        _context.simpleTimer2().addPeriodicEvent(new TaskCleaner(), TASK_CLEANER_TIME);
        // TaskCleaner self-reschedules in timeReached()
        changeState(RUNNING);
        if (_cmgr != null) {_cmgr.register(this);}
    }

    /**
     * UpdateManager interface.
     */
    @Override
    public void shutdown() {shutdown(null);}

    /**
     * ClientApp interface.
     * @param args command line arguments, unused
     * @since 0.9.12
     */
    @Override
    public synchronized void shutdown(String[] args) {
        if (_state == STOPPED) {return;}
        changeState(STOPPING);
        stopChecks();
        stopUpdates();
        _registeredUpdaters.clear();
        _registeredCheckers.clear();
        _available.clear();
        _downloaded.clear();
        _installed.clear();
        changeState(STOPPED);
    }

    /**
     * Current state of the updater.
     * @return the state the updater is in
     * @since 0.9.12
     */
    @Override
    public ClientAppState getState() {return _state;}

    /**
     * Application name.
     * @return the name the console shows for this plugin
     * @since 0.9.12
     */
    @Override
    public String getName() {return APP_NAME;}

    /**
     * Name shown in the console plugin list.
     * @return the human-readable plugin name
     * @since 0.9.12
     */
    @Override
    public String getDisplayName() {return "Console Update Manager";}

    /**
     * Record the app state and notify the client app manager of the change.
     *
     * @param state the new state of this client app
     */
    private synchronized void changeState(ClientAppState state) {
        _state = state;
        if (_cmgr != null) {_cmgr.notify(this, state, null, null);}
    }

    /**
     * The status on any update current or last finished.
     * @return status or ""
     */
    @Override
    public String getStatus() {return _status;}

    /**
     * When the status message was last set.
     *
     * @return the time in ms, 0 when no status has been set since startup
     * @since 0.9.72+
     */
    public long getStatusTime() {return _statusTime;}

    /**
     * Is an update available?
     * Blocking.
     * An available update may still have a constraint or lack sources.
     * @param type the UpdateType of this request
     * @return new version or null if nothing newer is available
     * @since 0.9.21
     */
    @Override
    public String checkAvailable(UpdateType type) {return checkAvailable(type, "", DEFAULT_CHECK_TIME);}

    /**
     * Is an update available?
     * Blocking.
     * An available update may still have a constraint or lack sources.
     * @param type the UpdateType of this request
     * @param maxWait max time to block
     * @return new version or null if nothing newer is available
     */
    @Override
    public String checkAvailable(UpdateType type, long maxWait) {return checkAvailable(type, "", maxWait);}

    /**
     * Is an update available?
     * Blocking.
     * An available update may still have a constraint or lack sources.
     * @param type the UpdateType of this request
     * @param id id of this request
     * @param maxWait max time to block
     * @return new version or null if nothing newer is available
     */
    @Override
    public String checkAvailable(UpdateType type, String id, long maxWait) {
        if (isCheckInProgress(type, id) || isUpdateInProgress(type, id)) {
            if (_log.shouldWarn()) {
                _log.warn("Check or update already in progress for: " + type + ' ' + id);
            }
            return null;
        }
        for (RegisteredChecker r : _registeredCheckers) {
            if (r.type == type) {
                String current = getDownloadedOrInstalledVersion(type, id);
                UpdateTask t;
                synchronized(_activeCheckers) {
                    t = r.checker.check(type, r.method, id, current, maxWait);
                    if (t != null) {
                         if (_log.shouldInfo()) {_log.info("Starting " + r, new Exception());}
                        _activeCheckers.add(t);
                        t.start();
                    }
                }
                if (t != null) {
                    synchronized(t) {
                        try {t.wait(maxWait);}
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
                    }
                    return getUpdateAvailable(type, id);
                }
            }
        }
        return null;
    }

    /**
     * Fire off a checker task
     * Non-blocking.
     * @param type the UpdateType of this request
     */
    public void check(UpdateType type) {check(type, "");}

    /**
     * Fire off a checker task
     * Non-blocking.
     * @param type the UpdateType of this request
     * @param id id of this request
     */
    public void check(UpdateType type, String id) {
        if (isCheckInProgress(type, id)) {
            if (_log.shouldWarn()) {_log.warn("Check already in progress for: " + type + ' ' + id);}
            return;
        }
        for (RegisteredChecker r : _registeredCheckers) {
            if (r.type == type) {
                String current = getDownloadedOrInstalledVersion(type, id);
                synchronized(_activeCheckers) {
                    UpdateTask t = r.checker.check(type, r.method, id, current, DEFAULT_CHECK_TIME);
                    if (t != null) {
                         if (_log.shouldInfo()) {_log.info("Starting " + r, new Exception());}
                        _activeCheckers.add(t);
                        t.start();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Is an update available?
     * Non-blocking, returns result of last check or notification from an Updater.
     * An available update may still have a constraint or lack sources.
     * @param type the UpdateType of this request
     * @return new version or null if nothing newer is available
     */
    public String getUpdateAvailable(UpdateType type) {return getUpdateAvailable(type, "");}

    /**
     * Is an update available?
     * Non-blocking, returns result of last check or notification from an Updater.
     * An available update may still have a constraint or lack sources.
     * @param type the UpdateType of this request
     * @param id id of this request
     * @return new version or null if nothing newer is available
     */
    public String getUpdateAvailable(UpdateType type, String id) {
        Version v = _available.get(new UpdateItem(type, id));
        if (v == null) {return null;}
        return v.versionStr;
    }

    /**
     * Is an update downloaded?
     * Non-blocking, returns result of last download.
     * @param type the UpdateType of this request
     * @return new version or null if nothing was downloaded
     */
    public String getUpdateDownloaded(UpdateType type) {return getUpdateDownloaded(type, "");}

    /**
     * Is an update downloaded?
     * Non-blocking, returns result of last download.
     * @param type the UpdateType of this request
     * @param id id of this request
     * @return new version or null if nothing was downloaded
     */
    public String getUpdateDownloaded(UpdateType type, String id) {
        Version v = _downloaded.get(new UpdateItem(type, id));
        if (v == null) {return null;}
        return v.versionStr;
    }

    /**
     * The highest of the installed or downloaded version.
     *
     * @param type the UpdateType to look up
     * @param id id of the request to look up
     * @return new version or null if nothing was downloaded or installed
     */
    private String getDownloadedOrInstalledVersion(UpdateType type, String id) {
        UpdateItem ui = new UpdateItem(type, id);
        Version vi = _installed.get(ui);
        Version vd = _downloaded.get(ui);
        if (vi != null) {
            if (vd != null) {return (vi.compareTo(vd) > 0) ? vi.versionStr : vd.versionStr;}
            return vi.versionStr;
        }
        return vd != null ? vd.versionStr : null;
    }

    /**
     * Is any download in progress?
     * Does not include checks.
     * @return whether update in progress
     */
    @Override
    public boolean isUpdateInProgress() {return !_downloaders.isEmpty();}

    /**
     * Is a download in progress?
     * @return whether update in progress
     */
    @Override
    public boolean isUpdateInProgress(UpdateType type) {return isUpdateInProgress(type, "");}

    /**
     * Is a download in progress?
     * @return whether update in progress
     */
    @Override
    public boolean isUpdateInProgress(UpdateType type, String id) {
        for (UpdateTask t : _downloaders.keySet()) {
            if (t.getType() == type && id.equals(t.getID())) {return true;}
        }
        return false;
    }

    /**
     * Stop all downloads in progress.
     */
    public void stopUpdates() {
        for (UpdateTask t : _downloaders.keySet()) {t.shutdown();}
        _downloaders.clear();
    }

    /**
     * Stop this download.
     * @param type the UpdateType of this download
     */
    public void stopUpdate(UpdateType type) {stopUpdate(type, "");}

    /**
     * Stop this download.
     * @param type the UpdateType of this download
     * @param id id of this download
     */
    public void stopUpdate(UpdateType type, String id) {
        for (Iterator<UpdateTask> iter = _downloaders.keySet().iterator(); iter.hasNext(); ) {
            UpdateTask t = iter.next();
            if (t.getType() == type && id.equals(t.getID())) {
                iter.remove();
                t.shutdown();
            }
        }
    }

    /**
     * Is any check in progress?
     * Does not include updates.
     * @return whether check in progress
     */
    public boolean isCheckInProgress() {return !_activeCheckers.isEmpty();}

    /**
     * Is a check in progress?
     * @param type the UpdateType of this request
     * @return whether check in progress
     */
    public boolean isCheckInProgress(UpdateType type) {return isCheckInProgress(type, "");}

    /**
     * Is a check in progress?
     * @param type the UpdateType of this request
     * @param id id of this request
     * @return whether check in progress
     */
    public boolean isCheckInProgress(UpdateType type, String id) {
        for (UpdateTask t : _activeCheckers) {
            if (t.getType() == type && id.equals(t.getID())) {return true;}
        }
        return false;
    }

    /**
     * Stop all checks in progress.
     */
    public void stopChecks() {
        synchronized(_activeCheckers) {
            for (UpdateTask t : _activeCheckers) {t.shutdown();}
            _activeCheckers.clear();
        }
    }

    /**
     * Stop this check.
     * @param type the UpdateType of this check
     */
    public void stopCheck(UpdateType type) {stopCheck(type, "");}

    /**
     * Stop this check.
     * @param type the UpdateType of this check
     * @param id id of this check
     */
    public void stopCheck(UpdateType type, String id) {
        for (Iterator<UpdateTask> iter = _activeCheckers.iterator(); iter.hasNext(); ) {
            UpdateTask t = iter.next();
            if (t.getType() == type && id.equals(t.getID())) {
                iter.remove();
                t.shutdown();
            }
        }
    }

    /**
     * A router update had been downloaded and handled by an UpdatePostProcessor.
     * It will provide wrapper-like function to install the update and restart after shutdown.
     *
     * @return whether external restart pending
     * @since 0.9.51
     */
    public boolean isExternalRestartPending() {return _externalRestartPending;}

    /**
     * Install a plugin. Non-blocking.
     * If returns true, then call {@link #isUpdateInProgress()} in a loop.
     * @param name if null, a new install
     * @param uri where to fetch the plugin, a file: URI installs without a network fetch
     * @return true if task started
     */
    public boolean installPlugin(String name, URI uri) {
        // We must have a name and install it in _available or else
        // update_fromCheck() will fail.
        // It's not removed from _available on success, as we lose the name.
        if (name == null) {name = Long.toString(_context.random().nextLong());}
        List<URI> uris = Collections.singletonList(uri);
        UpdateItem item = new UpdateItem(PLUGIN, name);
        VersionAvailable va = _available.get(item);
        if (va == null) {
            UpdateMethod method = "file".equals(uri.getScheme()) ? FILE : HTTP;
            va = new VersionAvailable("", "", method, uris);
            VersionAvailable existingVa = _available.putIfAbsent(item, va);
            if (existingVa != null) {va = existingVa;}
        }
        if (_log.shouldWarn()) {_log.warn("Install plugin: " + name + ' ' + va);}
        return update(PLUGIN, name);
    }

    /**
     * Non-blocking. Does not check.
     * If returns true, then call isUpdateInProgress() in a loop
     * Max time 3 hours by default but not honored by all Updaters.
     * @return true if task started
     */
    @Override
    public boolean update(UpdateType type) {return update(type, "", DEFAULT_MAX_TIME);}

    /**
     * Non-blocking. Does not check.
     * Max time 3 hours by default but not honored by all Updaters
     * If returns true, then call isUpdateInProgress() in a loop.
     * @return true if task started
     */
    @Override
    public boolean update(UpdateType type, String id) {return update(type, id, DEFAULT_MAX_TIME);}

    /**
     * Non-blocking. Does not check.
     * If returns true, then call isUpdateInProgress() in a loop.
     * @param maxTime not honored by all Updaters
     * @return true if task started
     */
    @Override
    public boolean update(UpdateType type, long maxTime) {return update(type, "", maxTime);}

    /**
     * Non-blocking. Does not check.
     * Fails if check or update already in progress.
     * If returns true, then call isUpdateInProgress() in a loop.
     * @param maxTime not honored by all Updaters
     * @return true if task started
     */
    @Override
    public boolean update(UpdateType type, String id, long maxTime) {
        if (isCheckInProgress(type, id)) {
            if (_log.shouldWarn()) {_log.warn("Check already in progress for: " + type + ' ' + id);}
            return false;
        }
        return update_fromCheck(type, id, maxTime);
    }

    /**
     * Non-blocking. Does not check.
     * Fails update already in progress. Use this to call from within a checker task.
     * If returns true, then call isUpdateInProgress() in a loop.
     *
     * @param type the UpdateType to update
     * @param id id of the request to update
     * @param maxTime not honored by all Updaters
     * @return true if task started
     */
    private boolean update_fromCheck(UpdateType type, String id, long maxTime) {
        if (isUpdateInProgress(type, id)) {
            if (_log.shouldWarn()) {_log.warn("Update already in progress for: " + type + ' ' + id);}
            return false;
        }
        UpdateItem ui = new UpdateItem(type, id);
        VersionAvailable va = _available.get(ui);
        if (va == null) {
            if (_log.shouldWarn()) {_log.warn("No version available for: " + type + ' ' + id);}
            return false;
        }
        List<RegisteredUpdater> sorted = new ArrayList<>(4);
        for (RegisteredUpdater ru : _registeredUpdaters) {
            if (ru.type == type) {sorted.add(ru);}
        }
        Collections.sort(sorted);
        return retry(ui, va.sourceMap, sorted, maxTime) != null;
    }

    /**
     * Schedules a retry of a failed update.
     *
     * @param ui the update item to retry
     * @param sourceMap the source URIs, by method
     * @param toTry the updaters still to try
     * @param maxTime the maximum time for the whole update
     * @return the scheduled task, null if none could be scheduled
     */
    private UpdateTask retry(UpdateItem ui, Map<UpdateMethod, List<URI>> sourceMap,
                             List<RegisteredUpdater> toTry, long maxTime) {
        for (Iterator<RegisteredUpdater> iter = toTry.iterator(); iter.hasNext(); ) {
            RegisteredUpdater r = iter.next();
            iter.remove();
            // check in case unregistered later
            if (!_registeredUpdaters.contains(r)) {continue;}
            VersionAvailable va = _available.get(ui);
            String newVer = va != null ? va.versionStr : "";
            for (Map.Entry<UpdateMethod, List<URI>> e : sourceMap.entrySet()) {
                UpdateMethod meth = e.getKey();
                if (r.type == ui.type && r.method == meth) {
                    UpdateTask t = r.updater.update(ui.type, meth, e.getValue(),
                                                    ui.id, newVer, maxTime);
                    if (t != null) {
                        // race window here
                        //  store the remaining ones for retrying
                        if (_log.shouldInfo()) {_log.info("Starting " + r, new Exception());}
                        _downloaders.put(t, toTry);
                        t.start();
                        return t;
                    } else {
                        if (_log.shouldWarn()) {
                            _log.warn("Updater refused: " + r + " for " + meth + ' ' + e.getValue());
                        }
                    }
                }
            }
            if (_log.shouldWarn()) {_log.warn("Nothing left to try for: " + r);}
        }
        if (_log.shouldWarn()) {_log.warn("Nothing left to try for: " + ui);}
        return null;
    }

    /**
     * Add the Updater to the set consulted for each update type and method.
     */
    @Override
    public void register(Updater updater, UpdateType type, UpdateMethod method, int priority) {
        if ((type == ROUTER_SIGNED || type == ROUTER_UNSIGNED ||
             type == ROUTER_SIGNED_SU3 || type == ROUTER_DEV_SU3) &&
            NewsHelper.dontInstall(_context)) {
            if (_log.shouldWarn()) {
                _log.warn("Ignoring registration for " + type + ", router updates disabled");
            }
            return;
        }
        if (type == ROUTER_SIGNED_SU3 && !ConfigUpdateHandler.USE_SU3_UPDATE) {
            if (_log.shouldWarn()) {
                _log.warn("Ignoring registration for " + type + ", SU3 updates disabled");
            }
            return;
        }
        boolean enableTorrentUpdates = _context.getProperty(PROP_ENABLE_TORRENT_UPDATES, DEFAULT_ENABLE_TORRENT_UPDATES);
        if (method == TORRENT && (!_allowTorrent || !enableTorrentUpdates)) {
            if (_log.shouldWarn()) {_log.warn("Ignoring torrent registration");}
            return;
        }
        RegisteredUpdater ru = new RegisteredUpdater(updater, type, method, priority);
        if (_log.shouldInfo()) {_log.info("Registering " + ru);}
        if (!_registeredUpdaters.add(ru) && _log.shouldWarn()) {_log.warn("Duplicate registration " + ru);}
    }

    /**
     * Remove the Updater previously registered for the type and method.
     */
    @Override
    public void unregister(Updater updater, UpdateType type, UpdateMethod method) {
        RegisteredUpdater ru = new RegisteredUpdater(updater, type, method, 0);
        if (_log.shouldInfo()) {_log.info("Unregistering " + ru);}
        _registeredUpdaters.remove(ru);
    }

    /**
     * Add the Checker to the set consulted for each update type and method.
     */
    @Override
    public void register(Checker updater, UpdateType type, UpdateMethod method, int priority) {
        RegisteredChecker rc = new RegisteredChecker(updater, type, method, priority);
        if (_log.shouldInfo()) {_log.info("Registering " + rc);}
        if (!_registeredCheckers.add(rc) && _log.shouldWarn()) {_log.warn("Duplicate registration " + rc);}
    }

    /**
     * Remove the Checker previously registered for the type and method.
     */
    @Override
    public void unregister(Checker updater, UpdateType type, UpdateMethod method) {
        RegisteredChecker rc = new RegisteredChecker(updater, type, method, 0);
        if (_log.shouldInfo()) {_log.info("Unregistering " + rc);}
        _registeredCheckers.remove(rc);
    }

    /**
     * Register a post-processor for this UpdateType and SU3File file type.
     *
     * @param type only ROUTER_SIGNED_SU3 and ROUTER_DEV_SU3 are currently supported
     * @param fileType a SU3File TYPE_xxx constant, 1-255, TYPE_ZIP not supported.
     * @since 0.9.51
     */
    @Override
    public void register(UpdatePostProcessor upp, UpdateType type, int fileType) {
        Integer key = Integer.valueOf(type.toString().hashCode() ^ fileType);
        UpdatePostProcessor old = _registeredPostProcessors.put(key, upp);
        if (old != null && _log.shouldWarn()) {_log.warn("Duplicate registration " + upp);}
    }

    /**
     * Called by the Updater, either after {@link #check} was called, or it found out on its own.
     * Use this if there is only one UpdateMethod; otherwise use the Map method below.
     *
     * @param newsSource who told us
     * @param id plugin name for plugins, ignored otherwise
     * @param updateSources Where to get the new version
     * @param newVersion The new version available
     * @param minVersion The minimum installed version to be able to update to newVersion
     * @return true if it's newer
     */
    @Override
    public boolean notifyVersionAvailable(UpdateTask task, URI newsSource,
                                           UpdateType type, String id,
                                           UpdateMethod method, List<URI> updateSources,
                                           String newVersion, String minVersion) {
        return notifyVersionAvailable(task, newsSource, type, id,
                                      Collections.singletonMap(method, updateSources),
                                      newVersion, minVersion);
    }

    /**
     * Called by the Checker, either after check() was called, or it found out on its own.
     * Checkers must use this method if there are multiple UpdateMethods discovered simultaneously.
     *
     * @param newsSource who told us
     * @param id plugin name for plugins, ignored otherwise
     * @param sourceMap Mapping of methods to sources
     * @param newVersion The new version available
     * @param minVersion The minimum installed version to be able to update to newVersion
     * @return true if we didn't know already
     * @since 0.9.6
     */
    @Override
    public boolean notifyVersionAvailable(UpdateTask task, URI newsSource,
                                           UpdateType type, String id,
                                           Map<UpdateMethod, List<URI>> sourceMap,
                                           String newVersion, String minVersion) {
        if (type == NEWS || type == NEWS_SU3 || type == BLOCKLIST) {
            // shortcut
            notifyInstalled(type, "", newVersion);
            return true;
        }
        UpdateItem ui = new UpdateItem(type, id);
        boolean shouldUpdate = false;
        for (Map.Entry<UpdateMethod, List<URI>> e : sourceMap.entrySet()) {
            UpdateMethod method = e.getKey();
            List<URI> updateSources = e.getValue();

            VersionAvailable newVA = new VersionAvailable(newVersion, minVersion, method, updateSources);
            Version old = _installed.get(ui);
            if (_log.shouldInfo()) {
                _log.info("notifyVersionAvailable " + ui + ' ' + newVA + " old: " + old);
            }
            if (old != null && old.compareTo(newVA) >= 0) {
                if (_log.shouldWarn()) {_log.warn(ui.toString() + ' ' + old + " already installed");}
                return false; // don't bother updating sources
            }
            old = _downloaded.get(ui);
            if (old != null && old.compareTo(newVA) >= 0) {
                if (_log.shouldWarn())
                    _log.warn(ui.toString() + ' ' + old + " already downloaded");
                return false; // don't bother updating sources
            }
            VersionAvailable oldVA = _available.get(ui);
            if (oldVA != null)  {
                int comp = oldVA.compareTo(newVA);
                if (comp > 0) {
                    if (_log.shouldWarn()) {
                        _log.warn(ui.toString() + ' ' + oldVA + " already available");
                    }
                    continue;
                } else if (comp == 0) {
                    List<URI> oldSources = oldVA.sourceMap.putIfAbsent(method, updateSources);
                    if (oldSources == null) {
                        // merge with existing VersionAvailable
                        // new method
                        if (_log.shouldWarn()) {
                            _log.warn(ui.toString() + ' ' + oldVA + " updated with new source method");
                        }
                    } else if (!oldSources.containsAll(updateSources)) {
                        // merge with existing VersionAvailable
                        // new sources to existing method
                        for (URI uri : updateSources) {
                            if (!oldSources.contains(uri)) {
                                if (_log.shouldWarn()) {
                                    _log.warn(ui.toString() + ' ' + oldVA + " adding " + uri + " to method " + method);
                                }
                                try {oldSources.add(uri);}
                                catch (UnsupportedOperationException uoe) {
                                    // rare case, changed URL but it's a singleton list, replace old list
                                    oldVA.sourceMap.put(method, Collections.singletonList(uri));
                                }
                            }
                        }
                    } else {
                        if (_log.shouldWarn()) {_log.warn(ui.toString() + ' ' + oldVA + " already available");}
                    }
                    continue;
                }  // else new version is newer
            }

            // Use the new VersionAvailable
            if (_log.shouldInfo()) {_log.info(ui.toString() + ' ' + newVA + " now available");}
            _available.put(ui, newVA);
            shouldUpdate = true;
        }

        // save across restarts
        if (type == ROUTER_UNSIGNED) {
            _context.router().saveConfig(PROP_UNSIGNED_AVAILABLE, newVersion);
        } else if (type == ROUTER_DEV_SU3) {
            _context.router().saveConfig(PROP_DEV_SU3_AVAILABLE, newVersion);
        }

        if (!shouldUpdate) {return false;}

        if (type == ROUTER_SIGNED_SU3 && _cmgr != null) {
            NotificationService ns = (NotificationService) _cmgr.getRegisteredApp("desktopgui");
            if (ns != null) {
                ns.notify("Router", null, Log.INFO, _t("Router"),
                          _t("Update available") + ": " + _t("Version {0}", newVersion),
                          null);
            }
        }

        String msg = null;
        switch (type) {
            case NEWS:
            case NEWS_SU3:
                break;

            case ROUTER_UNSIGNED:
            case ROUTER_DEV_SU3:
            case ROUTER_SIGNED:
            case ROUTER_SIGNED_SU3:
                if (shouldInstall() &&
                    !(isUpdateInProgress(ROUTER_SIGNED) ||
                      isUpdateInProgress(ROUTER_SIGNED_SU3) ||
                      isUpdateInProgress(ROUTER_DEV_SU3) ||
                      isUpdateInProgress(ROUTER_UNSIGNED))) {
                    if (_log.shouldInfo()) {_log.info("Updating " + ui + " after notify");}
                    update_fromCheck(type, id, DEFAULT_MAX_TIME);
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Not updating " + ui + ", update disabled or in progress");
                    }
                }
                // ConfigUpdateHandler, SidebarHelper, SidebarRenderer handle status display
                break;

            case PLUGIN:
                msg = "<b class=volatile>" + _t("New plugin version {0} is available", newVersion) + "</b>";
                break;

            default:
                break;
        }
        if (msg != null) {finishStatus(msg);}
        return true;
    }

    /**
     * A new version is available but cannot be downloaded or installed due to some constraint.
     * The manager should notify the user.
     * Called by the Checker, either after check() was called, or it found out on its own.
     *
     * @param newsSource who told us
     * @param id plugin name for plugins, ignored otherwise
     * @param newVersion The new version available
     * @param message A translated message to be displayed to the user, non-null
     * @since 0.9.9
     */
    @Override
    public void notifyVersionConstraint(UpdateTask task, URI newsSource,
                                        UpdateType type, String id,
                                        String newVersion, String message) {
        UpdateItem ui = new UpdateItem(type, id);
        Version old = _installed.get(ui);
        VersionAvailable newVA = new VersionAvailable(newVersion, message);
        if (_log.shouldInfo()) {_log.info("notifyVersionConstraint " + ui + ' ' + newVA + " old: " + old);}
        if (old != null && old.compareTo(newVA) >= 0) {
            if (_log.shouldWarn()) {_log.warn(ui.toString() + ' ' + old + " already installed");}
            return;
        }
        old = _downloaded.get(ui);
        if (old != null && old.compareTo(newVA) >= 0) {
            if (_log.shouldWarn()) {_log.warn(ui.toString() + ' ' + old + " already downloaded");}
            return;
        }
        VersionAvailable oldVA = _available.get(ui);
        if (oldVA != null)  {
            if (_log.shouldWarn()) {_log.warn(ui.toString() + ' ' + oldVA + " already available");}
            if (oldVA.compareTo(newVA) >= 0) {return;}
            // don't replace an unconstrained version with a constrained one
            if (oldVA.constraint == null) {return;}
            // replace constrained one
        }
        // Use the new VersionAvailable
        if (_log.shouldInfo()) {_log.info(ui.toString() + ' ' + newVA + " now available");}
        _available.put(ui, newVA);
    }

    /**
     * Called by the Updater after check() was called and all {@link #notifyVersionAvailable} callbacks are finished.
     */
    @Override
    public void notifyCheckComplete(UpdateTask task, boolean newer, boolean success) {
        if (_log.shouldInfo()) {_log.info("Checker " + task + " for " + task.getType() + " complete");}
        synchronized(_activeCheckers) {_activeCheckers.remove(task);}
        String msg = null;
        switch (task.getType()) {
            case NEWS:
            case NEWS_SU3:
            case ROUTER_SIGNED:
            case ROUTER_SIGNED_SU3:
            case ROUTER_UNSIGNED:
            case ROUTER_DEV_SU3:
                // ConfigUpdateHandler, SidebarHelper, SidebarRenderer handle status display
                break;

            case PLUGIN:
                if (!success) {
                    msg = _t("Update check failed for plugin {0}", task.getID());
                    _log.logAlways(Log.WARN, msg);
                    msg = "<b>" + msg + "</b>";
                } else if (!newer) {
                    msg = "<b class=volatile>" + _t("No new version is available for plugin {0}", task.getID()) + "</b>";
                }
                /// else success.... message for that?

                break;

            default:
                break;
        }
        if (msg != null) {finishStatus(msg);}
        else {
            if (success && _t("HTTP client proxy tunnel must be running").equals(_status)) {
                _status = "";
            }
        }
        synchronized(task) {task.notifyAll();}
    }

    /**
     * Publish the download status text and progress bar to the console.
     */
    @Override
    public void notifyProgress(UpdateTask task, String status, long downloaded, long totalSize) {
        StringBuilder buf = new StringBuilder(64);
        buf.append("<div id=sb_updateprogress class=\"sb_updatestatus volatile\">").append(status).append("</div>");
        double pct = ((double)downloaded) / ((double)totalSize);
        synchronized (_pct) {
            buf.append("<div id=sb_updatebar class=\"percentBarOuter volatile\"><div class=\"percentBarText volatile\">")
               .append(DataHelper.formatSize2(downloaded)).append("B / ").append(DataHelper.formatSize2(totalSize))
               .append("B</div><div class=\"percentBarInner volatile\" style=\"width: ").append(_pct.format(pct)).append("\"></div></div>");
        }
        updateStatus(buf.toString());
    }

    /**
     * May be null.
     * @param task may be null
     */
    @Override
    public void notifyProgress(UpdateTask task, String status) {updateStatus(status);}

    /**
     * An expiring status.
     * @param task may be null
     * @param status the message to show, cleared when it expires
     */
    public void notifyComplete(UpdateTask task, String status) {finishStatus(status);}

    /**
     * Not necessarily the end if there are more URIs to try.
     * @param task checker or updater
     * @param t may be null
     */
    @Override
    public void notifyAttemptFailed(UpdateTask task, String reason, Throwable t) {
        if (_log.shouldWarn()) {
            _log.warn("[" + task.getType() + "] Update or check failed " + reason, t);
        }
    }

    /**
     * The task has finished and failed.
     * @param task checker or updater
     * @param t may be null
     */
    @Override
    public void notifyTaskFailed(UpdateTask task, String reason, Throwable t) {
        int level = task.getType() == TYPE_DUMMY ? Log.WARN : Log.ERROR;
        if (_log.shouldLog(level)) {_log.log(level, "[" + task.getType() + "] Update or check failed " + reason, t);}
        List<RegisteredUpdater> toTry = _downloaders.get(task);
        if (toTry != null) {
            UpdateItem ui = new UpdateItem(task.getType(), task.getID());
            VersionAvailable va = _available.get(ui);
            if (va != null) {
                UpdateTask next = retry(ui, va.sourceMap, toTry, DEFAULT_MAX_TIME);  // fixme old maxtime lost
                if (next != null && _log.shouldWarn()) {_log.warn("Retrying with " + next + "...");}
            }
        }
        _downloaders.remove(task);
        _activeCheckers.remove(task);
        // any other types that shouldn't display?
        if (task.getURI() != null && task.getType() != TYPE_DUMMY) {
            StringBuilder buf = new StringBuilder(256);
            buf.append("<b id=updatefailed class=volatile>");
            String uri = task.getURI().toString();
            if (uri.startsWith("file:") || task.getMethod() == FILE) {
                uri = DataHelper.stripHTML(task.getURI().getPath());
                buf.append(_t("Install failed:{0}", "<br>")).append(uri.replace("http://", ""));
            } else {buf.append(_t("Transfer failed:{0}", "<br>")).append(uri.replace("http://", ""));}
            if (reason != null && !reason.isEmpty()) {
                String trimmed = reason.replace("http://", "").replace("java.io.IOException", _t("Error")).replaceAll(FOR_CONTENT_PATTERN.pattern(), "");
                buf.append("<br>").append(trimmed);
            }
            if (t != null && t.getMessage() != null && !t.getMessage().isEmpty()) {
                buf.append("<br>").append(DataHelper.stripHTML(t.getMessage()));
            }
            buf.append("</b>");
            finishStatus(buf.toString());
        }
    }

    /**
     * An update has been downloaded but not verified.
     * The manager will verify it.
     * Caller should delete the file upon return, unless it will share it with others,
     * e.g. on a torrent.
     * If the return value is false, caller must call {@link #notifyTaskFailed} or notifyComplete()
     * again.
     *
     * @param task must be an Updater, not a Checker
     * @param actualVersion may be higher (or lower?) than the version requested
     * @param file a valid format for the task's UpdateType, or null if it did the installation itself
     * @return true if valid, false if corrupt
     */
    @Override
    public boolean notifyComplete(UpdateTask task, String actualVersion, File file) {
        if (_log.shouldInfo()) {_log.info("Updater " + task + " for " + task.getType() + " complete");}
        boolean rv = false;
        UpdateType utype = task.getType();
        switch (utype) {
            case TYPE_DUMMY:
            case NEWS:
            case NEWS_SU3:
                rv = true;
                break;

            case ROUTER_SIGNED:
                rv = handleRouterFile(task.getURI(), actualVersion, file, utype);
                if (rv) {notifyDownloaded(task.getType(), task.getID(), actualVersion);}
                break;

            case ROUTER_SIGNED_SU3:
                rv = handleRouterFile(task.getURI(), actualVersion, file, utype);
                if (rv) {notifyDownloaded(task.getType(), task.getID(), actualVersion);}
                break;

            case ROUTER_UNSIGNED:
                rv = handleUnsignedFile(task.getURI(), actualVersion, file);
                if (rv) {
                    _context.router().saveConfig(PROP_UNSIGNED_AVAILABLE, null);
                    notifyDownloaded(task.getType(), task.getID(), actualVersion);
                }
                break;

            case ROUTER_DEV_SU3:
                rv = handleRouterFile(task.getURI(), actualVersion, file, utype);
                if (rv) {
                    _context.router().saveConfig(PROP_DEV_SU3_AVAILABLE, null);
                    notifyDownloaded(task.getType(), task.getID(), actualVersion);
                }
                break;

            case PLUGIN:     // file handled in PluginUpdateRunner
            default:         // assume Updater installed it
                rv = true;
                notifyInstalled(task.getType(), task.getID(), actualVersion);
                break;
        }
        if (rv) {_downloaders.remove(task);}
        return rv;
    }

    /**
     * Adds to installed, removes from downloaded and available.
     * @param id subtype for plugins, or ""
     * @param version null to remove from installed
     * @since 0.9.45
     */
    @Override
    public void notifyInstalled(UpdateType type, String id, String version) {
        UpdateItem ui = new UpdateItem(type, id);
        if (version == null) {
            _installed.remove(ui);
            if (_log.shouldInfo()) {_log.info(ui + " removed");}
            return;
        }
        Version ver = new Version(version);
        if (_log.shouldInfo()) {_log.info(ui + " " + ver + " installed");}
        _installed.put(ui, ver);
        Version old = _downloaded.get(ui);
        if (old != null && old.compareTo(ver) <= 0) {_downloaded.remove(ui);}
        old = _available.get(ui);
        if (old != null && old.compareTo(ver) <= 0) {_available.remove(ui);}
    }

    /**
     * Adds to downloaded, removes from available.
     *
     * @param type the UpdateType that was downloaded
     * @param id id of the downloaded item
     * @param version the version string that was downloaded
     */
    private void notifyDownloaded(UpdateType type, String id, String version) {
        UpdateItem ui = new UpdateItem(type, id);
        Version ver = new Version(version);
        if (_log.shouldInfo()) {_log.info(ui + " " + ver + " downloaded");}
        _downloaded.put(ui, ver);
        // one trumps the other
        if (type == ROUTER_SIGNED) {
            _downloaded.remove(new UpdateItem(ROUTER_UNSIGNED, ""));
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED_SU3, ""));
            _downloaded.remove(new UpdateItem(ROUTER_DEV_SU3, ""));
            // remove available from other type
            UpdateItem altui = new UpdateItem(ROUTER_SIGNED_SU3, id);
            Version old = _available.get(altui);
            if (old != null && old.compareTo(ver) <= 0) {_available.remove(altui);}
            // ... and declare the alt downloaded as well
            _downloaded.put(altui, ver);
        } else if (type == ROUTER_SIGNED_SU3) {
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED, ""));
            _downloaded.remove(new UpdateItem(ROUTER_UNSIGNED, ""));
            _downloaded.remove(new UpdateItem(ROUTER_DEV_SU3, ""));
            // remove available from other type
            UpdateItem altui = new UpdateItem(ROUTER_SIGNED, id);
            Version old = _available.get(altui);
            if (old != null && old.compareTo(ver) <= 0) {_available.remove(altui);}
            // ... and declare the alt downloaded as well
            _downloaded.put(altui, ver);
        } else if (type == ROUTER_UNSIGNED) {
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED, ""));
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED_SU3, ""));
            _downloaded.remove(new UpdateItem(ROUTER_DEV_SU3, ""));
        } else if (type == ROUTER_DEV_SU3) {
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED, ""));
            _downloaded.remove(new UpdateItem(ROUTER_SIGNED_SU3, ""));
            _downloaded.remove(new UpdateItem(ROUTER_UNSIGNED, ""));
        }
        Version old = _available.get(ui);
        if (old != null && old.compareTo(ver) <= 0) {_available.remove(ui);}
    }

    /**
     * From NewsFetcher.
     * @return false if the update policy is notify-only or news has suppressed
     * installs, or a downloaded router update zip is still pending
     */
    boolean shouldInstall() {
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
        if ("notify".equals(policy) || NewsHelper.dontInstall(_context)) {return false;}
        File zip = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        return !zip.exists();
    }

    /**
     * Where to find various resources.
     * @param type the UpdateType of this request
     * @param id id of this request
     * @param method the transport to list sources for
     * @return non-null may be empty
     */
    public List<URI> getUpdateURLs(UpdateType type, String id, UpdateMethod method) {
        VersionAvailable va = _available.get(new UpdateItem(type, id));
        if (va != null) {
            List<URI> rv = va.sourceMap.get(method);
            if (rv != null) {return rv;}
        }

        switch (type) {
            case NEWS:
            case NEWS_SU3:
                // handled in NewsHandler
                break;

            case ROUTER_SIGNED:
              { // avoid dup variables in next case
                String urls = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL, ConfigUpdateHandler.DEFAULT_UPDATE_URL);
                StringTokenizer tok = new StringTokenizer(urls, " ,\r\n");
                List<URI> rv = new ArrayList<>();
                while (tok.hasMoreTokens()) {
                    try {rv.add(new URI(tok.nextToken().trim()));}
                    catch (URISyntaxException use) { /* ignored */ }
                }
                Collections.shuffle(rv, _context.random());
                return rv;
              }

            case ROUTER_SIGNED_SU3:
                // handled in NewsFetcher
                break;

            case ROUTER_UNSIGNED:
                String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
                if (url != null) {
                    try {return Collections.singletonList(new URI(url));}
                    catch (URISyntaxException use) { /* ignored */ }
                }
                break;

            case ROUTER_DEV_SU3:
                String url3 = _context.getProperty(ConfigUpdateHandler.PROP_DEV_SU3_URL);
                if (url3 != null) {
                    try {return Collections.singletonList(new URI(url3));}
                    catch (URISyntaxException use) { /* ignored */ }
                }
                break;

            case PLUGIN:
                Properties props = PluginStarter.pluginProperties(_context, id);
                String xpi2pURL = props.getProperty("updateURL");
                if (xpi2pURL != null) {
                    try {return Collections.singletonList(new URI(xpi2pURL));}
                    catch (URISyntaxException use) { /* ignored */ }
                }
                break;

             default:
                break;
        }
        return Collections.emptyList();
    }

    /**
     * Is there a reason we can't download the update?
     * @param type the UpdateType of this request
     * @param id id of this request
     * @return translated constraint or null
     * @since 0.9.9
     */
    public String getUpdateConstraint(UpdateType type, String id) {
        VersionAvailable va = _available.get(new UpdateItem(type, id));
        if (va != null) {return va.constraint;}
        return null;
    }

    /**
     * Process sud, su2, or su3.
     * Only for router updates.
     *
     * @param uri the download source, recorded in the update status
     * @param actualVersion the version string reported by the download
     * @param f the downloaded file to verify and stage
     * @param updateType router update type selecting the verification path
     * @return true if the file verified and was staged for install at next
     *         restart, false if verification or the copy failed
     * @since 0.9.9
     */
    private boolean handleRouterFile(URI uri, String actualVersion, File f, UpdateType updateType) {
        boolean isSU3 = updateType == ROUTER_SIGNED_SU3 || updateType == ROUTER_DEV_SU3;
        String url = uri.toString();
        updateStatus("<b class=volatile>" + _t("Update downloaded") + "</b>");
        File to = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        String err = null;
        // Process the file
        if (isSU3) {
            SU3File up = new SU3File(_context, f);
            File temp = new File(_context.getTempDir(), "su3out-" + _context.random().nextLong());
            try {
                if (up.verifyAndMigrate(temp)) {
                    String ver = up.getVersionString();
                    int type = up.getContentType();
                    if (ver == null || VersionComparator.comp(RouterVersion.VERSION, ver) >= 0) {
                        err = "Old version " + ver;
                    } else if (type != SU3File.CONTENT_ROUTER) {
                        err = "Bad su3 content type " + type;
                    } else {
                        int ftype = up.getFileType();
                        if (ftype == SU3File.TYPE_ZIP) {
                            // standard update, copy to i2pupdate.zip in config dir
                            if (!FileUtil.copy(temp, to, true, false))
                        err = "Failed copy to " + to;
                        } else if ((ftype == SU3File.TYPE_DMG && SystemVersion.isMac()) ||
                                   (ftype == SU3File.TYPE_EXE && SystemVersion.isWindows())) {
                            Integer key = Integer.valueOf(updateType.toString().hashCode() ^ ftype);
                            UpdatePostProcessor upp = _registeredPostProcessors.get(key);
                            if (upp != null) {
                                upp.updateDownloadedAndVerified(updateType, ftype, actualVersion, temp);
                                _externalRestartPending = true;
                            } else {err = "Unsupported su3 file type " + ftype + " " + key;}
                        } else {err = "Unsupported su3 file type " + ftype;}
                    }
                } else {
                    err = "Signature failed, signer " + DataHelper.stripHTML(up.getSignerString()) +
                          ' ' + up.getSigType();
                }
            } catch (IOException ioe) {
                _log.error("SU3 extract error", ioe);
                err = DataHelper.stripHTML(ioe.toString());
            } finally {temp.delete();}
        } else {
            TrustedUpdate up = new TrustedUpdate(_context);
            err = up.migrateVerified(RouterVersion.VERSION, f, to);
        }

        // caller must delete.. could be an active torrent
        //f.delete();
        if (err == null) {
            String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
            // So unsigned update handler doesn't overwrite unless newer.
            long modtime = _context.clock().now();
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME, Long.toString(modtime));

            if ("install".equals(policy)) {
                _log.log(Log.CRIT, "Update was downloaded and verified, restarting to install it...");
                updateStatus("<b class=volatile>" + _t("Update verified") + "</b><br>" + _t("Restarting") + "...");
                restart();
            } else {
                _log.logAlways(Log.CRIT, "I2P Update was downloaded and verified, will be installed at next restart");
                // SidebarHelper will display restart info separately
                updateStatus("");
            }
        } else {
            _log.log(Log.CRIT, err + " from " + url);
            updateStatus("<b class=volatile>" + err + ' ' + _t("from {0}", linkify(url)) + " </b>");
        }
        return err == null;
    }

    /**
     * Only for router updates.
     *
     * @param uri download URI, used only for the status text and to tell an
     * I2P+ (skank) build from a stock one
     * @param lastmod server-supplied last-modified timestamp in ms, as a
     * string; the current time is used when null or unparseable
     * @param updFile the downloaded file, verified as a zip and then copied
     * into the router directory
     * @return true if the verified file was copied for install at next restart
     */
    private boolean handleUnsignedFile(URI uri, String lastmod, File updFile) {
        String url = uri.toString();
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY);
        if (FileUtil.verifyZip(updFile) && (!isExternalRestartPending() || !("install".equals(policy)))) {
            if (url.contains("skank")) {updateStatus("<b class=volatile>" + _t("Update downloaded").replace("Update", "I2P+ Update") + "</b>");}
            else {updateStatus("<b class=volatile>" + _t("Update downloaded") + "</b>");}
        } else {
            updFile.delete();
            if (url.contains("skank")) {
                updateStatus("<b class=volatile>" + _t("Unsigned update file from {0} is corrupt", url).replace("Unsigned update file", "I2P+ Update") + "</b>");
            } else {
                updateStatus("<b class=volatile>" + _t("Unsigned update file from {0} is corrupt", url) + "</b>");
            }
            _log.log(Log.CRIT, "Corrupt unsigned update from: " + url);
            return false;
        }
        File to = new File(_context.getRouterDir(), Router.UPDATE_FILE);
        boolean copied = FileUtil.copy(updFile, to, true, false);
        if (copied) {
            updFile.delete();
            long modtime = 0;
            if (lastmod != null) {
                try {modtime = Long.parseLong(lastmod);}
                catch (NumberFormatException nfe) { /* ignored */ }
            }
            if (modtime <= 0) {modtime = _context.clock().now();}
            _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME, Long.toString(modtime));
            if ("install".equals(policy)) {
                if (url.contains("skank")) {_log.log(Log.CRIT, "I2P+ update downloaded, restarting to install it...");}
                else {_log.log(Log.CRIT, "Update was downloaded, restarting to install it...");}
                restart();
            } else {
                if (url.contains("skank")) {_log.logAlways(Log.CRIT, "I2P+ update downloaded - will be installed at next restart");}
                else {_log.logAlways(Log.CRIT, "Update was downloaded - will be installed at next restart");}
                updateStatus(""); // SidebarHelper will display restart info separately
            }
        } else {
            _log.log(Log.CRIT, "Failed copy to " + to);
            updateStatus("<b class=volatile>" + _t("Failed copy to {0}", to.getAbsolutePath()) + "</b>");
        }
        return copied;
    }

    /** Restarts the router console. */
    private void restart() {
        if (_context.hasWrapper()) {
            ConfigServiceHandler.registerWrapperNotifier(_context, Router.EXIT_GRACEFUL_RESTART, false);
        }
        _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
    }

    /**
     * Convert a URL to an HTML clickable link.
     * @param url the URL, truncated in the label if longer than 28 characters
     * @return an anchor tag opening in a new window
     */
    static String linkify(String url) {
        String durl = url;
        if (durl.startsWith("http://")) {durl = durl.substring(7);}
        else if (durl.startsWith("https://")) {durl = durl.substring(8);}
        if (durl.length() > 28) {durl = durl.substring(0, 25) + "&hellip;";}
        return "<a target=_blank href=\"" + url + "\"/>" + durl + "</a>";
    }

    /**
     * Translate a string.
     * @param s the message key to look up
     * @return the translated text, or the key itself if there is no translation
     */
    public String _t(String s) {return Messages.getString(s, _context);}

    /**
     * Translate a string with a parameter.
     * @param s the message key to look up
     * @param o substituted for {0} in the message
     * @return the translated text, or the key itself if there is no translation
     */
    public String _t(String s, Object o) {return Messages.getString(s, o, _context);}

    /**
     * Translate a string with parameters.
     * @param s the message key to look up
     * @param o substituted for {0} in the message
     * @param o2 substituted for {1} in the message
     * @return the translated text, or the key itself if there is no translation
     * @since 0.9.9
     */
    public String _t(String s, Object o, Object o2) {return Messages.getString(s, o, o2, _context);}

    /**
     * Sets the status message shown in the console, and stamps the time so the
     * sidebar can bound how long a finished message stays on screen.
     *
     * @param s the message to show
     */
    private void updateStatus(String s) {
        _status = s;
        _statusTime = System.currentTimeMillis();
    }

    /**
     * Shows the final status message and schedules its cleanup.
     *
     * @param msg the message to show
     */
    private void finishStatus(String msg) {
        updateStatus(msg);
        new StatusCleaner(msg).schedule(STATUS_CLEAN_TIME);
    }

    /** Timer clearing an old update status message. */
    private class StatusCleaner extends SimpleTimer2.TimedEvent {
        /** The status message this cleaner may clear. */
        private final String _msg;
        /**
         * Clears the status message once it has been displayed long enough.
         *
         * @param msg the status message to clear when the timer fires
         */
        public StatusCleaner(String msg) {_msg = msg;}
        /**
         * Clears the message if no newer update has replaced it.
         */
        public void timeReached() {
            if (_msg.equals(getStatus())) {updateStatus("");}
        }
    }

    /**
     * Periodically drops checkers and downloaders whose tasks stopped running.
     */
    private class TaskCleaner extends SimpleTimer2.TimedEvent {
        /** Discards finished update tasks. */
        private TaskCleaner() {}

        /**
         * Reschedules itself, then evicts the checkers and downloaders
         * whose task is no longer running.
         */
        public void timeReached() {
            schedule(TASK_CLEANER_TIME);
            if (!_activeCheckers.isEmpty()) {
                synchronized(_activeCheckers) {
                    for (Iterator<UpdateTask> iter = _activeCheckers.iterator(); iter.hasNext(); ) {
                        UpdateTask t = iter.next();
                        if (!t.isRunning()) {
                            if (_log.shouldWarn()) {_log.warn("Failsafe remove checker " + t);}
                            iter.remove();
                        }
                    }
                }
            }
            if (!_downloaders.isEmpty()) {
                for (Iterator<UpdateTask> iter = _downloaders.keySet().iterator(); iter.hasNext(); ) {
                    UpdateTask t = iter.next();
                    if (!t.isRunning()) {
                        if (_log.shouldWarn()) {_log.warn("Failsafe remove downloader " + t);}
                        iter.remove();
                    }
                }
            }
        }
    }

    /**
     * Equals on updater, type and method only.
     */
    static class RegisteredUpdater implements Comparable<RegisteredUpdater> {
        /**
         * The Updater this registration invokes.
         */
        public final Updater updater;
        /**
         * The update type this registration serves.
         */
        public final UpdateType type;
        /**
         * The update method this registration was made for.
         */
        public final UpdateMethod method;
        /**
         * Higher priorities are consulted first.
         */
        public final int priority;

        /**
         * Record an Updater for the given type, method and priority.
         *
         * @param u the updater to run
         * @param t the update type it handles
         * @param m the fetch method it uses
         * @param priority ordering weight, higher priorities consulted first
         */
        public RegisteredUpdater(Updater u, UpdateType t, UpdateMethod m, int priority) {
            updater = u; type = t; method = m; this.priority = priority;
        }

        /** Orders by descending priority, breaking ties on hash code for a stable order. */
        public int compareTo(RegisteredUpdater r) {
            int p = r.priority - priority;
            if (p != 0) {return p;}
            return hashCode() - r.hashCode();
        }

        /**
         * Hash code consistent with {@link #equals}.
         * @return a hash code consistent with equals()
         */
        @Override
        public int hashCode() {return updater.hashCode() ^ type.hashCode() ^ method.hashCode();}

        /**
         * Compares the updater, type and method only.
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegisteredUpdater)) {return false;}
            RegisteredUpdater r = (RegisteredUpdater) o;
            return type == r.type && method == r.method && updater.equals(r.updater);
        }

        /**
         * Identifies the updater class, type, method and priority for logging.
         */
        @Override
        public String toString() {
            return "RegisteredUpdater " + updater.getClass().getName() + " for " + type + " (" + method + ") @ priority " + priority;
        }
    }

    /**
     * Equals on checker, type and method only.
     */
    static class RegisteredChecker implements Comparable<RegisteredChecker> {
        /**
         * The Checker this registration invokes.
         */
        public final Checker checker;
        /**
         * The update type this registration serves.
         */
        public final UpdateType type;
        /**
         * The update method this registration was made for.
         */
        public final UpdateMethod method;
        /**
         * Higher priorities are consulted first.
         */
        public final int priority;

        /**
         * Record a Checker for the given type, method and priority.
         *
         * @param u the checker to consult
         * @param t the update type it checks
         * @param m the fetch method it reports on
         * @param priority ordering weight, higher priorities consulted first
         */
        public RegisteredChecker(Checker u, UpdateType t, UpdateMethod m, int priority) {
            checker = u; type = t; method = m; this.priority = priority;
        }

        /** Orders by descending priority, breaking ties on hash code for a stable order. */
        public int compareTo(RegisteredChecker r) {
            int p = r.priority - priority;
            if (p != 0) {return p;}
            return hashCode() - r.hashCode();
        }

        /**
         * Hashes the fields compared by equals().
         *
         * @return the combined hash of those fields
         */
        @Override
        public int hashCode() {return checker.hashCode() ^ type.hashCode() ^ method.hashCode();}

        /**
         * Compares the checker, type and method only.
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegisteredChecker)) {return false;}
            RegisteredChecker r = (RegisteredChecker) o;
            return type == r.type && method == r.method && checker.equals(r.checker);
        }

        /**
         * Identifies the checker class, type, method and priority for logging.
         */
        @Override
        public String toString() {
            return "RegisteredChecker " + checker.getClass().getName() + " for " + type + " (" + method + ") @ priority " + priority;
        }
    }

    /**
     * Equals on type and ID only.
     */
    static class UpdateItem {
        /**
         * The update type this item identifies.
         */
        public final UpdateType type;
        /**
         * The plugin name, or an empty string for non-plugin updates.
         */
        public final String id;

        /**
         * Identify an installed update by type and plugin name.
         *
         * @param t the update type
         * @param id the plugin name, or empty for the type itself
         */
        public UpdateItem(UpdateType t, String id) {
            type = t;
            this.id = id;
        }

        /**
         * Hashes the fields compared by equals().
         *
         * @return the combined hash of those fields
         */
        @Override
        public int hashCode() {return type.hashCode() ^ id.hashCode();}

        /**
         * Compares the type and id only.
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof UpdateItem)) {return false;}
            UpdateItem r = (UpdateItem) o;
            return type == r.type && id.equals(r.id);
        }

        /**
         * Returns the type, with the plugin name appended when there is one.
         */
        @Override
        public String toString() {
            if (id.isEmpty()) {return type.toString();}
            return type.toString() + ' ' + id;
        }
    }

    /**
     * The version string of an installed or downloaded update, ordered
     * numerically or by date against other versions.
     */
    static class Version implements Comparable<Version> {
        /**
         * The version string, either a number or an ISO date.
         */
        public final String versionStr;

        /**
         * Wrap the version string for comparison and display.
         *
         * @param version the version string, either a number or an ISO date
         */
        public Version(String version) {this.versionStr = version;}

        /**
         * Orders versions oldest to newest.
         */
        public int compareTo(Version r) {return VersionComparator.comp(versionStr, r.versionStr);}

        /**
         * Hashes the fields compared by equals().
         *
         * @return the combined hash of those fields
         */
        @Override
        public int hashCode() {return versionStr.hashCode();}

        /**
         * Compares the version strings.
         */
        @Override
        public boolean equals(Object o) {return (o instanceof Version) && versionStr.equals(((Version)o).versionStr);}

        /**
         * Renders the version, formatting a 13-character string as a date.
         */
        @Override
        public String toString() {
            if (versionStr.length() == 13) {
                try {return "Version " + versionStr + " (" + DataHelper.formatTime(Long.parseLong(versionStr)) + ')';}
                catch (NumberFormatException nfe) { /* ignored */ }
            }
            return "Version " + versionStr;
        }
    }

    /**
     * An update that exists upstream, with its download sources per method
     * and an optional reason it cannot currently be fetched.
     */
    static class VersionAvailable extends Version {
        /**
         * The oldest installed version that can update to this one.
         */
        public final String minVersion;
        /**
         * Where the update can be fetched from, keyed by update method.
         */
        public final ConcurrentHashMap<UpdateMethod, List<URI>> sourceMap;
        /**
         * Why the update cannot be downloaded, or null when it can.
         */
        public volatile String constraint;

        /**
         * Puts the method and sources in the map. The map may be added to later.
         *
         * @param version the available version string
         * @param min the minimum compatible version, or empty for none
         * @param method the update method these sources belong to
         * @param updateSources where the update can be fetched from
         */
        public VersionAvailable(String version, String min, UpdateMethod method, List<URI> updateSources) {
            super(version);
            minVersion = min;
            sourceMap = new ConcurrentHashMap<>(4);
            sourceMap.put(method, updateSources);
        }

        /**
         * Available but can't be downloaded due to constraint.
         *
         * @param version the available version string
         * @param constraint why it cannot be downloaded, shown to the user
         */
        public VersionAvailable(String version, String constraint) {
            super(version);
            minVersion = "";
            sourceMap = new ConcurrentHashMap<>(4);
            this.constraint = constraint;
        }

        /**
         * Also requires the argument to be a VersionAvailable.
         */
        @Override
        public boolean equals(Object o) {return super.equals(o) && (o instanceof VersionAvailable);}

        /**
         * Hashes the fields compared by equals().
         *
         * @return the combined hash of those fields
         */
        @Override
        public int hashCode() {return super.hashCode();} // findbugs

        /**
         * Renders the version with its sources and any download constraint.
         */
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(128);
            buf.append("Version ").append(versionStr).append(' ');
            for (Map.Entry<UpdateMethod, List<URI>> e : sourceMap.entrySet()) {
                buf.append(e.getKey());
                List<URI> u = e.getValue();
                if (u.isEmpty()) {buf.append(' ');}
                else {
                    buf.append('=');
                    if (u.size() > 1) {buf.append('[');}
                    for (URI uri : u) {buf.append(uri).append(' ');}
                    if (u.size() > 1) {buf.append(']');}
                }
            }
            if (constraint != null) {buf.append(" \"").append(constraint).append('"');}
            return buf.toString();
        }
    }

    /**
     * Debug overview counts as {installed, downloaded, available}, where
     * available counts items with an update available from any source,
     * including the internal placeholder.
     *
     * @return the counts, never null
     * @since 0.9.71
     */
    public int[] getDebugCounts() {
        return new int[] {_installed.size(), _downloaded != null ? _downloaded.size() : 0, _available.size()};
    }

    /**
     * Render update manager status HTML for the debug page as one table
     * with two sections - update items, then registered checkers,
     * updaters and post-processors - sharing a column grid so the content
     * aligns. Name, type and method lead, then role, and status is a
     * colored dot with the state as a tooltip.
     */
    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        Map<UpdateType, Set<UpdateMethod>> checkMethods = new HashMap<>();
        for (RegisteredChecker rc : _registeredCheckers) {
            checkMethods.computeIfAbsent(rc.type, k -> new HashSet<>()).add(rc.method);
        }
        List<String[]> items = buildItemRows(_installed, _downloaded, _available, checkMethods);
        Map<UpdateType, String> typeVersions = new HashMap<>();
        for (Map.Entry<UpdateItem, Version> e : _installed.entrySet()) {
            if (e.getKey().id.isEmpty()) {typeVersions.put(e.getKey().type, fmtVersion(e.getValue().versionStr));}
        }
        List<String[]> workers = buildWorkerRows(_registeredCheckers, _registeredUpdaters, _registeredPostProcessors,
                                                 _activeCheckers, _downloaders, typeVersions);
        out.append("<h2>Update Manager</h2>\n");
        out.append(buildUpdateTable(items, workers));
    }

    /**
     * Build the two-section table from the rows of buildItemRows
     * {status, type, name, installed, available, methods} and buildWorkerRows
     * {active, role, class, type, method, version}. Item-only and worker-only
     * columns carry an em-dash in the other section.
     * Package-visible for testing.
     *
     * @param items one row per update item
     * @param workers one row per registered worker
     * @return the table html, never null
     */
    static String buildUpdateTable(List<String[]> items, List<String[]> workers) {
        String headers = "<tr><th>Name</th><th>Type</th><th>Method</th><th>Role</th>" +
                         "<th>Installed/Version</th><th>Available</th><th>Status</th></tr>\n";
        StringBuilder buf = new StringBuilder((items.size() + workers.size()) * 96 + 512);
        buf.append("<table id=updatemgr>\n");
        buf.append("<thead>\n<tr><th colspan=7>Update Items (").append(items.size()).append(")</th></tr>\n").
            append(headers).append("</thead>\n<tbody>\n");
        for (String[] row : items) {
            buf.append("<tr><td>").append(DataHelper.escapeHTML(row[2]))
               .append("</td><td>").append(row[1])
               .append("</td><td>").append(DataHelper.escapeHTML(row[5]))
               .append("</td><td>—</td><td>").append(DataHelper.escapeHTML(row[3]))
               .append("</td><td>").append(DataHelper.escapeHTML(row[4]))
               .append("</td><td><span class=\"appdot ").append(dotClass(row[0]))
               .append("\" title=\"").append(row[0]).append("\"></span></td></tr>\n");
        }
        buf.append("</tbody>\n");
        buf.append("<thead>\n<tr><th colspan=7>Update Workers (").append(workers.size()).append(")</th></tr>\n").
            append(headers).append("</thead>\n<tbody>\n");
        for (String[] row : workers) {
            buf.append("<tr><td>").append(DataHelper.escapeHTML(row[2]))
               .append("</td><td>").append(row[3])
               .append("</td><td>").append(row[4])
               .append("</td><td>").append(row[1])
               .append("</td><td>").append(DataHelper.escapeHTML(row[5]))
               .append("</td><td>—</td><td><span class=\"appdot ").append(dotClass(row[0]))
               .append("\" title=\"").append(row[0]).append("\"></span></td></tr>\n");
        }
        buf.append("</tbody>\n</table>\n");
        return buf.toString();
    }

    /**
     * Merge the installed, downloaded and available maps into one row set of
     * {status, type, name, installed, available, methods}, sorted by name.
     * Methods lists the update methods registered as checkers for the
     * item's type.
     * An item present in more than one map yields a single row; the status
     * reflects the most advanced stage: Downloaded, then Available, then
     * Installed. Items with no id (non-plugin updates) get a display name
     * derived from their type. Null maps are treated as empty.
     * Pure, for unit tests.
     *
     * @param installed downloaded and installed versions by item
     * @param downloaded downloaded but not installed versions by item
     * @param available versions reported by checkers by item
     * @param checkMethods update methods registered as checkers, by type
     * @return one row per item, never null
     * @since 0.9.71
     */
    static List<String[]> buildItemRows(Map<UpdateItem, Version> installed, Map<UpdateItem, Version> downloaded,
                                        Map<UpdateItem, VersionAvailable> available,
                                        Map<UpdateType, Set<UpdateMethod>> checkMethods) {
        Map<UpdateItem, Version> inst = installed != null ? installed : Collections.emptyMap();
        Map<UpdateItem, Version> dl = downloaded != null ? downloaded : Collections.emptyMap();
        Map<UpdateItem, VersionAvailable> av = available != null ? available : Collections.emptyMap();
        Set<UpdateItem> items = new HashSet<>(inst.size() + dl.size() + av.size());
        items.addAll(inst.keySet());
        items.addAll(dl.keySet());
        items.addAll(av.keySet());
        List<String[]> rows = new ArrayList<>(items.size());
        for (UpdateItem item : items) {
            String status = dl.containsKey(item) ? "Downloaded" : av.containsKey(item) ? "Available" : "Installed";
            String instStr = inst.containsKey(item) ? fmtVersion(inst.get(item).versionStr) : "—";
            String availStr = "—";
            if (av.containsKey(item)) {
                VersionAvailable va = av.get(item);
                availStr = fmtVersion(va.versionStr);
                if (va.constraint != null) {availStr += " (" + va.constraint + ')';}
            }
            Set<UpdateMethod> methods = checkMethods != null ? checkMethods.get(item.type) : null;
            String methodStr = "—";
            if (methods != null && !methods.isEmpty()) {
                List<String> names = new ArrayList<>(methods.size());
                for (UpdateMethod m : methods) {names.add(m.name());}
                Collections.sort(names);
                methodStr = String.join(", ", names);
            }
            rows.add(new String[] {status, item.type.name(), itemDisplayName(item.type, item.id), instStr, availStr, methodStr});
        }
        rows.sort(Comparator.comparing((String[] r) -> r[2], Collator.getInstance()));
        return rows;
    }

    /**
     * Display name for an update item. Plugin items are named by their
     * plugin id; blocklist items combine their id with the type
     * ("Sybil Blocklist"); geoip ids are already readable; the remaining
     * unnamed per-type items get a readable name from their type instead
     * of a dash.
     * Package-visible for testing.
     *
     * @param type the update type
     * @param id the item id, empty for non-plugin items
     * @return a display name, never null
     * @since 0.9.71
     */
    static String itemDisplayName(UpdateType type, String id) {
        switch (type) {
            case BLOCKLIST: return id.isEmpty() ? "Blocklist" : capitalize(id) + " Blocklist";
            case GEOIP: return id.isEmpty() ? "GeoIP Database" : capitalize(id);
            case ROUTER_SIGNED: return "Signed Update";
            case ROUTER_SIGNED_SU3: return "Signed Update (SU3)";
            case ROUTER_DEV_SU3: return "Development Update (SU3)";
            case ROUTER_UNSIGNED: return "Unsigned Update";
            case NEWS:
            case NEWS_SU3: return "News Feed";
            case API: return "API Update";
            case TYPE_DUMMY: return "Internal Placeholder";
            default: return !id.isEmpty() ? id : type.name();
        }
    }

    /**
     * The installed version string registered for a worker's type, or a
     * dash when that type has no installed version.
     *
     * @param typeVersions installed version strings by type, may be null
     * @param type the worker's update type
     * @return the version or an em-dash
     */
    private static String versionFor(Map<UpdateType, String> typeVersions, UpdateType type) {
        String v = typeVersions != null ? typeVersions.get(type) : null;
        return v != null ? v : "—";
    }

    /**
     * Upper-case the first character, leaving the rest as-is.
     *
     * @param s the string to capitalize, not null
     * @return the capitalized string
     */
    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Merge the registered checkers, updaters and post-processors into one
     * row set of {active, role, class, type, method, version}, sorted by
     * role then class. A checker is active when a running task matches its
     * type and method; an updater when it is in a running task's remaining
     * list; post-processors have no running state. Null collections are
     * treated as empty.
     * Pure, for unit tests.
     *
     * @param checkers the registered checkers, null treated as empty
     * @param updaters the registered updaters, null treated as empty
     * @param postProcessors registered post-processors by type id
     * @param activeCheckers currently running check tasks
     * @param downloaders currently running update tasks to their remaining updaters
     * @param typeVersions installed version strings by type, for the worker's Installed/Version cell
     * @return one row per registration, never null
     * @since 0.9.71
     */
    static List<String[]> buildWorkerRows(Collection<RegisteredChecker> checkers, Collection<RegisteredUpdater> updaters,
                                          Map<Integer, UpdatePostProcessor> postProcessors,
                                          Collection<UpdateTask> activeCheckers,
                                          Map<UpdateTask, List<RegisteredUpdater>> downloaders,
                                          Map<UpdateType, String> typeVersions) {
        Set<String> activeCheckerKeys = new HashSet<>();
        if (activeCheckers != null) {
            for (UpdateTask t : activeCheckers) {activeCheckerKeys.add(t.getType() + " " + t.getMethod());}
        }
        Set<RegisteredUpdater> activeUpdaters = new HashSet<>();
        if (downloaders != null) {
            for (List<RegisteredUpdater> l : downloaders.values()) {activeUpdaters.addAll(l);}
        }
        List<String[]> rows = new ArrayList<>();
        if (checkers != null) {
            for (RegisteredChecker r : checkers) {
                boolean active = activeCheckerKeys.contains(r.type + " " + r.method);
                rows.add(new String[] {active ? "Active" : "Idle", "Checker", r.checker.getClass().getSimpleName(),
                                       r.type.name(), r.method.name(), versionFor(typeVersions, r.type)});
            }
        }
        if (updaters != null) {
            for (RegisteredUpdater r : updaters) {
                rows.add(new String[] {activeUpdaters.contains(r) ? "Active" : "Idle", "Updater", r.updater.getClass().getSimpleName(),
                                       r.type.name(), r.method.name(), versionFor(typeVersions, r.type)});
            }
        }
        if (postProcessors != null) {
            for (Map.Entry<Integer, UpdatePostProcessor> e : postProcessors.entrySet()) {
                rows.add(new String[] {"—", "PostProcessor", e.getValue().getClass().getSimpleName(),
                                       e.getKey().toString(), "—", "—"});
            }
        }
        rows.sort(Comparator.comparing((String[] r) -> r[1], Collator.getInstance()).thenComparing(r -> r[2], Collator.getInstance()));
        return rows;
    }

    /**
     * Format a 13-digit version string as a date, otherwise as-is.
     *
     * @param versionStr the raw version string
     * @return a display string, never null
     * @since 0.9.71
     */
    static String fmtVersion(String versionStr) {
        if (versionStr.length() == 13) {
            try {return DataHelper.formatTime(Long.parseLong(versionStr));}
            catch (NumberFormatException nfe) { /* not a date, fall through */ }
        }
        return versionStr;
    }

    /**
     * Map an item or worker status to its status dot colour: green for
     * installed or active, yellow for an available update, blue for a
     * downloaded update awaiting install, grey otherwise.
     *
     * @param status the row status
     * @return a shared.css .appdot colour class, never null
     * @since 0.9.71
     */
    static String dotClass(String status) {
        switch (status) {
            case "Installed": case "Active": return "green";
            case "Available": return "yellow";
            case "Downloaded": return "blue";
            default: return "grey";
        }
    }

}
