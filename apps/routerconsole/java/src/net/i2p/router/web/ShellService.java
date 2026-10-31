/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * ShellService.java
 * 2021 The I2P Project
 * http://www.geti2p.net
 * This code is public domain.
 */

package net.i2p.router.web;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Alternative to ShellCommand for plugins based on ProcessBuilder, which
 * manages
 * a process and keeps track of it's state by maintaining a Process object.
 * <p>
 * Keeps track of the process, and reports start/stop status correctly
 * on configplugins. When running a ShellService from a {@code clients.config} file,
 * the user MUST pass -shellservice.name in the args field in {@code clients.config}
 * to override the plugin name. The name passed to -shellservice.name should
 * be unique to avoid causing issues.
 * (https://i2pgit.org/i2p-hackers/i2p.i2p/-/merge_requests/39#note_4234)
 * -shellservice.displayName is optional and configures the name of the plugin
 * which is shown on the console. In most cases, the -shellservice.name must be
 * the same as the plugin name in order for the $PLUGIN field in {@code clients.config}
 * to match the expected value. If this is not the case, i.e.
 * (-shellservice.name != plugin.name), you must not use $PLUGIN in your
 * {@code clients.config} file.
 * <p>
 * The recommended way to use this tool is to manage a single forked
 * app/process,
 * with a single ShellService, in a single plugin.
 * <p>
 * When you are writing your {@code clients.config} file, please take note that $PLUGIN
 * will be derived from the `shellservice.name` field in the config file args.
 * <p>
 * Works on Windows, OSX, and Linux.
 *
 * @since 1.6.0/0.9.52, moved from net.i2p.app in 0.9.53
 */
public class ShellService implements ClientApp {
    /** Option naming the service internally. */
    private static final String NAME_OPTION = "-shellservice.name";
    /** Option naming the service for display. */
    private static final String DISPLAY_NAME_OPTION = "-shellservice.displayname";
    /** Directory the command is looked up in. */
    private static final String PLUGIN_DIR = "plugins";
    /** Splits a command line argument on the equals sign. */
    private static final Pattern EQUALS_SPLIT = Pattern.compile("=");

    /** Logger for the shell service. */
    private final Log _log;
    /** Builder launching the external command. */
    private final ProcessBuilder _pb;
    /** The app context. */
    private final I2PAppContext _context;
    /** The manager notified of the state changes. */
    private final ClientAppManager _cmgr;
    /** The command line being run. */
    private final String _commandPath;
    /** File the command's errors are written to. */
    private final File _errorLog;
    /** File the command's output is written to. */
    private final File _outputLog;

    /** Current state of the service. */
    private ClientAppState _state = ClientAppState.UNINITIALIZED;

    /** Internal name of the service. */
    private volatile String name = "unnamedClient";
    /** Name of the service shown in the console. */
    private volatile String displayName = "unnamedClient";

    /** The running process, while started. */
    private Process _p;

    /**
     * Creates a ShellService from command-line args, validating the executable and setting up log files.
     *
     * @param context the application context supplying the log manager
     * @param listener the client app manager to notify of this app's state changes
     * @param args the command line arguments: the executable path first, then the
     *             arguments to pass to that executable
     */
    public ShellService(I2PAppContext context, ClientAppManager listener, String[] args) {
        _context = context;
        _cmgr = listener;
        _log = context.logManager().getLog(ShellService.class);

        ArrayList<String> procArgs = trimArgs(args);

        if (_log.shouldDebug()) {
            _log.debug("ShellService: Process: " + procArgs.toString());
            _log.debug("ShellService: Name: " + this.getName() + ", DisplayName: " + this.getDisplayName());
        }

        _commandPath = procArgs.get(0);

        File exe = new File(_commandPath);
        if (!exe.exists()) {
            if (_log.shouldError())
                _log.error("ShellService: Command does not exist: " + _commandPath);
            throw new RuntimeException("Command does not exist: " + _commandPath);
        }
        if (!exe.canExecute()) {
            if (_log.shouldWarn())
                _log.warn("ShellService: Command is not executable: " + _commandPath + " marking it executable");
            exe.setExecutable(true);
        }

        _pb = new ProcessBuilder(procArgs);

        if (_log.shouldDebug())
            _log.debug("ShellService: ProcessBuilder: " + _pb.command().toString() + " is built");

        String tmp_name = this.getName();
        File pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + tmp_name);
        if (!pluginDir.exists()){
            pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + tmp_name+"-"+SystemVersion.getOS()+"-"+SystemVersion.getArch());
            if (!pluginDir.exists()) {
                pluginDir = new File(_context.getConfigDir(), PLUGIN_DIR + '/' + tmp_name+"-"+SystemVersion.getOS());
                if (!pluginDir.exists()) {
                    throw new RuntimeException("Plugin directory does not exist: " + pluginDir.getAbsolutePath());
                } else {
                    this.name = tmp_name+"-"+SystemVersion.getOS();
                    if (_log.shouldDebug())
                        _log.debug("ShellService: Plugin name revised to match directory: " + this.getName());
                }
            } else {
                this.name = tmp_name+"-"+SystemVersion.getOS()+"-"+SystemVersion.getArch();
                if (_log.shouldDebug())
                    _log.debug("ShellService: Plugin name revised to match directory: " + this.getName());
            }
        }

        _errorLog = new File(pluginDir, "error.log");
        _outputLog = new File(pluginDir, "output.log");
        _pb.redirectOutput(_outputLog);
        _pb.redirectError(_errorLog);
        if (_log.shouldDebug())
            _log.debug("ShellService: Logs: " + _errorLog.getAbsolutePath() + ", " + _outputLog.getAbsolutePath());

        _pb.directory(pluginDir);
        if (_log.shouldDebug())
            _log.debug("ShellService: ProcessBuilder: " + _pb.directory() + " is set");
        changeState(ClientAppState.INITIALIZED, "ShellService: " + getName() + " setup and initialized");
    }

    /**
     * Extract the app process arguments, filtering out -shellservice options.
     * @param args raw command-line arguments
     * @return filtered argument list
     */
    private ArrayList<String> trimArgs(String[] args) {
        ArrayList<String> newargs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(NAME_OPTION)) {
                if (args[i].contains("=")) {
                    name = EQUALS_SPLIT.split(args[i])[1];
                } else {
                    name = args[i + 1];
                    i++;
                }
            } else if (args[i].startsWith(DISPLAY_NAME_OPTION)) {
                if (args[i].contains("=")) {
                    displayName = EQUALS_SPLIT.split(args[i])[1];
                } else {
                    displayName = args[i + 1];
                    i++;
                }
            } else {
                if (_log.shouldDebug())
                    _log.debug("Adding arg: " + args[i]);
                newargs.add(args[i]);
            }
        }
        if (getName() == null)
            throw new IllegalArgumentException(
                    "ShellService: ShellService passed with args=" + Arrays.toString(args) + " must have a name");
        if (getDisplayName() == null)
            displayName = name;
        return newargs;
    }

    /**
     * Changes the app state and notifies listeners.
     *
     * @param newState the new state
     * @param message the message for the listeners
     * @param ex the exception, null if none
     */
    private synchronized void changeState(ClientAppState newState, String message, Exception ex) {
        if (_state != newState) {
            _state = newState;
            _cmgr.notify(this, newState, message, ex);
        }
    }

    /**
     * Changes the app state and notifies listeners.
     *
     * @param newState the new state
     * @param message the message for the listeners
     */
    private synchronized void changeState(ClientAppState newState, String message) {
        changeState(newState, message, null);
    }

    /**
     * Determine if a ShellService corresponding to the wrapped application
     * has been started yet. If it hasn't, attempt to start the process and
     * notify the router that it has been started.
     */
    @Override
    public synchronized void startup() throws Throwable {
        File exe = new File(_commandPath);
        if (!exe.exists()) {
            if (_log.shouldError())
                _log.error("ShellService: Command does not exist: " + _commandPath);
            throw new RuntimeException("Command does not exist: " + _commandPath);
        }
        if (!exe.canExecute()) {
            if (_log.shouldWarn())
                _log.warn("ShellService: Command is not executable: " + _commandPath + " marking it executable");
            exe.setExecutable(true);
        }
        if (getName().equals("unnamedClient")) {
            if (_log.shouldWarn())
                _log.warn("ShellService: ShellService has no name, not starting");
            return;
        }
        changeState(ClientAppState.STARTING, "ShellService: " + getName() + " starting");
        boolean start = isProcessStopped();
        if (start) {
            _p = _pb.start();
            if (!_p.isAlive() && _log.shouldError())
                _log.error("ShellService process died -> Command: " + _pb.command()
                           + " * Exit value: " + _p.exitValue());
            if (_log.shouldDebug())
                _log.debug("ShellService: Started " + getName() + "process");
        }
        if (_p.isAlive())
            changeState(ClientAppState.RUNNING, "ShellService: " + getName() + " started");
        boolean reg = _cmgr.register(this);
        if (reg) {
            if (_log.shouldDebug())
                _log.debug("ShellService: " + getName() + " registered with the router");
        } else {
            if (_log.shouldWarn())
                _log.warn("ShellService: " + getName() + " failed to register with the router");
            _cmgr.unregister(this);
            _cmgr.register(this);
        }
        return;
    }

    /**
     * Determine if the process running or not.
     *
     * @return {@code true} if the Process is NOT running, {@code false} if the
     *         Process is
     *         running
     */
    public boolean isProcessStopped() {
        return !isProcessRunning();
    }

    /**
     * Determine if the process running or not.
     *
     * @return {@code true} if the Process is running, {@code false} if the Process
     *         is
     *         not running
     */
    public boolean isProcessRunning() {
        if (_p == null)
            return false;
        if (_log.shouldDebug())
            _log.debug("ShellService: Checking process status " + getName() + _p.isAlive());
        return _p.isAlive();
    }

    /**
     * Shut down the process by calling {@code Process.destroy()}.
     *
     * @param args generally null but could be stopArgs from {@code clients.config}
     */
    @Override
    public synchronized void shutdown(String[] args) throws Throwable {
        if (getName().equals("unnamedClient")) {
            if (_log.shouldWarn())
                _log.warn("ShellService: ShellService has no name, not shutting down");
            return;
        }
        changeState(ClientAppState.STOPPING, "ShellService: " + getName() + " stopping");
        if (_p != null) {
            if (_log.shouldDebug())
                _log.debug("ShellService: Stopping " + getName() + "process started with ShellService " + getName() + "...");
            _p.destroy();
        }
        changeState(ClientAppState.STOPPED, "ShellService: " + getName() + " stopped");
        _cmgr.unregister(this);
    }

    /**
     * Query the state of managed process and determine if it is running
     * or not. Convert to corresponding ClientAppState and return the correct
     * value.
     *
     * @return non-null
     */
    @Override
    public ClientAppState getState() {
        if (!isProcessRunning()) {
            if (_log.shouldDebug())
                _log.debug("ShellService: Process is not running " + getName());
            changeState(ClientAppState.STOPPED, "ShellService: " + getName() + " stopped");
            _cmgr.unregister(this);
        }
        return _state;
    }

    /**
     * The generic name of the ClientApp, used for registration,
     * e.g. "console". Do not translate. Has a special use in the context of
     * ShellService, must match the plugin name.
     *
     * @return non-null
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * The display name of the ClientApp, used in user interfaces.
     * The app must translate.
     *
     * @return non-null
     */
    @Override
    public String getDisplayName() {
        return displayName;
    }

}
