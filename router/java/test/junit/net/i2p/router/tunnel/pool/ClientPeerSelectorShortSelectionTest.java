package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Decision tests for {@link ClientPeerSelector#shouldDiscardShortSelection}, the
 * guard that decides whether a post-{@code finalizeSelection} selection is
 * returned as a short tunnel or thrown away for a redraw.
 *
 * <p>The regression: a selection the shortfall ladder had already approved at
 * full length was discarded whenever {@code finalizeSelection}'s safety net
 * dropped even one peer. A starved pool requests <em>minimum</em> length, which
 * leaves zero slack, so a single peer turning unreliable mid-selection discarded
 * the endpoint and every still-valid hop and forced a full redraw — repeated on
 * every attempt for as long as churn kept peers tripping the reliability gate.
 * That is what held {@code skank.i2p} at 2-3 of 8 tunnels.
 *
 * <p>The surviving peers are still filtered: {@code finalizeSelection} removes
 * ghosts, banlisted peers and unreliable peers before this runs, so what is
 * returned here is clean. Only a structurally unbuildable selection is dropped.
 *
 * @since 0.9.71+
 */
public class ClientPeerSelectorShortSelectionTest {

    private static final int MIN_STRUCTURAL =
        ClientPeerSelector.MIN_STRUCTURAL_NON_SELF_HOPS;

    /** {@code rvSize} includes self, so non-self hops = rvSize - 1. */
    private static int rvSizeFor(int nonSelfHops) { return nonSelfHops + 1; }

    // ---- the regression: a minor shortfall is no longer discarded ----

    /**
     * The exact production case: a 3-hop pool at minimum length where finalize
     * dropped one hop, leaving 2 of 3. The old guard discarded this; two
     * non-self hops is a buildable tunnel.
     */
    @Test
    public void testTwoOfThreeIsKept() {
        assertFalse("2/3 must be kept, it is structurally sound",
                    ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(2), 3));
    }

    @Test
    public void testOneHopShortfallAtHigherMinimumIsKept() {
        // 4-hop pool, three survive: still well above the structural floor
        assertFalse(ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(3), 4));
        assertFalse(ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(2), 4));
    }

    /** A full-length selection is never discarded. */
    @Test
    public void testFullLengthKept() {
        for (int min = 1; min <= 6; min++) {
            assertFalse("full-length min=" + min + " must be kept",
                        ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(min), min));
        }
    }

    /** A selection longer than requested is fine. */
    @Test
    public void testLongerThanRequestedKept() {
        assertFalse(ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(6), 3));
    }

    // ---- structurally unbuildable selections are still discarded ----

    /**
     * Fewer than two non-self hops cannot form a tunnel at all, so the redraw is
     * still correct and must be preserved.
     */
    @Test
    public void testStructurallyUnusableIsDiscarded() {
        assertTrue(ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(1), 3));
        assertTrue(ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(0), 3));
    }

    /** Self only (rvSize 1 => 0 non-self hops) is unbuildable. */
    @Test
    public void testSelfOnlyIsDiscarded() {
        assertTrue(ClientPeerSelector.shouldDiscardShortSelection(1, 3));
        assertTrue(ClientPeerSelector.shouldDiscardShortSelection(0, 3));
    }

    /**
     * A ladder that legitimately shortened under stress keeps its result, so a
     * structurally sound selection is kept even when {@code min} is 0 — the
     * guard must not become a new source of discards.
     */
    @Test
    public void testZeroMinKeepsStructuralSelection() {
        assertFalse(ClientPeerSelector.shouldDiscardShortSelection(rvSizeFor(MIN_STRUCTURAL), 0));
    }

    // ---- invariants across the whole input space ----

    /**
     * Never discards anything with at least the structural floor, and never keeps
     * anything below it. This is the whole contract in one sweep.
     */
    @Test
    public void testContractAcrossRange() {
        for (int rvSize = 0; rvSize <= 12; rvSize++) {
            for (int min = 0; min <= 6; min++) {
                boolean discard = ClientPeerSelector.shouldDiscardShortSelection(rvSize, min);
                int nonSelf = rvSize - 1;
                if (nonSelf >= MIN_STRUCTURAL) {
                    assertFalse("structural selection discarded: rvSize=" + rvSize +
                                " min=" + min, discard);
                } else if (nonSelf < min) {
                    assertTrue("unusable selection kept: rvSize=" + rvSize +
                               " min=" + min, discard);
                }
            }
        }
    }

    /**
     * The decision depends only on the structural floor, never on how far below
     * {@code min} the selection happens to be — otherwise a deeper shortfall
     * would still be discarded and the starvation would persist for pools that
     * are worse off than 3/8.
     */
    @Test
    public void testDecisionIndependentOfShortfallDepth() {
        for (int min = 1; min <= 8; min++) {
            boolean atFloor = ClientPeerSelector.shouldDiscardShortSelection(
                rvSizeFor(MIN_STRUCTURAL), min);
            for (int deeper = min + 1; deeper <= 12; deeper++) {
                assertEquals("decision must not change with min=" + deeper,
                             atFloor,
                             ClientPeerSelector.shouldDiscardShortSelection(
                                 rvSizeFor(MIN_STRUCTURAL), deeper));
            }
        }
    }

    /**
     * The structural floor must agree with the floor {@code dropUnreliable}
     * already uses, or a selection could be trimmed to one hop by the quality
     * pre-filter and then called buildable here.
     */
    @Test
    public void testStructuralFloorIsTwo() {
        assertEquals(2, MIN_STRUCTURAL);
    }
}
