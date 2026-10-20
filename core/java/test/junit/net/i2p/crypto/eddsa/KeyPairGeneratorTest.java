package net.i2p.crypto.eddsa;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.security.KeyPair;
import java.security.MessageDigest;

import net.i2p.crypto.eddsa.spec.EdDSAGenParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

import org.junit.Test;

/**
 * KeyPairGenerator behavior pins, notably the JCA contract that a null
 * SecureRandom means "use an implementation default" rather than NPE.
 */
public class KeyPairGeneratorTest {

    @Test
    public void nullRandomFallsBackToDefault() throws Exception {
        KeyPairGenerator kpg = new KeyPairGenerator();
        kpg.initialize(new EdDSAGenParameterSpec(EdDSANamedCurveTable.ED_25519), null);
        KeyPair kp = kpg.generateKeyPair();

        // The generated pair must actually work end to end.
        byte[] msg = "null random generator".getBytes();
        String hash = ((EdDSAPublicKey) kp.getPublic()).getParams().getHashAlgorithm();
        EdDSAEngine sgr = new EdDSAEngine(MessageDigest.getInstance(hash));
        sgr.initSign(kp.getPrivate());
        byte[] sig = sgr.signOneShot(msg);
        sgr.initVerify(kp.getPublic());
        assertThat("generated key pair must sign/verify", sgr.verifyOneShot(msg, sig), is(true));
    }
}
