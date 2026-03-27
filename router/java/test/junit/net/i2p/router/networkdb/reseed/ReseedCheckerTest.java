package net.i2p.router.networkdb.reseed;

import static org.junit.Assert.*;

import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for ReseedChecker state management and constants.
 */
public class ReseedCheckerTest {

    private static RouterContext _context;
    private ReseedChecker _checker;

    @BeforeClass
    public static void checkContext() {
        _context = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _checker = new ReseedChecker(_context);
    }

    @Test
    public void testMinimumConstant() {
        assertEquals("MINIMUM should be 50", 50, ReseedChecker.MINIMUM);
    }

    @Test
    public void testNotInProgressInitially() {
        assertFalse("Should not be in progress initially", _checker.inProgress());
    }

    @Test
    public void testStatusEmptyInitially() {
        assertNotNull(_checker.getStatus());
        assertEquals("", _checker.getStatus());
    }

    @Test
    public void testErrorEmptyInitially() {
        assertNotNull(_checker.getError());
        assertEquals("", _checker.getError());
    }

    @Test
    public void testStatusNotNull() {
        String status = _checker.getStatus();
        assertNotNull(status);
    }

    @Test
    public void testErrorNotNull() {
        String error = _checker.getError();
        assertNotNull(error);
    }
}
