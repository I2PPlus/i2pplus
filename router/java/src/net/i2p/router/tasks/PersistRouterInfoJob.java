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
import java.io.FileOutputStream;
import java.io.IOException;

import net.i2p.data.DataFormatException;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.startup.CreateRouterInfoJob;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 * Router information persistence and synchronization job.
 * 
 * This job is responsible for persisting the router's RouterInfo
 * to disk whenever it changes. The RouterInfo contains critical
 * router identity, capabilities, addresses, and statistics that
 * must be preserved across restarts and shared with the network.
 * 
 * <strong>RouterInfo Contents:</strong>
 * <ul>
 *   <li>Router identity (public keys, certificates)</li>
 *   <li>Network addresses and transport endpoints</li>
 *   <li>Router capabilities and supported versions</li>
 *   <li>Performance statistics and bandwidth limits</li>
 *   <li>Current router status and options</li>
 * </ul>
 * 
 * <strong>Persistence Process:</strong>
 * <ul>
 *   <li>Retrieves current RouterInfo from router</li>
 *   <li>Writes to router.info file in router directory</li>
 *   <li>Uses synchronized access to prevent corruption</li>
 *   <li>Employs atomic file writing for safety</li>
 *   <li>Handles errors gracefully with detailed logging</li>
 * </ul>
 * 
 * <strong>File Safety:</strong>
 * <ul>
 *   <li>Uses SecureFileOutputStream for atomic writes</li>
 *   <li>Synchronized on routerInfoFileLock for thread safety</li>
 *   <li>Prevents partial writes and corruption</li>
 *   <li>Ensures consistent state across restarts</li>
 * </ul>
 * 
 * This job is triggered automatically whenever RouterInfo is updated
 * due to address changes, capability changes, or periodic
 * refreshes. The persisted file is used for router startup
 * and network database publication.
 *
 * @since 0.8.12 moved from Router.java
 */
public class PersistRouterInfoJob extends JobImpl {
    /**
     * Create a new router info persistence job.
     * 
     * @param ctx router context for accessing router services
     * @since 0.8.12 moved from Router.java
     */
    public PersistRouterInfoJob(RouterContext ctx) {
        super(ctx);
    }

    /**
     * Get the name of this job.
     * 
     * @return job name for logging and identification
     */
    public String getName() { return "Store Updated Router Information"; }

    /**
     * Save the current router information to disk.
     * 
     * This job writes the router's RouterInfo to the router.info file
     * in the router directory. It uses a synchronized block to ensure
     * thread-safe access to the router info file.
     * 
     * The file is written using SecureFileOutputStream to ensure atomic
     * writes and prevent corruption. Any errors during the write process
     * are logged but do not prevent the job from completing.
     */
    public void runJob() {
        Log _log = getContext().logManager().getLog(PersistRouterInfoJob.class);
        if (_log.shouldDebug())
            _log.debug("Saving our updated RouterInfo file to disk");

        File infoFile = new File(getContext().getRouterDir(), CreateRouterInfoJob.INFO_FILENAME);

        RouterInfo info = getContext().router().getRouterInfo();

        FileOutputStream fos = null;
        synchronized (getContext().router().routerInfoFileLock) {
            try {
                fos = new SecureFileOutputStream(infoFile);
                info.writeBytes(fos);
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding our RouterInfo", dfe);
            } catch (IOException ioe) {
                _log.error("Error saving our updated RouterInfo file", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
}
