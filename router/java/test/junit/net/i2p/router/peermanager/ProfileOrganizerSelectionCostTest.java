package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.util.MaskedIPSet;

import org.junit.Test;

/**
 *  Tests the pure decision logic extracted from the selection hot path so the
 *  CPU work removed there cannot be silently reintroduced by a behaviour change
 *  nobody notices.
 *
 *  <p>Covers, in the order the fixes were made:
 *  <ul>
 *    <li>{@link ProfileOrganizer#isModeratelyLossy} — the lossiness band that
 *        now receives hoisted thresholds instead of two config reads per candidate</li>
 *    <li>{@link ProfileOrganizer#maskedIPKey} — the subnet fingerprint, whose
 *        key-space separation (IP vs port vs family) is what the subnet gate
 *        depends on</li>
 *    <li>{@link ProfileOrganizer#isExcessiveLifetimeFailure} — the profile-taking
 *        overload that replaced a read-lock re-entry per candidate</li>
 *    <li>{@link ProfileOrganizer#isExcludedBuildTier} / {@code isHighBandwidthTierName}
 *        / {@code qualifiesHighBandwidthTier} / {@code isLowBandwidthTierName} —
 *        the tier predicates fed by unvalidated netDb lookups</li>
 *    <li>{@link ProfileOrganizer#sampleFirstHopRttLimit} — the RTT sample bound</li>
 *    <li>{@link ProfileOrganizer#selectNthFastest} and
 *        {@link ProfileOrganizer#speedThresholdCutoff} — the bounded top-K
 *        selection that replaced a full sort</li>
 *  </ul>
 *
 *  @see ProfileOrganizerCandidateSampleTest
 *  @see ProfileOrganizerRestoreTest
 *  @since 0.9.71+
 */
public class ProfileOrganizerSelectionCostTest {

    private static final long NOW = 2_000_000_000L;
    private static final float MODERATE = 0.10f;
    private static final float DEMOTE = 0.25f;

    // ---- isModeratelyLossy ----

    /** A clean peer (no loss ever reported) is never in the band. */
    @Test
    public void zeroScoreIsNotModeratelyLossy() {
        assertFalse(ProfileOrganizer.isModeratelyLossy(0.0f, MODERATE, DEMOTE));
    }

    /** A negative score is not evidence of anything and must not trip the band. */
    @Test
    public void negativeScoreIsNotModeratelyLossy() {
        assertFalse(ProfileOrganizer.isModeratelyLossy(-0.5f, MODERATE, DEMOTE));
    }

    /** The band is closed at the bottom: exactly the moderate threshold qualifies. */
    @Test
    public void moderateThresholdIsInclusive() {
        assertTrue(ProfileOrganizer.isModeratelyLossy(MODERATE, MODERATE, DEMOTE));
    }

    /** Just below the moderate threshold is not penalised. */
    @Test
    public void justBelowModerateIsNotPenalised() {
        assertFalse(ProfileOrganizer.isModeratelyLossy(0.09f, MODERATE, DEMOTE));
    }

    /** Just below the demotion bar is the top of the band. */
    @Test
    public void justBelowDemoteIsStillPenalised() {
        assertTrue(ProfileOrganizer.isModeratelyLossy(0.24f, MODERATE, DEMOTE));
    }

    /**
     *  The band is open at the top: at the demotion threshold the peer is
     *  handled by the demotion path, not the selection penalty, so counting it
     *  here would double-count it.
     */
    @Test
    public void demoteThresholdIsExclusive() {
        assertFalse(ProfileOrganizer.isModeratelyLossy(DEMOTE, MODERATE, DEMOTE));
        assertFalse(ProfileOrganizer.isModeratelyLossy(0.9f, MODERATE, DEMOTE));
    }

    /** An inverted pair (demote below moderate) yields an empty band, not a crash. */
    @Test
    public void invertedThresholdsProduceEmptyBand() {
        assertFalse(ProfileOrganizer.isModeratelyLossy(0.5f, 0.9f, 0.1f));
    }

    // ---- maskedIPKey ----

    /** IPv4 keys carry the '.' family delimiter and two hex nibbles per byte. */
    @Test
    public void ipv4KeyFormat() {
        assertEquals(".0:14", ProfileOrganizer.maskedIPKey(new byte[] {10, 20}, 2));
        assertEquals(".<0:0", ProfileOrganizer.maskedIPKey(new byte[] {(byte) 192, (byte) 160}, 2));
        assertEquals(".0:", ProfileOrganizer.maskedIPKey(new byte[] {10}, 1));
    }

