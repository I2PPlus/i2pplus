package net.i2p.router;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 *  Records live bandwidth samples every second for the minigraph.
 *  Maintains a 1200-entry ring buffer (20 min at 1s resolution) and
 *  flushes to /tmp/i2p-bandwidth.dat every 60 seconds so data survives
 *  page refreshes and router restarts.
 *
 *  Started automatically by Router.startup().
 *  Accessed by SidebarRenderer.getDataAttributes() to populate the
 *  canvas minigraph with real data instead of interpolated RRD points.
 *
 *  @since 0.9.70+
 */
public class BandwidthHistory extends SimpleTimer2.TimedEvent {

    /** Buffer: 1200 entries = 20 min at 1s */
    public static final int CAPACITY = 1200;
    /** Sample once per second */
    public static final long SAMPLE_INTERVAL = 1000;
    /** Flush to disk every 60s */
    private static final long FLUSH_INTERVAL = 60 * 1000;
    /** Discard file data older than 30 min */
    private static final long MAX_FILE_AGE = 30L * 60 * 1000;
    private static final String FILE_NAME = "i2p-bandwidth.dat";

    private static BandwidthHistory _instance;

    private final RouterContext _ctx;
    private final long[] _timestamps;
    private final long[] _rx;
    private final long[] _tx;
    private final int _capacity;
    private final File _file;
    private int _head;
    private int _count;
    private long _nextFlush;

    /**
     *  @param ctx may be null in unit tests
     */
    public BandwidthHistory(RouterContext ctx) {
        super(ctx != null ? ctx.simpleTimer2() : null);
        _ctx = ctx;
        _capacity = CAPACITY;
        _timestamps = new long[_capacity];
        _rx = new long[_capacity];
        _tx = new long[_capacity];
        _head = 0;
        _count = 0;
        String dir = System.getProperty("java.io.tmpdir", "/tmp");
        _file = new File(dir, FILE_NAME);
        load();
        _nextFlush = ctx != null ? ctx.clock().now() + FLUSH_INTERVAL : System.currentTimeMillis() + FLUSH_INTERVAL;
        _instance = this;
    }

    /** @return the singleton, or null if not yet created */
    public static BandwidthHistory getInstance() {
        return _instance;
    }

    /** @return the ring buffer capacity */
    public int getCapacity() {
        return _capacity;
    }

    /**
     *  Sample bandwidth limiter and record.  Reschedules itself at 1s.
     */
    @Override
    public void timeReached() {
        try {
            long rx = (long) _ctx.bandwidthLimiter().getReceiveBps();
            long tx = (long) _ctx.bandwidthLimiter().getSendBps();
            record(rx, tx);
            long now = _ctx.clock().now();
            if (now >= _nextFlush) {
                save();
                _nextFlush = now + FLUSH_INTERVAL;
            }
        } catch (Exception e) {
            // don't let transient errors kill the timer
        }
        schedule(SAMPLE_INTERVAL);
    }

    /**
     *  Add a sample to the ring buffer.
     */
    public synchronized void record(long rx, long tx) {
        long now = System.currentTimeMillis();
        // skip duplicate timestamps (shouldn't happen with 1s interval)
        if (_count > 0 && _timestamps[(_head - 1 + _capacity) % _capacity] == now) {
            return;
        }
        _timestamps[_head] = now;
        _rx[_head] = rx;
        _tx[_head] = tx;
        _head = (_head + 1) % _capacity;
        if (_count < _capacity) {
            _count++;
        }
    }

    /** @return number of stored samples */
    public synchronized int getCount() {
        return _count;
    }

    /**
     *  Return the last {@code n} receive values as a comma-separated string.
     *  Oldest value first, most recent last.  Empty string if no data.
     */
    public synchronized String getLastRx(int n) {
        return formatLast(n, _rx);
    }

    /**
     *  Return the last {@code n} send values as a comma-separated string.
     *  Oldest value first, most recent last.  Empty string if no data.
     */
    public synchronized String getLastTx(int n) {
        return formatLast(n, _tx);
    }

    /**
     *  Write the ring buffer to disk.
     */
    synchronized void save() {
        if (_count == 0) {return;}
        try {
            PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(_file)));
            try {
                int start = (_head - _count + _capacity) % _capacity;
                for (int i = 0; i < _count; i++) {
                    int idx = (start + i) % _capacity;
                    pw.print(_timestamps[idx]);
                    pw.print(',');
                    pw.print(_rx[idx]);
                    pw.print(',');
                    pw.println(_tx[idx]);
                }
            } finally {
                pw.close();
            }
        } catch (Exception e) {
            // don't let shutdown logging fail
        }
    }

    /**
     *  Load the ring buffer from disk.  Discards data older than MAX_FILE_AGE.
     */
    private synchronized void load() {
        if (!_file.exists()) {return;}
        long now = System.currentTimeMillis();
        long cutoff = now - MAX_FILE_AGE;
        List<long[]> entries = new ArrayList<>(_capacity);
        try (BufferedReader br = new BufferedReader(new FileReader(_file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {continue;}
                String[] parts = line.split(",");
                if (parts.length != 3) {continue;}
                try {
                    long ts = Long.parseLong(parts[0]);
                    long rx = Long.parseLong(parts[1]);
                    long tx = Long.parseLong(parts[2]);
                    if (ts < cutoff) {continue;}
                    entries.add(new long[]{ts, rx, tx});
                } catch (NumberFormatException nfe) {
                    // skip corrupt line
                }
            }
        } catch (IOException e) {
            Log log = _ctx != null ? _ctx.logManager().getLog(BandwidthHistory.class) : null;
            if (log != null) {
                log.warn("Failed to load bandwidth history", e);
            }
            return;
        }
        if (entries.isEmpty()) {return;}
        for (long[] entry : entries) {
            _timestamps[_head] = entry[0];
            _rx[_head] = entry[1];
            _tx[_head] = entry[2];
            _head = (_head + 1) % _capacity;
            if (_count < _capacity) {
                _count++;
            }
        }
    }

    /**
     *  Build a comma-separated string of the last n values from the given array.
     *  Must be called under synchronized(this).
     */
    private String formatLast(int n, long[] values) {
        if (n <= 0 || _count == 0) {return "";}
        int limit = Math.min(n, _count);
        StringBuilder sb = new StringBuilder(limit * 10);
        int start = (_head - limit + _capacity) % _capacity;
        for (int i = 0; i < limit; i++) {
            if (i > 0) {sb.append(',');}
            sb.append(values[(start + i) % _capacity]);
        }
        return sb.toString();
    }
}
