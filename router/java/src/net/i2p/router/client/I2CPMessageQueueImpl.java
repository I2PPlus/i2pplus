package net.i2p.router.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.util.Log;

/**
 * Contains the methods to talk to a router or client via I2CP,
 * when both are in the same JVM.
 * This interface contains methods to access two queues,
 * one for transmission and one for receiving.
 * The methods are identical to those in java.util.concurrent.BlockingQueue.
 * <p>
 * One pair of backing queues is wrapped twice, once per end, with the queues swapped:
 * what one end writes to is what the other reads from. {@link #link} joins the two
 * wrappers so close state and reader wake-ups cross that boundary.
 *
 * @since 0.8.3
 */
class I2CPMessageQueueImpl extends I2CPMessageQueue {
    /** Queue this end reads from; the peer writes to it. */
    private final BlockingQueue<I2CPMessage> _in;
    /** Queue this end writes to; the peer reads from it. */
    private final BlockingQueue<I2CPMessage> _out;
    /**
     * The wrapper the peer end uses, or null for a stand-alone queue. Neither wrapper can
     * see the other's close state, or reach the reader sitting on the other end, without it.
     */
    private volatile I2CPMessageQueueImpl _peer;
    /** Registered by this end's reader; a writer on the peer end runs it after queueing. */
    private volatile Runnable _wakeHook;
    /** True once either end has closed, so _out will never be drained again. */
    private volatile boolean _closed;

    /**
     * Wrap a pair of queues as an I2CP message queue.
     *
     * @param in the queue messages from the client arrive on
     * @param out the queue messages to the client are written to
     */
    public I2CPMessageQueueImpl(BlockingQueue<I2CPMessage> in, BlockingQueue<I2CPMessage> out) {
        _in = in;
        _out = out;
    }

    /**
     * Cross-link the two wrappers built over one pair of queues.
     * <p>Each end holds its own wrapper with the queues swapped, so a close at one end and a
     * reader at the other are otherwise invisible to each other. Call after constructing both.
     *
     * @param other the wrapper the peer end uses; ignored if null or this queue
     * @since 0.9.73+
     */
    void link(I2CPMessageQueueImpl other) {
        if (other == null || other == this) {return;}
        _peer = other;
        other._peer = this;
    }

    /**
     * Send a message, nonblocking.
     *
     * @return success (false if no space available)
     */
    @Override
    public boolean offer(I2CPMessage msg) {
        boolean ok = _out.offer(msg);
        if (ok) {wakePeer();}
        return ok;
    }

    /**
     * Send a message, blocking.
     *
     * @param timeout how long to wait for space (ms)
     * @return success (false if no space available or if timed out)
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for space to become available
     * @since 0.9.3
     */
    @Override
    public boolean offer(I2CPMessage msg, long timeout) throws InterruptedException {
        boolean ok = _out.offer(msg, timeout, TimeUnit.MILLISECONDS);
        if (ok) {wakePeer();}
        return ok;
    }

    /**
     * Receive a message, non-blocking.
     *
     * @return message or null if none available
     */
    @Override
    public I2CPMessage poll() {return _in.poll();}

    /**
     * Messages waiting for this end to read.
     *
     * @return messages on this end's read side, waiting to be received
     * @since 0.9.71+
     */
    @Override
    public int pending() {return _in.size();}

    /**
     * Slots left on the side this end writes to.
     *
     * @return how many more messages offer() will accept; {@link Integer#MAX_VALUE} when unbounded
     * @since 0.9.71+
     */
    @Override
    public int remainingCapacity() {return _out.remainingCapacity();}

    /**
     * Messages this end has queued that the peer has not taken yet.
     * <p>This is the same queue {@link #offer} writes to and {@link #remainingCapacity()}
     * measures, so a value equal to the capacity is exactly why an offer failed.
     *
     * @return messages waiting for the peer to read
     * @since 0.9.73+
     */
    @Override
    public int writeBacklog() {return _out.size();}

    /**
     * Whether either end has closed this queue.
     *
     * @return true once {@link #close()} has run at either end, so a failed offer is
     *         permanent rather than momentary backpressure
     * @since 0.9.73+
     */
    @Override
    public boolean isClosed() {return _closed;}

    /**
     * Register the callback a writer on the peer end runs after queueing a message for
     * this end to read.
     *
     * @param hook the callback, or null to unregister it
     * @since 0.9.73+
     */
    @Override
    public void setWakeHook(Runnable hook) {_wakeHook = hook;}

    /**
     * Send a message, blocking until space is available.
     */
    public void put(I2CPMessage msg) throws InterruptedException {_out.put(msg);}

    /**
     * Receive a message, blocking until one is available.
     *
     * @return the next message from the inbound side of the queue
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for a message
     */
    public I2CPMessage take() throws InterruptedException {return _in.take();}

    /**
     * Mark the queue closed at both ends, then queue the poison message that stops the
     * peer's reader.
     * <p>The flag is set first, and on both wrappers, because the usual reason to close is
     * that the peer has already gone and the poison no longer fits in a full queue. The close
     * still happened, and a writer at either end has to be able to tell that apart from a
     * queue that is merely full.
     */
    @Override
    public void close() {
        _closed = true;
        I2CPMessageQueueImpl peer = _peer;
        if (peer != null) {peer._closed = true;}
        super.close();
    }

    /**
     * Hand the peer's reader to whoever serves it, now that _out holds another message.
     * <p>Runs on the writer's thread, which may be a transport or job-queue thread, so the
     * hook is expected to be a non-blocking offer. A hook that throws is reported and
     * dropped: the message is already queued, and losing the wake-up only delays the reader
     * until the dispatcher's next sweep.
     */
    private void wakePeer() {
        I2CPMessageQueueImpl peer = _peer;
        if (peer == null) {return;}
        Runnable hook = peer._wakeHook;
        if (hook == null) {return;}
        try {
            hook.run();
        } catch (RuntimeException re) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(I2CPMessageQueueImpl.class);
            if (log.shouldWarn()) {
                log.warn("Waking the reader at the other end of the I2CP queue failed", re);
            }
        }
    }
}
