package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import net.i2p.util.LogSuppressor;

/**
 * Tests the cadence of this layer's busiest log lines.
 *
 * <p>{@link Connection#shouldLogResend(int)} decides which resends get a log
 * line. One line per send per packet was the single largest producer of log
 * volume in this layer: 123,962 lines in one 22-minute rotation of a live
 * router's log, 71% of them from one connection, with the tail at 181 sends of a
 * single packet. The first sends of a packet are where a stall first becomes
 * visible, so those are logged in full and then every tenth send, which keeps the
 * growing count reportable at a tenth of the volume.
 *
 * <p>The rest of the tests cover the recurring lines: when a resend line carries
 * the full packet dump, and the suppressor keys that let the router collapse the
 * repeats of a condition it reports once a burst rather than once a packet. That
 * covers the connect-phase pair as well - in a later 80-minute window, with the
 * per-packet conditions already collapsed, the SYN rotations and give-ups still
 * wrote 38,961 lines and the acked-resend and ACK lines another 76,429, together
 * 13.1% of everything logged.
 *
 * @since 0.9.72+
 */
public class ResendLogCadenceTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the cadence decision is pure, so there is nothing to set up.
     *
     * @since 0.9.72+
     */
    public ResendLogCadenceTest() {}

    /** The first three sends of a packet are always logged. */
    @Test
    public void testFirstSendsLogged() {
        assertTrue(Connection.shouldLogResend(1));
        assertTrue(Connection.shouldLogResend(2));
        assertTrue(Connection.shouldLogResend(3));
    }

    /** A send that is not a multiple of ten in between is skipped. */
    @Test
    public void testMiddleSendsSkipped() {
        for (int n = 4; n <= 9; n++)
            assertFalse("sends=" + n, Connection.shouldLogResend(n));
        for (int n = 11; n <= 19; n++)
            assertFalse("sends=" + n, Connection.shouldLogResend(n));
    }

    /** Every tenth send is logged, so the count keeps reporting as it grows. */
    @Test
    public void testEveryTenthLogged() {
        for (int n = 10; n <= 200; n += 10)
            assertTrue("sends=" + n, Connection.shouldLogResend(n));
    }

    /**
     * The measured worst case: the packet that was resent 181 times produced 21
     * lines instead of 181, and the last logged count before the cap is still
     * close to the real one.
     */
    @Test
    public void testMeasuredWorstCaseVolume() {
        int logged = 0;
        for (int n = 1; n <= 181; n++)
            if (Connection.shouldLogResend(n))
                logged++;
        assertEquals(21, logged);
        assertTrue(Connection.shouldLogResend(180));
        assertFalse(Connection.shouldLogResend(181));
    }

    /** A zero or negative send count is logged rather than silently dropped. */
    @Test
    public void testNonPositiveSendsLogged() {
        assertTrue(Connection.shouldLogResend(0));
        assertTrue(Connection.shouldLogResend(-1));
    }

    // ---------- recurring lines ----------

    /**
     * The packet dump rides the first send of a packet only. The send count and
     * sequence are on the line either way, and the dump is nine lines: the
     * resend line appeared 22,711 times in one 48-minute rotation of a live
     * router's log.
     */
    @Test
    public void testDumpRidesTheFirstSendOnly() {
        assertTrue(Connection.shouldDumpResentPacket(1));
        assertFalse(Connection.shouldDumpResentPacket(2));
        assertFalse(Connection.shouldDumpResentPacket(181));
        // a packet reported as never sent still gets the dump
        assertTrue(Connection.shouldDumpResentPacket(0));
    }

    /**
     * The two cadences compose: a packet that is logged at all on a later send
     * is still a one-line entry, so the surviving lines are short.
     */
    @Test
    public void testSurvivingLinesAreShort() {
        int dumps = 0;
        int lines = 0;
        for (int n = 1; n <= 181; n++) {
            if (Connection.shouldLogResend(n)) {
                lines++;
                if (Connection.shouldDumpResentPacket(n)) {dumps++;}
            }
        }
        assertEquals(21, lines);
        assertEquals("only the first send carries a dump", 1, dumps);
    }

    // ---------- recurring-line suppressor keys ----------

    /**
     * A suppressor key has to name the condition, not the occurrence, or every
     * line passes the suppressor and the flood continues.
     */
    @Test
    public void testPeriodicKeyNamesTheCondition() {
        String label = "From: [by7luzwh] * To: [ca2njqii]";
        String other = "From: [by7luzwh] * To: [pi3lt5it]";
        String pass = Connection.periodicDebugKey(label, "retx-pass");
        assertEquals(label + ":retx-pass", pass);
        assertEquals("stable across occurrences", pass,
                     Connection.periodicDebugKey(label, "retx-pass"));
        assertNotEquals("one key per condition", pass,
                        Connection.periodicDebugKey(label, "window-cut"));
        assertNotEquals("one key per connection", pass,
                        Connection.periodicDebugKey(other, "retx-pass"));
    }

    /**
     * The measured flood run through the house suppressor: one connection wrote
     * 31,301 timer-fire lines in a single 48-minute rotation of a live router's
     * log; the same occurrences under a condition key collapse to one line per
     * thousand, so the condition stays reportable every few seconds instead of
     * every packet.
     */
    @Test
    public void testSuppressorCollapsesTheMeasuredFlood() {
        LogSuppressor sup = new LogSuppressor();
        String label = "From: [by7luzwh] * To: [nnvz55wt]";
        String key = Connection.periodicDebugKey(label, "retx-pass");
        long now = 1_700_000_000_000L;
        int written = 0;
        for (int i = 0; i < 31_301; i++) {
            now += 92;
            if (sup.record(key, now).log) {written++;}
        }
        assertEquals(48, written);
        assertTrue("at least a 600-fold cut",
                   written * 600 <= 31_301);
    }

    /**
     * The connect-phase and ACK lines added after that first sweep each need
     * their own key, and a key per connection: a client tunnel that dialed
     * hundreds of peers that never answer must report each peer's failure once,
     * not lose the later peers behind one shared counter.
     */
    @Test
    public void testConnectPhaseConditionsHaveTheirOwnKeys() {
        String label = "From: [by7luzwh] * To: [o3nim53d]";
        Set<String> keys = new HashSet<>();
        String[] conditions = {"ack-sent", "resend-acked", "syn-give-up",
                               "syn-rotate"};
        for (String cond : conditions) {
            assertTrue("one key per condition: " + cond,
                       keys.add(Connection.periodicDebugKey(label, cond)));
        }
        assertEquals(conditions.length, keys.size());
    }

    /**
     * The second measured window, taken after the first seven conditions were
     * already collapsed: 28,199 acked-resend records, 20,234 SYN rotations,
     * 18,727 SYN give-ups and 6,188 ACK records - 73,348 records, and 115,390
     * physical lines once the packet reports are counted, 13.1% of everything
     * the router logged in 80 minutes. Run through the suppressor at that rate
     * they collapse to a couple of lines a minute per condition, and every
     * condition still reports.
     */
    @Test
    public void testSuppressorCollapsesTheMeasuredSecondWindow() {
        int[] occurrences = {28_199, 20_234, 18_727, 6_188};
        String[] conditions = {"resend-acked", "syn-rotate", "syn-give-up",
                               "ack-sent"};
        String label = "From: [by7luzwh] * To: [o3nim53d]";
        long windowMs = 80 * 60 * 1000L;
        int written = 0;
        for (int c = 0; c < conditions.length; c++) {
            LogSuppressor sup = new LogSuppressor();
            String key = Connection.periodicDebugKey(label, conditions[c]);
            long now = 1_700_000_000_000L;
            long spacing = windowMs / occurrences[c];
            for (int i = 0; i < occurrences[c]; i++) {
                now += spacing;
                if (sup.record(key, now).log) {written++;}
            }
        }
        assertTrue("every condition stays reportable",
                   written >= conditions.length);
        assertTrue("a 100-fold cut at least, got " + written + " of 73,348",
                   written * 100 <= 73_348);
    }

    /**
     * A collapsed run has to say how big it was on the line that survives it, or
     * a reader cannot tell a suppressed flood from a quiet connection.
     */
    @Test
    public void testCollapsedRunsCarryTheirCount() {
        LogSuppressor sup = new LogSuppressor();
        String label = "From: [by7luzwh] * To: [o3nim53d]";
        String key = Connection.periodicDebugKey(label, "syn-rotate");
        long now = 1_700_000_000_000L;
        long largest = 0;
        // The measured rate: 20,234 rotations over 80 minutes of a live log.
        for (int i = 0; i < 20_234; i++) {
            now += 237;
            LogSuppressor.Decision d = sup.record(key, now);
            if (d.log && d.suppressed > largest) {largest = d.suppressed;}
        }
        // At that rate the 60-second window, not the 1,000-message burst, ends
        // a suppression run, so a surviving line stands for some 250 of them.
        assertTrue("the surviving line reports the run it stands for, got " +
                   largest, largest >= 200);
    }
}
