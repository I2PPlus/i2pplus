package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.SimpleTimer2;

/**
 * What happens to the output buffer when a stream is destroyed (§3.1). The
 * stream takes its buffer from a ByteCache and hands it back on close(); the
 * destroy() path cancelled the flusher and dropped the buffer to the garbage
 * collector instead, so every aborted stream cost the next one a fresh
 * allocation of the same size.
 */
public class MessageOutputStreamDestroyTest {

    /**
     * A buffer size no other stream asks for, so the cache keyed on it belongs
     * to this test alone and the buffer that comes back out is the one that went
     * in.
     */
    private static final int BUF = 100_000;

    /**
     * The buffer is back in the cache where the next stream can pick it up.
     */
    @Test
    public void testDestroyReturnsTheBufferToTheCache() throws Exception {
        MessageOutputStream mos = newStream();
        byte[] buf = bufferOf(mos);
        assertNotNull("the stream never acquired a buffer", buf);
        mos.destroy();
        ByteArray pooled = ByteCache.getInstance(128, BUF).acquire();
        assertSame("destroy() dropped the buffer instead of pooling it", buf, pooled.getData());
    }

    /**
     * Destroying twice does not pool the same array twice - the second call
     * would hand two streams the same buffer.
     */
    @Test
    public void testDestroyingTwicePoolsTheBufferOnce() throws Exception {
        MessageOutputStream mos = newStream();
        byte[] buf = bufferOf(mos);
        mos.destroy();
        mos.destroy();
        ByteCache cache = ByteCache.getInstance(128, BUF);
        ByteArray first = cache.acquire();
        ByteArray second = cache.acquire();
        assertSame(buf, first.getData());
        assertNotSame("the same buffer was pooled twice", first.getData(), second.getData());
    }

    /**
     * A stream with a receiver that never gets used - destroy() must not flush.
     */
    private static MessageOutputStream newStream() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        return new MessageOutputStream(ctx, SimpleTimer2.getInstance(), new NullReceiver(), BUF, BUF);
    }

    /**
     * @param mos the stream to look inside
     * @return the buffer it acquired from the cache, or null if it has none
     */
    private static byte[] bufferOf(MessageOutputStream mos) throws Exception {
        Field field = MessageOutputStream.class.getDeclaredField("_buf");
        field.setAccessible(true);
        return (byte[]) field.get(mos);
    }

    /**
     * A receiver that is never written to.
     */
    private static class NullReceiver implements MessageOutputStream.DataReceiver {

        @Override
        public MessageOutputStream.WriteStatus writeData(byte[] buf, int off, int size) {
            return null;
        }

        @Override
        public boolean writeInProcess() {
            return false;
        }
    }
}
