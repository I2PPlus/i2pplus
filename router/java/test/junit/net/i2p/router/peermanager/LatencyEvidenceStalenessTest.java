package net.i2p.router.peermanager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.Clock;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The three latency and throughput flags that gate fast-tier admission are
 * dated, so a peer is judged on evidence it can still refresh.
 *
 * <p>Each of these used to be a one-way door. The peer-test average had no
 * staleness window, so a peer measured slow once was excluded from the
 * "untested, give it a chance" path for the life of the profile file. The
 * low-latency flag froze at its last value once the direct-link RTT aged past
 * its validity window, in both directions: a stale-true flag admitted a peer on
 * a measurement it could no longer refresh, and a stale-false one condemned it
 * for a latency it no longer had. The peak-throughput ring is deliberately never
 * decayed, and it was used as an admission credential, so the quality-peer
 * count ratcheted upward over days and flipped the tier into its stricter
 * quality mode.
 *
 * <p>All three are only reachable with a context (the profile reads the clock),
 * so this is a context test, not a pure-decision one.
 *
 * @since 0.9.72+
 */
public class LatencyEvidenceStalenessTest {

    private static RouterContext _ctx;

    private static final long HOUR = 60 * 60 * 1000L;
    private static final long DAY = 24 * HOUR;

    private ProfilePersistenceHelper _helper;
    private Clock _clock;

