package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Random;

import org.junit.Test;

/**
 * Test the multi-dest auto-start delay in SnarkManager.
 *
 * @since 0.9.71+
 */
public class SnarkManagerTest {

    private static final long MIN_DELAY = 30L * 1000;
    private static final long MAX_DELAY = 90L * 1000;

    @Test
    public void testStartDelayBounds() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long delay = SnarkManager.startDelay(rnd);
            assertTrue("delay " + delay, delay >= MIN_DELAY && delay <= MAX_DELAY);
        }
    }

    @Test
    public void testStartDelayVaried() {
        HashSet<Long> seen = new HashSet<>();
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            seen.add(SnarkManager.startDelay(rnd));
        }
        assertTrue("expected multiple distinct delays, got " + seen.size(), seen.size() > 1);
    }
}