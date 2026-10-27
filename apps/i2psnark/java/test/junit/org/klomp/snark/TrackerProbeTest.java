package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Tests for what we remember about the BEP 15 (udp) support of the trackers we
 * announce to over http: reading and writing a stored verdict, its expiry, the
 * guardrails on which hosts get probed, and the choice of announce URL a
 * verdict leads to.
 *
 * @since 0.9.72+
 */
public class TrackerProbeTest {

    /**
     * JUnit builds one instance per test method, on which {@code setUp} hands
     * a fresh SnarkManager over.
     */
    public TrackerProbeTest() {}

    /** A time in the middle of the range, so the arithmetic below stays readable. */
    private static final long NOW = 1_700_000_000_000L;

    private SnarkManager mgr;
    private I2PAppContext ctx;
    private File baseDir;

    /**
     * A manager in a scratch directory, with the config it builds, and no
     * I2CP session, so probing can never reach the network.
     */
    @Before
    public void setUp() {
        // I2PAppContext defaults i2p.dir.base to the working directory, so point
        // the whole tree at a scratch dir we own, as the other SnarkManager
        // tests do, and let nothing be written outside it.
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-snark-probe-" + System.nanoTime());
        assertTrue(baseDir.mkdirs());
        Properties props = new Properties();
        props.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
        ctx = new I2PAppContext(props);
        mgr = new SnarkManager(ctx, "/test-i2psnark-probe", "test-i2psnark-probe");
    }

    /**
     * Stop the manager and remove the scratch directory.
     */
    @After
    public void tearDown() {
        if (mgr != null) {
            try { mgr.stop(); } catch (Exception ignore) {}
        }
        flag(TrackerProbe.PROP_PREFER_UDP, null);
        flag(TrackerProbe.PROP_PROBE_UDP, null);
        if (baseDir != null) {
            try { net.i2p.util.FileUtil.rmdir(baseDir, false); } catch (Exception ignore) {}
        }
    }

