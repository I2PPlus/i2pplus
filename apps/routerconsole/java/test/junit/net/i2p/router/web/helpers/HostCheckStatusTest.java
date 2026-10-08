package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 *  The decisions behind hiding dead sites from the Sites of Interest listing.
 *
 *  <p>Two sources decide: whether the naming service resolves the host, and what the last probe
 *  said. Each method here settles one of those, and each is pinned directly, because getting any
 *  of them wrong silently removes a link from the console - a failure no other test would catch.
 *
 *  @since 0.9.71+
 */
public class HostCheckStatusTest {


    private static java.util.Set<String> blacklist(String... lines) {
        Set<String> out = new HashSet<>();
        for (String line : lines) { HostCheckStatus.parseBlacklistLine(line, out); }
        return out;
    }

    private static Map<String, Boolean> probes(String... lines) {
        Map<String, Boolean> out = new HashMap<>();
        for (String line : lines) { HostCheckStatus.parseProbeLine(line, out); }
        return out;
    }

    // ---- parseProbeLine -----------------------------------------------------

    @Test
    public void reachableRowIsRecorded() {
        Map<String, Boolean> s = probes("1791429608769,exil3.i2p,y,forum,34765,[6,4]");
        assertEquals(1, s.size());
        assertEquals(Boolean.TRUE, s.get("exil3.i2p"));
    }

    @Test
    public void unreachableRowIsRecorded() {
        assertEquals(Boolean.FALSE, probes("1766890390409,zhuge.i2p,n,wip,-1,[]").get("zhuge.i2p"));
    }

    /**
     *  The trailing field holds commas of its own. The split is positional, so the fields we
     *  read must still land in the right place.
     */
    @Test
    public void commasInTheLeaseSetFieldDoNotShiftTheColumns() {
        Map<String, Boolean> s = probes("1791429608769,lr.vern.i2p,y,unknown,-1,[6,4]");
        assertEquals(Boolean.TRUE, s.get("lr.vern.i2p"));
    }

    @Test
    public void commentAndBlankLinesAreSkipped() {
        Map<String, Boolean> s = probes(
            "# I2P+ Address Book Host Check",
            "# Format: timestamp,host,reachable,category,responseTime,leaseSetTypes",
            "",
            "   ",
            "1766890352749,mattint-jabber.i2p,n,wip,-1,[]");
        assertEquals(1, s.size());
        assertEquals(Boolean.FALSE, s.get("mattint-jabber.i2p"));
    }

    /** A half-written row must not become a verdict and hide a working site. */
    @Test
    public void shortRowsAreIgnored() {
        assertTrue(probes("1791429608769", "1791429608769,exil3.i2p", "exil3.i2p").isEmpty());
    }

    @Test
    public void unknownReachableFlagIsIgnored() {
        assertTrue(probes("1791429608769,exil3.i2p,maybe,forum,34765,[6,4]").isEmpty());
    }

    @Test
    public void emptyHostIsIgnored() {
        assertTrue(probes("1791429608769,,y,forum,34765,[6,4]").isEmpty());
    }

    @Test
    public void probeHostIsLowercased() {
        assertEquals(Boolean.TRUE, probes("1791429608769,ExiL3.i2p,y,forum,1,[]").get("exil3.i2p"));
    }

    @Test
    public void laterProbeRowWins() {
        Map<String, Boolean> s = probes(
            "1791429608769,exil3.i2p,n,forum,-1,[]",
            "1791429608769,exil3.i2p,y,forum,34765,[6,4]");
        assertEquals(Boolean.TRUE, s.get("exil3.i2p"));
    }

    @Test
    public void nullProbeInputIsSurvivable() {
        Map<String, Boolean> s = new HashMap<>();
        HostCheckStatus.parseProbeLine(null, s);
        assertTrue(s.isEmpty());
    }

    // ---- isDown -------------------------------------------------------------

    /** Working site: resolves by name and last probe succeeded. Shown. */
    @Test
    public void reachableSiteIsShown() {
        assertFalse(HostCheckStatus.isDown("up.i2p", true, probes("1,up.i2p,y,forum,34765,[6,4]")));
    }

    /** Dead site: resolves by name but last probe failed. Hidden. */
    @Test
    public void unreachableSiteIsHidden() {
        assertTrue(HostCheckStatus.isDown("down.i2p", true, probes("1,down.i2p,n,wip,-1,[]")));
    }

