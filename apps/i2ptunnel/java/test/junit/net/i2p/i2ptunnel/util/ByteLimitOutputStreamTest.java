package net.i2p.i2ptunnel.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests ByteLimitOutputStream byte-counting and completion logic.
 *
 * @since 0.9.70+
 */
public class ByteLimitOutputStreamTest {

    @Test
    public void testWriteWithinLimit() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final AtomicInteger doneCalled = new AtomicInteger(0);
        ByteLimitOutputStream lim = new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { doneCalled.incrementAndGet(); }
            }, 100);

        lim.write("hello".getBytes());
        assertEquals(5, baos.size());
        assertEquals(0, doneCalled.get());
    }

    @Test
    public void testWriteExactLimit() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final AtomicInteger doneCalled = new AtomicInteger(0);
        ByteLimitOutputStream lim = new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { doneCalled.incrementAndGet(); }
            }, 5);

        lim.write("hello".getBytes());
        assertEquals(5, baos.size());
        assertEquals(1, doneCalled.get());
    }

    @Test
    public void testWriteExceedsLimit() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final AtomicInteger doneCalled = new AtomicInteger(0);
        ByteLimitOutputStream lim = new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { doneCalled.incrementAndGet(); }
            }, 5);

        lim.write("hello world".getBytes());
        // Only 5 bytes written
        assertEquals(5, baos.size());
        assertEquals("hello", baos.toString());
        assertEquals(1, doneCalled.get());
    }

    @Test(expected = EOFException.class)
    public void testWriteAfterDone() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteLimitOutputStream lim = new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { }
            }, 5);

        lim.write("hello".getBytes());
        lim.write("x".getBytes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativeLimit() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { }
            }, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testZeroLimit() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { }
            }, 0);
    }

    @Test
    public void testWriteZeroLenNoOp() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final AtomicInteger doneCalled = new AtomicInteger(0);
        ByteLimitOutputStream lim = new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { doneCalled.incrementAndGet(); }
            }, 5);

        lim.write(new byte[0]);
        assertEquals(0, baos.size());
        assertEquals(0, doneCalled.get());
    }

    @Test
    public void testMultipleWrites() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteLimitOutputStream lim = new ByteLimitOutputStream(baos,
            new LimitOutputStream.DoneCallback() {
                public void streamDone() { }
            }, 10);

        lim.write("abc".getBytes());
        lim.write("def".getBytes());
        assertEquals(6, baos.size());
    }
}
