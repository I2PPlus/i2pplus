/* Storage - Class used to store and retrieve pieces.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import gnu.getopt.Getopt;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteCache;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import net.i2p.util.SystemVersion;

/**
 * Storage manages torrent data files on disk for I2PSnark.
 *
 * <p>This class handles all aspects of storing and retrieving torrent pieces:
 * <ul>
 *   <li>Creating and managing files/directories for torrent data</li>
 *   <li>Reading and writing piece data to disk</li>
 *   <li>Tracking downloaded pieces via BitField</li>
 *   <li>Validating downloaded pieces against piece hashes</li>
 *   <li>File pre-allocation for sparse file support</li>
 *   <li>Priority-based file selection for partial downloads</li>
 *   <li>File checking and rechecking for data integrity</li>
 * </ul>
 *
 * <p>Storage implements Closeable and should be properly closed after use.
 * The storage must be checked via {@link #check()} before reading or writing pieces.
 *
 * <p>Thread safety: This class uses synchronized methods and concurrent collections
 * for thread-safe access to file handles and metadata.
 *
 * @see MetaInfo
 * @see BitField
 * @see StorageListener
 */
public class Storage implements Closeable {
    private final MetaInfo metainfo;
    private final List<TorrentFile> _torrentFiles;
    private final File _base;
    private final StorageListener listener;
    private final I2PSnarkUtil _util;
    private static final Log _log = new Log(Storage.class);

    private /* FIXME final FIXME */ BitField bitfield; // BitField to represent the pieces
    private int needed; // Number of pieces needed
    private boolean _probablyComplete; // use this to decide whether to open files RO

    private final int piece_size;
    private final int pieces;
    private final long total_length;
    private final boolean _preserveFileNames;
    private boolean changed;
    private volatile boolean _isChecking;
    private boolean _inOrder;
    private final AtomicInteger _allocateCount = new AtomicInteger();
    private final AtomicInteger _checkProgress = new AtomicInteger();
    private final AtomicLong _activity = new AtomicLong();
    private List<String> _filesExcluded = new ArrayList<>();
    /** Files or folders that exist but could not be read (e.g. permissions). */
    private List<String> _unreadableFiles = new ArrayList<>();

    /**
     * Directory where incomplete files are written while downloading, when the
     * {@code i2psnark.tempDir} property is configured. Files are moved to the
     * data directory only when complete. Null when the feature is disabled.
     */
    private final File _stagingBase;

    /**
     * Files currently being copied from the staging directory to the data
     * directory, to prevent concurrent duplicate copies (putPiece may be
     * called concurrently).
     */
    private final Set<TorrentFile> _copying = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** BEP 47 directory holding synthetic padding files; skipped when walking a source dir. */
    static final String PAD_DIR = ".pad";

    /**
     * Maximum permitted piece size.
     * Torrents with pieces larger than this will be rejected.
     */
    public static final int MAX_PIECE_SIZE = 64 * 1024 * 1024;

    /** The maximum number of pieces allowed in a single torrent. */
    public static final int MAX_PIECES = 64 * 1024;

    /** Maximum total torrent size (MAX_PIECE_SIZE * MAX_PIECES). */
    public static final long MAX_TOTAL_SIZE = MAX_PIECE_SIZE * (long) MAX_PIECES;

    /** Priority value indicating a file should be skipped during download. */
    public static final int PRIORITY_SKIP = -9;

    /** Default priority value for files. */
    public static final int PRIORITY_NORMAL = 0;

    private static final Map<String, String> _filterNameCache =
            new ConcurrentHashMap<>();

    private static final boolean _isWindows = SystemVersion.isWindows();
    private static final boolean _isARM = SystemVersion.isARM();

    private static final int BUFSIZE = PeerState.PARTSIZE;
    private static final ByteCache _cache = ByteCache.getInstance(16, BUFSIZE);

    /** The default piece size for new torrents. */
    private static final int DEFAULT_PIECE_SIZE = 256 * 1024;

    /** Number of attempts to copy a completed file out of the staging directory. */
    private static final int MAX_COPY_RETRIES = 5;

    /** Delay in milliseconds between copy attempts. */
    private static final long COPY_RETRY_DELAY = 30 * 1000;

    /**
     * Creates a new storage based on the supplied MetaInfo.
     *
     * <p>Does not check storage. Caller MUST call check(), which will try to create and/or check
     * all needed files in the MetaInfo.
     *
     * @param baseFile the torrent data file or dir
     * @param preserveFileNames if true, do not remap names to a 'safe' charset
     */
    public Storage(
            I2PSnarkUtil util,
            File baseFile,
            MetaInfo metainfo,
            StorageListener listener,
            boolean preserveFileNames) {
        _util = util;
        _base = baseFile;
        this.metainfo = metainfo;
        this.listener = listener;
        needed = metainfo.getPieces();
        bitfield = new BitField(needed);
        piece_size = metainfo.getPieceLength(0);
        pieces = needed;
        total_length = metainfo.getTotalLength();
        List<List<String>> files = metainfo.getFiles();
        int sz = files != null ? files.size() : 1;
        _torrentFiles = new ArrayList<>(sz);
        _preserveFileNames = preserveFileNames;
        _stagingBase = getStagingBase(_util.getTempDirProp(), getBaseName(), metainfo);
    }

    /**
     * Creates a storage from the existing file or directory. Creates an in-memory metainfo but does
     * not save it to a file, caller must do that.
     *
     * <p>Creates the metainfo, this may take a LONG time. BLOCKING.
     *
     * @param announce may be null
     * @param listener may be null
     * @param created_by may be null
     * @throws IOException when creating and/or checking files fails.
     */
    public Storage(
            I2PSnarkUtil util,
            File baseFile,
            String announce,
            List<List<String>> announce_list,
            String created_by,
            boolean privateTorrent,
            StorageListener listener,
            List<TorrentCreateFilter> filters)
            throws IOException {
        this(
                util,
                baseFile,
                announce,
                announce_list,
                created_by,
                privateTorrent,
                null,
                null,
                listener,
                filters);
    }

    /**
     * Creates a storage from the existing file or directory. Creates an in-memory metainfo but does
     * not save it to a file, caller must do that.
     *
     * <p>Creates the metainfo, this may take a LONG time. BLOCKING.
     *
     * @param announce may be null
     * @param listener may be null
     * @param created_by may be null
     * @param url_list may be null
     * @param comment may be null
     * @throws IOException when creating and/or checking files fails.
     * @since 0.9.48
     */
    public Storage(
            I2PSnarkUtil util,
            File baseFile,
            String announce,
            List<List<String>> announce_list,
            String created_by,
            boolean privateTorrent,
            List<String> url_list,
            String comment,
            StorageListener listener,
            List<TorrentCreateFilter> filters)
            throws IOException {
        _util = util;
        _base = baseFile;
        this.listener = listener;
        _preserveFileNames = true;
        // New torrents are created directly in the data directory, no staging.
        _stagingBase = null;
        // Create names, rafs and lengths arrays.
        _torrentFiles = getFiles(baseFile, filters);

        long total = 0;
        ArrayList<Long> lengthsList = new ArrayList<>(_torrentFiles.size());
        for (TorrentFile tf : _torrentFiles) {
            long length = tf.length;
            total += length;
            lengthsList.add(Long.valueOf(length));
        }

        if (total <= 0) {
            throw new IOException(noDataMessage(baseFile, _unreadableFiles));
        }
        if (total > MAX_TOTAL_SIZE) {
            throw new IOException(
                    "Torrent too big ("
                            + total
                            + " bytes), maximum permitted is "
                            + MAX_TOTAL_SIZE);
        }

        int pc_size;
        if (total <= 5 * 1024 * 1024) {
            pc_size = DEFAULT_PIECE_SIZE / 4;
        } else if (total <= 10 * 1024 * 1024) {
            pc_size = DEFAULT_PIECE_SIZE / 2;
        } else {
            pc_size = DEFAULT_PIECE_SIZE;
        }
        int pcs = (int) ((total - 1) / pc_size) + 1;
        while (pcs > (MAX_PIECES / 3) && pc_size < MAX_PIECE_SIZE) {
            pc_size *= 2;
            pcs = (int) ((total - 1) / pc_size) + 1;
        }

        // BEP 47 padding: insert zero-fill files so each file except the last ends on a piece
        // boundary. Multi-file torrents only; single-file torrents have no file list to pad.
        // The piece growth loop above ran on the real total, so pc_size is final; padding adds
        // at most one piece per file, which the 3x headroom of that guard absorbs.
        List<String> attributes = null;
        if (_util.getShouldPadFiles() && _torrentFiles.size() > 1) {
            attributes = new ArrayList<>(_torrentFiles.size());
            List<TorrentFile> realFiles = new ArrayList<>(_torrentFiles);
            Map<Long, Integer> padNames = new HashMap<>(4);
            int idx = 0;
            for (int i = 0; i < realFiles.size(); i++) {
                TorrentFile tf = realFiles.get(i);
                attributes.add("");
                idx++;
                long rem = tf.length % pc_size;
                if (rem != 0 && i < realFiles.size() - 1) {
                    long padLen = pc_size - rem;
                    // Recommended name is the length in base 10; suffix duplicates
                    Integer n = padNames.get(Long.valueOf(padLen));
                    String padName;
                    if (n == null) {
                        padNames.put(Long.valueOf(padLen), Integer.valueOf(2));
                        padName = Long.toString(padLen);
                    } else {
                        padNames.put(Long.valueOf(padLen), Integer.valueOf(n.intValue() + 1));
                        padName = Long.toString(padLen) + '-' + n;
                    }
                    _torrentFiles.add(
                            idx,
                            new TorrentFile(
                                    baseFile,
                                    new File(baseFile, PAD_DIR + File.separator + padName),
                                    padLen,
                                    true));
                    lengthsList.add(idx, Long.valueOf(padLen));
                    attributes.add(idx, "p");
                    total += padLen;
                    idx++;
                }
            }
            if (total > MAX_TOTAL_SIZE) {
                throw new IOException(
                        "Torrent too big ("
                                + total
                                + " bytes), maximum permitted is "
                                + MAX_TOTAL_SIZE);
            }
        }
        pcs = (int) ((total - 1) / pc_size) + 1;
        piece_size = pc_size;
        pieces = pcs;
        total_length = total;

        bitfield = new BitField(pieces);
        needed = 0;

        List<List<String>> files = new ArrayList<>(_torrentFiles.size());
        for (TorrentFile tf : _torrentFiles) {
            List<String> file = new ArrayList<>();
            StringTokenizer st = new StringTokenizer(tf.name, File.separator);
            while (st.hasMoreTokens()) {
                String part = st.nextToken();
                file.add(part);
            }
            files.add(file);
        }

        if (files.size() == 1 && !baseFile.isDirectory()) {
            files = null;
            lengthsList = null;
        }

        // TODO thread this so we can return and show something on the UI
        byte[] piece_hashes = fast_digestCreate();
        metainfo =
                new MetaInfo(
                        announce,
                        baseFile.getName(),
                        null,
                        files,
                        lengthsList,
                        attributes,
                        piece_size,
                        piece_hashes,
                        total,
                        privateTorrent,
                        announce_list,
                        created_by,
                        url_list,
                        comment);
    }

