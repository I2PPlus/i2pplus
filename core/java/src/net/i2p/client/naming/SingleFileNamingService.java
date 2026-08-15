/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.FileUtil;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;

import java.nio.charset.StandardCharsets;
/**
 * A naming service based on a single file using the "hosts.txt" format.
 * Supports adds, removes, and listeners.
 *
 * All methods here are case-sensitive.
 * Conversion to lower case is done in HostsTxtNamingService.
 *
 * This does NOT provide .b32.i2p or {b64} resolution.
 * It also does not do any caching.
 * Use from HostsTxtNamingService or chain with another NamingService
 * via MetaNamingService if you need those features.
 *
 * @since 0.8.7
 */
public class SingleFileNamingService extends NamingService {

    private final File _file;
    private final ReentrantReadWriteLock _fileLock;

    /** Cached number of entries */
    private volatile int _size;

    /** Last write time */
    private long _lastWrite;

    /** hostname -> base64 key cache, guarded by _fileLock; valid when _cacheStamp >= file mtime */
    private final Map<String, String> _keyCache = new HashMap<>(64);
    private volatile long _cacheStamp;

    private volatile boolean _isClosed;

    /**
     * Application context.
     * @param context the application context
     * @param filename the hosts file name
     */
    public SingleFileNamingService(I2PAppContext context, String filename) {
        super(context);
        File file = new File(filename);
        if (!file.isAbsolute()) file = new File(context.getRouterDir(), filename);
        _file = file;
        _fileLock = new ReentrantReadWriteLock(true);
    }

    /**
     * Return the file's absolute path.
     *
     *  @return the file's absolute path
     */
    @Override
    public String getName() {
        return _file.getAbsolutePath();
    }

