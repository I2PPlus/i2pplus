package net.i2p.i2ptunnel.access;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Filter definition element that reads destination hashes from a file.
 * <p>
 * The file is the policy, so a read that fails must not take the policy away with
 * it: the parsed list is staged in a local map and only published once the whole
 * file has been read, and a failed reload leaves the previous list in force and
 * retries on the next sync.
 *
 * @since 0.9.40
 */
class FileFilterDefinitionElement extends FilterDefinitionElement {

    /** Logger for this class. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(FileFilterDefinitionElement.class);

    /**
     * Most destinations read from one file, so a hand-edited or generated list
     * cannot grow the tracked set without bound.
     */
    private static final int MAX_ENTRIES = 65536;

    /** The file of blocked destinations. */
    private final File file;
    /** The destinations of the last successful read. */
    private final Map<Hash, DestTracker> lastLoaded = new HashMap<>();
    /** The file's lastModified() at the last successful read. */
    private volatile long lastModified;
    /** The file's length() at the last successful read. */
    private volatile long lastLength;
    /** Whether a read has ever succeeded. */
    private volatile boolean loaded;
    /** Whether the current read failure has already been logged. */
    private volatile boolean failureLogged;

    /**
     * Create a filter definition element that reads destination hashes from a file.
     *
     * @param file file to read the remote destinations from
     * @param threshold threshold to apply to all those destinations
     */
    FileFilterDefinitionElement(File file, Threshold threshold) {
        super(threshold);
        this.file = file;
    }

    /**
     * Adds the destinations of the file to the given working set, re-reading the
     * file only when its (lastModified, length) signature changed since the last
     * successful read.
     * <p>
     * A reload that fails while a previous list is in force is logged once, keeps
     * that list, and does not throw - the next sync retries the read. Only a
     * failure to read the file the first time is reported to the caller, so a
     * tunnel configured with an unreadable list fails at startup instead of
     * running unfiltered.
     *
     * @param map the working set the filter is rebuilt from
     * @throws IOException if the file has never been read successfully
     */
    @Override
    public void update(Map<Hash, DestTracker> map) throws IOException {
        if (!(file.exists() && file.isFile())) {return;}
        long modified = file.lastModified();
        long length = file.length();
        // Same (lastModified, length) signature the breach-recorder files use: an
        // unchanged file is served from the snapshot, and a file whose timestamp
        // moved backwards (restored from a backup, a clock change) is still
        // re-read when its size differs.
        if (loaded && modified == lastModified && length == lastLength) {
            publishSnapshot(map);
            return;
        }

        Map<Hash, DestTracker> fresh = new HashMap<>();
        try {
            readInto(fresh);
        } catch (IOException failed) {
            // Clearing the cache before the read, as this class used to, meant one
            // disk error dropped every deny and throttle entry until the file's
            // timestamp moved on.
            publishSnapshot(map);
            if (!loaded)
                throw failed;
            if (!failureLogged) {
                failureLogged = true;
                _log.error("Reloading the access list failed -> Keeping the previous entries * File: " + file, failed);
            }
            return;
        }

        synchronized (lastLoaded) {
            lastLoaded.clear();
            lastLoaded.putAll(fresh);
        }
        // Stamped from the file itself, not the wall clock, so a skewed clock
        // cannot make an unread file look already loaded.
        lastModified = modified;
        lastLength = length;
        loaded = true;
        failureLogged = false;
        publish(fresh, map);
    }

    /**
     * Read the destination hashes of the file into the given map.
     * <p>
     * An unparseable line is logged and skipped; it is not worth losing the rest
     * of the list for one bad entry.
     *
     * @param into map to add the destinations found to, keyed by destination hash
     * @throws IOException if the file cannot be read
     * @since 0.9.72+
     */
    private void readInto(Map<Hash, DestTracker> into) throws IOException {
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Trimmed: a list edited on Windows ends its lines with a CR, and
                // the entry parser wants exactly 60 characters.
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                if (into.size() >= MAX_ENTRIES) {
                    skipped++;
                    continue;
                }
                Hash hash;
                try {
                    hash = fromBase32(line);
                } catch (InvalidDefinitionException bad32) {
                    _log.error("Invalid access list entry \"" + line + "\" in " + file, bad32);
                    continue;
                }
                if (into.containsKey(hash))
                    continue;
                into.put(hash, new DestTracker(hash, threshold));
            }
        }
        if (skipped > 0)
            _log.warn("Access list entries beyond the limit were ignored -> Raise the limit to use them" +
                      "\n* File: " + file + " * Read: " + into.size() + " entries * Ignored: " + skipped + " entries");
    }

    /**
     * Add the destinations of the last successful read to the working set.
     *
     * @param map the working set the filter is rebuilt from
     */
    private void publishSnapshot(Map<Hash, DestTracker> map) {
        synchronized (lastLoaded) {
            publish(lastLoaded, map);
        }
    }

    /**
     * Add the given destinations to the filter's working set, leaving any
     * destination another element of the definition already claimed.
     *
     * @param from the destinations to add
     * @param into the working set built up by the caller
     */
    private static void publish(Map<Hash, DestTracker> from, Map<Hash, DestTracker> into) {
        for (Map.Entry<Hash, DestTracker> entry : from.entrySet()) {
            if (!into.containsKey(entry.getKey())) {
                into.put(entry.getKey(), entry.getValue());
            }
        }
    }

}
