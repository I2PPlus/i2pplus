package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.Test;

import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.util.OrderedProperties;

/**
 * Tests for the pure decision helpers of {@link IntroducerLookupJob}:
 * introducer hash extraction from transport addresses, unreachable-capability
 * detection, and the staggered retry backoff / attempt gating.
 *
 * @since 0.9.71+
 */
public class IntroducerLookupJobTest {

    private static final long NOW = 2_000_000_000L;
    private static final long TEN_MIN = 10 * 60 * 1000L;
    private static final long THIRTY_MIN = 30 * 60 * 1000L;
    private static final long SIX_HOURS = 6L * 60 * 60 * 1000L;

    // ---- getIntroducerHashes ------------------------------------------------

    @Test
    public void singleSSU2IntroducerExtracted() {
        Hash h = hash(1);
        List<RouterAddress> addrs = Collections.singletonList(
            ssu2Address(new String[] {"itag0", "ih0"}, new String[] {"77", b64(h)}));
        List<Hash> rv = IntroducerLookupJob.getIntroducerHashes(addrs);
        assertEquals(1, rv.size());
        assertEquals(h, rv.get(0));
    }

    @Test
    public void multipleSlotsExtractedInOrder() {
        Hash h0 = hash(2);
        Hash h1 = hash(3);
        List<RouterAddress> addrs = Collections.singletonList(
            ssu2Address(new String[] {"itag0", "ih0", "itag1", "ih1"},
                        new String[] {"11", b64(h0), "22", b64(h1)}));
        List<Hash> rv = IntroducerLookupJob.getIntroducerHashes(addrs);
        assertEquals(Arrays.asList(h0, h1), rv);
    }

    @Test
    public void ssu1OnlySlotYieldsNothing() {
        // SSU1 introducers carry ihost/iport/ikey but no router hash
        List<RouterAddress> addrs = Collections.singletonList(
            ssu2Address(new String[] {"itag0", "ihost0", "iport0", "ikey0"},
                        new String[] {"55", "1.2.3.4", "8888", b64(hash(4))}));
        assertTrue(IntroducerLookupJob.getIntroducerHashes(addrs).isEmpty());
    }

    @Test
    public void slotWithoutTagIgnored() {
        // ih present but no relay tag -> not an active introducer slot
        List<RouterAddress> addrs = Collections.singletonList(
            ssu2Address(new String[] {"ih0"}, new String[] {b64(hash(5))}));
        assertTrue(IntroducerLookupJob.getIntroducerHashes(addrs).isEmpty());
    }

    @Test
    public void malformedHashSkipped() {
        List<RouterAddress> addrs = Collections.singletonList(
            ssu2Address(new String[] {"itag0", "ih0"}, new String[] {"9", "!!!not-base64!!!"}));
        assertTrue(IntroducerLookupJob.getIntroducerHashes(addrs).isEmpty());
    }

    @Test
    public void wrongLengthHashSkipped() {
        byte[] short_ = new byte[16];
        Arrays.fill(short_, (byte) 7);
        List<RouterAddress> addrs = Collections.singletonList(
            ssu2Address(new String[] {"itag0", "ih0"}, new String[] {"9", Base64.encode(short_)}));
        assertTrue(IntroducerLookupJob.getIntroducerHashes(addrs).isEmpty());
    }

    @Test
    public void duplicatesAcrossAddressesDeduped() {
        Hash h = hash(6);
        List<RouterAddress> addrs = new ArrayList<>(2);
        addrs.add(ssu2Address(new String[] {"itag0", "ih0"}, new String[] {"1", b64(h)}));
        addrs.add(ssu2Address(new String[] {"itag0", "ih0"}, new String[] {"2", b64(h)}));
        List<Hash> rv = IntroducerLookupJob.getIntroducerHashes(addrs);
        assertEquals(Collections.singletonList(h), rv);
    }

    @Test
    public void nullAndEmptyInputsYieldEmpty() {
        assertTrue(IntroducerLookupJob.getIntroducerHashes(null).isEmpty());
        assertTrue(IntroducerLookupJob.getIntroducerHashes(new ArrayList<RouterAddress>(0)).isEmpty());
    }

    // ---- isUnreachablePeer --------------------------------------------------

    @Test
    public void unreachableCapDetected() {
        assertTrue(IntroducerLookupJob.isUnreachablePeer(routerInfo("XORU")));
    }

