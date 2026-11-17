package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Properties;

import org.junit.Test;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;

/**
 * The store-message key must name the RouterInfo it stores. The key is chosen
 * by the sender and is not covered by the entry's signature, so a peer can
 * otherwise plant any other router's newer RouterInfo under a victim's hash and
 * have the victim, not the sender, banlisted for it.
 *
 * @since 0.9.72+
 */
public class StoreKeyIdentityTest {

    /** Holds no state; every case builds its own RouterInfos. */
    public StoreKeyIdentityTest() {}

    /**
     * A RouterInfo stored under the hash of its own identity passes.
     *
     * @throws Exception if the identity key pairs cannot be generated
     */
    @Test
    public void entryStoredUnderItsOwnKeyMatches() throws Exception {
        RouterInfo ri = routerInfo();
        assertTrue(HandleFloodfillDatabaseStoreMessageJob.keyMatchesIdentity(
                ri.getIdentity().getHash(), ri));
    }

    /**
     * The attack: the wire key names one router while the entry is another
     * router's RouterInfo, so the check must fail and no ban may name the key.
     *
     * @throws Exception if the identity key pairs cannot be generated
     */
    @Test
    public void entryStoredUnderAnotherRoutersKeyDoesNotMatch() throws Exception {
        // The attack: the wire key is the victim's hash, the entry is somebody
        // else's RouterInfo, signed by that other router.
        RouterInfo victim = routerInfo();
        RouterInfo planted = routerInfo();
        Hash victimKey = victim.getIdentity().getHash();
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.keyMatchesIdentity(victimKey, planted));
        // And the planted entry does match its own key, so the check is about
        // the pairing, not about the entry being unusable.
        assertTrue(HandleFloodfillDatabaseStoreMessageJob.keyMatchesIdentity(
                planted.getIdentity().getHash(), planted));
    }

    /**
     * A missing key, a missing entry, or an entry with no identity at all, all
     * fail the check rather than passing it.
     *
     * @throws Exception if the identity key pairs cannot be generated
     */
    @Test
    public void missingPiecesDoNotMatch() throws Exception {
        RouterInfo ri = routerInfo();
        Hash key = ri.getIdentity().getHash();
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.keyMatchesIdentity(null, ri));
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.keyMatchesIdentity(key, null));
        RouterInfo keyless = new RouterInfo();
        keyless.setOptions(new Properties());
        assertFalse(HandleFloodfillDatabaseStoreMessageJob.keyMatchesIdentity(key, keyless));
    }

    /**
     * A RouterInfo with a freshly generated identity, so its hash is its own.
     *
     * @return a signed RouterInfo with no addresses and the current time as its publication date
     * @throws Exception if the identity key pairs cannot be generated
     */
    private static RouterInfo routerInfo() throws Exception {
        Object[] pki = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey) pki[0];
        Object[] signing = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey signingPubKey = (SigningPublicKey) signing[0];
        SigningPrivateKey signingPrivKey = (SigningPrivateKey) signing[1];

        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(pubKey);
        ident.setSigningPublicKey(signingPubKey);

        RouterInfo ri = new RouterInfo();
        ri.setIdentity(ident);
        ri.setAddresses(new HashSet<>());
        Properties options = new Properties();
        options.setProperty("router.version", "0.9.72");
        ri.setOptions(options);
        ri.setPublished(System.currentTimeMillis());
        ri.sign(signingPrivKey);
        assertNotNull(ri.getIdentity().getHash());
        return ri;
    }
}
