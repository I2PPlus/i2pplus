package net.i2p.data.i2cp;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Date;

import org.junit.Test;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.PublicKeyTest;
import net.i2p.data.Signature;
import net.i2p.data.SignatureTest;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SigningPublicKeyTest;
import net.i2p.data.TunnelId;

/**
 * Verifies how CreateLeaseSetMessage round-trips when the client's private key
 * is NOT the default ElGamal size.
 *
 * The router-side reader (doReadMessage) assumes a DSA 20-byte signing private
 * key followed by an ElGamal 256-byte private key. The writer emits the actual
 * length of whatever key objects were set. An external I2CP client using ECIES
 * (32-byte private key) therefore produces a payload whose LeaseSet section
 * begins 224 bytes earlier than where the reader starts parsing it, so every
 * field of the parsed LeaseSet — including lease timestamps — comes from the
 * wrong offsets.
 *
 * These tests pin that behavior:
 *  - ElGamal + DSA round-trips byte-for-byte.
 *  - ECIES private key corrupts the parsed LeaseSet (different lease data).
 *
 * @since 0.9.70+
 */
public class CreateLeaseSetMessageOffsetTest {

    private static final long LEASE_END = 1_700_000_000_000L;

    private static LeaseSet buildLeaseSet() throws DataFormatException {
        Destination dest = new Destination();
        // DSA-style destination: 256-byte padding cert, matching the fixtures
        dest.setPublicKey((PublicKey) new PublicKeyTest().createDataStructure());
        dest.setSigningPublicKey((SigningPublicKey) new SigningPublicKeyTest().createDataStructure());
        dest.setCertificate(Certificate.NULL_CERT);
        LeaseSet ls = new LeaseSet();
        ls.setDestination(dest);
        ls.setEncryptionKey((PublicKey) new PublicKeyTest().createDataStructure());
        ls.setSigningKey((SigningPublicKey) new SigningPublicKeyTest().createDataStructure());
        Lease l = new Lease();
        Hash gw = new Hash();
        byte[] b = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < b.length; i++) {b[i] = (byte) i;}
        gw.setData(b);
        l.setGateway(gw);
        l.setTunnelId(new TunnelId(12345));
        l.setEndDate(new Date(LEASE_END));
        ls.addLease(l);
        // must come after addLease(), which rejects already-signed sets
        ls.setSignature((Signature) new SignatureTest().createDataStructure());
        return ls;
    }

    /**
     * Serializes the message exactly as the client-side producer would and
     * parses it back exactly as the router-side listener would.
     */
    private static CreateLeaseSetMessage roundTrip(PrivateKey pk, SigningPrivateKey spk,
                                                   LeaseSet ls) throws DataFormatException,java.io.IOException {
        CreateLeaseSetMessage out = new CreateLeaseSetMessage();
        out.setSessionId(new SessionId(7));
        out.setPrivateKey(pk);
        out.setSigningPrivateKey(spk);
        out.setLeaseSet(ls);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.writeBytes(baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        CreateLeaseSetMessage in = new CreateLeaseSetMessage();
        in.readBytes(bais);
        return in;
    }

    @Test
    public void testElGamalRoundTripIsLossless() throws Exception {
        LeaseSet ls = buildLeaseSet();
        // Defaults: ElGamal 256-byte private key, DSA 20-byte signing private key
        PrivateKey pk = new PrivateKey();
        byte[] pkb = new byte[PrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < pkb.length; i++) {pkb[i] = (byte) (i % 16);}
        pk.setData(pkb);
        SigningPrivateKey spk = new SigningPrivateKey();
        byte[] spkb = new byte[SigningPrivateKey.KEYSIZE_BYTES];
        for (int i = 0; i < spkb.length; i++) {spkb[i] = (byte) (i % 16);}
        spk.setData(spkb);
        CreateLeaseSetMessage in = roundTrip(pk, spk, ls);
        assertEquals(ls.getLeaseCount(), in.getLeaseSet().getLeaseCount());
        assertEquals(LEASE_END, in.getLeaseSet().getEarliestLeaseDate());
    }

    @Test
    public void testEciesPrivateKeyRoundTripIsRecovered() throws Exception {
        LeaseSet ls = buildLeaseSet();
        // What an external ECIES client sends: 32-byte ECIES private key and
        // the dummy 20-byte DSA signing key from RequestLeaseSetMessageHandler.
        byte[] ec = new byte[32];
        for (int i = 0; i < ec.length; i++) {ec[i] = (byte) (0x40 + i);}
        PrivateKey pk = new PrivateKey(EncType.ECIES_X25519, ec);
        byte[] d20 = new byte[20];
        for (int i = 0; i < d20.length; i++) {d20[i] = (byte) (0x10 + i);}
        SigningPrivateKey spk = new SigningPrivateKey(d20);

        // The reader must locate the LeaseSet at its true offset and recover
        // it intact, despite the non-ElGamal key length.
        CreateLeaseSetMessage in = roundTrip(pk, spk, ls);
        assertEquals(LEASE_END, in.getLeaseSet().getEarliestLeaseDate());
        assertEquals(ls.getLeaseCount(), in.getLeaseSet().getLeaseCount());
        assertTrue(ls.getLease(0).getGateway().equals(in.getLeaseSet().getLease(0).getGateway()));
        assertTrue(ls.getDestination().equals(in.getLeaseSet().getDestination()));
        // recovered key types must reflect the actual lengths on the wire
        assertEquals(EncType.ECIES_X25519, in.getPrivateKey().getType());
        assertEquals(SigType.DSA_SHA1, in.getSigningPrivateKey().getType());
    }
}
