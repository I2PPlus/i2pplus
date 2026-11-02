package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * The thread-name suffixes: fixed width, so the log column does not move and
 * a counter that has run for a month does not push the stem out of the
 * 12-character column.
 *
 * @since 0.9.72+
 */
public class ThreadNamesTest {

    /**
     * A fresh test instance per method, as JUnit builds one per test; the
     * class carries no state.
     *
     * @since 0.9.72+
     */
    public ThreadNamesTest() {}

    /**
     * A single-digit sequence number is padded to two digits.
     */
    @Test
    public void testSeqPads() {
        assertEquals(".07", ThreadNames.seq(7));
        assertEquals(".00", ThreadNames.seq(0));
    }

    /**
     * The suffix is always three characters, which is the budget a stem is
     * sized against: 9 + 3 fills the column exactly.
     */
    @Test
    public void testSeqWidthIsFixed() {
        for (int n : new int[]{0, 9, 10, 99, 100, 1234, 1000000}) {
            String s = ThreadNames.seq(n);
            assertEquals(n + " -> " + s, ThreadNames.seqWidth(), s.length());
        }
    }

    /**
     * A monotonic counter wraps at 100 rather than growing the name.
     */
    @Test
    public void testSeqWraps() {
        assertEquals(".00", ThreadNames.seq(100));
        assertEquals(".37", ThreadNames.seq(137));
        assertEquals(".04", ThreadNames.seq(10004));
    }

    /**
     * A negative counter, from a subtraction of two ids, stays in width
     * instead of gaining a sign character.
     */
    @Test
    public void testSeqOfNegativeStaysInWidth() {
        assertEquals(".99", ThreadNames.seq(-1));
        assertEquals(3, ThreadNames.seq(Integer.MIN_VALUE).length());
    }

    /**
     * An index from a bounded pool is padded, and shows the real number when
     * the pool outgrows two digits.
     */
    @Test
    public void testIdxPadsWithoutWrapping() {
        assertEquals(".03", ThreadNames.idx(3));
        assertEquals(".120", ThreadNames.idx(120));
    }

    /**
     * A pool hands out the lowest number no thread is using, so a family of
     * threads that come and go stays named .01 upward.
     */
    @Test
    public void testPoolReusesTheLowestFreeNumber() {
        ThreadNames.Pool p = new ThreadNames.Pool();
        assertEquals(1, p.acquire());
        assertEquals(2, p.acquire());
        assertEquals(3, p.acquire());
        p.release(2);
        assertEquals("the freed number comes back first", 2, p.acquire());
    }

    /**
     * The highest number in use is dropped rather than pooled, so a family that
     * shrinks back to nothing starts handing out .01 upward again instead of
     * recycling a tail of freed numbers.
     */
    @Test
    public void testPoolShrinksBackToTheStart() {
        ThreadNames.Pool p = new ThreadNames.Pool();
        p.acquire();
        p.acquire();
        p.acquire();
        p.release(2);
        p.release(3);
        p.release(1);
        assertEquals(1, p.acquire());
    }

    /**
     * A number released twice is not handed out twice, and a number that was
     * never acquired is ignored.
     */
    @Test
    public void testPoolIgnoresStrayReleases() {
        ThreadNames.Pool p = new ThreadNames.Pool();
        p.release(0);
        p.release(-7);
        assertEquals(1, p.acquire());
    }

    /**
     * A thread made by the pool is named from its number, is a daemon when
     * asked, and gives the number back when its run() returns.
     */
    @Test
    public void testPoolThreadReleasesItsNumberWhenItFinishes() {
        ThreadNames.Pool p = new ThreadNames.Pool();
        Thread t = p.thread(() -> {}, "PoolTest", true);
        assertEquals("PoolTest.01", t.getName());
        assertTrue(t.isDaemon());
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("the pool thread did not finish");
        }
        assertFalse("the thread is gone", t.isAlive());
        assertEquals("the finished thread's number is free again", 1, p.acquire());
    }

    /**
     * A thread that throws still releases its number - the harvest is in a
     * finally, not after the run.
     */
    @Test
    public void testPoolThreadReleasesItsNumberOnAnException() {
        ThreadNames.Pool p = new ThreadNames.Pool();
        Thread t = p.thread(() -> {throw new IllegalStateException("boom");}, "PoolTest", true);
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("the pool thread did not finish");
        }
        assertEquals(1, p.acquire());
    }
}
