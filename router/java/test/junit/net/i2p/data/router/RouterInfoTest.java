package net.i2p.data.router;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataStructure;
import net.i2p.data.Hash;
import net.i2p.data.HashTest;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.StructureTest;
import net.i2p.util.Log;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterInfoTest extends StructureTest {
    private static final Log _log = new Log(RouterInfoTest.class);

    @SuppressWarnings("deprecation")
    public DataStructure createDataStructure() throws DataFormatException {
        RouterInfo info = new RouterInfo();
        HashSet<RouterAddress> addresses = new HashSet<RouterAddress>();
        DataStructure structure = (new RouterAddressTest()).createDataStructure();
        addresses.add((RouterAddress) structure);
        info.setAddresses(addresses);

        PublicKey pubKey = null;
        SigningPublicKey signingPubKey = null;
        PrivateKey privKey = null;
        SigningPrivateKey signingPrivKey = null;

        Object[] obj = KeyGenerator.getInstance().generatePKIKeypair();
        pubKey = (PublicKey) obj[0];
        privKey = (PrivateKey) obj[1];
        obj = KeyGenerator.getInstance().generateSigningKeypair();
        signingPubKey = (SigningPublicKey) obj[0];
        signingPrivKey = (SigningPrivateKey) obj[1];

        _log.debug("SigningPublicKey: " + signingPubKey);
        _log.debug("SigningPrivateKey: " + signingPrivKey);

        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(pubKey);
        ident.setSigningPublicKey(signingPubKey);

        info.setIdentity(ident);

        Properties options = new Properties();
        for (int i = 0; i < 16; i++) {
            options.setProperty("option." + i, "12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890$:." + i);
        }
        options.setProperty("netConnectionSpeed", "OC12");
        info.setOptions(options);

        HashSet<Hash> peers = new HashSet<Hash>();
        structure = (new HashTest()).createDataStructure();
        peers.add((Hash) structure);
        info.setPeers(peers);
        info.setPublished(System.currentTimeMillis());

        // info.setVersion(69);

        info.sign(signingPrivKey);

        return info;
    }

    public DataStructure createStructureToRead() {
        return new RouterInfo();
    }

    @Test
    public void testSignatureVerify() throws Exception {
        RouterInfo info = (RouterInfo) createDataStructure();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        info.writeBytes(baos);

        RouterInfo round = new RouterInfo();
        round.readBytes(new ByteArrayInputStream(baos.toByteArray()), true);
        assertTrue("signed RouterInfo should verify", round.isValid());

        // tamper a byte in the middle and expect rejection
        byte[] tampered = baos.toByteArray();
        tampered[tampered.length / 2] ^= 0x01;
        RouterInfo bad = new RouterInfo();
        try {
            bad.readBytes(new ByteArrayInputStream(tampered), true);
            fail("tampered RouterInfo should not verify");
        } catch (net.i2p.data.DataFormatException dfe) {
            // expected
        } catch (java.io.IOException ioe) {
            // expected, corrupted structure may hit EOF
        }
    }
}
