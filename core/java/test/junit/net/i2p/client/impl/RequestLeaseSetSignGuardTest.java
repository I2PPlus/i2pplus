package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;

import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.Lease;
import net.i2p.data.Lease2;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.TunnelId;
import org.junit.Test;

/**
 * Guards that keep LeaseSet2.writeHeader() from rejecting a client-built
 * set with "LeaseSet expired": positive TTL vs published and current time,
 * and no empty sign.
 */
public class RequestLeaseSetSignGuardTest {

    @Test
    public void positiveExpiryPassesThroughWhenAlreadySafe() {
        long published = 1_700_000_000_000L;
        long leaseEnd = published + 60_000L;
        assertEquals(leaseEnd,
                RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(leaseEnd, published, published));
    }

    @Test
    public void positiveExpiryFloorsStaleEndToPublishedPlusOneSecond() {
        long published = 1_700_000_000_000L;
        long staleEnd = published - 5_000L;
        long floored = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(staleEnd, published, published);
        assertEquals(published + RequestLeaseSetMessageHandler.MIN_LS2_LEASE_TTL_MS, floored);
        assertTrue(floored > published);
    }

    @Test
    public void positiveExpiryLeavesEndUntouchedWhenPublishedUnset() {
        long leaseEnd = 12_345L;
        // A huge current time must not trigger a floor on the LS1 path
        assertEquals(leaseEnd,
                RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(leaseEnd, 0, leaseEnd + 999_999L));
        assertEquals(leaseEnd,
                RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(leaseEnd, -1, leaseEnd + 999_999L));
    }

    @Test
    public void positiveExpiryBumpsEndEqualOrJustAfterPublished() {
        long published = 1_700_000_000_000L;
        long equal = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(published, published, published);
        assertTrue(equal > published);
        long justAfter = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(published + 1, published, published);
        assertTrue(justAfter > published);
    }

    /**
     * Delayed signing: when the clock has moved past the published stamp,
     * the floor is measured from the current time so the set still carries
     * a full second of validity when it is finally signed.
     */
    @Test
    public void positiveExpiryFloorsFromCurrentTimeWhenSignIsDelayed() {
        long published = 1_700_000_000_000L;
        long now = published + 2_500L;
        long staleEnd = published; // stale request, already behind the delayed now
        long floored = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(staleEnd, published, now);
        assertEquals(now + RequestLeaseSetMessageHandler.MIN_LS2_LEASE_TTL_MS, floored);
        assertTrue(floored > now);
    }

    /**
     * A published stamp ahead of the clock (monotonic floor from an earlier
     * sign) must still set the margin, not the smaller current time.
     */
    @Test
    public void positiveExpiryUsesPublishedWhenPublishedIsAheadOfClock() {
        long published = 1_700_000_031_000L;
        long now = published - 10_000L;
        long staleEnd = now - 1_000L;
        long floored = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(staleEnd, published, now);
        assertEquals(published + RequestLeaseSetMessageHandler.MIN_LS2_LEASE_TTL_MS, floored);
    }

    /**
     * writeHeader() computes exp = (_expires/1000) - (published/1000) with
     * independent truncation; the floor must keep that difference at least
     * one second for any sub-second remainder.
     */
    @Test
    public void floorSurvivesHeaderSecondTruncation() {
        long[] publisheds = {
            1_700_000_000_000L, // exact second
            1_700_000_000_001L, // just after a second boundary
            1_700_000_000_999L, // just before the next boundary
        };
        for (long published : publisheds) {
            long floored = RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(
                    published - 1L, published, published);
            long expSeconds = (floored / 1000) - (published / 1000);
            assertTrue("published=" + published + " floored=" + floored,
                    expSeconds >= 1);
        }
    }

    @Test
    public void isSignableRejectsNullAndEmpty() {
        assertFalse(RequestLeaseSetMessageHandler.isSignable(null));
        assertFalse(RequestLeaseSetMessageHandler.isSignable(new LeaseSet()));
        assertFalse(RequestLeaseSetMessageHandler.isSignable(new LeaseSet2()));
    }

    @Test
    public void isSignableAcceptsSetWithOneLease() {
        LeaseSet ls = new LeaseSet();
        ls.addLease(sampleLease());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(ls));

