package net.i2p.router.crypto.pqc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;

import org.junit.Test;

/**
 * Tests the byte budget that bounds the ML-KEM precalc pool.
 * <p>
 * A pooled keypair is about 4 KB, and the pool is bounded by a byte budget, not
 * by the queue's hard maximum. The demand-driven resize bounded itself against
 * the hard maximum instead, so a busy router could grow the pool to 32768 pairs
 * - about 128 MB - and the memory-pressure halving below it only engages above
 * 70% pressure, so a router with a large heap would stay there. What is pinned
 * here is that demand, configuration and the floor all stop at the byte budget.
 *
 * @since 0.9.72+
 */
public class MLKEMPoolCapTest {

    private static final int CAP = MLKEMKeyFactory.BYTE_CAP;

    /**
     * The cap really is the byte budget: it is the budget divided by the size of
     * one pooled keypair, and it is well below the queue's hard maximum.
     */
    @Test
    public void testCapDerivesFromTheByteBudget() {
        assertEquals(MLKEMKeyFactory.MAX_POOL_BYTES / MLKEMKeyFactory.BYTES_PER_PAIR, CAP);
        assertTrue("the cap must be far below the hard maximum: " + CAP, CAP <= 4096);
        assertTrue("a full pool must fit the byte budget",
                   (long) CAP * MLKEMKeyFactory.BYTES_PER_PAIR <= MLKEMKeyFactory.MAX_POOL_BYTES);
    }

    /**
     * Demand cannot take the pool past the budget, however much of it there is.
     * This is the regression: the resize used to allow 32768 pairs here.
     */
    @Test
    public void testDemandCannotExceedTheBudget() {
        assertEquals(CAP, MLKEMKeyFactory.boundedPoolSize(64, 100_000, CAP));
        assertEquals(CAP, MLKEMKeyFactory.boundedPoolSize(64, Integer.MAX_VALUE, CAP));
    }

    /**
     * Demand below the budget is honoured as asked - the cap is a ceiling, not a
     * target, so a quiet router does not prebuild 4096 keypairs.
     */
    @Test
    public void testModestDemandIsUsedVerbatim() {
        assertEquals(1000, MLKEMKeyFactory.boundedPoolSize(64, 1000, CAP));
    }

    /**
     * With no demand the pool still keeps its floor plus the four-pair margin,
     * so a resize to zero demand cannot starve the next burst.
     */
    @Test
    public void testFloorMarginIsKept() {
        assertEquals(68, MLKEMKeyFactory.boundedPoolSize(64, 0, CAP));
        assertEquals(68, MLKEMKeyFactory.boundedPoolSize(64, 10, CAP));
    }

    /**
     * A configured minimum above the budget must not smuggle the pool past it by
     * way of the floor - the floor is capped too.
     */
    @Test
    public void testFloorAboveCapStillRespectsTheCap() {
        assertEquals(CAP, MLKEMKeyFactory.boundedPoolSize(10_000, 20_000, CAP));
        assertEquals(CAP, MLKEMKeyFactory.boundedPoolSize(10_000, 0, CAP));
    }

    /**
     * The result never exceeds the cap for any combination of floor and demand.
     */
    @Test
    public void testCapHoldsAcrossTheInputRange() {
        int[] floors = {0, 1, 64, 1000, CAP - 4, CAP, CAP + 1, 100_000};
        int[] demands = {0, 1, 68, 1000, CAP - 1, CAP, CAP + 1, Integer.MAX_VALUE};
        for (int floor : floors) {
            for (int demand : demands) {
                int got = MLKEMKeyFactory.boundedPoolSize(floor, demand, CAP);
                assertTrue("cap breached for floor " + floor + " demand " + demand + ": " + got,
                           got <= CAP);
                assertTrue("result must be non-negative: " + got, got >= 0);
            }
        }
    }

    /**
     * The resize itself, not only the helper it calls: a huge demand must not
     * leave the pool above the byte budget. This is the exact path the Tuner
     * runs every 15 seconds.
     */
    @Test
    public void testResizeHonoursTheBudget() throws Exception {
        MLKEMKeyFactory factory = new MLKEMKeyFactory(I2PAppContext.getGlobalContext(),
                                                      EncType.MLKEM768_X25519_INT);
        try {
            Field usedField = MLKEMKeyFactory.class.getDeclaredField("_usedCount");
            Field emptyField = MLKEMKeyFactory.class.getDeclaredField("_emptyCount");
            usedField.setAccessible(true);
            emptyField.setAccessible(true);
            ((AtomicInteger) usedField.get(factory)).set(100_000);
            ((AtomicInteger) emptyField.get(factory)).set(100_000);

            factory.refreshPoolSize();

            assertTrue("resize left the pool above the byte budget: " + factory.getMaxSize(),
                       factory.getMaxSize() <= CAP);
        } finally {
            factory.shutdown();
        }
    }

    /**
     * The Tuner's manual override is bounded by the same budget as demand, so
     * setting a huge maximum cannot bypass the cap either.
     */
    @Test
    public void testSetMaxSizeHonoursTheBudget() {
        MLKEMKeyFactory factory = new MLKEMKeyFactory(I2PAppContext.getGlobalContext(),
                                                      EncType.MLKEM768_X25519_INT);
        try {
            factory.setMaxSize(100_000);
            assertTrue("setMaxSize bypassed the byte budget: " + factory.getMaxSize(),
                       factory.getMaxSize() <= CAP);
        } finally {
            factory.shutdown();
        }
    }