    /** Not in the addressbook: cannot be reached by name at all. Hidden. */
    @Test
    public void siteAbsentFromTheAddressbookIsHidden() {
        assertTrue(HostCheckStatus.isDown("gone.i2p", false, probes("1,gone.i2p,y,forum,1,[]")));
    }

    /** A successful probe cannot rescue a site the naming service cannot resolve. */
    @Test
    public void aProbeResultCannotRescueAnUnknownSite() {
        assertTrue("membership is checked first and wins",
                   HostCheckStatus.isDown("gone.i2p", false, probes("1,gone.i2p,y,forum,1,[]")));
    }

    /** In the addressbook but never probed: untested is not down, so shown. */
    @Test
    public void knownButUntestedSiteIsShown() {
        assertFalse(HostCheckStatus.isDown("fresh.i2p", true, probes()));
    }

    /** Neither known nor probed is still hidden, on the membership test alone. */
    @Test
    public void unknownAndUntestedSiteIsHidden() {
        assertTrue(HostCheckStatus.isDown("stranger.i2p", false, probes()));
    }

    /** No results file means nothing has been probed, so nothing is judged down. */
    @Test
    public void absentProbeResultsJudgeNothing() {
        assertFalse(HostCheckStatus.isDown("up.i2p", true, null));
        assertFalse(HostCheckStatus.isDown("up.i2p", true, new HashMap<String, Boolean>()));
    }

    @Test
    public void nullHostIsNotHidden() {
        assertFalse(HostCheckStatus.isDown(null, true, probes("1,up.i2p,y,forum,1,[]")));
    }

    /**
     *  A recovered site reappears as soon as a later probe succeeds, which is the whole point of
     *  hiding on failure rather than deleting the link.
     */
    @Test
    public void aSiteThatComesBackIsShownAgain() {
        Map<String, Boolean> probed = probes("1,flaky.i2p,n,forum,-1,[]");
        assertTrue(HostCheckStatus.isDown("flaky.i2p", true, probed));
        HostCheckStatus.parseProbeLine("2,flaky.i2p,y,forum,1200,[6,4]", probed);
        assertFalse(HostCheckStatus.isDown("flaky.i2p", true, probed));
    }

    // ---- hostFromUrl --------------------------------------------------------

    @Test
    public void hostIsExtractedFromAFullUrl() {
        assertEquals("exil3.i2p", HostCheckStatus.hostFromUrl("http://exil3.i2p/"));
        assertEquals("exil3.i2p", HostCheckStatus.hostFromUrl("https://exil3.i2p/some/path?q=1"));
    }

    @Test
    public void hostIsLowercased() {
        assertEquals("exil3.i2p", HostCheckStatus.hostFromUrl("http://ExiL3.I2P/"));
    }

    @Test
    public void portIsStripped() {
        assertEquals("exil3.i2p", HostCheckStatus.hostFromUrl("http://exil3.i2p:8080/"));
    }

    /** A .b32 name is an I2P name and must be judged like any other. */
    @Test
    public void b32NameIsExtractedWhole() {
        assertEquals("abcdef.b32.i2p", HostCheckStatus.hostFromUrl("http://abcdef.b32.i2p/"));
    }

    @Test
    public void clearnetHostIsExtractedButNotAnI2pHost() {
        String host = HostCheckStatus.hostFromUrl("https://example.com/x");
        assertEquals("example.com", host);
        assertFalse(HostCheckStatus.isI2pHost(host));
    }

    @Test
    public void noHostIsNull() {
        assertNull(HostCheckStatus.hostFromUrl(null));
        assertNull(HostCheckStatus.hostFromUrl(""));
        assertNull(HostCheckStatus.hostFromUrl("   "));
        assertNull(HostCheckStatus.hostFromUrl("http://"));
    }

    // ---- isI2pHost ----------------------------------------------------------

    @Test
    public void i2pHostsAreInScope() {
        assertTrue(HostCheckStatus.isI2pHost("exil3.i2p"));
        assertTrue(HostCheckStatus.isI2pHost("abcdef.b32.i2p"));
    }

    /** These must always be shown, since the addressbook cannot hold them. */
    @Test
    public void nonI2pHostsAreOutOfScope() {
        assertFalse(HostCheckStatus.isI2pHost("example.com"));
        assertFalse(HostCheckStatus.isI2pHost("127.0.0.1"));
        assertFalse(HostCheckStatus.isI2pHost("/webmail"));
        assertFalse(HostCheckStatus.isI2pHost("i2pmetrics"));
        assertFalse(HostCheckStatus.isI2pHost(null));
    }

