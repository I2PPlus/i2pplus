package net.i2p.crypto.eddsa;

import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;

/**
 * Common interface for all EdDSA keys.
 *
 * @author str4d
 * @since 0.9.15
 */
public interface EdDSAKey {
    /**
     * The reported key algorithm for all EdDSA keys
     *
     * @since 0.9.36
     */
    String KEY_ALGORITHM = "EdDSA";

    /**
     * The EdDSA domain parameters for the key.
     *
     * @return A parameter specification representing the EdDSA domain
     *         parameters for the key.
     */
    EdDSAParameterSpec getParams();
}
