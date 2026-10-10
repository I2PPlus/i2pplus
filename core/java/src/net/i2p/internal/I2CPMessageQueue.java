package net.i2p.internal;

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.util.Log;

import java.io.Closeable;

/**
 * Contains the methods to talk to a router or client via I2CP,
 * when both are in the same JVM.
 * This interface contains methods to access two queues,
 * one for transmission and one for receiving.
 * The methods are identical to those in java.util.concurrent.BlockingQueue.
 *
 * Reading may be done in a thread using the QueuedI2CPMessageReader class.
 * Non-blocking writing may be done directly with offer().
 *
 * @author zzz
 * @since 0.8.3
 */
public abstract class I2CPMessageQueue implements Closeable {
    /**
     * Constructor for implementations, which configure the queue after construction.
     */

    public I2CPMessageQueue() {}


    /**
     * Send a message, nonblocking.
     *
     * @param msg the message to enqueue for the router to read
     * @return success (false if no space available)
     */
    public abstract boolean offer(I2CPMessage msg);

    /**
     * Send a message, blocking.
     *
     * @param msg the message to enqueue for the router to read
     * @param timeout how long to wait for space (ms)
     * @return success (false if no space available or if timed out)
     * @throws InterruptedException if the calling thread is interrupted while
     *         waiting for space to become available
     * @since 0.9.3
     */
    public abstract boolean offer(I2CPMessage msg, long timeout) throws InterruptedException;

/**
 * Receive a message, non-blocking.
 * Unused for now.
 *
 * @return message or null if none available
 */
    public abstract I2CPMessage poll();

    /**
     * How many messages are waiting to be received, for diagnostics.
     *
     * <p>A queue that stays non-empty means the client is not draining it, which is the
     * reason {@link #offer(I2CPMessage)} fails and the only explanation for a client that
     * has stopped answering.
     *
     * @return messages waiting to be received
     * @since 0.9.71+
     */
    public abstract int pending();

    /**
     * How many more messages this queue will accept, for diagnostics.
     *
     * @return remaining capacity; {@link Integer#MAX_VALUE} when unbounded
     * @since 0.9.71+
     */
    public abstract int remainingCapacity();

    /**
     * Send a message, blocking until space is available.
     * Unused for now.
     *
     * @param msg the message to enqueue for the router to read
     * @throws InterruptedException if the calling thread is interrupted while
     *         waiting for space to become available
     */
    public abstract void put(I2CPMessage msg) throws InterruptedException;

    /**
     * Receive a message, blocking until one is available.
     *
     * @return message
     * @throws InterruptedException if the calling thread is interrupted while
     *         waiting for a message to arrive
     */
    public abstract I2CPMessage take() throws InterruptedException;

    /**
     * == offer(new PoisonI2CPMessage());
     */
    @Override
    public void close() {
        if (!offer(new PoisonI2CPMessage())) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2CPMessageQueue.class);
            log.warn("Failed to send close message - queue full");
        }
    }
}
