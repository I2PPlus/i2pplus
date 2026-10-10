package com.maxmind.db;

import java.io.IOException;

/**
 * Interface for caching nodes during MaxMind database lookups.
 * Provides a way to cache frequently accessed nodes to improve performance.
 */
public interface NodeCache {

    /**
     * Interface for loading nodes on cache miss.
     */
    public interface Loader {
        /**
         * Load a node by key on cache miss.
         * @param key the node key to look up, identifying one node in the database tree
         * @return the loaded value
         * @throws IOException if the backing store cannot be read to produce the node
         */
        Object load(int key) throws IOException;
    }

    /**
     * Get a value from the cache, loading it if necessary.
     * @param key the cache key
     * @param loader the loader to use if the key is not in cache
     * @return the cached or loaded value
     * @throws IOException if loading fails
     */
    public Object get(int key, Loader loader) throws IOException;

}
