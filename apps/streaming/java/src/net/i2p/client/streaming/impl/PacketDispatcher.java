package net.i2p.client.streaming.impl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;

/**
 *  Dispatch inbound stream packets to a small pool of single-threaded shard
 *  workers, so a slow or hostile connection no longer stalls the session
 *  notifier thread for every other connection on the same destination.
 *  <p>
 *  Ordering: {@link #shardFor} maps a connection's inbound stream id to
 *  exactly one worker, and each worker drains its queue FIFO, so all packets
 *  of a given connection are handled by one thread in arrival order.  Only
 *  packets for different connections run in parallel.
 *  <p>
 *  Back-pressure: the per-shard queue is bounded.  When it is full the
 *  producer ({@code PacketHandler} on the session notifier thread) waits
 *  briefly for space instead of dropping or reordering; this reproduces the
 *  blocking behaviour of the old fully synchronous path, but only for the
 *  saturated connection's shard.
 *  <p>
 *  Live resize: the router's Tuner can change the worker count at runtime via
 *  {@link #resizeAll} (applies the Tuner's global default to every live
 *  manager).  A resize is guarded by the dispatcher's write lock so no dispatch
 *  is in flight while the shard array is rebuilt; each old shard is drained to
 *  idle before its worker is retired, so every connection stays on exactly one
 *  thread with no reordering and no dropped packets.
 *  <p>
 *  SYN / unknown-stream establishment is intentionally not offloaded; it
 *  continues on the notifier thread and the {@link ConnectionHandler}.
 *  Shutdown replaces the synchronous path once more: {@link #shutdown()}
 *  stops the workers and frees any queued-but-unprocessed packets without
 *  leaving the notifier thread blocked.
 *
 *  @since 0.9.71
 */
class PacketDispatcher {

    /** All live dispatchers, so the Tuner can resize every manager at once. */
    private static final Set<PacketDispatcher> _live = ConcurrentHashMap.newKeySet();

    /**
     *  Process one dispatched (connection, packet) pair.  Implemented by
     *  {@link PacketHandler#receiveKnownConnection}; the implementation must
     *  consume the packet's payload exactly once.
     */
    interface PacketProcessor {
        /**
         *  Process the packet.
         *  @param con the connection the packet belongs to, may be null in tests
         *  @param packet the packet to process
         */
        void process(Connection con, Packet packet);
    }

    /** Queue element: keeps the already-resolved connection with its packet. */
    private static final class Entry {
        final Connection con;
        final Packet packet;

        Entry(Connection con, Packet packet) {
            this.con = con;
            this.packet = packet;
        }
    }

    /**
     *  Guards the shard array.  Dispatch holds the read lock (one reader in
     *  practice — a single notifier thread), so it is uncontended on the hot
     *  path; resize and shutdown hold the write lock, excluding every dispatch
     *  while the array is rebuilt, an old worker retired, or the dispatcher
     *  stopped.
     */
    private final ReentrantReadWriteLock _lock = new ReentrantReadWriteLock();

    private final int _queueCapacity;
    private final PacketProcessor _processor;
    private final I2PAppContext _context;
    private final Log _log;
    private volatile Worker[] _workers;
    private volatile boolean _running = true;

    /**
     *  Create and start the shard workers.  Package-visible for unit tests.
     *
     *  @param workerCount number of shards, at least 1 (higher values clamped)
     *  @param queueCapacity queue size per shard, at least 1 (higher clamped)
     *  @param processor not null
     */
    PacketDispatcher(int workerCount, int queueCapacity, PacketProcessor processor) {
        this(workerCount, queueCapacity, processor, null);
    }

