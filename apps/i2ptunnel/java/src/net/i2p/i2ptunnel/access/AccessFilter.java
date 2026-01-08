package net.i2p.i2ptunnel.access;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.StatefulConnectionFilter;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SimpleTimer2;

/**
 * Incoming connection filter configurable through access list rules.
 * <p>
 * Tracks known destinations (defined in access lists) and unknown destinations
 * (recently attempted connections). Reloads access lists from disk when modified
 * to allow user edits. Writes recorder data to disk at intervals when
 * new threshold breaches occur.
 *
 * @since 0.9.40
 */
class AccessFilter implements StatefulConnectionFilter {

    private static final long PURGE_INTERVAL = 1000;
    private static final long SYNC_INTERVAL = 5 * 1000;

    /**
     * All disk i/o from all instances of this filter
     * happen on this thread (apart from initial load)
     */
    private static final ExecutorService DISK_WRITER = Executors.newSingleThreadExecutor();

    private final FilterDefinition definition;
    private final I2PAppContext context;

    private final AtomicBoolean timersRunning = new AtomicBoolean();

    /**
     * Trackers for known destinations defined in access lists
     */
    private final Map<Hash, DestTracker> knownDests = new HashMap<Hash, DestTracker>();
    /**
     * Trackers for unknown destinations not defined in access lists
     */
    private final Map<Hash, DestTracker> unknownDests = new HashMap<Hash, DestTracker>();

    private volatile Syncer syncer;

    /**
     * Create a new access filter.
     * 
     * @param context the context, used for scheduling and timer purposes
     * @param definition definition of this filter
     * @throws IOException if an I/O error occurs
     */
    AccessFilter(I2PAppContext context, FilterDefinition definition)
            throws IOException {
        this.context = context;
        this.definition = definition;

        reload();
    }

    /**
     * Starts the filter's background tasks.
     * <p>
     * Initializes the purger and syncer timers to begin tracking connection
     * attempts and periodically saving state to disk.
     *
     * @since 0.9.40
     */
    @Override
    public void start() {
        if (timersRunning.compareAndSet(false, true)) {
            new Purger();
            syncer = new Syncer();
        }
    }

    /**
     * Stops the filter's background tasks.
     * <p>
     * Halts the purger and syncer timers. Existing connection data is
     * retained until the next purge cycle.
     *
     * @since 0.9.40
     */
    @Override
    public void stop() {
        timersRunning.set(false);
        syncer = null;
    }

    /**
     * Determines if a connection to the given destination should be allowed.
     * <p>
     * Checks the destination against known and unknown destination trackers.
     * If the destination has exceeded its threshold for the current time window,
     * the connection is denied. Unknown destinations start with the default threshold.
     *
     * @param d the destination to check
     * @return true if the connection should be allowed, false if denied
     * @since 0.9.40
     */
    @Override
    public boolean allowDestination(Destination d) {
        Hash hash = d.getHash();
        long now = context.clock().now();
        DestTracker tracker;
        synchronized(knownDests) {
            tracker = knownDests.get(hash);
        }
        if (tracker == null) {
            synchronized(unknownDests) {
                tracker = unknownDests.get(hash);
                if (tracker == null) {
                    tracker = new DestTracker(hash, definition.getDefaultThreshold());
                    unknownDests.put(hash, tracker);
                }
            }
        }

        return !tracker.recordAccess(now);
    }

    private void reload() throws IOException {
        Map<Hash, DestTracker> tmp = new HashMap<Hash, DestTracker>();
        for (FilterDefinitionElement element : definition.getElements()) {
            element.update(tmp);
        }

        synchronized(knownDests) {
            knownDests.keySet().retainAll(tmp.keySet());
            for (Map.Entry<Hash, DestTracker> e : tmp.entrySet()) {
                Hash newHash = e.getKey();
                if (knownDests.containsKey(newHash))
                    continue;
                knownDests.put(newHash, e.getValue());
            }
        }

    }

    private void record() throws IOException {
        final long now = context.clock().now();
        for (Recorder recorder : definition.getRecorders()) {
            Threshold threshold = recorder.getThreshold();
            File file = recorder.getFile();
            Set<String> breached = new LinkedHashSet<String>();

            // if the file already exists, add previously breached b32s
            if (file.exists() && file.isFile()) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
                    String b32;
                    while((b32 = reader.readLine()) != null) {
                        breached.add(b32);
                    }
                } finally {
                    if (reader != null) try { reader.close(); } catch (IOException ignored) {}
                }
            }

            boolean newBreaches = false;
            synchronized(unknownDests) {
                for (DestTracker tracker : unknownDests.values()) {
                    if (!tracker.getCounter().isBreached(threshold, now))
                        continue;
                    newBreaches |= breached.add(tracker.getHash().toBase32());
                }
            }

            if (breached.isEmpty() || !newBreaches)
                continue;

            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(
                    new OutputStreamWriter(
                        new SecureFileOutputStream(file), StandardCharsets.UTF_8));
                for (String b32 : breached) {
                    writer.write(b32);
                    writer.newLine();
                }
            } finally {
                if (writer != null) try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void purge() {
        long olderThan = context.clock().now() - definition.getPurgeSeconds() * 1000;

        synchronized(knownDests) {
            for (DestTracker tracker : knownDests.values()) {
                tracker.purge(olderThan);
            }
        }

        synchronized(unknownDests) {
            for (Iterator<Map.Entry<Hash,DestTracker>> iter = unknownDests.entrySet().iterator();
                    iter.hasNext();) {
                Map.Entry<Hash,DestTracker> entry = iter.next();
                if (entry.getValue().purge(olderThan))
                    iter.remove();
            }
        }
    }

    private class Purger extends SimpleTimer2.TimedEvent {
        Purger() {
            super(context.simpleTimer2(), PURGE_INTERVAL);
        }

        /**
         * Called by the timer when the purge interval has elapsed.
         * Removes old connection records and clears all data if stopped.
         *
         * @since 0.9.40
         */
        public void timeReached() {
            if (!timersRunning.get()) {
                synchronized(knownDests) {
                    knownDests.clear();
                }
                synchronized(unknownDests) {
                    unknownDests.clear();
                }
                return;
            }
            purge();
            schedule(PURGE_INTERVAL);
        }
    }

    private class Syncer extends SimpleTimer2.TimedEvent {
        Syncer() {
            super(context.simpleTimer2(), SYNC_INTERVAL);
        }

        /**
         * Called by the timer when the sync interval has elapsed.
         * Saves recorder data and reloads access lists from disk.
         *
         * @since 0.9.40
         */
        public void timeReached() {
            if (!timersRunning.get())
                return;
            DISK_WRITER.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        record();
                        reload();
                        Syncer syncer = AccessFilter.this.syncer;
                        if (syncer != null)
                            syncer.schedule(SYNC_INTERVAL);
                    } catch (IOException bad) {
                        Log log = context.logManager().getLog(AccessFilter.class);
                       log.log(Log.CRIT, "Syncing access list for Tunnel Filter failed", bad);
                    }
                }
            });
         }
    }
}
