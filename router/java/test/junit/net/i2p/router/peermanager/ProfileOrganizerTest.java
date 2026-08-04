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

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for ProfileOrganizer profile registration and peer selection.
 *  Peer profiles are seeded through the dummy netdb so that the
 *  profiling-exclusion check passes, then registered via addProfile()
 *  and selected through the not-failing selection path.
 *
 *  @since 0.9.10
 */
public class ProfileOrganizerTest {

    private static RouterContext _ctx;
    private static ProfileOrganizer _org;

    @BeforeClass
    public static void setUp() throws Exception {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        _org = new ProfileOrganizer(_ctx);
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
        // floodfill + 256k tier capability, modern version
        Properties opts = new Properties();
        opts.setProperty("caps", "fO");
        opts.setProperty("router.version", "0.9.70");
        info.setOptions(opts);
        // a reachable NTCP address so the peer passes the usability filter
        OrderedProperties addrProps = new OrderedProperties();
        addrProps.setProperty("host", "1.2.3.4");
        addrProps.setProperty("port", "1024");
        Set<RouterAddress> addresses = new HashSet<>(1);
        addresses.add(new RouterAddress("NTCP", addrProps, 10));
        info.setAddresses(addresses);
        return info;
    }

    /** register a router info in the dummy netdb so profiling-exclusion passes */
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

    private static Hash register(int seed) throws Exception {
        RouterInfo ri = routerInfo(seed);
        seedNetDb(ri);
        PeerProfile profile = new PeerProfile(_ctx, ri.getIdentity().getHash());
        _org.addProfile(profile);
        return ri.getIdentity().getHash();
    }

    @Test
    public void testAddProfileIncrementsCount() throws Exception {
        int before = _org.countNotFailingPeers();
        register(1000 + before);
        assertEquals(before + 1, _org.countNotFailingPeers());
    }

    @Test
    public void testSelectNotFailingPeers() throws Exception {
        Set<Hash> added = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            added.add(register(2000 + i));
        }
        System.out.println("DEBUG count=" + _org.countNotFailingPeers() + " added=" + added.size()
                           + " list=" + _org.countNotFailingPeers());
        Set<Hash> matches = new HashSet<>();
        Set<Hash> exclude = new HashSet<>();
        _org.selectNotFailingPeers(3, exclude, matches);
        assertEquals(3, matches.size());
        for (Hash peer : matches) {
            assertNotNull(_org.getProfile(peer));
        }
    }

    @Test
    public void testSelectExcludesGivenPeers() throws Exception {
        Hash excluded = register(3000);
        for (int i = 0; i < 4; i++) {
            register(3001 + i);
        }
        Set<Hash> exclude = new HashSet<>();
        exclude.add(excluded);
        Set<Hash> matches = new HashSet<>();
        _org.selectNotFailingPeers(3, exclude, matches);
        assertEquals(3, matches.size());
        assertFalse(matches.contains(excluded));
    }

    @Test
    public void testGetProfileReturnsRegistered() throws Exception {
        Hash peer = register(4000);
        PeerProfile profile = _org.getProfile(peer);
        assertNotNull(profile);
        assertEquals(peer, profile.getPeer());
    }

    // ---- static threshold clamps ----

    @Test
    public void testSetDefaultMinFastPeersClamps() {
        ProfileOrganizer.setDefaultMinFastPeers(5000);
        assertEquals(2000, ProfileOrganizer.getDefaultMinFastPeers());
        ProfileOrganizer.setDefaultMinFastPeers(10);
        assertEquals(50, ProfileOrganizer.getDefaultMinFastPeers());
        ProfileOrganizer.setDefaultMinFastPeers(400);
        assertEquals(400, ProfileOrganizer.getDefaultMinFastPeers());
    }

    @Test
    public void testSetDefaultMaxFastPeersClamps() {
        ProfileOrganizer.setDefaultMaxFastPeers(50000);
        assertEquals(3000, ProfileOrganizer.getDefaultMaxFastPeers());
        ProfileOrganizer.setDefaultMaxFastPeers(50);
        assertEquals(200, ProfileOrganizer.getDefaultMaxFastPeers());
        ProfileOrganizer.setDefaultMaxFastPeers(500);
        assertEquals(500, ProfileOrganizer.getDefaultMaxFastPeers());
    }

    @Test
    public void testSetDefaultMaxProfilesClamps() {
        int min = ProfileOrganizer.MIN_MAX_PROFILES;
        int max = ProfileOrganizer.ABSOLUTE_MAX_PROFILES;
        ProfileOrganizer.setDefaultMaxProfiles(1);
        assertEquals(min, ProfileOrganizer.getDefaultMaxProfilesValue());
        ProfileOrganizer.setDefaultMaxProfiles(max + 1000);
        assertEquals(max, ProfileOrganizer.getDefaultMaxProfilesValue());
        ProfileOrganizer.setDefaultMaxProfiles((min + max) / 2);
        assertEquals((min + max) / 2, ProfileOrganizer.getDefaultMaxProfilesValue());
    }
}
