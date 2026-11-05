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
 * <p>
 * Reading may be done in a thread using the QueuedI2CPMessageReader class.
 * Non-blocking writing may be done directly with {@link #offer}.
 *
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
     *                              waiting for space to become available
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
     * How many messages are waiting for this end to read, for diagnostics.
     * <p>This is the read side, not the side {@link #offer(I2CPMessage)} writes to. An
     * implementation with a reader of its own keeps this near zero however badly its peer
     * is falling behind, so it never explains an offer failure - {@link #writeBacklog()}
     * does.
     *
     * @return messages waiting to be received by this end
     * @since 0.9.71+
     */
    public abstract int pending();

    /**
     * How many more messages this queue will accept, for diagnostics.
     *
     * @return remaining capacity on the side {@link #offer(I2CPMessage)} writes to;
     *         {@link Integer#MAX_VALUE} when unbounded
     * @since 0.9.71+
     */
    public abstract int remainingCapacity();

    /**
     * How many messages this end has queued for the peer to read, for diagnostics.
     * <p>Unlike {@link #pending()}, which counts what this end has not read yet, this counts
     * what this end has written and the peer has not taken. It is therefore the number that
     * explains an {@link #offer(I2CPMessage)} failure: a value at the capacity says the peer
     * has stopped draining, and a value that stays there says it never started again.
     *
     * @return messages queued for the peer to read
     * @since 0.9.73+
     */
    public abstract int writeBacklog();

    /**
     * Whether this queue is closed, so no reader will ever take another message from it.
     * <p>A closed queue and a full one look identical to a writer - {@link #offer(I2CPMessage)}
     * returns false either way - and the difference matters: one is temporary backpressure and
     * the other is permanent. The default is false, for implementations with no close state of
     * their own.
     *
     * @return true once {@link #close()} has run for either end of this queue
     * @since 0.9.73+
     */
    public boolean isClosed() {return false;}

    /**
     * Register the callback a writer runs after it queues a message for this end to read.
     * <p>The reader registers itself here so the dispatcher is handed the reader as soon as
     * work arrives, rather than rediscovering it on a later sweep. The callback runs on the
     * writer's thread, so it must not block and must not throw.
     *
     * @param hook the callback to run after a message is queued for this end, or null to
     *             unregister
     * @since 0.9.73+
     */
    public void setWakeHook(Runnable hook) {}

    /**
     * Send a message, blocking until space is available.
     * Unused for now.
     *
     * @param msg the message to enqueue for the router to read
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for space to become available
     */
    public abstract void put(I2CPMessage msg) throws InterruptedException;

    /**
     * Receive a message, blocking until one is available.
     *
     * @return the next message from the router, waiting indefinitely if none is queued
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for a message to arrive
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
