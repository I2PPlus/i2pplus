package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pins the refusal a destination gets from the streaming tunnel filter, and
 * what that refusal costs when the destination keeps trying.
 *
 * <p>A live router refused 11,745 inbound connections from two destinations
 * in 133 minutes - 44 a minute each - and every one of them cost a full SYN
 * handshake, a reject response and an INFO line. The reason was built as
 * {@code new Reason("Destination blocked by Tunnel Filter", 0)}, and 0 seconds
 * is documented in {@code limitResponse()} as "omit it", so the peer was told
 * to retry immediately. It was also the only refusal in
 * {@code shouldRejectConnection()} that neither carried a wait nor armed a
 * ban: the hourly and daily throttlers ban, the per-minute throttler bans, and
 * the blacklist refusals use MAX_TIME. With the ban armed on the first
 * refusal, the next SYN takes the silent temp-ban branch instead.
 *
 * @since 0.9.72+
 */
public class TunnelFilterRefusalTest {

    /**
     * Refusals counted in the live log for the destinations the tunnel filter
     * blocked, over the window they were counted in.
     */
    private static final int MEASURED_REFUSALS = 11_745;

    /** The destinations those refusals came from. */
    private static final int MEASURED_DESTS = 2;

    /** Length of the measured window, in minutes. */
    private static final int MEASURED_MINUTES = 133;

    /** The production default of i2p.streaming.tempBanMinutes. */
    private static final int DEFAULT_BAN_MINUTES = 5;

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the refusal is built by a pure helper, so nothing is set up.
     */
    public TunnelFilterRefusalTest() {}

    /**
     * The defect: 0 seconds means the reject response carries no Retry-After
     * at all, so nothing tells the peer to back off. A refusal that will be
     * repeated has to name a wait.
     */
    @Test
    public void testRefusalCarriesARetryAfter() {
        int secs = ConnectionManager.tunnelFilterRefusal().getSeconds();
        assertTrue("0 means the header is omitted: " + secs, secs > 0);
        assertEquals(ConnectionManager.FILTER_RETRY_AFTER_S, secs);
    }

    /**
     * The wait has to be bounded. MAX_TIME is what the blacklist refusals use
     * and it means "do not come back"; the tunnel filter is configuration a
     * user can change, so a blocked destination has to be told to try again
     * well inside a day.
     */
    @Test
    public void testRetryAfterIsBoundedNotForever() {
        int secs = ConnectionManager.tunnelFilterRefusal().getSeconds();
        assertTrue("long enough to stop the hammering: " + secs, secs >= 600);
        assertTrue("short enough for a config edit to matter: " + secs,
                   secs < 86_400);
    }

    /**
     * The first refusal must stay visible: it is how an operator learns that
     * the filter, not a rate limit, is what stopped a peer. The silent flag is
     * reserved for repeats of an already-banned destination.
     */
    @Test
    public void testRefusalIsNotSilent() {
        assertFalse(ConnectionManager.tunnelFilterRefusal().isSilent());
    }

    /**
     * The text is the greppable identifier in the log, and it has to name the
     * subsystem responsible rather than a generic "refused".
     */
    @Test
    public void testRefusalNamesTheFilter() {
        String txt = ConnectionManager.tunnelFilterRefusal().toString();
        assertTrue("names the filter: " + txt, txt.contains("Tunnel Filter"));
    }

    /**
     * What the ban costs the flood. Walking the measured run at the measured
     * rate, with the ban armed on the first refusal and checked before the
     * filter as the code does, the log can hold at most one refusal per ban
     * window per destination.
     */
    @Test
    public void testBanningOnFirstRefusalBoundsTheLog() {
        int perMinute = MEASURED_REFUSALS / MEASURED_DESTS / MEASURED_MINUTES;
        int logged = loggedRefusalLines(perMinute, MEASURED_MINUTES,
                                        DEFAULT_BAN_MINUTES);
        assertEquals("one line per ban window, plus the refusal that armed it",
                     MEASURED_MINUTES / DEFAULT_BAN_MINUTES + 1, logged);
        assertTrue(perMinute * MEASURED_MINUTES + " refusals collapsed to " +
                   logged, logged * 20 <= perMinute * MEASURED_MINUTES);
    }

    /**
     * Without the ban, every attempt is logged - the pre-fix behaviour this
     * test exists to keep from coming back.
     */
    @Test
    public void testWithoutTheBanEveryAttemptIsLogged() {
        int perMinute = MEASURED_REFUSALS / MEASURED_DESTS / MEASURED_MINUTES;
        assertEquals(perMinute * MEASURED_MINUTES,
                     loggedRefusalLines(perMinute, MEASURED_MINUTES, 0));
    }

    /**
     * Counts the refusals that reach the log for one destination over
     * {@code minutes}, at an even rate, when a refusal arms a ban that hides
     * later ones.
     *
     * @param perMinute refusals the destination makes each minute
     * @param minutes length of the measured window
     * @param banMinutes how long a refusal bans the destination, 0 for no ban
     * @return the refusals that were logged
     */
    private static int loggedRefusalLines(int perMinute, int minutes,
                                          int banMinutes) {
        long banUntil = -1;
        int logged = 0;
        for (int m = 0; m < minutes; m++) {
            for (int i = 0; i < perMinute; i++) {
                if (m < banUntil) {
                    continue;
                }
                logged++;
                if (banMinutes > 0) {
                    banUntil = m + banMinutes;
                }
            }
        }
        return logged;
    }
}
