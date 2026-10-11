package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;

/**
 * Base key parameters for ML-KEM (Module-Lattice Key Encapsulation Mechanism).
 * Provides common functionality for both public and private key parameters.
 */
public class MLKEMKeyParameters
    extends AsymmetricKeyParameter
{
    private MLKEMParameters params;

    /**
     * Wraps the ML-KEM parameter set alongside the public/private flag.
     * @param isPrivate passed to the AsymmetricKeyParameter superclass to mark the key private (true) or public (false)
     * @param params the ML-KEM parameter set carrying the scheme's degree and key sizes
     */
    public MLKEMKeyParameters(
        boolean isPrivate,
        MLKEMParameters params)
    {
        super(isPrivate);
        this.params = params;
    }

    /**
     * Returns the ML-KEM parameter set this key was created with.
     * @return the parameter set supplied to the constructor, never null
     */
    public MLKEMParameters getParameters()
    {
        return params;
    }

}
