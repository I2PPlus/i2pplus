package org.rrd4j.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Backend which is used to store RRD data to ordinary disk files using java.nio.* package. This is
 * the default backend engine.
 *
 * <p>Writes land directly in the mapped file buffer. A caller that wants a group of writes to
 * become visible together can stage them with {@link #bufferStatFile} and publish them as one batch
 * with {@link #flushBufferedStatFiles()}. Staging is heap-only and bounded by a budget taken from
 * the free heap: what is staged is the delta being written, not the file, so a batch costs the size
 * of the rows it touches, and the budget is what keeps a flush that never runs from growing into an
 * allocation failure. When the budget cannot hold the next block, this backend publishes what it
 * already holds and, if that is still not enough, publishes the block on its own.
 */
public class RrdNioBackend extends ByteBufferBackend implements RrdFileBackend {

    /**
     * Method cleaning a mapped buffer without unsafe, the preferred
     * path on Java 8.
     */
    private static final Method cleanerMethod;
    /** Method invoked on the cleaner, old JDKs. */
    private static final Method cleanMethod;
    /**
     * Method cleaning a mapped buffer via unsafe, for newer JDKs. The
     * call is terminally deprecated and a live mapping never blocks the
     * file on POSIX, so it is resolved only where a mapping does hold
     * the file open, which is Windows.
     */
    private static final Method invokeCleaner;
    /** The unsafe object the cleaner runs on, if any. */
    private static final Object unsafe;

    static {
        Method cleanerMethodTemp = null;
        Method cleanMethodTemp = null;
        Method invokeCleanerTemp = null;
        Object unsafeTemp = null;

        // Try DirectBuffer.cleaner() path (works on Java 8, may fail on Java 25
        // if sun.nio.ch is not exported)
        try {
            Class<?> directBufferClass =
                    RrdRandomAccessFileBackend.class
                            .getClassLoader()
                            .loadClass("sun.nio.ch.DirectBuffer");
            cleanerMethodTemp = directBufferClass.getMethod("cleaner");
            cleanerMethodTemp.setAccessible(true);
            // Try sun.misc.Cleaner.clean() (Java 8)
            try {
                Class<?> cleanerClass =
                        RrdNioBackend.class.getClassLoader().loadClass("sun.misc.Cleaner");
                cleanMethodTemp = cleanerClass.getMethod("clean");
                cleanMethodTemp.setAccessible(true);
            } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
                // Java 9+: sun.misc.Cleaner removed; clean() resolved lazily
                cleanMethodTemp = null;
            }
        } catch (Exception e) {
            // Java 25: sun.nio.ch not exported, or DirectBuffer.cleaner() removed
            cleanerMethodTemp = null;
            cleanMethodTemp = null;
        }

        // Try Unsafe.invokeCleaner path (Java 9+, warns on Java 25) - Windows only
        if (System.getProperty("os.name", "").contains("Windows")) {
            try {
                Field singletonInstanceField =
                        RrdRandomAccessFileBackend.class
                                .getClassLoader()
                                .loadClass("sun.misc.Unsafe")
                                .getDeclaredField("theUnsafe");
                singletonInstanceField.setAccessible(true);
                unsafeTemp = singletonInstanceField.get(null);
                invokeCleanerTemp =
                        unsafeTemp.getClass().getMethod("invokeCleaner", ByteBuffer.class);
            } catch (NoSuchFieldException
                    | SecurityException
                    | IllegalArgumentException
                    | IllegalAccessException
                    | NoSuchMethodException
                    | ClassNotFoundException e) {
                invokeCleanerTemp = null;
                unsafeTemp = null;
            }
        }

        cleanerMethod = cleanerMethodTemp;
        cleanMethod = cleanMethodTemp;
        invokeCleaner = invokeCleanerTemp;
        unsafe = unsafeTemp;
    }

    /** The mapped file buffer. */
    private MappedByteBuffer byteBuffer;
    /** The channel of the rrd file. */
    private final FileChannel file;
    /** True if the file is opened read only. */
    private final boolean readOnly;

    /** The running periodic sync task, if any. */
    private ScheduledFuture<?> syncRunnableHandle = null;

    /**
     * Serializes the JVM-wide staging accounting.
     *
     * <p>Staged blocks are heap copies, so the only state shared between backends is the byte
     * counter the staging budget is checked against. The lock covers that arithmetic and nothing
     * else; no I/O happens under it.
     */
    private static final Object STAGING_LOCK = new Object();

    /**
     * Bytes staged on the heap across every backend in this JVM.
     *
     * <p>Guarded by {@link #STAGING_LOCK}.
     */
    private static long _stagedBytes;

    /**
     * Staging budget set by the embedder, or 0 to size it from the free heap on every check.
     *
     * <p>Guarded by {@link #STAGING_LOCK}.
     */
    private static long _budgetOverrideBytes;

    /** Ceiling on heap staging however much heap is free, in bytes. */
    static final int MAX_STAGING_BYTES = 8 * 1024 * 1024;

    /** The free heap is divided by this to size the staging budget. */
    static final int STAGING_HEAP_DIVISOR = 16;

    /**
     * Free heap under this many bytes means no staging at all: each write is published straight to
     * the mapping instead of being held for a batch.
     */
    static final int MIN_FREE_HEAP_BYTES = 64 * 1024;

    /**
     * This backend's staged blocks, in the order they were staged.
     *
     * <p>Touched only from the synchronized methods of this instance; the byte accounting that
     * spans instances goes through {@link #STAGING_LOCK}.
     */
    private final List<StatFileSegment> stagedBlocks = new ArrayList<>(4);

    /**
     * One block of stat data held on the heap for a later batch publish.
     */
    private static class StatFileSegment {
        /** The staged bytes, released once the block has been published. */
        byte[] data;
        /** Size of the block in bytes; stays readable after the data is released. */
        final int length;
        /** Where the block belongs in the mapped (RRD) file. */
        final long targetFileOffset;

        /**
         * Records one block of the deferred write.
         *
         * @param data the bytes to publish later
         * @param targetFileOffset where the data belongs in the rrd file
         */
        StatFileSegment(byte[] data, long targetFileOffset) {
            this.data = data;
            this.length = data.length;
            this.targetFileOffset = targetFileOffset;
        }

        /** Hands back the heap the block was using, keeping its length for the accounting. */
        void release() {
            data = null;
        }
    }

    /**
     * Creates RrdFileBackend object for the given file path, backed by java.nio.* classes.
     *
     * @param path Path to a file
     * @param readOnly True, if file should be open in read-only mode. False otherwise
     * @param threadPool Sync thread pool; can be null
     * @param syncPeriod Sync period in seconds
     * @throws IOException On I/O error
     */
    protected RrdNioBackend(
            String path, boolean readOnly, RrdSyncThreadPool threadPool, int syncPeriod)
            throws IOException {
        super(path);
        Set<StandardOpenOption> options = new HashSet<>(3);
        options.add(StandardOpenOption.READ);
        options.add(StandardOpenOption.CREATE);
        if (!readOnly) {
            options.add(StandardOpenOption.WRITE);
        }

        file = FileChannel.open(Paths.get(path), options);
        this.readOnly = readOnly;
        try {
            mapFile(file.size());
        } catch (IOException | RuntimeException ex) {
            file.close();
            super.close();
            throw ex;
        }

        try {
            if (!readOnly && threadPool != null) {
                // The task must not let anything escape. scheduleWithFixedDelay
                // cancels every future execution as soon as the task throws, so a
                // single failure would silently and permanently end all later
                // ones: the main file would keep its last pre-flush contents and
                // every reader would serve stale data with no error anywhere.
                // flushBufferedStatFiles() publishes staged blocks into the mapping
                // and forces it, and it reports its own trouble rather than
                // throwing, so the only thing that can reach here is a bug; the
                // guard keeps the schedule alive for it either way.
                final String rrdPath = getPath();
                final long[] flushes = { 0 };
                Runnable syncRunnable =
                        () -> {
                            try {
                                flushBufferedStatFiles();
                                // Heartbeat: proves the scheduled task is still
                                // being rescheduled, which a frozen .jrb mtime
                                // cannot distinguish from a task that stopped.
                                long done = ++flushes[0];
                                if ((done % 10) == 0) {
                                    RrdLog.debug("flush ok for " + rrdPath + " (count=" + done + ")");
                                }
                            } catch (Throwable t) {
                                RrdLog.error(
                                        "RRD4J flush failed for " + rrdPath
                                                + "; the flush schedule stays active and will retry",
                                        t);
                            }
                        };
                syncRunnableHandle =
                        threadPool.scheduleWithFixedDelay(
                                syncRunnable, syncPeriod, syncPeriod, TimeUnit.SECONDS);
            }
        } catch (RuntimeException rte) {
            releaseStaged();
            unmapFile();
            file.close();
            super.close();
            throw rte;
        }
    }

    /**
     * Maps the file into memory.
     *
     * @param length the length to map
     * @throws IOException if the file cannot be mapped
     */
    private void mapFile(long length) throws IOException {
        if (length > 0) {
            FileChannel.MapMode mapMode =
                    readOnly ? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE;
            byteBuffer = file.map(mapMode, 0, length);
            setByteBuffer(byteBuffer);
        }
    }

    /** Cleans and releases the mapped buffer. */
    private void unmapFile() {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            try {
                // Path 1: DirectBuffer.cleaner() -> clean() (no Unsafe)
                if (cleanerMethod != null) {
                    Object cleaner = cleanerMethod.invoke(byteBuffer);
                    if (cleaner != null) {
                        Method clean = cleanMethod;
                        if (clean == null) {
                            // Java 9+: resolve clean() from actual class
                            clean = cleaner.getClass().getMethod("clean");
                            clean.setAccessible(true);
                        }
                        clean.invoke(cleaner);
                        byteBuffer = null;
                        return;
                    }
                }
                // Path 2: Unsafe.invokeCleaner (resolved on Windows only)
                if (invokeCleaner != null) {
                    invokeCleaner.invoke(unsafe, byteBuffer);
                }
            } catch (Exception ex) {
                // Buffer will be unmapped when GC collects the MappedByteBuffer
            }
        }
        byteBuffer = null;
    }

    /**
     * {@inheritDoc} Sets length of the underlying RRD file. This method is called only once,
     * immediately after new RRD creation.
     *
     * @throws IllegalArgumentException if the length is bigger than the possible mapping position
     *                                  (2GiB).
     */
    protected synchronized void setLength(long newLength) throws IOException {
        if (newLength < 0 || newLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal offset: " + newLength);
        }

        unmapFile();
        file.truncate(newLength);
        mapFile(newLength);
    }

    /**
     * Closes the underlying RRD file, publishing anything still staged first.
     *
     * @throws IOException On I/O error
     */
    @Override
    public synchronized void close() throws IOException {
        try {
            if (!readOnly && syncRunnableHandle != null) {
                syncRunnableHandle.cancel(false);
                syncRunnableHandle = null;
            }
            flushBufferedStatFiles();
        } finally {
            releaseStaged();
            unmapFile();
            file.close();
            super.close();
        }
    }

    /**
     * Bytes staged on the heap across every backend in this JVM.
     *
     * @return the total size of the blocks waiting to be published, in bytes
     */
    static long stagedBytes() {
        synchronized (STAGING_LOCK) {
            return _stagedBytes;
        }
    }

    /**
     * Set an explicit heap staging budget, overriding the free-heap calculation.
     *
     * <p>An embedder that knows how much of its heap is its own to spend can pin the budget here
     * instead of leaving it re-read from the runtime on every stage.
     *
     * @param bytes the budget in bytes; 0 or less goes back to sizing it from the free heap
     */
    public static void setStagingBudgetBytes(long bytes) {
        synchronized (STAGING_LOCK) {
            _budgetOverrideBytes = bytes > 0 ? bytes : 0;
        }
    }

    /**
     * The staging budget in force right now.
     *
     * @return the budget in bytes; 0 means staging is off and each write is published at once
     */
    private static long budgetBytes() {
        long override;
        synchronized (STAGING_LOCK) {
            override = _budgetOverrideBytes;
        }
        return override > 0 ? override : stagingBudgetBytes(freeHeapBytes());
    }

    /**
     * Size the heap staging budget from the free heap.
     *
     * <p>Sized from what the JVM can still take rather than from how many databases are open: a
     * budget fixed by count is an allocation failure waiting on a small heap and most of the room
     * left unused on a large one. The ceiling holds a stalled flush to single-digit megabytes even
     * on a server heap, because what is staged is the delta being written, not the file.
     *
     * @param freeHeapBytes bytes the JVM can still allocate, or a negative value to disable staging
     * @return the staging budget in bytes; 0 means publish every write straight to the mapping
     */
    static long stagingBudgetBytes(long freeHeapBytes) {
        if (freeHeapBytes < MIN_FREE_HEAP_BYTES) {
            return 0;
        }
        return Math.min(MAX_STAGING_BYTES, freeHeapBytes / STAGING_HEAP_DIVISOR);
    }

    /**
     * Decide whether the staging budget can take another block as things stand.
     *
     * @param stagedBytes bytes staged across the JVM right now
     * @param incomingBytes the size of the block about to be staged
     * @param budgetBytes the staging budget, 0 to stage nothing
     * @return true if publishing has to happen before this block can be staged
     */
    static boolean mustPublishBeforeStaging(long stagedBytes, long incomingBytes, long budgetBytes) {
        return stagedBytes + incomingBytes > budgetBytes;
    }

    /**
     * Bytes the JVM can still allocate.
     *
     * @return free heap, including the room to grow to the maximum
     */
    private static long freeHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
    }

    /**
     * Stages one block of stat data on the heap for a later batch publish.
     *
     * <p>If the budget cannot hold the block, this backend publishes what it has already staged to
     * make room; if the block still does not fit, it is published on its own rather than pushing
     * the staging area past its budget. The caller does not have to know which of the two happened.
     *
     * @param data ByteBuffer containing the bytes to stage
     * @param targetFileOffset Position in the mapped RRD file the bytes belong to on publish
     * @throws IOException If the backend is read-only or has no mapping to publish into
     */
    public synchronized void bufferStatFile(ByteBuffer data, long targetFileOffset)
            throws IOException {
        if (readOnly) {
            throw new IOException("Backend opened in read-only mode; cannot buffer stats.");
        }
        data.rewind();
        int length = data.remaining();
        long budget = budgetBytes();
        long outstanding = stagedBytes();
        if (mustPublishBeforeStaging(outstanding, length, budget)) {
            // publish this backend's own batch first: it is the cheapest way to make room,
            // and the bytes it frees are already destined for the mapping anyway
            flushBufferedStatFiles();
            outstanding = stagedBytes();
        }
        if (mustPublishBeforeStaging(outstanding, length, budget)) {
            publish(data, targetFileOffset);
            return;
        }
        byte[] copy = new byte[length];
        data.get(copy);
        synchronized (STAGING_LOCK) {
            stagedBlocks.add(new StatFileSegment(copy, targetFileOffset));
            _stagedBytes += length;
        }
    }

    /**
     * Publishes everything this backend has staged, then pushes the mapping to disk.
     *
     * <p>A block's heap is released as soon as it is copied, so the memory a batch was using is
     * reclaimable when the batch is, not at the next collection. A block that no longer fits its
     * mapping is reported and dropped rather than thrown: the rest of the batch still lands, and
     * the scheduled flush that runs this keeps its place in the schedule.
     */
    public synchronized void flushBufferedStatFiles() {
        if (readOnly || byteBuffer == null) {
            return;
        }
        int published = 0;
        long released = 0;
        for (StatFileSegment segment : stagedBlocks) {
            if (segment.data == null) {
                continue;
            }
            if (segment.targetFileOffset < 0 ||
                    segment.targetFileOffset + segment.length > byteBuffer.limit()) {
                RrdLog.error("Staged block rejected for " + getPath() + " -> Falls outside the mapping"
                             + " * Offset: " + segment.targetFileOffset + " bytes"
                             + " * Length: " + segment.length + " bytes"
                             + " * Mapping: " + byteBuffer.limit() + " bytes", null);
            } else {
                byteBuffer.position((int) segment.targetFileOffset);
                byteBuffer.put(segment.data);
                published++;
            }
            released += segment.length;
            segment.release();
        }
        stagedBlocks.clear();
        if (released > 0) {
            adjustStagedBytes(-released);
        }
        if (published > 0 || isDirty()) {
            byteBuffer.force();
        }
    }

    /**
     * Copies one block into the mapping and pushes the mapping to disk.
     *
     * @param data the bytes to publish, read from the buffer's current position
     * @param targetFileOffset where the bytes belong in the mapped file
     * @throws IOException if there is no mapping, or the block falls outside it
     */
    private void publish(ByteBuffer data, long targetFileOffset) throws IOException {
        if (byteBuffer == null) {
            throw new IOException("Backend has no mapping to publish to.");
        }
        int length = data.remaining();
        if (targetFileOffset < 0 || targetFileOffset + length > byteBuffer.limit()) {
            throw new IOException("Cannot publish block for " + getPath() + " -> Falls outside the mapping"
                                  + " * Offset: " + targetFileOffset + " bytes"
                                  + " * Length: " + length + " bytes"
                                  + " * Mapping: " + byteBuffer.limit() + " bytes");
        }
        byteBuffer.position((int) targetFileOffset);
        byteBuffer.put(data);
        byteBuffer.force();
    }

    /** Drops this backend's staged blocks and takes their size out of the JVM-wide accounting. */
    private void releaseStaged() {
        long released = 0;
        for (StatFileSegment segment : stagedBlocks) {
            released += segment.length;
            segment.release();
        }
        stagedBlocks.clear();
        if (released > 0) {
            adjustStagedBytes(-released);
        }
    }

    /**
     * Moves the JVM-wide staged-byte counter.
     *
     * @param delta the change in bytes, negative as blocks are published or dropped
     */
    private static void adjustStagedBytes(long delta) {
        synchronized (STAGING_LOCK) {
            _stagedBytes += delta;
            if (_stagedBytes < 0) {
                // Never let a double release drive the counter negative: the budget check
                // would then admit more than the budget allows until it drifts back up.
                _stagedBytes = 0;
            }
        }
    }

    /**
     * Makes the mapping durable.
     *
     * <p>Writes land in the mapping as they happen, so a sync has no buffer to move: it only has to
     * push the mapping's dirty pages out, and only when there are any.
     */
    protected synchronized void sync() {
        if (!readOnly && byteBuffer != null && isDirty()) {
            byteBuffer.force();
        }
    }

    @Override
    public synchronized long getLength() throws IOException {
        return file.size();
    }

    @Override
    public String getCanonicalPath() {
        return Paths.get(getPath()).toAbsolutePath().normalize().toString();
    }
}
