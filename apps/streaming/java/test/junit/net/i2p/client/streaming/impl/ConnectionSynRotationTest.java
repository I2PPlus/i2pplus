package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the pure budget arithmetic
 * {@link Connection#synRotationRoomMs(long, long)} behind the SYN-phase tunnel
 * rotation's accounting.
 *
 * <p>A rotation asks the pool for a fresh outbound tunnel. If the pool has a
 * spare that is instant; if it does not, the rotation starts a build the connect
 * may not live to see, and that build is taken out of the same capacity the
 * running tunnels need. 1,342 rotations went through one log set, one per failed
 * connect, and nothing recorded how much handshake time any of them had left.
 * Live, one connect rotated 2,580ms into an 8,831ms SYN phase and died 6,251ms
 * later - room enough to ride a tunnel that already exists, nowhere near enough
 * for one that has to be built.
 *
 * @since 0.9.72+
 */
public class ConnectionSynRotationTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The arithmetic under test is static and holds no state.
     *
     * @since 0.9.72+
     */
    public ConnectionSynRotationTest() {}

    /** The room is what the budget has left, to the millisecond. */
    @Test
    public void roomIsTheBudgetLeft() {
        assertEquals(6_251L, Connection.synRotationRoomMs(8_831L, 2_580L));
        assertEquals(8_831L, Connection.synRotationRoomMs(8_831L, 0L));
    }

    /**
     * A phase that has run past its budget has no room, not a negative room: the
     * caller formats this as a duration and a negative one would read as a
     * duration that has not happened yet. A phase that reads as having run for
     * negative time - the clock went backwards - has all of it left, the same
     * answer as a phase that has not run at all.
     */
    @Test
    public void roomIsNeverNegative() {
        assertEquals(0L, Connection.synRotationRoomMs(8_831L, 8_831L));
        assertEquals(0L, Connection.synRotationRoomMs(8_831L, 9_000L));
        assertEquals(8_831L, Connection.synRotationRoomMs(8_831L, -1L));
    }

    /**
     * No configured SYN budget means no wall-clock bound, and this reports 0
     * rather than inventing one - the stat says "no budget to measure against",
     * not "the connect had all the time in the world".
     */
    @Test
    public void noBudgetReportsNoRoom() {
        assertEquals(0L, Connection.synRotationRoomMs(0L, 2_580L));
        assertEquals(0L, Connection.synRotationRoomMs(-1L, 0L));
    }

    /**
     * The room falls as the phase ages, monotonically and never back up: a
     * rotation spent later in the same connect can only have less of it, which is
     * what makes the stat usable for a gate rather than only for a report.
     */
    @Test
    public void roomFallsMonotonicallyWithAge() {
        long budget = 30_000L;
        long previous = Connection.synRotationRoomMs(budget, 0L);
        for (long age = 1_000L; age <= 40_000L; age += 1_000L) {
            long room = Connection.synRotationRoomMs(budget, age);
            assertTrue("age " + age + " gave more room than " + (age - 1_000L), room <= previous);
            previous = room;
        }
        assertEquals(0L, previous);
    }
}
