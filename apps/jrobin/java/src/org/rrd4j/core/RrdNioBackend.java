package org.rrd4j.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>This version buffers all stat files in a single temporary buffer file sequentially, keeping
 * track of each file's offset and length, then flushes all to the mapped file in batch.
 */
public class RrdNioBackend extends ByteBufferBackend implements RrdFileBackend {

    // The java 8- methods
    private static final Method cleanerMethod;
    private static final Method cleanMethod;
    // The java 9+ methods
    private static final Method invokeCleaner;
    private static final Object unsafe;

    static {
        Method cleanerMethodTemp;
        Method cleanMethodTemp;
        Method invokeCleanerTemp;
        Object unsafeTemp;
        try {
            Class<?> directBufferClass =
                    RrdRandomAccessFileBackend.class
                            .getClassLoader()
                            .loadClass("sun.nio.ch.DirectBuffer");
            Class<?> cleanerClass =
                    RrdNioBackend.class.getClassLoader().loadClass("sun.misc.Cleaner");
            cleanerMethodTemp = directBufferClass.getMethod("cleaner");
            cleanerMethodTemp.setAccessible(true);
            cleanMethodTemp = cleanerClass.getMethod("clean");
            cleanMethodTemp.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException | SecurityException e) {
            cleanerMethodTemp = null;
            cleanMethodTemp = null;
        }
        try {
            Field singletonInstanceField =
                    RrdRandomAccessFileBackend.class
                            .getClassLoader()
                            .loadClass("sun.misc.Unsafe")
                            .getDeclaredField("theUnsafe");
            singletonInstanceField.setAccessible(true);
            unsafeTemp = singletonInstanceField.get(null);
            invokeCleanerTemp = unsafeTemp.getClass().getMethod("invokeCleaner", ByteBuffer.class);
        } catch (NoSuchFieldException
                | SecurityException
                | IllegalArgumentException
                | IllegalAccessException
                | NoSuchMethodException
                | ClassNotFoundException e) {
            invokeCleanerTemp = null;
            unsafeTemp = null;
        }
        cleanerMethod = cleanerMethodTemp;
        cleanMethod = cleanMethodTemp;
        invokeCleaner = invokeCleanerTemp;
        unsafe = unsafeTemp;
    }

    private MappedByteBuffer byteBuffer;
    private final FileChannel file;
    private final boolean readOnly;

    private ScheduledFuture<?> syncRunnableHandle = null;

    /** Path to the single temporary file used to buffer all stat files data sequentially. */
    private Path tempBufferFilePath;

    /** FileChannel to write and read stats data from the single temp buffer file. */
    private FileChannel tempBufferFileChannel;

    /**
     * Data structure to track each buffered stat file's offset and length within the single temp
     * buffer file.
     */
    private final List<StatFileSegment> bufferedStatSegments = new ArrayList<>();

    /**
     * Helper class representing a buffered stat file segment's location in the temp buffer file.
     */
    private static class StatFileSegment {
        final long offset; // offset within temp buffer file where this stat file data begins
        final int length; // length of this stat file data in bytes
        final long targetFileOffset; // offset within the mapped (RRD) file where this stat belongs

