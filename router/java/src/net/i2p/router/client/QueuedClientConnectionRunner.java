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
 * @since 0.8.3
 */
class QueuedClientConnectionRunner extends ClientConnectionRunner {
    /** The queue shared with the client. */
    private final I2CPMessageQueue queue;
    /**
     * Fraction of the client's queue at which a backlog sample is worth taking.
     *
     * @see #recordBacklog()
     */
    private static final int BACKLOG_SAMPLE_FRACTION = 4;

    /**
     * Create a new runner with the given queues.
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
        boolean ok = queue.offer(msg);
        recordBacklog();
        if (!ok) {throw queueFull();}
    }

    /**
     * Send the I2CPMessage, giving the client's queue up to timeoutMs to make room.
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
        recordBacklog();
        if (!success) {throw queueFull();}
    }

    /**
     * The error for a client that is not taking messages off its queue.
     * <p>Reports the backlog on the side the failed write went to, because that is what tells
     * the two ways an offer fails apart: a queue sitting at its capacity may drain again,
     * while a queue whose reader has gone away never will. The read side of the same pair of
     * queues says nothing about either - the router drains that one itself.
     *
     * @return a fresh exception naming the condition and the depth of the client's queue
     */
    private I2CPMessageException queueFull() {
        _context.statManager().addRateData("client.internalQueueFull", 1);
        int backlog = queue.writeBacklog();
        int free = queue.remainingCapacity();
        if (queue.isClosed()) {
            return new I2CPMessageException("The client's I2CP queue is closed ("
                                            + backlog + " messages unread)");
        }
        if (free == Integer.MAX_VALUE) {
            return new I2CPMessageException("Client is not draining its I2CP queue ("
                                            + backlog + " messages queued)");
        }
        return new I2CPMessageException("Client is not draining its I2CP queue ("
                                        + backlog + " of " + (backlog + free) + " messages)");
    }

    /**
     * Record how deep the client's queue is, so a backlog that persists is visible as a
     * sustained graph line and not only as the warning from the moment it overflowed.
     * <p>Sampled only above a quarter of the capacity: the healthy state is an empty queue,
     * and a sample for every message would bury the signal in the idle path. An unbounded
     * queue has no overflow point for a depth to be measured against, so it is not sampled.
     */
    private void recordBacklog() {
        int backlog = queue.writeBacklog();
        int free = queue.remainingCapacity();
        if (free == Integer.MAX_VALUE || backlog * BACKLOG_SAMPLE_FRACTION < backlog + free) {return;}
        _context.statManager().addRateData("i2cp.internalQueueBacklog", backlog);
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
     * @return always the published core version; the client version is fixed and
     *         cannot be set
     * @since 0.9.7
     */
    @Override
    public String getClientVersion() {return CoreVersion.PUBLISHED_VERSION;}

}
