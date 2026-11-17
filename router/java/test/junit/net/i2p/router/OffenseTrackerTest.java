package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * The bad-packet, corrupt-connection and port-hop offense trackers count
 * offenses against a bare source address, hold a bounded number of sources, and
 * drop counts that can no longer reach the threshold. A UDP source address
 * proves nothing and a socket address carries a port, so a tracker keyed on
 * "ip:port" costs one entry per port, never matches the blocklist it feeds, and
 * grows without limit under a spoofed-source flood.
 *
 * @since 0.9.72+
 */
public class OffenseTrackerTest {

    /** The context every case runs on. */
    private static RouterContext _context;
    /** The blocklist a ban writes to. */
    private static Blocklist _blocklist;
    /** The trackers, rebuilt per case so the ceiling case cannot poison the others. */
    private Banlist _banlist;
    /** The switches the trackers are gated on, and what they were set to before. */
    private static final String[] PROPS = {"router.banlist.enableBadPacketBan", "router.banlist.enableCorruptConnectionBan"};
    /** What those switches were set to before this class ran, null where unset. */
    private static String[] _saved;

    /**
     * Turns both offense bans on and builds the shared context, skipping the
     * class when no context can be created.
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
        _blocklist = _context.blocklist();
    }

    /** Puts the two offense bans back as the rest of the suite left them. */
    @AfterClass
    public static void tearDownClass() {
        for (int i = 0; i < PROPS.length; i++) {
            if (_saved[i] != null) {System.setProperty(PROPS[i], _saved[i]);}
            else {System.clearProperty(PROPS[i]);}
        }
    }

    /** A tracker with empty counters, so the ceiling case is not shared. */
    @Before
    public void setUp() {
        Assume.assumeTrue("No RouterContext available", _context != null);
        _banlist = new Banlist(_context);
    }

    /** Holds no state of its own beyond the tracker rebuilt for each case. */
    public OffenseTrackerTest() {}

    /**
     * A source address is tracked as the bare address, whatever port it arrived
     * on, and an address that does not parse is not tracked at all.
     */
    @Test
    public void offensesAreKeyedOnTheBareAddress() {
        assertEquals("203.0.113.7", Banlist.offenseIP("203.0.113.7:12345"));
        assertEquals("203.0.113.7", Banlist.offenseIP("203.0.113.7"));
        assertEquals("not an address", Banlist.offenseIP("not an address"));
    }

    /**
     * Three bad packets from three ports of one address reach the threshold
     * together and blocklist the address, which a per-port key never did.
     */
    @Test
    public void distinctPortsOfOneAddressReachTheBanTogether() {
        String base = "198.51.100.31";
        for (int port = 40000; port < 40000 + Banlist.MAX_OFFENSES_DEFAULT; port++) {
            _banlist.badPacket(base + ":" + port, null);
        }
        assertTrue("address " + base + " should be blocklisted after strikes from " +
                   Banlist.MAX_OFFENSES_DEFAULT + " ports", _blocklist.isBlocklisted(base));
    }

    /**
     * A count older than the offense window is dropped by the sweep, so the
     * strikes after it start over instead of tipping the source over the
     * threshold.
     */
    @Test
    public void staleOffensesArePruned() {
        String ip = "198.51.100.44";
        _banlist.badPacket(ip, null);
        _banlist.pruneOffenses(_context.clock().now() + Banlist.OFFENSE_WINDOW_DEFAULT + 1);
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT - 1; i++) {
            _banlist.badPacket(ip, null);
        }
        assertFalse("address " + ip + " should stay clear once its stale count is pruned",
                    _blocklist.isBlocklisted(ip));
    }

    /**
     * Past the ceiling the tracker stops taking on new sources, and a source it
     * is already counting still gets banned - the ceiling drops the flood, it
     * does not evict what is already being counted.
     */
    @Test
    public void trackerStopsTrackingNewSourcesPastTheCeiling() {
        String first = null;
        for (int i = 0; i < Banlist.MAX_OFFENSE_TRACKING; i++) {
            String ip = "10." + (i >>> 16) + "." + ((i >>> 8) & 0xff) + "." + (i & 0xff);
            _banlist.badPacket(ip, null);
            if (first == null) {first = ip;}
        }
        String newcomer = "198.51.100.200";
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT; i++) {
            _banlist.badPacket(newcomer, null);
        }
        assertFalse("address " + newcomer + " should not be tracked past the ceiling",
                    _blocklist.isBlocklisted(newcomer));
        for (int i = 0; i < Banlist.MAX_OFFENSES_DEFAULT - 1; i++) {
            _banlist.badPacket(first, null);
        }
        assertTrue("address " + first + " was already being tracked and should still be banned",
                   _blocklist.isBlocklisted(first));
    }

    /**
     * A corrupt connection from a port-bearing address is counted against the
     * bare address too, the form the blocklist accepts.
     */
    @Test
    public void corruptConnectionsAreCountedAgainstTheBareAddress() {
        String base = "198.51.100.55";
        for (int port = 41000; port < 41000 + Banlist.MAX_OFFENSES_DEFAULT; port++) {
            _banlist.corruptConnection(base + ":" + port, null);
        }
        assertTrue("address " + base + " should be blocklisted after " + Banlist.MAX_OFFENSES_DEFAULT +
                   " corrupt connections", _blocklist.isBlocklisted(base));
    }
}