        LeaseSet2 ls2 = new LeaseSet2();
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(3));
        lease.setEndDate(System.currentTimeMillis() + 60_000L);
        ls2.addLease(lease);
        assertTrue(RequestLeaseSetMessageHandler.isSignable(ls2));
    }

    @Test
    public void flooredEndMakesLs2ExpiresStrictlyAfterPublished() {
        long published = 1_700_000_000_000L;
        LeaseSet2 ls2 = new LeaseSet2();
        ls2.setPublished(published);
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(7));
        lease.setEndDate(RequestLeaseSetMessageHandler.ensurePositiveLs2Expiry(
                published - 5_000L, published, published));
        ls2.addLease(lease);
        assertTrue(ls2.getExpires() > ls2.getPublished());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(ls2));
    }

    /**
     * A pre-decryption EncryptedLeaseSet reports 0 leases but is still
     * signable: its real leases are only visible after decryption, and a
     * non-zero expiry proves leases were added.
     */
    @Test
    public void isSignableAcceptsEncryptedLeaseSetBeforeDecryption() {
        EncryptedLeaseSet els2 = new EncryptedLeaseSet();
        assertFalse(RequestLeaseSetMessageHandler.isSignable(els2));

        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(8));
        lease.setEndDate(System.currentTimeMillis() + 60_000L);
        els2.addLease(lease);

        assertEquals(0, els2.getLeaseCount());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(els2));
    }

    /**
     * Full ELS2 wire round-trip: build with real keys, sign (inner with the
     * unblinded key, then encrypt and sign the outer with the blinded key),
     * serialize, parse, rebind the destination, and verify both signatures
     * plus the decrypted lease count.
     */
    @Test
    public void encryptedLeaseSetSignsSerializesAndVerifies() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance();
        KeyPair pki = kg.generatePKIKeys(EncType.ECIES_X25519);
        SimpleDataStructure[] signingKeys = kg.generateSigningKeys(SigType.EdDSA_SHA512_Ed25519);
        SigningPublicKey spk = (SigningPublicKey) signingKeys[0];
        SigningPrivateKey spl = (SigningPrivateKey) signingKeys[1];
        Destination dest = wireValidDestination(spk);

        // the destination itself must survive the wire; an ECIES key with a
        // null certificate cannot be read back by PublicKey.create()
        ByteArrayOutputStream dbaos = new ByteArrayOutputStream();
        dest.writeBytes(dbaos);
        Destination wireDest = Destination.create(new ByteArrayInputStream(dbaos.toByteArray()));
        assertEquals(dest.getPublicKey(), wireDest.getPublicKey());
        assertEquals(dest.getSigningPublicKey(), wireDest.getSigningPublicKey());
        assertEquals(dest.getCertificate(), wireDest.getCertificate());

        EncryptedLeaseSet els2 = new EncryptedLeaseSet();
        long published = ((System.currentTimeMillis() + 500) / 1000) * 1000;
        els2.setPublished(published);
        Lease2 lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(11));
        lease.setEndDate(published + 60_000L);
        els2.addLease(lease);
        els2.addEncryptionKey(pki.getPublic());
        els2.setDestination(dest);
        els2.sign(spl);

        assertEquals(DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2, els2.getType());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(els2));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        els2.writeBytes(baos);
        byte[] wire = baos.toByteArray();
        assertTrue(wire.length > 0);

        EncryptedLeaseSet parsed = new EncryptedLeaseSet();
        parsed.readBytes(new ByteArrayInputStream(wire));
        assertEquals(DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2, parsed.getType());
        assertTrue(RequestLeaseSetMessageHandler.isSignable(parsed));
        parsed.setDestination(wireDest);
        assertTrue(parsed.verifySignature());
        assertEquals(1, parsed.getLeaseCount());
        assertEquals(Hash.FAKE_HASH, parsed.getLease(0).getGateway());
    }

    /**
     * A destination in the production wire layout, as built by
     * I2PClientImpl.createDestination(): a 256-byte public key, a KeyCertificate
     * derived from the signing key, and the padding that completes the
     * 128-byte signing-key field. The public encryption key is unused here;
     * only the signing key material matters for the subcredential and the
     * ELS2 signatures.
     *
     * @param spk the destination's signing public key
     * @return a destination that round-trips through Destination.create()
     */
    private static Destination wireValidDestination(SigningPublicKey spk) {
        Destination dest = new Destination();
        SecureRandom rnd = new SecureRandom();
        byte[] pk = new byte[PublicKey.KEYSIZE_BYTES];
        rnd.nextBytes(pk);
        dest.setPublicKey(new PublicKey(pk));
        dest.setSigningPublicKey(spk);
        byte[] pad = new byte[128 - spk.getType().getPubkeyLen()];
        rnd.nextBytes(pad);
        dest.setPadding(pad);
        dest.setCertificate(new KeyCertificate(spk));
        return dest;
    }

    private static Lease sampleLease() {
        Lease lease = new Lease2();
        lease.setGateway(Hash.FAKE_HASH);
        lease.setTunnelId(new TunnelId(1));
        lease.setEndDate(System.currentTimeMillis() + 60_000L);
        return lease;
    }
}