    /**
     *  Nibbles render as '0' + nibble over the "0123456789:;&lt;=&gt;?"
     *  alphabet, so 0x0f is "?" and 0x0a is ":". Pinning values outside 0-9 is
     *  what makes this a test of the alphabet rather than of hex encoding.
     */
    @Test
    public void nibblesUseTheFakeHexAlphabet() {
        assertEquals(".0?", ProfileOrganizer.maskedIPKey(new byte[] {0x0f}, 1));
        assertEquals(".0:", ProfileOrganizer.maskedIPKey(new byte[] {0x0a}, 1));
        assertEquals(".0??0", ProfileOrganizer.maskedIPKey(new byte[] {0x0f, (byte) 0xf0}, 2));
    }

    /**
     *  IPv6 doubles the matched byte count and switches the delimiter, so an
     *  IPv4 and an IPv6 address sharing a prefix can never collide.
     */
    @Test
    public void ipv6KeyDoublesMaskAndChangesDelimiter() {
        byte[] v6 = new byte[16];
        v6[0] = 10;
        v6[1] = 20;
        String key = ProfileOrganizer.maskedIPKey(v6, 2);
        assertEquals(":0:140000", key);
        assertFalse(key.equals(ProfileOrganizer.maskedIPKey(new byte[] {10, 20}, 2)));
    }

    /** Equal prefixes produce equal keys; differing prefixes do not. */
    @Test
    public void keyEqualityTracksTheMaskedPrefixOnly() {
        byte[] a = new byte[] {(byte) 203, 0, 113, 5};
        byte[] b = new byte[] {(byte) 203, 0, 113, (byte) 200};
        byte[] c = new byte[] {(byte) 198, 51, 100, 5};
        assertEquals(ProfileOrganizer.maskedIPKey(a, 3), ProfileOrganizer.maskedIPKey(b, 3));
        assertFalse(ProfileOrganizer.maskedIPKey(a, 3).equals(ProfileOrganizer.maskedIPKey(c, 3)));
    }

    /**
     *  A wider mask must yield a strictly longer key, which is what makes
     *  mask 4 reject a peer that mask 2 accepted (and not the reverse).
     */
    @Test
    public void widerMaskYieldsLongerKey() {
        byte[] ip = new byte[] {1, 2, 3, 4};
        assertEquals(ProfileOrganizer.maskedIPKey(ip, 2).length() + 4,
                     ProfileOrganizer.maskedIPKey(ip, 4).length());
    }

    /** A zero mask is the degenerate prefix and is allowed. */
    @Test
    public void zeroMaskIsTheDegeneratePrefix() {
        assertEquals(".", ProfileOrganizer.maskedIPKey(new byte[] {9, 9, 9, 9}, 0));
    }

    /** No IP key can collide with a port or family key, which use 'p' / 'x'. */
    @Test
    public void keySpacesCannotCollide() {
        for (int mask = 1; mask <= 4; mask++) {
            String ipKey = ProfileOrganizer.maskedIPKey(
                new byte[] {(byte) 0xab, (byte) 0xcd, (byte) 0xef, 1}, mask);
            assertFalse("IP key must not look like a port key",
                        ipKey.equals("p" + 1234));
            assertFalse("IP key must not look like a family key",
                        ipKey.equals("x" + "family"));
        }
    }

    // ---- isExcessiveLifetimeFailure ----

    private PeerProfile profileWithHistory(long failed, long agreed) {
        PeerProfile p = mock(PeerProfile.class);
        TunnelHistory th = mock(TunnelHistory.class);
        when(th.getLifetimeFailed()).thenReturn(failed);
        when(th.getLifetimeAgreedTo()).thenReturn(agreed);
        when(p.getTunnelHistory()).thenReturn(th);
        return p;
    }

    /** A null profile is not a failure — there is no evidence against the peer. */
    @Test
    public void nullProfileIsNotExcessive() {
        assertFalse(ProfileOrganizer.isExcessiveLifetimeFailure(null));
    }