        StatFileSegment(long offset, int length, long targetFileOffset) {
            this.offset = offset;
            this.length = length;
            this.targetFileOffset = targetFileOffset;
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

        if (!readOnly) {
            tempBufferFilePath = Paths.get(System.getProperty("java.io.tmpdir"), "rrd_stats_buffer.tmp");
            tempBufferFileChannel =
                    FileChannel.open(
                            tempBufferFilePath, 
                            StandardOpenOption.READ, 
                            StandardOpenOption.WRITE, 
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
        }

        try {
            if (!readOnly && threadPool != null) {
                Runnable syncRunnable = this::flushBufferedStatFiles;
                syncRunnableHandle =
                        threadPool.scheduleWithFixedDelay(
                                syncRunnable, syncPeriod, syncPeriod, TimeUnit.SECONDS);
            }
        } catch (RuntimeException rte) {
            closeTempResources();
            unmapFile();
            file.close();
            super.close();
            throw rte;
        }
    }

    private void mapFile(long length) throws IOException {
        if (length > 0) {
            FileChannel.MapMode mapMode =
                    readOnly ? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE;
            byteBuffer = file.map(mapMode, 0, length);
            setByteBuffer(byteBuffer);
        }
    }

    private void unmapFile() {
        if (byteBuffer != null && byteBuffer.isDirect()) {
            try {
                if (cleanMethod != null) {
                    Object cleaner = cleanerMethod.invoke(byteBuffer);
                    cleanMethod.invoke(cleaner);
                } else {
                    invokeCleaner.invoke(unsafe, byteBuffer);
                }
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }
        }
        byteBuffer = null;
    }

    /**
     * {@inheritDoc} Sets length of the underlying RRD file. This method is called only once,
     * immediately after new RRD creation.
     *
     * @throws IllegalArgumentException if the length is bigger than the possible mapping position
     *     (2GiB).
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
     * Closes the underlying RRD file. Also closes and deletes the temporary buffer file used for
     * buffering.
     *
     * @throws IOException On I/O error
     */
    @Override
    public synchronized void close() throws IOException {
        try {
            if (!readOnly && syncRunnableHandle != null) {
                syncRunnableHandle.cancel(false);
                syncRunnableHandle = null;
                flushBufferedStatFiles();
            }
            closeTempResources();
            unmapFile();
        } finally {
            file.close();
            super.close();
        }
    }

    private void closeTempResources() {
        try {
            if (tempBufferFileChannel != null) {
                tempBufferFileChannel.close();
                tempBufferFileChannel = null;
            }
            if (tempBufferFilePath != null) {
                Files.deleteIfExists(tempBufferFilePath);
                tempBufferFilePath = null;
            }
        } catch (IOException ignored) {
            // Ignore exceptions on temp file/resource cleanup
        }
    }

    /**
     * Buffers one stat file's data into the single temp buffer file sequentially. This method
     * appends the data to the temp file and records the offset and length, including the target
     * offset in the mapped file where this stat file belongs.
     *
     * @param data ByteBuffer containing the stat file's bytes to buffer
     * @param targetFileOffset Position in the mapped RRD file to write this stat file to on flush
     * @throws IOException If writing to temp file fails
     */
    public synchronized void bufferStatFile(ByteBuffer data, long targetFileOffset)
            throws IOException {
        if (readOnly) {
            throw new IOException("Backend opened in read-only mode; cannot buffer stats.");
        }
        if (tempBufferFileChannel == null) {
            throw new IOException("Temporary buffer file channel is not open.");
        }
        data.rewind();
        long offsetBefore = tempBufferFileChannel.size();
        tempBufferFileChannel.position(offsetBefore);

        int length = 0;
        while (data.hasRemaining()) {
            length += tempBufferFileChannel.write(data);
        }
        tempBufferFileChannel.force(false);

        bufferedStatSegments.add(new StatFileSegment(offsetBefore, length, targetFileOffset));
    }

    /**
     * Flushes all buffered stat files sequentially from the temp buffer file into their designated
     * positions in the mapped RRD file in one batch operation. After flushing, the temp buffer file
     * and index are cleared for the next batch.
     */
    public synchronized void flushBufferedStatFiles() {
        if (readOnly
                || byteBuffer == null
                || tempBufferFileChannel == null
                || tempBufferFilePath == null) {
            return;
        }
        try {
            for (StatFileSegment segment : bufferedStatSegments) {
                try (FileChannel readChannel =
                        FileChannel.open(tempBufferFilePath, StandardOpenOption.READ)) {
                    MappedByteBuffer mappedSegment =
                            readChannel.map(
                                    FileChannel.MapMode.READ_ONLY, segment.offset, segment.length);
                    byteBuffer.position((int) segment.targetFileOffset);
                    byteBuffer.put(mappedSegment);
                }
            }
            byteBuffer.force();

            // Clear temp buffer file and reset state
            tempBufferFileChannel.truncate(0);
            tempBufferFileChannel.force(false);
            bufferedStatSegments.clear();

        } catch (IOException e) {
            throw new RuntimeException("Failed to flush buffered stat files", e);
        }
    }

    /** Override sync to disable unbuffered flush. */
    protected synchronized void sync() {
        // Intentionally empty: use buffered write + flush instead
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
