package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The window an I2NP message expiration may fall in, in absolute time.
 *
 * <p>Both bounds are derived from {@link Router#CLOCK_FUDGE_FACTOR}, the
 * tolerance for a peer's clock being wrong, and they are what decides whether a
 * router that is three minutes off can talk to us at all: it is a working peer
 * for the rest of the network, and a message-dropping one if the tolerance is
 * cut in half. The other tests in this package scale their inputs by the
 * constant, so they stay green however wide the window is; these pin the width.
 *
 * @since 0.9.72+
 */
public class MessageValidatorWindowTest {

    /** Runs the cases; the context it needs is static. */
    public MessageValidatorWindowTest() {}

    /** Context for the clock and stats the validator reads. */
    private static RouterContext _context;
    /** The validator under test, rebuilt per case so no state carries over. */
    private MessageValidator _validator;

    /** The past bound: an expiration this far back is the last one accepted. */
    private static final long PAST_WINDOW = 90 * 1000L;
    /** The future bound: an expiration this far ahead is the last one accepted. */
    private static final long FUTURE_WINDOW = 240 * 1000L;

    /**
     * One context for the validator's clock and stats; the cases are skipped if
     * no context can be built at all.
     */
    @BeforeClass
    public static void setUpClass() {
        _context = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    /** A validator with its duplicate filter running, as in a live router. */
    @Before
    public void setUp() {
        _validator = new MessageValidator(_context);
        _validator.startup();
    }

    /** The window is what the clock tolerance makes it, not a magic number. */
    @Test
    public void theWindowsComeFromTheClockTolerance() {
        assertEquals(PAST_WINDOW, Router.CLOCK_FUDGE_FACTOR * 3 / 2);
        assertEquals(FUTURE_WINDOW, 4 * Router.CLOCK_FUDGE_FACTOR);
    }

    /** A message that expired a second inside the past window is accepted. */
    @Test
    public void justExpiredIsAccepted() {
        long now = _context.clock().now();
        assertNull(_validator.validateMessage(now - PAST_WINDOW + 1000));
    }

    /** A message that expired a second outside it is rejected. */
    @Test
    public void expiredPastTheWindowIsRejected() {
        long now = _context.clock().now();
        assertNotNull(_validator.validateMessage(now - PAST_WINDOW - 1000));
    }

    /**
     * A peer whose clock runs three minutes ahead is a working peer: its
     * messages expire four minutes out and that is still inside the window.
     */
    @Test
    public void threeMinutesAheadIsAccepted() {
        long now = _context.clock().now();
        assertNull(_validator.validateMessage(now + 180 * 1000L));
        assertNull(_validator.validateMessage(now + FUTURE_WINDOW - 1000));
    }

    /** Past the future window the message is rejected, and says how far ahead. */
    @Test
    public void tooFarAheadIsRejected() {
        long now = _context.clock().now();
        String reason = _validator.validateMessage(now + FUTURE_WINDOW + 1000);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("future"));
    }

    /**
     * The lease margin is the tighter of the two clock constants. It is a
     * decision about our own tunnels, not a judgement of a peer's clock, and
     * keeping it separate is what lets the message window stay upstream-wide.
     */
    @Test
    public void leaseMarginIsTighterThanTheClockTolerance() {
        assertTrue(Router.LEASE_FUDGE_FACTOR < Router.CLOCK_FUDGE_FACTOR);
    }
}
