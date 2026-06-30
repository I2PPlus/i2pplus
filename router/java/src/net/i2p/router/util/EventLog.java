package net.i2p.router.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 * Event logging utility with caching and file-based persistence.
 * <p>
 * Provides simple event logging for occasional router events
 * with efficient caching for read operations and file-based
 * persistence. Does not maintain open file handles to
 * avoid resource leaks.
 * <p>
 * Supports event categorization and timestamp tracking with
 * configurable caching behavior. Optimized for low-frequency
 * logging scenarios where performance is more important than
 * comprehensive event tracking.
 * <p>
 * Includes predefined event types for router lifecycle events,
 * network changes, and critical system events. Provides
 * both append-only and read-write access patterns with proper
 * synchronization for thread safety.
 *
 * @since 0.9.3
 */
public class EventLog {

    private final I2PAppContext _context;
    private final File _file;
    /** event to cached map */
    private final Map<String, SortedMap<Long, String>> _cache;
    /** event to starting time of cached map */
    private final Map<String, Long> _cacheTime;

    /** max entries per cache type */
    private static final int MAX_CACHE_ENTRIES = 32;

    /** for convenience, not required */
    public static final String ABORTED = "aborted";
    public static final String BECAME_FLOODFILL = "becameFloodfill";
    public static final String CHANGE_IP = "changeIP";
    public static final String CHANGE_PORT = "changePort";
    public static final String CLOCK_SHIFT = "clockShift";
    public static final String CRASHED = "crashed";
    public static final String CRITICAL = "critical";
    public static final String DEADLOCK = "deadlock";
    public static final String INSTALLED = "installed";
    public static final String INSTALL_FAILED = "installFailed";
    public static final String NETWORK = "network";
    public static final String NEW_IDENT = "newIdent";
    public static final String NOT_FLOODFILL = "disabledFloodfill";
    public static final String OOM = "oom";
    public static final String REACHABILITY = "reachability";
    public static final String REKEYED = "rekeyed";
    public static final String RESEED = "reseed";
    public static final String SOFT_RESTART = "softRestart";
    public static final String STARTED = "started";
    public static final String STOPPED = "stopped";
    public static final String UPDATED = "updated";
    public static final String WATCHDOG = "watchdog";

    /**
     *  Evict oldest entries if cache exceeds limit.
     */
    private synchronized void evictIfNeeded() {
        if (_cache.size() <= MAX_CACHE_ENTRIES)
            return;
        long oldestTime = Long.MAX_VALUE;
        String oldestKey = null;
        for (Map.Entry<String, Long> e : _cacheTime.entrySet()) {
            long t = e.getValue();
            if (t < oldestTime) {
                oldestTime = t;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            _cache.remove(oldestKey);
            _cacheTime.remove(oldestKey);
        }
    }

    /**
     *  @param file should be absolute
     */
    public EventLog(I2PAppContext ctx, File file) {
        //if (!file.isAbsolute())
        //    throw new IllegalArgumentException();
        _context = ctx;
        _file = file;
        _cache = new HashMap<>(4);
        _cacheTime = new HashMap<>(4);
    }

    /**
     *  Append an event. Fails silently.
     *  @param event no spaces, e.g. "started"
     *  @throws IllegalArgumentException if event contains a space or newline
     */
    public void addEvent(String event) {
        addEvent(event, null);
    }

    /**
     *  Append an event. Fails silently.
     *  @param event no spaces or newlines, e.g. "started"
     *  @param info no newlines, may be blank or null
     *  @throws IllegalArgumentException if event contains a space or either contains a newline
     */
    public synchronized void addEvent(String event, String info) {
        if (event.contains(" ") || event.contains("\n") ||
            (info != null && info.contains("\n")))
            throw new IllegalArgumentException();
        _cache.remove(event);
        _cacheTime.remove(event);
        try (OutputStream out = new SecureFileOutputStream(_file, true)) {
            StringBuilder buf = new StringBuilder(128);
            buf.append(_context.clock().now()).append(' ').append(event);
            if (info != null && info.length() > 0)
                buf.append(' ').append(info);
            if (SystemVersion.isWindows())
                buf.append('\r');
            buf.append('\n');
            out.write(buf.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioe) { /* ignored */ }
    }

    /**
     *  Caches.
     *  Fails silently.
     *  @param event matching this event only, case sensitive
     *  @param since since this time, 0 for all
     *  @return non-null, Map of times to (possibly empty) info strings, sorted, earliest first, unmodifiable
     */
    public synchronized SortedMap<Long, String> getEvents(String event, long since) {
        SortedMap<Long, String> rv = _cache.get(event);
        if (rv != null) {
            Long cacheTime = _cacheTime.get(event);
            if (cacheTime != null && since >= cacheTime.longValue())
                return rv.tailMap(Long.valueOf(since));
        }
        rv = new TreeMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(_file), StandardCharsets.UTF_8))) {
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    String[] s = DataHelper.split(line.trim(), " ", 3);
                    if (!s[1].equals(event))
                        continue;
                    long time = Long.parseLong(s[0]);
                    if (time <= since)
                        continue;
                    Long ltime = Long.valueOf(time);
                    String info = s.length > 2 ? s[2] : "";
                    rv.put(ltime, info);
                } catch (IndexOutOfBoundsException ioobe) { /* ignored */ } catch (NumberFormatException nfe) { /* ignored */ }
            }
            rv = Collections.unmodifiableSortedMap(rv);
            _cache.put(event, rv);
            _cacheTime.put(event, Long.valueOf(since));
            evictIfNeeded();
        } catch (IOException ioe) { /* ignored */ }
        return rv;
    }

    /**
     *  All events since a given time.
     *  Does not cache. Fails silently.
     *  Values in the returned map have the format "event[ info]".
     *  Events do not contain spaces.
     *
     *  @param since since this time, 0 for all
     *  @return non-null, Map of times to info strings, sorted, earliest first, unmodifiable
     *  @since 0.9.14
     */
    public synchronized SortedMap<Long, String> getEvents(long since) {
        SortedMap<Long, String> rv = new TreeMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(_file), StandardCharsets.UTF_8))) {
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    String[] s = DataHelper.split(line.trim(), " ", 2);
                    if (s.length < 2)
                        continue;
                    long time = Long.parseLong(s[0]);
                    if (time <= since)
                        continue;
                    Long ltime = Long.valueOf(time);
                    rv.put(ltime, s[1]);
                } catch (IndexOutOfBoundsException ioobe) { /* ignored */ } catch (NumberFormatException nfe) { /* ignored */ }
            }
            rv = Collections.unmodifiableSortedMap(rv);
        } catch (IOException ioe) { /* ignored */ }
        return rv;
    }

    /**
     *  Timestamp of last event.
     *
     *  @param event matching this event, case sensitive
     *  @param since since this time, 0 for all
     *  @return last event time, or 0 for none
     *  @since 0.9.47
     */
    public synchronized long getLastEvent(String event, long since) {
        long rv = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(_file), StandardCharsets.UTF_8))) {
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    String[] s = DataHelper.split(line.trim(), " ", 3);
                    if (s.length < 2)
                        continue;
                    if (!s[1].equals(event))
                        continue;
                    long time = Long.parseLong(s[0]);
                    if (time <= since)
                        continue;
                    rv = time;
                } catch (NumberFormatException nfe) { /* ignored */ }
            }
        } catch (IOException ioe) { /* ignored */ }
        return rv;
    }
}
