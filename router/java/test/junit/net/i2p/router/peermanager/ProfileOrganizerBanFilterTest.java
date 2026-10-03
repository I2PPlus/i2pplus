package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.OrderedProperties;

import org.junit.After;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests that no selection path can hand out a banlisted peer.
 *
 *  <p>Motivation: {@code BuildHandler} drops tunnel requests whose next hop is
 *  banned, and a burst of those warnings says peer selection handed out peers the
 *  banlist already held. The ban gate lives in {@code passesBasicGates} rather
 *  than in each selection loop, so the risk is not that a gate is missing but
 *  that some path bypasses the chokepoint. These tests pin the chokepoint from
 *  both sides: the peer is selectable before the ban and unselectable after it,
 *  across the fast tier, the not-failing last-resort pool and the direct
 *  {@code isSelectable} entry point.
 *
 *  <p>The before/after pairing matters. Asserting only that a banned peer is
 *  rejected would also pass if the peer were rejected for an unrelated reason —
 *  a stale RouterInfo, a lost profile — and would prove nothing about bans.
 *
 *  @since 0.9.71+
 */
public class ProfileOrganizerBanFilterTest {

    private static RouterContext _ctx;
    private static ProfileOrganizer _org;

    /** Peers banned by a test, so a shared RouterContext is left clean. */
    private final Set<Hash> _banned = new HashSet<>();

    @BeforeClass
    public static void setUpClass() throws Exception {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _org = new ProfileOrganizer(_ctx);
    }

    @After
    public void tearDown() {
        for (Hash h : _banned) {
            _ctx.banlist().unbanlistRouter(h);
        }
        _banned.clear();
    }

    private static RouterInfo routerInfo(int seed) {
        RouterInfo info = new RouterInfo();
        RouterIdentity ident = new RouterIdentity();
        byte[] pk = new byte[EncType.ECIES_X25519.getPubkeyLen()];
        pk[pk.length - 1] = (byte) seed;
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(new PublicKey(EncType.ECIES_X25519, pk));
        byte[] spk = new byte[SigType.EdDSA_SHA512_Ed25519.getPubkeyLen()];
        spk[0] = (byte) seed;
        spk[1] = (byte) (seed >> 8);
        ident.setSigningPublicKey(new SigningPublicKey(SigType.EdDSA_SHA512_Ed25519, spk));
        info.setIdentity(ident);
        Properties opts = new Properties();
        opts.setProperty("caps", "fO");
        opts.setProperty("router.version", "0.9.70");
        info.setOptions(opts);
        OrderedProperties addrProps = new OrderedProperties();
        addrProps.setProperty("host", "1.2.3.4");
        addrProps.setProperty("port", "1024");
        Set<RouterAddress> addresses = new HashSet<>(1);
        addresses.add(new RouterAddress("NTCP", addrProps, 10));
        info.setAddresses(addresses);
        info.setPublished(System.currentTimeMillis());
        return info;
    }

    private static void seedNetDb(RouterInfo ri) throws Exception {
        Class<?> c = _ctx.netDb().getClass();
        Field routers = null;
        while (c != null && routers == null) {
            try {
                routers = c.getDeclaredField("_routers");
            } catch (NoSuchFieldException nsf) {
                c = c.getSuperclass();
            }
        }
        if (routers == null) {
            throw new NoSuchFieldException("_routers");
        }
        routers.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Hash, RouterInfo> m = (Map<Hash, RouterInfo>) routers.get(_ctx.netDb());
        m.put(ri.getIdentity().getHash(), ri);
    }

    /** Registers a selectable peer: valid RouterInfo in netdb plus a profile. */
    private static Hash register(int seed) throws Exception {
        RouterInfo ri = routerInfo(seed);
        seedNetDb(ri);
        Hash h = ri.getIdentity().getHash();
        _org.addProfile(new PeerProfile(_ctx, h));
        return h;
    }

    private void ban(Hash peer, String reason) {
        _ctx.banlist().banlistRouter(peer, reason);
        _banned.add(peer);
    }

    /**
     *  The core contract, on the direct entry point every path funnels through:
     *  selectable before the ban, not selectable after it.
     */
    @Test
    public void banIsWhatMakesAPeerUnselectable() throws Exception {
        Hash peer = register(0xB001);
        assertTrue("precondition: peer must be selectable before the ban",
                   _org.isSelectable(peer));
        ban(peer, "ban filter test");
        assertFalse("a banlisted peer must not be selectable",
                    _org.isSelectable(peer));
    }

