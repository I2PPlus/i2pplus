package net.i2p.internal;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Fetches messages off an In-JVM queue, zero-copy.
 * Uses a shared dispatcher pool instead of one thread per instance.
 *
 * @author zzz
 * @since 0.8.3
 */
public class QueuedI2CPMessageReader extends I2CPMessageReader {
    private final I2CPMessageQueue in;
    private volatile boolean registered;
    /**
     *  Set while a worker is serving this reader.
     *
     *  <p>I2CP messages for one session are order-dependent, so exactly one worker may
     *  touch a reader at a time. Correctness would otherwise rest on the ready queue never
     *  holding two entries for the same reader; claiming it before serving turns that into
     *  something the code enforces rather than something registration order has to uphold.
     */
    private final AtomicBoolean _claimed = new AtomicBoolean();

    private static final InternalI2CPDispatcher DISPATCHER = new InternalI2CPDispatcher();

    /** Messages one worker takes from a reader before requeueing it. */
    static final int MAX_DRAIN_PER_PASS = 32;

    /**
     * Creates a new instance of this QueuedMessageReader and registers with the shared dispatcher.
     * Call startReading() to begin.
     */
    public QueuedI2CPMessageReader(I2CPMessageQueue in, I2CPMessageEventListener lsnr) {
        super(lsnr);
        this.in = in;
    }