    /**
     * The staging directory for a torrent: the configured temp dir plus a
     * subdirectory unique to the torrent. Null when the feature is disabled
     * or the infohash is unavailable.
     *
     * @param tempDir the configured staging directory, or null
     * @param baseName the torrent's base name
     * @param metainfo the torrent
     * @return the per-torrent staging dir, or null
     * @since 0.9.71+
     */
    static File getStagingBase(String tempDir, String baseName, MetaInfo metainfo) {
        if (tempDir == null) {
            return null;
        }
        byte[] hash = metainfo.getInfoHash();
        if (hash == null) {
            return null;
        }
        String hash8 = DataHelper.toString(hash);
        if (hash8.length() > 8) {
            hash8 = hash8.substring(0, 8);
        }
        return new File(tempDir, baseName + '-' + hash8);
    }

    /**
     * Creates piece hashes for a new storage. This does NOT create the files, just the hashes. Also
     * sets all the bitfield bits.
     *
     * <p>FIXME we can run out of fd's doing this, maybe some sort of global close-RAF-right-away
     * flag would do the trick
     */
    private byte[] fast_digestCreate() throws IOException {
        // Calculate piece_hashes
        MessageDigest digest = SHA1.getInstance();

        byte[] piece_hashes = new byte[20 * pieces];

        byte[] piece = new byte[piece_size];
        for (int i = 0; i < pieces; i++) {
            int length = getUncheckedPiece(i, piece);
            digest.update(piece, 0, length);
            byte[] hash = digest.digest();
            System.arraycopy(hash, 0, piece_hashes, 20 * i, 20);
            bitfield.set(i);
        }
        return piece_hashes;
    }

    private List<TorrentFile> getFiles(File base, List<TorrentCreateFilter> filters)
            throws IOException {
        if (base.getAbsolutePath().equals("/")) {
            throw new IOException("Don't seed root");
        }
        List<File> files = new ArrayList<>();
        addFiles(files, base, filters);

        int size = files.size();
        List<TorrentFile> rv = new ArrayList<>(size);

        for (File f : files) {
            rv.add(new TorrentFile(base, f));
        }
        // Sort to prevent exposing OS type, and to make it more likely
        // the same torrent created twice will have the same infohash.
        Collections.sort(rv);
        return rv;
    }

    /**
     * Record a file or folder that exists but could not be read, and warn.
     * The creation proceeds with the readable data; if there is none, the
     * caller fails with noDataMessage() naming the unreadable paths.
     *
     * @param f the unreadable file or folder
     * @param reason a short human-readable reason
     * @since 0.9.71+
     */
    private void addUnreadable(File f, String reason) {
        _unreadableFiles.add(f.getPath());
        if (_log.shouldWarn()) {
            _log.warn("[I2PSnark] Skipping '" + f + "' -> " + reason);
        }
    }

    /**
     * The message for a failed torrent creation with no readable data, so a
     * permission problem is reported as such rather than as an empty folder.
     *
     * @param base the data folder or file
     * @param unreadable paths that exist but could not be read, may be empty
     * @return the message
     * @since 0.9.71+
     */
    static String noDataMessage(File base, List<String> unreadable) {
        if (!unreadable.isEmpty()) {
            return "Cannot read "
                    + unreadable.size()
                    + " file(s) or folder(s) under \""
                    + base
                    + "\", first: \""
                    + unreadable.get(0)
                    + "\"";
        }
        return "Torrent contains no data";
    }

    /**
     * The excluded files.
     *
     * @return the excluded files
     * @since 0.9.62+
     */
    public List<String> getExcludedFiles(File base) {
        List<String> excludedNames = new ArrayList<>();
        for (String filePath : _filesExcluded) {
            Path path = Paths.get(filePath);
            String folderHierarchy = getFolderHierarchy(path, base.toPath());
            excludedNames.add(folderHierarchy + "/" + path.getFileName().toString());
        }
        return excludedNames;
    }

    private String getFolderHierarchy(Path path, Path base) {
        Path relativePath = base.relativize(path);
        Path parent = relativePath.getParent();

        if (parent == null) {
            return "";
        } // Excluded file is directly under the base folder

        return parent.toString();
    }

    /**
     * Is this a padding directory (BEP 47 ".pad" or a parse-renamed "_pad")?
     * Padding entries hold only synthetic zero files: they must not count
     * toward the max files per torrent limit, nor be included as data when
     * creating a torrent.
     *
     * @param name directory name
     * @return true if it is a padding directory
     */
    static boolean isPadDir(String name) {
        return PAD_DIR.equals(name) || "_pad".equals(name);
    }

    /**
     * Count the entries in a directory listing for the max files per torrent
     * check, excluding padding directories.
     *
     * @param files directory listing, may be null
     * @return the number of non-padding entries
     */
    static int countNonPad(File[] files) {
        if (files == null) {
            return 0;
        }
        int count = files.length;
        for (File f : files) {
            if (f.isDirectory() && isPadDir(f.getName())) {
                count--;
            }
        }
        return count;
    }

    /**
     * Count the files in the torrent.
     *
     * @throws IOException if too many total files
     */
    private void addFiles(List<File> l, File f, List<TorrentCreateFilter> filters)
            throws IOException {
        int max = _util.getMaxFilesPerTorrent();

        for (int i = 0; i < filters.size(); i++) {
            TorrentCreateFilter filter = filters.get(i);

            switch (filter.filterType) {
                case "starts_with":
                    if (f.getName().startsWith(filter.filterPattern)) {
                        _filesExcluded.add(f.getPath());
                        return;
                    }
                    break;

                case "ends_with":
                    if (f.getName().endsWith(filter.filterPattern)) {
                        _filesExcluded.add(f.getPath());
                        return;
                    }
                    break;

                default:
                    if (f.getName().contains(filter.filterPattern)) {
                        _filesExcluded.add(f.getPath());
                        return;
                    }
            }
        }

        if (!f.isDirectory()) {
            if (!f.exists()) {
                addUnreadable(f, "does not exist");
                return;
            }
            if (!f.canRead()) {
                addUnreadable(f, "cannot be read, check permissions");
                return;
            }
            int sz = l.size() + 1;
            if (sz > max) {
                throw new IOException(
                        _util.getString(
                                        "Too many files in \"{0}\" ({1})!",
                                        (metainfo != null ? metainfo.getName() : _base.toString()),
                                        sz)
                                + " - limit is "
                                + max
                                + ", zip them or set "
                                + SnarkManager.PROP_MAX_FILES_PER_TORRENT
                                + '='
                                + sz
                                + " in "
                                + SnarkManager.CONFIG_FILE
                                + " and restart");
            }
            l.add(f);
        } else {
            if (!f.exists()) {
                addUnreadable(f, "does not exist");
                return;
            }
            if (!f.canRead()) {
                addUnreadable(f, "cannot be read, check permissions");
                return;
            }
            File[] files = f.listFiles();
            if (files == null) {
                if (_log.shouldWarn()) {
                    _log.warn("[I2PSnark] WARNING: Skipping '" + f + "' -> Not a normal file!");
                }
                return;
            }
            int sz = l.size() + countNonPad(files);
            if (sz > max) {
                throw new IOException(
                        _util.getString(
                                        "Too many files in \"{0}\" ({1})!",
                                        (metainfo != null ? metainfo.getName() : _base.toString()),
                                        sz)
                                + " - limit is "
                                + max
                                + ", zip them or set "
                                + SnarkManager.PROP_MAX_FILES_PER_TORRENT
                                + '='
                                + sz
                                + " in "
                                + SnarkManager.CONFIG_FILE
                                + " and restart");
            }
            for (int i = 0; i < files.length; i++) {
                // Skip BEP 47 padding directories so re-creating a padded torrent does not
                // include the synthetic zero files as data; parse renames dotfiles to _pad
                if (files[i].isDirectory() && isPadDir(files[i].getName())) {
                    continue;
                }
                addFiles(l, files[i], filters);
            }
        }
    }

    /** Returns the MetaInfo associated with this Storage. */
    public MetaInfo getMetaInfo() {
        return metainfo;
    }

    /** How many pieces are still missing from this storage. */
    public int needed() {
        return needed;
    }

    /** Whether or not this storage contains all pieces if the MetaInfo. */
    public boolean complete() {
        return needed == 0;
    }

    /**
     * Has the storage changed since instantiation?
     *
     * @return whether changed
     * @since 0.8.5
     */
    public boolean isChanged() {
        return changed;
    }

    /**
     * Clear the storage changed variable
     *
     * @since 0.9.30
     */
    void clearChanged() {
        changed = false;
    }

    /**
     * The last activity timestamp.
     *
     * @return timestamp in milliseconds since epoch, or 0 if never set
     * @since 0.9.42
     */
    public long getActivity() {
        return _activity.get();
    }

    /**
     * Mark the activity timestamp as now.
     *
     * @since 0.9.42
     */
    private void setActivity() {
        setActivity(I2PAppContext.getGlobalContext().clock().now());
    }

    /**
     * Store the activity timestamp.
     *
     * @param time timestamp in milliseconds since epoch
     * @since 0.9.42
     */
    public void setActivity(long time) {
        _activity.set(time);
        changed = true;
    }

    /**
     * File checking in progress.
     *
     * @return whether checking
     * @since 0.9.3
     */
    public boolean isChecking() {
        return _isChecking;
    }

    /**
     * If checking is in progress, return completion 0.0 ... 1.0, else return 1.0.
     *
     * @return the checking progress
     * @since 0.9.23
     */
    public double getCheckingProgress() {
        if (_isChecking) {
            return _checkProgress.get() / (double) pieces;
        } else {
            return 1.0d;
        }
    }

