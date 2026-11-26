package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.crypto.ratchet.RatchetSessionTag;

import org.junit.Test;

/**
 * Unit tests for the clause that names how a lookup's reply comes back. The
 * line that reports the query going out carries it, so one line says what was
 * sent and how the answer is expected; the old form spent a second line per
 * query, 149,277 of them in 23 minutes, and printed six characters of the
 * session key, a truncated secret the console linkified as a router.
 *
 * @since 0.9.72+
 */
public class ReplySessionClauseTest {

    /**
     * Builds sessions per case and holds nothing of its own.
     */
    public ReplySessionClauseTest() {
    }

    /**
     * The ratchet tag is the only identifier of the AEAD session that appears
     * anywhere else, so it is printed - once, under one key, not as
     * "Tag: RatchetSessionTag: ...".
     */
    @Test
    public void testTheRatchetTagIsNamedOnce() {
        RatchetSessionTag rtag = new RatchetSessionTag(0x0102030405060708L);
        String clause = IterativeSearchJob.replyClause(session(rtag));
        assertEquals(" -> AEAD reply tag: " + rtag.toBase64(), clause);
        assertFalse("no duplicated key: " + clause,
                    clause.contains("RatchetSessionTag"));
    }

    /**
     * The session key is a secret and its short form in brackets was linkified
     * as a router that does not exist, so neither form of it appears.
     */
    @Test
    public void testTheSessionKeyIsNotPrinted() {
        RatchetSessionTag rtag = new RatchetSessionTag(0x0102030405060708L);
        String clause = IterativeSearchJob.replyClause(session(rtag));
        assertFalse("no key fragment: " + clause,
                    clause.contains("Session key"));
        assertFalse("no brackets around a non-hash: " + clause,
                    clause.contains("["));
    }

    /**
     * The ElGamal session's own tag is 32 bytes of base64 that nothing else in
     * the log quotes back, so that path names the cipher and spends no
     * characters on the tag.
     */
    @Test
    public void testElGamalNamesTheCipherNotTheTag() {
        String clause = IterativeSearchJob.replyClause(
            new MessageWrapper.OneTimeSession(key(), new SessionTag(true)));
        assertEquals(" -> ElGamal reply", clause);
    }

    /**
     * No session means no reply to expect: the query goes out unencrypted and
     * the line says nothing about a session it does not have.
     */
    @Test
    public void testNoSessionNamesNothing() {
        assertEquals("", IterativeSearchJob.replyClause(null));
    }

    /**
     * The clause rides on a line that already names the key and the peer, so a
     * long clause would push the emitted line past the house budget.
     */
    @Test
    public void testTheClauseStaysInsideTheLineBudget() {
        RatchetSessionTag rtag = new RatchetSessionTag(0x0102030405060708L);
        String clause = IterativeSearchJob.replyClause(session(rtag));
        assertTrue("clause too long (" + clause.length() + "): " + clause,
                   clause.length() <= 32);
    }

    /**
     * A ratchet session with a fresh key.
     *
     * @param rtag the reply tag to carry
     * @return a session for the AEAD path
     */
    private static MessageWrapper.OneTimeSession session(
                                          RatchetSessionTag rtag) {
        return new MessageWrapper.OneTimeSession(key(), rtag);
    }

    /**
     * A session key whose bytes are all zero - the value never reaches the log.
     *
     * @return a 32-byte session key
     */
    private static SessionKey key() {
        SessionKey key = new SessionKey();
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        key.setData(data);
        return key;
    }
}
