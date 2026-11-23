package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * Covers how {@link ProfileManagerImpl} charges a peer for a tunnel test round,
 * both the rounds that came back and the ones that did not.
 *
 * <p>The test cycle credited the hops of a passing round and charged nobody for
 * a failing one, so a peer's test record was only the rounds that answered: a
 * peer that silently eats our rounds accrues nothing against it while the peers
 * that answer keep lowering their test RTT, and the selection gate reading that
 * RTT cannot see the difference. These cases pin that both halves are recorded,
 * and that the failure is a count only - nothing here may penalise the peer,
 * because the hop a failed round is charged to is the hop we sent it through,
 * not necessarily the hop that dropped it.
 */
public class ProfileManagerImplTunnelTestChargeTest {

    /** Round trip of the passing round under test, in milliseconds. */
    private static final long REPLY_MS = 400L;

    /** Statistics group for the rate stats the history creates, freed afterwards. */
    private static final String GROUP = "tunnelTestChargeTest";

    /** The hop the round under test was sent through. */
    private final Hash _peer = Hash.create(new byte[Hash.HASH_LENGTH]);

    /** Supplies the log, clock and stat manager the history needs. */
    private RouterContext _real;

    /** The context handed to the profile manager, with the organizer stubbed. */
    private RouterContext _ctx;

    /** The peer's profile as the manager sees it. */
    private PeerProfile _profile;

    /** The real history behind the profile, where the charge lands. */
    private TunnelHistory _hist;

    /** The manager under test. */
    private ProfileManagerImpl _pm;

    /**
     * The charge cases. Each case builds its own context and profile, so the
     * instance carries nothing between them.
     */
    public ProfileManagerImplTunnelTestChargeTest() {
    }

    /**
     * Builds the real log manager, clock and stat manager the history needs, and
     * a context whose organizer hands back one stubbed profile over a real
     * tunnel history, so what the assertions read is the manager's own decision
     * to charge or not.
     */
    @Before
    public void setUp() {
        // LogManager.getLog() is final and cannot be stubbed on a mock, so the
        // log manager, clock and stat manager come from a real context.
        _real = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _real != null);

        _ctx = mock(RouterContext.class);
        when(_ctx.logManager()).thenReturn(_real.logManager());
        when(_ctx.statManager()).thenReturn(_real.statManager());
        when(_ctx.clock()).thenReturn(_real.clock());

        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(organizer);
        _profile = newProfile(organizer);

        _pm = new ProfileManagerImpl(_ctx);
    }

    /**
     * Drops the rate stats the case registered under its own group, so a later
     * run in the same JVM does not read this peer's rounds as its own.
     */
    @After
    public void tearDown() {
        if (_real != null) {
            _real.statManager().removeRateStat("tunnelHistory.rejectRate." + GROUP);
            _real.statManager().removeRateStat("tunnelHistory.failRate." + GROUP);
        }
    }

    /**
     * A profile whose tunnel history is the real one, so the counters the
     * manager writes are read back rather than verified as method calls.
     *
     * @param organizer the stubbed organizer the profile is served from
     * @return the profile the profile manager will be handed for the peer
     */
    private PeerProfile newProfile(ProfileOrganizer organizer) {
        PeerProfile p = mock(PeerProfile.class);
        _hist = new TunnelHistory(_ctx, GROUP);
        when(p.getTunnelHistory()).thenReturn(_hist);
        when(organizer.getOrCreateProfileNonblocking(any(Hash.class))).thenReturn(p);
        return p;
    }

    /**
     * A round that timed out is charged to the hop it was sent through. This is
     * the half that was missing: the failure path wrote nothing to any profile.
     */
    @Test
    public void testFailedRoundChargesTheHop() {
        _pm.tunnelTestFailed(_peer);
        assertEquals(1L, _hist.getLifetimeTestFailed());
        assertTrue(_hist.getLastTestFailed() > 0);
    }

    /**
     * A round that came back is counted beside the failures, not instead of
     * them, so the ratio between the two is available to whatever reads it.
     */
    @Test
    public void testPassingRoundCountsSeparatelyFromFailingOnes() {
        _pm.tunnelTestFailed(_peer);
        _pm.tunnelTestFailed(_peer);
        _pm.tunnelTestSucceeded(_peer, REPLY_MS);
        assertEquals(2L, _hist.getLifetimeTestFailed());
        assertEquals(1L, _hist.getLifetimeTestSucceeded());
    }

    /**
     * The charge does not touch the build record. A test round that failed says
     * nothing about whether this peer agreed to builds, and the acceptance ratio
     * selection reads must not move because a round trip did not complete.
     */
    @Test
    public void testFailedRoundLeavesTheBuildRecordAlone() {
        _pm.tunnelTestFailed(_peer);
        assertEquals(0L, _hist.getLifetimeRejected());
        assertEquals(0L, _hist.getLifetimeTimedOut());
        assertEquals(0L, _hist.getLifetimeFailed());
        assertEquals(1.0d, _hist.getAcceptanceRatio(), 0.0001d);
    }

    /**
     * A peer with no profile at all - excluded from profiling, or the reorganize
     * lock held - records nothing and throws nothing.
     */
    @Test
    public void testNoProfileRecordsNothing() {
        ProfileOrganizer empty = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(empty);
        _pm.tunnelTestFailed(_peer);
        assertEquals(0L, _hist.getLifetimeTestFailed());
        assertEquals(0L, _hist.getLastTestFailed());
    }
}
