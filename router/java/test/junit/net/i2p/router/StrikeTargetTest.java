package net.i2p.router;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.OrderedProperties;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A bad-packet or port-hop strike from an established peer is charged to the
 * address the packet was received from. The hosts a peer publishes in its own
 * RouterInfo are the peer's to write, and the first of them is not where the
 * packets came from, so a strike counted against a published address lets the
 * peer get a stranger's address put on the no-expiry blocklist while its own
 * address stays clean.
 *
 * @since 0.9.72+
 */
public class StrikeTargetTest {

    /** The context every case runs on, with the two strike bans turned on. */
    private static RouterContext _context;
    /** The banlist under test, built after the strike bans were enabled. */
    private static Banlist _banlist;
    /** The blocklist a third strike writes to. */
    private static Blocklist _blocklist;
    /** Counts up so each case uses its own pair of addresses. */
    private static int _case;
    /** The two switches the strike counters are gated on. */
    private static final String[] PROPS = {"router.banlist.enableBadPacketBan", "router.banlist.enablePortHoppingBan"};
    /** What those switches were set to before this class ran, null where unset. */
    private static String[] _saved;

    /**
     * Turns both strike bans on, builds the shared context and a banlist that
     * reads those switches, and skips the class when no context can be created.
     */
    @BeforeClass
    public static void setUpClass() {
        _saved = new String[PROPS.length];
        for (int i = 0; i < PROPS.length; i++) {
            _saved[i] = System.getProperty(PROPS[i]);
            System.setProperty(PROPS[i], "true");
        }
        _context = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
        _banlist = new Banlist(_context);
        _blocklist = _context.blocklist();
    }

    /** Puts the two strike bans back as the rest of the suite left them. */
    @AfterClass
    public static void tearDownClass() {
        for (int i = 0; i < PROPS.length; i++) {
            if (_saved[i] != null) {System.setProperty(PROPS[i], _saved[i]);}
            else {System.clearProperty(PROPS[i]);}
        }
    }

    /** Holds no state of its own; the state is the shared context. */
    public StrikeTargetTest() {}

    /**
     * A fresh peer hash for the case being built, and the case counter every
     * address helper below reads. Never the all-zeros or FAKE hash the banlist
     * bans forever at construction.
     *
     * @return a hash not used by any other case
     */
    private static Hash peerHash() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) (0x40 + _case++);
        return new Hash(data);
    }

    /**
     * The address the misbehaving packets are actually seen on, unique per case.
     *
     * @return a TEST-NET-2 address
     */
    private static String observedIP() {
        return "198.51.100." + (10 + _case);
    }

    /**
     * The address the peer publishes first in its RouterInfo, belonging to
     * someone else, unique per case.
     *
     * @return a TEST-NET-1 address
     */
    private static String publishedIP() {
        return "192.0.2." + (20 + _case);
    }

    /**
     * Puts a RouterInfo for the peer in the local netdb whose first published
     * address names {@link #publishedIP()} - the shape the attack takes, an
     * address of someone else listed ahead of the peer's own.
     *
     * @param hash the peer to store the RouterInfo under
     */
    private static void storePublishedAddress(Hash hash) {
        RouterInfo ri = new RouterInfo();
        OrderedProperties opts = new OrderedProperties();
        opts.setProperty(RouterAddress.PROP_HOST, publishedIP());
        opts.setProperty(RouterAddress.PROP_PORT, "8834");
        OrderedProperties own = new OrderedProperties();
        own.setProperty(RouterAddress.PROP_HOST, observedIP());
        own.setProperty(RouterAddress.PROP_PORT, "12345");
        ri.setAddresses(Arrays.asList(new RouterAddress("NTCP2", opts, 5),
                                      new RouterAddress("SSU2", own, 7)));
        _context.netDb().store(hash, ri);
    }

    /**
     * Three bad-packet strikes block the address they were received from, and
     * leave the address the peer published alone.
     */
    @Test
    public void badPacketStrikesBlockTheObservedAddress() {
        Hash peer = peerHash();
        String observed = observedIP();
        String published = publishedIP();
        storePublishedAddress(peer);
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT; i++) {
            _banlist.badPacket(peer, null, observed);
        }
        assertTrue("observed address " + observed + " should be blocklisted after " +
                   Banlist.MAX_OFFENSES_DEFAULT + " strikes", _blocklist.isBlocklisted(observed));
        assertFalse("published address " + published + " should not be blocklisted",
                    _blocklist.isBlocklisted(published));
    }

    /**
     * Three port-hop strikes block the address the path response was received
     * from, and leave the address the peer published alone.
     */
    @Test
    public void portHopStrikesBlockTheObservedAddress() {
        Hash peer = peerHash();
        String observed = observedIP();
        String published = publishedIP();
        storePublishedAddress(peer);
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT; i++) {
            _banlist.portHopping(peer, observed);
        }
        assertTrue("observed address " + observed + " should be blocklisted after " +
                   Banlist.MAX_OFFENSES_DEFAULT + " strikes", _blocklist.isBlocklisted(observed));
        assertFalse("published address " + published + " should not be blocklisted",
                    _blocklist.isBlocklisted(published));
    }

    /**
     * A peer whose RouterInfo is not in the local netdb still has the address it
     * was seen on blocked - the strike does not depend on the netdb at all.
     */
    @Test
    public void strikesBlockTheObservedAddressWithoutARouterInfo() {
        Hash peer = peerHash();
        String observed = observedIP();
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT; i++) {
            _banlist.badPacket(peer, null, observed);
        }
        assertTrue("observed address " + observed + " should be blocklisted", _blocklist.isBlocklisted(observed));
    }

    /**
     * One strike short of the threshold blocks nothing, so the counter, and not
     * the address choice, is what decides the ban.
     */
    @Test
    public void strikesBelowTheThresholdBlockNothing() {
        Hash peer = peerHash();
        String observed = observedIP();
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT - 1; i++) {
            _banlist.badPacket(peer, null, observed);
        }
        assertFalse("observed address " + observed + " should stay clear below the threshold",
                    _blocklist.isBlocklisted(observed));
    }
}
