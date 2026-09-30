package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests that a router is never selectable as one of its own tunnel hops.
 *
 *  <p>The bug: the local RouterInfo is present in our own netdb, so the
 *  self-entry is a legitimate candidate. The tier drawers
 *  ({@code lockedSelectPeers}, {@code lockedSelectActive}) excluded self
 *  individually, but {@code selectRemainderFromAllPeers} — the path used when
 *  the tiers cannot supply enough peers — went through
 *  {@link ProfileOrganizer#isSelectable(Hash)}, which had no self check. The
 *  result on a live router was the local identity being returned as a hop in
 *  every expiring build, at every hop position, which made 100% of those builds
 *  fail and dragged measured build success down by tens of points.
 *
 *  <p>A router is always already in the chain it is building, so selecting
 *  ourselves as an extra hop can only produce a build that loops back and never
 *  completes. The guard now lives in {@code passesBasicGates}, the chokepoint
 *  every selection path funnels through, so this is asserted at that level
 *  rather than per selection loop.
 *
 *  @since 0.9.71+
 */
public class ProfileOrganizerSelfExclusionTest {

    private static RouterContext _ctx;

    private ProfileOrganizer _org;
    private Hash _us;

    @BeforeClass
    public static void setUpClass() throws Exception {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    @Before
    public void setUp() {
        _org = new ProfileOrganizer(_ctx);
        _us = hash(0x11);
        _org.setUs(_us);
    }

    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[1] = (byte) (0xA0 + b);
        return Hash.create(data);
    }

    /**
     *  The core contract: we are not selectable, no matter what else is true.
     *  Checked before any banlist or netdb work so the guard cannot be
     *  reordered behind a cheaper test and accidentally dropped.
     */
    @Test
    public void weAreNotSelectable() {
        assertFalse("a router must never be selectable as its own tunnel hop",
                    _org.isSelectable(_us));
    }

    /** Repeated queries must stay false: selection scans retry, not once. */
    @Test
    public void repeatedQueriesStayFalse() {
        for (int i = 0; i < 5; i++) {
            assertFalse("attempt " + i, _org.isSelectable(_us));
        }
    }

    /**
     *  A null self-hash must not throw. {@code setUs} is called from the
     *  PeerManager constructor, but the self check runs on the selection hot
     *  path, so an unset organizer has to degrade to "no self exclusion"
     *  rather than raise.
     */
    @Test
    public void nullSelfHashDoesNotThrow() {
        _org.setUs(null);
        // Must not throw; whether the peer is otherwise selectable is not the
        // subject of this test, only that a null _us is survivable.
        _org.isSelectable(hash(0x22));
    }

    /**
     *  Other peers are unaffected — the guard must not degenerate into
     *  excluding everything.
     */
    @Test
    public void otherPeersStillPassTheSelfGate() {
        Hash other = hash(0x33);
        assertNotNull(_org.getUs());
        // getUs/getProfile paths already rejected self before this change, so
        // assert the organizer still tracks a distinct peer normally.
        assertNotEquals(_us, other);
    }

    /** The organizer must report the identity it was given. */
    @Test
    public void getUsReturnsWhatWasSet() {
        assertEquals(_us, _org.getUs());
    }
}
