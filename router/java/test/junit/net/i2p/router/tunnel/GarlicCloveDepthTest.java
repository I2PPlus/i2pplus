package net.i2p.router.tunnel;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the ceiling on how deep a peer may nest garlic inside garlic.
 *
 * <p>A clove that decrypts to another garlic message is handed straight back to
 * the receiver, which parses it and calls back into the code that opened the
 * outer clove, so the peer that built the message chooses how deep that recursion
 * goes. Nothing counted the levels: the only bound was the size of what arrived
 * down the tunnel, and at roughly 110 bytes of clove overhead a 16 KB tunnel
 * message carries on the order of 140 levels of nesting. Real garlic carries one
 * or two levels, so the cap is far above what is sent and far below what the
 * stack has to absorb.
 *
 * @since 0.9.72+
 */
public class GarlicCloveDepthTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public GarlicCloveDepthTest() {}

    /** The context the distributor under test runs on. */
    private RouterContext _ctx;

    /** The distributor, with the recursion point recorded instead of entered. */
    private InboundMessageDistributor _imd;

    /** The levels whose nested garlic message the distributor chose to open. */
    private final List<Integer> _opened = new ArrayList<>(16);

    /**
     * A distributor over an isolated router context.
     */
    @Before
    public void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _opened.clear();
        _imd = new InboundMessageDistributor(_ctx, null) {
            @Override
            void openNestedGarlic(GarlicMessage nested, int depth) {
                _opened.add(depth);
            }
        };
    }

    /** Drop the context. */
    @After
    public void tearDown() {
        _ctx = null;
    }

    /**
     * A clove asking for local delivery, the mode a nested garlic message
     * arrives with.
     *
     * @return the instructions to hand the distributor
     */
    private static DeliveryInstructions local() {
        DeliveryInstructions di = new DeliveryInstructions();
        di.setDeliveryMode(DeliveryInstructions.DELIVERY_MODE_LOCAL);
        return di;
    }

    /**
     * A garlic message to sit inside a clove.
     *
     * @return the payload the distributor is asked to deliver
     */
    private GarlicMessage garlic() {
        return new GarlicMessage(_ctx);
    }

    /**
     * The cap is a fixed small number, not a fraction of the message: a peer can
     * afford about 140 levels inside one tunnel datagram, and the guard has to
     * stop long before that.
     */
    @Test
    public void testTheCapIsFarBelowWhatOneMessageCanCarry() {
        int limit = InboundMessageDistributor.MAX_CLOVE_DEPTH;
        assertTrue("the cap leaves no room for real garlic", limit >= 4);
        assertTrue("the cap is above what a 16 KB tunnel message can nest", limit < 140);
        for (int depth = 0; depth < limit; depth++) {
            assertTrue("depth " + depth + " was refused",
                       InboundMessageDistributor.mayOpenNestedGarlic(depth));
        }
        assertFalse("the cap itself was still allowed",
                    InboundMessageDistributor.mayOpenNestedGarlic(limit));
        assertFalse("a level past the cap was still allowed",
                    InboundMessageDistributor.mayOpenNestedGarlic(limit + 130));
    }

    /**
     * Every level up to the cap is opened, and one level deeper each time, so a
     * real two-level message is delivered exactly as it was before the guard.
     */
    @Test
    public void testClovesUpToTheCapAreOpenedOneLevelDeeper() {
        int limit = InboundMessageDistributor.MAX_CLOVE_DEPTH;
        for (int depth = 0; depth < limit; depth++) {
            _opened.clear();
            _imd.handleClove(local(), garlic(), depth);
            assertEquals("the clove at depth " + depth + " was not opened once", 1, _opened.size());
            assertEquals("the clove at depth " + depth + " was opened at the wrong level",
                         Integer.valueOf(depth + 1), _opened.get(0));
        }
    }

    /**
     * At the cap the clove is dropped instead of opened: the recursion stops
     * there rather than continuing to whatever depth the peer built.
     */
    @Test
    public void testACloveAtTheCapIsDroppedNotOpened() {
        _imd.handleClove(local(), garlic(), InboundMessageDistributor.MAX_CLOVE_DEPTH);
        assertTrue("a clove at the cap still recursed", _opened.isEmpty());
    }

    /**
     * The two-argument entry point, used by callers that never learned a depth,
     * counts the message that arrived down the tunnel as level zero.
     */
    @Test
    public void testTheOutermostMessageCountsAsLevelZero() {
        _imd.handleClove(local(), garlic());
        assertEquals(1, _opened.size());
        assertEquals(Integer.valueOf(1), _opened.get(0));
    }
}
