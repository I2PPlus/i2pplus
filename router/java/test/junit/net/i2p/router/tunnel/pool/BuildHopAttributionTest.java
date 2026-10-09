package net.i2p.router.tunnel.pool;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for per-hop expiry attribution.
 *
 * <p>The failure these guard against is silent misattribution. Hop0 is this
 * router, and two classifications are expected states rather than failures. If
 * either is counted, the stat distribution says "hop 0 fails most", which is
 * meaningless and would point tuning at the wrong end of the build path.
 *
 * @since 0.9.71+
 */
public class BuildHopAttributionTest {

    @Test
    public void selfIsNeverAPeerFailure() {
        assertFalse("Hop0 is us, not a failing peer",
                    BuildHopAttribution.isPeerFailure(0, BuildExecutor.HOP_ESTABLISHED_SILENT));
    }

    @Test
    public void selfExpectedIsNotAFailure() {
        // Reached when the gateway is us (length-1 builds); expected, not a fault.
        assertFalse(BuildHopAttribution.isPeerFailure(1, BuildExecutor.HOP_SELF_EXPECTED));
    }

    @Test
    public void neverAssignedIsNotAFailure() {
        assertFalse(BuildHopAttribution.isPeerFailure(2, BuildExecutor.HOP_NEVER_ASSIGNED));
    }

    @Test
    public void establishedSilentIsAPeerFailure() {
        assertTrue(BuildHopAttribution.isPeerFailure(1, BuildExecutor.HOP_ESTABLISHED_SILENT));
    }

    @Test
    public void unreachableIsAPeerFailure() {
        assertTrue(BuildHopAttribution.isPeerFailure(1, BuildExecutor.HOP_UNREACHABLE));
        assertTrue(BuildHopAttribution.isPeerFailure(3, BuildExecutor.HOP_REACHABLE));
    }

    @Test
    public void nullClassificationIsSafe() {
        assertFalse(BuildHopAttribution.isPeerFailure(1, null));
        assertFalse(BuildHopAttribution.isPeerFailure(0, null));
        assertEquals("", BuildHopAttribution.normalise(null));
    }

    @Test
    public void statNameHasNoSpacesOrCommas() {
        // "established, silent" must still yield a registerable stat name.
        String name = BuildHopAttribution.statName(1, BuildExecutor.HOP_ESTABLISHED_SILENT);
        assertEquals("tunnel.buildExpired.Hop1.establishedsilent", name);
        assertFalse("stat names cannot contain spaces", name.contains(" "));
        assertFalse("stat names cannot contain commas", name.contains(","));
    }

    @Test
    public void statNameIsUniquePerPositionAndClassification() {
        String hop1 = BuildHopAttribution.statName(1, BuildExecutor.HOP_ESTABLISHED_SILENT);
        String hop2 = BuildHopAttribution.statName(2, BuildExecutor.HOP_ESTABLISHED_SILENT);
        String unreach = BuildHopAttribution.statName(1, BuildExecutor.HOP_UNREACHABLE);
        assertNotEquals("positions must not collide", hop1, hop2);
        assertNotEquals("classifications must not collide", hop1, unreach);
    }

    @Test
    public void establishedSilentIsDistinguishedFromUnreachable() {
        // The two warrant opposite responses; conflating them hides the distinction
        // the whole measurement exists to draw.
        assertTrue(BuildHopAttribution.isEstablishedSilent(BuildExecutor.HOP_ESTABLISHED_SILENT));
        assertFalse(BuildHopAttribution.isEstablishedSilent(BuildExecutor.HOP_UNREACHABLE));
        assertFalse(BuildHopAttribution.isEstablishedSilent(BuildExecutor.HOP_REACHABLE));
    }

    @Test
    public void normaliseLowerCases() {
        assertEquals("this router (expected)",
                     BuildHopAttribution.normalise(BuildExecutor.HOP_SELF_EXPECTED));
        assertEquals("established, silent",
                     BuildHopAttribution.normalise(BuildExecutor.HOP_ESTABLISHED_SILENT));
    }
}
