package net.i2p.i2ptunnel.access;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.client.streaming.StatefulConnectionFilter;

/**
 * A filter for incoming connections which can be configured
 * based on access list rules.
 *
 * It keeps a track of known destinations - those defined in existing access
 * lists and unknown ones - those who are not defined in such lists but have
 * recently attempted to connect to us.
 *
 * Every SYNC_INTERVAL seconds the access lists are reloaded from disk if they
 * have changed according to file modification time.  This
 * allows the user to edit them.  Also, if any recorders are defined in the
 * access rules, they will write to disk at such interval if there have been
 * any new breaches.
 *
 * @since 0.9.40
 */
class AccessFilter implements StatefulConnectionFilter {

    private static final long PURGE_INTERVAL = 1000;
//    private static final long SYNC_INTERVAL = 10 * 1000;
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
     * @param context the context, used for scheduling and timer purposes
     * @param definition definition of this filter
     */
    AccessFilter(I2PAppContext context, FilterDefinition definition)
            throws IOException {
        this.context = context;
        this.definition = definition;

        reload();
    }

    @Override
    public void start() {
        if (timersRunning.compareAndSet(false, true)) {
            new Purger();
            syncer = new Syncer();
        }
    }

    @Override
    public void stop() {
        timersRunning.set(false);
        syncer = null;
    }

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
                    reader = new BufferedReader(new FileReader(file));
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
                        new SecureFileOutputStream(file)));
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
                       log.log(Log.CRIT, "Syncing access list for tunnel filter failed", bad);
                    }
                }
            });
         }
    }
}
