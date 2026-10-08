package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Classification of the hops of a build that expired unanswered.
 *
 * <p>Build timeouts are the largest unexplained loss on this router (1165 against 560
 * rejections over 55 minutes), and the existing per-hop dump was only reachable at DEBUG,
 * which this router does not run. The classification is what makes the compact one-line
 * report answer the question the timeout count cannot: whether the loss is concentrated at
 * the gateway, at the far end, or spread across hops.
 *
 * @since 0.9.71+
 */
public class ExpiredHopClassificationTest {

    // ---- classifyExpiredHop -------------------------------------------------

    @Test
    public void unassignedHop() {
        assertEquals(BuildExecutor.HOP_NEVER_ASSIGNED,
            BuildExecutor.classifyExpiredHop(false, false, false, false, false, false));
    }

    @Test
    public void selfAtGatewayIsExpected() {
        assertEquals(BuildExecutor.HOP_SELF_EXPECTED,
            BuildExecutor.classifyExpiredHop(true, true, true, true, true, true));
    }

    /** Our own identity at a non-gateway index is a selection fault, not a peer failure. */
    @Test
    public void selfAwayFromGatewayIsUnexpected() {
        assertEquals(BuildExecutor.HOP_SELF_UNEXPECTED,
            BuildExecutor.classifyExpiredHop(true, true, false, false, false, false));
    }

    @Test
    public void unreachablePeer() {
        assertEquals(BuildExecutor.HOP_UNREACHABLE,
            BuildExecutor.classifyExpiredHop(true, false, false, true, false, false));
    }

    /** The signature that matters: connection is up, build request ignored. */
    @Test
    public void establishedPeerIsSilent() {
        assertEquals(BuildExecutor.HOP_ESTABLISHED_SILENT,
            BuildExecutor.classifyExpiredHop(true, false, false, false, true, false));
    }

    @Test
    public void unfinishedHandshake() {
        assertEquals(BuildExecutor.HOP_HANDSHAKE_UNFINISHED,
            BuildExecutor.classifyExpiredHop(true, false, false, false, false, true));
    }

    @Test
    public void healthyPeer() {
        assertEquals(BuildExecutor.HOP_REACHABLE,
            BuildExecutor.classifyExpiredHop(true, false, false, false, false, false));
    }

    /**
     * Unreachable outranks the connection flags. If we had already written the peer off,
     * the useful fact is that selection let it in - reporting "silent" instead would
     * misdirect the fix towards the peer rather than towards the filter.
     */
    @Test
    public void unreachableOutranksConnectionFlags() {
        assertEquals(BuildExecutor.HOP_UNREACHABLE,
            BuildExecutor.classifyExpiredHop(true, false, false, true, true, true));
    }

    /** An unassigned hop has no connection state, so it must not be read as any of them. */
    @Test
    public void unassignedOutranksEverything() {
        assertEquals(BuildExecutor.HOP_NEVER_ASSIGNED,
            BuildExecutor.classifyExpiredHop(false, false, true, true, true, true));
    }

    /** Established and connecting together should still read as established, not handshaking. */
    @Test
    public void establishedOutranksConnecting() {
        assertEquals(BuildExecutor.HOP_ESTABLISHED_SILENT,
            BuildExecutor.classifyExpiredHop(true, false, false, false, true, true));
    }

    // ---- isActionableHop ----------------------------------------------------

    @Test
    public void actionableClassifications() {
        assertTrue(BuildExecutor.isActionableHop(BuildExecutor.HOP_UNREACHABLE));
        assertTrue(BuildExecutor.isActionableHop(BuildExecutor.HOP_ESTABLISHED_SILENT));
        assertTrue(BuildExecutor.isActionableHop(BuildExecutor.HOP_HANDSHAKE_UNFINISHED));
        assertTrue(BuildExecutor.isActionableHop(BuildExecutor.HOP_NEVER_ASSIGNED));
        assertTrue(BuildExecutor.isActionableHop(BuildExecutor.HOP_SELF_UNEXPECTED));
    }

    /** A benign hop explains nothing, so it must not be counted as a finding. */
    @Test
    public void benignClassificationsAreNotActionable() {
        assertFalse(BuildExecutor.isActionableHop(BuildExecutor.HOP_REACHABLE));
        assertFalse(BuildExecutor.isActionableHop(BuildExecutor.HOP_SELF_EXPECTED));
    }

    @Test
    public void unknownClassificationIsNotActionable() {
        assertFalse(BuildExecutor.isActionableHop(null));
        assertFalse(BuildExecutor.isActionableHop(""));
        assertFalse(BuildExecutor.isActionableHop("something else"));
    }

    /**
     * Every classification must be either actionable or benign - never both, never neither.
     * A token that fell out of the first two tests' coverage would otherwise be silently
     * invisible in the report.
     */
    @Test
    public void everyClassificationIsExactlyOneKind() {
        String[] all = {
            BuildExecutor.HOP_NEVER_ASSIGNED,
            BuildExecutor.HOP_SELF_EXPECTED,
            BuildExecutor.HOP_SELF_UNEXPECTED,
            BuildExecutor.HOP_UNREACHABLE,
            BuildExecutor.HOP_ESTABLISHED_SILENT,
            BuildExecutor.HOP_HANDSHAKE_UNFINISHED,
            BuildExecutor.HOP_REACHABLE,
        };
        for (String classification : all) {
            String verdict = classification + " actionable=" + BuildExecutor.isActionableHop(classification);
            if (BuildExecutor.HOP_REACHABLE.equals(classification)
                || BuildExecutor.HOP_SELF_EXPECTED.equals(classification)) {
                assertFalse(verdict, BuildExecutor.isActionableHop(classification));
            } else {
                assertTrue(verdict, BuildExecutor.isActionableHop(classification));
            }
        }
    }

    /**
     * Across a whole tunnel, only the hops that can explain the expiry should be counted.
     * Here we sit at the gateway (Hop2, expected) but also appear at Hop1, which never
     * legitimately happens and is therefore the one actionable finding.
     */
    @Test
    public void onlyUnexpectedSelfPlacementCountsAcrossTheTunnel() {
        int gatewayHop = 2;
        boolean[] self = {false, true, true};
        int actionable = 0;
        for (int hop = 0; hop < 3; hop++) {
            String c = BuildExecutor.classifyExpiredHop(true, self[hop], hop == gatewayHop,
                false, false, false);
            if (BuildExecutor.isActionableHop(c)) { actionable++; }
        }
        assertEquals("only the unexpected self placement counts", 1, actionable);
    }

    /** The same tunnel without the anomaly has nothing actionable to report. */
    @Test
    public void aHealthyShapeHasNoActionableHops() {
        int gatewayHop = 2;
        boolean[] self = {false, false, true};
        int actionable = 0;
        for (int hop = 0; hop < 3; hop++) {
            String c = BuildExecutor.classifyExpiredHop(true, self[hop], hop == gatewayHop,
                false, false, false);
            if (BuildExecutor.isActionableHop(c)) { actionable++; }
        }
        assertEquals("nothing to explain", 0, actionable);
    }
}