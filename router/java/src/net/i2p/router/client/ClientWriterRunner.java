package net.i2p.router.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.PoisonI2CPMessage;
import net.i2p.router.RouterContext;

/**
 * Writes I2CP messages to a client asynchronously, so a hung client app
 * cannot take down the whole router. Without this, the JobQueue would block
 * until the client reads from their i2cp socket.
 * <p>
 * For external I2CP connections only.
 */
class ClientWriterRunner implements Runnable {
    /** Messages waiting to go out to the client. */
    private final BlockingQueue<I2CPMessage> _messagesToWrite;
    /** The connection these messages belong to. */
    private final ClientConnectionRunner _runner;
    /** The router context the writer runs in. */
    private final RouterContext _context;
    /** Smallest queue the tuner may set, in messages - the floor a value is clamped up to. */
    static final int MIN_QUEUE_SIZE = 32;
    /**
     * Largest queue the tuner may set, in messages. Every queue is also built
     * with this as its hard capacity, so the memory one client can hold stays
     * bounded however far the cap is raised.
     */
    static final int MAX_QUEUE_SIZE = 2048;
    /** Queue size in messages in force until the tuner changes it. */
    static final int DEFAULT_QUEUE_SIZE = 256;

    /**
     * Max messages queued per client before an offer is refused. Read on every
     * offer, not only when the queue is built, so a change reaches a connection
     * that is already running - see {@link #addMessage}.
     *
     * @since 0.9.70+ mutable for adaptive tuning
     */
    private static volatile int queueSize = DEFAULT_QUEUE_SIZE;

    /**
     * The current queue size in messages - the cap an offer is checked against,
     * not the capacity of any one queue.
     *
     * @return the queue size
     * @since 0.9.70+
     */
    public static int getQueueSize() { return queueSize; }

    /**
     * Update the message queue size, clamped to the supported range. Applies to
     * connections already established as well as new ones: the cap is compared
     * against the queue's depth when a message is offered, so a long-running
     * client is not stuck with the limit that was in force when it connected.
     *
     * @param val the new queue size in messages
     * @since 0.9.70+
     */
    public static void setQueueSize(int val) {
        queueSize = Math.max(MIN_QUEUE_SIZE, Math.min(MAX_QUEUE_SIZE, val));
    }

    /**
     * Creates a new client writer runner. The queue is sized to the largest
     * supported queue, and {@link #addMessage} holds it to the current cap, so
     * raising the cap does not need the queue rebuilt.
     *
     * @param ctx the router context
     * @param runner the client connection runner
     */
    public ClientWriterRunner(RouterContext ctx, ClientConnectionRunner runner) {
        _context = ctx;
        _messagesToWrite = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        _runner = runner;
        ctx.statManager().createRequiredRateStat("client.writerQueueFull",
                                          "I2CP writer queue overflow drops", "ClientMessages",
                                          new long[] { 60*1000L, 10*60*1000L, 60*60*1000L });
    }

    /**
     * Add this message to the writer's queue
     * <p>
     * Nonblocking. Refused once the queue holds the current cap's worth of
     * messages, or once it reaches the hard capacity behind it.
     * @param msg the message to add
     * @throws I2CPMessageException if the queue is full
     */
    public void addMessage(I2CPMessage msg) throws I2CPMessageException {
        // The cap is compared here, not baked into the queue at construction,
        // so the tuner reaches a client that has been connected for days: the
        // queue keeps its capacity and only this limit moves. Shrinking below
        // the current depth refuses messages until the writer drains it.
        boolean success = _messagesToWrite.size() < queueSize && _messagesToWrite.offer(msg);
        if (!success) {
            _context.statManager().addRateData("client.writerQueueFull", 1);
            throw new I2CPMessageException("I2CP write to queue failed");
        }
    }

    /**
     * No more messages - don't even try to send what we have.
     *
     */
    public void stopWriting() {
        _messagesToWrite.clear();
        try {_messagesToWrite.put(new PoisonI2CPMessage());}
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public void run() {
        I2CPMessage msg;
        while (!_runner.getIsDead()) {
            try {msg = _messagesToWrite.take();}
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); continue; }
            if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE) {break;}
            _runner.writeMessage(msg);
        }
    }

}
