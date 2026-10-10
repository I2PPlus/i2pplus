package org.bouncycastle.pqc.crypto.util;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.security.auth.DestroyFailedException;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.util.Util;

/**
 * Implementation of SecretWithEncapsulation for post-quantum cryptography.
 * Provides secure storage and destruction of encapsulated secrets and session keys.
 */
public class SecretWithEncapsulationImpl
    implements SecretWithEncapsulation
{
    private final AtomicBoolean hasBeenDestroyed = new AtomicBoolean(false);

    private final byte[] sessionKey;
    private final byte[] cipher_text;

    /**
     * Stores the shared key and the encapsulated key.
     * @param sessionKey the shared key agreed by the KEM, zeroed by destroy() and copied on every read
     * @param cipher_text the encapsulated key produced by the encapsulator, zeroed by destroy() and copied on every read
     */
    public SecretWithEncapsulationImpl(byte[] sessionKey, byte[] cipher_text)
    {
        this.sessionKey = sessionKey;
        this.cipher_text = cipher_text;
    }

    /**
     * Returns a copy of the KEM session key.
     * @return a copy of the KEM session key
     * @throws IllegalStateException if the secret has already been destroyed
     */
    public byte[] getSecret()
    {
        byte[] clone = Util.clone(sessionKey);

        checkDestroyed();

        return clone;
    }

    /**
     * Returns a copy of the encapsulated key.
     * @return a copy of the encapsulated key
     * @throws IllegalStateException if the secret has already been destroyed
     */
    public byte[] getEncapsulation()
    {
        byte[] clone = Util.clone(cipher_text);

        checkDestroyed();

        return clone;
    }

    /**
     * Zeroes the session key and encapsulated key.
     */
    public void destroy()
        throws DestroyFailedException
    {
        if (!hasBeenDestroyed.getAndSet(true))
        {
            Arrays.fill(sessionKey, (byte) 0);
            Arrays.fill(cipher_text, (byte) 0);
        }
    }

    /**
     * Reports whether destroy() has already run.
     * @return true if the secret has been destroyed
     */
    public boolean isDestroyed()
    {
        return hasBeenDestroyed.get();
    }

    /**
     * Throws if the secret has already been destroyed.
     */
    void checkDestroyed()
    {
        if (isDestroyed())
        {
            throw new IllegalStateException("data has been destroyed");
        }
    }
}
