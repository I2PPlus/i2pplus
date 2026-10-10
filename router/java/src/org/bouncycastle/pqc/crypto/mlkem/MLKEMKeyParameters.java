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
     * MLKEMKeyParameters.
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
     * getParameters.
     * @return the parameter set supplied to the constructor, never null
     */
    public MLKEMParameters getParameters()
    {
        return params;
    }

}
