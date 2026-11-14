package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins how long the announce loop waits before its next round and how that wait is
 * named in the log. The wait is what the whole torrent's peer discovery runs on: a
 * downloading torrent that is failing its trackers wakes every 90 seconds, one that is
 * healthy every five, and a seeding one every fifteen. The requeue line used to print
 * the wait on every round - 11,545 lines in a four hour window - so it now reports the
 * cadence and prints only when that moves, which requires the three cadences to be
 * tellable apart from their jittered numbers.
 *
 * @since 0.9.72+
 */
public class AnnounceCadenceTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public AnnounceCadenceTest() {}

    /**
     * A seeding torrent waits three times the steady interval plus its jitter.
     */
    @Test
    public void testASeedingTorrentWaitsThreeTimesAsLong() {
        assertEquals(3 * TrackerClient.SLEEP * 60 * 1000 + 7_000,
                     TrackerClient.nextRoundDelay(true, false, 0, false, true, 7_000));
    }

    /**
     * A torrent whose trackers are failing wakes at the retry interval, and keeps doing
     * so until the failure run reaches its cap - at which point the problems stop
     * buying fast wake-ups and the torrent settles to the steady interval. The cap is
     * what the loop's own counter increment has to preserve.
     */
    @Test
    public void testFailuresWakeFastUntilTheCap() {
        assertEquals(TrackerClient.INITIAL_SLEEP,
                     TrackerClient.nextRoundDelay(false, true, 1, false, true, 0));
        assertEquals(TrackerClient.INITIAL_SLEEP,
                     TrackerClient.nextRoundDelay(false, true, TrackerClient.MAX_CONSEC_FAILS - 1,
                                                  false, true, 0));
        // at the cap the problems no longer hold the torrent at the retry interval
        assertEquals(TrackerClient.SLEEP * 60 * 1000,
                     TrackerClient.nextRoundDelay(false, true, TrackerClient.MAX_CONSEC_FAILS,
                                                  false, true, 0));
    }

    /**
     * A torrent that has never announced successfully, and one with no metadata yet,
     * both wake at the retry interval rather than sitting out the steady one.
     */
    @Test
    public void testAnUnstartedOrMetadataLessTorrentWakesFast() {
        assertEquals(TrackerClient.INITIAL_SLEEP,
                     TrackerClient.nextRoundDelay(false, false, 0, true, true, 0));
        assertEquals(TrackerClient.INITIAL_SLEEP,
                     TrackerClient.nextRoundDelay(false, false, 0, false, false, 0));
    }

    /**
     * A healthy downloading torrent waits the steady interval plus its jitter, and the
     * jitter never pushes it into the seeding range.
     */
    @Test
    public void testTheSteadyIntervalStaysBelowTheSeedingOne() {
        assertEquals(TrackerClient.SLEEP * 60 * 1000,
                     TrackerClient.nextRoundDelay(false, false, 0, false, true, 0));
        int steadyMax = TrackerClient.SLEEP * 60 * 1000 + 119_999;
        assertEquals("the jitter must not reach the seeding cadence",
                     "steady", TrackerClient.roundCadence(steadyMax));
        assertEquals("seeding", TrackerClient.roundCadence(3 * TrackerClient.SLEEP * 60 * 1000));
    }

    /**
     * Each cadence names itself, and the retry interval - the only one carried without
     * jitter - is recognised exactly.
     */
    @Test
    public void testEachCadenceNamesItself() {
        assertEquals("retry after failures", TrackerClient.roundCadence(TrackerClient.INITIAL_SLEEP));
        assertEquals("steady", TrackerClient.roundCadence(TrackerClient.SLEEP * 60 * 1000 + 60_000));
        assertEquals("seeding",
                     TrackerClient.roundCadence(3 * TrackerClient.SLEEP * 60 * 1000 + 119_999));
    }

    /**
     * Every delay the loop can produce is classified, and no delay falls into two
     * cadences: the retry value carries no jitter, the steady one tops out under three
     * times the base interval, and the seeding one starts there.
     */
    @Test
    public void testTheCadencesDoNotOverlap() {
        int retry = TrackerClient.INITIAL_SLEEP;
        int steadyMin = TrackerClient.SLEEP * 60 * 1000;
        int steadyMax = steadyMin + 119_999;
        int seedingMin = 3 * TrackerClient.SLEEP * 60 * 1000;
        assertTrue("retry must sit below the steady interval", retry < steadyMin);
        assertTrue("the steady jitter must not reach the seeding interval",
                   steadyMax < seedingMin);
        assertEquals(retry, TrackerClient.nextRoundDelay(false, true, 1, false, true, 119_999));
    }
}
