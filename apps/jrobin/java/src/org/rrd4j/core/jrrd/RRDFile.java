package org.rrd4j.core.jrrd;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import org.rrd4j.core.InvalidRrdException;

/**
 * This class is used read information from an RRD file. Writing to RRD files is not currently
 * supported. It uses NIO's RandomAccessFile to read the file
 *
 * <p>Currently this can read RRD files that were generated on Solaris (Sparc) and Linux (x86).
 *
 * @version $Revision: 1.1 $
 */
class RRDFile implements Constants {

    /**
     * Constant <code>FLOAT_COOKIE_BIG_ENDIAN={0x5B, 0x1F, 0x2B, 0x43,
     * (byte) 0xC7, (byte) 0xC0, 0x25,
     * 0x2F}</code>
     */
    private static final byte[] FLOAT_COOKIE_BIG_ENDIAN = {
        0x5B, 0x1F, 0x2B, 0x43, (byte) 0xC7, (byte) 0xC0, 0x25, 0x2F
    };

    /**
     * Constant <code>FLOAT_COOKIE_LITTLE_ENDIAN={0x2F, 0x25, (byte) 0xC0,
     * (byte) 0xC7, 0x43, 0x2B, 0x1F,
     * 0x5B}</code>
     */
    private static final byte[] FLOAT_COOKIE_LITTLE_ENDIAN = {
        0x2F, 0x25, (byte) 0xC0, (byte) 0xC7, 0x43, 0x2B, 0x1F, 0x5B
    };

    /** Reflected cleaner method of the mapped buffer. */
    private static final Method CLEANER_METHOD;
    /** Reflected clean method of the mapped buffer. */
    private static final Method CLEAN_METHOD;
    /** Reflected unsafe method cleaning a buffer. */
    private static final Method INVOKE_CLEANER;
    /** Unsafe instance for unmapping, null if unavailable. */
    private static final Object UNSAFE;

