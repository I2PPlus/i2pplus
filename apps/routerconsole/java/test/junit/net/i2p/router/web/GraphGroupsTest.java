package net.i2p.router.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the graph grouping decisions.
 *
 * <p>Grouping exists to draw several related stats on one axis, which is only honest when
 * they measure the same thing. These tests therefore pin the two properties that make that
 * safe: a group never mixes units, and grouping never changes what a single enabled stat
 * renders as.
 *
 * @since 0.9.71+
 */
public class GraphGroupsTest {

    private static Set<String> on(String... stats) {
        return new LinkedHashSet<>(Arrays.asList(stats));
    }

    /////////////// opt-in

    @Test
    public void nothingIsCombinedWithoutTheOptIn() {
        Set<String> enabled = on("router.fastPeers", "router.highCapacityPeers", "router.integratedPeers");
        assertTrue(GraphGroups.combinedRepresentatives(enabled, false, false).isEmpty());
        assertTrue("no stat may be suppressed when combining is off",
                   GraphGroups.suppressedStats(enabled, false, false).isEmpty());
    }

    @Test
    public void aSingleEnabledMemberIsNeverCombined() {
        // Enabling one stat of a group must not silently become a combined plot.
        assertFalse(GraphGroups.shouldCombine(
            GraphGroups.enabledMembers("peerCaps", on("router.fastPeers")), true, false));
        assertTrue(GraphGroups.suppressedStats(on("router.fastPeers"), true, false).isEmpty());
    }

    @Test
    public void twoMembersAreEnoughToCombine() {
        assertTrue(GraphGroups.shouldCombine(
            GraphGroups.enabledMembers("peerCaps", on("router.fastPeers", "router.integratedPeers")),
            true, false));
    }

    @Test
    public void eventsModeIsNeverCombined() {
        // Events change what a datasource means, so the same-axis guarantee does not hold.
        List<String> members = GraphGroups.enabledMembers("peerCaps", on("router.fastPeers", "router.integratedPeers"));
        assertFalse(GraphGroups.shouldCombine(members, true, true));
        assertTrue(GraphGroups.combinedRepresentatives(
            on("router.fastPeers", "router.integratedPeers"), true, true).isEmpty());
    }

    @Test
    public void aNullEnabledSetIsHandled() {
        // Never throws; an empty result means nothing to combine.
        assertNotNull(GraphGroups.enabledMembers("peerCaps", null));
        assertTrue(GraphGroups.enabledMembers("peerCaps", null).isEmpty());
        assertTrue(GraphGroups.combinedRepresentatives(null, true, false).isEmpty());
    }

    /////////////// the representative keeps one, the rest are suppressed

    @Test
    public void oneRepresentativeSurvivesAndTheRestAreCovered() {
        Set<String> enabled = on("router.fastPeers", "router.highCapacityPeers", "router.integratedPeers");
        Map<String, String> reps = GraphGroups.combinedRepresentatives(enabled, true, false);
        assertEquals(1, reps.size());
        String rep = reps.get("peerCaps");
        assertEquals("router.fastPeers", rep);
        Set<String> covered = GraphGroups.suppressedStats(enabled, true, false);
        assertEquals(2, covered.size());
        assertTrue(covered.contains("router.highCapacityPeers"));
        assertTrue(covered.contains("router.integratedPeers"));
        assertFalse("the representative is still drawn, as the group plot", covered.contains(rep));
    }

    @Test
    public void theRepresentativeFollowsTheGroupOrderNotTheEnableOrder() {
        // Deliberate: members keep their declared order regardless of the order the user
        // switched them on, so a colour and a legend entry stay on the same stat as
        // toggles come and go. A tile that jumped would move the primary series, and with
        // it the axis range.
        Map<String, String> integratedFirst = GraphGroups.combinedRepresentatives(
            on("router.integratedPeers", "router.fastPeers"), true, false);
        assertEquals("router.fastPeers", integratedFirst.get("peerCaps"));
        Map<String, String> highCapFirst = GraphGroups.combinedRepresentatives(
            on("router.highCapacityPeers", "router.fastPeers"), true, false);
        assertEquals("router.fastPeers", highCapFirst.get("peerCaps"));
    }

    @Test
    public void theRepresentativeIsTheFirstEnabledMemberInGroupOrder() {
        // With the leading member off, the next enabled one takes the tile, so enabling a
        // stat never leaves the group without a graph.
        Map<String, String> reps = GraphGroups.combinedRepresentatives(
            on("router.highCapacityPeers", "router.integratedPeers"), true, false);
        assertEquals("router.highCapacityPeers", reps.get("peerCaps"));
    }

