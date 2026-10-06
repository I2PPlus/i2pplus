package net.i2p.router.peermanager;


import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the recently-active profile predicate behind {@code peer.activeProfileCount}.
 *
 * <p>The stat was fed the total in-RAM profile count - the same value as
 * {@code peer.profileCount} - so the Peers page's Active ring, which divides one by the other,
 * rendered 100% unconditionally while claiming a 24-hour activity window. It now counts profiles
 * with recent activity over a one-hour window, matching the sidebar's Peers row.
 *
 * @since 0.9.72
 */
public class ProfileOrganizerActiveWindowTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HIDE_BEFORE = NOW - 60 * 60 * 1000L;

    /**
     * A profile with the four signals the window considers. {@code isActive} is separate from the
     * three timestamps because it is a peer-test-derived flag rather than a contact time.
     */
    private static PeerProfile profile(
            boolean isActive, long sendOk, long sendFailed, long heard) {
        PeerProfile p = mock(PeerProfile.class);
        when(p.getIsActive(60 * 60 * 1000L)).thenReturn(isActive);
        when(p.getLastSendSuccessful()).thenReturn(sendOk);
        when(p.getLastSendFailed()).thenReturn(sendFailed);
        when(p.getLastHeardFrom()).thenReturn(heard);
        return p;
    }

    /** Nothing set at all: an untouched profile is not active. */
    @Test
    public void untouchedProfileIsNotActive() {
        assertFalse(ProfileOrganizer.isActiveInWindow(profile(false, 0, 0, 0), HIDE_BEFORE));
    }

    /** The regression's subject: a fresh profile with no contacts must not read as active. */
    @Test
    public void profileOutsideTheWindowIsNotActive() {
        long old = NOW - 2 * 60 * 60 * 1000L; // two hours ago
        assertFalse(ProfileOrganizer.isActiveInWindow(profile(false, old, old, old), HIDE_BEFORE));
    }

    /** Any one signal inside the window is enough. */
    @Test
    public void recentSuccessfulSendCounts() {
        assertTrue(ProfileOrganizer.isActiveInWindow(profile(false, NOW - 1000, 0, 0), HIDE_BEFORE));
    }

    @Test
    public void recentFailedSendCounts() {
        // A failed send is still contact: the peer answered at the transport.
        assertTrue(ProfileOrganizer.isActiveInWindow(profile(false, 0, NOW - 1000, 0), HIDE_BEFORE));
    }

    @Test
    public void recentHearCounts() {
        assertTrue(ProfileOrganizer.isActiveInWindow(profile(false, 0, 0, NOW - 1000), HIDE_BEFORE));
    }

    /** The peer-test flag counts on its own, with no timestamps set. */
    @Test
    public void isActiveFlagAloneCounts() {
        assertTrue(ProfileOrganizer.isActiveInWindow(profile(true, 0, 0, 0), HIDE_BEFORE));
    }

    /** Exactly on the boundary is inside the window: the comparison is >=. */
    @Test
    public void boundaryInstantCounts() {
        assertTrue(ProfileOrganizer.isActiveInWindow(profile(false, HIDE_BEFORE, 0, 0), HIDE_BEFORE));
    }

    /** One millisecond older is outside. */
    @Test
    public void justBeforeTheBoundaryDoesNotCount() {
        assertFalse(
                ProfileOrganizer.isActiveInWindow(profile(false, HIDE_BEFORE - 1, 0, 0), HIDE_BEFORE));
    }

    /**
     * The window is an hour, so a peer heard four hours ago is active under the old
     * countActivePeers() (4h) but not here. Pins the window rather than just "recent".
     */
    @Test
    public void windowIsOneHourNotFour() {
        long fourHoursAgo = NOW - 4 * 60 * 60 * 1000L;
        assertFalse(
                ProfileOrganizer.isActiveInWindow(profile(false, fourHoursAgo, 0, fourHoursAgo), HIDE_BEFORE));
    }

    /** A peer currently active stays active however old its contact timestamps are. */
    @Test
    public void activeFlagSurvivesStaleTimestamps() {
        long old = NOW - 5 * 60 * 60 * 1000L;
        assertTrue(ProfileOrganizer.isActiveInWindow(profile(true, old, old, old), HIDE_BEFORE));
    }

    /**
     * The predicate must not depend on wall-clock time, only the cutoff it is handed, so the
     * published gauge and the sidebar's on-demand scan can share it.
     */
    @Test
    public void predicateReadsOnlyTheSuppliedCutoff() {
        PeerProfile p = profile(false, NOW, 0, 0);
        assertTrue(ProfileOrganizer.isActiveInWindow(p, NOW - 1000));
        assertFalse(ProfileOrganizer.isActiveInWindow(p, NOW + 1000));
    }
}