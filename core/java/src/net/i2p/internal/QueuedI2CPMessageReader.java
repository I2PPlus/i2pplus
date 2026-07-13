package net.i2p.internal;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Get messages off an In-JVM queue, zero-copy.
 * Uses a shared dispatcher thread instead of one thread per instance.
 *
 * @author zzz
 * @since 0.8.3
 */
public class QueuedI2CPMessageReader extends I2CPMessageReader {
    private final I2CPMessageQueue in;
    private volatile boolean registered;

    private static final InternalI2CPDispatcher DISPATCHER = new InternalI2CPDispatcher();

    /**
     * Creates a new instance of this QueuedMessageReader and registers with the shared dispatcher.
     * Call startReading() to begin.
     */
    public QueuedI2CPMessageReader(I2CPMessageQueue in, I2CPMessageEventListener lsnr) {
        super(lsnr);
        this.in = in;
    }

    /**
     * Register with the shared dispatcher.
     */
    @Override
    public void startReading() {
        DISPATCHER.register(this);
        registered = true;
    }

    /**
     * Unregister from the shared dispatcher.
     */
    @Override
    public void stopReading() {
        if (registered) {
            registered = false;
            DISPATCHER.unregister(this);
        }
    }

    /**
     * Non-blocking poll + dispatch. Called by the shared dispatcher thread.
     * @return true if a message was processed
     */
    boolean processOnce() {
        I2CPMessage msg = in.poll();
        if (msg == null)
            return false;
        try {
            if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE) {
                _listener.disconnected(this);
                stopReading();
            } else {
                _listener.messageReceived(this, msg);
            }
        } catch (RuntimeException e) {
            I2PAppContext.getGlobalContext().logManager().getLog(QueuedI2CPMessageReader.class)
                .log(Log.CRIT, "Uncaught I2CP error processing message", e);
            _listener.readError(this, e);
            _listener.disconnected(this);
            stopReading();
        }
        return true;
    }

    /**
     * Shared dispatcher that multiplexes all internal I2CP message readers
     * on a single daemon thread. Cycles through registered readers with
     * non-blocking poll, sleeping briefly when all queues are idle.
     */
    private static class InternalI2CPDispatcher implements Runnable {
        private final Set<QueuedI2CPMessageReader> readers =
            Collections.newSetFromMap(new ConcurrentHashMap<QueuedI2CPMessageReader, Boolean>());
        private volatile Thread dispatcherThread;
        private static final long IDLE_SLEEP_MS = 5;

        void register(QueuedI2CPMessageReader reader) {
            readers.add(reader);
            startIfNeeded();
        }

        void unregister(QueuedI2CPMessageReader reader) {
            readers.remove(reader);
        }

        private synchronized void startIfNeeded() {
            if (dispatcherThread == null) {
                dispatcherThread = new I2PThread(this, "I2CPDispatcher", true);
                dispatcherThread.start();
            }
        }

        @Override
        public void run() {
            while (true) {
                boolean didWork = false;
                for (QueuedI2CPMessageReader reader : readers) {
                    while (reader.processOnce()) {
                        didWork = true;
                    }
                }
                if (!didWork) {
                    if (readers.isEmpty()) {
                        break;
                    }
                    try {
                        Thread.sleep(IDLE_SLEEP_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            dispatcherThread = null;
        }
    }
}
