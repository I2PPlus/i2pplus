package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;

/**
 * Interface for database storage operations in the network database.
 * <p>
 * Provides methods for storing, retrieving, and managing database entries
 * including RouterInfo and LeaseSet objects with optional persistence control.
 */
public interface DataStore {
    /**
     * Check if the data store has been initialized.
     *
     * @return true if the store is ready for operations
     */
    public boolean isInitialized();
    /**
     * Check if the given key exists in the data store.
     *
     * @param key the hash key to look up
     * @return true if the key is known and stored
     */
    public boolean isKnown(Hash key);
    /**
     * Retrieve the database entry for the given key.
     *
     * @param key the hash key to look up
     * @return the database entry, or null if not found
     */
    public DatabaseEntry get(Hash key);
    /**
     * Retrieve the database entry for the given key with optional persistence control.
     *
     * @param key the hash key to look up
     * @param persist if true, keep the entry in persistent storage
     * @return the database entry, or null if not found
     */
    public DatabaseEntry get(Hash key, boolean persist);
    /**
     * Store a database entry with the given key.
     *
     * @param key the hash key to store under
     * @param data the database entry to store
     * @return true if the store was successful
     */
    public boolean put(Hash key, DatabaseEntry data);
    /**
     * Store a database entry with the given key and persistence control.
     *
     * @param key the hash key to store under
     * @param data the database entry to store
     * @param persist if true, keep the entry in persistent storage
     * @return true if the store was successful
     */
    public boolean put(Hash key, DatabaseEntry data, boolean persist);

    /*
     *  Unconditionally store, bypass all newer/older checks
     *
     *  @return success
     *  @param key non-null
     *  @param data non-null
     *  @since 0.9.64
     */
    public boolean forcePut(Hash key, DatabaseEntry data);

    /**
     * Remove the entry for the given key.
     *
     * @param key the hash key to remove
     * @return the removed database entry, or null if not found
     */
    public DatabaseEntry remove(Hash key);
    /**
     * Remove the entry for the given key with optional persistence control.
     *
     * @param key the hash key to remove
     * @param persist if true, also remove from persistent storage
     * @return the removed database entry, or null if not found
     */
    public DatabaseEntry remove(Hash key, boolean persist);
    /**
     * Get all keys stored in the data store.
     *
     * @return set of all hash keys
     */
    public Set<Hash> getKeys();
    /** @since 0.8.3 */
    public Collection<DatabaseEntry> getEntries();
    /** @since 0.8.3 */
    public Set<Map.Entry<Hash, DatabaseEntry>> getMapEntries();
    /**
     * Stop the data store and release resources.
     */
    public void stop();
    /**
     * Rescan the storage for any changes.
     */
    public void rescan();
    /**
     * Count the number of LeaseSet entries in the store.
     *
     * @return the number of LeaseSets stored
     */
    public int countLeaseSets();

    /**
     *  @return total size (RI and LS)
     *  @since 0.8.8
     */
    public int size();
}