    /**
     * Register with the shared dispatcher. Idempotent: a second call must not put a
     * duplicate entry in the ready queue, which would let two workers serve this
     * reader concurrently and deliver its messages out of order.
     */
    @Override
    public void startReading() {
        if (!registered) {
            registered = true;
            DISPATCHER.register(this);
        }
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
     * Non-blocking poll + dispatch. Called by a dispatcher worker.
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
     *  Drain at most maxMessages, then return even if the queue is not empty.
     *
     *  <p>The bound is what stops one backed-up client from starving every other in-JVM
     *  client. Workers are pooled, so a reader that only returns once its queue is empty
     *  lets a payload flood hold a worker indefinitely while unrelated clients' queues
     *  back up until the router refuses to send to them at all. A full return value
     *  means more may be waiting, and the caller requeues the reader to continue.
     *
     *  @param maxMessages most messages to handle in this call
     *  @return messages handled; equal to maxMessages when the queue was not drained
     *  @since 0.9.71+
     */
    int drainOnce(int maxMessages) {
        int handled = 0;
        while (handled < maxMessages && processOnce()) {
            handled++;
        }
        return handled;
    }

    /**
     *  Take exclusive ownership of this reader for the calling worker.
     *
     *  @return false when another worker already owns it, or it stopped reading
     *  @since 0.9.71+
     */
    private boolean claim() {return _claimed.compareAndSet(false, true);}

    /**
     *  Give up ownership after a pass.
     *
     *  <p>Reports only that the reader is still registered, which is not the same as having
     *  more to send: the dispatcher decides what to reoffer from the pass result and
     *  {@link #needsService}.
     *
     *  @return true when the reader is still reading
     *  @since 0.9.71+
     */
    private boolean release() {
        _claimed.set(false);
        return registered;
    }

    /**
     *  Whether this reader is still reading and has messages waiting for a worker.
     *
     *  <p>This is the question the dispatcher's idle tick asks. It is deliberately not the
     *  same as {@link #release}: release reports only that the reader is still registered,
     *  which says nothing about whether its queue holds anything.
     *
     *  @return true when a worker should serve this reader now
     *  @since 0.9.71+
     */
    boolean needsService() {return registered && in.pending() > 0;}

    /** Name for diagnostics; identifies the session without depending on its internals. */
    String describe() {
        String lsnr = _listener == null ? "none" : _listener.getClass().getSimpleName();
        return lsnr + '@' + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Shared dispatcher pool that multiplexes all internal I2CP message readers.
     *
* <p>The ready queue holds readers that have work: one arrives at registration, is handed
     * back after a pass that filled its budget, and is picked up again by the idle tick if a
     * message turns up for it later. A reader that drained is left out, so on an idle system the
     * queue empties, {@link #IDLE_POLL_MS} actually parks a worker instead of timing out against
     * a queue the workers keep refilling themselves, and surplus workers can retire. The idle cost
     * is therefore a single worker however many clients are connected.
     *
     * <p>Reoffering after a full pass goes to the back of the queue, so a client with a backlog
     * cannot monopolise the pool. Readers picked up by the idle tick are offered in no particular
     * order, which only matters when several become ready in the same tick.
     *
     * <p>The pool never falls to zero workers while a reader is registered. Nothing wakes
     * a pool that has gone to sleep, so a reader stranded without one would sit on a queue
     * nobody drains until it filled, at which point the router starts failing to send to
     * that client. {@link #register} and {@link #workerStopped} both run under this
     * monitor, so a registration can never land between a worker's decision to exit and
     * its departure.
     */
    private static class InternalI2CPDispatcher implements Runnable {
        private final Set<QueuedI2CPMessageReader> readers =
            Collections.newSetFromMap(new ConcurrentHashMap<QueuedI2CPMessageReader, Boolean>());
        /** Readers waiting to be served. Requeued readers go to the back, so no client monopolises it. */
        private final LinkedBlockingQueue<QueuedI2CPMessageReader> ready = new LinkedBlockingQueue<>();
        /** Live workers, plus workers that have decided to exit but not yet decremented. */
        private int liveThreads;
        /** When the last retirement happened, to shed one worker per idle tick. */
        private long lastRetireMs;
        /** Thread name counter; prefix plus digits stays within the 12-char convention. */
        private int threadCount;

        /**
         * Idle poll interval, which is also the latency floor for a queued message, and the
         * spacing between retirement of surplus workers.
         */
        static final long IDLE_POLL_MS = 5;
        /**
         *  Ceiling on concurrent workers, started on demand rather than up front.
         *
         *  <p>Only reached while several clients are backed up at once; at that point one
         *  worker per client would be unbounded, and 8 concurrent workers is already far
         *  more than the queue drain rate needs.
         */
        static final int MAX_WORKERS = 8;
        /** A pass slower than this means a client's message handler is blocking the pool. */
        static final long SLOW_PASS_WARN_MS = 2000;

        /**
         *  Add a reader and make sure something will serve it.
         *
         *  @param reader the reader to serve
         */
        synchronized void register(QueuedI2CPMessageReader reader) {
            readers.add(reader);
            ready.offer(reader);
            if (liveThreads < 1) {startWorker();}
        }

        /**
         *  Stop serving a reader. No monitor: {@link #readers} is concurrent, and a
         *  worker re-checks membership before requeueing, so an entry left in the ready
         *  queue is skipped rather than served.
         *
         *  @param reader the reader to drop
         */
        void unregister(QueuedI2CPMessageReader reader) {
            readers.remove(reader);
        }

        /**
         *  Add a worker when the ready backlog is deeper than the pool can serve serially.
         */
        synchronized void grow() {
            if (liveThreads < MAX_WORKERS && ready.size() > liveThreads) {startWorker();}
        }

        /** Start one worker. Caller must hold the monitor. */
        private void startWorker() {
            liveThreads++;
            new I2PThread(this, "I2CPDisp." + threadCount++, true).start();
        }

        /** Whether any reader is registered, and so whether the pool must stay up. */
        private synchronized boolean hasReaders() {
            return !readers.isEmpty();
        }

        /** Whether this worker can retire, keeping at least one for the registered readers. */
        private synchronized boolean isSurplus() {
            return liveThreads > 1;
        }

        /**
         *  Reserve a retirement slot for the calling worker, shedding at most one worker
         *  per idle tick.
         *
         *  <p>Every parked worker notices the same idle moment, so a plain surplus check would
         *  let all of them decide to leave before any of them is counted, collapsing the pool
         *  to zero and restarting it. Spacing the retirements by one tick steps the pool down
         *  to the single worker the design promises instead. The count is decremented here,
         *  under the monitor, so the caller must not decrement it again.
         *
         *  @return true if the caller is surplus and must exit
         */
        private synchronized boolean mayRetire() {
            if (!isSurplus()) {return false;}
            long now = System.currentTimeMillis();
            if (now - lastRetireMs < IDLE_POLL_MS) {return false;}
            lastRetireMs = now;
            liveThreads--;
            return true;
        }

        /**
         *  Offer every registered reader that has messages waiting.
         *
         *  <p>A reader whose queue was drained is not requeued by the worker that served it,
         *  so this is how a message arriving afterwards is still noticed. Called once per
         *  idle tick, which bounds the cost to one pass over the registered readers per
         *  {@link #IDLE_POLL_MS} - the latency floor a queued message already had.
         */
        private void offerPendingReaders() {
            for (QueuedI2CPMessageReader reader : readers) {
                if (reader.needsService()) {ready.offer(reader);}
            }
        }

/**
     *  Account for a worker leaving, whatever the reason, and uphold the invariant
     *  that a registered reader always has a worker. The check and the restart happen
     *  in one critical section so a concurrent registration cannot be missed.
         *
         *  @param alreadyCounted true when the worker reserved its retirement slot in
         *                       {@link #mayRetire} and so must not be counted again
         */
    private synchronized void workerStopped(boolean alreadyCounted) {
        if (!alreadyCounted) {liveThreads--;}
        if (liveThreads >= 1 || readers.isEmpty()) {return;}
        // Unreachable while register() and this method share this monitor: register starts
        // a worker whenever the count is zero. Restarted rather than reported, because a
        // reader with no worker can never be served again, and nothing here is worth
        // risking that over: this is the recovery path, so it must not depend on a log
        // lookup or anything else that can fail on its own.
        startWorker();
    }

        @Override
        public void run() {
            boolean retired = false;
            try {
                while (hasReaders()) {
                    QueuedI2CPMessageReader reader = ready.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
                    if (reader == null) {
                        // Nothing queued. Because the ready queue holds only readers that have
                        // work, this is the tick on which a reader that drained earlier is
                        // picked up again if something has arrived for it since.
                        offerPendingReaders();
                        if (mayRetire()) {
                            retired = true;
                            break;
                        }
                        continue;
                    }
                    if (!reader.claim()) continue;
                    int handled = 0;
                    long started = System.currentTimeMillis();
                    try {
                        handled = reader.drainOnce(QueuedI2CPMessageReader.MAX_DRAIN_PER_PASS);
                    } finally {
                        boolean stillReading = reader.release();
                        if (stillReading && handled == QueuedI2CPMessageReader.MAX_DRAIN_PER_PASS) {
                            // A full pass means the queue was not drained, so hand the reader
                            // straight back and add a worker for the backlog.
                            //
                            // A reader that drained is deliberately not requeued: it has
                            // nothing to give, and reoffering it here is what kept the ready
                            // queue permanently non-empty, so poll never parked, no worker
                            // ever saw a surplus pool, and every idle worker spun on
                            // poll/drain/offer instead. The idle tick above covers the case
                            // this gives up, which is a message arriving after the drain.
                            ready.offer(reader);
                            grow();
                        }
                    }
                    warnIfSlowPass(reader, started);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                workerStopped(retired);
            }
        }

        /**
         *  Warn when one pass over a reader took long enough to look like a blocked
         *  handler. The stall that matters here is invisible from the router side: the
         *  client simply stops draining its queue until the queue is full.
         *
         *  @param reader the reader that was served
         *  @param startedMs wall-clock ms the pass began
         */
        private static void warnIfSlowPass(QueuedI2CPMessageReader reader, long startedMs) {
            long elapsed = System.currentTimeMillis() - startedMs;
            if (elapsed <= SLOW_PASS_WARN_MS) return;
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(QueuedI2CPMessageReader.class);
            if (log.shouldWarn()) {
                log.warn("I2CP dispatch pass for " + reader.describe() + " took " + elapsed
                         + "ms; a client message handler is blocking the I2CP pool");
            }
        }
    }
}
