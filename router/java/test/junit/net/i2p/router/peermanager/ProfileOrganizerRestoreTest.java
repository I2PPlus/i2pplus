package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

/**
 * Unit tests for the tier-restore and netDb-eviction predicates extracted
 * from ProfileOrganizer reorganize maintenance paths.
 *
 * @since 0.9.71+
 */
public class ProfileOrganizerRestoreTest {

    private static final long NOW = 2_000_000_000L;
    private static final long MINUTE = 60 * 1000L;

    private PeerProfile profile() {
        return mock(PeerProfile.class);
    }

    // ---- needsTierRestore ----

    @Test
    public void testAtExactlyHalfNoRestore() {
        assertFalse(ProfileOrganizer.needsTierRestore(50, 100));
    }

    @Test
    public void testBelowHalfRestores() {
        assertTrue(ProfileOrganizer.needsTierRestore(49, 101));
        assertTrue(ProfileOrganizer.needsTierRestore(59, 120));
        assertTrue(ProfileOrganizer.needsTierRestore(0, 200));
    }

    @Test
    public void testAtOrAboveHalfNoRestore() {
        assertFalse(ProfileOrganizer.needsTierRestore(50, 100));
        assertFalse(ProfileOrganizer.needsTierRestore(60, 100));
        assertFalse(ProfileOrganizer.needsTierRestore(100, 100));
    }

    @Test
    public void testSmallOldSizeNeverRestores() {
        assertFalse(ProfileOrganizer.needsTierRestore(0, 100));
        assertFalse(ProfileOrganizer.needsTierRestore(0, 99));
        assertFalse(ProfileOrganizer.needsTierRestore(49, 99));
    }

    // ---- needsTierRestore with the tier minimum ----

    /**
     * The measured failure of the collapse-only rule. The fast tier fell from
     * 1,104 peers to 192 over 48 hours, one reorganize at a time, so each
     * cycle's "previous size" was already short and the half-of-previous trigger
     * never fired: the router spent 47 of those hours below its 1,000-peer
     * minimum with a restore mechanism reporting nothing to do.
     */
    @Test
    public void testSlowDeclineTriggersOnTheMinimumNotTheCollapse() {
        // A day-old cycle in the measured decline: 240 -> 227, no collapse.
        assertFalse("the collapse rule alone sees nothing wrong",
                    ProfileOrganizer.needsTierRestore(227, 240));
        assertTrue("the tier minimum sees a tier at 23% of target",
                   ProfileOrganizer.needsTierRestore(227, 240, 1000));
    }

    /** A tier at or above its minimum is not restored, whatever the old size was. */
    @Test
    public void testTierAtItsMinimumDoesNotRestoreOnTheMinimumRule() {
        assertFalse(ProfileOrganizer.needsTierRestore(1000, 1004, 1000));
        assertFalse("and a tier above it", ProfileOrganizer.needsTierRestore(1200, 1200, 1000));
        assertFalse("a tier with nothing to gain", ProfileOrganizer.needsTierRestore(900, 800, 1000));
    }

    /**
     * The minimum rule needs the snapshot to hold something the rebuild dropped;
     * a tier that grew in the rebuild has nothing preserved to restore.
     */
    @Test
    public void testMinimumRuleNeedsPreservedPeers() {
        assertFalse("the rebuild kept everything it had",
                    ProfileOrganizer.needsTierRestore(227, 227, 1000));
        assertFalse("and grew", ProfileOrganizer.needsTierRestore(227, 200, 1000));
    }

    /**
     * A tier minimum of zero, i.e. no target known, selects the collapse rule
     * alone: the old behaviour, so a caller without a tier size cannot turn
     * restoration on for every cycle.
     */
    @Test
    public void testNoMinimumSelectsTheCollapseRuleAlone() {
        assertFalse(ProfileOrganizer.needsTierRestore(227, 240, 0));
        assertFalse(ProfileOrganizer.needsTierRestore(227, 240, -1));
        assertTrue("the collapse rule still fires",
                   ProfileOrganizer.needsTierRestore(49, 200, 0));
    }

    /**
     * A small tier is not exempt: a 40-peer tier with a 100-peer minimum is
     * short of its own target, which is the whole condition. The old rule's
     * "old size &gt; 100" floor was about not reacting to noise in a tier too
     * small to measure, and it left a small router's starved tier untouched.
     */
    @Test
    public void testSmallTierBelowItsOwnMinimumRestores() {
        assertTrue(ProfileOrganizer.needsTierRestore(40, 45, 100));
    }

    // ---- isStaleAbsentPeer ----

    @Test
    public void testRecentActivityNotStale() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 10 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 20 * MINUTE);
        when(p.getLastHeardAbout()).thenReturn(NOW - 30 * MINUTE);
        assertFalse(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testAllActivityStale() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 2 * 60 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 2 * 60 * MINUTE);
        when(p.getLastHeardAbout()).thenReturn(NOW - 2 * 60 * MINUTE);
        assertTrue(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testMaxActivityDetermines() {
        PeerProfile p = profile();
        when(p.getLastSendSuccessful()).thenReturn(NOW - 90 * MINUTE);
        when(p.getLastHeardFrom()).thenReturn(NOW - 10 * MINUTE);
        assertFalse(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testExactlyAtThresholdNotStale() {
        PeerProfile p = profile();
        when(p.getLastHeardFrom()).thenReturn(NOW - 60 * MINUTE);
        assertFalse(ProfileOrganizer.isStaleAbsentPeer(p, NOW, 60 * MINUTE));
    }

    @Test
    public void testNeverContactedIsStale() {
        assertTrue(ProfileOrganizer.isStaleAbsentPeer(profile(), NOW, 60 * MINUTE));
    }
}