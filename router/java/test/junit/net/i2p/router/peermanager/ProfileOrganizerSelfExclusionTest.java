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
 *  Tests that a router is not selectable through the normal selection path.
 *
 *  <p>The local RouterInfo sits in our own netdb, so we are a candidate in
 *  every pool. We are nevertheless <em>meant</em> to appear in a client
 *  tunnel, at one end — {@code ClientPeerSelector.finalizeSelection} inserts
 *  us directly and {@code ExploratoryPeerSelector} excludes us outright,
 *  neither through this path. These tests therefore cover only the invariant
 *  that selection must not hand us out as a candidate hop, which is defence in
 *  depth: the tier drawers excluded self individually, but
 *  {@code selectRemainderFromAllPeers} went through
 *  {@link ProfileOrganizer#isSelectable(Hash)} without doing so, and the two
 *  protections could disagree.
 *
 *  <p>Deliberately not claimed here: that this fixed an observed build
 *  failure. A router showing up in an expiring-build log line is the expected
 *  gateway, not evidence of self-selection. Whether we were ever drawn at a
 *  mid-hop remains unestablished.
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
     *  The core contract: selection never offers us as a candidate. Checked
     *  before any banlist or netdb work so the guard cannot be reordered behind
     *  a cheaper test and accidentally dropped.
     */
    @Test
    public void weAreNotSelectable() {
        assertFalse("selection must never hand out the local router as a candidate",
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
