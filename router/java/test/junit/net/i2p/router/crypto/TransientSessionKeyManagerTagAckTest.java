package net.i2p.router.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.TagSetHandle;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for tag availability in {@link TransientSessionKeyManager}.
 *
 * <p>Tags handed to {@link TransientSessionKeyManager#tagsDelivered} are not
 * usable until the peer acks them, so a delivered tag set must contribute zero
 * available tags and only raise the count after {@code tagsAcked}. The
 * acknowledgement is the only evidence that the peer received the tags.
 *
 * @since 0.9.72
 */
public class TransientSessionKeyManagerTagAckTest {

    private static final int TAG_COUNT = 5;

    private I2PAppContext _ctx;
    private TransientSessionKeyManager _skm;
    private PublicKey _target;
    private SessionKey _key;

    @Before
    public void setUp() {
        _ctx = I2PAppContext.getGlobalContext();
        _skm = new TransientSessionKeyManager(_ctx, 20, 10);
        KeyGenerator kg = _ctx.keyGenerator();
        _key = kg.generateSessionKey();
        _target = (PublicKey) kg.generatePKIKeys()[0];
        assertNotNull(_target);
        assertEquals(EncType.ELGAMAL_2048, _target.getType());
    }

    @After
    public void tearDown() {
        if (_skm != null) {
            _skm.shutdown();
        }
    }

    /** A fresh set of tags of the size this manager normally sends. */
    private static Set<SessionTag> newTags() {
        Set<SessionTag> tags = new HashSet<>(TAG_COUNT);
        for (int i = 0; i < TAG_COUNT; i++) {
            tags.add(new SessionTag(true));
        }
        return tags;
    }

    @Test
    public void testDeliveredTagsAreNotUsableBeforeAck() {
        TagSetHandle handle = _skm.tagsDelivered(_target, _key, newTags());
        assertNotNull(handle);
        assertEquals("delivered tags must not be usable before the ack",
                     0, _skm.getAvailableTags(_target, _key));
    }

    @Test
    public void testAckedTagsBecomeUsable() {
        TagSetHandle handle = _skm.tagsDelivered(_target, _key, newTags());
        assertNotNull(handle);
        _skm.tagsAcked(_target, _key, handle);
        assertEquals("acked tags must become usable", TAG_COUNT, _skm.getAvailableTags(_target, _key));
    }

    /**
     * Failing a delivered tag set must leave nothing usable, so a peer that never
     * acks cannot accumulate a pool of tags we would encrypt to.
     */
    @Test
    public void testFailedTagsAreNeverUsable() {
        TagSetHandle handle = _skm.tagsDelivered(_target, _key, newTags());
        assertNotNull(handle);
        _skm.failTags(_target, _key, handle);
        assertEquals(0, _skm.getAvailableTags(_target, _key));
    }

    /** A delivered tag set must report a nonzero time left only once acked. */
    @Test
    public void testAvailableTimeLeftOnlyAfterAck() {
        TagSetHandle handle = _skm.tagsDelivered(_target, _key, newTags());
        assertNotNull(handle);
        assertEquals(0L, _skm.getAvailableTimeLeft(_target, _key));
        _skm.tagsAcked(_target, _key, handle);
        assertTrue("acked tags must have time left", _skm.getAvailableTimeLeft(_target, _key) > 0L);
    }

    /**
     * A rekey drops the previously acked tags, so tags bound to a stale session
     * key are never offered to the peer again.
     */
    @Test
    public void testRekeyDropsAckedTags() {
        TagSetHandle first = _skm.tagsDelivered(_target, _key, newTags());
        assertNotNull(first);
        _skm.tagsAcked(_target, _key, first);
        assertEquals(TAG_COUNT, _skm.getAvailableTags(_target, _key));
        SessionKey newKey = _ctx.keyGenerator().generateSessionKey();
        TagSetHandle second = _skm.tagsDelivered(_target, newKey, newTags());
        assertNotNull(second);
        assertEquals("a rekey must drop the previous session's tags",
                     0, _skm.getAvailableTags(_target, _key));
    }
}
