package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;

/**
 * This class extends StoreJob to fire off a FloodfillVerifyStoreJob after a successful store.
 *
 * Stores through this class always request a reply.
 */
class FloodfillStoreJob extends StoreJob {
    private static final String PROP_RI_VERIFY = "router.verifyRouterInfoStore";
    private static final long RI_VERIFY_STARTUP_TIME = 90 * 60 * 1000L;

    /** Length of Base32 key prefix for logging */
    private static final int LOG_KEY_B32_LENGTH = 8;
    /** Length of Base64 key prefix for logging */
    private static final int LOG_KEY_B64_LENGTH = 6;

    private final FloodfillNetworkDatabaseFacade _facade;

    /**
     * Create a new FloodfillStoreJob to send data to floodfills.
     */
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade,
                             Hash key, DatabaseEntry data, Job onSuccess, Job onFailure, long timeoutMs) {
        this(context, facade, key, data, onSuccess, onFailure, timeoutMs, null);
    }

    /**
     * Create a new FloodfillStoreJob to send data to floodfills.
     *
     * @param toSkip set of peer hashes to skip (e.g., already have the data), may be null
     */
    public FloodfillStoreJob(RouterContext context, FloodfillNetworkDatabaseFacade facade,
                             Hash key, DatabaseEntry data, Job onSuccess, Job onFailure,
                             long timeoutMs, Set<Hash> toSkip) {
        super(context, facade, key, data, onSuccess, onFailure, timeoutMs, toSkip);
        _facade = facade;
    }

    @Override
    protected int getParallelization() {return 1;}

    @Override
    protected int getRedundancy() {return 1;}

    @Override
    protected void succeed() {
        super.succeed();

        final Hash key = _state.getTarget();
        final String keyB32 = key.toBase32().substring(0, LOG_KEY_B32_LENGTH);
        final String keyB64 = key.toBase64().substring(0, LOG_KEY_B64_LENGTH);
        final boolean shouldLog = _log.shouldInfo();

        if (shouldSkipVerify(key, keyB64, shouldLog)) {return;}

        RouterContext ctx = getContext();

        if (shouldSkipVerifyForStartup(ctx, keyB64)) {return;}

        DatabaseEntry data = _state.getData();
        if (data == null) {
            if (shouldLog) {
                _log.info("No data available for verify after store of [" + keyB64 + "]");
            }
            return;
        }

        final int type = data.getType();
        final boolean isRouterInfo = type == DatabaseEntry.KEY_TYPE_ROUTERINFO;

        // Skip verify if router info and condition met
        if (isRouterInfo && !ctx.getBooleanProperty(PROP_RI_VERIFY)
            && ctx.router().getUptime() > RI_VERIFY_STARTUP_TIME) {
            _facade.routerInfoPublishSuccessful();
            return;
        }

        long published = getPublishedTimestamp(data);
        Hash sentTo = _state.getSuccessful(); // should always have exactly one
        Hash client = getClientHash(data, key);

        if (sentTo == null || client == null) {
            if (shouldLog) {
                _log.info("Skipping verify: missing sentTo or client hash for [" + keyB64 + "]");
            }
            return;
        }

        Set<Hash> attempted = _state.getAttempted();
        Job verifyJob = new FloodfillVerifyStoreJob(ctx, key, client, published, type, sentTo, attempted, _facade);

        if (shouldLog) {_log.info("Succeeded sending key [" + keyB32 + "] -> Queueing Verify Store Job...");}

        ctx.jobQueue().addJob(verifyJob);
    }

    /**
     * Check if verify should be skipped due to existing verify or shutdown.
     */
    private boolean shouldSkipVerify(Hash key, String keyB64, boolean shouldLog) {
        if (_facade.isVerifyInProgress(key)) {
            if (shouldLog) {_log.info("Skipping verify, one already in progress for: [" + keyB64 + "]");}
            return true;
        }

        RouterContext ctx = getContext();
        if (ctx.router().gracefulShutdownInProgress()) {
            if (shouldLog) {_log.info("Skipping verify of [" + keyB64 + "] -> Shutdown/Restart in progress...");}
            return true;
        }

        return false;
    }

    /**
     * Check if verify should be skipped due to startup verification delay.
     */
    private boolean shouldSkipVerifyForStartup(RouterContext ctx, String keyB64) {
        if (!ctx.getBooleanProperty(PROP_RI_VERIFY) && ctx.router().getUptime() > RI_VERIFY_STARTUP_TIME) {
            if (_log.shouldInfo()) {_log.info("Skipping verify of [" + keyB64 + "] -> Startup period not yet complete.");}
            return true;
        }
        return false;
    }

    /**
     * Get the published timestamp from the data.
     */
    private long getPublishedTimestamp(DatabaseEntry data) {
        if (data instanceof LeaseSet2) {return ((LeaseSet2) data).getPublished();}
        else {return data.getDate();}
    }

    /**
     * Get the client hash from the data or key if not available.
     */
    private Hash getClientHash(DatabaseEntry data, Hash key) {
        if (data.getType() == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
            LeaseSet ls = (LeaseSet) data;
            return ls.getDestination().calculateHash();
        }
        return key;
    }

    @Override
    public String getName() {return "Verify Floodfill Store";}

}