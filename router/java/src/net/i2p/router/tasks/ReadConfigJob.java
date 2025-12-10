package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simply read the router config periodically,
 * so that the user may make config changes externally.
 * This isn't advertised as a feature,
 * but it could be used, for example, to change bandwidth limits
 * at certain times of day.
 *
 * Unfortunately it will also read the file back in every time the
 * router writes it.
 *
 * We must keep this enabled, as it's the only way for people
 * to set routerconsole.advanced=true without restarting.
 */
public class ReadConfigJob extends JobImpl {
//    private final static long DELAY = 30*1000; // reread every 30 seconds
    private final static long DELAY = 60*1000; // reread every minute
    private volatile long _lastRead;
    private static final String PROP_ADVANCED = "routerconsole.advanced";

    /**
     * Create a new configuration reader job.
     * 
     * @param ctx router context for accessing configuration and clock
     */
    public ReadConfigJob(RouterContext ctx) {
        super(ctx);
        _lastRead = ctx.clock().now();
    }

    /**
     * Check if advanced console mode is enabled.
     * 
     * @return true if routerconsole.advanced property is true
     */
    public boolean isAdvanced() {
        return getContext().getBooleanProperty(PROP_ADVANCED);
    }

    /**
     * Get the name of this job.
     * 
     * @return job name for logging and identification
     */
    public String getName() { return "Read Router Configuration"; }

    /**
     * Check for and reload router configuration if file has changed.
     * 
     * This job runs periodically to detect external configuration changes.
     * If the router.config file has been modified since last check, it will
     * be reloaded and the last read timestamp updated.
     * 
     * The check interval varies based on console mode:
     * <ul>
     *   <li>Normal mode: every 60 seconds</li>
     *   <li>Advanced mode: every 90 seconds</li>
     * </ul>
     * 
     * Note: This will also trigger when the router itself writes the config file,
     * but that's acceptable behavior.
     */
    public void runJob() {
        File configFile = new File(getContext().router().getConfigFilename());
        if (shouldReread(configFile)) {
            getContext().router().readConfig();
            _lastRead = getContext().clock().now();
            Log log = getContext().logManager().getLog(ReadConfigJob.class);
            if (log.shouldDebug())
                log.debug("Reloaded " + configFile);
        }
        if (!isAdvanced())
            requeue(DELAY);
        else
            requeue(DELAY / 2 * 3); // 90 seconds
    }

    private boolean shouldReread(File configFile) {
        // lastModified() returns 0 if not found
        //if (!configFile.exists()) return false;
        return configFile.lastModified() > _lastRead;
    }
}
