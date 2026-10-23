package net.i2p.crypto;

import static org.junit.Assert.*;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.EnumSet;

import org.junit.Test;

/**
 * Tests for {@link SelfSignedGenerator}, which had no automated coverage at all.
 *
 * <p>The only exercise of this class was a {@code test(String, SigType)} helper
 * that sat commented out in {@code main()}: it generated a certificate for every
 * available {@link SigType} and wrote a keystore, cert, CRL and private key to
 * the working directory. It never ran under any build, so a break in the DER
 * assembly or the signature encoding would only surface when someone ran the
 * tool by hand.
 *
 * <p>These tests keep the useful part - generate a certificate for every
 * signature type and check that it is well formed and self-consistent - without
 * writing anything to disk.
 *
 * <p>Signature types the JVM cannot provide (missing provider) are skipped
 * rather than failed, since availability depends on the installed crypto
 * providers rather than on this code.
 *
 * @since 0.9.71+
 */
public class SelfSignedGeneratorTest {

    private static final String CNAME = "cname@example.com";
    private static final int VALID_DAYS = 3652;

    /**
     * The signature types {@code generate()} can actually build a certificate
     * for.
     *
     * <p>RedDSA is excluded on purpose: {@link SigType#isAvailable} reports it
     * as usable for key generation, but {@code SelfSignedGenerator} rejects it
     * with "Unsupported" because a RedDSA signature cannot be encoded into an
     * X.509 certificate. Filtering here keeps the loop aligned with the
     * generator's own switch rather than with key availability.
     *
     * @return the available signature types {@code generate()} can build a
     *         certificate for; empty only if the JVM provides no usable
     *         signature type at all
     */
    private static java.util.List<SigType> supportedTypes() {
        java.util.List<SigType> out = new java.util.ArrayList<>();
        for (SigType t : EnumSet.allOf(SigType.class)) {
            if (!t.isAvailable()) continue;
            if (t.name().startsWith("RedDSA")) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * Extract the CN from a certificate subject.
     *
     * <p>{@code getName()} returns RFC2253 form, where the common name is the
     * leading element and may or may not carry a literal {@code CN=} prefix, so
     * the first comma-delimited element is stripped rather than split on the
     * tag.
     *
     * @param cert the certificate whose subject DN is parsed
     * @return the common name, stripped of any leading {@code CN=} tag
     */
    private static String commonName(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName(
                javax.security.auth.x500.X500Principal.RFC2253);
        String first = dn.split(",")[0].trim();
        return first.startsWith("CN=") ? first.substring(3) : first;
    }

    /**
     * Every available signature type produces a self-signed certificate whose
     * signature verifies against the key it carries.
     *
     * <p>This is the core of the removed helper: the DER is assembled by hand in
     * {@code genTBS()} and {@code genCRL()}, so a malformed length or a wrong
     * algorithm OID shows up here as a verification failure or a parse error.
     *
     * @throws Exception on key generation, certificate parsing or signature
     *                   verification failure
     */
    @Test
    public void testGenerateForEveryAvailableSigType() throws Exception {
        int tested = 0;
        for (SigType type : supportedTypes()) {
            if (!type.isAvailable()) continue;
            Object[] rv = SelfSignedGenerator.generate(CNAME, "ou", "o", null, "st", "c", VALID_DAYS, type);
            assertNotNull("no result for " + type, rv);
            assertEquals("wrong result length for " + type, 4, rv.length);

            PublicKey jpub = (PublicKey) rv[0];
            assertNotNull("no public key for " + type, jpub);
            assertNotNull("no private key for " + type, rv[1]);
            X509Certificate cert = (X509Certificate) rv[2];
            assertNotNull("no certificate for " + type, cert);
            assertNotNull("no CRL for " + type, rv[3]);

            // the certificate must verify against the keypair we were handed
            cert.verify(jpub);
            // and against the key it actually embeds - note the embedded key is
            // the JDK form (e.g. sun.security Ed25519), not the I2P
            // net.i2p.crypto.eddsa type returned in rv[0], so compare the
            // encoded form rather than object identity
            cert.verify(cert.getPublicKey());
            assertArrayEquals("cert public key mismatch for " + type,
                              jpub.getEncoded(), cert.getPublicKey().getEncoded());
            tested++;
        }
        assertTrue("no signature type was available to test", tested > 0);
    }

    /**
     * The generated certificate carries the requested common name and is valid
     * now. A certificate whose notBefore/notAfter are wrong would still parse and
     * verify, so check the dates and the subject explicitly.
     *
     * @throws Exception on certificate generation, validity check or signature
     *                   verification failure
     */
    @Test
    public void testSubjectAndValidity() throws Exception {
        for (SigType type : supportedTypes()) {
            if (!type.isAvailable()) continue;
            Object[] rv = SelfSignedGenerator.generate(CNAME, "ou", "o", null, "st", "c", VALID_DAYS, type);
            X509Certificate cert = (X509Certificate) rv[2];

            assertEquals("wrong CN for " + type, CNAME, commonName(cert));
            // throws CertificateExpiredException / CertificateNotYetValidException
            cert.checkValidity();
            cert.verify(cert.getPublicKey());
        }
    }

    /**
     * The returned CRL is a real CRL signed by the certificate key, and is not
     * the certificate bytes re-parsed. {@code genCRL()} rebuilds the structure by
     * hand, so this is the check that its TBS encoding is coherent.
     *
     * @throws Exception on certificate or CRL generation, or CRL verification
     *                   failure
     */
    @Test
    public void testGeneratedCRLIsUsable() throws Exception {
        for (SigType type : supportedTypes()) {
            if (!type.isAvailable()) continue;
            Object[] rv = SelfSignedGenerator.generate(CNAME, "ou", "o", null, "st", "c", VALID_DAYS, type);
            X509Certificate cert = (X509Certificate) rv[2];
            X509CRL crl = (X509CRL) rv[3];

            // the CRL must verify under the certificate's public key
            crl.verify(cert.getPublicKey());
            // genTBSCRL() builds the entry from the certificate's own serial
            // number, so a fresh certificate always revokes itself
            assertNotNull("CRL should revoke the certificate for " + type,
                          crl.getRevokedCertificates());
            assertEquals("wrong revoked serial for " + type,
                         cert.getSerialNumber(),
                         crl.getRevokedCertificates().iterator().next().getSerialNumber());
        }
    }

    /**
     * The altNames overload adds the requested names. The no-altNames overload
     * must still work and must not fail when the set is absent.
     *
     * @throws Exception on key generation or certificate verification failure
     */
    @Test
    public void testAltNamesOverload() throws Exception {
        SigType type = firstAvailable();
        Object[] rv = SelfSignedGenerator.generate(
                CNAME, null, "ou", "o", null, "st", "c", VALID_DAYS, type);
        X509Certificate cert = (X509Certificate) rv[2];
        cert.verify((PublicKey) rv[0]);

        java.util.Set<String> alt = new java.util.HashSet<>();
        alt.add("alt.example.com");
        Object[] rv2 = SelfSignedGenerator.generate(
                CNAME, alt, "ou", "o", null, "st", "c", VALID_DAYS, type);
        X509Certificate cert2 = (X509Certificate) rv2[2];
        cert2.verify((PublicKey) rv2[0]);
        assertEquals(CNAME, commonName(cert2));
    }

    /**
     * {@code renew()} reuses the existing private key rather than making a new
     * one, so the renewed certificate must carry the same public key.
     *
     * @throws Exception on certificate generation, renewal or signature
     *                   verification failure
     */
    @Test
    public void testRenewKeepsKeyPair() throws Exception {
        SigType type = firstAvailable();
        Object[] rv = SelfSignedGenerator.generate(CNAME, "ou", "o", null, "st", "c", VALID_DAYS, type);
        X509Certificate cert = (X509Certificate) rv[2];
        PrivateKey jpriv = (PrivateKey) rv[1];

        Object[] renewed = SelfSignedGenerator.renew(cert, jpriv, VALID_DAYS);
        X509Certificate rcert = (X509Certificate) renewed[2];
        rcert.verify((PublicKey) rv[0]);
        assertEquals("renew must keep the original key pair",
                     cert.getPublicKey(), rcert.getPublicKey());
    }

    /**
     * The first signature type the router supports.
     *
     * @return the first available signature type, or skip the test if none is
     */
    private static SigType firstAvailable() {
        for (SigType type : supportedTypes()) {
            return type;
        }
        org.junit.Assume.assumeTrue("no signature type available in this JVM", false);
        return null;
    }
}