package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.Properties;
import net.i2p.I2PAppContext;
import org.junit.Test;

/**
 * Tests for <code>I2PSnarkUtil.buildOpts()</code>, the session properties snark hands to the
 * socket manager factory. It starts from the context's properties, merges snark's own over them,
 * then writes snark's session defaults - unanswered pings, a three-minute streaming inactivity
 * timer, capped tunnel counts, tunnel nicknames - into the result. Two things have to hold for
 * that to be safe inside a running router: the result must be a throwaway object, so none of
 * those writes reach the router's own settings, and it must keep the context's values reachable,
 * which it does through the defaults chain the context puts behind it. A caller that copies the
 * result with <code>putAll()</code> keeps only the own entries and silently drops every value
 * inherited that way, including an operator's tunnel counts and window settings.
 *
 * @since 0.9.72+
 */
public class BuildOptsTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public BuildOptsTest() {}

    /**
     * Snark's session defaults land in the properties it gets back and nowhere else: the
     * context's own merged properties are unchanged by the call, so an in-router snark cannot
     * rewrite router-wide settings for the rest of the process.
     */
    @Test
    public void testSessionDefaultsDoNotLeakIntoTheContext() {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Properties opts = util.buildOpts("SnarkTest", true);
        assertEquals("SnarkTest", opts.getProperty("inbound.nickname"));
        assertEquals("false", opts.getProperty("i2p.streaming.answerPings"));
        assertEquals(Long.toString(I2PSnarkUtil.STREAMING_INACTIVITY_MS),
                     opts.getProperty("i2p.streaming.inactivityTimeout"));

        Properties ctx = I2PAppContext.getGlobalContext().getProperties();
        assertNull("the context gained a snark nickname", ctx.getProperty("inbound.nickname"));
        assertNull("the context gained a snark session default",
                   ctx.getProperty("i2p.streaming.answerPings"));
    }

    /**
     * The context's own values stay reachable from the result. They arrive through the defaults
     * chain rather than as entries of their own, which is exactly what a <code>putAll()</code>
     * copy of the result loses - the reason the per-torrent session path passes the result
     * through as it is.
     */
    @Test
    public void testContextValuesStayReachable() {
        String expected = System.getProperty("java.version");
        assertNotNull(expected);
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Properties opts = util.buildOpts(null, false);
        assertEquals(expected, opts.getProperty("java.version"));

        // what a putAll() copy would hand the session instead
        Properties copied = new Properties();
        copied.putAll(opts);
        assertNull(copied.getProperty("java.version"));
    }
}
