package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.networkdb.reseed.ReseedChecker;

/**
 * Abstract interface for I2P network database operations. Provides router info and lease set lookup, storage, and search functionality for peer discovery and routing.
 *
 */
public abstract class NetworkDatabaseFacade implements Service {

    /**
     * Return the RouterInfo structures for the routers closest to the given key.
     * At most maxNumRouters will be returned
     *
     * @param key The key
     * @param maxNumRouters The maximum number of routers to return
     * @param peersToIgnore Hash of routers not to include
     * @return set of router hashes closest to the key
     */
    public abstract Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore);

    /**
     *  Lookup a database entry locally.
     *
     *  @param key the key
     *  @return RouterInfo, LeaseSet, or null
     *  @since 0.8.3
     */
    public abstract DatabaseEntry lookupLocally(Hash key);

    /**
     *  Not for use without validation
     *
     *  @param key the key
     *  @return RouterInfo, LeaseSet, or null, NOT validated
     *  @since 0.9.38
     */
    public abstract DatabaseEntry lookupLocallyWithoutValidation(Hash key);

    /**
     *  Lookup a LeaseSet in the network database.
     *
     *  @param key the key
     *  @param onFindJob job to run on success
     *  @param onFailedLookupJob job to run on failure
     *  @param timeoutMs timeout in milliseconds
     */
    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs);

    /**
     *  Lookup using the client's tunnels
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.10
     */
    public abstract void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest);

    /**
     * key).
     */
    public abstract LeaseSet lookupLeaseSetLocally(Hash key);
    /**
     * timeoutMs).
     */
    public abstract void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs);
    /**
     * key).
     */
    public abstract RouterInfo lookupRouterInfoLocally(Hash key);

    /**
     *  Unconditionally lookup using the client's tunnels.
     *  No success or failed jobs, no local lookup, no checks.
     *  Use this to refresh a leaseset before expiration.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.25
     */
    public abstract void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest);

    /**
     *  Unconditionally lookup using the client's tunnels.
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @param onFindJob may be null
     *  @param onFailedLookupJob may be null
     *  @since 0.9.47
     */
    public abstract void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest);

    /**
     *  Lookup using the client's tunnels
     *  Succeeds even if LS validation fails due to unsupported sig type
     *
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.16
     */
    public abstract void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest);

    /**
     *  Lookup locally in netDB and in badDest cache
     *  Succeeds even if LS validation failed due to unsupported sig type
     *
     *  @since 0.9.16
     */
    public abstract Destination lookupDestinationLocally(Hash key);

    /**
     * @return the leaseSet if another leaseSet already existed at that key
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException;

    /**
     * Record access to a LeaseSet for refresh tracking.
     * Only tracks if we have tunnels built AND there's a hostname.
     *
     * @since 0.9.67
     */
    public abstract void accessLeaseSet(Hash key);

    /**
     * Remove a LeaseSet from refresh tracking.
     * Call this after HostChecker completes to avoid unnecessary refreshes.
     *
     * @since 0.9.67
     */
    public abstract void removeLeaseSetFromTracking(Hash key);

    /**
     * @return the routerInfo if another router already existed at that key
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public abstract RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException;

    /**
     *  Store a DatabaseEntry in the network database.
     *
     *  @param key the key
     *  @param entry the entry to store
     *  @return the old entry if it already existed at that key
     *  @throws IllegalArgumentException if the data is not valid
     *  @since 0.9.16
     */
    public DatabaseEntry store(Hash key, DatabaseEntry entry) throws IllegalArgumentException {
        if (!entry.isLeaseSet()) {
            return store(key, (RouterInfo) entry);
        } else {
            return store(key, (LeaseSet) entry);
        }
    }

    /**
     *  Publish a RouterInfo to the network database.
     *
     *  @param localRouterInfo the RouterInfo to publish
     *  @throws IllegalArgumentException if the local router is not valid
     */
    public abstract void publish(RouterInfo localRouterInfo) throws IllegalArgumentException;

    /**
     *  Publish a LeaseSet to the network database.
     *
     *  @param localLeaseSet the LeaseSet to publish
     */
    public abstract void publish(LeaseSet localLeaseSet);

    /**
     *  Unpublish a LeaseSet from the network database.
     *
     *  @param localLeaseSet the LeaseSet to unpublish
     */
    public abstract void unpublish(LeaseSet localLeaseSet);

    /**
     *  Mark a database entry as failed.
     *
     *  @param dbEntry the key of the entry to fail
     */
    public abstract void fail(Hash dbEntry);

    /**
     *  The last time we successfully published our RI.
     *
     *  @return the timestamp, or 0
     *  @since 0.9.9
     */
    public long getLastRouterInfoPublishTime() {return 0;}

    /**
     *  Get all known router hashes.
     *
     *  @return set of router hashes
     */
    public abstract Set<Hash> getAllRouters();

    /**
     *  Get the number of known routers.
     *
     *  @return the count
     */
    public int getKnownRouters() {return 0;}

    /**
     *  Get the number of known LeaseSets.
     *
     *  @return the count
     */
    public int getKnownLeaseSets() {return 0;}

    /**
     *  Is the network database initialized?
     *
     *  @return true if initialized
     */
    public boolean isInitialized() {return true;}

    /**
     *  Rescan the network database.
     */
    public void rescan() {}

    /** Debug only - all user info moved to NetDbRenderer in router console */
    public void renderStatusHTML(Writer out) throws IOException {}
    /**
     *  Get all known LeaseSets for display.
     *
     *  @return set of LeaseSets, or empty
     */
    public Set<LeaseSet> getLeases() {return Collections.emptySet();}
    /** public for NetDbRenderer in routerconsole */
    public Set<RouterInfo> getRouters() {return Collections.emptySet();}
    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    /**
     * @return the client leases
     */
    public Set<LeaseSet> getClientLeases() {return Collections.emptySet();}
    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    /**
     * @return the published leases
     */
    public Set<LeaseSet> getPublishedLeases() {return Collections.emptySet();}
    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    /**
     * @return the unpublished leases
     */
    public Set<LeaseSet> getUnpublishedLeases() {return Collections.emptySet();}
    /** public for NetDbRenderer in routerconsole */
    /* @since 0.9.64+ */
    /**
     * @return the floodfill leases
     */
    public Set<LeaseSet> getFloodfillLeases() {return Collections.emptySet();}
    /** @since 0.9 */
    public ReseedChecker reseedChecker() {return null;}

    /**
     *  For convenience, so users don't have to cast to FNDF, and unit tests using
     *  Dummy NDF will work.
     *
     *  @return false; FNDF overrides to return actual setting
     *  @since IPv6
     */
    public boolean floodfillEnabled() {return false;}

    /**
     *  Is it permanently negative cached?
     *
     *  @param key only for Destinations; for RouterIdentities, see Banlist
     *  @since 0.9.16
     *  @return whether negative cached forever
     */
    public boolean isNegativeCachedForever(Hash key) {return false;}

    /**
     *  @param spk unblinded key
     *  @return BlindData or null
     *  @since 0.9.40
     */
    public BlindData getBlindData(SigningPublicKey spk) {
        return null;
    }

    /**
     *  @param bd new BlindData to put in the cache
     *  @since 0.9.40
     */
    public void setBlindData(BlindData bd) {}

    /**
     *  For console ConfigKeyringHelper
     *
     *  @since 0.9.41
     * @return the blind data
     */
    public List<BlindData> getBlindData() {
        return null;
    }

    /**
     *  For console ConfigKeyringHelper
     *
     *  @return true if removed
     *  @since 0.9.41
     */
    public boolean removeBlindData(SigningPublicKey spk) {
        return false;
    }

    /**
     *  Notify the netDB that the routing key changed at midnight UTC
     *
     *  @since 0.9.50
     */
    public void routingKeyChanged() {}

    /**
     *  Trigger a retroactive purge sweep of the NetDb.
     *  Bans and removes all routers matching enabled LU/XG/custom-cap bans.
     *
     *  @since 0.9.70
     */
    public void purgeMatchingRouters() {}
}
