package net.i2p.util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests the stall registry that lets the router prefer a different outbound
 * tunnel for a destination whose stream stopped delivering.
 *
 * <p>The load-bearing property is that this is a <b>hint, not a veto</b>: a
 * destination whose every tunnel is inside its cooldown must still be sent to,
 * or a slow path becomes a dead one. That is a property of the caller, but the
 * registry must at least never report a mark as permanent, or a single stall
 * would sideline a destination for the life of the process.
 *
 * @since 0.9.71+
 */
public class StallRegistryTest {

    private static final long COOLDOWN = 3 * 60 * 1000L;

    private Hash _a;
    private Hash _b;

    /** Deterministic distinct destination, so key collisions cannot pass silently. */
    private static Hash hashOf(int seed) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        b[0] = (byte) (seed >>> 24);
        b[1] = (byte) (seed >>> 16);
        b[2] = (byte) (seed >>> 8);
        b[3] = (byte) seed;
        return new Hash(b);
    }

    @Before
    public void setUp() {
        StallRegistry.clear();
        _a = hashOf(0xA0A0);
        _b = hashOf(0xB0B0);
    }

    @After
    public void tearDown() {StallRegistry.clear();}

    /** An unmarked destination is never in cooldown. */
    @Test
    public void testUnmarkedIsNotStalled() {
        assertFalse(StallRegistry.recentlyStalled(_a, 1000, COOLDOWN));
    }

    /** A mark takes effect immediately. */
    @Test
    public void testMarkIsImmediate() {
        StallRegistry.markStalled(_a, 1000);
        assertTrue(StallRegistry.recentlyStalled(_a, 1000, COOLDOWN));
    }

    /** A different destination is unaffected: the key is per-destination. */
    @Test
    public void testOtherDestinationUnaffected() {
        StallRegistry.markStalled(_a, 1000);
        assertFalse(StallRegistry.recentlyStalled(_b, 1000, COOLDOWN));
    }

    /** Just inside the cooldown, the mark still holds. */
    @Test
    public void testStillStalledJustBeforeCooldown() {
        StallRegistry.markStalled(_a, 0);
        assertTrue(StallRegistry.recentlyStalled(_a, COOLDOWN - 1, COOLDOWN));
    }

    /**
     * The cooldown boundary is exclusive: a mark exactly at the cooldown has
     * expired, so the tunnel becomes eligible again.
     */
    @Test
    public void testExpiredExactlyAtCooldown() {
        StallRegistry.markStalled(_a, 0);
        assertFalse(StallRegistry.recentlyStalled(_a, COOLDOWN, COOLDOWN));
        assertFalse(StallRegistry.recentlyStalled(_a, COOLDOWN + 1, COOLDOWN));
    }

    /**
     * The critical anti-starvation property: a mark must expire so a
     * single transient stall cannot sideline a destination indefinitely.
     */
    @Test
    public void testMarkExpiresRatherThanPersisting() {
        StallRegistry.markStalled(_a, 0);
        assertTrue(StallRegistry.recentlyStalled(_a, 1, COOLDOWN));
        assertFalse("mark must expire, not persist forever",
                    StallRegistry.recentlyStalled(_a, COOLDOWN * 100, COOLDOWN));
    }

    /** Re-marking restarts the cooldown rather than being ignored. */
    @Test
    public void testRemarkRestartsCooldown() {
        StallRegistry.markStalled(_a, 0);
        assertFalse(StallRegistry.recentlyStalled(_a, COOLDOWN + 1, COOLDOWN));
        StallRegistry.markStalled(_a, COOLDOWN + 1);
        assertTrue("a fresh mark must hold again",
                   StallRegistry.recentlyStalled(_a, COOLDOWN + 2, COOLDOWN));
    }

    /** A non-positive cooldown disables the hint entirely. */
    @Test
    public void testNonPositiveCooldownDisables() {
        StallRegistry.markStalled(_a, 0);
        assertFalse(StallRegistry.recentlyStalled(_a, 0, 0));
        assertFalse(StallRegistry.recentlyStalled(_a, 0, -1));
    }

    /** A null destination is ignored rather than throwing on the send path. */
    @Test
    public void testNullDestinationIsSafe() {
        StallRegistry.markStalled(null, 0);
        assertFalse(StallRegistry.recentlyStalled(null, 0, COOLDOWN));
        assertEquals(0, StallRegistry.size());
    }

    /** A clock that steps backwards must not produce a negative age. */
    @Test
    public void testBackwardsClockIsTreatedAsNotStalled() {
        StallRegistry.markStalled(_a, 10000);
        assertFalse("a backwards clock must not read as in-cooldown",
                    StallRegistry.recentlyStalled(_a, 5000, COOLDOWN));
    }

    /**
     * An expired mark must report exactly the cooldown, not a large age. A
     * caller ranking destinations by stallAge would otherwise see a very old
     * mark as the worst offender forever, and a long-idle destination would
     * outrank a freshly marked one.
     */
    @Test
    public void testExpiredAgeClampsToCooldown() {
        StallRegistry.markStalled(_a, 0);
        assertEquals("an expired mark must clamp to the cooldown",
                     COOLDOWN, StallRegistry.stallAge(_a, COOLDOWN * 100, COOLDOWN));
    }

    /** A mark still in effect reports its true age, not the clamp. */
    @Test
    public void testLiveAgeIsExact() {
        StallRegistry.markStalled(_a, 1000);
        assertEquals(500, StallRegistry.stallAge(_a, 1500, COOLDOWN));
    }

    /** stallAge is clamped to the cooldown and never negative. */
    @Test
    public void testStallAgeIsClamped() {
        assertEquals(COOLDOWN, StallRegistry.stallAge(_a, 0, COOLDOWN));
        StallRegistry.markStalled(_a, 1000);
        assertTrue(StallRegistry.stallAge(_a, 1500, COOLDOWN) >= 0);
        assertTrue(StallRegistry.stallAge(_a, 1500, COOLDOWN) <= COOLDOWN);
    }

    /** The registry must stay bounded under many destinations. */
    @Test
    public void testBoundedUnderManyDestinations() {
        for (int i = 0; i < 9000; i++) {
            StallRegistry.markStalled(hashOf(i), i * 10L);
        }
        assertTrue("registry must stay bounded, was " + StallRegistry.size(),
                   StallRegistry.size() <= 8192);
    }

    /** Pruning must not discard a mark that is still in effect. */
    @Test
    public void testPruneKeepsLiveMarks() {
        StallRegistry.markStalled(_a, 1000);
        for (int i = 0; i < 9000; i++) {
            StallRegistry.markStalled(hashOf(i + 1), 1000);
        }
        assertTrue("a fresh mark must survive pruning",
                   StallRegistry.recentlyStalled(_a, 1001, COOLDOWN));
    }
}