    @Test
    public void reachablePeerNotFlagged() {
        assertFalse(IntroducerLookupJob.isUnreachablePeer(routerInfo("XORf")));
    }

    @Test
    public void emptyCapsNotFlagged() {
        assertFalse(IntroducerLookupJob.isUnreachablePeer(routerInfo("")));
    }

    // ---- parseIntroducerHash ------------------------------------------------

    @Test
    public void roundTrip() {
        Hash h = hash(7);
        assertEquals(h, IntroducerLookupJob.parseIntroducerHash(b64(h)));
    }

    @Test
    public void nullAndGarbageReturnNull() {
        assertNull(IntroducerLookupJob.parseIntroducerHash(null));
        assertNull(IntroducerLookupJob.parseIntroducerHash(""));
        assertNull(IntroducerLookupJob.parseIntroducerHash("@@@@"));
    }

    // ---- backoffMillis ------------------------------------------------------

    @Test
    public void zeroFailsUsesMinRetry() {
        assertEquals(TEN_MIN, IntroducerLookupJob.backoffMillis(0));
    }

    @Test
    public void firstFailBacksOffThirtyMinutes() {
        assertEquals(THIRTY_MIN, IntroducerLookupJob.backoffMillis(1));
    }

    @Test
    public void backoffDoublesPerFailure() {
        assertEquals(THIRTY_MIN * 2, IntroducerLookupJob.backoffMillis(2));
        assertEquals(THIRTY_MIN * 4, IntroducerLookupJob.backoffMillis(3));
    }

    @Test
    public void backoffCappedAtSixHours() {
        assertEquals(SIX_HOURS, IntroducerLookupJob.backoffMillis(8));
        assertEquals(SIX_HOURS, IntroducerLookupJob.backoffMillis(50));
    }

    // ---- canAttempt ---------------------------------------------------------

    @Test
    public void neverAttemptedAllowed() {
        assertTrue(IntroducerLookupJob.canAttempt(NOW, null));
    }

    @Test
    public void freshAttemptGatedWhileInFlight() {
        long[] state = {NOW - TEN_MIN + 1000, 0};
        assertFalse(IntroducerLookupJob.canAttempt(NOW, state));
    }

    @Test
    public void minRetryBoundaryAllowsAttempt() {
        long[] state = {NOW - TEN_MIN, 0};
        assertTrue(IntroducerLookupJob.canAttempt(NOW, state));
    }

    @Test
    public void failedAttemptGatedUntilBackoffElapses() {
        long[] state = {NOW - THIRTY_MIN + 1, 1};
        assertFalse(IntroducerLookupJob.canAttempt(NOW, state));
        long[] elapsed = {NOW - THIRTY_MIN, 1};
        assertTrue(IntroducerLookupJob.canAttempt(NOW, elapsed));
    }

    @Test
    public void repeatedFailureGateUsesExponentialWindow() {
        // 3 failures -> 120 minute backoff window
        long[] state = {NOW - THIRTY_MIN * 4 + 1, 3};
        assertFalse(IntroducerLookupJob.canAttempt(NOW, state));
        long[] elapsed = {NOW - THIRTY_MIN * 4, 3};
        assertTrue(IntroducerLookupJob.canAttempt(NOW, elapsed));
    }

    // ---- helpers ------------------------------------------------------------

    /** An SSU2-style address with exactly the given option keys/values. */
    private static RouterAddress ssu2Address(String[] keys, String[] values) {
        OrderedProperties opts = new OrderedProperties();
        for (int i = 0; i < keys.length; i++) {
            opts.setProperty(keys[i], values[i]);
        }
        return new RouterAddress("SSU2", opts, 5);
    }

    /** A RouterInfo whose published capabilities string is the given value. */
    private static RouterInfo routerInfo(String caps) {
        RouterInfo ri = new RouterInfo();
        Properties opts = new Properties();
        opts.setProperty(RouterInfo.PROP_CAPABILITIES, caps);
        ri.setOptions(opts);
        return ri;
    }

    private static String b64(Hash h) {
        return Base64.encode(h.getData());
    }

    /** Deterministic 32-byte hash with every byte set to the given value. */
    private static Hash hash(int val) {
        byte[] b = new byte[Hash.HASH_LENGTH];
        Arrays.fill(b, (byte) val);
        return Hash.create(b);
    }
}
