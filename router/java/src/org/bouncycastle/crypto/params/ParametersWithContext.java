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
     * Wrap cipher parameters together with the context bytes they apply to.
     *
     * @param parameters the cipher parameters
     * @param context the context data, which must not be null and is cloned
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

    /**
     * Copy the context bytes into another buffer.
     *
     * @param buf the array receiving the copied context bytes
     * @param off index in buf at which the copy starts
     * @param len number of context bytes to copy, which must equal the context length
     */
    public void copyContextTo(byte[] buf, int off, int len)
    {
        if (context.length != len)
        {
            throw new IllegalArgumentException("len");
        }

        System.arraycopy(context, 0, buf, off, len);
    }

    /**
     * Read the context bytes back.
     *
     * @return a clone of the context bytes the wrapped parameters apply to
     */
    public byte[] getContext()
    {
        return Util.clone(context);
    }

    /**
     * Report how many context bytes are held.
     *
     * @return the number of context bytes held by this object
     */
    public int getContextLength()
    {
        return context.length;
    }

    /**
     * Read back the wrapped cipher parameters.
     *
     * @return the cipher parameters this object wraps, as supplied at construction
     */
    public CipherParameters getParameters()
    {
        return parameters;
    }
}
