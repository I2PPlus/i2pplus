package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
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
 * Whether a floodfill candidate can be asked anything at all,
 * {@link FloodfillPeerSelector#canAnswerLookup(RouterInfo)}.
 * <p>
 * The selector ranked floodfills by health, proximity and subnet and never by
 * capability, so it handed a LeaseSet search the closest stale peers first; the
 * search then rejected each one in {@code sendQuery()} after it had consumed a
 * slot of the search's candidate allowance. A live router ended 3,362 LeaseSet
 * searches in four log files having asked nobody, and never once widened its
 * candidate set, because the peers it passed over satisfied the beta-confirmation
 * gate that switches widening off.
 * <p>
 * The predicate is the one the lookup path already applies, so the invariant
 * under test is that selection and lookup agree: a candidate is never both
 * selected and then passed over.
 *
 * @since 0.9.72+
 */
public class FloodfillAnswerCapabilityTest {

    /**
     * An encryption type code this build has no {@link EncType} for - a legacy
     * ElGamal/1024 peer parses to this, and its {@link PublicKey#getType()} is
     * null rather than a named type.
     */
    private static final int UNKNOWN_ENC_CODE = 1;

    /**
     * Test case carrying the floodfill capability cases. Every case builds a
     * RouterInfo and calls a static helper, so the instance holds no state and
     * needs no router.
     */
    public FloodfillAnswerCapabilityTest() {
    }

    /**
     * Builds a RouterInfo carrying only what the capability gate reads.
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
     * Builds a RouterInfo on the oldest version the NetDb will store to at all,
     * so only the capability under test varies between cases.
     *
     * @param sig the signing key type, non-null
     * @param enc the encryption key type, or null for a type this build cannot name
     * @return the RouterInfo, non-null
     */
    private static RouterInfo current(SigType sig, EncType enc) {
        return router(StoreJob.MIN_STORE_VERSION, sig, enc);
    }

    /** A current floodfill is kept, on either encryption type this build uses. */
    @Test
    public void testCurrentFloodfillIsKept() {
        assertTrue(FloodfillPeerSelector.canAnswerLookup(
                       current(SigType.ECDSA_SHA256_P256, EncType.ELGAMAL_2048)));
        assertTrue(FloodfillPeerSelector.canAnswerLookup(
                       current(SigType.EdDSA_SHA512_Ed25519, EncType.ECIES_X25519)));
    }

    /** A DSA-signed floodfill cannot hold a modern LeaseSet, so it is dropped. */
    @Test
    public void testDsaFloodfillIsDropped() {
        assertFalse(FloodfillPeerSelector.canAnswerLookup(
                        current(SigType.DSA_SHA1, EncType.ECIES_X25519)));
    }

    /** A floodfill on a legacy cipher has no encryption type this router can wrap to. */
    @Test
    public void testLegacyCipherFloodfillIsDropped() {
        assertFalse(FloodfillPeerSelector.canAnswerLookup(
                        current(SigType.ECDSA_SHA256_P256, null)));
    }

    /** A floodfill below the minimum store version predates the key types entirely. */
    @Test
    public void testOldVersionFloodfillIsDropped() {
        assertFalse(FloodfillPeerSelector.canAnswerLookup(
                        router("0.9.57", SigType.EdDSA_SHA512_Ed25519, EncType.ELGAMAL_2048)));
    }

    /**
     * With no RouterInfo for the candidate the capability cannot be checked, and
     * the candidate is kept - the lookup path keeps it too, which is the point of
     * the invariant below.
     */
    @Test
    public void testUnknownRouterInfoIsKept() {
        assertTrue(FloodfillPeerSelector.canAnswerLookup(null));
    }

    /**
     * The invariant: selection rejects exactly the peers the lookup path rejects,
     * so a candidate is never both ranked and then passed over. Each one passed
     * over cost the search a slot of its candidate allowance without a packet
     * leaving, which is how a search starved while candidates remained to rank.
     */
    @Test
    public void testSelectionAgreesWithTheLookupGate() {
        RouterInfo[] candidates = {
            current(SigType.EdDSA_SHA512_Ed25519, EncType.ELGAMAL_2048),
            current(SigType.EdDSA_SHA512_Ed25519, EncType.ECIES_X25519),
            router("0.9.57", SigType.EdDSA_SHA512_Ed25519, EncType.ELGAMAL_2048),
            current(SigType.DSA_SHA1, EncType.ELGAMAL_2048),
            current(SigType.EdDSA_SHA512_Ed25519, null),
        };
        for (RouterInfo ri : candidates) {
            assertTrue("selected but then passed over: " + StoreJob.storeRejectReason(ri),
                       FloodfillPeerSelector.canAnswerLookup(ri) == StoreJob.shouldStoreTo(ri));
        }
    }
}
