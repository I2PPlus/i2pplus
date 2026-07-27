package org.bouncycastle.crypto;

/** Foundation class for checked exceptions thrown by the crypto packages. */
public class CryptoException
    extends Exception
{
    /** Cause */
    private Throwable cause;

    /** Base constructor. */
    public CryptoException()
    {
    }

    /**
     * Constructs a CryptoException with the given message.
     *
     * @param message the message to be carried with the exception
     */
    public CryptoException(
        String  message)
    {
        super(message);
    }

    /**
     * Constructs a CryptoException with the given message and cause.
     *
     * @param message message describing exception
     * @param cause the throwable that was the underlying cause
     */
    public CryptoException(
        String  message,
        Throwable cause)
    {
        super(message);

        this.cause = cause;
    }

    /** @return the underlying cause */
    public Throwable getCause()
    {
        return cause;
    }
}
