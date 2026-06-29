package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Test harness for loading / storing Lease objects
 *
 * @author jrandom
 */
public class LeaseSetTest extends StructureTest {

    @Override
    public DataStructure createDataStructure() throws DataFormatException {
        LeaseSet leaseSet = new LeaseSet();
        leaseSet.setDestination((Destination) (new DestinationTest()).createDataStructure());
        leaseSet.setEncryptionKey((PublicKey) (new PublicKeyTest()).createDataStructure());
        leaseSet.setSignature((Signature) (new SignatureTest()).createDataStructure());
        leaseSet.setSigningKey((SigningPublicKey) (new SigningPublicKeyTest()).createDataStructure());
        // leaseSet.setVersion(42L);
        return leaseSet;
    }

    @Override
    public DataStructure createStructureToRead() {
        return new LeaseSet();
    }

    @Test
    public void failsToGetLeaseWhenEmpty() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // should contain no leases now.
        try {
            subj.getLease(0);
            fail("exception not thrown");
        } catch (IndexOutOfBoundsException expected) {
        }
    }

    @Test
    public void failsToGetInvalidLease() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // this shouldn't work either
        try {
            subj.getLease(-1);
            fail("exception not thrown");
        } catch (IndexOutOfBoundsException expected) {
        }
    }

    @Test
    public void testAddLeaseNull() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // now add an null lease
        try {
            subj.addLease(null);
            fail("exception not thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("Error:! Null lease!", expected.getMessage());
        }
    }

    @Test
    public void testAddLeaseInvalid() {
        // create test subject
        LeaseSet subj = new LeaseSet();

        // try to add completely invalid lease(ie. no data)
        try {
            subj.addLease(new Lease());
            fail("exception not thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("Error: Lease has no gateway!", expected.getMessage());
        }
    }

    @Test
    public void testGetLeaseCountEmpty() {
        LeaseSet subj = new LeaseSet();
        assertEquals(0, subj.getLeaseCount());
    }

    @Test
    public void testVerifySignatureWithNullKey() {
        // verifySignature(null) should delegate to no-arg verifySignature()
        LeaseSet subj = new LeaseSet();
        // No signature set, so should return false
        assertFalse(subj.verifySignature(null));
    }

    @Test
    public void testVerifySignatureWithNoSignature() {
        LeaseSet subj = new LeaseSet();
        // Create an EdDSA signing key
        SigningPublicKey spk = new SigningPublicKey(net.i2p.crypto.SigType.EdDSA_SHA512_Ed25519);
        // No signature set on the LeaseSet, should return false
        assertFalse(subj.verifySignature(spk));
    }

    @Test
    public void testVerifySignatureWithRSAKey() {
        LeaseSet subj = new LeaseSet();
        // RSA keys should always return false (not supported)
        SigningPublicKey spk = new SigningPublicKey(net.i2p.crypto.SigType.RSA_SHA256_2048);
        assertFalse(subj.verifySignature(spk));
    }
}
