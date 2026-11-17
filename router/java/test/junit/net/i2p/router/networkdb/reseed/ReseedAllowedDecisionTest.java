package net.i2p.router.networkdb.reseed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Whether a reseed may be attempted at all, before the peer count and the
 * downtime decide how often it happens.
 * <p>
 * The check was written as one expression whose second arm was
 * {@code || !vmCommSystem}. On every real router {@code vmCommSystem} is false,
 * so that arm made the whole expression true and none of the opt-outs stopped
 * anything: a router with {@code router.reseedDisable} set, or one of the
 * {@code .i2pnoreseed} marker files, or a shutdown in progress, fetched a
 * reseed over the clearnet anyway - in the marker-file case it even logged that
 * reseeding was disabled by config file first.
 *
 * @since 0.9.72+
 */
public class ReseedAllowedDecisionTest {

    /** Runs the decision; it holds no state. */
    public ReseedAllowedDecisionTest() {}

    /** A router with nothing in its way may reseed. */
    @Test
    public void aHealthyRouterMayReseed() {
        assertTrue(ReseedChecker.reseedAllowed(false, false, false, false, true));
    }

    /**
     * The reported case: the configuration switch stops it on a real router,
     * where the old expression's {@code || !vmCommSystem} arm made it proceed.
     */
    @Test
    public void configurationStopsItOnARealRouter() {
        // vmCommSystem false is every real router, and the old expression was
        // true there whatever the rest said
        assertFalse(ReseedChecker.reseedAllowed(true, false, false, false, true));
    }

    /** Either marker file stops it, which is what the operator put it there for. */
    @Test
    public void aMarkerFileStopsIt() {
        assertFalse(ReseedChecker.reseedAllowed(false, true, false, false, true));
    }

    /** A shutdown in progress stops it: the fetch would be cut off mid-write. */
    @Test
    public void aShutdownStopsIt() {
        assertFalse(ReseedChecker.reseedAllowed(false, false, true, false, true));
    }

    /** A test JVM on the VM comm system has no clearnet to fetch over. */
    @Test
    public void aTestJvmStopsIt() {
        assertFalse(ReseedChecker.reseedAllowed(false, false, false, true, true));
    }

    /** With no IPv4 or IPv6 address there is nothing to fetch a reseed over. */
    @Test
    public void noClearnetAddressStopsIt() {
        assertFalse(ReseedChecker.reseedAllowed(false, false, false, false, false));
    }

    /**
     * Every opt-out stops it on its own, whatever the others say - the old
     * expression let all four through at once on a real router.
     */
    @Test
    public void everyOptOutStopsItOnItsOwn() {
        assertFalse(ReseedChecker.reseedAllowed(true, false, false, false, true));
        assertFalse(ReseedChecker.reseedAllowed(false, true, false, false, true));
        assertFalse(ReseedChecker.reseedAllowed(false, false, true, false, true));
        assertFalse(ReseedChecker.reseedAllowed(false, false, false, true, true));
        assertFalse(ReseedChecker.reseedAllowed(false, false, false, false, false));
    }
}
