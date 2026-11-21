package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Exercises {@link MessageValidator}'s two gates: the message-id duplicate
 * filter and the expiry window built from {@link Router#CLOCK_FUDGE_FACTOR}.
 */
public class MessageValidatorTest {

    /**
     * A holder for one {@link MessageValidator}, built fresh for each test
     * method so the duplicate filter starts empty.
     */
    public MessageValidatorTest() {}

    /** Shared context, skipped when the suite runs without one. */
    private static RouterContext _context;
    /** Validator under test, built fresh for each test method. */
    private MessageValidator _validator;

    /**
     * Grab the shared router context once for the class, and skip every test
     * if the validator needs one that the suite cannot provide.
     */
    @BeforeClass
    public static void checkContext() {
        _context = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    /**
     * Build a fresh validator for the test method, with its duplicate filter
     * empty.
     */
    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _validator = new MessageValidator(_context);
        _validator.startup();
    }

    /**
     * A message id the filter has not seen, expiring a minute out, passes.
     */
    @Test
    public void testValidMessage() {
        long now = _context.clock().now();
        String result = _validator.validateMessage(12345L, now + 60000);
        assertNull("Valid message should not be rejected", result);
    }

    /**
     * A message three fudge factors past its expiry is rejected, well outside
     * the 1.5 fudge factors of clock slack the validator grants.
     */
    @Test
    public void testExpiredMessage() {
        long now = _context.clock().now();
        long expired = now - Router.CLOCK_FUDGE_FACTOR * 3;
        String result = _validator.validateMessage(expired);
        assertNotNull("Expired message should be rejected", result);
    }

    /**
     * A message expiring five fudge factors out is rejected: the future window
     * is four fudge factors, so a peer cannot park a message in the NetDb
     * filter for an arbitrarily long time.
     */
    @Test
    public void testFarFutureMessage() {
        long now = _context.clock().now();
        long future = now + Router.CLOCK_FUDGE_FACTOR * 5;
        String result = _validator.validateMessage(future);
        assertNotNull("Far future message should be rejected", result);
    }

    /**
     * A message id seen twice inside its validity window is rejected the
     * second time, and says so in words.
     */
    @Test
    public void testDuplicateDetection() {
        long now = _context.clock().now();
        long msgId = 99999L;
        long expiry = now + 60000;

        assertNull("First message should be valid", _validator.validateMessage(msgId, expiry));
        String dup = _validator.validateMessage(msgId, expiry);
        assertNotNull("Duplicate should be rejected", dup);
        assertTrue("Should say duplicate", dup.contains("uplicate"));
    }

    /**
     * Distinct message ids inside one validity window are not duplicates of
     * each other.
     */
    @Test
    public void testDifferentMessageIdsNotDuplicates() {
        long now = _context.clock().now();
        long expiry = now + 60000;

        assertNull(_validator.validateMessage(100L, expiry));
        assertNull(_validator.validateMessage(200L, expiry));
        assertNull(_validator.validateMessage(300L, expiry));
    }

    /**
     * The expiration-only overload accepts a message that is still live and
     * rejects one already past the fudge window, without consulting the id
     * filter at all.
     */
    @Test
    public void testExpirationOnlyValidation() {
        long now = _context.clock().now();
        assertNull(_validator.validateMessage(now + 30000));
        assertNotNull(_validator.validateMessage(now - Router.CLOCK_FUDGE_FACTOR * 3));
    }

    /**
     * One fudge factor past expiry is still accepted: the rejection threshold
     * is 1.5 fudge factors, the clock slack granted to a peer before its
     * messages are dropped.
     */
    @Test
    public void testBoundaryExpiration() {
        long now = _context.clock().now();
        long borderline = now - Router.CLOCK_FUDGE_FACTOR;
        assertNull("Borderline expiration should be accepted", _validator.validateMessage(borderline));
    }

    /**
     * The expiry reason carries a duration a reader can use, not a raw
     * millisecond count to divide by hand at 3am.
     */
    @Test
    public void testExpiredReasonUsesTheHouseDurationFormatter() {
        long now = _context.clock().now();
        String reason =
            _validator.validateMessage(now - Router.CLOCK_FUDGE_FACTOR * 3);
        assertNotNull("Expired message should be rejected", reason);
        assertTrue("says how long ago: " + reason, reason.contains("ago"));
        assertFalse("no raw millisecond count: " + reason,
                    reason.matches(".*\\d{4,}ms.*"));
    }

    /**
     * The future-window reason names the wait as a duration as well, and keeps
     * it out of a parenthetical so it reads as a key-value segment beside the
     * rest of the line.
     */
    @Test
    public void testFutureReasonNamesTheWaitNotAParenthetical() {
        long now = _context.clock().now();
        long farFuture = now + 4 * Router.CLOCK_FUDGE_FACTOR + 60_000L;
        String reason = _validator.validateMessage(farFuture);
        assertNotNull("Far future message should be rejected", reason);
        assertTrue("names the wait: " + reason,
                   reason.contains("Expires in:"));
        assertFalse("no raw millisecond count: " + reason,
                    reason.matches(".*\\d+ms.*"));
    }

    /**
     * Restarting the validator empties the duplicate filter, so a message id
     * seen before the shutdown is accepted again afterwards.
     */
    @Test
    public void testShutdownAndRestart() {
        long now = _context.clock().now();
        long expiry = now + 60000;

        _validator.validateMessage(1L, expiry);
        _validator.shutdown();
        _validator.startup();

        assertNull("After restart, old messages should not be duplicates", _validator.validateMessage(1L, expiry));
    }
}