    /** A profile with no tunnel history at all cannot be excluded. */
    @Test
    public void profileWithoutHistoryIsNotExcessive() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getTunnelHistory()).thenReturn(null);
        assertFalse(ProfileOrganizer.isExcessiveLifetimeFailure(p));
    }

    /** Below the hard cap with a healthy ratio, the peer stays eligible. */
    @Test
    public void healthyPeerIsNotExcessive() {
        assertFalse(ProfileOrganizer.isExcessiveLifetimeFailure(profileWithHistory(1, 100)));
    }

    /** The hard cap alone excludes, regardless of ratio. */
    @Test
    public void hardCapExcludes() {
        PeerProfile p = profileWithHistory(200, 100000);
        assertTrue(ProfileOrganizer.isExcessiveLifetimeFailure(p));
    }

    /** The ratio gate excludes well before the absolute cap. */
    @Test
    public void failureRatioExcludes() {
        // 60% failures, above MAX_LIFETIME_FAILURE_RATIO, and well under the
        // absolute cap, so only the ratio gate can be responsible
        PeerProfile p = profileWithHistory(30, 20);
        assertTrue(ProfileOrganizer.isExcessiveLifetimeFailure(p));
    }

    // ---- tier predicates ----

    /** L/M/N cannot host tunnels; everything else can. */
    @Test
    public void excludedBuildTiers() {
        assertTrue(ProfileOrganizer.isExcludedBuildTier("L"));
        assertTrue(ProfileOrganizer.isExcludedBuildTier("M"));
        assertTrue(ProfileOrganizer.isExcludedBuildTier("N"));
        assertFalse(ProfileOrganizer.isExcludedBuildTier("X"));
        assertFalse(ProfileOrganizer.isExcludedBuildTier("O"));
        assertFalse(ProfileOrganizer.isExcludedBuildTier("Unknown"));
    }

    /**
     *  A tier with anything appended is not the tier letter, so it is not
     *  excluded. This pins the direct-comparison behaviour that replaced the
     *  per-candidate stripHTML: stripHTML substitutes spaces for
     *  {@code < > " '} and so could never have produced "L" from anything but
     *  the one-character string "L" either.
     */
    @Test
    public void excludedBuildTierIgnoresNonExactStrings() {
        assertFalse(ProfileOrganizer.isExcludedBuildTier("<b>L"));
        assertFalse(ProfileOrganizer.isExcludedBuildTier("L<"));
        assertFalse(ProfileOrganizer.isExcludedBuildTier(" L"));
        assertFalse(ProfileOrganizer.isExcludedBuildTier(""));
    }

    /** A null tier is not evidence of anything. */
    @Test
    public void excludedBuildTierHandlesNull() {
        assertFalse(ProfileOrganizer.isExcludedBuildTier(null));
    }

    /** O/P/X are the high-bandwidth tiers. */
    @Test
    public void highBandwidthTierNames() {
        assertTrue(ProfileOrganizer.isHighBandwidthTierName("O"));
        assertTrue(ProfileOrganizer.isHighBandwidthTierName("P"));
        assertTrue(ProfileOrganizer.isHighBandwidthTierName("X"));
        assertFalse(ProfileOrganizer.isHighBandwidthTierName("L"));
        assertFalse(ProfileOrganizer.isHighBandwidthTierName("K"));
        assertFalse(ProfileOrganizer.isHighBandwidthTierName("Unknown"));
    }

    /** K/L/M/Unknown are the tiers excluded from profiling. */
    @Test
    public void lowBandwidthTierNames() {
        assertTrue(ProfileOrganizer.isLowBandwidthTierName("K"));
        assertTrue(ProfileOrganizer.isLowBandwidthTierName("L"));
        assertTrue(ProfileOrganizer.isLowBandwidthTierName("M"));
        assertTrue(ProfileOrganizer.isLowBandwidthTierName("Unknown"));
        assertFalse(ProfileOrganizer.isLowBandwidthTierName("X"));
        assertFalse(ProfileOrganizer.isLowBandwidthTierName("O"));
    }

    /** X/P/O with no blocking capability qualifies. */
    @Test
    public void qualifiesHighBandwidthWithCleanCaps() {
        assertTrue(ProfileOrganizer.qualifiesHighBandwidthTier("X", "LfR"));
        assertTrue(ProfileOrganizer.qualifiesHighBandwidthTier("P", ""));
        assertTrue(ProfileOrganizer.qualifiesHighBandwidthTier("O", "LR"));
    }

    /** Each blocking capability is disqualifying on its own. */
    @Test
    public void qualifiesHighBandwidthRejectsBlockingCaps() {
        assertFalse(ProfileOrganizer.qualifiesHighBandwidthTier("X", "LfRD"));
        assertFalse(ProfileOrganizer.qualifiesHighBandwidthTier("X", "LfRE"));
        assertFalse(ProfileOrganizer.qualifiesHighBandwidthTier("X", "LfRG"));
        assertFalse(ProfileOrganizer.qualifiesHighBandwidthTier("X", "LfRU"));
    }

    /** A non-X/P/O tier never qualifies, whatever the capabilities say. */
    @Test
    public void qualifiesHighBandwidthRejectsLowTiers() {
        assertFalse(ProfileOrganizer.qualifiesHighBandwidthTier("N", "LfR"));
        assertFalse(ProfileOrganizer.qualifiesHighBandwidthTier("Unknown", "LfR"));
    }

    // ---- sampleFirstHopRttLimit ----

    /** An empty tier samples nothing. */
    @Test
    public void rttSampleLimitOnEmptyTier() {
        assertEquals(0, ProfileOrganizer.sampleFirstHopRttLimit(0));
        assertEquals(0, ProfileOrganizer.sampleFirstHopRttLimit(-5));
    }

    /** A small tier is sampled in full — the bound must not blind the sampler. */
    @Test
    public void rttSampleLimitCoversSmallTiers() {
        assertEquals(10, ProfileOrganizer.sampleFirstHopRttLimit(10));
    }

    /** A large tier is capped at the candidate-sample floor. */
    @Test
    public void rttSampleLimitCapsLargeTiers() {
        int floor = ProfileOrganizer.DEFAULT_MIN_CANDIDATE_SAMPLE;
        assertEquals(floor, ProfileOrganizer.sampleFirstHopRttLimit(4500));
        assertEquals(floor, ProfileOrganizer.sampleFirstHopRttLimit(100000));
    }

    /** The limit never exceeds the tier, so no caller can ask for more than exists. */
    @Test
    public void rttSampleLimitNeverExceedsTier() {
        for (int n : new int[] {1, 2, 255, 256, 257, 1024}) {
            assertTrue(ProfileOrganizer.sampleFirstHopRttLimit(n) <= n);
        }
    }

    // ---- speedThresholdCutoff ----

    /** The cutoff is the top 30%, capped at 50. */
    @Test
    public void speedCutoffIsThirtyPercentCappedAtFifty() {
        assertEquals(30, ProfileOrganizer.speedThresholdCutoff(100));
        assertEquals(50, ProfileOrganizer.speedThresholdCutoff(1000));
        assertEquals(50, ProfileOrganizer.speedThresholdCutoff(100000));
    }

    /** Degenerate sizes do not produce a negative rank. */
    @Test
    public void speedCutoffIsNeverNegative() {
        assertEquals(0, ProfileOrganizer.speedThresholdCutoff(0));
        assertEquals(0, ProfileOrganizer.speedThresholdCutoff(-3));
        assertEquals(0, ProfileOrganizer.speedThresholdCutoff(1));
    }

    // ---- selectNthFastest ----

    /** A profile that qualifies and is the only one is returned for k=1. */
    @Test
    public void selectsTheOnlyQualifyingProfile() {
        PeerProfile only = mock(PeerProfile.class);
        when(only.getCapacityValue()).thenReturn(10.0f);
        when(only.getIsActive(NOW)).thenReturn(true);
        when(only.getSpeedValue()).thenReturn(5.0f);
        List<PeerProfile> all = new ArrayList<>();
        all.add(only);
        assertEquals(only, ProfileOrganizer.selectNthFastest(all, NOW, 1.0, 1));
    }

    /** Fewer than k qualifiers yields null rather than a wrong answer. */
    @Test
    public void returnsNullWhenFewerThanKQualify() {
        PeerProfile only = mock(PeerProfile.class);
        when(only.getCapacityValue()).thenReturn(10.0f);
        when(only.getIsActive(NOW)).thenReturn(true);
        when(only.getSpeedValue()).thenReturn(5.0f);
        List<PeerProfile> all = new ArrayList<>();
        all.add(only);
        assertNull(ProfileOrganizer.selectNthFastest(all, NOW, 1.0, 2));
    }

    /** k<=0 has no meaningful rank and must not throw. */
    @Test
    public void returnsNullForNonPositiveK() {
        assertNull(ProfileOrganizer.selectNthFastest(new ArrayList<PeerProfile>(), NOW, 0.0, 0));
        assertNull(ProfileOrganizer.selectNthFastest(new ArrayList<PeerProfile>(), NOW, 0.0, -1));
    }

    /**
     *  The fastest profile is returned for k=1 out of many. The fastest is
     *  deliberately the <em>last</em> element added, so an implementation that
     *  only ever looked at a prefix would fail.
     */
    @Test
    public void selectsFastestForKOne() {
        List<PeerProfile> all = new ArrayList<>();
        PeerProfile best = null;
        for (int i = 0; i < 200; i++) {
            PeerProfile p = mock(PeerProfile.class);
            when(p.getCapacityValue()).thenReturn(10.0f);
            when(p.getIsActive(NOW)).thenReturn(true);
            when(p.getSpeedValue()).thenReturn((float) i);
            if (i == 199) best = p;
            all.add(p);
        }
        assertEquals(best, ProfileOrganizer.selectNthFastest(all, NOW, 1.0, 1));
    }

    /**
     *  The bounded selection must agree with a full sort at the rank it is asked
     *  for — this is the property the removed O(n log n) sort guaranteed, and the
     *  only thing that matters about which of several equal-speed peers it picks
     *  is that the speed value matches.
     */
    @Test
    public void boundedSelectionAgreesWithFullSort() {
        List<PeerProfile> all = new ArrayList<>();
        List<Float> speeds = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 500; i++) {
            PeerProfile p = mock(PeerProfile.class);
            float speed = rnd.nextInt(1000);
            when(p.getCapacityValue()).thenReturn(10.0f);
            when(p.getIsActive(NOW)).thenReturn(true);
            when(p.getSpeedValue()).thenReturn(speed);
            all.add(p);
            speeds.add(speed);
        }
        speeds.sort((a, b) -> Float.compare(b, a));
        for (int k : new int[] {1, 2, 17, 51}) {
            PeerProfile got = ProfileOrganizer.selectNthFastest(all, NOW, 1.0, k);
            assertNotNull("k=" + k, got);
            assertEquals("k=" + k + " speed", speeds.get(k - 1), got.getSpeedValue(), 0.0f);
        }
    }

    /** Peers below the capacity threshold are filtered out before ranking. */
    @Test
    public void boundedSelectionFiltersByCapacity() {
        PeerProfile weak = mock(PeerProfile.class);
        when(weak.getCapacityValue()).thenReturn(1.0f);
        when(weak.getIsActive(NOW)).thenReturn(true);
        when(weak.getSpeedValue()).thenReturn(999.0f);
        List<PeerProfile> all = new ArrayList<>();
        all.add(weak);
        assertNull(ProfileOrganizer.selectNthFastest(all, NOW, 10.0, 1));
    }

    /** Inactive peers are filtered out before ranking. */
    @Test
    public void boundedSelectionFiltersInactive() {
        PeerProfile stale = mock(PeerProfile.class);
        when(stale.getCapacityValue()).thenReturn(100.0f);
        when(stale.getIsActive(NOW)).thenReturn(false);
        when(stale.getSpeedValue()).thenReturn(999.0f);
        List<PeerProfile> all = new ArrayList<>();
        all.add(stale);
        assertNull(ProfileOrganizer.selectNthFastest(all, NOW, 10.0, 1));
    }

    /** Exactly the capacity threshold qualifies — the gate is inclusive. */
    @Test
    public void boundedSelectionCapacityGateIsInclusive() {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getCapacityValue()).thenReturn(10.0f);
        when(p.getIsActive(NOW)).thenReturn(true);
        when(p.getSpeedValue()).thenReturn(1.0f);
        List<PeerProfile> all = new ArrayList<>();
        all.add(p);
        assertEquals(p, ProfileOrganizer.selectNthFastest(all, NOW, 10.0, 1));
    }

    /**
     *  Key equality is the whole contract of the subnet gate: two peers share a
     *  key exactly when they share a masked prefix. This exercises that through
     *  a set — the accumulator type the selection API actually receives — and
     *  so also pins that the key is usable as a {@code Set<String>} element.
     */
    @Test
    public void maskedIpKeyRoundTripThroughASet() {
        Set<String> claimed = new HashSet<>();
        String key = ProfileOrganizer.maskedIPKey(new byte[] {(byte) 203, 0, 113, 5}, 2);
        assertTrue(claimed.add(key));
        assertFalse("second add of the same key must report a collision",
                    claimed.add(key));
        assertTrue(claimed.contains(key));
    }

    /** The accumulator is only ever asked the Set operations, and it is a Set. */
    @Test
    public void maskedIPSetIsUsableAsTheAccumulator() {
        MaskedIPSet set = new MaskedIPSet(4);
        assertTrue(set.isEmpty());
        assertTrue(set.add(ProfileOrganizer.maskedIPKey(new byte[] {1, 2, 3, 4}, 2)));
        assertFalse(set.isEmpty());
        assertEquals(1, set.size());
        assertTrue(set.contains(ProfileOrganizer.maskedIPKey(new byte[] {1, 2, 9, 9}, 2)));
        Collection<String> asCollection = set;
        assertEquals(1, asCollection.size());
    }
}
