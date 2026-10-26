package net.i2p.client.impl;

import net.i2p.client.I2PSessionException;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.PoisonI2CPMessage;
import net.i2p.util.I2PAppThread;
import net.i2p.util.SystemVersion;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Copied from net.i2p.router.client
 * We need a single thread that writes so we don't have issues with
 * the Piped Streams used in InternalSocket.
 *
 */
class ClientWriterRunner implements Runnable {
    /** Where messages are written to the router. */
    private final OutputStream _out;
    /** The session whose messages are written. */
    private final I2PSessionImpl _session;
    /** Messages waiting to reach the router. */
    private final BlockingQueue<I2CPMessage> _messagesToWrite;
    /** Counts the writer threads created. */
    private static final AtomicLong __Id = new AtomicLong();
    /** Most messages queued before writes start blocking. */
    private static final int MAX_QUEUE_SIZE = SystemVersion.isSlow() ? 64 : 256;
    /** Longest a send waits for the queue to drain, in ms. */
    private static final long MAX_SEND_WAIT = SystemVersion.isSlow() ? 10 * 1000 : 5 * 1000;

    /**
     * Create the writer for a session, buffering I2CP messages queued for
     * output to that session.
     * <p>
     * As of 0.9.11 does not start the thread, caller must call {@link #startWriting()}.
     *
     * @param out stream the I2CP messages are written to, buffered internally
     * @param session session to poll for closure and to report write failures to
     */
    public ClientWriterRunner(OutputStream out, I2PSessionImpl session) {
        _out = new BufferedOutputStream(out);
        _session = session;
        _messagesToWrite = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    }

    /**
     * Starts the daemon thread that drains the queue and writes to the session's output stream.
     * @since 0.9.11
     */
    public void startWriting() {
        Thread t = new I2PAppThread(this, "I2CPWrtr." + __Id.incrementAndGet(), true);
        t.start();
    }

    /**
     * Add this message to the writer's queue.
     * Blocking if queue is full.
     *
     * @param msg the message to queue for writing to the session's output stream
     * @throws I2PSessionException if we wait too long or are interrupted
     */
    public void addMessage(I2CPMessage msg) throws I2PSessionException {
        try {
            if (!_messagesToWrite.offer(msg, MAX_SEND_WAIT, TimeUnit.MILLISECONDS)) {
                throw new I2PSessionException("Timed out waiting while write queue was full");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new I2PSessionException("Interrupted while write queue was full", ie);
        }
    }

    /**
     * No more messages - don't even try to send what we have.
     *
     */
    public void stopWriting() {
        _messagesToWrite.clear();
        try {
            _messagesToWrite.put(new PoisonI2CPMessage());
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); /* ignored */ }
    }

    @Override
    public void run() {
        I2CPMessage msg;
        while (!_session.isClosed()) {
            try {
                msg = _messagesToWrite.take();
            } catch (InterruptedException ie) {
                continue;
            }
            if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE) {
                break;
            }
            // only thread, we don't need synchronized
            try {
                msg.writeMessage(_out);
                if (_messagesToWrite.isEmpty()) {
                    _out.flush();
                }
            } catch (I2CPMessageException ime) {
                _session.propagateError("Error writing out the message", ime);
                _session.disconnect();
                break;
            } catch (IOException ioe) {
                _session.propagateError("Error writing out the message", ioe);
                _session.disconnect();
                break;
            }
        }
        _messagesToWrite.clear();
    }
}