    @BeforeClass
    public static void setUpClass() {
        _ctx = RouterTestHelper.newContext();
    }

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("No context available", _ctx != null);
        // The helper takes its store from the context's router dir plus the
        // profile-dir property. RouterTestHelper.newContext() points the router
        // dir at the build's test data dir, already out of the source tree, and
        // each profile is written under a hash-named shard of its own, so the
        // round-trip tests cannot collide with each other.
        _helper = new ProfilePersistenceHelper(_ctx);
        _clock = _ctx.clock();
    }

    @After
    public void tearDown() {
        for (PeerProfile p : _written) {
            File f = profileFile(p);
            if (f.exists()) {f.delete();}
        }
        _written.clear();
    }

    /** Peers this test wrote, so tearDown can clean their files up. */
    private final java.util.List<PeerProfile> _written = new java.util.ArrayList<>();

    /**
     * Where the helper keeps a peer's profile. The layout is the store's: a shard
     * directory named for the first character of the base64 hash, holding a file
     * named for the whole hash.
     *
     * @param p the peer profile
     * @return the file the helper reads and writes for it
     */
    private File profileFile(PeerProfile p) {
        String b64 = p.getPeer().toBase64();
        String dir = _ctx.getProperty(ProfilePersistenceHelper.PROP_PEER_PROFILE_DIR,
                                      ProfilePersistenceHelper.DEFAULT_PEER_PROFILE_DIR);
        File base = new File(_ctx.getRouterDir(), dir);
        return new File(new File(base, "p" + b64.charAt(0)), "profile-" + b64 + ".txt.gz");
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        return Hash.create(data);
    }

    private PeerProfile profile(int seed) {
        return new PeerProfile(_ctx, hash(seed));
    }

    /**
     * Writes a profile through the helper and reads the file back, so a test can
     * assert on what a restart sees. The file is the one the helper names for
     * the peer, so the test reads that path back through the helper's own loader.
     *
     * @param p the profile to write
     * @return the profile as reloaded from disk
     * @throws IOException if the profile file cannot be written or read back
     */
    private PeerProfile writeAndRead(PeerProfile p) throws IOException {
        _written.add(p);
        // The loader discards a profile with no activity timestamp at all - no
        // last-sent-successfully, last-heard-from or last-heard-about - as one
        // whose learned history is worthless. A bare profile built in a test has
        // none of those, so give it one; the keys under test are the dated
        // evidence flags, and the file has to survive the loader to be read.
        p.setLastHeardFrom(_clock.now());
        assertTrue("the helper must store the profile", _helper.writeProfile(p));
        File f = profileFile(p);
        assertTrue("at " + f.getName(), f.exists());
        // The helper's own loader, with a cutoff a year back: this is the parse a
        // restart does, which is the whole point of the round trip.
        long cutoff = _ctx.clock().now() - 365L * 24 * 60 * 60 * 1000;
        PeerProfile r = _helper.readProfile(f, cutoff);
        assertNotNull("the file must parse back into a profile", r);
        assertEquals(p.getPeer(), r.getPeer());
        return r;
    }

    // ---- R4: the peer-test average expires ----

    /**
     * A peer test older than the validity window reads as untested, which is what
     * reopens the fast tier's untested-peer path.
     */
    @Test
    public void testPeerTestExpiresAfterTheValidityWindow() {
        long now = _clock.now();
        PeerProfile p = profile(1);
        p.setPeerTestTimeAverage(900f);
        p.setPeerTestTimeLastUpdate(now - 23 * HOUR);
        assertTrue("a 23 h-old test is evidence", p.hasBeenTested(now));

        p.setPeerTestTimeLastUpdate(now - 25 * HOUR);
        assertFalse("a 25 h-old test is not", p.hasBeenTested(now));
        assertTrue("and the undated form still reports the average exists",
                   p.hasBeenTested());
    }

    /**
     * A peer-test average with no date at all — a profile file written before the
     * date was stored — is not evidence. The average alone cannot say when the
     * peer was measured, and the fast tier admits on "untested".
     */
    @Test
    public void testUndatedPeerTestIsNotEvidence() {
        PeerProfile p = profile(2);
        p.setPeerTestTimeAverage(900f);
        assertFalse(p.hasBeenTested(_clock.now()));
    }

    /** No average is not "tested", whatever the date says. */
    @Test
    public void testNoAverageIsNeverTested() {
        PeerProfile p = profile(3);
        p.setPeerTestTimeLastUpdate(_clock.now());
        assertFalse(p.hasBeenTested(_clock.now()));
    }

    // ---- R5: proven throughput is a dated credential ----

    /** A peak earned inside the TTL is current evidence. */
    @Test
    public void testFreshPeakIsProvenThroughput() {
        long now = _clock.now();
        PeerProfile p = profile(4);
        p.setPeakTunnel1mThroughputKBps(50f);
        p.setPeakTunnel1mTime(now - DAY);
        assertTrue(p.hasProvenThroughput(now));
    }

    /**
     * A peak older than the TTL is not. The ring is never decayed, so this date
     * is the only thing that stops it being a credential forever — and as one it
     * pushed the quality-peer count past the mode switch, which demands
     * ten-minute-fresh activity of every candidate.
     */
    @Test
    public void testStalePeakIsNotProvenThroughput() {
        long now = _clock.now();
        PeerProfile p = profile(5);
        p.setPeakTunnel1mThroughputKBps(50f);
        p.setPeakTunnel1mTime(now - 8 * DAY);
        assertFalse(p.hasProvenThroughput(now));
        assertTrue("the peak itself survives as the capacity signal",
                   p.getPeakTunnel1mThroughputKBps() > 0);
    }

    /** A peak with no date is not proven: the file predates the credential. */
    @Test
    public void testUndatedPeakIsNotProven() {
        PeerProfile p = profile(6);
        p.setPeakTunnel1mThroughputKBps(50f);
        assertFalse(p.hasProvenThroughput(_clock.now()));
    }

    /** No peak is never proven. */
    @Test
    public void testNoPeakIsNeverProven() {
        PeerProfile p = profile(7);
        p.setPeakTunnel1mTime(_clock.now());
        assertFalse(p.hasProvenThroughput(_clock.now()));
    }

    /**
     * Carrying a full minute of traffic raises the ring and dates it, so the
     * credential cannot be earned without the date being recorded.
     */
    @Test
    public void testRaisingThePeakDatesIt() {
        PeerProfile p = profile(8);
        assertEquals(0, p.getPeakTunnel1mTime());
        p.dataPushed1m(50L * 60 * 1024);
        assertTrue("the ring was raised", p.getPeakTunnel1mThroughputKBps() > 0);
        assertTrue("and the date came with it", p.getPeakTunnel1mTime() > 0);
        assertTrue(p.hasProvenThroughput(_clock.now()));
    }

    /**
     * Traffic that does not raise the ring does not re-date the credential. A
     * peer that is merely being used, and not at its peak rate, is not
     * re-earning the proven-throughput claim.
     */
    @Test
    public void testOrdinaryTrafficDoesNotReDateThePeak() {
        PeerProfile p = profile(9);
        p.dataPushed1m(50L * 60 * 1024);
        long earned = p.getPeakTunnel1mTime();
        p.dataPushed1m(1L);
        assertEquals(earned, p.getPeakTunnel1mTime());
    }

    // ---- R6: the low-latency flag is tri-state ----

    /**
     * A first-hop RTT older than the validity window yields unknown, not the
     * last answer. The RTT is refreshed only by a pre-connect probe or a build,
     * and both need the peer to be selected, so freezing the flag made the last
     * verdict permanent.
     */
    @Test
    public void testStaleFirstHopRttYieldsUnknownNotFrozen() {
        long now = _clock.now();
        // A current measurement: the flag goes true, with evidence behind it.
        PeerProfile fresh = profile(10);
        fresh.setFirstHopRtt(50, now - 60 * 1000L);
        fresh.recalculateLowLatency();
        assertTrue(fresh.isLowLatency());
        assertTrue("with evidence", fresh.hasLatencyEvidence());

        // The same reading, past the 4 h validity window: unknown, not the last
        // answer. A profile file written days ago restores exactly this shape.
        PeerProfile stale = profile(11);
        stale.setFirstHopRtt(50, now - 5 * HOUR);
        stale.recalculateLowLatency();
        assertFalse("the stale true must not survive", stale.isLowLatency());
        assertFalse("and the peer is unmeasured, not measured-slow",
                    stale.hasLatencyEvidence());
    }

    /**
     * A peer that was measured slow and whose measurement then ages out is
     * forgiven, not condemned: it moves to unknown, which the fast tier's
     * filling path admits.
     */
    @Test
    public void testStaleSlowMeasurementIsForgiven() {
        long now = _clock.now();
        // Measured slow, recently: the flag is false, and that is fair.
        PeerProfile fresh = profile(12);
        fresh.setFirstHopRtt(900, now - 60 * 1000L);
        fresh.recalculateLowLatency();
        assertFalse(fresh.isLowLatency());
        assertTrue(fresh.hasLatencyEvidence());

        // The same slow reading, aged out: the peer is no longer held to it. The
        // RTT is refreshed only by a probe or a build, and both need the peer to
        // be selected, so a frozen false was a sentence with no appeal.
        PeerProfile stale = profile(13);
        stale.setFirstHopRtt(900, now - 5 * HOUR);
        stale.recalculateLowLatency();
        assertFalse(stale.isLowLatency());
        assertFalse("no longer held to a measurement it cannot refresh",
                    stale.hasLatencyEvidence());
    }

    /** A peer that has never been measured starts out unknown, not slow. */
    @Test
    public void testNeverMeasuredIsUnknown() {
        PeerProfile p = profile(14);
        p.recalculateLowLatency();
        assertFalse(p.isLowLatency());
        assertFalse(p.hasLatencyEvidence());
    }

    /** A current measurement that is low sets the flag true. */
    @Test
    public void testCurrentLowMeasurementIsTrue() {
        PeerProfile p = profile(15);
        long now = _clock.now();
        p.setFirstHopRtt(50, now - 60 * 1000L);
        p.recalculateLowLatency();
        assertTrue(p.isLowLatency());
        assertTrue(p.hasLatencyEvidence());
    }

    // ---- the three dates survive a round trip ----

    /**
     * Every one of these is a persisted flag whose meaning depends on its date,
     * so a restart must not turn a dated answer back into a permanent one.
     *
     * @throws IOException if the profile file cannot be written or read back
     */
    @Test
    public void testDatesRoundTrip() throws IOException {
        long now = _clock.now();
        PeerProfile p = profile(16);
        p.setPeerTestTimeAverage(700f);
        p.setPeerTestTimeLastUpdate(now - 2 * HOUR);
        p.setPeakTunnel1mThroughputKBps(40f);
        p.setPeakTunnel1mTime(now - 3 * DAY);
        p.setFirstHopRtt(60, now - 60 * 1000L);
        p.recalculateLowLatency();
        assertTrue(p.isLowLatency());

        PeerProfile r = writeAndRead(p);
        assertNotNull(r);
        assertEquals(now - 2 * HOUR, r.getPeerTestTimeLastUpdate());
        assertTrue("the peer test is still evidence", r.hasBeenTested(now));
        assertEquals(now - 3 * DAY, r.getPeakTunnel1mTime());
        assertTrue("the peak credential keeps its date", r.hasProvenThroughput(now));
        assertTrue("the low-latency flag survives with its evidence",
                   r.isLowLatency() && r.hasLatencyEvidence());
    }

    /**
     * A profile file that recorded no low-latency key restores as unknown, not as
     * "measured and slow". Every file written before the flag became tri-state
     * looks like this, and reading them as slow would evict the tier on a
     * measurement nobody took.
     *
     * @throws IOException if the profile file cannot be written or read back
     */
    @Test
    public void testMissingLowLatencyKeyRestoresUnknown() throws IOException {
        PeerProfile p = profile(17);
        PeerProfile r = writeAndRead(p);
        assertNotNull(r);
        assertFalse(r.isLowLatency());
        assertFalse("unmeasured, not measured-slow", r.hasLatencyEvidence());
    }

    /**
     * A measured-and-not-low peer persists that answer, and restores it: the
     * file must be able to say "no" as well as "not yet".
     *
     * @throws IOException if the profile file cannot be written or read back
     */
    @Test
    public void testMeasuredNotLowRoundTrips() throws IOException {
        long now = _clock.now();
        PeerProfile p = profile(18);
        p.setFirstHopRtt(900, now - 60 * 1000L);
        p.recalculateLowLatency();
        assertFalse(p.isLowLatency());
        assertTrue(p.hasLatencyEvidence());

        PeerProfile r = writeAndRead(p);
        assertNotNull(r);
        assertFalse(r.isLowLatency());
        assertTrue("the negative answer is recorded, not collapsed to unknown",
                   r.hasLatencyEvidence());
    }

    // ---- R7: the RTT baseline re-anchors ----

    /**
     * No baseline yet always anchors; a baseline of any age past the interval
     * re-anchors; one inside it does not.
     */
    @Test
    public void testBaselineReanchorWindow() {
        long now = 2_000_000_000_000L;
        assertTrue("never anchored", ProfileOrganizer.shouldReanchorBaseline(0, now));
        assertTrue("anchored at startup, a year on",
                   ProfileOrganizer.shouldReanchorBaseline(now - 365 * DAY, now));
        assertFalse("just anchored", ProfileOrganizer.shouldReanchorBaseline(now - HOUR, now));
        assertFalse("at the interval", ProfileOrganizer.shouldReanchorBaseline(now - ProfileOrganizer.BASELINE_REANCHOR_MS, now));
        assertTrue("past the interval",
                   ProfileOrganizer.shouldReanchorBaseline(now - ProfileOrganizer.BASELINE_REANCHOR_MS - 1, now));
    }

    /**
     * The interval is a day: long enough that the anti-amplification floor still
     * tracks a network that has become uniformly slow, short enough that a
     * condition observed during startup stops binding a year later.
     */
    @Test
    public void testBaselineReanchorIntervalIsADay() {
        assertEquals(24 * 60 * 60 * 1000L, ProfileOrganizer.BASELINE_REANCHOR_MS);
    }
}
