package net.i2p.router.client;

import net.i2p.CoreVersion;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.router.RouterContext;

/**
 * Zero-copy in-JVM.
 * While super() starts both a reader and a writer thread, we only need a reader thread here.
 *
 * @author zzz
 * @since 0.8.3
 */
class QueuedClientConnectionRunner extends ClientConnectionRunner {
    private final I2CPMessageQueue queue;

    /**
     * Create a new runner with the given queues
     *
     * @param context the router context
     * @param manager the client manager
     * @param queue the I2CP message queue
     */
    public QueuedClientConnectionRunner(RouterContext context, ClientManager manager, I2CPMessageQueue queue) {
        super(context, manager, null);
        this.queue = queue;
    }

    /**
     * Starts the reader thread. Does not call super().
     */
    @Override
    public synchronized void startRunning() {
        _reader = new QueuedI2CPMessageReader(this.queue, new ClientMessageEventListener(_context, this, false));
        _reader.startReading();
    }

    /**
     * Calls super() to stop the reader, and sends a poison message to the client.
     */
    @Override
    public synchronized void stopRunning() {
        super.stopRunning();
        queue.close();
    }

    /**
     * In super(), doSend queues it to the writer thread and
     * the writer thread calls writeMessage() to write to the output stream.
     * Since we have no writer thread this shouldn't happen.
     */
    @Override
    void writeMessage(I2CPMessage msg) {throw new RuntimeException("huh?");}

    /**
     * Actually send the I2CPMessage to the client.
     * Non-blocking.
     *
     * @throws I2CPMessageException if queue full or on other errors
     */
    @Override
    void doSend(I2CPMessage msg) throws I2CPMessageException {
        if (!queue.offer(msg)) {throw queueFull();}
    }

    /**
     * Send the I2CPMessage, giving the client's queue up to timeoutMs to make room.
     *
     * <p>The in-JVM dispatcher drains this queue every few milliseconds, so a full queue
     * means the client is briefly behind rather than gone. Refusing the message outright
     * costs far more than the wait does: a dropped LeaseSet request spends a whole request
     * timing out and then rebuilds the tunnel, for a message that would have been handed
     * over milliseconds later.
     *
     * @param msg the message to send
     * @param timeoutMs how long to wait for space in the client's queue
     * @throws I2CPMessageException if the queue is still full after the wait, or on other errors
     */
    @Override
    void doSendWait(I2CPMessage msg, long timeoutMs) throws I2CPMessageException {
        boolean success;
        try {
            success = queue.offer(msg, timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new I2CPMessageException("Interrupted waiting for I2CP queue space");
        }
        if (!success) {throw queueFull();}
    }

    /**
     * The error for a client that is not draining its I2CP queue.
     *
     * <p>Names the client's side of the queue rather than saying only that a write
     * failed, because that is the fact the operator needs: the router's own writer is
     * healthy and the client is the side that has stopped reading.
     */
    private I2CPMessageException queueFull() {
        _context.statManager().addRateData("client.internalQueueFull", 1);
        return new I2CPMessageException("Client is not draining its I2CP queue ("
                                        + queue.pending() + " pending, "
                                        + queue.remainingCapacity() + " slots free)");
    }

    /**
     * Does nothing. Client version is the core version.
     *
     * @since 0.9.7
     */
    @Override
    public void setClientVersion(String version) {
        // intentionally empty - client version is the core version, not configurable
    }

    /**
     * The client version.
     *
     * @return CoreVersion.PUBLISHED_VERSION
     * @since 0.9.7
     */
    @Override
    public String getClientVersion() {return CoreVersion.PUBLISHED_VERSION;}

}
