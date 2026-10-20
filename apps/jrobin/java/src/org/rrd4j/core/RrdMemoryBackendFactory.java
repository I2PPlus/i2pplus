package org.rrd4j.core;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory class which creates actual {@link org.rrd4j.core.RrdMemoryBackend} objects. Rrd4j's
 * support for in-memory RRDs is still experimental. You should know that all active
 * RrdMemoryBackend objects are held in memory, each backend object stores RRD data in one big byte
 * array. This implementation is therefore quite basic and memory hungry but runs very fast.
 *
 * <p>Calling {@link org.rrd4j.core.RrdDb#close() close()} on RrdDb objects does not release any
 * memory at all (RRD data must be available for the next <code>new RrdDb(path)</code> call. To
 * release allocated memory, you'll have to call {@link #delete(java.lang.String) delete(path)}
 * method of this class.
 */
@RrdBackendAnnotation(name = "MEMORY", shouldValidateHeader = false)
public class RrdMemoryBackendFactory extends RrdBackendFactory {

    /**
     * A factory holding no open backends; the map starts empty and is filled by {@link #open}
     * as RRDs are opened and released by {@link #delete}.
     */
    public RrdMemoryBackendFactory() {}

    /**
     * Open RRD buffers by RRD ID, held until delete() removes them.
     */
    protected final Map<String, AtomicReference<ByteBuffer>> backends = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>Creates RrdMemoryBackend object.
     */
    protected RrdBackend open(String id, boolean readOnly) {
        AtomicReference<ByteBuffer> refbb =
                backends.computeIfAbsent(id, i -> new AtomicReference<>());
        return new RrdMemoryBackend(id, refbb);
    }

    /**
     * Accepts only URIs whose scheme matches this backend's memory scheme.
     */
    @Override
    public boolean canStore(URI uri) {
        return uri.getScheme().equals(getScheme());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Method to determine if a memory storage with the given ID already exists.
     */
    protected boolean exists(String id) {
        return backends.containsKey(id);
    }

    /**
     * Removes the storage with the given ID from the memory.
     *
     * @param id Storage ID, the key this factory registered the storage under
     * @return true if a storage was removed, false if the ID was unknown
     */
    public boolean delete(String id) {
        if (backends.containsKey(id)) {
            backends.remove(id);
            return true;
        } else {
            return false;
        }
    }
}
