package net.i2p.crypto;

import java.security.spec.AlgorithmParameterSpec;
import net.i2p.data.DataHelper;

/**
 * Defines the context for signing with personalized hashes.
 * See proposal 148.
 *
 * @since 0.9.40
 */
public enum SigContext {

    SC_NONE     (null),
    SC_DATAGRAM ("sign_datagramI2P"),
    SC_I2CP     ("I2CP_SessionConf"),
    SC_NETDB    ("network_database"),
    SC_NTCP     ("NTCP_1_handshake"),
    SC_SSU      ("SSUHandshakeSign"),
    SC_STREAMING("streaming_i2psig"),
    SC_SU3      ("i2pSU3FileFormat"),
    SC_TEST     ("test1234test5678"),

    ;

    private final SigContextSpec spec;

    /**
     * The 16 bytes for this type, or null for none
     */
    SigContext(String p) {
        spec = new SigContextSpec(p);
    }

    /**
     * The AlgorithmParameterSpec.
     * Pass this as an argument in setParameter()
     * to the Blake sign/verify engines.
     */
    public SigContextSpec getSpec() { return spec; }

    /**
     * Algorithm parameter specification for personalized hash contexts in digital signatures.
     *
     * This class implements {@link AlgorithmParameterSpec} to provide context-specific
     * data for cryptographic signature operations using personalized hash functions.
     * The context data is used as domain separation to prevent signature replay attacks
     * across different I2P protocols and data types.
     *
     * <p><strong>Purpose and Security:</strong>
     * <ul>
     *   <li>Domain separation between different I2P protocols</li>
     *   <li>Prevention of cross-protocol signature replay attacks</li>
     *   <li>Implementation of Proposal 148 for enhanced signature security</li>
     *   <li>Context-specific hash personalization for BLAKE signature engines</li>
     * </ul>
     *
     * <p><strong>Format and Validation:</strong>
     * <ul>
     *   <li>Context data must be exactly 16 ASCII bytes</li>
     *   <li>Null context indicates no personalization (SC_NONE)</li>
     *   <li>Invalid lengths throw {@link IllegalArgumentException}</li>
     *   <li>Data is stored as raw bytes for cryptographic operations</li>
     * </ul>
     *
     * <p><strong>Usage:</strong>
     * <pre>
     * // Get context specification for datagram signatures
     * SigContextSpec spec = SigContext.SC_DATAGRAM.getSpec();
     * 
     * // Apply to signature engine
     * signatureEngine.setParameter(spec);
     * </pre>
     *
     * <p><strong>Supported Contexts:</strong>
     * <ul>
     *   <li>SC_DATAGRAM - Datagram protocol signatures</li>
     *   <li>SC_I2CP - I2CP session configuration</li>
     *   <li>SC_NETDB - Network database entries</li>
     *   <li>SC_NTCP - NTCP handshake signatures</li>
     *   <li>SC_SSU - SSU handshake signatures</li>
     *   <li>SC_STREAMING - Streaming protocol signatures</li>
     *   <li>SC_SU3 - SU3 file format signatures</li>
     *   <li>SC_TEST - Test context for development</li>
     * </ul>
     *
     * <p><strong>Thread Safety:</strong> This class is immutable and thread-safe.
     * The returned byte array should not be modified by callers.
     *
     * @see AlgorithmParameterSpec
     * @see SigContext
     * @since 0.9.40
     */
    public static class SigContextSpec implements AlgorithmParameterSpec {
        private final byte[] b;

        /**
         * Creates a signature context specification from the given context string.
         *
         * @param context the ASCII context string, must be exactly 16 characters,
         *               or null for no context (SC_NONE)
         * @throws IllegalArgumentException if the context string is not exactly 16 characters
         */
        public SigContextSpec(String context) {
            if (context != null) {
                b = DataHelper.getASCII(context);
                if (b.length != 16)
                    throw new IllegalArgumentException("Context string must be exactly 16 characters, got: " + b.length);
            } else {
                b = null;
            }
        }

        /**
         * Returns the raw context data for cryptographic operations.
         *
         * The returned array is the internal storage and should not be modified.
         *
         * @return the 16-byte context data as a byte array, or null if no context is set
         */
        public byte[] getData() { return b; }
    }
}
