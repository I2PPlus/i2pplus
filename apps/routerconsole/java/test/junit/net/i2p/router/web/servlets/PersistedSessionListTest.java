package net.i2p.router.web.servlets;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests the persisted "remember me" session list written to router.config.
 * <p>
 * The writer used to append unconditionally: every login added an entry,
 * expired entries were never removed even though the loader skips them, and
 * nothing bounded the value - so a long-running router accumulated one
 * bearer token per login in its config file for the rest of its life. What is
 * pinned here is that the list stays pruned, deduplicated and bounded, and that
 * the entry format still matches what loadPersistedSessions() reads back.
 *
 * @since 0.9.72+
 */
public class PersistedSessionListTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 60 * 60 * 1000L;

    /**
     * Split a stored value the way the loader does.
     *
     * @param value the config value
     * @return its entries
     */
    private static List<String> entries(String value) {
        List<String> out = new ArrayList<>();
        for (String e : value.split("\\|")) {
            if (!e.isEmpty()) {out.add(e);}
        }
        return out;
    }

    /**
     * A stored value of n already-valid entries, oldest first.
     *
     * @param n how many
     * @return the stored value
     */
    private static String stored(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {sb.append('|');}
            sb.append("tok").append(i).append("=user").append(i).append(':').append(NOW + HOUR);
        }
        return sb.toString();
    }

    /**
     * Adding to an empty list produces exactly the new entry.
     */
    @Test
    public void testFirstSessionIsTheWholeList() {
        String out = LoginServlet.addPersistedSession(null, "tokA", "alice", NOW + HOUR, NOW);
        assertEquals("tokA=alice:" + (NOW + HOUR), out);
    }

    /**
     * An entry whose expiry has passed is dropped on the next write. The loader
     * already skips it, so keeping it only bloats the config for the router's
     * whole uptime.
     */
    @Test
    public void testExpiredEntriesAreDropped() {
        String existing = "old=alice:" + (NOW - 1) + "|keep=bob:" + (NOW + HOUR);
        String out = LoginServlet.addPersistedSession(existing, "tokA", "carol", NOW + HOUR, NOW);
        List<String> list = entries(out);
        assertEquals(2, list.size());
        assertFalse(out.contains("old=alice"));
        assertTrue(out.contains("keep=bob:"));
        assertTrue(out.endsWith("tokA=carol:" + (NOW + HOUR)));
    }

    /**
     * A session with no expiry (-1) is never dropped for being expired.
     */
    @Test
    public void testSessionsWithoutExpirySurvive() {
        String out = LoginServlet.addPersistedSession("forever=alice:-1", "tokA", "bob", NOW + HOUR, NOW);
        assertTrue(out.contains("forever=alice:-1"));
        assertEquals(2, entries(out).size());
    }

    /**
     * Re-logging in with a token already in the list replaces that entry rather
     * than adding a second one for the same session.
     */
    @Test
    public void testSameTokenIsReplacedNotAppended() {
        String first = LoginServlet.addPersistedSession(null, "tokA", "alice", NOW + HOUR, NOW);
        String second = LoginServlet.addPersistedSession(first, "tokA", "alice", NOW + 2 * HOUR, NOW);
        assertEquals(1, entries(second).size());
        assertTrue(second.endsWith("tokA=alice:" + (NOW + 2 * HOUR)));
    }

    /**
     * The list is capped, and the entry just added is always kept - trimming has
     * to take the oldest survivors, not the new session that triggered the write.
     */
    @Test
    public void testListIsCappedAndKeepsTheNewest() {
        String out = LoginServlet.addPersistedSession(stored(LoginServlet.MAX_PERSISTED_SESSIONS * 2),
                                                      "tokNew", "carol", NOW + HOUR, NOW);
        List<String> list = entries(out);
        assertEquals(LoginServlet.MAX_PERSISTED_SESSIONS, list.size());
        assertEquals("tokNew=carol:" + (NOW + HOUR), list.get(list.size() - 1));
        // the oldest survivors are the ones that went
        assertFalse(out.contains("tok0=user0"));
        assertTrue(out.contains("tok" + (LoginServlet.MAX_PERSISTED_SESSIONS * 2 - 1) + '='));
    }

    /**
     * Malformed stored entries are dropped rather than carried forward: the
     * loader cannot use any of them, and a corrupt value used to be rewritten
     * verbatim on every login.
     */
    @Test
    public void testMalformedEntriesAreDropped() {
        String existing = "=nokey|noequals|tokNouser|tokBad=user:notanumber|ok=dave:" + (NOW + HOUR);
        String out = LoginServlet.addPersistedSession(existing, "tokA", "erin", NOW + HOUR, NOW);
        List<String> list = entries(out);
        assertEquals(2, list.size());
        assertTrue(out.contains("ok=dave:"));
        assertTrue(out.endsWith("tokA=erin:" + (NOW + HOUR)));
    }

    /**
     * The writer and the loader have to agree on the entry format - an entry
     * this produces must be readable back as token, username and expiry.
     */
    @Test
    public void testEntryFormatMatchesTheLoader() {
        long expires = NOW + 3 * HOUR;
        String out = LoginServlet.addPersistedSession(null, "tokA", "alice", expires, NOW);
        String entry = entries(out).get(0);
        int eq = entry.indexOf('=');
        assertTrue(eq > 0);
        String token = entry.substring(0, eq);
        String data = entry.substring(eq + 1);
        int colon = data.indexOf(':');
        assertTrue(colon > 0);
        assertEquals("tokA", token);
        assertEquals("alice", data.substring(0, colon));
        assertEquals(expires, Long.parseLong(data.substring(colon + 1)));
    }
}
