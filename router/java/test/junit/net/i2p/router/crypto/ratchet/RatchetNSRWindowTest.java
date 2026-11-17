package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

/**
 * The look-ahead window of tags a New Session Reply is matched by.
 * <p>
 * Alice registers a window of tags for the handshake and Bob answers with
 * New Session Reply messages until her first Existing Session message reaches
 * him, each reply carrying the next tag of that window. Only the tag a reply
 * actually carries may be taken out of the window, and only once the reply's
 * AEAD has passed: the key manager runs before the engine has decrypted
 * anything, so a tag spent here is spent even when the message that brought it
 * turns out to be garbage. Taking the whole window on the first hit left Alice
 * able to decrypt only the first reply of a session — a SYN-ACK followed by
 * data, or two replies reordered in transit, was dropped until her own next
 * message, and one on-path message copying an observed tag emptied the window
 * for nothing.
 *
 * @since 0.9.72+
 */
public class RatchetNSRWindowTest {

    /** Tags registered for one handshake, the size the ratchet's NSR window is set to. */
    private static final int WINDOW = 12;

    /** Context the key managers and tag sets are built on. */
    private static RouterContext _ctx;

    /** Builds the context once for the class. */
    @BeforeClass
    public static void makeContext() {
        _ctx = RouterTestHelper.getContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /** Runs the window decisions; it holds no state. */
    public RatchetNSRWindowTest() {}

    /**
     * Every tag of the window still resolves after the first one is spent,
     * which is what lets the second and third reply of a session decrypt.
     */
    @Test
    public void everyTagOfTheWindowSurvivesTheFirst() {
        RatchetSKM skm = newRatchetSKM();
        RatchetTagSet tagSet = nsrTagSet(skm);
        List<RatchetSessionTag> tags = new ArrayList<RatchetSessionTag>(tagSet.getRegisteredTags());
        assertEquals(WINDOW, tags.size());

        assertNotNull("the first NSR tag resolves", skm.consumeTag(tags.get(0)));

        int resolved = 0;
        for (int i = 1; i < tags.size(); i++) {
            if (skm.consumeTag(tags.get(i)) != null) {
                resolved++;
            }
        }
        assertEquals("the rest of the NSR window went with the first tag", WINDOW - 1, resolved);
    }

    /**
     * Spending a tag refills the window, and the fresh tag resolves too: the
     * responder may keep replying for as long as the handshake is open.
     */
    @Test
    public void theWindowIsRefilledAndStillResolves() {
        RatchetSKM skm = newRatchetSKM();
        RatchetTagSet tagSet = nsrTagSet(skm);
        List<RatchetSessionTag> first = new ArrayList<RatchetSessionTag>(tagSet.getRegisteredTags());
        skm.consumeTag(first.get(0));

        List<RatchetSessionTag> live = new ArrayList<RatchetSessionTag>(tagSet.getRegisteredTags());
        assertTrue("the window was not made back", live.size() >= WINDOW - 1);
        int resolved = 0;
        for (RatchetSessionTag tag : live) {
            if (skm.consumeTag(tag) != null) {
                resolved++;
            }
        }
        assertTrue("too few tags of the refilled window resolved", resolved >= WINDOW - 1);
    }

    /**
     * A tag the manager never minted resolves to nothing and leaves the window
     * whole — a message injected with a made-up tag costs the session nothing.
     */
    @Test
    public void anUnknownTagTakesNothingFromTheWindow() {
        RatchetSKM skm = newRatchetSKM();
        RatchetTagSet tagSet = nsrTagSet(skm);
        List<RatchetSessionTag> tags = new ArrayList<RatchetSessionTag>(tagSet.getRegisteredTags());

        assertNull(skm.consumeTag(new RatchetSessionTag(0xdeadbeefL)));

        int resolved = 0;
        for (RatchetSessionTag tag : tags) {
            if (skm.consumeTag(tag) != null) {
                resolved++;
            }
        }
        assertEquals(WINDOW, resolved);
    }

    /**
     * A key manager with nothing registered in it yet.
     *
     * @return a fresh ratchet key manager
     */
    private static RatchetSKM newRatchetSKM() {
        return new RatchetSKM(_ctx, null, EncType.ECIES_X25519);
    }

    /**
     * A fresh inbound NSR tag set, with its whole window of tags registered in
     * the given key manager.
     *
     * @param skm the manager to register the tags in
     * @return the tag set
     */
    private static RatchetTagSet nsrTagSet(RatchetSKM skm) {
        HKDF hkdf = new HKDF(_ctx);
        byte[] root = new byte[32];
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) {
            root[i] = (byte) i;
            data[i] = (byte) (i + 1);
        }
        HandshakeState state = mock(HandshakeState.class);
        return new RatchetTagSet(hkdf, skm, state, new SessionKey(root), new SessionKey(data),
                                 _ctx.clock().now(), WINDOW, WINDOW);
    }
}