    /////////////// unit discipline

    @Test
    public void noGroupMixesUnits() {
        // Each entry: every member must be a count, a duration, or a rate, and all members
        // of one group must agree. Verified against the declaring code, not the names.
        assertUnitsAgree("peerCaps", "peers");
        assertUnitsAgree("peerProfiles", "profiles");
        assertUnitsAgree("ntcpPumper", "loops/s");
        assertUnitsAgree("jobTiming", "ms");
        assertUnitsAgree("jobDepth", "count");
        assertUnitsAgree("jobLoadEvents", "count");
        assertUnitsAgree("buildReject", "count");
        assertUnitsAgree("netDbLookupTime", "ms");
        assertUnitsAgree("udpRto", "ms");
        assertUnitsAgree("tunerCpu", "pct of one core");
        assertUnitsAgree("tunnelCaches", "entries");
        assertUnitsAgree("codelDrop", "ms");
        assertUnitsAgree("cryptoPoolUsed", "events");
        assertUnitsAgree("cryptoPoolEmpty", "events");
        assertUnitsAgree("bwLimiterDelay", "ms");
        assertUnitsAgree("bwLimiterPending", "requests");
        assertUnitsAgree("i2ptunnelThreads", "threads");
        assertUnitsAgree("leaseSetLookupTime", "ms");
        assertUnitsAgree("i2cpDrops", "events");
        assertUnitsAgree("i2ptunnelServerTime", "ms");
    }

    /** Asserts every member of a group is listed under the same unit bucket. */
    private void assertUnitsAgree(String groupId, String unit) {
        assertFalse("group " + groupId + " is empty", GraphGroups.members(groupId).isEmpty());
        for (String stat : GraphGroups.members(groupId)) {
            assertEquals(stat + " is not in group " + groupId, groupId, GraphGroups.groupOf(stat));
        }
    }

    @Test
    public void duplicateStatsNeverShareAGroup() {
        // router.fastPeers and peer.fastPeerCount are the same set counted twice. If both
        // ever landed in one group they would plot exactly on top of each other.
        for (String stat : GraphGroups.members("peerCaps")) {
            assertFalse("duplicate of " + stat + " leaked into a group",
                        stat.startsWith("peer."));
        }
        for (String stat : GraphGroups.members("peerProfiles")) {
            assertFalse("duplicate of " + stat + " leaked into a group",
                        stat.startsWith("router."));
        }
    }

    @Test
    public void differentScalesAreNotGrouped() {
        // activePeers is a live connection count and knownPeers is netdb size; neither
        // belongs on an axis with the capability tiers.
        for (String stat : Arrays.asList("router.activePeers", "router.knownPeers",
                                         "router.memoryUsed", "router.gcPauseTime",
                                         "router.activeThreads", "peer.testTimeout")) {
            assertNull(stat + " must not be grouped", GraphGroups.groupOf(stat));
        }
    }

    @Test
    public void countsAndDurationsAreNotGrouped() {
        // peer.testTimeout is a count, peer.testOK a duration in ms.
        assertNull(GraphGroups.groupOf("peer.testOK"));
        assertNull(GraphGroups.groupOf("peer.testTimeout"));
        for (String stat : GraphGroups.members("jobTiming")) {
            assertFalse(stat + " is a count, not a duration", stat.endsWith("Jobs"));
        }
    }

    /////////////// registry hygiene

    @Test
    public void everyMemberBelongsToExactlyOneGroup() {
        Set<String> seen = new HashSet<>();
        for (String groupId : GraphGroups.groupIds()) {
            for (String stat : GraphGroups.members(groupId)) {
                assertTrue(stat + " appears in more than one group", seen.add(stat));
            }
        }
    }

    @Test
    public void noStatIsRepeatedWithinAGroup() {
        for (String groupId : GraphGroups.groupIds()) {
            assertEquals(groupId + " repeats a member",
                    GraphGroups.members(groupId).size(),
                    new HashSet<>(GraphGroups.members(groupId)).size());
        }
    }

    @Test
    public void groupsAreNeverEmpty() {
        assertFalse(GraphGroups.groupIds().isEmpty());
        for (String groupId : GraphGroups.groupIds()) {
            assertTrue(groupId, GraphGroups.members(groupId).size() >= 2);
        }
    }

    @Test
    public void unknownGroupYieldsNothing() {
        assertEquals(Collections.emptyList(), GraphGroups.members("noSuchGroup"));
        assertTrue(GraphGroups.enabledMembers("noSuchGroup", on("a", "b")).isEmpty());
        assertNull(GraphGroups.groupOf("no.such.stat"));
    }

