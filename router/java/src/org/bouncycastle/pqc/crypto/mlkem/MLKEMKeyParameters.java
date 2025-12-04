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

    public MLKEMKeyParameters(
        boolean isPrivate,
        MLKEMParameters params)
    {
        super(isPrivate);
        this.params = params;
    }

    public MLKEMParameters getParameters()
    {
        return params;
    }

}
