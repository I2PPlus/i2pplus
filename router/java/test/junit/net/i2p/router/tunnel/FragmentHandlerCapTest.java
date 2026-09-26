package net.i2p.router.tunnel;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.i2p.router.RouterContext;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for the bound on partially reassembled messages in
 * FragmentHandler, which keeps a peer from growing the map without limit by
 * sending fragments for many distinct message IDs.
 *
 * @since 0.9.71+
 */
public class FragmentHandlerCapTest {

    private static RouterContext _context;

    @BeforeClass
    public static void globalSetUp() {
        _context = new RouterContext(null);
    }

    @Test
    public void testMayStartNewFragmentedMessage() {
        assertTrue(FragmentHandler.mayStartNewFragmentedMessage(0));
        assertTrue(FragmentHandler.mayStartNewFragmentedMessage(1));
        assertTrue(FragmentHandler.mayStartNewFragmentedMessage(
                FragmentHandler.MAX_FRAGMENTED_MESSAGES - 1));
        assertFalse(FragmentHandler.mayStartNewFragmentedMessage(
                FragmentHandler.MAX_FRAGMENTED_MESSAGES));
        assertFalse(FragmentHandler.mayStartNewFragmentedMessage(
                FragmentHandler.MAX_FRAGMENTED_MESSAGES + 1));
    }

    @Test
    public void testGetOrCreateRefusesNewMessageWhenFull() {
        FragmentHandler handler = new FragmentHandler(_context, null);
        int max = FragmentHandler.MAX_FRAGMENTED_MESSAGES;
        for (int i = 0; i < max; i++) {
            assertNotNull("message " + i, handler.getOrCreate(i, i));
        }
        assertNull(handler.getOrCreate(max, max));
        assertNull(handler.getOrCreate(max + 1, max + 1));
        // messages already reassembling may still finish
        assertNotNull(handler.getOrCreate(0, 0));
        assertNotNull(handler.getOrCreate(max - 1, max - 1));
    }

    @Test
    public void testGetOrCreateStartsNewMessageBelowCap() {
        FragmentHandler handler = new FragmentHandler(_context, null);
        FragmentedMessage first = handler.getOrCreate(7, 7);
        assertNotNull(first);
        // a second fragment of the same message finds the existing one
        assertSame(first, handler.getOrCreate(7, 7));
    }
}
