package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;

/**
 * Covers how {@link ProfileManagerImpl#dbLookupReply} accounts for a NetDb
 * lookup answered with a redirect to other routers.
 *
 * <p>Only a handful of floodfills hold any one key, so answering with a
 * redirect is the ordinary way one responds to a lookup. Two things depend on
 * that reply being recorded at all: the profile has to be expanded for NetDb
 * use, because {@code IterativeSearchJob.sendQuery()} sizes each peer's reply
 * timeout from the response time stored there; and the combined responsiveness
 * rate has to see a success, because {@code FloodfillPeerSelector} ranks a
 * floodfill by comparing its last failure time against its last answer time.
 */
public class ProfileManagerImplReplyCreditTest {

    /** Milliseconds the peer took to answer the redirect under test. */
    private static final long REPLY_MS = 250L;

    /** The peer the reply under test came from. */
    private final Hash _peer = Hash.create(new byte[Hash.HASH_LENGTH]);

    /** Supplies the log, clock and stat manager the profile objects need. */
    private RouterContext _real;

    /** The context handed to the profile manager, with the organizer stubbed. */
    private RouterContext _ctx;

    /** The organizer the profile manager looks the peer's profile up through. */
    private ProfileOrganizer _organizer;

    /** The peer's profile as the manager sees it. */
    private PeerProfile _profile;

    /** The real history behind the profile, where the answer credit lands. */
    private DBHistory _hist;

    /** Response times recorded against the peer, fed by the reply's timing. */
    private RateStat _responseTime;

    /** Introduction counts recorded against the peer. */
    private RateStat _introduction;

    /** The manager under test. */
    private ProfileManagerImpl _pm;

    /**
     * The reply-credit cases. Each case builds its own context and profile, so
     * the instance carries nothing between them.
     */
    public ProfileManagerImplReplyCreditTest() {
    }

    /**
     * Builds the real log manager, clock and stat manager the profile objects
     * need, and a context whose organizer hands back one stubbed profile, so
     * the manager's own decisions - expand or not, record or not - are what the
     * assertions read.
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

        _organizer = mock(ProfileOrganizer.class);
        when(_ctx.profileOrganizer()).thenReturn(_organizer);
        _profile = newProfile();
        when(_organizer.getOrCreateProfileNonblocking(any(Hash.class))).thenReturn(_profile);

        _pm = new ProfileManagerImpl(_ctx);
    }

    /**
     * Drops the rate stats the case registered under its own group, so a later
     * run in the same JVM does not read this peer's answers as its own.
     */
    @After
    public void tearDown() {
        if (_real != null) {
            String group = "replyCreditTest";
            _real.statManager().removeRateStat("dbHistory.failedLookupRate." + group);
            _real.statManager().removeRateStat("dbHistory.invalidReplyRate." + group);
            _real.statManager().removeRateStat("dbResponseTime." + group);
            _real.statManager().removeRateStat("dbIntroduction." + group);
        }
    }

    /**
     * A profile that has not been expanded for NetDb use yet, with the real
     * DB history and rate objects behind it.
     *
     * @return the profile the profile manager will be handed for the peer
     */
    private PeerProfile newProfile() {
        PeerProfile p = mock(PeerProfile.class);
        _hist = new DBHistory(_ctx, "replyCreditTest");
        _responseTime = new RateStat("dbResponseTime", "Time for NetDb response from peer (ms)",
                                     "replyCreditTest",
                                     new long[] {RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR});
        _introduction = new RateStat("dbIntroduction", "Peers received in NetDb replies",
                                     "replyCreditTest",
                                     new long[] {RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR});
        when(p.getDBHistory()).thenReturn(_hist);
        when(p.getDbResponseTime()).thenReturn(_responseTime);
        when(p.getDbIntroduction()).thenReturn(_introduction);
        // false for the expansion check itself, true afterwards, so the
        // reliability update that follows takes the expanded path
        when(p.getIsExpandedDB()).thenReturn(false, true);
        return p;
    }

    /**
     * A peer whose only NetDb traffic is redirect replies still has to end up
     * with a response time recorded against it. Before, the reply was dropped
     * outright whenever the profile was not already expanded for NetDb use, so
     * such a peer never had a measured response time at all, and every lookup
     * asked of it waited the full default reply timeout instead of a multiple
     * of what it actually answers in.
     */
    @Test
    public void testRedirectExpandsTheProfileAndRecordsTheResponseTime() {
        _pm.dbLookupReply(_peer, 2, 1, 0, 0, REPLY_MS);
        verify(_profile).expandDBProfile();
        assertEquals(1L, _responseTime.getLifetimeEventCount());
        assertEquals(REPLY_MS, (long) _responseTime.getLifetimeAverageValue());
    }

    /**
     * A redirect is a request answered, so it feeds the combined NetDb
     * responsiveness rate with a success and stamps the last-answered time.
     * Without that, a floodfill that never happened to hold the key keeps only
     * its failures, and the ranking that compares a failure time against an
     * answer time sees it as never having answered anything.
     */
    @Test
    public void testRedirectCreditsTheResponsivenessRate() {
        _pm.dbLookupReply(_peer, 0, 4, 0, 0, REPLY_MS);
        RateStat rate = _hist.getFailedLookupRate();
        assertNotNull(rate);
        assertEquals(1L, rate.getLifetimeEventCount());
        assertEquals(0.0d, rate.getLifetimeAverageValue(), 0.0001d);
        assertTrue(_hist.getLastLookupSuccessful() > 0);
        assertEquals(0L, _hist.getSuccessfulLookups());
        assertEquals(0L, _hist.getFailedLookups());
    }

    /**
     * Repeated redirects from a profile that is already expanded are recorded
     * every time, and the profile is not expanded a second time for them.
     */
    @Test
    public void testRepeatedRedirectsAreAllRecorded() {
        when(_profile.getIsExpandedDB()).thenReturn(true);
        for (int i = 0; i < 4; i++) {
            _pm.dbLookupReply(_peer, 1, 2, 0, 0, REPLY_MS);
        }
        verify(_profile, never()).expandDBProfile();
        assertEquals(4L, _hist.getFailedLookupRate().getRate(RateConstants.ONE_HOUR).getLifetimeEventCount());
        assertEquals(0.0d, _hist.getFailedLookupRate().getLifetimeAverageValue(), 0.0001d);
        assertEquals(4L, _introduction.getLifetimeEventCount());
        assertEquals(0L, _hist.getFailedLookups());
        assertEquals(0L, _hist.getSuccessfulLookups());
    }

    /**
     * A peer with no profile at all - excluded from profiling, or the reorganize
     * lock held - records nothing and throws nothing.
     */
    @Test
    public void testNoProfileRecordsNothing() {
        when(_organizer.getOrCreateProfileNonblocking(any(Hash.class))).thenReturn(null);
        _pm.dbLookupReply(_peer, 1, 1, 0, 0, REPLY_MS);
        assertEquals(0L, _hist.getFailedLookupRate().getLifetimeEventCount());
        assertEquals(0L, _hist.getLastLookupSuccessful());
        assertEquals(0L, _responseTime.getLifetimeEventCount());
    }
}
