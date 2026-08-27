package org.klomp.snark;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Self-contained bandwidth sampler for the snark screen log graph.
 *
 *  Every SAMPLE_INTERVAL_MS, snapshots the cumulative traffic counters (peer wire
 *  rates summed over torrents, datagram counters fed by DatagramSender and the
 *  receive paths, HTTP tracker counters fed by I2PSnarkUtil) and stores per-interval
 *  byte totals in a fixed ring. Runs for the webapp's lifetime, independent of any
 *  browser being open, so the graph stays continuous.
 *
 *  Flushes to the tmp dir every FLUSH_INTERVAL_MS so history survives webapp or
 *  router restarts; entries older than WINDOW_MS are dropped on load.
 *
 *  @since 0.9.71+
 */
public class BandwidthGraph extends SimpleTimer2.TimedEvent {

    /** Sample once per minute */
    public static final long SAMPLE_INTERVAL_MS = 60 * 1000;
    /** Ring capacity: 240 samples = 4 hours at 60 s */
    public static final int CAPACITY = 240;
    /** Window covered by a full ring, in ms; older persisted samples are dropped */
    public static final long WINDOW_MS = CAPACITY * SAMPLE_INTERVAL_MS;
    /** Flush to disk every five minutes */
    private static final long FLUSH_INTERVAL_MS = 5 * 60 * 1000;

    private static final String FILE_NAME = "i2p-snark-bandwidth.dat";

    // Cumulative counter hooks; instrumentation sites add raw bytes here.

    private static final AtomicLong _datagramTx = new AtomicLong();
    private static final AtomicLong _datagramRx = new AtomicLong();
    private static final AtomicLong _httpTx = new AtomicLong();
    private static final AtomicLong _httpRx = new AtomicLong();

    /** Record datagram bytes sent (DHT + UDP tracker funnel). */
    public static void datagramSent(int bytes) { _datagramTx.addAndGet(bytes); }

    /** Record datagram bytes received (DHT + UDP tracker listeners). */
    public static void datagramReceived(int bytes) { _datagramRx.addAndGet(bytes); }

    /** Record an HTTP tracker/torrent fetch: approximate request size plus response size. */
    public static void httpTransferred(long txBytes, long rxBytes) {
        if (txBytes > 0) {_httpTx.addAndGet(txBytes);}
        if (rxBytes > 0) {_httpRx.addAndGet(rxBytes);}
    }

    private static volatile BandwidthGraph _instance;

    /**
     *  Start the sampler for this snark manager, loading prior history from the
     *  tmp-dir file when present. Subsequent calls are no-ops.
     *
     *  @since 0.9.71+
     */
    public static synchronized void start(SnarkManager manager) {
        if (_instance != null) {return;}
        _instance = new BandwidthGraph(manager);
    }

    /**
     *  Stop the sampler and flush pending history. Safe to call repeatedly.
     *
     *  @since 0.9.71+
     */
    public static synchronized void stop() {
        BandwidthGraph g = _instance;
        if (g == null) {return;}
        _instance = null;
        g.cancel();
        g.flush();
    }

    /**
     *  The current sample version: increments once per stored sample. Clients use it
     *  to detect new data without parsing the samples themselves.
     *
     *  @return monotonically increasing sample counter
     *  @since 0.9.71+
     */
    public static long getVersion() {
        BandwidthGraph g = _instance;
        return g != null ? g._version : 0;
    }

    /**
     *  Serialize the ring oldest-first as "timestampSec,rxKB,txKB;...".
     *  Values are KB (bytes/1024 rounded) to keep the wire format compact.
     *
     *  @return compact CSV of every stored sample, empty string when none
     *  @since 0.9.71+
     */
    public static String getSamples() {
        BandwidthGraph g = _instance;
        return g != null ? g.serialize() : "";
    }

    private final SnarkManager _manager;
    private final Log _log;
    private final long[] _times = new long[CAPACITY];
    private final long[] _rx = new long[CAPACITY];
    private final long[] _tx = new long[CAPACITY];
    private int _head;
    private int _count;
    private long _version;
    private long _lastFlush;

    /** Counter snapshots taken at the previous tick, for delta computation. */
    private long _lastDatagramTx, _lastDatagramRx, _lastHttpTx, _lastHttpRx;
    private long _lastSampleTime;

    private BandwidthGraph(SnarkManager manager) {
        super(I2PAppContext.getGlobalContext().simpleTimer2());
        _manager = manager;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(BandwidthGraph.class);
        load();
        _lastSampleTime = System.currentTimeMillis();
        _lastFlush = _lastSampleTime;
        schedule(SAMPLE_INTERVAL_MS);
    }

    /**
     *  One sampling pass: derive interval bytes from counter deltas plus current
     *  peer-wire rates, append to the ring, and flush periodically.
     */
    public synchronized void timeReached() {
        try {
            sample();
            long now = System.currentTimeMillis();
            if (now - _lastFlush >= FLUSH_INTERVAL_MS) {
                _lastFlush = now;
                flush();
            }
        } catch (Exception e) {
            // A bad pass must not kill the reschedule chain.
            if (_log.shouldWarn()) {_log.warn("Bandwidth graph sample failed", e);}
        } finally {
            schedule(SAMPLE_INTERVAL_MS);
        }
    }

