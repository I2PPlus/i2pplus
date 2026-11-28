package net.i2p.data.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

import org.junit.Test;

/**
 * The peer list of a router info, and the message a rejected signature carries.
 *
 * <p>The spec requires the peer list to be empty and nothing in the router uses
 * it, so a peer that fills one is only worth bounding by size — the whole router
 * info is capped at {@link RouterInfo#MAX_UNCOMPRESSED_SIZE}, which a 32-byte
 * hash cannot get past about 120 times over. A count check of five rejected a
 * fork's honest router info on one handshake only I2P+ would reject, and both
 * transports then punished the sender for a parse failure of their own making.
 *
 * <p>The signature message matters because the transports tell a forged identity
 * from a bad format by it, and only the first is a reason to block an address.
 *
 * @since 0.9.72+
 */
public class RouterInfoPeersTest {

    /** Runs the cases; it holds no state of its own. */
    public RouterInfoPeersTest() {}

    /**
     * A signed router info with a real DSA/ElGamal identity and count distinct
     * peer hashes, so {@code readBytes} takes the same signature path the
     * transports use.
     *
     * @param count peer hashes to put in the list
     * @param signWith the key the router info is signed with
     * @param publish the signing key published in the identity
     * @return the router info, ready to write
     * @throws DataFormatException if the test fixture cannot be built
     */
    private static RouterInfo signed(int count, SigningPrivateKey signWith,
                                     SigningPublicKey publish) throws DataFormatException {
        Object[] keys = KeyGenerator.getInstance().generatePKIKeypair();
        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey((PublicKey) keys[0]);
        ident.setSigningPublicKey(publish);

        RouterInfo ri = new RouterInfo();
        ri.setIdentity(ident);
        ri.setOptions(new Properties());
        ri.setPublished(System.currentTimeMillis());
        Set<Hash> peers = new HashSet<Hash>(count);
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[Hash.HASH_LENGTH];
            data[0] = (byte) i;
            data[1] = (byte) (i >> 8);
            Hash h = new Hash();
            h.setData(data);
            peers.add(h);
        }
        ri.setPeers(peers);
        ri.sign(signWith);
        return ri;
    }

    /**
     * Writes and re-reads a router info with the signature verified.
     *
     * @param ri the router info to serialise
     * @param baos the buffer to write it into, reset first and reused per call
     * @return the router info read back from that buffer
     * @throws Exception on a stream the writer or the reader cannot use
     */
    private static RouterInfo roundTrip(RouterInfo ri, ByteArrayOutputStream baos)
                                       throws Exception {
        baos.reset();
        ri.writeBytes(baos);
        RouterInfo round = new RouterInfo();
        round.readBytes(new ByteArrayInputStream(baos.toByteArray()), true);
        return round;
    }

    /**
     * A peer list longer than the five the old check allowed parses, and the
     * hashes survive the round trip.
     *
     * @throws Exception if the keypair generation or the byte output fails
     */
    @Test
    public void aLongPeerListParses() throws Exception {
        Object[] pair = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey) pair[0];
        SigningPrivateKey priv = (SigningPrivateKey) pair[1];
        for (int count : new int[] {0, 1, 5, 6, 20, 100}) {
            RouterInfo ri = signed(count, priv, pub);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            RouterInfo round = roundTrip(ri, baos);
            assertTrue("the signature must verify for " + count + " peers", round.isValid());
            if (count == 0) {
                assertTrue("an empty peer list reads back empty", round.getPeers().isEmpty());
            } else {
                assertEquals(count, round.getPeers().size());
            }
        }
    }

    /**
     * The size cap that bounds the peer list now: 100 hashes still fit inside
     * it, which is why no separate count check is needed.
     *
     * @throws Exception if the keypair generation or the byte output fails
     */
    @Test
    public void aHundredPeersStaysInsideTheSizeCap() throws Exception {
        Object[] pair = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey) pair[0];
        SigningPrivateKey priv = (SigningPrivateKey) pair[1];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        roundTrip(signed(100, priv, pub), baos);
        assertTrue(baos.size() + " bytes must stay under the cap",
                   baos.size() < RouterInfo.MAX_UNCOMPRESSED_SIZE);
    }

    /**
     * A router info signed by a key other than the one it publishes is rejected,
     * and the rejection is named so a transport can tell it from a format error.
     *
     * @throws Exception if the keypair generation or the byte output fails
     */
    @Test
    public void aMismatchedSignatureIsNamed() throws Exception {
        Object[] signer = KeyGenerator.getInstance().generateSigningKeypair();
        Object[] published = KeyGenerator.getInstance().generateSigningKeypair();
        RouterInfo ri = signed(1, (SigningPrivateKey) signer[1], (SigningPublicKey) published[0]);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ri.writeBytes(baos);
        try {
            RouterInfo round = new RouterInfo();
            round.readBytes(new ByteArrayInputStream(baos.toByteArray()), true);
            fail("A router info that does not verify its own signature must be rejected");
        } catch (DataFormatException dfe) {
            assertEquals(RouterInfo.BAD_SIGNATURE_MESSAGE, dfe.getMessage());
        }
    }

    /**
     * A peer list the parse cannot fill — here, one truncated after the count
     * byte — fails on the structure, not on the signature.
     *
     * @throws Exception if the keypair generation or the byte output fails
     */
    @Test
    public void aTruncatedPeerListIsNotASignatureFailure() throws Exception {
        Object[] pair = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey) pair[0];
        SigningPrivateKey priv = (SigningPrivateKey) pair[1];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        signed(10, priv, pub).writeBytes(baos);
        byte[] bytes = baos.toByteArray();
        byte[] truncated = new byte[bytes.length - 40];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        try {
            new RouterInfo().readBytes(new ByteArrayInputStream(truncated), true);
            fail("A router info cut short of its signature must be rejected");
        } catch (DataFormatException dfe) {
            assertTrue("expected a format failure, got: " + dfe.getMessage(),
                       !RouterInfo.BAD_SIGNATURE_MESSAGE.equals(dfe.getMessage()));
        } catch (java.io.IOException ioe) {
            // EOF while reading the structure is the other way this fails
        }
    }
}
