package net.i2p.crypto.elgamal;

import javax.crypto.interfaces.DHKey;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;

/**
 * Base interface for ElGamal cryptographic keys.
 * Defines common methods for ElGamal public and private keys.
 */
public interface ElGamalKey
    extends DHKey
{
    public ElGamalParameterSpec getParameters();
}