    /**
     *  Snapshot counters, compute deltas, and append one ring entry.
     */
    private void sample() {
        SnarkManager mgr = _manager;
        if (mgr == null) {return;}
        long now = System.currentTimeMillis();
        long elapsedMs = now - _lastSampleTime;
        _lastSampleTime = now;

        long datagramTx = _datagramTx.get(), datagramRx = _datagramRx.get();
        long httpTx = _httpTx.get(), httpRx = _httpRx.get();

        long tx = delta(datagramTx, _lastDatagramTx) + delta(httpTx, _lastHttpTx);
        long rx = delta(datagramRx, _lastDatagramRx) + delta(httpRx, _lastHttpRx);
        _lastDatagramTx = datagramTx;
        _lastDatagramRx = datagramRx;
        _lastHttpTx = httpTx;
        _lastHttpRx = httpRx;

        // Peer-wire rates are Bps; scale to this interval's bytes so all sources
        // share one unit.
        long peerDown = 0, peerUp = 0;
        for (Snark snark : mgr.getTorrents()) {
            if (!snark.isStopped()) {
                peerDown += snark.getDownloadRate();
                peerUp += snark.getUploadRate();
            }
        }
        double secs = elapsedMs / 1000.0;
        rx += Math.round(peerDown * secs);
        tx += Math.round(peerUp * secs);

        _times[_head] = now / 1000;
        _rx[_head] = Math.max(rx, 0);
        _tx[_head] = Math.max(tx, 0);
        _head = (_head + 1) % CAPACITY;
        if (_count < CAPACITY) {_count++;}
        _version++;
    }

    /**
     *  Counter delta with reset detection: a cumulative source that shrank was
     *  reset, so only the post-reset amount is attributable to this interval.
     */
    private static long delta(long current, long previous) {
        return current >= previous ? current - previous : current;
    }

    /**
     *  @return CSV "ts,rxKB,txKB;..." oldest-first — KB keeps the data URL small
     */
    private synchronized String serialize() {
        StringBuilder buf = new StringBuilder(48 * _count);
        int start = (_head - _count + CAPACITY) % CAPACITY;
        for (int i = 0; i < _count; i++) {
            int idx = (start + i) % CAPACITY;
            long rxKB = (_rx[idx] + 512) / 1024;
            long txKB = (_tx[idx] + 512) / 1024;
            buf.append(_times[idx]).append(',')
               .append(rxKB).append(',')
               .append(txKB);
            if (i < _count - 1) {buf.append(';');}
        }
        return buf.toString();
    }

    /**
     *  Write the serialized ring to the tmp-dir file atomically-ish (write temp,
     *  rename), ignoring all errors — persistence is best-effort by design.
     */
    private synchronized void flush() {
        try {
            File file = file();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), serialize().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(),
                       java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {_log.warn("Bandwidth graph flush failed", ioe);}
        }
    }

    /**
     *  Load persisted samples younger than the window, seeding the ring so a fresh
     *  webapp start still shows recent history.
     */
    private void load() {
        File file = file();
        if (!file.exists()) {return;}
        try {
            String csv = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            // Old persisted files store bytes; new ones store KB. Detect and discard old to avoid double-scaling.
            if (csv.contains(",") && csv.length() > 0) {
                boolean looksLikeBytes = false;
                for (String e : csv.split(";")) {
                    String[] p = e.split(",");
                    if (p.length == 3) {
                        try {
                            long rx = Long.parseLong(p[1].trim());
                            long tx = Long.parseLong(p[2].trim());
                            if (rx > 100000 || tx > 100000) { looksLikeBytes = true; break; }
                        } catch (NumberFormatException nfe) {}
                    }
                }
                if (looksLikeBytes) { Files.deleteIfExists(file.toPath()); return; }
            }
            long cutoff = (System.currentTimeMillis() - WINDOW_MS) / 1000;
            for (String entry : csv.split(";")) {
                String[] parts = entry.split(",");
                if (parts.length != 3) {continue;}
                try {
                    long ts = Long.parseLong(parts[0].trim());
                    long rx = Long.parseLong(parts[1].trim());
                    long tx = Long.parseLong(parts[2].trim());
                    if (ts < cutoff || ts > System.currentTimeMillis() / 1000 + 5) {continue;}
                    // Persisted values are now KB; convert back to bytes for internal ring
                    _times[_head] = ts;
                    _rx[_head] = rx * 1024;
                    _tx[_head] = tx * 1024;
                    _head = (_head + 1) % CAPACITY;
                    if (_count < CAPACITY) {_count++;}
                    _version++;
                } catch (NumberFormatException nfe) {}
            }
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {_log.warn("Bandwidth graph load failed", ioe);}
        }
    }

    /**
     *  Persistence location: the JVM tmp dir (defaulting to /tmp), matching the
     *  BandwidthHistory pattern.
     */
    private static File file() {
        String dir = System.getProperty("java.io.tmpdir", "/tmp");
        return new File(dir, FILE_NAME);
    }
}