    /**
     * Set, or with a null value clear, one config flag. A context falls back to
     * the system properties for anything it was not constructed with, which is
     * how a test sets a flag without rebuilding the manager.
     *
     * @param key the config key
     * @param value the value, null to clear it
     */
    private void flag(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    // ---- the stored form ----

    /**
     * A fresh "ok" is a verdict that the host answers udp.
     */
    @Test
    public void testParseFreshOk() {
        assertEquals(TrackerProbe.Support.UDP,
                     TrackerProbe.parse("ok," + NOW, NOW, TrackerProbe.OK_TTL, TrackerProbe.FAIL_TTL));
    }

    /**
     * A fresh "broken" is a verdict that it does not, and the shorter lifetime
     * of a "no" is pinned here: a tracker that starts answering udp is not
     * stuck behind a month of the old answer.
     */
    @Test
    public void testParseFreshBroken() {
        long ttl = TrackerProbe.FAIL_TTL;
        assertEquals(TrackerProbe.Support.NO_UDP,
                     TrackerProbe.parse("broken," + NOW, NOW + ttl - 1, TrackerProbe.OK_TTL, ttl));
        assertEquals(TrackerProbe.Support.UNKNOWN,
                     TrackerProbe.parse("broken," + NOW, NOW + ttl + 1, TrackerProbe.OK_TTL, ttl));
    }

    /**
     * A "yes" outlives a "no", and both expire on their own.
     */
    @Test
    public void testParseExpiry() {
        assertEquals(TrackerProbe.Support.UDP,
                     TrackerProbe.parse("ok," + NOW, NOW + TrackerProbe.OK_TTL,
                                        TrackerProbe.OK_TTL, TrackerProbe.FAIL_TTL));
        assertEquals(TrackerProbe.Support.UNKNOWN,
                     TrackerProbe.parse("ok," + NOW, NOW + TrackerProbe.OK_TTL + 1,
                                        TrackerProbe.OK_TTL, TrackerProbe.FAIL_TTL));
    }

    /**
     * Anything unreadable is no verdict at all, so a hand-edited config cannot
     * make us announce over udp to a tracker that never said it answers there.
     */
    @Test
    public void testParseMalformed() {
        long ok = TrackerProbe.OK_TTL;
        long fail = TrackerProbe.FAIL_TTL;
        String[] bad = {null, "", "ok", ",", ",12345", "ok,", "ok,notanumber", "ok, ",
                        "yes," + NOW, "OK," + NOW, "broken " + NOW, "ok," + NOW + ",extra junk"};
        for (String s : bad) {
            assertEquals(s, TrackerProbe.Support.UNKNOWN, TrackerProbe.parse(s, NOW, ok, fail));
        }
    }

    /**
     * A verdict written out reads back as the same verdict, and a verdict
     * written in the future is not trusted.
     */
    @Test
    public void testFormatRoundTrip() {
        long ok = TrackerProbe.OK_TTL;
        long fail = TrackerProbe.FAIL_TTL;
        String yes = TrackerProbe.format(TrackerProbe.Support.UDP, NOW);
        String no = TrackerProbe.format(TrackerProbe.Support.NO_UDP, NOW);
        assertEquals("ok," + NOW, yes);
        assertEquals("broken," + NOW, no);
        assertEquals(TrackerProbe.Support.UDP, TrackerProbe.parse(yes, NOW, ok, fail));
        assertEquals(TrackerProbe.Support.NO_UDP, TrackerProbe.parse(no, NOW, ok, fail));
        // a timestamp in the future is a clock skew, not a verdict
        assertEquals(TrackerProbe.Support.UNKNOWN,
                     TrackerProbe.parse(yes, NOW - 1000, ok, fail));
    }

    /**
     * The instant a stored verdict says it was learned, for the expiry and the
     * trim; the current time for anything unreadable, so a malformed entry is
     * dropped first rather than kept forever.
     */
    @Test
    public void testLearned() {
        long ok = TrackerProbe.OK_TTL;
        long fail = TrackerProbe.FAIL_TTL;
        assertEquals(NOW - 5000, TrackerProbe.learned("ok," + (NOW - 5000), NOW, ok, fail));
        assertEquals(NOW, TrackerProbe.learned("garbage", NOW, ok, fail));
        assertEquals(NOW, TrackerProbe.learned("ok,zzz", NOW, ok, fail));
        // expired: parse() says UNKNOWN, so learned() reports now
        assertEquals(NOW, TrackerProbe.learned("ok," + (NOW - ok - 1), NOW, ok, fail));
    }

    // ---- the guardrails on what gets probed ----

    /**
     * Only an I2P name is probeable. A probe of a clearnet host would be an
     * outbound announce-shaped packet off the box, which is not what the config
     * asked for.
     */
    @Test
    public void testIsProbeable() {
        assertTrue(TrackerProbe.isProbeable("tracker2.postman.i2p"));
        assertTrue(TrackerProbe.isProbeable("opentracker.dg2.i2p"));
        assertTrue(TrackerProbe.isProbeable("proxy.i2p"));
        assertTrue(TrackerProbe.isProbeable("zzzot.i2p"));
        // a b32 address, and a full base-64 destination, are both names
        assertTrue(TrackerProbe.isProbeable("nfrjvknwcw47itotkzmk6mdlxmxfxsxhbhlr5ozhlsuavcogv4hq.b32.i2p"));
        assertTrue(TrackerProbe.isProbeable(longString(516, 'A') + ".i2p"));
        assertFalse(TrackerProbe.isProbeable(null));
        assertFalse(TrackerProbe.isProbeable(""));
        assertFalse(TrackerProbe.isProbeable(".i2p"));
        assertFalse(TrackerProbe.isProbeable("tracker.example.com"));
        assertFalse(TrackerProbe.isProbeable("tracker.i2p.example.com"));
        assertFalse(TrackerProbe.isProbeable("localhost"));
        // not a host at all
        assertFalse(TrackerProbe.isProbeable("host.i2p:6969"));
        assertFalse(TrackerProbe.isProbeable("user@host.i2p"));
        assertFalse(TrackerProbe.isProbeable("host name.i2p"));
        assertFalse(TrackerProbe.isProbeable("[::1].i2p"));
        assertFalse(TrackerProbe.isProbeable(longString(517, 'A') + ".i2p"));
    }

    /**
     * The host of an announce URL, for grouping the two URLs a dual-transport
     * tracker is listed as, and for keying a verdict.
     */
    @Test
    public void testHostOf() {
        assertEquals("tracker2.postman.i2p", TrackerProbe.hostOf("http://tracker2.postman.i2p/announce.php"));
        assertEquals("tracker2.postman.i2p", TrackerProbe.hostOf("https://tracker2.postman.i2p/announce"));
        assertEquals("opentracker.dg2.i2p", TrackerProbe.hostOf("udp://opentracker.dg2.i2p:6969/announce"));
        assertEquals("opentracker.dg2.i2p", TrackerProbe.hostOf("udp://opentracker.dg2.i2p"));
        assertEquals("opentracker.dg2.i2p", TrackerProbe.hostOf("udp://OPENTRACKER.dg2.i2p:6969/"));
        assertEquals("tracker2.postman.i2p", TrackerProbe.hostOf("tracker2.postman.i2p/announce"));
        assertNull(TrackerProbe.hostOf(null));
        assertNull(TrackerProbe.hostOf(""));
        assertNull(TrackerProbe.hostOf("udp://"));
        assertNull(TrackerProbe.hostOf("/announce.php"));
    }

    // ---- the URL a verdict leads to ----

    /**
     * With nothing known about the primary's host, the declared URL is used, so
     * the shipped behaviour is what a user gets before any probe has run.
     */
    @Test
    public void testPreferUDPNothingKnown() {
        String primary = "http://tracker2.postman.i2p/announce.php";
        List<String> candidates = Arrays.asList("http://tracker2.postman.i2p/scrape");
        assertEquals(primary, TrackerProbe.preferUDP(primary, candidates, h -> false));
        assertEquals(primary, TrackerProbe.preferUDP(primary, Collections.<String>emptyList(), h -> false));
    }

    /**
     * A primary that is already udp is left alone, port and path and all.
     */
    @Test
    public void testPreferUDPAlreadyUDP() {
        String primary = "udp://opentracker.dg2.i2p:8080/announce";
        assertEquals(primary, TrackerProbe.preferUDP(primary, Collections.<String>emptyList(), h -> true));
    }

    /**
     * When the torrent lists the primary's host over udp, that URL is used,
     * with the port and path the torrent gives, because the torrent asserts it.
     */
    @Test
    public void testPreferUDPFromTorrent() {
        String primary = "http://tracker2.postman.i2p/announce.php";
        List<String> candidates = Arrays.asList("http://unrelated.i2p/announce",
                                                "udp://tracker2.postman.i2p:8080/announce");
        assertEquals("udp://tracker2.postman.i2p:8080/announce",
                     TrackerProbe.preferUDP(primary, candidates, h -> true));
        // the host is matched on the host, not the string
        List<String> upper = Arrays.asList("udp://Tracker2.Postman.i2p:6969/announce");
        assertEquals("udp://Tracker2.Postman.i2p:6969/announce",
                     TrackerProbe.preferUDP(primary, upper, h -> true));
    }

    /**
     * When a probe says the host answers BEP 15 at the conventional port, and
     * the torrent never says so, the conventional URL is built.
     */
    @Test
    public void testPreferUDPFromProbe() {
        String primary = "http://tracker2.postman.i2p/announce.php";
        assertEquals("udp://tracker2.postman.i2p:" + TrackerClient.DEFAULT_UDP_TRACKER_PORT + '/',
                     TrackerProbe.preferUDP(primary, Collections.<String>emptyList(), h -> true));
        // a host we may not announce to over udp keeps its http URL
        assertEquals(primary,
                     TrackerProbe.preferUDP(primary, Collections.<String>emptyList(),
                                            h -> !"tracker2.postman.i2p".equals(h)));
    }

    /**
     * A null primary stays null, and a primary with no host in it is left alone.
     */
    @Test
    public void testPreferUDPNoHost() {
        assertNull(TrackerProbe.preferUDP(null, Collections.<String>emptyList(), h -> true));
        String primary = "http:///announce.php";
        assertEquals(primary, TrackerProbe.preferUDP(primary, Collections.<String>emptyList(), h -> true));
    }

    /**
     * The hosts a set of announce URLs names for one transport, for knowing
     * which hosts the torrent itself lists over udp.
     */
    @Test
    public void testHosts() {
        List<String> urls = Arrays.asList("http://a.i2p/announce", "udp://b.i2p:6969/announce",
                                          "udp://C.i2p/", null, "not a url");
        Set<String> udp = TrackerProbe.hosts(urls, true);
        assertEquals(new LinkedHashSet<>(Arrays.asList("b.i2p", "c.i2p")), udp);
        // the http side is everything that is not a udp url, junk included,
        // since a url we cannot read is certainly not one we announce over udp
        assertEquals(new LinkedHashSet<>(Arrays.asList("a.i2p", "not a url")),
                     TrackerProbe.hosts(urls, false));
        assertTrue(TrackerProbe.hosts(null, true).isEmpty());
        assertTrue(TrackerProbe.hosts(Collections.<String>emptyList(), false).isEmpty());
    }

    /**
     * The oldest verdicts are the ones dropped, and a request for more than
     * there are returns what there is.
     */
    @Test
    public void testTrim() {
        Map<String, Long> probedOn = new HashMap<>();
        probedOn.put("oldest.i2p", Long.valueOf(NOW - 3000));
        probedOn.put("middle.i2p", Long.valueOf(NOW - 2000));
        probedOn.put("newest.i2p", Long.valueOf(NOW - 1000));
        List<String> one = new ArrayList<>(TrackerProbe.trim(probedOn, 1));
        assertEquals(Collections.singletonList("oldest.i2p"), one);
        List<String> two = new ArrayList<>(TrackerProbe.trim(probedOn, 2));
        assertEquals(Arrays.asList("oldest.i2p", "middle.i2p"), two);
        assertEquals(3, TrackerProbe.trim(probedOn, 99).size());
        assertTrue(TrackerProbe.trim(probedOn, 0).isEmpty());
        assertTrue(TrackerProbe.trim(probedOn, -1).isEmpty());
        assertTrue(TrackerProbe.trim(new HashMap<String, Long>(), 3).isEmpty());
    }

    // ---- the cache, the config flags, and the config file ----

    /**
     * Both switches are off unless the config says otherwise, so the shipped
     * behaviour is to announce exactly to what the torrent names.
     */
    @Test
    public void testFlagsDefaultOff() {
        TrackerProbe probe = mgr.getTrackerProbe();
        assertFalse(probe.preferEnabled());
        assertFalse(probe.probeEnabled());
    }

    /**
     * Probing implies the preference, since a verdict is only worth learning
     * in order to use it.
     */
    @Test
    public void testFlagsFromConfig() {
        flag(TrackerProbe.PROP_PROBE_UDP, "true");
        TrackerProbe probe = mgr.getTrackerProbe();
        assertFalse(probe.probeEnabled());
        flag(TrackerProbe.PROP_PREFER_UDP, "true");
        assertTrue(probe.preferEnabled());
        assertTrue(probe.probeEnabled());
        // probing off, preference on: use what is known, ask nothing
        flag(TrackerProbe.PROP_PROBE_UDP, "false");
        assertTrue(probe.preferEnabled());
        assertFalse(probe.probeEnabled());
    }

    /**
     * A verdict is remembered for the host, in the global config, keyed by
     * host and not by torrent.
     */
    @Test
    public void testRecordAndSupport() {
        TrackerProbe probe = mgr.getTrackerProbe();
        probe.record("Tracker2.Postman.i2p", TrackerProbe.Support.UDP);
        assertEquals(TrackerProbe.Support.UDP, probe.support("tracker2.postman.i2p"));
        assertEquals(TrackerProbe.Support.UDP, probe.support("TRACKER2.POSTMAN.I2P"));
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("opentracker.dg2.i2p"));
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support(null));
        String stored = storedVerdict("tracker2.postman.i2p");
        assertTrue(stored, stored.startsWith("ok,"));
    }

    /**
     * A "no" is remembered too, and is what makes us keep announcing over http.
     */
    @Test
    public void testRecordBroken() {
        TrackerProbe probe = mgr.getTrackerProbe();
        probe.record("opentracker.dg2.i2p", TrackerProbe.Support.NO_UDP);
        assertEquals(TrackerProbe.Support.NO_UDP, probe.support("opentracker.dg2.i2p"));
        String stored = storedVerdict("opentracker.dg2.i2p");
        assertTrue(stored, stored.startsWith("broken,"));
    }

    /**
     * A verdict with nothing to say is not remembered, so a probe that could
     * not run is not written down as a fact about a tracker.
     */
    @Test
    public void testRecordUnknownIsNothing() {
        TrackerProbe probe = mgr.getTrackerProbe();
        probe.record("opentracker.dg2.i2p", TrackerProbe.Support.UNKNOWN);
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("opentracker.dg2.i2p"));
        assertNull(storedVerdictOrNull("opentracker.dg2.i2p"));
    }

    /**
     * A verdict expires on its own: a tracker that stops answering udp, moves
     * port, or gets filtered is asked again. The stored timestamp is what
     * carries the age, so this is read from a config, the way it is after a
     * restart.
     */
    @Test
    public void testSupportExpiry() {
        TrackerProbe probe = mgr.getTrackerProbe();
        long now = ctx.clock().now();
        Properties config = new Properties();
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "stale.i2p",
                           "ok," + (now - TrackerProbe.OK_TTL - 1000));
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "fresh.i2p", "ok," + now);
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "quiet.i2p",
                           "broken," + (now - TrackerProbe.FAIL_TTL - 1000));
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "loud.i2p", "broken," + now);
        probe.reload(config);
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("stale.i2p"));
        assertEquals(TrackerProbe.Support.UDP, probe.support("fresh.i2p"));
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("quiet.i2p"));
        assertEquals(TrackerProbe.Support.NO_UDP, probe.support("loud.i2p"));
    }

    /**
     * Nothing is probed while the config has not asked for it.
     */
    @Test
    public void testProbeOffAsksNothing() {
        TrackerProbe probe = mgr.getTrackerProbe();
        probe.probe("opentracker.dg2.i2p", TrackerClient.DEFAULT_UDP_TRACKER_PORT);
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("opentracker.dg2.i2p"));
        assertNull(storedVerdictOrNull("opentracker.dg2.i2p"));
    }

    /**
     * With probing asked for, and no session to ask over, nothing is written
     * down: the absence of an answer we never sent is not a verdict.
     *
     * @throws InterruptedException if the bounded wait for the probe thread is
     *                              interrupted
     */
    @Test
    public void testProbeWithoutSessionRecordsNothing() throws Exception {
        flag(TrackerProbe.PROP_PREFER_UDP, "true");
        flag(TrackerProbe.PROP_PROBE_UDP, "true");
        TrackerProbe probe = mgr.getTrackerProbe();
        assertFalse(util().connected());
        probe.probe("opentracker.dg2.i2p", TrackerClient.DEFAULT_UDP_TRACKER_PORT);
        // the probe runs on its own thread, so give it a moment, bounded
        for (int i = 0; i < 20 && storedVerdictOrNull("opentracker.dg2.i2p") != null; i++) {
            Thread.sleep(50);
        }
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("opentracker.dg2.i2p"));
        assertNull(storedVerdictOrNull("opentracker.dg2.i2p"));
    }

    /**
     * A clearnet host is not probed even with probing asked for.
     */
    @Test
    public void testProbeClearnetRejected() {
        flag(TrackerProbe.PROP_PREFER_UDP, "true");
        flag(TrackerProbe.PROP_PROBE_UDP, "true");
        TrackerProbe probe = mgr.getTrackerProbe();
        probe.probe("tracker.example.com", TrackerClient.DEFAULT_UDP_TRACKER_PORT);
        probe.probe(null, TrackerClient.DEFAULT_UDP_TRACKER_PORT);
        probe.probe("opentracker.dg2.i2p", 0);
        probe.probe("opentracker.dg2.i2p", -1);
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("tracker.example.com"));
        assertNull(storedVerdictOrNull("tracker.example.com"));
    }

    /**
     * A config reload replaces the cache with what the config holds, so
     * deleting the keys reverts the behaviour exactly.
     */
    @Test
    public void testReload() {
        TrackerProbe probe = mgr.getTrackerProbe();
        probe.record("tracker2.postman.i2p", TrackerProbe.Support.UDP);
        probe.record("opentracker.dg2.i2p", TrackerProbe.Support.NO_UDP);
        Properties config = new Properties();
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "tracker2.postman.i2p",
                           "ok," + ctx.clock().now());
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "gone.i2p",
                           "ok," + (ctx.clock().now() - TrackerProbe.OK_TTL - 1));
        config.setProperty(TrackerProbe.PROP_UDP_TRACKER + "junk.i2p", "not a verdict");
        config.setProperty("i2psnark.dir", "i2psnark");
        probe.reload(config);
        assertEquals(TrackerProbe.Support.UDP, probe.support("tracker2.postman.i2p"));
        // what the config no longer holds is forgotten, and so is the expired
        // and the malformed
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("opentracker.dg2.i2p"));
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("gone.i2p"));
        assertEquals(TrackerProbe.Support.UNKNOWN, probe.support("junk.i2p"));
    }

    /**
     * The verdict the config file holds for a host.
     *
     * @param host the tracker hostname
     * @return the stored value, never null
     */
    private String storedVerdict(String host) {
        String s = storedVerdictOrNull(host);
        assertNotNull("no verdict in the config for " + host, s);
        return s;
    }

    /**
     * The verdict the config file holds for a host.
     *
     * @param host the tracker hostname
     * @return the stored value, null when the config does not hold one
     */
    private String storedVerdictOrNull(String host) {
        File file = new File(baseDir, "test-i2psnark-probe.config.d" + File.separator + SnarkManager.CONFIG_FILE);
        Properties props = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
            return props.getProperty(TrackerProbe.PROP_UDP_TRACKER + host);
        } catch (IOException ioe) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignore) {}
            }
        }
    }

    /**
     * The util the manager built, for the session check.
     *
     * @return the util, never null
     */
    private I2PSnarkUtil util() {
        return mgr.util();
    }

    /**
     * A string of the requested length, for the host-length limits.
     *
     * @param len the length wanted
     * @param c the character to fill with
     * @return a string of exactly len characters
     */
    private static String longString(int len, char c) {
        StringBuilder buf = new StringBuilder(len);
        for (int i = 0; i < len; i++) {buf.append(c);}
        return buf.toString();
    }
}
