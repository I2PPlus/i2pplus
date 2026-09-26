package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins the peak-throughput contract in {@link PeerProfile}.
 *
 * <p>Peak throughput records demonstrated capability, not recency, so
 * coalescing folds a new measurement in but never erases an existing peak.
 * {@code coalesceOnly()} therefore takes no decay argument: there is nothing for
 * it to control.
 *
 * @since 0.9.72
 */
public class PeerProfilePeakDecayTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        _ctx = RouterTestHelper.getContext();
    }

    private static PeerProfile newProfile() {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        return new PeerProfile(_ctx, Hash.create(new byte[Hash.HASH_LENGTH]));
    }

    @Test
    public void testCoalesceOnlyTakesNoDecayArgument() throws Exception {
        // A decay argument would imply peaks can be eroded; the only overload
        // must be the argument-free one.
        PeerProfile.class.getDeclaredMethod("coalesceOnly");
        try {
            PeerProfile.class.getDeclaredMethod("coalesceOnly", boolean.class);
            org.junit.Assert.fail("coalesceOnly(boolean) must not exist: peak values are never decayed");
        } catch (NoSuchMethodException expected) {
            // correct
        }
    }

    /** A peak recorded from a measurement survives coalescing untouched. */
    @Test
    public void testCoalescePreservesPeak() {
        PeerProfile prof = newProfile();
        // 100 KBps, expressed as bytes in a 1 minute period
        int bytes = (int) (100 * 60 * 1024L);
        prof.dataPushed1m(bytes);
        float before = prof.getPeakTunnel1mThroughputKBps();
        assertTrue("a pushed measurement must register a peak", before > 0f);
        prof.coalesceOnly();
        assertEquals("coalescing must not decay a recorded peak",
                     before, prof.getPeakTunnel1mThroughputKBps(), 0.0001f);
    }

    /**
     * Repeated coalescing is idempotent for peak values: nothing erodes them no
     * matter how many reorganize cycles pass.
     */
    @Test
    public void testRepeatedCoalesceDoesNotErodePeak() {
        PeerProfile prof = newProfile();
        prof.dataPushed1m((int) (250 * 60 * 1024L));
        float before = prof.getPeakTunnel1mThroughputKBps();
        for (int i = 0; i < 20; i++) {
            prof.coalesceOnly();
        }
        assertEquals(before, prof.getPeakTunnel1mThroughputKBps(), 0.0001f);
    }

    /** coalesceStats() must not erode peaks either. */
    @Test
    public void testCoalesceStatsDoesNotErodePeak() {
        PeerProfile prof = newProfile();
        prof.dataPushed1m((int) (75 * 60 * 1024L));
        float before = prof.getPeakTunnel1mThroughputKBps();
        prof.coalesceStats();
        assertEquals(before, prof.getPeakTunnel1mThroughputKBps(), 0.0001f);
    }
}