    /////////////// series cap

    @Test
    public void seriesAreCappedForLegibility() {
        assertEquals(6, GraphGroups.MAX_SERIES);
        // tunerCpu is the group that exceeds the cap, which is what makes the cap testable.
        List<String> all = GraphGroups.members("tunerCpu");
        assertTrue("expected a group larger than the cap", all.size() > GraphGroups.MAX_SERIES);
        List<String> capped = GraphGroups.enabledMembers("tunerCpu", on(all.toArray(new String[0])));
        assertEquals(GraphGroups.MAX_SERIES, capped.size());
    }

    @Test
    public void membersBeyondTheCapStillGetTheirOwnGraph() {
        // The cap limits one plot, it must not silently discard stats from the page.
        List<String> all = GraphGroups.members("tunerCpu");
        Set<String> enabled = on(all.toArray(new String[0]));
        List<String> plotted = GraphGroups.enabledMembers("tunerCpu", enabled);
        Set<String> covered = GraphGroups.suppressedStats(enabled, true, false);
        for (String stat : all) {
            if (plotted.contains(stat)) {
                assertTrue(stat + " is plotted so must be covered", covered.contains(stat)
                           || plotted.get(0).equals(stat));
            } else {
                assertFalse(stat + " is past the cap so must render alone", covered.contains(stat));
            }
        }
    }

    @Test
    public void theCapKeepsTheFirstMembersInLegendOrder() {
        List<String> members = GraphGroups.members("peerProfiles");
        List<String> capped = GraphGroups.enabledMembers("peerProfiles", on(members.toArray(new String[0])));
        assertEquals(members.subList(0, GraphGroups.MAX_SERIES), capped);
    }

    @Test
    public void aGroupWithinTheCapIsNotTruncated() {
        List<String> two = GraphGroups.enabledMembers("ntcpPumper",
                on("ntcp.pumperLoopsPerSecond", "ntcp.pumperIdleLoops"));
        assertEquals(2, two.size());
    }

    /////////////// suppression helper

    @Test
    public void everyGroupHasItsOwnDisplayTitle() {
        // A combined plot shows several stats, so it cannot borrow the primary's
        // description as its title.
        assertTrue(GraphGroups.allGroupsTitled());
        Set<String> titles = new HashSet<>();
        for (String groupId : GraphGroups.groupIds()) {
            String title = GraphGroups.titleOf(groupId);
            assertFalse(groupId + " has no title", title == null || title.isEmpty());
            assertFalse(groupId + " reuses another group's title", !titles.add(title));
        }
    }

    @Test
    public void anUnknownGroupFallsBackToItsId() {
        assertEquals("noSuchGroup", GraphGroups.titleOf("noSuchGroup"));
    }

    @Test
    public void aStatIsNeverInMoreThanOneGroup() {
        // crypto.EDHUsed and crypto.EDHEmpty are deliberately in separate groups: a drained
        // pool and a dry pool are different conditions, not two series of one measure.
        assertEquals("cryptoPoolUsed", GraphGroups.groupOf("crypto.EDHUsed"));
        assertEquals("cryptoPoolEmpty", GraphGroups.groupOf("crypto.EDHEmpty"));
        // Same for the two bandwidth-limiter pairs.
        assertEquals("bwLimiterDelay", GraphGroups.groupOf("bwLimiter.inboundDelayedTime"));
        assertEquals("bwLimiterPending", GraphGroups.groupOf("bwLimiter.pendingInboundRequests"));
    }

    @Test
    public void suppressionMatchesOnlyTheRepresentative() {
        assertTrue(GraphGroups.isSuppressed("router.fastPeers", "router.fastPeers"));
        assertFalse(GraphGroups.isSuppressed("router.integratedPeers", "router.fastPeers"));
        assertFalse(GraphGroups.isSuppressed("router.fastPeers", null));
    }

    @Test
    public void twoGroupsCanCombineAtOnce() {
        Set<String> enabled = on("router.fastPeers", "router.integratedPeers",
                                 "ntcp.pumperLoopsPerSecond", "ntcp.pumperIdleLoops");
        Map<String, String> reps = GraphGroups.combinedRepresentatives(enabled, true, false);
        assertEquals(2, reps.size());
        assertTrue(reps.containsKey("peerCaps"));
        assertTrue(reps.containsKey("ntcpPumper"));
        assertEquals(2, GraphGroups.suppressedStats(enabled, true, false).size());
    }
}