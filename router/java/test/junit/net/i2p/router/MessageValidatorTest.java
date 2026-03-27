package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class MessageValidatorTest {

    private static RouterContext _context;
    private MessageValidator _validator;

    @BeforeClass
    public static void checkContext() {
        _context = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _validator = new MessageValidator(_context);
        _validator.startup();
    }

    @Test
    public void testValidMessage() {
        long now = _context.clock().now();
        String result = _validator.validateMessage(12345L, now + 60000);
        assertNull("Valid message should not be rejected", result);
    }

    @Test
    public void testExpiredMessage() {
        long now = _context.clock().now();
        long expired = now - Router.CLOCK_FUDGE_FACTOR * 3;
        String result = _validator.validateMessage(expired);
        assertNotNull("Expired message should be rejected", result);
    }

    @Test
    public void testFarFutureMessage() {
        long now = _context.clock().now();
        long future = now + Router.CLOCK_FUDGE_FACTOR * 5;
        String result = _validator.validateMessage(future);
        assertNotNull("Far future message should be rejected", result);
    }

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

    @Test
    public void testDifferentMessageIdsNotDuplicates() {
        long now = _context.clock().now();
        long expiry = now + 60000;

        assertNull(_validator.validateMessage(100L, expiry));
        assertNull(_validator.validateMessage(200L, expiry));
        assertNull(_validator.validateMessage(300L, expiry));
    }

    @Test
    public void testExpirationOnlyValidation() {
        long now = _context.clock().now();
        assertNull(_validator.validateMessage(now + 30000));
        assertNotNull(_validator.validateMessage(now - Router.CLOCK_FUDGE_FACTOR * 3));
    }

    @Test
    public void testBoundaryExpiration() {
        long now = _context.clock().now();
        long borderline = now - Router.CLOCK_FUDGE_FACTOR;
        assertNull("Borderline expiration should be accepted", _validator.validateMessage(borderline));
    }

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