    /**
     * The constructor applies the byte budget as well, not only the Tuner's
     * refresh. A configured maximum above the budget used to survive
     * construction, so the budget only started bounding the pool at the first
     * refreshPoolSize() call fifteen seconds later. The floor is pinned low as
     * well, because a floor already at the cap saturates every expression here
     * and would let the assertion pass unread.
     */
    @Test
    public void testConfiguredMaxIsBoundedAtConstruction() {
        String minKey = "crypto.mlkem.precalc.min";
        String maxKey = "crypto.mlkem.precalc.max";
        String savedMin = System.getProperty(minKey);
        String savedMax = System.getProperty(maxKey);
        System.setProperty(minKey, "8");
        System.setProperty(maxKey, "100000");
        MLKEMKeyFactory factory = new MLKEMKeyFactory(I2PAppContext.getGlobalContext(),
                                                      EncType.MLKEM768_X25519_INT);
        try {
            assertEquals(8, factory.getMinSize());
            assertTrue("a configured max above the budget survived construction: " +
                       factory.getMaxSize(), factory.getMaxSize() <= CAP);
        } finally {
            factory.shutdown();
            restore(minKey, savedMin);
            restore(maxKey, savedMax);
        }
    }

    /**
     * A configured maximum below the budget is honoured as asked, which is what
     * keeps the assertion above from being a test of a property nobody read.
     */
    @Test
    public void testConfiguredMaxBelowTheBudgetIsHonouredAtConstruction() {
        String minKey = "crypto.mlkem.precalc.min";
        String maxKey = "crypto.mlkem.precalc.max";
        String savedMin = System.getProperty(minKey);
        String savedMax = System.getProperty(maxKey);
        System.setProperty(minKey, "8");
        System.setProperty(maxKey, "100");
        MLKEMKeyFactory factory = new MLKEMKeyFactory(I2PAppContext.getGlobalContext(),
                                                      EncType.MLKEM768_X25519_INT);
        try {
            assertEquals(MLKEMKeyFactory.boundedPoolSize(8, (8 + 100) / 2, CAP),
                         factory.getMaxSize());
            assertTrue("the configured maximum must come down, not sit at the cap",
                       factory.getMaxSize() < CAP);
        } finally {
            factory.shutdown();
            restore(minKey, savedMin);
            restore(maxKey, savedMax);
        }
    }

    private static void restore(String key, String saved) {
        if (saved != null)
            System.setProperty(key, saved);
        else
            System.clearProperty(key);
    }

    /**
     * A Tuner-set floor cannot breach the budget either, and a refresh over that
     * floor cannot add its way back past it.
     */
    @Test
    public void testSetMinSizeIsBoundedAndSurvivesARefresh() {
        MLKEMKeyFactory factory = new MLKEMKeyFactory(I2PAppContext.getGlobalContext(),
                                                      EncType.MLKEM768_X25519_INT);
        try {
            factory.setMinSize(100_000);
            assertEquals("setMinSize lifted the floor past the byte budget: " +
                         factory.getMinSize(), CAP, factory.getMinSize());
            factory.refreshPoolSize();
            assertTrue("a refresh over a capped floor left the pool above budget: " +
                       factory.getMaxSize(), factory.getMaxSize() <= CAP);
        } finally {
            factory.shutdown();
        }
    }

    /**
     * The floor is capped, so the "at least min + 4" margin in the resize cannot
     * add a four-pair overshoot on top of a floor already at the budget.
     */
    @Test
    public void testPoolFloorIsCapped() {
        assertEquals(68, MLKEMKeyFactory.poolFloor(64, CAP));
        assertEquals(CAP, MLKEMKeyFactory.poolFloor(CAP, CAP));
        assertEquals(CAP, MLKEMKeyFactory.poolFloor(100_000, CAP));
    }

    /**
     * A shrink under memory pressure cannot lift the ceiling past the budget on
     * its way back down. With the floor already at the cap, the shrink's floor
     * used to be min + 4 - four pairs over the budget.
     */
    @Test
    public void testPressureShrinkRespectsTheCap() {
        assertEquals(CAP, MLKEMKeyFactory.pressureAdjustedMax(CAP, CAP, CAP, 0.95));
        assertEquals(CAP, MLKEMKeyFactory.pressureAdjustedMax(CAP, CAP, CAP, 0.75));
        assertEquals(CAP, MLKEMKeyFactory.pressureAdjustedMax(CAP, 100_000, CAP, 0.95));
    }

    /**
     * Away from the cap the shrink really shrinks, and no pressure leaves the
     * ceiling alone - the cap is not a ratchet that only ever holds.
     */
    @Test
    public void testPressureShrinkStillShrinks() {
        assertEquals(500, MLKEMKeyFactory.pressureAdjustedMax(1000, 64, CAP, 0.95));
        assertEquals(750, MLKEMKeyFactory.pressureAdjustedMax(1000, 64, CAP, 0.75));
        assertEquals(1000, MLKEMKeyFactory.pressureAdjustedMax(1000, 64, CAP, 0.5));
    }
}
