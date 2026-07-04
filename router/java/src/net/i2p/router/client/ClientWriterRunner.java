package net.i2p.router.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.PoisonI2CPMessage;
import net.i2p.router.RouterContext;

/**
 * Async writer class so that if a client app hangs, it won't take down the
 * whole router with it (otherwise the JobQueue would block until the client
 * reads from their i2cp socket, causing all sorts of bad things to happen)
 *
 * For external I2CP connections only.
 */
class ClientWriterRunner implements Runnable {
    private final BlockingQueue<I2CPMessage> _messagesToWrite;
    private final ClientConnectionRunner _runner;
    private final RouterContext _context;
    /** @since 0.9.70+ mutable for adaptive tuning */
    private static volatile int _queueSize = 256;

    /** @since 0.9.70+ */
    public static int getQueueSize() { return _queueSize; }

    /** @since 0.9.70+ */
    public static void setQueueSize(int val) { _queueSize = Math.max(32, Math.min(2048, val)); }

    public ClientWriterRunner(RouterContext ctx, ClientConnectionRunner runner) {
        _context = ctx;
        _messagesToWrite = new LinkedBlockingQueue<>(_queueSize);
        _runner = runner;
        ctx.statManager().createRequiredRateStat("client.writerQueueFull",
                                          "I2CP writer queue overflow drops", "ClientMessages",
                                          new long[] { 60*1000L, 10*60*1000L, 60*60*1000L });
    }

    /**
     * Add this message to the writer's queue
     *
     * Nonblocking, throws exception if queue is full
     */
    public void addMessage(I2CPMessage msg) throws I2CPMessageException {
        boolean success = _messagesToWrite.offer(msg);
        if (!success) {
            _context.statManager().addRateData("client.writerQueueFull", 1);
            throw new I2CPMessageException("I2CP write to queue failed");
        }
    }

    /**
     * No more messages - don't even try to send what we have
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
