package net.i2p.router.startup;

import static net.i2p.app.ClientAppState.*;

import java.io.IOException;
import java.io.Writer;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.app.*;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Notify the router of events, and provide methods for
 * client apps to find each other.
 *
 * @since 0.9.4
 */
public class RouterAppManager extends ClientAppManagerImpl {

    /** Logger for this class. */
    private final Log _log;
    /**
     * Client apps to the arguments they were started with. This assumes the
     * clients do not override equals().
     */
    private final ConcurrentHashMap<ClientApp, String[]> _clients;

    /**
     * Opens the client map and registers a shutdown task.
     *
     * @param ctx router context supplying the log manager and shutdown task registration
     */
    public RouterAppManager(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(RouterAppManager.class);
        _clients = new ConcurrentHashMap<>(16);
        ctx.addShutdownTask(new Shutdown());
    }

    /**
     * Add and start a client application.
     *
     * @param app the client application to track and start
     * @param args the args that were used to instantiate the app, non-null, may be zero-length
     * @return true if the app started; false if startup() threw, in which case it is dropped again
     * @throws IllegalArgumentException if already added
     */
    public boolean addAndStart(ClientApp app, String[] args) {
        if (_log.shouldInfo())
            _log.info("Client " + app.getDisplayName() + " ADDED");
        String[] old = _clients.putIfAbsent(app, args);
        if (old != null)
            throw new IllegalArgumentException("already added");
        try {
            app.startup();
            return true;
        } catch (Throwable t) {
            _clients.remove(app);
            _log.error("Client " + app + " failed to start", t);
            return false;
        }
    }

    /**
     * First known ClientApp with this class name and exact arguments.
     * Caller may then retrieve or control the state of the returned client.
     * A client will generally be found only if it is running or transitioning;
     * after it is stopped it will not be tracked by the manager.
     *
     * @param className fully qualified class name of the client application to find
     * @param args non-null, may be zero-length
     * @return client app or null
     * @since 0.9.6
     */
    public ClientApp getClientApp(String className, String[] args) {
        for (Map.Entry<ClientApp, String[]> e : _clients.entrySet()) {
            if (e.getKey().getClass().getName().equals(className) &&
                Arrays.equals(e.getValue(), args))
                return e.getKey();
        }
        // workaround for Jetty stop and restart from i2ptunnel
        // app becomes untracked so look in registered
        if (className.equals("net.i2p.jetty.JettyStart") && args.length > 0) {
            for (ClientApp app : _registered.values()) {
                if (app.getClass().getName().equals(className)) {
                    String dname = app.getDisplayName();
                    int idx = 0;
                    boolean match = true;
                    for (String arg : args) {
                        idx = dname.indexOf(arg, idx);
                        if (idx < 0) {
                            match = false;
                            break;
                        }
                    }
                    if (match)
                        return app;
                }
            }
        }
        return null;
    }

    /**
     * Must be called on all state transitions except
     * from UNINITIALIZED to INITIALIZED.
     *
     * @param app non-null
     * @param state non-null
     * @param message may be null
     * @param e may be null
     */
    @Override
    public void notify(ClientApp app, ClientAppState state, String message, Exception e) {
        switch(state) {
          case UNINITIALIZED:
          case INITIALIZED:
            if (_log.shouldWarn())
                _log.warn("Client " + app.getDisplayName() + " is now " + state);
            break;

          case STARTING:
          case RUNNING:
            if (_log.shouldInfo())
                _log.info("Client " + app.getDisplayName() + " is now " + state);
            break;

          case FORKED:
          case STOPPING:
          case STOPPED:
            _clients.remove(app);
            boolean removed = _registered.remove(app.getName(), app);
            if (removed && _log.shouldInfo()) {
                _log.info("Client " + app.getDisplayName() + " UNREGISTERED AS " + app.getName());
            }
            if (message == null)
                message = "";
            if (_log.shouldInfo())
                _log.info("Client " + app.getDisplayName() + " is now " + state +
                          ' ' + message, e);
            break;

          case CRASHED:
          case START_FAILED:
            _clients.remove(app);
            boolean removed2 = _registered.remove(app.getName(), app);
            if (removed2 && _log.shouldInfo()) {
                _log.info("Client " + app.getDisplayName() + " UNREGISTERED AS " + app.getName());
            }
            if (message == null)
                message = "";
            _log.log(Log.CRIT, "Client " + app.getDisplayName() + ' ' + state +
                               ' ' + message, e);
            break;
        }
    }

    /**
     * Register with the manager under the given name,
     * so that other clients may find it.
     * Only required for apps used by other apps.
     *
     * @param app non-null
     * @return true if successful, false if duplicate name
     */
    @Override
    public boolean register(ClientApp app) {
        if (!_clients.containsKey(app) && _log.shouldInfo()) {
            // Allow registration even if we didn't start it,
            // useful for plugins
            _log.info("Registering untracked client " + app.getName());
        }
        if (_log.shouldInfo())
            _log.info("Client " + app.getDisplayName() + " REGISTERED AS " + app.getName());
        // TODO if old app in there is not running and != this app, allow replacement
        return super.register(app);
    }

