package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import org.junit.Test;

/**
 * The reason behind a {@link StoreJob#shouldStoreTo} rejection. The predicate
 * answers with a bare boolean, so the NetDb lines that pass a peer over could
 * only say "old router" - and a peer that has not upgraded, a peer still on DSA
 * keys and a peer whose encryption type the router cannot name are three
 * different problems with three different fixes.
 * <p>
 * A live router skipped 80,499 queries to 100 such peers in 8.7 minutes without
 * naming once what was wrong with any of them.
 *
 * @since 0.9.72+
 */
public class StoreGateReasonTest {

    /**
     * An encryption type code this build has no {@link EncType} for - a legacy
     * ElGamal/1024 peer parses to this, and its {@link PublicKey#getType()} is
     * null rather than a named type.
     */
    private static final int UNKNOWN_ENC_CODE = 1;

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     *
     * @since 0.9.72+
     */
    public StoreGateReasonTest() {
    }

    /**
     * Builds a RouterInfo carrying only what the store gate reads.
     *
     * @param version the value for the <code>router.version</code> option
     * @param sig the signing key type, non-null
     * @param enc the encryption key type, or null for a type this build cannot name
     * @return the RouterInfo, non-null
     */
    private static RouterInfo router(String version, SigType sig, EncType enc) {
        Properties opts = new Properties();
        opts.setProperty("router.version", version);
        RouterInfo ri = new RouterInfo();
        ri.setOptions(opts);
        RouterIdentity ident = new RouterIdentity();
        ident.setSigningPublicKey(new SigningPublicKey(sig));
        ident.setPublicKey(enc != null ? new PublicKey(enc)
                                       : new PublicKey(UNKNOWN_ENC_CODE, new byte[0]));
        ri.setIdentity(ident);
        return ri;
    }

    /**
     * A router that passes the gate has nothing to explain, and the null says
     * so - a caller that printed a reason for an accepted peer would be
     * inventing one.
     *
     * @since 0.9.72+
     */
    @Test
    public void testAcceptedRouterHasNoReason() {
        RouterInfo ri = router(StoreJob.MIN_STORE_VERSION, SigType.ECDSA_SHA256_P256, EncType.ELGAMAL_2048);
        assertTrue(StoreJob.shouldStoreTo(ri));
        assertNull(StoreJob.storeRejectReason(ri));
    }

    /**
     * A version below the gate is named with both numbers, so the reader can
     * tell a peer that merely has not upgraded from one that is broken.
     *
     * @since 0.9.72+
     */
    @Test
    public void testOldVersionIsNamedWithBothNumbers() {
        RouterInfo ri = router("0.9.57", SigType.ECDSA_SHA256_P256, EncType.ELGAMAL_2048);
        assertFalse(StoreJob.shouldStoreTo(ri));
        String why = StoreJob.storeRejectReason(ri);
        assertEquals("Version 0.9.57 predates " + StoreJob.MIN_STORE_VERSION, why);
    }

    /**
     * A DSA-signed peer is named by the key type, the term a reader greps for.
     *
     * @since 0.9.72+
     */
    @Test
    public void testDsaSigningKeyIsNamed() {
        RouterInfo ri = router(StoreJob.MIN_STORE_VERSION, SigType.DSA_SHA1, EncType.ECIES_X25519);
        assertFalse(StoreJob.shouldStoreTo(ri));
        assertEquals("Signing key " + SigType.DSA_SHA1, StoreJob.storeRejectReason(ri));
    }

    /**
     * An encryption type this router cannot name - a legacy ElGamal/1024 peer
     * parses to a null EncType - is reported in prose, never as the literal
     * word "null".
     *
     * @since 0.9.72+
     */
    @Test
    public void testUnknownEncryptionTypeIsNamedInProse() {
        RouterInfo ri = router(StoreJob.MIN_STORE_VERSION, SigType.ECDSA_SHA256_P256, null);
        assertFalse(StoreJob.shouldStoreTo(ri));
        String why = StoreJob.storeRejectReason(ri);
        assertEquals("Encryption key type not recognised", why);
        assertFalse(why, why.contains("null"));
    }

    /**
     * A peer that fails every gate is reported as failing every gate: a line
     * that stops at the first one sends the reader to the wrong fix, and the
     * tally cannot tell the two apart.
     *
     * @since 0.9.72+
     */
    @Test
    public void testEveryFailureIsReported() {
        RouterInfo ri = router("0.9.57", SigType.DSA_SHA1, null);
        String why = StoreJob.storeRejectReason(ri);
        assertEquals(3, why.split(" \\* ").length);
        assertTrue(why, why.contains("Version 0.9.57"));
        assertTrue(why, why.contains("Signing key"));
        assertTrue(why, why.contains("Encryption key"));
    }

    /**
     * The reason exists to ride one log line, so it must never open a
     * continuation line of its own.
     *
     * @since 0.9.72+
     */
    @Test
    public void testReasonStaysOnOneLine() {
        String why = StoreJob.storeRejectReason(router("0.9.57", SigType.DSA_SHA1, null));
        assertNotNull(why);
        assertFalse(why, why.contains("\n"));
    }

    /**
     * The reason and the gate cannot disagree: a non-null reason appears for
     * exactly the routers the gate rejects.
     *
     * @since 0.9.72+
     */
    @Test
    public void testReasonAgreesWithTheGate() {
        SigType[] sigs = {SigType.DSA_SHA1, SigType.ECDSA_SHA256_P256};
        EncType[] encs = {EncType.ELGAMAL_2048, EncType.ECIES_X25519, null};
        String[] versions = {"0.9.57", StoreJob.MIN_STORE_VERSION, "1.17.0"};
        for (SigType sig : sigs) {
            for (EncType enc : encs) {
                for (String version : versions) {
                    RouterInfo ri = router(version, sig, enc);
                    boolean accepted = StoreJob.shouldStoreTo(ri);
                    String why = StoreJob.storeRejectReason(ri);
                    assertEquals("sig " + sig + " enc " + enc + " version " + version,
                                 accepted, why == null);
                }
            }
        }
    }
}
