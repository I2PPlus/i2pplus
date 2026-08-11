package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Extend Destination with methods to verify its Certificate.
 * The router does not check Certificates, it doesn't care.
 * Apps however (particularly addressbook) may wish to enforce various
 * cert content, format, and policies.
 * This class is written such that apps may extend it to
 * create their own policies.
 *
 * @author zzz
 */
public class VerifiedDestination extends Destination {

    /** Creates a new VerifiedDestination with default values. */
    public VerifiedDestination() {
        super();
    }

    /**
     * Alternative constructor which takes a base64 string representation
     *
     * @param s a Base64 representation of the destination, as (eg) is used in hosts.txt
     */
    public VerifiedDestination(String s) throws DataFormatException {
        this();
        fromBase64(s);
    }

    /**
     * Create from an existing Dest
     *
     * @param d must be non-null
     */
    public VerifiedDestination(Destination d) throws DataFormatException {
        this(d.toBase64());
    }

    /**
     * Verify the certificate.
     *
     * @param allowNone If true, allow a NULL or HIDDEN certificate.
     * @return true if the certificate is valid
     */
    public boolean verifyCert(boolean allowNone) {
        if (_publicKey == null || _signingKey == null || _certificate == null) return false;
        switch (_certificate.getCertificateType()) {
            case Certificate.CERTIFICATE_TYPE_NULL:
            case Certificate.CERTIFICATE_TYPE_HIDDEN:
                return allowNone;

            case Certificate.CERTIFICATE_TYPE_SIGNED:
                return verifySignedCert();
        }
        return verifyUnknownCert();
    }

    /** Defaults for Signed Certs */
    public static final int CERTIFICATE_LENGTH_SIGNED = Signature.SIGNATURE_BYTES;

    /** Length of a signed certificate including the signer hash. */
    public static final int CERTIFICATE_LENGTH_SIGNED_WITH_HASH = Signature.SIGNATURE_BYTES + Hash.HASH_LENGTH;

    /**
     *  Signed Certs are signed by a 3rd-party Destination.
     *  They can be used for a second-level domain, for example, to sign the
     *  Destination for a third-level domain. Or for a central authority
     *  to approve a destination.
     *
     *  We define a Signed Certificate as follows:
     *     - length: Either 44 or 72 bytes
     *     - contents:
     *      1: a 44 byte Signature
     *      2 (optional): a 32 byte Hash of the signing Destination
     *        This can be a hint to the verification process to help find
     *        the identity and keys of the signing Destination.
     *     Data which is signed: The first 384 bytes of the Destination
     *     (i.e. the Public Key and Signing Public Key, WITHOUT the Certificate)
     *
     *  It is not appropriate to enforce a particular delegation scheme here.
     *  The application will need to apply additional steps to select
     *  an appropriate signing Destination and verify the signature.
     *
     *  See PrivateKeyFile.verifySignature() for sample verification code.
     *
     *  @return true if the signed certificate payload length is valid
     */
    protected boolean verifySignedCert() {
        return _certificate.getPayload() != null
                && (_certificate.getPayload().length == CERTIFICATE_LENGTH_SIGNED
                        || _certificate.getPayload().length == CERTIFICATE_LENGTH_SIGNED_WITH_HASH);
    }

    /**
     *  Reject all unknown certs
     *
     *  @return false always
     */
    protected boolean verifyUnknownCert() {
        return false;
    }

    /** Returns a string representation including verification status. */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128); // NOPMD - AvoidUnnecessaryStringBuilderCreation
        buf.append(super.toString());
        buf.append("\n\tVerified Certificate? ").append(verifyCert(true));
        return buf.toString();
    }
}
