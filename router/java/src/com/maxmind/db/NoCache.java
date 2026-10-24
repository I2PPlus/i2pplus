package com.maxmind.db;

import java.io.IOException;

/**
 * A no-op cache singleton.
 */
public class NoCache implements NodeCache {

    /** The single instance, shared by every reader. */
    private static final NoCache INSTANCE = new NoCache();

    /** Utility class, not intended for instantiation. */
    private NoCache() {
    }

    @Override
    public Object get(int key, Loader loader) throws IOException {
        return loader.load(key);
    }

    /**
     * Get the singleton instance.
     * @return the NoCache singleton
     */
    public static NoCache getInstance() {
        return INSTANCE;
    }

}