    /**
     *  Will strip a "www." prefix and retry if lookup fails
     *
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param lookupOptions unused, may be null
     *  @param storedOptions unused, may be null
     */
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        if (hostname.endsWith(".i2p.alt")) {
            // RFC 9476
            hostname = hostname.substring(0, hostname.length() - 4);
        }
        try {
            String key = getKey(hostname);
            if (key == null && hostname.startsWith("www.") && hostname.length() > 7)
                key = getKey(hostname.substring(4));
            if (key != null) return lookupBase64(key);
        } catch (IOException ioe) {
            if (_file.exists()) _log.error("Error loading hosts file " + _file, ioe);
            else if (_log.shouldWarn()) _log.warn("Error loading hosts file " + _file, ioe);
        }
        return null;
    }

    /**
     * Reverse lookup a destination to a hostname.
     *
     *  @param options unused, may be null
     */
    @Override
    public String reverseLookup(Destination dest, Properties options) {
        String destkey = dest.toBase64();
        getReadLock();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
            String line = null;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#")) continue;
                if (line.indexOf('#') > 0) line = line.substring(0, line.indexOf('#')).trim(); // trim off any end of line comment
                int split = line.indexOf('=');
                if (split <= 0) continue;
                if (destkey.equals(line.substring(split + 1))) return line.substring(0, split);
            }
            return null;
        } catch (IOException ioe) {
            if (_file.exists()) _log.error("Error loading hosts file " + _file, ioe);
            else if (_log.shouldWarn()) _log.warn("Error loading hosts file " + _file, ioe);
            return null;
        } finally {
            releaseReadLock();
        }
    }

    /**
     *  Better than DataHelper.loadProps(), doesn't load the whole file into memory,
     *  and stops when it finds a match.
     *
     *  @param host case-sensitive; caller should convert to lower case
     *  @return the key
     */
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    private String getKey(String host) throws IOException {
        getReadLock();
        try {
            if (_file.lastModified() > _cacheStamp) {
                loadKeyCacheLocked();
            }
            return _keyCache.get(host);
        } finally {
            releaseReadLock();
        }
    }

    /**
     *  Full-scan parse of the hosts file into _keyCache (hostname -> base64 key).
     *  Caller must hold the read lock. Sets _cacheStamp on success.
     *  @since 0.9.71+
     */
    private void loadKeyCacheLocked() throws IOException {
        if (!_file.exists()) {
            _keyCache.clear();
            _cacheStamp = 0;
            return;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
            _keyCache.clear();
            String line = null;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String clean = line.indexOf('#') > 0 ? line.substring(0, line.indexOf('#')).trim() : line.trim();
                int split = clean.indexOf('=');
                if (split <= 0) continue;
                _keyCache.put(clean.substring(0, split), clean.substring(split + 1));
            }
            _cacheStamp = _file.lastModified();
        }
    }

    /**
     * Store a hostname-destination mapping.
     *
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param options if non-null, any prefixed with '=' will be appended
     *                 in subscription format
     */
    @Override
    public boolean put(String hostname, Destination d, Properties options) {
        // try easy way first, most adds are not replaces
        if (putIfAbsent(hostname, d, options)) return true;
        if (!getWriteLock()) return false;
        try {
            if (_isClosed) return false;
            File tmp = SecureFile.createTempFile(
                    "temp-", ".tmp", _file.getAbsoluteFile().getParentFile());
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), StandardCharsets.UTF_8))) {
                if (_file.exists()) {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
                        String line = null;
                        String search = hostname + '=';
                        while ((line = in.readLine()) != null) {
                            if (line.startsWith(search)) continue;
                            out.write(line);
                            out.newLine();
                        }
                    }
                }
                out.write(hostname);
                out.write('=');
                out.write(d.toBase64());
                // subscription options
                if (options != null) writeOptions(options, out);
                out.newLine();
            }
            boolean success = FileUtil.rename(tmp, _file);
            if (success) {
                _cacheStamp = 0;
                for (NamingServiceListener nsl : _listeners) {
                    nsl.entryChanged(this, hostname, d, options);
                }
            }
            return success;
        } catch (IOException ioe) {
            _log.error("Error adding " + hostname, ioe);
            return false;
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Store a hostname-destination mapping if not already present.
     *
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param options if non-null, any prefixed with '=' will be appended
     *                 in subscription format
     */
    @Override
    public boolean putIfAbsent(String hostname, Destination d, Properties options) {
        if (!getWriteLock()) return false;
        try {
            if (_isClosed) return false;
            // simply check if present, and if not, append
            try {
                if (getKey(hostname) != null) return false;
            } catch (IOException ioe) {
                if (_file.exists()) {
                    _log.error("Error adding " + hostname, ioe);
                    return false;
                }
                // else new file
            }
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(_file, true), StandardCharsets.UTF_8))) {
                // FIXME fails if previous last line didn't have a trailing \n
                out.write(hostname);
                out.write('=');
                out.write(d.toBase64());
                // subscription options
                if (options != null) writeOptions(options, out);
                out.write('\n');
            }
            for (NamingServiceListener nsl : _listeners) {
                nsl.entryAdded(this, hostname, d, options);
            }
            _cacheStamp = 0;
            return true;
        } catch (IOException ioe) {
            _log.error("Error adding " + hostname, ioe);
            return false;
        } finally {
            releaseWriteLock();
        }
    }

    /**
     *  Write the subscription options part of the line (including the #!).
     *  Only options starting with '=' (if any) are written (with the '=' stripped).
     *  Does not write a newline.
     *
     *  @param options non-null
     *  @param out the writer to write to
     *  @since 0.9.26, package private since 0.9.30, public since 0.9.31
     */
    public static void writeOptions(Properties options, Writer out) throws IOException {
        boolean started = false;
        for (Map.Entry<Object, Object> e : options.entrySet()) {
            String k = (String) e.getKey();
            if (!k.startsWith("=")) continue;
            k = k.substring(1);
            String v = (String) e.getValue();
            if (started) {
                out.write(HostTxtEntry.PROP_SEPARATOR);
            } else {
                started = true;
                out.write(HostTxtEntry.PROPS_SEPARATOR);
            }
            out.write(k);
            out.write('=');
            out.write(v);
        }
    }

    /**
     * Remove a hostname from the naming service.
     *
     *  @param hostname case-sensitive; caller should convert to lower case
     *  @param options unused, may be null
     */
    @Override
    public boolean remove(String hostname, Properties options) {
        if (!getWriteLock()) return false;
        try {
            if (!_file.exists()) return false;
            if (_isClosed) return false;
            File tmp = SecureFile.createTempFile(
                    "temp-", ".tmp", _file.getAbsoluteFile().getParentFile());
            boolean success = false;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024);
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), StandardCharsets.UTF_8))) {
                String line = null;
                String search = hostname + '=';
                while ((line = in.readLine()) != null) {
                    if (line.startsWith(search)) {
                        success = true;
                        continue;
                    }
                    out.write(line);
                    out.newLine();
                }
            }
            if (!success) {
                tmp.delete();
                return false;
            }
            success = FileUtil.rename(tmp, _file);
            if (success) {
                _cacheStamp = 0;
                for (NamingServiceListener nsl : _listeners) {
                    nsl.entryRemoved(this, hostname);
                }
            }
            return success;
        } catch (IOException ioe) {
            _log.error("Error removing " + hostname, ioe);
            return false;
        } finally {
            releaseWriteLock();
        }
    }

    /**
     * Return all entries matching the options.
     *
     * @param options null OK, or as follows:
     *                Key "search": return only those matching substring
     *                Key "startsWith": return only those starting with
     *                                  ("[0-9]" allowed)
     *                Key "skip": number of matching entries to skip
     *                Key "limit": maximum number of matching entries to return
     * @return the entries
     * @since 0.9.71+
     */
    @Override
    public Map<String, Destination> getEntries(Properties options) {
        if (!_file.exists()) return Collections.emptyMap();
        String searchOpt = null;
        String startsWith = null;
        int skip = 0;
        int limit = 0;
        if (options != null) {
            searchOpt = options.getProperty("search");
            startsWith = options.getProperty("startsWith");
            String s = options.getProperty("skip");
            if (s != null) {
                try { skip = Integer.parseInt(s); } catch (NumberFormatException nfe) { /* ignored */ }
            }
            s = options.getProperty("limit");
            if (s != null) {
                try { limit = Integer.parseInt(s); } catch (NumberFormatException nfe) { /* ignored */ }
            }
        }
        if (skip < 0) skip = 0;
        if (limit < 0) limit = 0;
        if (_log.shouldDebug())
            _log.debug("Searching " + " starting with " + startsWith + " search string " + searchOpt
                       + " skip " + skip + " limit " + limit);
        getReadLock();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
            String line = null;
            Map<String, Destination> rv = new HashMap<>();
            int skipped = 0;
            while ((line = in.readLine()) != null) {
                if (line.length() <= 0) continue;
                if (startsWith != null) {
                    if (startsWith.equals("[0-9]")) {
                        if (line.charAt(0) < '0' || line.charAt(0) > '9') continue;
                    } else if (!line.startsWith(startsWith)) {
                        continue;
                    }
                }
                if (line.startsWith("#")) continue;
                if (line.indexOf('#') > 0) line = line.substring(0, line.indexOf('#')).trim(); // trim off any end of line comment
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                if (searchOpt != null && key.indexOf(searchOpt) < 0) continue;
                if (skipped < skip) {
                    skipped++;
                    continue;
                }
                if (limit > 0 && rv.size() >= limit) break;
                String b64 = line.substring(split + 1); // .trim() ??????????????
                try {
                    Destination dest = new Destination(b64);
                    rv.put(key, dest);
                } catch (DataFormatException dfe) { /* ignored */ }
            }
            if (searchOpt == null && startsWith == null && skip == 0 && limit == 0) {
                _lastWrite = _file.lastModified();
                _size = rv.size();
            }
            return rv;
        } catch (IOException ioe) {
            _log.error("getEntries error", ioe);
            return Collections.emptyMap();
        } finally {
            releaseReadLock();
        }
    }

    /**
     *  Overridden since we store base64 natively.
     *
     *  @param options null OK, or as follows:
     *                 Key "search": return only those matching substring
     *                 Key "startsWith": return only those starting with
     *                                   ("[0-9]" allowed)
     *
     *  @return all mappings (matching the options if non-null)
     *          or empty Map if none.
     *          Returned Map is not sorted.
     *
     *  @since 0.9.20
     */
    @Override
    public Map<String, String> getBase64Entries(Properties options) {
        if (!_file.exists()) return Collections.emptyMap();
        String searchOpt = null;
        String startsWith = null;
        if (options != null) {
            searchOpt = options.getProperty("search");
            startsWith = options.getProperty("startsWith");
        }
        getReadLock();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
            String line = null;
            Map<String, String> rv = new HashMap<>();
            while ((line = in.readLine()) != null) {
                if (line.length() <= 0) continue;
                if (startsWith != null) {
                    if (startsWith.equals("[0-9]")) {
                        if (line.charAt(0) < '0' || line.charAt(0) > '9') continue;
                    } else if (!line.startsWith(startsWith)) {
                        continue;
                    }
                }
                if (line.startsWith("#")) continue;
                if (line.indexOf('#') > 0) line = line.substring(0, line.indexOf('#')).trim(); // trim off any end of line comment
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                if (searchOpt != null && key.indexOf(searchOpt) < 0) continue;
                String b64 = line.substring(split + 1); // .trim() ??????????????
                if (b64.length() < 387) continue;
                rv.put(key, b64);
            }
            if (searchOpt == null && startsWith == null) {
                _lastWrite = _file.lastModified();
                _size = rv.size();
            }
            return rv;
        } catch (IOException ioe) {
            _log.error("getEntries error", ioe);
            return Collections.emptyMap();
        } finally {
            releaseReadLock();
        }
    }

    /**
     *  Overridden for efficiency.
     *  Output is not sorted.
     *
     *  @param options unused, may be null
     *  @since 0.9.20
     */
    @Override
    public void export(Writer out, Properties options) throws IOException {
        out.write("# Address book: ");
        out.write(getName());
        final String nl = System.getProperty("line.separator", "\n");
        out.write(nl);
        out.write("# Exported: ");
        out.write(Instant.now().toString());
        out.write(nl);
        getReadLock();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
            String line = null;
            while ((line = in.readLine()) != null) {
                out.write(line);
                out.write(nl);
            }
        } finally {
            releaseReadLock();
        }
    }

    /**
     * Return all known host names.
     *
     *  @param options unused, may be null
     *  @return all known host names, unsorted
     */
    @Override
    public Set<String> getNames(Properties options) {
        if (!_file.exists()) return Collections.emptySet();
        getReadLock();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
            String line = null;
            Set<String> rv = new HashSet<>();
            while ((line = in.readLine()) != null) {
                if (line.length() <= 0) continue;
                if (line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                rv.add(key);
            }
            return rv;
        } catch (IOException ioe) {
            _log.error("getNames error", ioe);
            return Collections.emptySet();
        } finally {
            releaseReadLock();
        }
    }

    /**
     * Return the number of entries.
     *
     *  @param options unused, may be null
     */
    @Override
    public int size(Properties options) {
        if (!_file.exists()) return 0;
        getReadLock();
        try {
            if (_file.lastModified() <= _lastWrite) return _size;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(_file), StandardCharsets.UTF_8), 16 * 1024)) {
                String line = null;
                int rv = 0;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("#") || line.length() <= 0) continue;
                    rv++;
                }
                _lastWrite = _file.lastModified();
                _size = rv;
                return rv;
            }
        } catch (IOException ioe) {
            _log.error("size() error", ioe);
            return -1;
        } finally {
            releaseReadLock();
        }
    }

    @Override
    public void shutdown() {
        if (!getWriteLock()) return;
        try {
            _isClosed = true;
        } finally {
            releaseWriteLock();
        }
    }

    private void getReadLock() {
        _fileLock.readLock().lock();
    }

    private void releaseReadLock() {
        _fileLock.readLock().unlock();
    }

    /**
     * Try to acquire the write lock.
     * @return true if the lock was acquired
     */
    private boolean getWriteLock() {
        try {
            boolean rv = _fileLock.writeLock().tryLock(10000, TimeUnit.MILLISECONDS);
            if ((!rv) && _log.shouldWarn())
                _log.warn("no lock, size is: " + _fileLock.getQueueLength(), new Exception("rats"));
            return rv;
        } catch (InterruptedException ie) { /* ignored */ }
        return false;
    }

    private void releaseWriteLock() {
        _fileLock.writeLock().unlock();
    }

}
