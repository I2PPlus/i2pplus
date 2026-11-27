package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.SigningPublicKey;

import org.junit.Test;

/**
 * The line reported when an inbound SYN is dropped for carrying a sequence
 * number other than zero.
 * <p>
 * The streaming spec fixes a SYN's sequence number at zero, and both ingress
 * paths drop a SYN that breaks the rule rather than letting the value be keyed on
 * downstream as if it named a queued block. Upstream added the guard in
 * 5efa2e84ba. The drop is only actionable if the line says which peer sent it and
 * what it sent, so the formatting is pinned here: the peer hash bracketed for the
 * console linkifier, the sequence number beside the reason, and one line.
 *
 * @since 0.9.72+
 */
public class SynSequenceGuardTest {

    /**
     * The formatting cases. Each one calls the formatter directly, so the
     * instance holds no state and needs no session or router.
     *
     * @since 0.9.72+
     */
    public SynSequenceGuardTest() {}

    /** The sequence number used by the cases that supply one. */
    private static final long SEQ = 7;

    /**
     * A structurally valid destination, so its hash can be calculated the way the
     * log line does it.
     *
     * @return a destination with an ElGamal and a DSA key, non-null
     * @throws Exception on key generation failure
     */
    private static Destination identity() throws Exception {
        Destination dest = new Destination();
        dest.setCertificate(Certificate.NULL_CERT);
        KeyGenerator gen = KeyGenerator.getInstance();
        KeyPair pki = gen.generatePKIKeys(EncType.ELGAMAL_2048);
        dest.setPublicKey(pki.getPublic());
        byte[] sigKey = new byte[SigType.DSA_SHA1.getPubkeyLen()];
        dest.setSigningPublicKey(new SigningPublicKey(SigType.DSA_SHA1, sigKey));
        return dest;
    }

    /**
     * The line names the drop, the reason, the sequence number that broke the
     * rule, and the peer that sent it - bracketed and eight characters, which is
     * the only form the console linkifier turns into a destination link.
     *
     * @throws Exception if the fixture destination's keys cannot be generated
     */
    @Test
    public void namesTheDropTheSequenceNumberAndThePeer() throws Exception {
        Destination from = identity();
        String line = ConnectionHandler.badSynLine(SEQ, from);
        assertTrue("the line does not name the drop: " + line,
                   line.startsWith("Dropping SYN -> Sequence number is not zero"));
        assertTrue("the sequence number is missing: " + line, line.contains(" * Seq: " + SEQ));
        String tag = " * From: [" + from.calculateHash().toShortBase32() + "]";
        assertEquals("the peer tag is wrong: " + line, line.length(), line.indexOf(tag) + tag.length());
        assertEquals("the peer hash is not the eight characters the console links",
                     8, from.calculateHash().toShortBase32().length());
    }

    /**
     * A SYN with no source option gets no peer segment, and no dangling separator
     * where the segment would have been.
     */
    @Test
    public void omitsThePeerSegmentWhenThereIsNone() {
        String line = ConnectionHandler.badSynLine(SEQ, null);
        assertFalse("a peer segment was emitted for a SYN with no source: " + line, line.contains("From:"));
        assertFalse("the line ends on a separator: " + line, line.endsWith(" * "));
        assertTrue("the sequence number is missing: " + line, line.contains(" * Seq: " + SEQ));
    }

    /**
     * The line is one line, and short enough for the console log page - the
     * house limit for an emitted line is 100 characters.
     *
     * @throws Exception if the fixture destination's keys cannot be generated
     */
    @Test
    public void staysOnOneLineWithinTheHouseLimit() throws Exception {
        String line = ConnectionHandler.badSynLine(SEQ, identity());
        assertEquals("the line wraps", -1, line.indexOf('\n'));
        assertTrue("the emitted line is " + line.length() + " characters", line.length() <= 100);
    }

    /**
     * The whole line is pinned, because it is the grep term for this class of
     * drop and both ingress paths emit exactly this text.
     */
    @Test
    public void pinsTheGreppableText() {
        assertEquals("Dropping SYN -> Sequence number is not zero * Seq: 7",
                     ConnectionHandler.badSynLine(SEQ, null));
    }
}