    /**
     * Disk allocation (ballooning) in progress. Always false on Windows.
     *
     * @return whether allocating
     * @since 0.9.3
     */
    public boolean isAllocating() {
        return _allocateCount.get() > 0;
    }

    /**
     * Index to pass to remaining(), getPriority(), setPriority()
     *
     * @param file non-canonical path (non-directory)
     * @return internal index of file; -1 if unknown file
     * @since 0.9.15
     */
    public int indexOf(File file) {
        for (int i = 0; i < _torrentFiles.size(); i++) {
            File f = _torrentFiles.get(i).finalFile;
            if (f.equals(file)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * For efficiency, calculate remaining bytes for all files at once
     *
     * @return number of bytes remaining for each file, use indexOf() to get index for a file
     * @since 0.9.23
     */
    public long[] remaining() {
        return remaining2()[0];
    }

    /**
     * For efficiency, calculate remaining bytes for all files at once. Remaining bytes is rv[0].
     * Preview bytes is rv[1].
     *
     * @return number of bytes remaining and number of bytes available for a preview for each file,
     *     use indexOf() to get index for a file
     * @since 0.9.45
     */
    public long[][] remaining2() {
        long[] rv = new long[_torrentFiles.size()];
        long[] pv = new long[_torrentFiles.size()];
        long[][] rva = new long[][] {rv, pv};
        if (complete()) {
            return rva;
        }
        long bytes = 0;
        for (int i = 0; i < _torrentFiles.size(); i++) {
            TorrentFile tf = _torrentFiles.get(i);
            long start = bytes;
            long end = start + tf.length;
            int pc = (int) (bytes / piece_size);
            long rvi = 0;
            long pvi = 0;
            long first = Math.min(piece_size - (start % piece_size), tf.length);
            if (bitfield.get(pc)) pvi = first;
            else rvi = first;
            boolean preview = true;
            for (int j = pc + 1; (((long) j) * piece_size) < end && j < pieces; j++) {
                if (bitfield.get(j)) {
                    if (preview) {
                        if (((long) (j + 1)) * piece_size < end) pvi += piece_size;
                        else pvi += end - (((long) j) * piece_size);
                    }
                } else {
                    preview = false;
                    if (((long) (j + 1)) * piece_size < end) rvi += piece_size;
                    else rvi += end - (((long) j) * piece_size);
                }
            }
            rv[i] = rvi;
            pv[i] = pvi;
            bytes += tf.length;
        }
        return rva;
    }

    /**
     * The priority of the file at the index.
     *
     * @param fileIndex as obtained from indexOf
     * @return the priority
     * @since 0.8.1
     */
    public int getPriority(int fileIndex) {
        if (complete() || metainfo.getFiles() == null) {
            return PRIORITY_NORMAL;
        }
        if (fileIndex < 0 || fileIndex >= _torrentFiles.size()) {
            return PRIORITY_NORMAL;
        }
        return _torrentFiles.get(fileIndex).priority;
    }

    /**
     * Must call Snark.updatePiecePriorities() (which calls getPiecePriorities()) after calling
     * this.
     *
     * @param fileIndex as obtained from indexOf
     * @param pri default 0; &lt;0 to disable
     * @since 0.8.1
     */
    public void setPriority(int fileIndex, int pri) {
        if (complete() || metainfo.getFiles() == null) {
            return;
        }
        if (fileIndex < 0 || fileIndex >= _torrentFiles.size()) {
            return;
        }
        _torrentFiles.get(fileIndex).priority = pri;
    }

    /**
     * The file priorities array.
     *
     * @return null on error, if complete, or if only one file
     * @since 0.8.1
     */
    public int[] getFilePriorities() {
        if (complete()) {
            return null;
        }
        int sz = _torrentFiles.size();
        if (sz <= 1) {
            return null;
        }
        int[] priorities = new int[sz];
        for (int i = 0; i < sz; i++) {
            priorities[i] = _torrentFiles.get(i).priority;
        }
        return priorities;
    }

    /**
     * The file priorities array. Only call this when stopped, but after check()
     *
     * @param p may be null
     * @since 0.8.1
     */
    void setFilePriorities(int[] p) {
        if (p == null) {
            for (TorrentFile tf : _torrentFiles) {
                tf.priority = PRIORITY_NORMAL;
            }
        } else {
            int sz = _torrentFiles.size();
            if (p.length != sz) {
                throw new IllegalArgumentException();
            }
            for (int i = 0; i < sz; i++) {
                _torrentFiles.get(i).priority = p[i];
            }
        }
    }

    /**
     * Whether in-order download mode is enabled.
     *
     * @return whether in-order download mode is enabled
     * @since 0.9.36
     */
    public boolean getInOrder() {
        return _inOrder;
    }

    /**
     * Enable or disable in-order download mode.
     * When enabled, pieces within each file are prioritized sequentially.
     * Must call setFilePriorities() BEFORE this method.
     *
     * @param yes true to enable in-order mode, false to disable
     * @since 0.9.36
     */
    public void setInOrder(boolean yes) {
        if (yes == _inOrder) {
            return;
        }
        _inOrder = yes;
        if (complete()) {
            return;
        }
        if (yes) {
            List<TorrentFile> sorted = _torrentFiles;
            int sz = sorted.size();
            if (sz > 1) {
                sorted = new ArrayList<>(sorted);
                Collections.sort(sorted, new FileNameComparator());
            }
            for (int i = 0; i < sz; i++) {
                TorrentFile tf = sorted.get(i);
                // higher number is higher priority
                if (tf.priority >= PRIORITY_NORMAL) {
                    tf.priority = sz - i;
                }
            }
        } else {
            for (TorrentFile tf : _torrentFiles) {
                if (tf.priority > PRIORITY_NORMAL) {
                    tf.priority = PRIORITY_NORMAL;
                }
            }
        }
    }

    /**
     * Sort with locale comparator. (not using TorrentFile.compareTo())
     *
     * @since 0.9.36
     */
    private static class FileNameComparator implements Comparator<TorrentFile>, Serializable {

        private final Collator c = Collator.getInstance();

        public int compare(TorrentFile l, TorrentFile r) {
            return c.compare(l.toString(), r.toString());
        }
    }

    /**
     * Call setPriority() for all changed files first, then call this. Set the piece priority to the
     * highest priority of all files spanning the piece. Caller must pass array to the
     * PeerCoordinator.
     *
     * @return null on error, if complete, or if only one file and inOrder not set.
     * @since 0.8.1
     */
    public int[] getPiecePriorities() {
        if (complete() || (metainfo.getFiles() == null && !_inOrder)) {
            return null;
        }
        int[] rv = new int[metainfo.getPieces()];
        int file = 0;
        long pcEnd = -1;
        long fileEnd = _torrentFiles.get(0).length - 1;
        for (int i = 0; i < rv.length; i++) {
            pcEnd += piece_size;
            int pri = _torrentFiles.get(file).priority;
            while (fileEnd <= pcEnd && file < _torrentFiles.size() - 1) {
                file++;
                TorrentFile tf = _torrentFiles.get(file);
                long oldFileEnd = fileEnd;
                fileEnd += tf.length;
                if (tf.priority > pri && oldFileEnd < pcEnd) {
                    pri = tf.priority;
                }
            }
            rv[i] = pri;
        }
        if (_inOrder) {
            // Do a second pass to set the priority of the pieces within each file
            // this only works because MAX_PIECES * MAX_FILES_PER_TORRENT < Integer.MAX_VALUE
            // the base file priority
            int pri = PRIORITY_SKIP;
            for (int i = 0; i < rv.length; i++) {
                int val = rv[i];
                if (val <= PRIORITY_NORMAL) {
                    continue;
                }
                if (val != pri) {
                    pri = val;
                    // new file
                    rv[i] *= MAX_PIECES;
                } else {
                    rv[i] = rv[i - 1] - 1;
                } // same file, decrement priority from previous piece
            }
        }
        return rv;
    }

    /**
     * Call setPriority() for all changed files first, then call this. The length of all the pieces
     * that are not yet downloaded, and are set to skipped. This is not the same as the total of all
     * skipped files, since pieces may span multiple files.
     *
     * @return 0 on error, if complete, or if only one file
     * @since 0.9.24
     */
    public long getSkippedLength() {
        int[] pri = getPiecePriorities();
        if (pri == null) {
            return 0;
        }
        long rv = 0;
        final int end = pri.length - 1;
        for (int i = 0; i <= end; i++) {
            if (pri[i] <= PRIORITY_SKIP && !bitfield.get(i)) {
                rv += (i != end) ? piece_size : metainfo.getPieceLength(i);
            }
        }
        return rv;
    }

    /**
     * The BitField that tells which pieces this storage contains. Do not change this since this is
     * the current state of the storage.
     * @return the bit field
     */
    public BitField getBitField() {
        return bitfield;
    }

    /**
     * The base file or directory name of the data, as specified in the .torrent file, but filtered
     * to remove illegal characters. This is where the data actually is, relative to the snark base
     * dir.
     *
     * @return the base name
     * @since 0.7.14
     */
    public String getBaseName() {
        return optFilterName(metainfo.getName());
    }

    /**
     * Whether original file names are being preserved, false if filtered.
     *
     * @return true if original file names are being preserved, false if filtered
     * @since 0.9.15
     */
    public boolean getPreserveFileNames() {
        return _preserveFileNames;
    }

    /**
     * Creates (and/or checks) all files from the metainfo file list. Only call this once, and only
     * after the constructor with the metainfo. Use recheck() to check again later.
     *
     * @throws IllegalStateException if called more than once
     */
    public void check() throws IOException {
        check(0, null);
    }

    /**
     * Creates (and/or checks) all files from the metainfo file list. Use a saved bitfield and
     * timestamp from a config file. Only call this once, and only after the constructor with the
     * metainfo. Use recheck() to check again later.
     *
     * @throws IllegalStateException if called more than once
     */
    public void check(long savedTime, BitField savedBitField) throws IOException {
        boolean areFilesPublic = _util.getFilesPublic();
        boolean useSavedBitField = savedTime > 0 && savedBitField != null;

        if (!_torrentFiles.isEmpty()) {
            throw new IllegalStateException();
        }
        List<List<String>> files = metainfo.getFiles();
        List<Boolean> trusted = null;
        if (files == null) {
            // Create base as file.
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] Creating/checking file: " + _base);
            }
            File active = _base;
            File workFile = null;
            if (_stagingBase != null) {
                // Incomplete downloads go to the staging dir; only a file whose
                // full length is already in the data directory stays there.
                workFile = new File(_stagingBase, _base.getName());
                if (_base.exists() && _base.length() == metainfo.getTotalLength()) {
                    if (!workFile.delete()) {
                        _log.warn("[I2PSnark] Unable to delete stale file: " + workFile);
                    }
                    // already in the data dir; the movedToDataDir flag must reflect that
                    workFile = null;
                } else {
                    if (_base.exists() && !_base.delete()) {
                        throw new IOException("Could not delete file " + _base);
                    }
                    if (!_stagingBase.mkdir() && !_stagingBase.isDirectory()) {
                        throw new IOException("Could not create directory " + _stagingBase);
                    }
                    if (!workFile.exists() && !workFile.createNewFile()) {
                        throw new IOException("Could not create file " + workFile);
                    }
                    active = workFile;
                }
            } else if (!_base.exists() && !_base.createNewFile()) {
                // createNewFile() can throw a "Permission denied" IOE even if the file exists???
                // so do it second
                throw new IOException("Could not create file " + _base);
            }
            _torrentFiles.add(
                    new TorrentFile(
                            _base, active, _base, workFile, metainfo.getTotalLength(), false));
            if (useSavedBitField) {
                long lm = active.lastModified();
                if (lm <= 0 || lm > savedTime) {
                    useSavedBitField = false;
                } else if (active.length() != metainfo.getTotalLength()) {
                    useSavedBitField = false;
                }
            }
        } else {
            // Create base as dir.
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] Creating/checking directory: " + _base);
            }
            if (!_base.mkdir() && !_base.isDirectory()) {
                throw new IOException("Could not create directory " + _base);
            }
            List<Long> ls = metainfo.getLengths();
            int size = files.size();
            long total = 0;
            trusted = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                List<String> path = files.get(i);
                boolean isPad = metainfo.isPaddingFile(i);
                File finalFile = buildFilePath(_base, path);
                // dup file name check after filtering
                for (int j = 0; j < i; j++) {
                    if (finalFile.equals(_torrentFiles.get(j).finalFile)) {
                        // Rename and start the check over again
                        // Copy path since metainfo list is unmodifiable
                        path = new ArrayList<>(path);
                        int last = path.size() - 1;
                        String lastPath = path.get(last);
                        int dot = lastPath.lastIndexOf('.');
                        // foo.mp3 -> foo_.mp3; foo -> _foo
                        if (dot >= 0) {
                            lastPath = lastPath.substring(0, dot) + '_' + lastPath.substring(dot);
                        } else {
                            lastPath = '_' + lastPath;
                        }
                        path.set(last, lastPath);
                        finalFile = buildFilePath(_base, path);
                        j = 0;
                    }
                }
                long len = ls.get(i).longValue();
                File active = finalFile;
                File workFile = null;
                if (!isPad && _stagingBase != null) {
                    // Incomplete downloads go to the staging dir. A file whose
                    // full length is already in the data directory stays there;
                    // otherwise download into staging, salvaging any partial
                    // data found in the data directory.
                    File stagingFile = buildFilePath(_stagingBase, path);
                    if (finalFile.exists() && finalFile.length() == len) {
                        if (!stagingFile.delete()) {
                            _log.warn("[I2PSnark] Unable to delete stale file: " + stagingFile);
                        }
                    } else {
                        if (!_stagingBase.mkdir() && !_stagingBase.isDirectory()) {
                            throw new IOException("Could not create directory " + _stagingBase);
                        }
                        workFile = createFileFromNames(_stagingBase, path, areFilesPublic);
                        active = workFile;
                        if (finalFile.exists()) {
                            // Salvage the partial data (rename may fail across filesystems)
                            if (!finalFile.renameTo(workFile)) {
                                if (!workFile.delete() && workFile.exists()) {
                                    throw new IOException("Could not delete file " + workFile);
                                }
                                if (!workFile.createNewFile()) {
                                    throw new IOException("Could not create file " + workFile);
                                }
                            }
                        }
                    }
                } else if (!isPad) {
                    active = createFileFromNames(_base, path, areFilesPublic);
                }
                _torrentFiles.add(new TorrentFile(_base, active, finalFile, workFile, len, isPad));
                total += len;
                if (useSavedBitField && !isPad) {
                    long lm = active.lastModified();
                    trusted.add(Boolean.valueOf(lm > 0 && lm <= savedTime && active.length() == len));
                } else {
                    // Padding never touches disk, so its hashes can never go stale
                    trusted.add(isPad ? Boolean.TRUE : Boolean.FALSE);
                }
            }