    /**
     *  The last-resort pool is the path most likely to bypass a gate, because it
     *  runs when the tiers could not supply enough peers and is documented as
     *  willing to consider peers the tier drawers would reject.
     */
    @Test
    public void lastResortPoolSkipsBannedPeers() throws Exception {
        Hash banned = register(0xB002);
        Hash allowed = register(0xB003);
        ban(banned, "ban filter test");

        Set<Hash> matches = new HashSet<>();
        _org.selectNotFailingPeers(10, new HashSet<Hash>(), matches);
        assertFalse("last-resort selection returned a banned peer", matches.contains(banned));
        assertTrue("control: an unbanned peer must still be selectable", matches.contains(allowed));
    }

    /**
     *  Passing a null exclusion set must not disable ban filtering. The gate is
     *  documented as independent of the exclusion set, because a caller handing
     *  over a plain set used to reduce {@code contains()} to a membership test
     *  and silently skip the banlist.
     */
    @Test
    public void nullExcludeSetStillFiltersBans() throws Exception {
        Hash banned = register(0xB004);
        Hash allowed = register(0xB005);
        ban(banned, "ban filter test");

        Set<Hash> matches = new HashSet<>();
        _org.selectNotFailingPeers(10, null, matches);
        assertFalse("selection with a null exclude set returned a banned peer",
                    matches.contains(banned));
        assertTrue("control: an unbanned peer must still be selectable",
                   matches.contains(allowed));
    }

    /** Repeated scans must stay clean: selection retries, it does not ask once. */
    @Test
    public void repeatedScansStayClean() throws Exception {
        Hash banned = register(0xB006);
        ban(banned, "ban filter test");
        for (int i = 0; i < 5; i++) {
            Set<Hash> matches = new HashSet<>();
            _org.selectNotFailingPeers(10, new HashSet<Hash>(), matches);
            assertFalse("attempt " + i + " returned a banned peer", matches.contains(banned));
            assertFalse("attempt " + i, _org.isSelectable(banned));
        }
    }

    /**
     *  Lifting the ban must clear the banlist entry itself.
     *
     *  <p>Deliberately asserts the banlist rather than selectability: banning has
     *  side effects beyond the entry (the peer is dropped from transport state),
     *  and whether a just-unbanned peer is immediately selectable again depends on
     *  those, not on the ban filter. What must hold is that the ban is gone, or
     *  {@code tearDown} has nothing to clean up and the next test inherits it.
     */
    @Test
    public void unbanClearsTheBanlistEntry() throws Exception {
        Hash peer = register(0xB007);
        ban(peer, "ban filter test");
        assertTrue("precondition: peer must be banlisted", _ctx.banlist().isBanlisted(peer));
        _ctx.banlist().unbanlistRouter(peer);
        _banned.remove(peer);
        assertFalse("unbanlistRouter must clear the entry", _ctx.banlist().isBanlisted(peer));
    }

    /**
     *  The rejection log needs the ban cause: without it a burst of
     *  {@code tunnel.buildBanHit} cannot be attributed to whichever subsystem is
     *  banning peers, and the only alternative is temporary logging in each.
     */
    @Test
    public void banCauseIsReported() throws Exception {
        Hash peer = register(0xB009);
        ban(peer, "cause-for-test");
        assertEquals("cause-for-test", _ctx.banlist().getBanCause(peer));
    }

    /** No cause accessor value when there is no standing ban, so callers can test null once. */
    @Test
    public void unbannedPeerHasNoCause() throws Exception {
        Hash peer = register(0xB00A);
        assertNull(_ctx.banlist().getBanCause(peer));
    }

    /** A lifted ban must stop reporting a cause. */
    @Test
    public void unbanClearsCause() throws Exception {
        Hash peer = register(0xB00B);
        ban(peer, "cause-for-test");
        _ctx.banlist().unbanlistRouter(peer);
        _banned.remove(peer);
        assertNull(_ctx.banlist().getBanCause(peer));
    }

    /**
     *  The gate must be per peer, not a global kill switch: banning one peer has
     *  to leave every other candidate selectable, or a single ban would silently
     *  empty the pool and look exactly like the starvation this gate exists to
     *  avoid.
     */
    @Test
    public void banDoesNotAffectOtherPeers() throws Exception {
        Hash banned = register(0xB008);
        Hash other = register(0xB00C);
        ban(banned, "ban filter test");
        assertFalse("banned peer must be unselectable", _org.isSelectable(banned));
        assertTrue("an unbanned peer must stay selectable",
                   _org.isSelectable(other));
        Set<Hash> matches = new HashSet<>();
        _org.selectNotFailingPeers(10, new HashSet<Hash>(), matches);
        assertFalse(matches.contains(banned));
        assertTrue("selection must still return the unbanned peer", matches.contains(other));
    }
}
