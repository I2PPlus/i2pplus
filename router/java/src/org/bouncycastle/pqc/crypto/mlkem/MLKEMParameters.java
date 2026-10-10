package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.pqc.crypto.KEMParameters;

/**
 * Parameters for ML-KEM (Module-Lattice Key Encapsulation Mechanism) algorithms.
 * Defines configuration for different ML-KEM security levels and variants.
 */
public class MLKEMParameters
    implements KEMParameters
{
    /**
     * ml_kem_512.
     */
    public static final MLKEMParameters ml_kem_512 = new MLKEMParameters("ML-KEM-512", 2, 256);
    /**
     * ml_kem_768.
     */
    public static final MLKEMParameters ml_kem_768 = new MLKEMParameters("ML-KEM-768", 3, 256);
    /**
     * ml_kem_1024.
     */
    public static final MLKEMParameters ml_kem_1024 = new MLKEMParameters("ML-KEM-1024", 4, 256);

    private final String name;
    private final int k;
    private final int sessionKeySize;

    private MLKEMParameters(String name, int k, int sessionKeySize)
    {
        this.name = name;
        this.k = k;
        this.sessionKeySize = sessionKeySize;
    }

    /**
     * getName.
     *
     * @return the standardized name, for example ML-KEM-1024
     */
    public String getName()
    {
        return name;
    }

    /**
     * getEngine.
     *
     * @return a new engine sized for this parameter set's K value
     */
    public MLKEMEngine getEngine()
    {
        return new MLKEMEngine(k);
    }

    /**
     * getSessionKeySize.
     *
     * @return the size of the encapsulated shared secret in bytes
     */
    public int getSessionKeySize()
    {
        return sessionKeySize;
    }
}
