package net.i2p.data.i2np;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 *  Tests for I2NPMessageImpl unique id handling.
 */
public class I2NPMessageImplTest {

    @Test
    public void testLazyUniqueIdStable() throws Exception {
        DataMessage msg = new DataMessage(I2PAppContext.getGlobalContext());
        long id = msg.getUniqueId();
        assertTrue("id >= 0", id >= 0);
        assertEquals("id stable", id, msg.getUniqueId());
        assertEquals("bloom xor", id ^ 0x1234L, msg.getUniqueId(0x1234L));
    }

    @Test
    public void testSetUniqueId() throws Exception {
        DataMessage msg = new DataMessage(I2PAppContext.getGlobalContext());
        msg.setUniqueId(12345);
        assertEquals(12345, msg.getUniqueId());
    }

    @Test
    public void testConcurrentLazyUniqueIdSingleValue() throws Exception {
        final DataMessage msg = new DataMessage(I2PAppContext.getGlobalContext());
        final int threads = 8;
        final int iterations = 500;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final Set<Long> ids = java.util.Collections.synchronizedSet(new HashSet<Long>());
        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int j = 0; j < iterations; j++) {
                        ids.add(Long.valueOf(msg.getUniqueId()));
                    }
                    done.countDown();
                }
            }, "i2p-id-test-" + i).start();
        }
        start.countDown();
        try {
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
        assertEquals("all threads must see one id", 1, ids.size());
    }

    @Test
    public void testReadBytesSetsUniqueId() throws Exception {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        DataMessage msg = new DataMessage(ctx);
        msg.setData(new byte[32]);
        byte[] buf = msg.toByteArray();
        long id = msg.getUniqueId();
        DataMessage msg2 = new DataMessage(ctx);
        msg2.setData(new byte[32]);
        int len = msg2.readBytes(buf, -1, 0);
        assertEquals(buf.length, len);
        assertEquals("id read from wire", id, msg2.getUniqueId());
    }
}
