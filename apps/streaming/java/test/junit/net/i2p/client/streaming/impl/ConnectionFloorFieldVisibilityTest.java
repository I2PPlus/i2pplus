package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Cross-thread visibility of the goodput floor detector. The ACK path writes
 * these fields while FloorCheckEvent reads them on the timer thread to decide
 * whether a connection has stalled. They were plain long/double fields, which
 * the JMM does not require to be read atomically and gives no ordering guarantee
 * for, so the timer could act on a half-written or stale value.
 * <p>
 * This pins the declaration rather than the concurrency behaviour - a torn read
 * cannot be provoked on demand - so that dropping volatile again is a test
 * failure rather than a silent regression.
 *
 * @since 0.9.72+
 */
public class ConnectionFloorFieldVisibilityTest {

    /** Every floor-detector field shared between the ACK path and the timer thread. */
    private static final List<String> FLOOR_FIELDS = Arrays.asList(
        "_floorBaselineBps",
        "_floorSamples",
        "_floorSampleBytes",
        "_floorSampleStart",
        "_lastProgressAt",
        "_floorBelowCount",
        "_lastStallState");

    private static Field field(String name) {
        try {
            return Connection.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            fail("floor-detector field " + name + " is gone - update this test deliberately, " +
                 "it exists so the shared state stays visible across threads");
            return null;
        }
    }

    /** The 64-bit fields are the ones a plain read may tear. */
    @Test
    public void testWideFieldsAreVolatile() {
        for (String name : Arrays.asList("_floorBaselineBps", "_floorSampleBytes",
                                         "_floorSampleStart", "_lastProgressAt")) {
            Field f = field(name);
            assertTrue(name + " must be wide",
                       f.getType() == long.class || f.getType() == double.class);
            assertTrue(name + " must be volatile - the timer thread reads what the ACK path writes",
                       Modifier.isVolatile(f.getModifiers()));
        }
    }

    /** The narrow fields carry no tearing risk but still need the visibility edge. */
    @Test
    public void testNarrowFieldsAreVolatile() {
        for (String name : Arrays.asList("_floorSamples", "_floorBelowCount", "_lastStallState")) {
            Field f = field(name);
            assertTrue(name + " must be volatile", Modifier.isVolatile(f.getModifiers()));
        }
    }

    /**
     * The set is complete: every field the floor check reads on the timer thread
     * and the ACK path writes is covered here.
     */
    @Test
    public void testEveryFloorFieldIsCovered() {
        assertTrue("the list must not be empty", !FLOOR_FIELDS.isEmpty());
        for (String name : FLOOR_FIELDS)
            assertNotNull(field(name));
        // The floor check and the ACK path share exactly these.
        assertTrue(FLOOR_FIELDS.contains("_lastProgressAt"));
        assertTrue(FLOOR_FIELDS.contains("_floorBaselineBps"));
        assertEqualsCount();
    }

    /**
     * Guards against the list drifting away from the class: every non-final field
     * named floor or progress in Connection is in the list. Final fields such as
     * the FloorCheckEvent object itself are excluded - a final reference needs no
     * volatile to publish the object it points at.
     */
    private static void assertEqualsCount() {
        int found = 0;
        for (Field f : Connection.class.getDeclaredFields()) {
            String n = f.getName();
            if (Modifier.isFinal(f.getModifiers()))
                continue;
            if (n.startsWith("_floor") || n.equals("_lastProgressAt") || n.equals("_lastStallState")) {
                found++;
                assertTrue(n + " is in the covered list", FLOOR_FIELDS.contains(n));
                assertTrue(n + " must be volatile", Modifier.isVolatile(f.getModifiers()));
            }
        }
        assertTrue("found " + found + " shared floor fields, listed " + FLOOR_FIELDS.size(),
                   found == FLOOR_FIELDS.size());
    }
}
