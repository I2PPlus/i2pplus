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
     * I2CP type number reserved for the never-to-be-sent poison marker.
     */
    public static final int MESSAGE_TYPE = 999999;

    /**
     * Creates the marker used to terminate a queue.
     */
    public PoisonI2CPMessage() {
        super();
    }

    /**
     *  Reading this message always fails.
     *
     *  @deprecated don't do this
     *  @throws I2CPMessageException always, as reading this message is not supported
     */
    @Deprecated
    @Override
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException {
        throw new I2CPMessageException("Don't do this");
    }

    /**
     *  Writing this message always fails.
     *
     *  @deprecated don't do this
     *  @throws I2CPMessageException always, as writing this message is not supported
     */
    @Deprecated
    @Override
    protected byte[] doWriteMessage() throws I2CPMessageException {
        throw new I2CPMessageException("Don't do this");
    }

    /**
     * Type of this message.
     *
     * @return the PoisonI2CPMessage type
     */
    @Override
    public int getType() {
        return MESSAGE_TYPE;
    }

    /**
     * Hash code for this message.
     *
     * @return whether h code is present
     */
    @Override
    public int hashCode() {
        return MESSAGE_TYPE;
    }

    /**
     * Whether this message equals the given object.
     */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof PoisonI2CPMessage)) {
            return true;
        }

        return false;
    }

    /**
     * String representation of this message.
     */
    @Override
    public String toString() {
        return "[PoisonMessage]";
    }
}
