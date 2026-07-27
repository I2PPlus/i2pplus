package org.bouncycastle.crypto.params;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.util.Util;

/**
 * Cipher parameters with associated context data.
 * Wraps cipher parameters with additional context information for cryptographic operations.
 */
public class ParametersWithContext
    implements CipherParameters
{
    private CipherParameters  parameters;
    private byte[] context;

    /**
     * @param parameters the cipher parameters
     * @param context the context data
     */
    public ParametersWithContext(
        CipherParameters parameters,
        byte[] context)
    {
        if (context == null)
        {
            throw new NullPointerException("'context' cannot be null");
        }

        this.parameters = parameters;
        this.context = Util.clone(context);
    }

    /** @param buf destination buffer */
    public void copyContextTo(byte[] buf, int off, int len)
    {
        if (context.length != len)
        {
            throw new IllegalArgumentException("len");
        }

        System.arraycopy(context, 0, buf, off, len);
    }

    /** @return the context data */
    public byte[] getContext()
    {
        return Util.clone(context);
    }

    /** @return the context length */
    public int getContextLength()
    {
        return context.length;
    }

    /** @return the cipher parameters */
    public CipherParameters getParameters()
    {
        return parameters;
    }
}