    /**
     * Unregister with the manager. Name must be the same as that from {@link #register}.
     * Only required for apps used by other apps.
     *
     * @param app non-null
     * @since 0.9.41 overridden for logging only
     */
    @Override
    public void unregister(ClientApp app) {
        if (_log.shouldInfo() && getRegisteredApp(app.getName()) != null)
            _log.info("Client " + app.getDisplayName() + " UNREGISTERED AS " + app.getName());
        super.unregister(app);
    }

    /// end ClientAppManager interface

    /**
     * Shut down all running client applications.
     *
     * @since 0.9.6
     */
    public synchronized void shutdown() {
        Set<ClientApp> apps = new HashSet<>(_clients.keySet());
        for (ClientApp app : apps) {
            ClientAppState state = app.getState();
            if (state == RUNNING || state == STARTING) {
                try {
                   if (_log.shouldWarn())
                       _log.warn("Shutting down client " + app.getDisplayName());
                    app.shutdown(null);
                } catch (Throwable t) { /* ignored */ }
            }
        }
    }

    /**
     * Shutdown task for router applications.
     * @since 0.9.6
     */
    public class Shutdown implements Runnable {
        /**
         * The enclosing RouterAppManager is what run() shuts down; as an inner class the
         * outer instance is supplied by the compiler, and this one adds no state of its own.
         */
        public Shutdown() {}

        /**
         * Shut down all running router applications.
         */
        @Override
        public void run() {
            shutdown();
        }
    }

    /**
     * Render debug HTML of the app manager state.
     *
     * @param out the writer the debug HTML is appended to
     * @throws IOException if writing the HTML to the writer fails
     * @since 0.9.6
     */
    /**
     * Debug overview counts as {tracked, running, registered}.
     *
     * @return the counts, never null
     * @since 0.9.71
     */
    public int[] getDebugCounts() {
        int running = 0;
        for (ClientApp app : _clients.keySet()) {
            if (app.getState() == ClientAppState.RUNNING) {running++;}
        }
        return new int[] {_clients.size(), running, _registered.size()};
    }

    /**
     * Table of registered and running router apps with state dots.
     *
     * @param out writer to write HTML output to
     * @throws IOException if writing to output fails
     */
    public void renderStatusHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(1024);
        List<String[]> rows = buildRows(_clients, _registered);
        buf.append("<h2>App Manager</h2>\n<table id=apps>\n<thead>\n" +
                  "<tr><th>State</th><th>Name</th><th>Class</th><th>Args</th></tr>\n</thead>\n");
        for (String[] row : rows) {
            buf.append("<tr><td><span class=\"appdot ").append(dotClass(row[0])).append("\"></span>").append(row[0])
               .append("</td><td>").append(DataHelper.escapeHTML(row[1]))
               .append("</td><td>").append(row[2])
               .append("</td><td>").append(DataHelper.escapeHTML(row[3])).append("</td></tr>\n");
        }
        buf.append("</table>\n");
        out.append(buf);
    }

    /**
     * Merge the tracked and registered app maps into one row set of
     * {state, name, class, args}, sorted by name. Tracked apps report their
     * live state and launch args; registered apps that were never tracked
     * report "Registered" with no args.
     * Pure, for unit tests.
     *
     * @param clients tracked apps to their launch args
     * @param registered all registered apps by name
     * @return one row per app, never null
     * @since 0.9.71
     */
    static List<String[]> buildRows(Map<ClientApp, String[]> clients, Map<String, ClientApp> registered) {
        List<String[]> rows = new ArrayList<>(clients.size() + registered.size());
        Set<ClientApp> tracked = new HashSet<>(clients.size());
        for (Map.Entry<ClientApp, String[]> entry : clients.entrySet()) {
            ClientApp app = entry.getKey();
            tracked.add(app);
            String args = entry.getValue() != null ? Arrays.toString(entry.getValue()) : "—";
            rows.add(new String[] {app.getState().toString(), app.getName(), app.getClass().getName(), args});
        }
        for (Map.Entry<String, ClientApp> entry : registered.entrySet()) {
            if (tracked.contains(entry.getValue())) {continue;}
            rows.add(new String[] {"Registered", entry.getKey(), entry.getValue().getClass().getName(), "—"});
        }
        rows.sort(Comparator.comparing(r -> r[1], Collator.getInstance()));
        return rows;
    }

    /**
     * Map an app state to its status dot colour: green running, yellow in
     * transition, red failed, grey stopped or never started.
     *
     * @param state the app state name
     * @return a shared.css .appdot colour class, never null
     * @since 0.9.71
     */
    static String dotClass(String state) {
        switch (state) {
            case "RUNNING": return "green";
            case "STARTING": case "STOPPING": case "INITIALIZED": case "UNINITIALIZED": return "yellow";
            case "START_FAILED": return "red";
            default: return "grey";
        }
    }
}
