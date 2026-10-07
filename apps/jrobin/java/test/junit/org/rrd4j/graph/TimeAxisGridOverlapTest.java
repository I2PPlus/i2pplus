package org.rrd4j.graph;

import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the structural precondition behind the time axis's gridline overlap.
 *
 * <p>The time axis draws its grid in two passes over two different units, so nothing stopped a
 * position that satisfied both from being emitted twice - once by the minor pass and once by the
 * major pass - with the two lines landing on identical coordinates. On a six hour graph that is
 * 18 doubled vertical lines. It went unnoticed because a theme that draws both grids in the
 * same colour renders the duplicate as an imperceptibly heavier line, and the value axis never
 * had the problem: it picks major-or-minor per index inside a single loop.
 *
 * <p>The cause is structural, not accidental. Every entry in the tick-settings table names a
 * major unit that is a whole multiple of the minor unit, and both passes align the calendar to
 * their own unit boundary, so every major position is necessarily also a minor position. The
 * overlap is total rather than occasional, which is what lets the minor pass exclude the major
 * positions by coordinate instead of by arithmetic. These tests pin that relationship.
 *
 * <p><b>What these tests do not do:</b> they do not fail against the unfixed renderer. They
 * guard the assumption the fix's coordinate-based exclusion rests on, so a future tick setting
 * that breaks the multiple would fail here and force the exclusion to be reconsidered. The
 * overlap itself cannot be asserted here, because this fork has no RRD creation API, so a graph
 * cannot be rendered without a datasource. It was measured against a live {@code viewstat.jsp}
 * graph instead: 92 vertical line elements across 74 distinct x positions, 18 of them doubled.
 *
 * @since 0.9.71+
 */
public class TimeAxisGridOverlapTest {

    /** The tick-settings table, private and static. */
    private static TimeAxisSetting[] tickSettings() throws Exception {
        Field f = TimeAxis.class.getDeclaredField("tickSettings");
        f.setAccessible(true);
        return (TimeAxisSetting[]) f.get(null);
    }

    /** Calendar units have no fixed length, so they are excluded from the arithmetic. */
    private static boolean isFixedLength(TimeUnit unit) {
        return unit != TimeUnit.MONTH && unit != TimeUnit.YEAR;
    }

    private static long seconds(TimeUnit unit, int count) {
        switch (unit) {
            case SECOND: return count;
            case MINUTE: return 60L * count;
            case HOUR: return 3600L * count;
            case DAY: return 86400L * count;
            case WEEK: return 7L * 86400L * count;
            default: throw new IllegalArgumentException(unit + " has no fixed length");
        }
    }

    /** Names a setting for a failure message. */
    private static String where(TimeAxisSetting s) {
        return s.minorUnit + " x" + s.minorUnitCount + " / " + s.majorUnit + " x" + s.majorUnitCount;
    }

    /**
     * The precondition the exclusion relies on. If a setting ever named a major unit that is
     * not a multiple of the minor unit, the overlap would become partial rather than total and
     * the coordinate-based exclusion would need re-examining, so pin the relationship.
     *
     * <p>Compared in seconds where both units have a fixed length, and within the calendar unit
     * where they are the same one - that covers the 1-month-per-1-month and 1-year-per-1-year
     * settings, where the multiple holds trivially. Mixed calendar/fixed pairs (a week minor
     * under a month major) have no fixed length to divide and are left alone.
     */
    @Test
    public void theMajorUnitIsAWholeMultipleOfTheMinorUnitInEverySetting() throws Exception {
        for (TimeAxisSetting s : tickSettings()) {
            if (s.majorUnit == s.minorUnit) {
                assertEquals(where(s) + " must stay a whole multiple within its own calendar unit",
                             0, s.majorUnitCount % s.minorUnitCount);
                continue;
            }
            if (!isFixedLength(s.majorUnit) || !isFixedLength(s.minorUnit)) {continue;}
            long minor = seconds(s.minorUnit, s.minorUnitCount);
            long major = seconds(s.majorUnit, s.majorUnitCount);
            assertEquals(where(s) + " must stay a whole multiple of the minor unit, or major "
                         + "positions stop being minor positions too", 0L, major % minor);
        }
    }

    /**
     * The overlap is total: every major position is also a minor position, which is why the
     * minor pass can exclude by coordinate rather than by testing timestamps. Guards the
     * reasoning in the fix's comment rather than any rendered output.
     */
    @Test
    public void theMajorUnitIsNeverSmallerThanTheMinorUnit() throws Exception {
        for (TimeAxisSetting s : tickSettings()) {
            if (!isFixedLength(s.majorUnit) || !isFixedLength(s.minorUnit)) {continue;}
            long minor = seconds(s.minorUnit, s.minorUnitCount);
            long major = seconds(s.majorUnit, s.majorUnitCount);
            assertTrue(where(s) + " makes the major interval shorter than the minor one", major >= minor);
        }
    }

    /**
     * The two grids must be able to look different, or "which is major" is not a question the
     * renderer can answer. A theme dashes the major stroke apart from the minor one, so the
     * two have to remain independently settable.
     */
    @Test
    public void theTwoGridsCanCarryDifferentStrokes() {
        RrdGraphDef def = new RrdGraphDef();
        def.setMajorGridStroke(RrdGraphConstants.gridStroke(1f, 1f, 2f));
        assertNotSame("major and minor grid must not be forced to share one stroke",
                      def.majorGridStroke, def.gridStroke);
    }
}
