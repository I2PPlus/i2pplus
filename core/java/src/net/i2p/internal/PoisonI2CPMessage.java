package net.i2p.internal;

import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageImpl;

import java.io.InputStream;

/**
 * For marking end-of-queues in a standard manner.
 * Don't actually send it.
 *
 * @author zzz
 * @since 0.8.3
 */
public class PoisonI2CPMessage extends I2CPMessageImpl {
    /**
     * MESSAGE_TYPE.
     */
    public static final int MESSAGE_TYPE = 999999;

    /**
     * PoisonI2CPMessage.
     */
    public PoisonI2CPMessage() {
        super();
    }

    /**
     *  @deprecated don't do this
     *  @throws I2CPMessageException always
     */
    @Deprecated
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException {
        throw new I2CPMessageException("Don't do this");
    }

    /**
     *  @deprecated don't do this
     *  @throws I2CPMessageException always
     */
    @Deprecated
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException {
        throw new I2CPMessageException("Don't do this");
    }

    /**
     * @return the type
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    /**
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return MESSAGE_TYPE;
    }

    /**
     * equals.
     */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof PoisonI2CPMessage)) {
            return true;
        }

        return false;
    }

    /**
     * toString.
     */
    @Override
    public String toString() {
        return "[PoisonMessage]";
    }
}
