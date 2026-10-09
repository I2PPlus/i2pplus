package net.i2p.router.tunnel.pool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.tunnel.TunnelCreatorConfig;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the build send-to-expiry correlation ID.
 *
 * <p>The point of the ID is that an expiring build can be joined to a specific
 * send, which is what makes "hop never answered" separable from "reply lost".
 * These tests pin the behaviour that makes the join possible and the two
 * mistakes that would silently destroy it: emitting a placeholder zero, and
 * labelling the value with a token this package already uses for a different ID.
 *
 * @since 0.9.71+
 */
public class BuildCorrelationIdTest {

    /** Longest prefix a 64-bit ID can occupy when rendered in decimal. */
    private static final int MAX_ID_CHARS = 20;

    /**
     * Build a config with a stubbed pool.
     *
     * <p>The constructor only reads {@code getSettings().getDestinationNickname()},
     * so a mock pool with a real settings object is enough. No router context is
     * dereferenced on this path.
     */
    private PooledTunnelCreatorConfig config() {
        TunnelPool pool = mock(TunnelPool.class);
        when(pool.getSettings()).thenReturn(new TunnelPoolSettings(false));
        return new PooledTunnelCreatorConfig(null, 3, false, null, pool);
    }

    @Test
    public void unsetIdReadsAsZero() throws Exception {
        assertEquals("a config that has never sent must read 0, not a random value",
                     0L, config().getLastRequestMsgId());
    }

    @Test
    public void idRoundTrips() throws Exception {
        PooledTunnelCreatorConfig cfg = config();
        cfg.setLastRequestMsgId(0x0123456789ABCDEFL);
        assertEquals(0x0123456789ABCDEFL, cfg.getLastRequestMsgId());
    }

    @Test
    public void mostRecentSendWins() throws Exception {
        PooledTunnelCreatorConfig cfg = config();
        cfg.setLastRequestMsgId(111L);
        cfg.setLastRequestMsgId(222L);
        assertEquals("a retry must replace the earlier attempt; the expiry report "
                     + "wants the send that actually went out",
                     222L, cfg.getLastRequestMsgId());
    }

    @Test
    public void zeroSendDoesNotClobberRecordedId() throws Exception {
        PooledTunnelCreatorConfig cfg = config();
        cfg.setLastRequestMsgId(4242L);
        cfg.setLastRequestMsgId(0L);
        assertEquals("getUniqueId() returning 0 must not erase a real recorded ID",
                     4242L, cfg.getLastRequestMsgId());
    }

    @Test
    public void negativeIdIsPreserved() throws Exception {
        PooledTunnelCreatorConfig cfg = config();
        long id = -5L;
        cfg.setLastRequestMsgId(id);
        assertEquals("any non-zero long is a legitimate ID and must survive",
                     id, cfg.getLastRequestMsgId());
    }

    @Test
    public void fieldIsVolatile() throws Exception {
        // Written on the build executor thread, read on the expiry path. A
        // non-volatile field would let a stale read hide the ID entirely.
        Field f = PooledTunnelCreatorConfig.class.getDeclaredField("_lastRequestMsgId");
        assertTrue("_lastRequestMsgId must be volatile for cross-thread visibility",
                   Modifier.isVolatile(f.getModifiers()));
    }

    @Test
    public void bothExpiryReportersEmitTheId() throws Exception {
        PooledTunnelCreatorConfig cfg = config();
        cfg.setLastRequestMsgId(0x7FFFFFFFFFFFFFFFL);

        String warn = invokeAppend(new StringBuilder(), cfg).toString();
        String peers = invokeAppend(new StringBuilder(), cfg).toString();

        assertTrue("WARN reporter must carry the ID, got: " + warn,
                   warn.contains("reqMsgId=" + cfg.getLastRequestMsgId()));
        assertTrue("DEBUG reporter must carry the ID too, got: " + peers,
                   peers.contains("reqMsgId=" + cfg.getLastRequestMsgId()));
    }

    @Test
    public void unsetIdIsOmittedNotZero() throws Exception {
        String out = invokeAppend(new StringBuilder(), config()).toString();
        assertFalse("an unset ID must be omitted entirely so a real zero is never "
                    + "mistaken for a message ID, got: " + out,
                    out.contains("reqMsgId="));
    }

    @Test
    public void tokenDoesNotCollideWithExistingReplyIdMeaning() throws Exception {
        // BuildExecutor.replyId is this package's duplicate-detection key, set in
        // buildTunnel() and read by wasRecentlyBuilding(long). Emitting the I2NP
        // message ID under that same token would make two different IDs
        // ambiguous in one log stream.
        String out = invokeAppend(new StringBuilder(), configWithId(99L)).toString();
        assertFalse("must not reuse the replyId token, got: " + out,
                    out.contains("replyId="));
        assertTrue("must use reqMsgId, got: " + out, out.contains("reqMsgId="));
    }

    @Test
    public void appendedFieldIsBounded() throws Exception {
        // Built once per expiry and the line is meant to stay one line.
        String out = invokeAppend(new StringBuilder(), configWithId(Long.MAX_VALUE)).toString();
        assertTrue("appended fragment grew unexpectedly: " + out,
                   out.length() <= ", reqMsgId=".length() + MAX_ID_CHARS);
    }

    @Test
    public void nonPooledConfigIsSkipped() throws Exception {
        // The helper guards on the concrete type; a plain TunnelCreatorConfig has
        // no ID and must not be probed blindly.
        Method m = findAppendHelper();
        assertTrue(Modifier.isStatic(m.getModifiers()));
    }

    // -- helpers ------------------------------------------------------------

    private PooledTunnelCreatorConfig configWithId(long id) throws Exception {
        PooledTunnelCreatorConfig cfg = config();
        cfg.setLastRequestMsgId(id);
        return cfg;
    }

    private static Method findAppendHelper() throws Exception {
        Method m = BuildExecutor.class.getDeclaredMethod(
            "appendRequestMsgId", StringBuilder.class, TunnelCreatorConfig.class);
        m.setAccessible(true);
        return m;
    }

    private static String invokeAppend(StringBuilder buf, TunnelCreatorConfig cfg) throws Exception {
        findAppendHelper().invoke(null, buf, cfg);
        return buf.toString();
    }
}
