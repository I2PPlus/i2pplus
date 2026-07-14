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
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
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

    // Reflective unmap support (mirrors RrdNioBackend pattern)
    private static final Method CLEANER_METHOD;
    private static final Method CLEAN_METHOD;
    private static final Method INVOKE_CLEANER;
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

    private int alignment;
    private int longSize = 4;

    private final FileInputStream underlying;
    private final MappedByteBuffer mappedByteBuffer;

    private ByteOrder order;

    RRDFile(String name) throws IOException {
        this(new File(name));
    }

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

    private int indexOf(byte[] pattern, byte[] array) {
        return (new String(array, StandardCharsets.UTF_8))
                .indexOf(new String(pattern, StandardCharsets.UTF_8));
    }

    boolean isBigEndian() {
        return order == ByteOrder.BIG_ENDIAN;
    }

    int getAlignment() {
        return alignment;
    }

    double readDouble() {
        return mappedByteBuffer.getDouble();
    }

    int readInt() {
        return mappedByteBuffer.getInt();
    }

    int readLong() {
        if (longSize == 4) {
            return mappedByteBuffer.getInt();
        } else {
            return (int) mappedByteBuffer.getLong();
        }
    }

    String readString(int maxLength) {
        byte[] array = new byte[maxLength];
        mappedByteBuffer.get(array);

        return new String(array, 0, maxLength, StandardCharsets.UTF_8).trim();
    }

    void skipBytes(int n) {
        mappedByteBuffer.position(mappedByteBuffer.position() + n);
    }

    int align(int boundary) {

        int skip = (boundary - (mappedByteBuffer.position() % boundary)) % boundary;

        if (skip != 0) {
            mappedByteBuffer.position(mappedByteBuffer.position() + skip);
        }

        return skip;
    }

    int align() {
        return align(alignment);
    }

    long info() {
        return mappedByteBuffer.position();
    }

    long getFilePointer() {
        return mappedByteBuffer.position();
    }

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

    void close() throws IOException {
        unmapFile();
        if (underlying != null) {
            underlying.close();
        }
    }

    void read(ByteBuffer bb) {
        int count = bb.remaining();
        bb.put(
                (ByteBuffer)
                        mappedByteBuffer.duplicate().limit(mappedByteBuffer.position() + count));
        mappedByteBuffer.position(mappedByteBuffer.position() + count);
    }

    UnivalArray getUnivalArray(int size) {
        return new UnivalArray(this, size);
    }

    /**
     * @return the long size in bits for this file
     */
    int getBits() {
        return longSize * 8;
    }

    public void seek(long position) {
        mappedByteBuffer.position((int) position);
    }

    public void seekToEndOfFile() {
        mappedByteBuffer.position(mappedByteBuffer.limit());
    }
}