            // Sanity check for metainfo file.
            long metalength = metainfo.getTotalLength();
            if (total != metalength) {
                throw new IOException("File lengths do not add up " + total + " != " + metalength);
            }
        }
        if (useSavedBitField) {
            if (files == null || !trusted.contains(Boolean.FALSE)) {
                bitfield = savedBitField;
                needed = metainfo.getPieces() - bitfield.count();
                _probablyComplete = complete();
                if (_log.shouldInfo())
                    _log.info(
                            "[I2PSnark] Found saved state and files unchanged, skipping integrity"
                                + " check");
            } else {
                // Some files changed since the save: re-verify only the pieces overlapping those
                // files, keeping the saved bits for pieces that lie entirely within unchanged files
                changed = true;
                if (_log.shouldInfo()) {
                    _log.info(
                            "[I2PSnark] Found saved state, rechecking "
                                + (trusted.size() - Collections.frequency(trusted, Boolean.TRUE))
                                + " of "
                                + trusted.size()
                                + " files");
                }
                boolean[] pieceTrusted = computeTrustedPieces(trusted);
                bitfield = new BitField(savedBitField.getFieldBytes(), metainfo.getPieces());
                for (int i = 0; i < pieces; i++) {
                    if (!pieceTrusted[i]) {
                        bitfield.clear(i);
                    }
                }
                checkCreateFiles(false, pieceTrusted);
            }
        } else {
            // the following sets the needed variable
            changed = true;
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] Forcing integrity check...");
            }
            checkCreateFiles(false);
        }
        // Move any file whose pieces are all downloaded out of the staging dir;
        // needed e.g. after a restart where the torrent was already complete
        checkFileCompletions();
        if (complete()) {
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] Torrent is complete");
            }
        } else {
            // fixme saved priorities
            if (_log.shouldInfo()) {
                _log.info(
                        "[I2PSnark] Still need "
                                + needed
                                + " out of "
                                + metainfo.getPieces()
                                + " pieces");
            }
        }
    }

    /**
     * For a multi-file torrent with some files trusted from a saved state, marks the pieces that
     * lie entirely within a trusted file. A piece overlapping any changed file must be re-verified.
     *
     * @param fileTrusted one entry per file, in metainfo order
     * @return array indexed by piece, true if the piece may be trusted without re-hashing
     */
    private boolean[] computeTrustedPieces(List<Boolean> fileTrusted) {
        boolean[] rv = new boolean[pieces];
        int file = 0;
        long fileEnd = _torrentFiles.get(0).length;
        long pieceEnd = 0;
        for (int i = 0; i < pieces; i++) {
            long pieceStart = pieceEnd;
            pieceEnd = Math.min(pieceStart + piece_size, total_length);
            // Stop while file cursor covers the piece start; file may be to the
            // right of the piece start if the piece begins inside a file
            while (fileEnd <= pieceStart && file + 1 < _torrentFiles.size()) {
                file++;
                fileEnd += _torrentFiles.get(file).length;
            }
            // A piece is trusted only if every file it overlaps is trusted;
            // padding files are always trusted (they hash as zeros and never
            // touch disk, so their bytes cannot go stale)
            boolean ok = true;
            int f = file;
            long fEnd = fileEnd;
            while (true) {
                if (!fileTrusted.get(f).booleanValue()) {
                    ok = false;
                    break;
                }
                if (fEnd >= pieceEnd || f + 1 >= _torrentFiles.size()) {
                    break;
                }
                f++;
                fEnd += _torrentFiles.get(f).length;
            }
            rv[i] = ok && fEnd >= pieceEnd;
        }
        return rv;
    }

    /**
     * Doesn't really reopen the file descriptors for a restart. Just does an existence check but no
     * length check or data reverification
     *
     * @throws IOException on fail
     */
    public void reopen() throws IOException {
        if (_torrentFiles.isEmpty()) {
            throw new IOException("Storage not checked yet");
        }
        for (int i = 0; i < _torrentFiles.size(); i++) {
            TorrentFile tf = _torrentFiles.get(i);
            if (tf.isPadding) {
                continue;
            }
            if (!tf.RAFfile.exists()) {
                // File should exist when we get here, but could have vanished.
                // Recreate at the active location (staging dir while incomplete,
                // data dir otherwise).
                recreateFile(tf);
                synchronized (tf) {
                    tf.allocateFile();
                    // close as we go so we don't run out of file descriptors
                    try {
                        tf.closeRAF();
                    } catch (IOException ioe) { /* ignored */ }
                }
                String msg = "File '" + tf.name + "' was deleted, must be downloaded again";
                if (listener != null) {
                    listener.addMessage(msg);
                }
                _log.error(msg);
            }
        }
    }

    private static final char[] ILLEGAL =
            new char[] {
                '<',
                '>',
                ':',
                '"',
                '/',
                '\\',
                '|',
                '?',
                '*',
                0,
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8,
                9,
                10,
                11,
                12,
                13,
                14,
                15,
                16,
                17,
                18,
                19,
                20,
                21,
                22,
                23,
                24,
                25,
                26,
                27,
                28,
                29,
                30,
                31,
                0x7f,
                0x80,
                0x81,
                0x82,
                0x83,
                0x84,
                0x85,
                0x86,
                0x87,
                0x88,
                0x89,
                0x8a,
                0x8b,
                0x8c,
                0x8d,
                0x8e,
                0x8f,
                0x90,
                0x91,
                0x92,
                0x93,
                0x94,
                0x95,
                0x96,
                0x97,
                0x98,
                0x99,
                0x9a,
                0x9b,
                0x9c,
                0x9d,
                0x9e,
                0x9f,
                // unicode newlines
                0x2028,
                0x2029,
                // LTR/RTL
                // https://security.stackexchange.com/questions/158802/how-can-this-executable-have-an-avi-extension
                0x202a,
                0x202b,
                0x202c,
                0x202d,
                0x202e,
                0x200e,
                0x200f
            };

    // https://docs.microsoft.com/en-us/windows/desktop/FileIO/naming-a-file
    private static final String[] WIN_ILLEGAL =
            new String[] {
                "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
                "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8",
                "lpt9"
            };

    /**
     * Filter the name, but only if configured to do so. We will do so on torrents received from
     * others, but not on those we created ourselves, so we do not lose track of files.
     *
     * @since 0.9.15
     */
    private String optFilterName(String name) {
        if (_preserveFileNames) {
            return name;
        }
        return filterName(name);
    }

    /**
     * Removes 'suspicious' characters from the given file name.
     * http://msdn.microsoft.com/en-us/library/aa365247%28VS.85%29.aspx Then replace chars not
     * supported in the charset.
     *
     * <p>This is called frequently and it can be pretty slow so cache the result.
     *
     * <p>TODO: If multiple files in the same torrent map to the same filter name, the whole torrent
     * will blow up. Check at torrent creation?
     */
    public static String filterName(String name) {
        String rv = _filterNameCache.get(name);
        if (rv != null) {
            return rv;
        }
        if (name.equals(".") || name.equals(" ")) {
            rv = "_";
        } else {
            rv = name;
            if (rv.startsWith(".")) {
                rv = '_' + rv.substring(1);
            } else if (SystemVersion.isWindows()) {
                // https://docs.microsoft.com/en-us/windows/desktop/FileIO/naming-a-file
                String iname = name.toLowerCase(Locale.US);
                StringBuilder rvBuf = new StringBuilder(rv);
                for (int i = 0; i < WIN_ILLEGAL.length; i++) {
                    String w = WIN_ILLEGAL[i];
                    if (iname.equals(w)
                            || (iname.startsWith(w + '.') && w.indexOf('.', w.length() + 1) < 0)) {
                        rvBuf.insert(0, '_');
                    }
                }
                rv = rvBuf.toString();
            }
            if (rv.endsWith(".") || rv.endsWith(" ")) {
                rv = rv.substring(0, rv.length() - 1) + '_';
            }
            for (int i = 0; i < ILLEGAL.length; i++) {
                if (rv.indexOf(ILLEGAL[i]) >= 0) {
                    rv = rv.replace(ILLEGAL[i], '_');
                }
            }

            // Replace characters not supported in the charset
            if (!Charset.defaultCharset().name().equals("UTF-8")) {
                try {
                    CharsetEncoder enc = Charset.defaultCharset().newEncoder();
                    if (!enc.canEncode(rv)) {
                        String repl = rv;
                        for (int i = 0; i < rv.length(); i++) {
                            char c = rv.charAt(i);
                            if (!enc.canEncode(c)) {
                                repl = repl.replace(c, '_');
                            }
                        }
                        rv = repl;
                    }
                } catch (RuntimeException ex) {
                    _log.log(Log.WARN, "Error encoding charset", ex);
                }
            }
        }
        _filterNameCache.put(name, rv);
        return rv;
    }

    /**
     * Note that filtering each path element individually may lead to things going in the wrong
     * place if there are duplicates in intermediate path elements after filtering.
     *
     * @param names path elements
     */
    private File createFileFromNames(File base, List<String> names, boolean areFilesPublic)
            throws IOException {
        File f = null;
        Iterator<String> it = names.iterator();
        while (it.hasNext()) {
            String name = optFilterName(it.next());
            if (it.hasNext()) {
                // Another dir in the hierarchy.
                if (areFilesPublic) {
                    f = new File(base, name);
                } else {
                    f = new SecureFile(base, name);
                }
                if (!f.mkdir() && !f.isDirectory()) {
                    throw new IOException("Could not create directory " + f);
                }
                base = f;
            } else {
                // The final element (file) in the hierarchy.
                if (areFilesPublic) {
                    f = new File(base, name);
                } else {
                    f = new SecureFile(base, name);
                }
                // createNewFile() can throw a "Permission denied" IOE even if the file exists???
                // so do it second
                if (!f.exists() && !f.createNewFile()) {
                    throw new IOException("Could not create file " + f);
                }
            }
        }
        return f;
    }

    /**
     * Builds a file path under the given base from filtered path elements,
     * without creating anything on disk.
     *
     * @param base a directory
     * @param names path elements
     * @return the File
     * @since 0.9.71+
     */
    private File buildFilePath(File base, List<String> names) {
        File f = base;
        for (String name : names) {
            f = new File(f, optFilterName(name));
        }
        return f;
    }

    /**
     * Recreates a missing file at its active location (the staging dir while
     * incomplete, the data dir otherwise).
     *
     * @param tf the file to recreate
     * @throws IOException if the file cannot be created
     * @since 0.9.71+
     */
    private void recreateFile(TorrentFile tf) throws IOException {
        File f = tf.RAFfile;
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create directory " + parent);
        }
        // createNewFile() can throw a "Permission denied" IOE even if the file exists???
        // so do it second
        if (!f.exists() && !f.createNewFile()) {
            throw new IOException("Could not create file " + f);
        }
    }

    /**
     * The base file or directory.
     *
     * @return the File
     * @since 0.9.15
     */
    public File getBase() {
        return _base;
    }

    /**
     * Does not include directories. Unsorted.
     *
     * <p>In staging mode, returns the final data-directory location of each
     * file, even while the data is still being written to the staging dir.
     *
     * @return a new List
     * @since 0.9.15
     */
    public List<File> getFiles() {
        List<File> rv = new ArrayList<>(_torrentFiles.size());
        for (TorrentFile tf : _torrentFiles) {
            rv.add(tf.finalFile);
        }
        return rv;
    }

    /**
     * Does not include directories.
     *
     * @return the file count
     * @since 0.9.23
     */
    public int getFileCount() {
        return _torrentFiles.size();
    }

    /**
     * Includes the base for a multi-file torrent. Sorted bottom-up for easy deletion. Slow. Use for
     * deletion only.
     *
     * @return a new Set or null for a single-file torrent
     * @since 0.9.15
     */
    public SortedSet<File> getDirectories() {
        if (!_base.isDirectory()) return null;
        SortedSet<File> rv = new TreeSet<>(Collections.reverseOrder());
        rv.add(_base);
        for (TorrentFile tf : _torrentFiles) {
            File f = tf.finalFile;
            do {
                f = f.getParentFile();
            } while (f != null && rv.add(f));
        }
        return rv;
    }

    /**
     * Removes all partially downloaded data from the staging directory for
     * this torrent. No-op when the staging feature is disabled.
     *
     * @since 0.9.71+
     */
    public void deleteStagingData() {
        if (_stagingBase == null) {
            return;
        }
        // remove any empty dirs too; only the torrent's own subdir is touched
        if (FileUtil.rmdir(_stagingBase, false)) {
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] Deleted staging data: " + _stagingBase);
            }
        } else if (_log.shouldWarn()) {
            _log.warn("[I2PSnark] Unable to delete staging data: " + _stagingBase);
        }
    }

    /**
     * Blocking. Holds lock. Recommend running only when stopped. Caller should thread. Calls
     * listener.setWantedPieces() on completion if anything changed.
     *
     * @return true if anything changed, false otherwise
     * @since 0.9.23
     */
    public boolean recheck() throws IOException {
        boolean changed = checkCreateFiles(true);
        // files may have been recreated in the staging dir; move any complete ones
        checkFileCompletions();
        if (listener != null && changed) listener.setWantedPieces(this);
        return changed;
    }

    /**
     * This is called at the beginning, and at presumed completion, so we have to be careful about
     * locking.
     *
     * <p>TODO thread the checking so we can return and display something on the UI
     *
     * @param recheck if true, this is a check after we downloaded the last piece, and we don't
     *     modify the global bitfield unless the check fails.
     * @return true if changed (only valid if recheck == true)
     */
    private boolean checkCreateFiles(boolean recheck) throws IOException {
        return checkCreateFiles(recheck, null);
    }

    /**
     * Variant that skips hashing pieces marked trusted, for resuming with a partially trusted
     * saved state.
     *
     * @param pieceTrusted array indexed by piece, or null to hash everything
     */
    private boolean checkCreateFiles(boolean recheck, boolean[] pieceTrusted) throws IOException {
        synchronized (this) {
            _isChecking = true;
            try {
                return locked_checkCreateFiles(recheck, pieceTrusted);
            } finally {
                _isChecking = false;
            }
        }
    }

    /**
     * Check the pieces, reporting whether anything changed.
     *
     * @param pieceTrusted array indexed by piece, or null to hash everything
     * @return true if changed (only valid if recheck == true)
     */
    private boolean locked_checkCreateFiles(boolean recheck, boolean[] pieceTrusted) throws IOException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        _checkProgress.set(0);
        // Whether we are resuming or not,
        // if any of the files already exists we assume we are resuming.
        boolean resume = false;

        _probablyComplete = true;
        // use local variables during the check
        int need = metainfo.getPieces();
        if (pieceTrusted != null) {
            for (boolean trusted : pieceTrusted) {
                if (trusted) {
                    need--;
                }
            }
        }
        BitField bfield;
        if (recheck) {
            bfield = new BitField(need);
        } else {
            bfield = bitfield;
        }

        // Make sure all files are available and of correct length
        // The files should all exist as they have been created with zero length by
        // createFilesFromNames()
        long lengthProgress = 0;
        for (int i = 0; i < _torrentFiles.size(); i++) {
            TorrentFile tf = _torrentFiles.get(i);
            if (tf.isPadding) {
                // Padding is never on disk; skip the length check and allocation
                lengthProgress += tf.length;
                continue;
            }
            long length = tf.RAFfile.length();
            lengthProgress += tf.length;
            boolean exists = tf.RAFfile.exists();
            if (exists && length == tf.length) {
                if (listener != null) {
                    listener.storageAllocated(this, length);
                }
                _checkProgress.set(0);
                resume = true; // XXX Could dynamicly check
            } else if (length == 0) {
                if (!exists) {
                    // File should exist when we get here, but could have vanished
                    // and we're now doing a recheck. Recreate at the active
                    // location (staging dir while incomplete, data dir otherwise).
                    recreateFile(tf);
                    String msg =
                            "Corrupt file '"
                                    + tf.name
                                    + "' was deleted (cannot repair), must be downloaded again";
                    if (listener != null) {
                        listener.addMessage(msg);
                    }
                    if (!ctx.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                    _log.error(msg);
                }
            }
            changed = true;
            synchronized (tf) {
                allocateFile(tf);
                // close as we go so we don't run out of file descriptors
                try {
                    tf.closeRAF();
                } catch (IOException ioe) { /* ignored */ }
            }
            if (!resume) {
                _checkProgress.set((int) (pieces * lengthProgress / total_length));
            } else {
                if (tf.length != length) {
                    String msg =
                            "File '"
                                    + tf.name
                                    + "' exists, but has wrong length (expected "
                                    + tf.length
                                    + " but found "
                                    + length
                                    + ") - repairing corruption...";
                    if (listener != null) {
                        listener.addMessage(msg);
                    }
                    if (!ctx.isRouterContext()) {
                        System.out.println(" • " + msg);
                    }
                    _log.error(msg);
                }
                changed = true;
                resume = true;
                _checkProgress.set(0);
                _probablyComplete = false; // to force RW
                synchronized (tf) {
                    RandomAccessFile raf = tf.checkRAF();
                    raf.setLength(tf.length);
                    try {
                        tf.closeRAF();
                    } catch (IOException ioe) { /* ignored */ }
                }
            }
        }

        // Check which pieces match and which don't
        if (resume) {
            byte[] piece = new byte[piece_size];
            int file = 0;
            long fileEnd = _torrentFiles.get(0).length;
            long pieceEnd = 0;
            for (int i = 0; i < pieces; i++) {
                _checkProgress.set(i);
                boolean trusted = pieceTrusted != null && pieceTrusted[i];
                boolean padOnly = pieceTrusted == null && isPaddingPiece(pieceEnd);
                int length;
                boolean correctHash;
                if (trusted || padOnly) {
                    // trusted: saved state says complete; padOnly: hashes as zeros, never on disk
                    length = (int) Math.min(piece_size, total_length - pieceEnd);
                    correctHash = false;
                } else {
                    length = getUncheckedPiece(i, piece);
                    correctHash = metainfo.checkPiece(i, piece, 0, length);
                }
                // close as we go so we don't run out of file descriptors
                pieceEnd += length;
                while (fileEnd <= pieceEnd) {
                    TorrentFile tf = _torrentFiles.get(file);
                    try {
                        tf.closeRAF();
                    } catch (IOException ioe) { /* ignored */ }
                    if (++file >= _torrentFiles.size()) {
                        break;
                    }
                    fileEnd += _torrentFiles.get(file).length;
                }
                if (trusted) {
                    continue;
                }
                if (correctHash || padOnly) {
                    bfield.set(i);
                    need--;
                    if (listener != null) {
                        listener.storageChecked(this, i, true);
                    }
                } else {
                    bfield.clear(i);
                    if (listener != null) {
                        listener.storageChecked(this, i, false);
                    }
                }
            }
        }

        _checkProgress.set(pieces);
        _probablyComplete = complete();

        // do this here so we don't confuse the user during checking
        needed = need;
        boolean rv = false;
        if (recheck) {
            // FIXME bogus synch
            synchronized (bitfield) {
                rv = !bfield.equals(bitfield);
                bitfield = bfield;
            }
        }

        if (listener != null) {
            listener.storageAllChecked(this);
            if (needed <= 0) {
                listener.storageCompleted(this);
            }
        }
        return rv;
    }

    /** BEP 47: true if the piece lies entirely within padding files, so it hashes as zeros. */
    private boolean isPaddingPiece(long pieceStart) {
        return metainfo.isRangePadding(
                pieceStart, (int) Math.min(piece_size, total_length - pieceStart));
    }

    /**
     * This creates a (presumably) sparse file so that reads won't fail with IOE. Sets isSparse[nr]
     * = true. balloonFile(nr) should be called later to defrag the file.
     *
     * <p>This calls OpenRAF(); caller must synchronize and call closeRAF().
     */
    private void allocateFile(TorrentFile tf) throws IOException {
        // caller synchronized
        tf.allocateFile();
        if (listener != null) {
            listener.storageCreateFile(this, tf.name, tf.length);
            listener.storageAllocated(this, tf.length);
        }
        // caller will close rafs[nr]
    }

    /**
     * Closes the Storage and makes sure that all RandomAccessFiles are closed. The Storage is
     * unusable after this.
     */
    @Override
    public void close() throws IOException {
        for (TorrentFile tf : _torrentFiles) {
            try {
                tf.closeRAF();
            } catch (IOException ioe) {
                _log.error("[I2PSnark] Error closing " + tf, ioe);
            }
        }
        changed = false;
    }

    /**
     * Returns a byte array containing a portion of the requested piece or null if the storage
     * doesn't contain the piece yet.
     * @return the piece
     */
    public ByteArray getPiece(int piece, int off, int len) throws IOException {
        if (!bitfield.get(piece)) {
            return null;
        }

        I2PAppContext ctx = I2PAppContext.getGlobalContext();

        // Catch a common place for OOMs esp. on 1MB pieces
        ByteArray rv;
        byte[] bs;
        try {
            // Will be restored to cache in Message.sendMessage()
            if (len == BUFSIZE) {
                rv = _cache.acquire();
            } else {
                rv = new ByteArray(new byte[len]);
            }
        } catch (OutOfMemoryError oom) {
            String msg = "Out of memory, can't honor request for piece " + piece;
            if (!ctx.isRouterContext()) {
                System.out.println(" • " + msg);
            }
            if (_log.shouldWarn()) {
                _log.warn("[I2PSnark] " + msg, oom);
            }
            return null;
        }
        bs = rv.getData();
        getUncheckedPiece(piece, bs, off, len);
        setActivity();
        return rv;
    }

    /**
     * Write a (hash-verified) partial piece to the data files.
     *
     * @param pp the verified partial piece to write
     * @param piece the piece number
     * @param shouldPreallocate whether to balloon sparse files
     * @param forceRW if true, open the data files read-write even when the
     *        storage thinks they are complete, and try to make them writable
     *        if permissions deny it.  Without this, checkRAF() opens files
     *        read-only for a complete torrent, and the write (or the
     *        pre-allocation ballooning) then fails with EBADF on a healthy
     *        disk.
     * @throws IOException when some storage related error occurs.
     * @since 0.9.71+
     */
    private void writePiece(
            PartialPiece pp, int piece, boolean shouldPreallocate, boolean forceRW) throws IOException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        // Early typecast, avoid possibly overflowing a temp integer
        FileCursor fc = new FileCursor((long) piece * (long) piece_size);
        int written = 0;
        int length = metainfo.getPieceLength(piece);
        while (written < length) {
            int need = length - written;
            int len = fc.chunk(need);
            TorrentFile tf = fc.getFile();
            if (tf.isPadding) {
                // Padding never touches disk; the piece hash already verified it as zeros
                written += len;
                fc.advance(len, need);
                continue;
            }
            synchronized (tf) {
                try {
                    RandomAccessFile raf = tf.checkRAF(forceRW);
                    if (tf.isSparse && shouldPreallocate) {
                        /*
                         * If the file is a newly created sparse file, AND we aren't skipping it,
                         * balloon it with all zeros to un-sparse it by allocating the space.
                         * Obviously this could take a while. Once we have written to it,
                         * it isn't empty/sparse any more.
                         */
                        if (tf.priority >= 0) {
                            if (_log.shouldInfo()) {
                                String msg = "Pre-allocating file: " + tf + "...";
                                _log.info("[I2PSnark] " + msg);
                                if (!ctx.isRouterContext()) {
                                    System.out.println(" • " + msg);
                                }
                            }
                            tf.balloonFile();
                        } else {
                            tf.isSparse = false;
                        }
                    } else {
                        if (_log.shouldInfo()) {
                            String msg =
                                    "Not pre-allocating file: "
                                            + tf
                                            + " -> Disabled by configuration";
                            _log.info("[I2PSnark] " + msg);
                            if (!ctx.isRouterContext()) {
                                System.out.println(" • " + msg);
                            }
                        }
                    }
                    raf.seek(fc.getOffset());
                    pp.write(raf, written, len);
                } catch (IOException ioe) {
                    try {
                        tf.closeRAF();
                    } catch (IOException ioe2) { /* ignored */ }
                    // get the file name in the logs
                    IOException ioe2 =
                            new IOException("Error writing " + tf.RAFfile.getAbsolutePath());
                    ioe2.initCause(ioe);
                    throw ioe2;
                }
            }
            written += len;
            fc.advance(len, need);
        }
    }

    /**
     * Put the piece in the Storage if it is correct. Warning - takes a LONG time if complete as it
     * does the recheck here. TODO thread the recheck?
     *
     * @return true if the piece was correct (sha metainfo hash matches), otherwise false.
     * @throws IOException when some storage related error occurs.
     */
    public boolean putPiece(PartialPiece pp) throws IOException {
        int piece = pp.getPiece();
        boolean shouldPreallocate = _util.getPreallocateFiles();
        try {
            synchronized (bitfield) {
                if (bitfield.get(piece)) return true; // No need to store twice.
            }

            // TODO alternative - check hash on the fly as we write to the file,
            // to save another I/O pass
            boolean correctHash = metainfo.checkPiece(pp);
            if (!correctHash) {
                if (listener != null) {
                    listener.storageChecked(this, piece, false);
                }
                return false;
            }

            try {
                writePiece(pp, piece, shouldPreallocate, true);
            } catch (IOException ioe) {
                // Rectify before giving up.  The failure is often a stale
                // read-only handle: when the storage thinks the file is
                // complete, checkRAF() opens it "r", and any write (piece data
                // or pre-allocation ballooning) then fails with EBADF on a
                // perfectly healthy disk.  Close, try to make the file
                // writable, and retry the whole write once with a forced RW
                // reopen.
                if (_log.shouldWarn()) {
                    _log.warn(
                            "[I2PSnark] Write failed on piece "
                                    + piece
                                    + " for "
                                    + metainfo.getName()
                                    + " - retrying with forced RW reopen: "
                                    + ioe);
                }
                writePiece(pp, piece, shouldPreallocate, true);
            }
        } finally {
            pp.release();
        }

        setActivity();

        // Do this after the write, so we know it succeeded, and we don't set the
        // needed count to zero, which would cause checkRAF() to open the file readonly.
        boolean complete = false;
        synchronized (bitfield) {
            if (!bitfield.get(piece)) {
                bitfield.set(piece);
                needed--;
                complete = needed == 0;
            }
        }
        // tell listener after counts are updated
        if (listener != null) {
            listener.storageChecked(this, piece, true);
        }

        if (complete) {
            // do we also need to close all of the files and reopen
            // them readonly?

            // Do a complete check to be sure.
            // Temporarily resets the 'needed' variable and 'bitfield', then call
            // checkCreateFiles() which will set 'needed' and 'bitfield'
            // and also call listener.storageCompleted() if the double-check
            // was successful.
            checkCreateFiles(true);
            if (needed > 0) {
                if (listener != null) listener.setWantedPieces(this);
                if (_log.shouldWarn()) {
                    _log.warn("[I2PSnark] WARNING: Not really done, missing " + needed + " pieces");
                }
            }
        }
        // Move any file whose pieces are all downloaded out of the staging dir
        checkFileCompletions();
        return true;
    }

    /**
     * Moves any file whose pieces are all downloaded out of the staging
     * directory into the data directory. No-op when the staging feature is
     * disabled.
     *
     * <p>Called after every piece write and after checks/rechecks. The move
     * itself happens on a background thread; this method only starts it.
     *
     * @since 0.9.71+
     */
    private void checkFileCompletions() {
        if (_stagingBase == null) {
            return;
        }
        long[] ends = metainfo.fileEnds();
        long start = 0;
        boolean complete = false;
        if (ends == null) {
            // single-file torrent
            complete = complete();
        }
        for (int i = 0; i < _torrentFiles.size(); i++) {
            TorrentFile tf = _torrentFiles.get(i);
            if (tf.isPadding || tf.movedToDataDir) {
                start += tf.length;
                continue;
            }
            boolean done;
            if (ends == null) {
                done = complete;
            } else {
                long end = start + tf.length;
                done = fileComplete(start, end);
            }
            start += tf.length;
            if (done) {
                startCopy(tf);
            }
        }
    }

    /**
     * Whether all pieces overlapping the byte range [start, end) are set in the bitfield.
     *
     * @param start first byte offset of the file in the torrent
     * @param end one past the last byte of the file
     * @return true if the file's pieces are all downloaded
     * @since 0.9.71+
     */
    private boolean fileComplete(long start, long end) {
        int startPiece = (int) (start / piece_size);
        int endPiece = (int) ((end - 1) / piece_size);
        if (endPiece >= pieces) {
            endPiece = pieces - 1;
        }
        // piece may span a file boundary; require every piece it overlaps to be set
        for (int i = startPiece; i <= endPiece; i++) {
            if (!bitfield.get(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Starts copying a completed file from the staging directory to the data
     * directory on a background thread, unless a copy for the file is already
     * running.
     *
     * <p>The staging file is read with its open RAF handle; the handle is
     * closed before the file is renamed away, so a concurrent write to the
     * same file (which would be a hash failure anyway) is not a concern.
     *
     * @param tf the completed file, still located in the staging directory
     * @since 0.9.71+
     */
    private void startCopy(TorrentFile tf) {
        // serialize with any concurrent checkFileCompletions() caller
        synchronized (tf) {
            if (tf.movedToDataDir || !_copying.add(tf)) {
                return;
            }
        }
        I2PAppThread t =
                new I2PAppThread("SnarkStorageCopy-" + tf.name) {
                    @Override
                    public void run() {
                        copyToFinal(tf);
                    }
                };
        t.setDaemon(true);
        t.start();
    }

    /**
     * Copies the file to the data directory with a bounded number of retries
     * in case the destination is temporarily unavailable. Always runs on a
     * daemon thread.
     *
     * @param tf the completed file
     * @since 0.9.71+
     */
    private void copyToFinal(TorrentFile tf) {
        try {
            for (int i = 0; i < MAX_COPY_RETRIES; i++) {
                if (copyOnce(tf)) {
                    return;
                }
                sleep();
            }
            if (_log.shouldWarn()) {
                _log.warn(
                        "[I2PSnark] Giving up copying "
                                + tf.name
                                + " to the data directory after "
                                + MAX_COPY_RETRIES
                                + " attempts");
            }
        } finally {
            _copying.remove(tf);
        }
    }

    /**
     * Copies the file from the staging directory to the data directory once.
     *
     * @param tf the completed file
     * @return true if the file was moved, false if it must be retried
     * @since 0.9.71+
     */
    private boolean copyOnce(TorrentFile tf) {
        File finalFile = tf.finalFile;
        File workFile = tf.RAFfile;
        File tmp;
        try {
            // close the read handle so the file can be renamed; any piece write
            // after this point would be a hash mismatch and is lost
            synchronized (tf) {
                try {
                    tf.closeRAF();
                } catch (IOException ioe) { /* ignored */ }
            }
            File parent = finalFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create directory " + parent);
            }
            // a previous failed attempt may have left a temp file
            tmp = new File(finalFile.getParentFile(), finalFile.getName() + ".part");
            if (tmp.exists() && !tmp.delete()) {
                throw new IOException("Could not delete stale temp file " + tmp);
            }
            if (!copyFile(workFile, tmp)) {
                return false;
            }
            if (!tmp.renameTo(finalFile)) {
                return false;
            }
            synchronized (tf) {
                if (!workFile.delete()) {
                    _log.warn("[I2PSnark] Unable to delete staging file: " + workFile);
                }
                tf.RAFfile = finalFile;
                tf.isSparse = false;
                tf.movedToDataDir = true;
            }
            if (_log.shouldInfo()) {
                _log.info("[I2PSnark] Moved " + tf.name + " to " + finalFile);
            }
            return true;
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {
                _log.warn(
                        "[I2PSnark] Error copying "
                                + tf.name
                                + " to the data directory: "
                                + ioe);
            }
            return false;
        }
    }

    /**
     * Copies a file's contents to the destination, truncating the destination
     * first.
     *
     * @return true on success, false on transient failure (e.g. disk full)
     * @since 0.9.71+
     */
    private boolean copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[BUFSIZE];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return true;
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {
                _log.warn("[I2PSnark] Error copying " + src + " to " + dst + ": " + ioe);
            }
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ioe) { /* ignored */ }
            try {
                if (out != null) out.close();
            } catch (IOException ioe) { /* ignored */ }
        }
    }

    /** Delays between copy attempts; returns immediately on interruption. */
    private void sleep() {
        try {
            Thread.sleep(COPY_RETRY_DELAY);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * This is a dup of MetaInfo.getPieceLength() but we need it before the MetaInfo is created in
     * our second constructor.
     *
     * @return the piece length
     * @since 0.8.5
     */
    private int getPieceLength(int piece) {
        if (piece >= 0 && piece < pieces - 1) {
            return piece_size;
        } else if (piece == pieces - 1) {
            return (int) (total_length - ((long) piece * piece_size));
        } else {
            throw new IndexOutOfBoundsException("no piece: " + piece);
        }
    }

    private int getUncheckedPiece(int piece, byte[] bs) throws IOException {
        return getUncheckedPiece(piece, bs, 0, getPieceLength(piece));
    }

    private int getUncheckedPiece(int piece, byte[] bs, int off, int length) throws IOException {
        // Early typecast, avoid possibly overflowing a temp integer
        FileCursor fc = new FileCursor(((long) piece * (long) piece_size) + off);
        int read = 0;
        while (read < length) {
            int need = length - read;
            int len = fc.chunk(need);
            TorrentFile tf = fc.getFile();
            if (tf.isPadding) {
                // BEP 47: padding regions hash as zeros, never touch disk
                Arrays.fill(bs, read, read + len, (byte) 0);
            } else {
                synchronized (tf) {
                    try {
                        RandomAccessFile raf = tf.checkRAF();
                        raf.seek(fc.getOffset());
                        raf.readFully(bs, read, len);
                    } catch (IOException ioe) {
                        try {
                            tf.closeRAF();
                        } catch (IOException ioe2) { /* ignored */ }
                        // get the file name in the logs
                        IOException ioe2 =
                                new IOException("Error reading " + tf.RAFfile.getAbsolutePath());
                        ioe2.initCause(ioe);
                        throw ioe2;
                    }
                }
            }
            read += len;
            fc.advance(len, need);
        }
        return length;
    }

    /**
     * Cursor over the torrent's files, mapping an absolute byte position in the torrent to the
     * containing file and the offset within it. Used for sequential reads and writes across file
     * boundaries.
     *
     * @since 0.9.71+
     */
    private final class FileCursor {
        private int i;
        private long raflen;
        private long start;

        /**
         * Position the cursor at the given absolute byte offset in the torrent.
         *
         * @param pos the byte position in the torrent
         */
        FileCursor(long pos) {
            long[] ends = metainfo != null ? metainfo.fileEnds() : null;
            if (ends != null) {
                i = metainfo.fileIndex(pos);
                start = pos - (i == 0 ? 0 : ends[i - 1]);
            } else {
                // torrent creation path: metainfo is not built yet, walk the file list
                i = 0;
                start = pos;
                while (start > _torrentFiles.get(i).length) {
                    start -= _torrentFiles.get(i).length;
                    i++;
                }
            }
            raflen = _torrentFiles.get(i).length;
        }

        /**
     * The file containing the cursor position.
     *
     * @return the file containing the cursor position
     */
        TorrentFile getFile() {
            return _torrentFiles.get(i);
        }

        /**
     * The offset of the cursor within the current file.
     *
     * @return the offset of the cursor within the current file
     */
        long getOffset() {
            return start;
        }

        /**
         * The chunk length from the cursor position to the end of the current file, or need,
         * whichever is smaller.
         *
         * @param need the number of bytes wanted
         * @return the chunk length
         */
        int chunk(int need) {
            return (start + need < raflen) ? need : (int) (raflen - start);
        }

        /**
         * Advance the cursor past a consumed chunk, moving to the next file when the chunk ended at
         * a file boundary.
         *
         * @param len the chunk length just consumed
         * @param need the number of bytes wanted before the chunk was taken
         */
        void advance(int len, int need) {
            if (need - len > 0) {
                i++;
                raflen = _torrentFiles.get(i).length;
                start = 0;
            }
        }
    }

    private static final long RAF_CLOSE_DELAY = 4 * (long) 60 * 1000;

    /** Close unused RAFs - call periodically */
    public void cleanRAFs() {
        long cutoff = System.currentTimeMillis() - RAF_CLOSE_DELAY;
        for (TorrentFile tf : _torrentFiles) {
            tf.closeRAF(cutoff);
        }
    }

    /**
     * A single file in a torrent.
     *
     * @since 0.9.9
     */
    private class TorrentFile implements Comparable<TorrentFile> {
        public final long length;
        public final String name;

        /**
         * The active file: the staging-dir file while the file is incomplete,
         * the data-dir file after it has been moved. Never null. Not final:
         * replaced by the final file when the move completes. Locking: this.
         */
        public File RAFfile;

        /**
         * The final data-directory location of the file. Constant even while
         * the data is still being written to the staging dir, so paths shown
         * in the UI and used for file matching never change. Locking: none.
         */
        public final File finalFile;

        /**
         * The staging-dir location while the file is incomplete, null once the
         * file lives in the data directory (or when staging is disabled).
         * Locking: none.
         */
        public final File workFile;

        /**
         * Whether the file lives in the data directory; set when the copy out
         * of the staging dir completes. Locking: this.
         */
        public volatile boolean movedToDataDir;

        /** When the RAF was last accessed, or 0 if closed; locking: this. */
        private long RAFtime;

        /** Null when closed; locking: this. */
        private RandomAccessFile raf;

        /** Whether the file is empty and sparse; locking: this. */
        public boolean isSparse;

        /** BEP 47 padding placeholder; hashes as zeros and is never read from or written to disk */
        public final boolean isPadding;

        /** Priority by file; default 0. */
        public volatile int priority;

        /** For new metainfo from files; use base == f for single-file torrent */
        public TorrentFile(File base, File f) {
            this(base, f, f.length());
        }

        /**
         * For existing metainfo with specified file length; use base == f for single-file torrent
         */
        public TorrentFile(File base, File f, long len) {
            this(base, f, len, false);
        }

        /**
         * For existing metainfo; a padding file is a synthetic BEP 47 zero-fill entry whose pieces
         * hash as zeros. The runtime never creates, allocates, or writes it to disk.
         */
        public TorrentFile(File base, File f, long len, boolean padding) {
            this(base, f, f, null, len, padding);
        }

        /**
         * Full constructor supporting the staging feature.
         *
         * @param base the data dir (or the base file for a single-file torrent)
         * @param active the file to read and write now (staging or data dir)
         * @param finalFile the data-directory location
         * @param workFile the staging-dir location, or null when the file is
         *     already in the data directory
         * @param len expected length
         * @param padding whether this is a BEP 47 padding placeholder
         * @since 0.9.71+
         */
        public TorrentFile(
                File base,
                File active,
                File finalFile,
                File workFile,
                long len,
                boolean padding) {
            String n = finalFile.getPath();
            if (base.isDirectory() && n.startsWith(base.getPath())) {
                n = n.substring(base.getPath().length() + 1);
            }
            name = n;
            length = len;
            RAFfile = active;
            this.finalFile = finalFile;
            this.workFile = workFile;
            isPadding = padding;
            movedToDataDir = workFile == null;
        }

        /*
         * For each of the following, caller must synchronize on RAFlock[i]
         * ... except at the beginning if you're careful
         */

        /** This must be called before using the RAF to ensure it is open locking: this */
        public synchronized RandomAccessFile checkRAF() throws IOException {
            if (raf != null) {
                RAFtime = System.currentTimeMillis();
            } else {
                openRAF();
            }
            return raf;
        }

        /**
         * Like checkRAF(), but force a read-write open.  When the storage is
         * thought complete the files are opened read-only, and any write through
         * such a handle fails with EBADF on a perfectly healthy disk.  The write
         * path calls this to force a true RW handle, closing any existing
         * read-only one first, and attempts to make the file writable if
         * permissions deny it.
         *
         * @return the read-write handle
         * @throws IOException if the file cannot be opened read-write
         * @since 0.9.71+
         */
        public synchronized RandomAccessFile checkRAF(boolean forceRW) throws IOException {
            if (raf != null && !forceRW) {
                RAFtime = System.currentTimeMillis();
            } else {
                if (raf != null) {
                    closeRAF();
                }
                if (forceRW && !RAFfile.canWrite() && !RAFfile.setWritable(true)) {
                    if (_log.shouldWarn()) {
                        _log.warn("[I2PSnark] Unable to make file writable: " + RAFfile.getAbsolutePath());
                    }
                }
                openRAF(false, forceRW);
            }
            return raf;
        }

        /** Locking: this. */
        private synchronized void openRAF() throws IOException {
            openRAF(_probablyComplete);
        }

        /** Locking: this. */
        private synchronized void openRAF(boolean readonly) throws IOException {
            openRAF(readonly, false);
        }

        /** Locking: this. */
        private synchronized void openRAF(boolean readonly, boolean forceRW) throws IOException {
            raf = new RandomAccessFile(RAFfile, forceRW ? "rw" : (readonly || !RAFfile.canWrite()) ? "r" : "rw");
            RAFtime = System.currentTimeMillis();
        }

        /** Close if last used time older than cutoff. locking: this */
        public synchronized void closeRAF(long cutoff) {
            if (RAFtime > 0 && RAFtime < cutoff) {
                try {
                    closeRAF();
                } catch (IOException ioe) { /* ignored */ }
            }
        }

        /** Can be called even if not open locking: this */
        public synchronized void closeRAF() throws IOException {
            RAFtime = 0;
            if (raf == null) {
                return;
            }
            raf.close();
            raf = null;
        }

        /**
         * This creates a (presumably) sparse file so that reads won't fail with IOE. Sets
         * isSparse[nr] = true. balloonFile(nr) should be called later to defrag the file.
         *
         * <p>File MUST exist or will throw IOE
         *
         * <p>This calls openRAF(); caller must synchronize and call closeRAF().
         */
        public synchronized void allocateFile() throws IOException {
            // caller synchronized
            // force RW via checkRAF(true): openRAF(false) still falls back to
            // "r" when the file is not writable, and setLength() then fails
            // with EINVAL (or the open fails with EACCES)
            checkRAF(true); // RW
            raf.setLength(length);
            boolean shouldPreallocate = _util.getPreallocateFiles();
            /**
             * Don't bother ballooning later on Windows since there is no sparse file support until
             * JDK7 using the JSR-203 interface.
             *
             * <p>RAF seeks/writes do not create sparse files.
             *
             * <p>Windows will zero-fill up to the point of the write, which will make the file
             * fairly unfragmented, on average, at least until near the end where it will get
             * exponentially more fragmented.
             *
             * <p>Also don't ballon on ARM, as a proxy for solid state disk, where fragmentation
             * doesn't matter too much. Actual detection of SSD is almost impossible.
             */
            if (!_isWindows && !_isARM && shouldPreallocate) {
                isSparse = true;
            } else if (_log.shouldWarn()) {
                if (!shouldPreallocate) {
                    _log.warn("[I2PSnark] Not pre-allocating files -> Disabled by configuration");
                } else if (_isWindows) {
                    _log.warn("[I2PSnark] Not pre-allocating files -> Windows OS detected");
                } else if (_isARM) {
                    _log.warn(
                            "[I2PSnark] Not pre-allocating files -> ARM processor detected,"
                                + " assuming solid state storage");
                }
            }
        }

        /**
         * This "balloons" the file with zeros to eliminate disk fragmentation., Overwrites the
         * entire file with zeros. Sets isSparse[nr] = false.
         *
         * <p>Caller must synchronize and call checkRAF() or openRAF().
         *
         * @since 0.9.1
         */
        private synchronized void balloonFile() throws IOException {
            long remaining = length;
            final int ZEROBLOCKSIZE = (int) Math.min(remaining, (long) 32 * 1024);
            byte[] zeros = new byte[ZEROBLOCKSIZE];
            raf.seek(0);
            // don't bother setting flag for small files
            if (remaining > 20 * 1024 * 1024) {
                _allocateCount.incrementAndGet();
            }
            try {
                while (remaining > 0) {
                    int size = (int) Math.min(remaining, ZEROBLOCKSIZE);
                    raf.write(zeros, 0, size);
                    remaining -= size;
                }
            } finally {
                remaining = length;
                if (remaining > 20 * 1024 * 1024) {
                    _allocateCount.decrementAndGet();
                }
            }
            isSparse = false;
        }

        public int compareTo(TorrentFile tf) {
            return name.compareTo(tf.name);
        }

        /**
         * Whether hash code is present.
         *
         * <p>Keyed on the final data-directory path, which is constant while
         * the file is moved from staging to the data dir.
         *
         * @return whether h code is present
         */
        @Override
        public int hashCode() {
            return finalFile.getAbsolutePath().hashCode();
        }

        /**
         * Whether this equals the other.
         */
        @Override
        public boolean equals(Object o) {
            return (o instanceof TorrentFile)
                    && finalFile.getAbsolutePath()
                            .equals(((TorrentFile) o).finalFile.getAbsolutePath());
        }

        /**
         * String form of the storage.
         */
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Create a metainfo. Used in the installer build process; do not comment out.
     *
     * @since 0.9.4
     */
    public static void main(String[] args) {
        boolean error = false;
        String created_by = null;
        String announce = null;
        List<String> url_list = null;
        String comment = null;
        Getopt g = new Getopt("Storage", args, "a:c:m:w:");
        try {
            int c;
            while ((c = g.getopt()) != -1) {
                switch (c) {
                    case 'a':
                        announce = g.getOptarg();
                        break;

                    case 'c':
                        created_by = g.getOptarg();
                        break;

                    case 'm':
                        comment = g.getOptarg();
                        break;

                    case 'w':
                        if (url_list == null) url_list = new ArrayList<>();
                        url_list.add(g.getOptarg());
                        break;

                    case '?':
                    case ':':
                    default:
                        error = true;
                        break;
                } // switch
            } // while
        } catch (RuntimeException e) {
            e.printStackTrace();
            error = true;
        }
        if (error || args.length - g.getOptind() != 1) {
            System.err.println(
                    "Usage: Storage [-a announceURL] [-c created-by] [-m comment] [-w webseed-url]*"
                        + " file-or-dir");
            System.exit(1);
        }
        File base = new File(args[g.getOptind()]);
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        I2PSnarkUtil util = new I2PSnarkUtil(ctx);
        File file = null;
        try {
            Storage storage =
                    new Storage(
                            util,
                            base,
                            announce,
                            null,
                            created_by,
                            false,
                            url_list,
                            comment,
                            null,
                            null);
            MetaInfo meta = storage.getMetaInfo();
            file = new File(storage.getBaseName() + ".torrent");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(meta.getTorrentData());
            }
            String hex = DataHelper.toString(meta.getInfoHash());
            System.out.println("Created: " + file);
            System.out.println("InfoHash: " + hex);
            String basename = base.getName().replace(" ", "%20");
            String magnet = MagnetURI.MAGNET_FULL + hex + "&dn=" + basename;
            if (announce != null) {
                magnet += "&tr=" + announce;
            }
            System.out.println("Magnet: " + magnet);
        } catch (IOException ioe) {
            if (file != null) {
                file.delete();
            }
            ioe.printStackTrace();
            System.exit(1);
        }
    }
}
