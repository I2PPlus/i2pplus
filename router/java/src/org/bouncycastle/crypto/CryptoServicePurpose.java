package org.bouncycastle.crypto;

/**
 * Enumeration of cryptographic service purposes.
 * Defines the intended use case for cryptographic operations.
 */
public enum CryptoServicePurpose
{
    /** Key agreement protocol */
    AGREEMENT,
    /** Encryption operation */
    ENCRYPTION,
    /** Decryption operation */
    DECRYPTION,
    /** Key generation */
    KEYGEN,
    /** Signing operation (for signatures and digests) */
    SIGNING,         
    /** Verification operation */
    VERIFYING,
    /** Authentication operation (for MACs and digests) */
    AUTHENTICATION,  
    /** Verification operation */
    VERIFICATION,
    /** Pseudo-random function */
    PRF,
    /** Any purpose */
    ANY
}