    /** A bare "i2p" must not qualify; only dotted names are sites. */
    @Test
    public void bareI2pIsNotAHost() {
        assertFalse(HostCheckStatus.isI2pHost("i2p"));
    }

    // ---- parseInterval ------------------------------------------------------

    /**
     *  The live config reads "pingInterval=1", which means one hour. Reading it as one minute
     *  would expire the cache 60x too often and re-read the file on most requests.
     */
    @Test
    public void bareNumberIsHours() {
        assertEquals(60L * 60L * 1000L, HostCheckStatus.parseInterval("1"));
        assertEquals(4L * 60L * 60L * 1000L, HostCheckStatus.parseInterval("4"));
    }

    @Test
    public void trailingMIsMinutes() {
        assertEquals(30L * 60L * 1000L, HostCheckStatus.parseInterval("30M"));
        assertEquals(30L * 60L * 1000L, HostCheckStatus.parseInterval("30m"));
    }

    @Test
    public void fourHoursIsTheDefault() {
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval("4"));
    }

    @Test
    public void unusableValuesFallBackToTheDefault() {
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval(null));
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval(""));
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval("   "));
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval("soon"));
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval("M"));
    }

    /** Zero or negative would make the cache permanently stale and re-read every request. */
    @Test
    public void nonPositiveIntervalsFallBackToTheDefault() {
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval("0"));
        assertEquals(HostCheckStatus.DEFAULT_INTERVAL_MS, HostCheckStatus.parseInterval("-4"));
    }

    // ---- end to end ---------------------------------------------------------

    /** The listing's behaviour over a status file shaped like the real one. */
    @Test
    public void listingHidesOnlyUnreachableOrUnknownSites() {
        Map<String, Boolean> probed = probes(
            "1,up.i2p,y,forum,34765,[6,4]", "1,down.i2p,n,wip,-1,[]");
        assertFalse(HostCheckStatus.isDown("up.i2p", true, probed));
        assertTrue(HostCheckStatus.isDown("down.i2p", true, probed));
        assertFalse(HostCheckStatus.isDown("untested.i2p", true, probed));
        assertTrue(HostCheckStatus.isDown("not-in-book.i2p", false, probed));
    }

    // ---- blacklist -----------------------------------------------------------

    /** A blacklisted host is never offered, whatever the probe results say. */
    @Test
    public void blacklistedHostIsNotOffered() {
        Set<String> bl = blacklist("ahmia.i2p", "cake.i2p");
        assertTrue(HostCheckStatus.isBlacklisted("ahmia.i2p", bl));
        assertFalse(HostCheckStatus.isBlacklisted("exil3.i2p", bl));
    }

    /**
     *  Being blacklisted outranks being up: a successful probe must not earn a link back.
     */
    @Test
    public void blacklistOutranksASuccessfulProbe() {
        Set<String> bl = blacklist("ahmia.i2p");
        Map<String, Boolean> probed = probes("1,ahmia.i2p,y,forum,34765,[6,4]");
        // "not down" is what the probe says; the link is withheld anyway.
        assertFalse(HostCheckStatus.isDown("ahmia.i2p", true, probed));
        assertTrue(HostCheckStatus.isBlacklisted("ahmia.i2p", bl));
    }

    @Test
    public void nullsAreNotBlacklisted() {
        assertFalse(HostCheckStatus.isBlacklisted(null, blacklist("ahmia.i2p")));
        assertFalse(HostCheckStatus.isBlacklisted("ahmia.i2p", null));
    }

    /** The real file has no comments or blanks, but it is hand-maintained, so tolerate them. */
    @Test
    public void blacklistToleratesCommentsBlanksAndCase() {
        Set<String> bl = blacklist(
            "# operator blacklist",
            "",
            "   ",
            "  Ahmia.i2p  ",
            "CAKE.i2p");
        assertTrue(bl.contains("ahmia.i2p"));
        assertTrue(bl.contains("cake.i2p"));
        assertEquals(2, bl.size());
    }

    /**
     *  A line carrying more than a hostname is ignored rather than stored, so a malformed
     *  entry cannot blank a link that is not actually blacklisted.
     */
    @Test
    public void blacklistRejectsNonHostnameLines() {
        Set<String> bl = blacklist(
            "ahmia.i2p=x", "http://ahmia.i2p/", "two words.i2p", "good.i2p");
        assertEquals(1, bl.size());
        assertTrue(bl.contains("good.i2p"));
    }

    @Test
    public void emptyBlacklistBlocksNothing() {
        assertFalse(HostCheckStatus.isBlacklisted("ahmia.i2p", blacklist()));
    }
}