    static {
        Method cm = null;
        Method clm = null;
        Method ic = null;
        Object us = null;
        try {
            Class<?> dbc = RRDFile.class.getClassLoader().loadClass("sun.nio.ch.DirectBuffer");
            cm = dbc.getMethod("cleaner");
            cm.setAccessible(true);
            try {
                Class<?> cc = RRDFile.class.getClassLoader().loadClass("sun.misc.Cleaner");
                clm = cc.getMethod("clean");
                clm.setAccessible(true);
            } catch (Exception e) {
                // Java 9+: resolved lazily
            }
        } catch (Exception e) {
            // sun.nio.ch not accessible
        }
        try {
            Field f = RRDFile.class.getClassLoader()
                    .loadClass("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            us = f.get(null);
            ic = us.getClass().getMethod("invokeCleaner", ByteBuffer.class);
        } catch (Exception e) {
            // Unsafe not accessible
        }
        CLEANER_METHOD = cm;
        CLEAN_METHOD = clm;
        INVOKE_CLEANER = ic;
        UNSAFE = us;
    }

    /** Byte alignment of the values in the file. */
    private int alignment;
    /** Byte size of a long in the file. */
    private int longSize = 4;

    /** Stream backing the mapped file. */
    private final FileInputStream underlying;
    /** Mapping of the whole file. */
    private final MappedByteBuffer mappedByteBuffer;

    /** Byte order of the values in the file. */
    private ByteOrder order;

    /**
     * Open RRD file
     * @param name the filesystem path of the RRD to memory-map
     * @throws IOException if the file cannot be opened or mapped
     */
    RRDFile(String name) throws IOException {
        this(new File(name));
    }

    /**
     * Open RRD file
     * @param file the RRD to memory-map read-only
     * @throws IOException if the file cannot be opened or mapped, or if it is
     *                     larger than 2**31 bytes, which a ByteBuffer cannot address
     */
    RRDFile(File file) throws IOException {
        long len = file.length();
        if (len > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "RRDFile cannot read files larger than 2**31 because of limitations of"
                            + " java.nio.ByteBuffer");
        }

        boolean ok = false;
        try {
            underlying = new FileInputStream(file);
            mappedByteBuffer = underlying.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, len);
            initDataLayout(file);
            ok = true;
        } finally {
            if (!ok) {
                try {
                    close();
                } catch (IOException ignored) { /* ignored */ }
                // and then rethrow
            }
        }
    }

    /**
     * Reads the data layout header of the file.
     *
     * @param file the RRD file to read
     * @throws IOException if the file cannot be read
     */
    private void initDataLayout(File file) throws IOException {

        if (file.exists()) { // Load the data formats from the file
            byte[] buffer = new byte[32];
            mappedByteBuffer.get(buffer);
            ByteBuffer bbuffer = ByteBuffer.wrap(buffer);

            int index;

            if ((index = indexOf(FLOAT_COOKIE_BIG_ENDIAN, buffer)) != -1) {
                order = ByteOrder.BIG_ENDIAN;
            } else if ((index = indexOf(FLOAT_COOKIE_LITTLE_ENDIAN, buffer)) != -1) {
                order = ByteOrder.LITTLE_ENDIAN;
            } else {
                throw new InvalidRrdException("Invalid RRD file");
            }
            mappedByteBuffer.order(order);
            bbuffer.order(order);

            switch (index) {
                case 12:
                    alignment = 4;
                    break;

                case 16:
                    alignment = 8;
                    break;

                default:
                    throw new RuntimeException("Unsupported architecture");
            }

            bbuffer.position(index + 8);
            // We cannot have dsCount && rracount == 0
            // If one is 0, it's a 64 bits rrd
            int int1 = bbuffer.getInt(); // Should be dsCount in ILP32
            int int2 = bbuffer.getInt(); // Should be rraCount in ILP32
            if (int1 == 0 || int2 == 0) {
                longSize = 8;
            }
        }
        // Reset file pointer to start of file
        mappedByteBuffer.rewind();
    }

    /**
     * Finds a byte pattern in an array.
     *
     * @param pattern the bytes to find
     * @param array the array to search
     * @return the position, negative if absent
     */
    private int indexOf(byte[] pattern, byte[] array) {
        return (new String(array, StandardCharsets.UTF_8))
                .indexOf(new String(pattern, StandardCharsets.UTF_8));
    }

    /**
     * Reports the byte order of the mapped file.
     * @return true when the order is big endian
     */
    boolean isBigEndian() {
        return order == ByteOrder.BIG_ENDIAN;
    }

    /**
     * Byte alignment this file was mapped with.
     * @return 4 for 32 bit RRDs, 8 for 64 bit ones
     */
    int getAlignment() {
        return alignment;
    }

    /**
     * Reads a big-endian 8-byte float at the read position.
     * @return the big-endian double read from the mapped file, advancing the position by 8 bytes
     */
    double readDouble() {
        return mappedByteBuffer.getDouble();
    }

    /**
     * Reads a 4-byte integer at the read position.
     * @return the next 4-byte integer at the read position
     */
    int readInt() {
        return mappedByteBuffer.getInt();
    }

    /**
     * Reads a long, narrowed to int, honouring this file's long size.
     * @return the value read from the mapped file, narrowed to an int
     */
    int readLong() {
        if (longSize == 4) {
            return mappedByteBuffer.getInt();
        } else {
            return (int) mappedByteBuffer.getLong();
        }
    }

    /**
     * Reads a fixed-width NUL-padded UTF-8 field.
     * @param maxLength the fixed field width to read, NUL padding included
     * @return the next fixed-width field decoded as UTF-8, with the NUL padding trimmed
     */
    String readString(int maxLength) {
        byte[] array = new byte[maxLength];
        mappedByteBuffer.get(array);

        return new String(array, 0, maxLength, StandardCharsets.UTF_8).trim();
    }

    /**
     * Skip bytes
     * @param n the number of bytes to advance the read position by
     */
    void skipBytes(int n) {
        mappedByteBuffer.position(mappedByteBuffer.position() + n);
    }

    /**
     * Advances the read position to the given alignment boundary.
     * @param boundary the alignment boundary in bytes to advance to
     * @return the bytes skipped, 0 if already aligned
     */
    int align(int boundary) {

        int skip = (boundary - (mappedByteBuffer.position() % boundary)) % boundary;

        if (skip != 0) {
            mappedByteBuffer.position(mappedByteBuffer.position() + skip);
        }

        return skip;
    }

    /**
     * Advances the read position to the file's own alignment.
     * @return the bytes skipped, 0 if already aligned
     */
    int align() {
        return align(alignment);
    }

    /**
     * Reports the current read position.
     * @return the byte offset of the next read in the mapped file
     */
    long info() {
        return mappedByteBuffer.position();
    }

    /**
     * Reports the current read position.
     * @return the byte offset of the next read
     */
    long getFilePointer() {
        return mappedByteBuffer.position();
    }

    /** Unmaps the file so it can be deleted. */
    private void unmapFile() {
        if (mappedByteBuffer != null && mappedByteBuffer.isDirect()) {
            try {
                if (CLEANER_METHOD != null) {
                    Object cleaner = CLEANER_METHOD.invoke(mappedByteBuffer);
                    if (cleaner != null) {
                        Method clean = CLEAN_METHOD;
                        if (clean == null) {
                            clean = cleaner.getClass().getMethod("clean");
                            clean.setAccessible(true);
                        }
                        clean.invoke(cleaner);
                        return;
                    }
                }
                if (INVOKE_CLEANER != null)
                    INVOKE_CLEANER.invoke(UNSAFE, mappedByteBuffer);
            } catch (Exception e) {
                // Fallback: GC will clean up eventually
            }
        }
    }

    /**
     * Close the file
     * @throws IOException if the underlying stream cannot be closed
     */
    void close() throws IOException {
        unmapFile();
        if (underlying != null) {
            underlying.close();
        }
    }

    /**
     * Read bytes
     * @param bb the buffer to fill from the current read position
     */
    void read(ByteBuffer bb) {
        int count = bb.remaining();
        bb.put(
                (ByteBuffer)
                        mappedByteBuffer.duplicate().limit(mappedByteBuffer.position() + count));
        mappedByteBuffer.position(mappedByteBuffer.position() + count);
    }

    /**
     * Allocates a unival array at the current position.
     * @param size the number of elements the array holds
     * @return a new array of size elements
     */
    UnivalArray getUnivalArray(int size) {
        return new UnivalArray(this, size);
    }

    /**
     * Width of a long in this file, in bits.
     * @return 32 or 64, the long size times 8
     */
    int getBits() {
        return longSize * 8;
    }

    /**
     * Moves the read position to an absolute offset.
     * @param position the absolute byte offset to read from next
     */
    public void seek(long position) {
        mappedByteBuffer.position((int) position);
    }

    /**
     * Moves the read position to the end of the mapped region.
     */
    public void seekToEndOfFile() {
        mappedByteBuffer.position(mappedByteBuffer.limit());
    }
}
