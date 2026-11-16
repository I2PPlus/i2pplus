package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PublicKey;
import net.i2p.data.i2cp.MessageStatusMessage;

import org.junit.Test;

/**
 * The words the send-failure log line is built from.
 *
 * <p>A rejected send to a peer whose LeaseSet is already in hand used to read
 * "Unsupported encryption? (Failure code: 17)" — a question mark where the reason
 * is knowable, a bare status integer, and neither encryption type, so the reader
 * could not tell whether the peer publishes no ElGamal key or our own destination
 * is the ElGamal-only one. Those two cases need opposite fixes.
 *
 * @since 0.9.72+
 */
public class SendFailureCopyTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public SendFailureCopyTest() {}

    /**
     * Every failure code {@code getNextLease()} can return is mapped to prose, so
     * the log never prints a bare integer where the words already exist in the
     * tree.
     */
    @Test
    public void testEveryReachableCodeIsMapped() {
        assertEquals("No encryption type in common",
                     OutboundClientMessageOneShotJob.sendFailureName(
                             MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION));
        assertEquals("Meta LeaseSet cannot receive messages",
                     OutboundClientMessageOneShotJob.sendFailureName(
                             MessageStatusMessage.STATUS_SEND_FAILURE_META_LEASESET));
        assertEquals("Malformed LeaseSet",
                     OutboundClientMessageOneShotJob.sendFailureName(
                             MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET));
        assertEquals("No LeaseSet",
                     OutboundClientMessageOneShotJob.sendFailureName(
                             MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET));
        assertEquals("No outbound tunnel",
                     OutboundClientMessageOneShotJob.sendFailureName(
                             MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS));
    }

    /**
     * An unmapped code still gets prose rather than nothing, and no mapping is
     * just the number spelled out.
     */
    @Test
    public void testUnmappedCodeStillGetsProse() {
        String s = OutboundClientMessageOneShotJob.sendFailureName(9999);
        assertFalse(s.isEmpty());
        assertEquals("Send rejected", s);
        assertFalse(OutboundClientMessageOneShotJob.sendFailureName(
                            MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED).matches("\\d+"));
    }

    /** A v1 lease set carries one key, and its type is what gets printed. */
    @Test
    public void testV1LeaseSetSingleType() {
        LeaseSet ls = new LeaseSet();
        ls.setEncryptionKey(new PublicKey(EncType.ELGAMAL_2048));
        assertEquals("ELGAMAL_2048", OutboundClientMessageOneShotJob.encryptionTypes(ls));
    }

    /**
     * A v2 lease set can carry several keys; all of them are printed, in the
     * order the lease set holds them, so a peer that publishes both a legacy and
     * a modern type is visible as such.
     */
    @Test
    public void testV2LeaseSetListsEveryType() {
        LeaseSet2 ls = new LeaseSet2();
        ls.addEncryptionKey(new PublicKey(EncType.ECIES_X25519));
        ls.addEncryptionKey(new PublicKey(EncType.ELGAMAL_2048));
        assertEquals("ECIES_X25519/ELGAMAL_2048",
                     OutboundClientMessageOneShotJob.encryptionTypes(ls));
    }

    /** A lease set with no key at all reads as "none", never an empty segment. */
    @Test
    public void testLeaseSetWithoutKeysReadsAsNone() {
        assertEquals("none", OutboundClientMessageOneShotJob.encryptionTypes(new LeaseSet()));
        assertEquals("none", OutboundClientMessageOneShotJob.encryptionTypes(new LeaseSet2()));
    }

    /** Our own accepted set is printed the same way, so both sides line up. */
    @Test
    public void testSetOfTypes() {
        Set<EncType> both = EnumSet.of(EncType.ELGAMAL_2048, EncType.ECIES_X25519);
        String s = OutboundClientMessageOneShotJob.encryptionTypes(both);
        assertTrue(s.contains("ELGAMAL_2048"));
        assertTrue(s.contains("ECIES_X25519"));
        assertEquals("ELGAMAL_2048", OutboundClientMessageOneShotJob.encryptionTypes(
                Collections.singleton(EncType.ELGAMAL_2048)));
        assertEquals("none", OutboundClientMessageOneShotJob.encryptionTypes(
                Collections.<EncType>emptySet()));
    }
}
