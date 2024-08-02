package net.i2p.router.web;

import java.awt.SystemTray;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.apps.systray.UrlLauncher;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

import org.tanukisoftware.wrapper.WrapperManager;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {

    private static WrapperListener _wrapperListener;

    private static final String LISTENER_AVAILABLE = "3.2.0";
    private static final String PROPERTIES_AVAILABLE = "3.2.0";
    private static final String LOCATION_AVAILABLE = "3.3.7";

    /**
     *  Register two shutdown hooks, one to rekey and/or tell the wrapper we are stopping,
     *  and a final one to tell the wrapper we are stopped.
     *
     *  @since 0.8.8
     */
    private void registerWrapperNotifier(int code, boolean rekey) {
        registerWrapperNotifier(_context, code, rekey);
    }

    /**
     *  Register two shutdown hooks, one to rekey and/or tell the wrapper we are stopping,
     *  and a final one to tell the wrapper we are stopped.
     *
     *  @since 0.8.8
     */
    public static void registerWrapperNotifier(RouterContext ctx, int code, boolean rekey) {
        Runnable task = new UpdateWrapperOrRekeyTask(rekey, ctx.hasWrapper());
        ctx.addShutdownTask(task);
        if (ctx.hasWrapper()) {
            task = new FinalWrapperTask(code);
            ctx.addFinalShutdownTask(task);
        }
    }

    /**
     *  Rekey and/or tell the wrapper we are stopping,
     */
    private static class UpdateWrapperOrRekeyTask implements Runnable {
        private final boolean _rekey;
        private final boolean _tellWrapper;
        private static final int HASHCODE = -123999871;
        // RPi takes a long time to write out the peer profiles
        private static final int WAIT = SystemVersion.isARM() ? 4*60*1000 : 2*60*1000;

        public UpdateWrapperOrRekeyTask(boolean rekey, boolean tellWrapper) {
            _rekey = rekey;
            _tellWrapper = tellWrapper;
        }

        public void run() {
            try {
                if (_rekey)
                    ContextHelper.getContext(null).router().killKeys();
                if (_tellWrapper) {
                    int wait = WAIT;
                    String wv = System.getProperty("wrapper.version");
                    if (wv != null && VersionComparator.comp(wv, PROPERTIES_AVAILABLE) >= 0) {
                        try {
                            Properties props = WrapperManager.getProperties();
                            String tmout = props.getProperty("wrapper.jvm_exit.timeout");
                            if (tmout != null) {
                                try {
                                    int cwait = Integer.parseInt(tmout) * 1000;
                                    if (cwait > wait)
                                        wait = cwait;
                                } catch (NumberFormatException nfe) {}
                            }
                        } catch (Throwable t) {}
                    }
                    WrapperManager.signalStopping(wait);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public int hashCode() {
            return HASHCODE;
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public boolean equals(Object o) {
            return (o != null) && (o instanceof UpdateWrapperOrRekeyTask);
        }
    }

    /**
     *  Tell the wrapper we are stopped.
     *
     *  @since 0.8.8
     */
    private static class FinalWrapperTask implements Runnable {
        private final int _exitCode;
        private static final int HASHCODE = 123999871;

        public FinalWrapperTask(int exitCode) {
            _exitCode = exitCode;
        }

        public void run() {
            try {
                WrapperManager.signalStopped(_exitCode);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public int hashCode() {
            return HASHCODE;
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public boolean equals(Object o) {
            return (o != null) && (o instanceof FinalWrapperTask);
        }
    }

    /**
     *  Register a handler for signals,
     *  so we can handle HUP from the wrapper (wrapper 3.2.0 or higher)
     *
     *  @since 0.8.13
     */
    synchronized static void registerSignalHandler(RouterContext ctx) {
        if (ctx.hasWrapper() && _wrapperListener == null) {
            String wv = System.getProperty("wrapper.version");
            if (wv != null && VersionComparator.comp(wv, LISTENER_AVAILABLE) >= 0) {
                try {
                   _wrapperListener = new WrapperListener(ctx);
                } catch (Throwable t) {}
            }
        }
    }

    /**
     *  Unregister the handler for signals
     *
     *  @since 0.8.13
     */
    public synchronized static void unregisterSignalHandler() {
        if (_wrapperListener != null) {
            _wrapperListener.unregister();
            _wrapperListener = null;
        }
    }

    /**
     *  Should we show the cancel button?
     *
     *  @since 0.9.19
     */
    public boolean shouldShowCancelGraceful() {
        return _context.router().gracefulShutdownInProgress();
    }

    /**
     *  Should we show the systray controls?
     *
     *  @since 0.9.26
     */
    public boolean shouldShowSystray() {
        try {
            if (!SystemTray.isSupported())
                return false;
        } catch (Throwable t) {
            // java.lang.NoClassDefFoundError: Could not initialize class java.awt.Toolkit
            return false;
        }
        return !SystemVersion.isService() &&
               !(SystemVersion.isWindows() && _context.hasWrapper() && WrapperManager.isLaunchedAsService());
    }

    /**
     *  Is the systray enabled?
     *
     *  @since 0.9.26
     */
    public boolean isSystrayEnabled() {
        return RouterConsoleRunner.isSystrayEnabled(_context);
    }

    /**
     *  @since 0.9.33
     */
    public String getConsoleURL() {
        return _context.portMapper().getConsoleURL();
    }

    @Override
    protected void processForm() {
        if (_t("Shutdown gracefully").equals(_action)) {
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_GRACEFUL, false);
            _context.router().shutdownGracefully();
            addFormNotice(_t("Graceful shutdown initiated"));
        } else if (_t("Shutdown immediately").equals(_action)) {
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_HARD, false);
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice(_t("Shutdown immediately"));
        } else if (_t("Cancel graceful shutdown").equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice(_t("Graceful shutdown cancelled"));
        } else if (_t("Graceful restart").equals(_action)) {
            // should have wrapper if restart button is visible
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_GRACEFUL_RESTART, false);
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            addFormNotice(_t("Graceful restart requested"));
        } else if (_t("Hard restart").equals(_action)) {
            // should have wrapper if restart button is visible
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_HARD_RESTART, false);
            _context.router().shutdown(Router.EXIT_HARD_RESTART);
            addFormNotice(_t("Hard restart requested"));
        } else if (_t("Rekey and Restart").equals(_action)) {
            addFormNotice(_t("Rekeying after graceful restart"));
            registerWrapperNotifier(Router.EXIT_GRACEFUL_RESTART, true);
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
        } else if (_t("Rekey and Shutdown").equals(_action)) {
            addFormNotice(_t("Rekeying after graceful shutdown"));
            registerWrapperNotifier(Router.EXIT_GRACEFUL, true);
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL);
        } else if (_t("Install I2P service").equals(_action)) {
            enableService();
            addFormNotice(_t("I2P+ Service now enabled on system start"));
        } else if (_t("Enable I2P service").equals(_action)) {
            enableService();
            addFormNotice(_t("I2P+ Service now enabled on system start"));
        } else if (_t("Disable I2P service").equals(_action)) {
            disableService();
            addFormNotice(_t("I2P+ Service now disabled on system start"));
        } else if (_t("Dump threads").equals(_action)) {
            try {
                WrapperManager.requestThreadDump();
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the Service Manager - " + t.getLocalizedMessage());
            }
            File wlog = wrapperLogFile(_context);
            addFormNotice(_t("Threads dumped to {0}", wlog.getAbsolutePath()));
            boolean deadlock = DeadlockDetector.detect(_context);
            if (deadlock)
                addFormErrorNoEscape("Deadlock detected!<br><a href=\"/logs\">Please report using the information on the logs page!</a><br>After reporting, please restart your router!");
        } else if (_t("Open console on startup").equals(_action)) {
            browseOnStartup(true);
            addFormNotice(_t("Console is to be shown on startup"));
        } else if (_t("No console on startup").equals(_action)) {
            browseOnStartup(false);
            addFormNotice(_t("Console is not to be shown on startup"));
        } else if (_t("Force garbage collection").equals(_action)) {
            Runtime.getRuntime().gc();
            addFormNotice(_t("Full garbage collection requested"));
        } else if (_t("Show systray icon").equals(_action)) {
            changeSystray(true);
        } else if (_t("Hide systray icon").equals(_action)) {
            changeSystray(false);
        } else {
            //addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
        }
    }

    /**
     *  Does not necessarily exist.
     *
     *  @return non-null, doesn't necessarily exist
     *  @since 0.9.1, public since 0.9.27, moved from LogsHelper in 0.9.33
     */
    public static File wrapperLogFile(I2PAppContext ctx) {
        File f = null;
        if (ctx.hasWrapper()) {
            String wv = System.getProperty("wrapper.version");
            if (wv != null && VersionComparator.comp(wv, LOCATION_AVAILABLE) >= 0) {
                try {
                   f = WrapperManager.getWrapperLogFile();
                } catch (Throwable t) {}
            }
        }
        if (f == null || !f.exists()) {
            // RouterLaunch puts the location here if no wrapper
            String path = System.getProperty("wrapper.logfile");
            if (path != null) {
                f = new File(path);
            } else {
                // look in new and old places
                f = new File(System.getProperty("java.io.tmpdir"), "wrapper.log");
                if (!f.exists())
                    f = new File(ctx.getBaseDir(), "wrapper.log");
            }
        }
        return f;
    }

    private void installService() {
        try {
            Runtime.getRuntime().exec("install_i2p_service_winnt.bat");
            addFormNotice(_t("Installed I2P+ system service"));
        } catch (IOException ioe) {
            addFormError(_t("Warning: unable to install the service") + " - " + ioe.getLocalizedMessage());
        }
    }

    private void enableService() {
        try {
//            Runtime.getRuntime().exec("install_i2p_service_winnt.bat");
            Runtime.getRuntime().exec("EnableI2P+Service.bat");
//            addFormNotice(_t("Service installed"));
            addFormNotice(_t("Enabled I2P+ service autostart at system startup"));
        } catch (IOException ioe) {
//            addFormError(_t("Warning: unable to install the service") + " - " + ioe.getLocalizedMessage());
            addFormError(_t("Warning: could not set the I2P+ service to autostart") + " - " + ioe.getLocalizedMessage());
        }
    }

    private void disableService() {
        try {
//            Runtime.getRuntime().exec("uninstall_i2p_service_winnt.bat");
            Runtime.getRuntime().exec("DisableI2P+Service.bat");
//            addFormNotice(_t("Service removed"));
            addFormNotice(_t("Disabled I2P+ Service autostart at system startup"));
        } catch (IOException ioe) {
//            addFormError(_t("Warning: unable to remove the service") + " - " + ioe.getLocalizedMessage());
            addFormError(_t("Warning: could not set the I2P+ service to manual start") + " - " + ioe.getLocalizedMessage());
        }
    }

    private void browseOnStartup(boolean shouldLaunchBrowser) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        ClientAppConfig ca = null;
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig cac = clients.get(cur);
            if (UrlLauncher.class.getName().equals(cac.className)) {
                ca = cac;
                ca.disabled = !shouldLaunchBrowser;
                break;
            }
        }
        // releases <= 0.6.5 deleted the entry completely
        if (shouldLaunchBrowser && ca == null) {
            String url = _context.portMapper().getConsoleURL();
            ca = new ClientAppConfig(UrlLauncher.class.getName(), "consoleBrowser",
                                     url, 5, false);
            clients.add(ca);
        }
        try {
            if (ca != null) {
                if (ClientAppConfig.isSplitConfig(_context))
                    ClientAppConfig.writeClientAppConfig(_context, ca);
                else
                    ClientAppConfig.writeClientAppConfig(_context, clients);
            }
            addFormNotice(_t("Configuration saved successfully"));
        } catch (IOException ioe) {
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
            addFormError(ioe.getLocalizedMessage());
        }
    }

    /**
     *  Enable/disable and start/stop systray
     *
     *  @since 0.9.26
     */
    private void changeSystray(boolean enable) {
        ClientAppManager mgr = _context.clientAppManager();
        if (mgr != null) {
            try {
                ClientApp dtg = mgr.getRegisteredApp("desktopgui");
                if (dtg != null) {
                    if (enable) {
                        if (dtg.getState() == ClientAppState.STOPPED) {
                            dtg.startup();
                            addFormNotice(_t("Enabled system tray"));
                        }
                    } else {
                        if (dtg.getState() == ClientAppState.RUNNING) {
                            dtg.shutdown(null);
                            addFormNotice(_t("Disabled system tray"));
                        }
                    }
                } else if (enable) {
                    // already set to true, GraphicsEnvironment initialized, can't change it now
                    if (Boolean.parseBoolean(System.getProperty("java.awt.headless"))) {
                        addFormError(_t("Restart required to take effect"));
                    } else {
                        dtg = new net.i2p.desktopgui.Main(_context, mgr, null);
                        dtg.startup();
                        addFormNotice(_t("Enabled system tray"));
                    }
                }
            } catch (Throwable t) {
                if (enable)
                    addFormError(_t("Failed to start systray") + ": " + t);
                else
                    addFormError(_t("Failed to stop systray") + ": " + t);
            }
        }

        boolean saved = _context.router().saveConfig(RouterConsoleRunner.PROP_DTG_ENABLED, Boolean.toString(enable));
        if (saved)
            addFormNotice(_t("Configuration saved successfully"));
        else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
    }
}