    /**
     *  Create and start the shard workers.
     *
     *  @param workerCount number of shards, at least 1 (higher values clamped)
     *  @param queueCapacity queue size per shard, at least 1 (higher clamped)
     *  @param processor not null
     *  @param context used to record the receive-backlog / queue-depth rates,
     *                 may be null in tests (stats disabled)
     */
    PacketDispatcher(int workerCount, int queueCapacity, PacketProcessor processor, I2PAppContext context) {
        _context = context;
        _log = context != null ? context.logManager().getLog(PacketDispatcher.class) : null;
        _queueCapacity = Math.max(1, queueCapacity);
        _processor = processor;
        _workers = newWorkers(Math.max(1, workerCount));
        if (context != null) {
            if (context.statManager().getRate("stream.receiveBacklogged") == null) {
                context.statManager().createRateStat("stream.receiveBacklogged",
                                                     "Producers blocked on a full receive shard queue",
                                                     "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
            }
            if (context.statManager().getRate("stream.receiveQueueDepth") == null) {
                context.statManager().createRateStat("stream.receiveQueueDepth",
                                                     "Inbound receive shard queue depth at dispatch",
                                                     "Stream", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
            }
        }
        _live.add(this);
    }

    /**
     *  Route (con, packet) to the shard owning the connection and return
     *  promptly; wait for space only while that shard's queue is full.  Records
     *  the target shard's queue depth and any producer-blind back-pressure.
     *
     *  @param sendStreamId the connection's inbound stream id (shard key)
     *  @param con the resolved connection, passed through to the processor
     *  @param packet the packet to process
     *  @throws InterruptedException if interrupted while waiting for queue space
     */
    void dispatch(long sendStreamId, Connection con, Packet packet) throws InterruptedException {
        _lock.readLock().lock();
        try {
            if (!_running) {
                packet.releasePayload();
                return;
            }
            Worker w = _workers[shardFor(sendStreamId, _workers.length)];
            if (_context != null) {
                _context.statManager().addRateData("stream.receiveQueueDepth", w._queue.size());
            }
            w.waitForQueueSpace(con, packet);
        } finally {
            _lock.readLock().unlock();
        }
    }

    /**
     *  Rebuild the shard array at a new size.  Takes the write lock, so no
     *  dispatch is in flight; each old shard is drained to idle (queue empty,
     *  no entry mid-process) before its worker is retired and the new workers
     *  take over.  A no-op when the size is unchanged.
     *
     *  @param n the new worker count, at least 1 (lower values clamped)
     */
    void resize(int n) {
        final int target = Math.max(1, n);
        _lock.writeLock().lock();
        try {
            if (target == _workers.length) {return;}
            Worker[] old = _workers;
            try {
                for (Worker w : old) {w.awaitIdle();}
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            _workers = newWorkers(target);
            for (Worker w : old) {w.retire();}
        } finally {
            _lock.writeLock().unlock();
        }
    }

    /**
     *  Resize every live dispatcher to the Tuner's current default, so existing
     *  managers (not just ones created afterwards) follow the tunable value.
     *
     *  @param n the new worker count, at least 1 (lower values clamped)
     *  @since 0.9.71+
     */
    static void resizeAll(int n) {
        for (PacketDispatcher d : _live) {d.resize(n);}
    }

    /**
     *  Stop the workers and free queued-but-unprocessed packets.  Idempotent;
     *  after shutdown, {@link #dispatch} becomes a no-op.
     */
    void shutdown() {
        _lock.writeLock().lock();
        try {
            _running = false;
            for (Worker w : _workers) {w.retire();}
            _live.remove(this);
        } finally {
            _lock.writeLock().unlock();
        }
    }

    /**
     *  Current worker count (shard array length).  Package-visible for tests.
     *  @return the current worker count, &gt;= 1
     */
    int getWorkerCount() { return _workers.length; }

    /** Build and start the shard array of the given size. */
    private Worker[] newWorkers(int n) {
        Worker[] w = new Worker[n];
        for (int i = 0; i < n; i++) {
            w[i] = new Worker(i, _queueCapacity, _processor, _context);
        }
        for (Worker worker : w) {worker.start();}
        return w;
    }

    /**
     *  Deterministic, in-range shard index for a connection's inbound stream id.
     *  <p>
     *  The id is masked to 31 bits so the result is always non-negative even if
     *  the id space wraps; inbound stream ids are unique per live connection
     *  within a manager, giving a stable per-connection (and thus
     *  order-preserving) mapping.
     *
     *  @param sendStreamId inbound stream id, &gt; 0 for known connections
     *  @param nThreads number of shards, &gt;= 1
     *  @return 0..nThreads-1
     *  @since 0.9.71
     */
    static int shardFor(long sendStreamId, int nThreads) {
        if (nThreads <= 1) {return 0;}
        return (int) ((sendStreamId & 0x7fffffffL) % nThreads);
    }

    /** One worker drains its own bounded queue; started in the dispatcher ctor. */
    private class Worker extends Thread {
        private final LinkedBlockingQueue<Entry> _queue;
        private final PacketProcessor _processor;
        private final I2PAppContext _context;
        /** Entries currently inside process(); read by resize to prove idle. */
        private volatile int _busy;

        Worker(int shard, int queueCapacity, PacketProcessor processor, I2PAppContext context) {
            super("StreamRxPkt-" + shard);
            setDaemon(true);
            _queue = new LinkedBlockingQueue<Entry>(queueCapacity);
            _processor = processor;
            _context = context;
        }

        /**
         *  Wait for a queue slot while the dispatcher is running, then stage
         *  the entry.  Unlike a plain blocking put, this loop also exits when
         *  the dispatcher shuts down so a saturated notifier thread can never
         *  be left blocked on a dead worker.  Each blocked offer is recorded in
         *  {@code stream.receiveBacklogged}, the Tuner's grow signal.
         *
         *  @throws InterruptedException if interrupted while waiting
         */
        void waitForQueueSpace(Connection con, Packet packet) throws InterruptedException {
            Entry e = new Entry(con, packet);
            while (_running && !_queue.offer(e)) {
                if (_context != null) {
                    _context.statManager().addRateData("stream.receiveBacklogged", 1);
                }
                Thread.sleep(1);
            }
            if (!_running) {e.packet.releasePayload();}
        }

        /**
         *  Wait until the queue is empty and no entry is mid-process.  Called
         *  from resize under the write lock, so no new offers can arrive: once
         *  idle the worker is retired with no lost or reordered packets.
         */
        void awaitIdle() throws InterruptedException {
            while (!_queue.isEmpty() || _busy > 0) {
                Thread.sleep(1);
            }
        }

        /** Interrupt the worker and free any queued-but-unprocessed entries. */
        void retire() {
            interrupt();
            Entry e;
            while ((e = _queue.poll()) != null) {
                e.packet.releasePayload();
            }
        }

        @Override
        public void run() {
            while (_running || !_queue.isEmpty()) {
                Entry e;
                try {
                    e = _queue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    break;
                }
                if (e != null) {
                    _busy++;
                    try {
                        _processor.process(e.con, e.packet);
                    } catch (RuntimeException re) {
                        // A poisoned entry must not kill this thread: if the
                        // worker died the producer would spin forever on this
                        // full shard queue and stall every connection on the
                        // destination. Release the payload and move on.
                        if (_log != null && _log.shouldWarn())
                            _log.warn("Error processing receive packet, packet dropped", re);
                        e.packet.releasePayload();
                    } finally {
                        _busy--;
                    }
                }
            }
        }
    }
}
