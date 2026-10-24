package net.i2p.router.update;

import static net.i2p.update.UpdateType.*;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 * Also looks for unsigned updates.
 * <p>
 * Runs forever on instantiation, can't be stopped.
 *
 * @since 0.9.4 moved from {@link NewsFetcher}
 */
class NewsTimerTask extends SimpleTimer2.TimedEvent {
    /** The router context. */
    private final RouterContext _context;
    /** The log for the news task. */
    private final Log _log;
    /** The update manager fetching the news. */
    private final ConsoleUpdateManager _mgr;
    /** True for the first run after startup. */
    private volatile boolean _firstRun = true;

    /** Delay before the first run. */
    private static final long INITIAL_DELAY = 5 * (long) 60 * 1000;
    /** Delay before the first run on a new install. */
    private static final long NEW_INSTALL_DELAY = 25 * (long) 60 * 1000;
    /** Delay between the runs. */
    private static final long RUN_DELAY = 10 * (long) 60 * 1000;

    /**
     * Schedules the recurring news check and runs the first one now.
     * @param ctx the router context, whose simple timer and random source schedule the recurring check
     * @param mgr the update manager consulted to decide whether a fetch or an install is warranted
     */
    public NewsTimerTask(RouterContext ctx, ConsoleUpdateManager mgr) {
        super(ctx.simpleTimer2());
        _context = ctx;
        _log = ctx.logManager().getLog(NewsTimerTask.class);
        _mgr = mgr;
        long installed = ctx.getProperty("router.firstInstalled", 0L);
        boolean isNew = (ctx.clock().now() - installed) < 30*60*1000L;
        long delay = isNew ? NEW_INSTALL_DELAY : INITIAL_DELAY;
        delay += _context.random().nextLong(INITIAL_DELAY);
        if (_log.shouldInfo())
            _log.info("Scheduling first news check in " + DataHelper.formatDuration(delay));
        schedule(delay);
        // UpdateManager calls NewsFetcher to check the existing news at startup
    }

    /**
     * Re-arms the check, fetching news or resuming an install when warranted.
     */
    public void timeReached() {
        schedule(RUN_DELAY);
        if (shouldFetchNews()) {
            Thread t = new Fetcher();
            t.start();
        } else if (_firstRun &&
                   _mgr.shouldInstall() &&
                   !_mgr.isCheckInProgress() && !_mgr.isUpdateInProgress()) {
            // A prior update check detected a new version but we shut down before
            // it downloaded and the 36h news-delay hasn't elapsed; try again now.
            // TODO unsigned too?
            _mgr.update(ROUTER_SIGNED);
        }
        _firstRun = false;
    }

    /**
     * Whether to fetch the news now.
     *
     * @return true when it is time to fetch
     */
    private boolean shouldFetchNews() {
        if (_context.router().gracefulShutdownInProgress())
            return false;
        if (_mgr.isCheckInProgress() || _mgr.isUpdateInProgress())
            return false;
        long lastFetch = NewsHelper.lastChecked(_context);
        String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY,
                                           ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY);
        try {
            long ms = Long.parseLong(freq);
            if (ms <= 0)
                return false;

            if (lastFetch + ms < _context.clock().now()) {
                return true;
            } else {
                if (_log.shouldDebug())
                    _log.debug("Last fetched " + DataHelper.formatDuration(_context.clock().now() - lastFetch) + " ago");
                return false;
            }
        } catch (NumberFormatException nfe) {
            if (_log.shouldError())
                _log.error("Invalid refresh frequency: " + freq);
            return false;
        }
    }

    /** Fetch news updates from the configured feed. */
    private void fetchNews() {
        _mgr.checkAvailable(NEWS, (long) 60*1000);
    }

    /**
     * Whether to fetch unsigned news.
     *
     * @return true when unsigned news is allowed
     */
    private boolean shouldFetchUnsigned() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
        return url != null && !url.isEmpty() &&
               _context.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED) &&
               !NewsHelper.dontInstall(_context);
    }

    /**
     * Whether dev SU3 fetching is enabled.
     *
     * @return true if a dev SU3 URL is set, the dev SU3 update property is
     *         enabled, and news installation is not disabled
     * @since 0.9.20
     */
    private boolean shouldFetchDevSU3() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_DEV_SU3_URL);
        return url != null && !url.isEmpty() &&
               _context.getBooleanProperty(ConfigUpdateHandler.PROP_UPDATE_DEV_SU3) &&
               !NewsHelper.dontInstall(_context);
    }

    /**
     * Don't clog the scheduler when fetching the news.
     *
     * @since 0.9.9
     */
    private class Fetcher extends I2PAppThread {
        /**
         * Creates a daemon thread named News Fetcher.
         */
        public Fetcher() {
            super("News Fetcher");
            setDaemon(true);
        }

        /**
         * Fetches the news, then kicks off a devSU3 check if one is due.
         */
        public void run() {
            // blocking
            fetchNews();
            if (shouldFetchDevSU3()) {
                // give it a sec for the download to kick in, if it's going to
                try { Thread.sleep((long) 5*1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
                if (!_mgr.isCheckInProgress() && !_mgr.isUpdateInProgress())
                    // nonblocking
                    _mgr.check(ROUTER_DEV_SU3);
            }
            if (shouldFetchUnsigned()) {
                // give it a sec for the download to kick in, if it's going to
                try { Thread.sleep((long) 5*1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
                if (!_mgr.isCheckInProgress() && !_mgr.isUpdateInProgress())
                    // nonblocking
                    _mgr.check(ROUTER_UNSIGNED);
            }
        }
    }
}
