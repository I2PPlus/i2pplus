package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

import net.i2p.data.router.RouterInfo;

/**
 * Unit tests for {@link ProbeStalePeerJob#score}, the pure probe-priority
 * scoring function.
 *
 * <p>The function was extracted (and its RouterInfo parameter made explicit) so
 * the stale-peer sweep can compute a candidate's score once instead of once per
 * sort comparison — the old comparator re-ran a validating
 * {@code netDb().lookupRouterInfoLocally()}, i.e. the full
 * {@code KademliaNetworkDatabaseFacade.validate()} ban cascade, on every one of
 * the ~n*log(n) comparisons. These tests pin the score values so the extracted
 * form cannot drift from the original arithmetic.
 *
 * @since 0.9.71+
 */
public class ProbeStalePeerJobScoreTest {

    /**
     * Build a RouterInfo carrying only the capabilities property, which is all
     * score() reads. No identity or signature is needed.
     *
     * @param caps the capabilities string, or null for none
     * @return the RouterInfo
     */
    private static RouterInfo ri(String caps) {
        RouterInfo ri = new RouterInfo();
        if (caps != null) {
            Properties opts = new Properties();
            opts.setProperty(RouterInfo.PROP_CAPABILITIES, caps);
            ri.setOptions(opts);
        }
        return ri;
    }

    @Test
    public void nullCapsScoresZero() {
        assertEquals(0, ProbeStalePeerJob.score(ri(null)));
    }

    @Test
    public void emptyCapsScoresZero() {
        assertEquals(0, ProbeStalePeerJob.score(ri("")));
    }

    @Test
    public void unreachableAloneScoresZero() {
        // 'U' is not 'R', so it contributes nothing
        assertEquals(0, ProbeStalePeerJob.score(ri("U")));
    }

    @Test
    public void reachableAddsTen() {
        assertEquals(10, ProbeStalePeerJob.score(ri("R")));
    }

    @Test
    public void floodfillAddsFive() {
        assertEquals(5, ProbeStalePeerJob.score(ri("f")));
    }

    @Test
    public void reachableAndFloodfillAddFifteen() {
        assertEquals(15, ProbeStalePeerJob.score(ri("Rf")));
    }

    @Test
    public void unreachableAndReachableTogetherScoreReachableOnly() {
        assertEquals(10, ProbeStalePeerJob.score(ri("UR")));
    }

    @Test
    public void xTierAddsEight() {
        assertEquals(8, ProbeStalePeerJob.score(ri("X")));
    }

    @Test
    public void pTierAddsSix() {
        assertEquals(6, ProbeStalePeerJob.score(ri("P")));
    }

    @Test
    public void oTierAddsFour() {
        assertEquals(4, ProbeStalePeerJob.score(ri("O")));
    }

    @Test
    public void lowerTiersAddNothing() {
        for (String tier : new String[] {"L", "M", "N", "G", "S"}) {
            assertEquals(tier, 0, ProbeStalePeerJob.score(ri(tier)));
        }
    }

    @Test
    public void onlyFirstBandwidthTierCharCounts() {
        // getBandwidthTier() stops at the first BW char, so "XO" is an X tier
        assertEquals(8, ProbeStalePeerJob.score(ri("XO")));
    }

    @Test
    public void capsAndTierAccumulate() {
        // R (10) + f (5) + X (8)
        assertEquals(23, ProbeStalePeerJob.score(ri("RfX")));
    }

    @Test
    public void lowercaseXIsNotTheUnlimitedTier() {
        // the bandwidth-tier marker is uppercase; 'x' must not be mistaken for it
        assertEquals(0, ProbeStalePeerJob.score(ri("x")));
    }

    @Test
    public void highValueScoreReachesTheTieredThreshold() {
        // the sweep shortens maxAge when score >= 10; reachable alone qualifies
        assertTrue(ProbeStalePeerJob.score(ri("R")) >= 10);
        assertTrue(ProbeStalePeerJob.score(ri("RfX")) >= 10);
    }

    @Test
    public void scoreIsIndependentOfOrderingOfCaps() {
        assertEquals(ProbeStalePeerJob.score(ri("RfX")),
                     ProbeStalePeerJob.score(ri("RfX")));
        assertEquals(ProbeStalePeerJob.score(ri("RfX")),
                     ProbeStalePeerJob.score(ri("RXf")));
    }
}
