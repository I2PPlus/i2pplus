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
    private static final int QUEUE_SIZE = 256;
    public ClientWriterRunner(RouterContext context, ClientConnectionRunner runner) {
        _messagesToWrite = new LinkedBlockingQueue<I2CPMessage>(QUEUE_SIZE);
        _runner = runner;
    }

    /**
     * Add this message to the writer's queue
     *
     * Nonblocking, throws exception if queue is full
     */
    public void addMessage(I2CPMessage msg) throws I2CPMessageException {
        boolean success = _messagesToWrite.offer(msg);
        if (!success) {throw new I2CPMessageException("I2CP write to queue failed");}
    }

    /**
     * No more messages - dont even try to send what we have
     *
     */
    public void stopWriting() {
        _messagesToWrite.clear();
        try {_messagesToWrite.put(new PoisonI2CPMessage());}
        catch (InterruptedException ie) {}
    }

    public void run() {
        I2CPMessage msg;
        while (!_runner.getIsDead()) {
            try {msg = _messagesToWrite.take();}
            catch (InterruptedException ie) {continue;}
            if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE) {break;}
            _runner.writeMessage(msg);
        }
    }

}
