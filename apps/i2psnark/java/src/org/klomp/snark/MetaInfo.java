/* MetaInfo - Holds all information gotten from a torrent file.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import gnu.getopt.Getopt;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Holds all information extracted from a torrent file.
 *
 * <p>This class parses and stores the metadata from .torrent files, including:
 *
 * <ul>
 *   <li>Tracker announce URLs
 *   <li>Info hash for torrent identification
 *   <li>File names, sizes, and structure
 *   <li>Piece length and piece hashes for integrity verification
 * </ul>
 *
 * <p><strong>Note:</strong> This class has a known limitation where it doesn't propagate custom
 * meta fields into the bencoded info data, and from there to the info_hash. However, it currently
 * works with torrents created by I2P-BT, I2PRufus and Azureus.
 *
 * @since 0.1.0
 */
public class MetaInfo {
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(MetaInfo.class);
    private final String announce;
    private final byte[] info_hash;
    private final String name;
    private final List<List<String>> files;
    private final List<String> attributes;
    private final List<Long> lengths;
    private final int piece_length;
    private final byte[] piece_hashes;
    /** Cumulative exclusive end offset of each file, lazily computed; null for single-file. */
    private volatile long[] _fileEnds;
    private final long length;
    private final int privateTorrent; // 0: not present; 1: = 1; -1: = 0
    private final List<List<String>> announce_list;
    private final String comment;
    private final String created_by;
    private final long creation_date;
    private final List<String> url_list;
    /** Original bencoded info dictionary, preserved to keep infohash consistent on re-encode. */
    private Map<String, BEValue> infoMap;
    /** Length of the serialized info dictionary in bytes, cached after first computation. */
    private int infoBytesLength;

    /**
     * Called by Storage when creating a new torrent from local data.
     *
     * @param announce the tracker announce URL, may be null
     * @param name the file or top-level directory name
     * @param nameUtf8 unused, retained for API compatibility
     * @param files list of file path components per file, null for single-file torrent
     * @param lengths list of file sizes in bytes, null for single-file torrent
     * @param attributes per-file attribute strings (BEP 47 "p" marks padding files), may be null
     * @param pieceLength the length of each data piece in bytes
     * @param pieceHashes concatenated 20-byte SHA1 hashes of all pieces
     * @param length total torrent size in bytes
     * @param privateTorrent whether the tracker restricts peer sharing to the swarm
     * @param announceList tiered list of alternate announce URLs, may be null
     * @param createdBy application name that created the torrent, may be null
     * @param urlList web seed URLs for BEP 19 HTTP seeding, may be null
     * @param comment free-form user comment, may be null
     */
    public MetaInfo(
            String announce,
            String name,
            String nameUtf8,
            List<List<String>> files,
            List<Long> lengths,
            List<String> attributes,
            int pieceLength,
            byte[] pieceHashes,
            long length,
            boolean privateTorrent,
            List<List<String>> announceList,
            String createdBy,
            List<String> urlList,
            String comment) {
        this.announce = announce;
        this.name = name;
        this.files = files == null ? null : Collections.unmodifiableList(files);
        this.lengths = lengths == null ? null : Collections.unmodifiableList(lengths);
        this.attributes =
                attributes == null ? null : Collections.unmodifiableList(attributes);
        this.piece_length = pieceLength;
        this.piece_hashes = pieceHashes;
        this.length = length;
        this.privateTorrent = privateTorrent ? 1 : 0;
        this.announce_list = announceList;
        this.comment = comment;
        this.created_by = null;
        this.creation_date = 0;
        this.url_list = urlList;
        this.info_hash = calculateInfoHash();
    }

    /**
     * Preserves privateTorrent int value, for use by main().
     *
     * @param announce the tracker announce URL, may be null
     * @param name the file or top-level directory name
     * @param nameUtf8 unused, retained for API compatibility
     * @param files list of file path components per file, null for single-file torrent
     * @param lengths list of file sizes in bytes, null for single-file torrent
     * @param pieceLength the length of each data piece in bytes
     * @param pieceHashes concatenated 20-byte SHA1 hashes of all pieces
     * @param length total torrent size in bytes
     * @param privateTorrent 0 = not present, 1 = private, -1 = explicitly not private
     * @param announceList tiered list of alternate announce URLs, may be null
     * @param createdBy application name that created the torrent, may be null
     * @param urlList web seed URLs for BEP 19 HTTP seeding, may be null
     * @param comment free-form user comment, may be null
     * @since 0.9.62
     */
    public MetaInfo(
            String announce,
            String name,
            String nameUtf8,
            List<List<String>> files,
            List<Long> lengths,
            int pieceLength,
            byte[] pieceHashes,
            long length,
            int privateTorrent,
            List<List<String>> announceList,
            String createdBy,
            List<String> urlList,
            String comment) {
        this.announce = announce;
        this.name = name;
        this.files = files == null ? null : Collections.unmodifiableList(files);
        this.lengths = lengths == null ? null : Collections.unmodifiableList(lengths);
        this.piece_length = pieceLength;
        this.piece_hashes = pieceHashes;
        this.length = length;
        this.privateTorrent = privateTorrent;
        this.announce_list = announceList;
        this.comment = comment;
        this.created_by = createdBy;
        this.creation_date = I2PAppContext.getGlobalContext().clock().now();
        this.url_list = urlList;
        this.attributes = null;
        this.info_hash = calculateInfoHash();
    }

    /**
     * Will not change infohash. Retains creation date of old MetaInfo if nonzero.
     *
     * @param old the source MetaInfo whose infohash, name, files, and hashes are reused
     * @param newAnnounce the replacement announce URL, may be null
     * @param newAnnounceList tiered list of replacement announce URLs, may be null
     * @param newComment replacement comment string, may be null
     * @param newCreatedBy replacement created-by string, may be null
     * @param newUrlList replacement web seed URLs, may be null
     * @since 0.9.64
     */
    public MetaInfo(
            MetaInfo old,
            String newAnnounce,
            List<List<String>> newAnnounceList,
            String newComment,
            String newCreatedBy,
            List<String> newUrlList) {
        this.announce = newAnnounce;
        this.info_hash = old.info_hash;
        this.name = old.name;
        this.files = old.files;
        this.attributes = old.attributes;
        this.lengths = old.lengths;
        this.piece_length = old.piece_length;
        this.piece_hashes = old.piece_hashes;
        this.length = old.length;
        this.privateTorrent = old.privateTorrent;
        this.announce_list = newAnnounceList;
        this.comment = newComment;
        this.created_by = null;
        this.creation_date =
                old.creation_date > 0
                        ? old.creation_date
                        : I2PAppContext.getGlobalContext().clock().now();
        this.url_list = newUrlList;
        this.infoMap = old.infoMap;
        this.infoBytesLength = old.infoBytesLength;
    }

    /**
     * Creates a new MetaInfo from the given InputStream. The InputStream must start with a
     * correctly bencoded dictionary describing the torrent. Caller must close the stream.
     *
     * @param in the stream containing the bencoded torrent data
     * @throws IOException if the stream cannot be read or the bencoded data is malformed
     */
    public MetaInfo(InputStream in) throws IOException {
        this(new BDecoder(in));
    }

    /**
     * Creates a new MetaInfo from the given BDecoder. The BDecoder must have a complete dictionary
     * describing the torrent.
     */
    private MetaInfo(BDecoder be) throws IOException {
        // Note that evaluation order matters here...
        this(be.bdecodeMap().getMap());
        byte[] origInfohash = be.get_special_map_digest();
        // shouldn't ever happen
        if (!DataHelper.eq(origInfohash, info_hash)) {
            throw new InvalidBEncodingException("Infohash mismatch, please report");
        }
    }

    /**
     * Creates a new MetaInfo from a Map of BEValues and the SHA1 over the original bencoded info
     * dictionary (this is a hack, we could reconstruct the bencoded stream and recalculate the
     * hash). Will NOT throw a InvalidBEncodingException if the given map does not contain a valid
     * announce string. WILL throw a InvalidBEncodingException if the given map does not contain a
     * valid info dictionary.
     *
     * @param m the parsed bencoded dictionary as a map of keys to BEValues
     * @throws InvalidBEncodingException if the info dictionary is missing or malformed
     */
    public MetaInfo(Map<String, BEValue> m) throws InvalidBEncodingException {
        if (_log.shouldDebug()) {
            _log.debug("Creating a metaInfo: " + m, new Exception("source"));
        }
        BEValue val = m.get("announce");
        // Disabled check, we can get info from a magnet now
        if (val == null) {
            this.announce = null;
        } else {
            this.announce = val.getString();
        }

        // BEP 12
        val = m.get("announce-list");
        if (val == null) {
            this.announce_list = null;
        } else {
            this.announce_list = new ArrayList<>();
            List<BEValue> bl1 = val.getList();
            for (BEValue bev : bl1) {
                List<BEValue> bl2 = bev.getList();
                List<String> sl2 = new ArrayList<>();
                for (BEValue bev2 : bl2) {
                    sl2.add(bev2.getString());
                }
                this.announce_list.add(sl2);
            }
        }

        // BEP 19
        val = m.get("url-list");
        if (val == null) {
            this.url_list = null;
        } else {
            List<String> urllist;
            try {
                List<BEValue> bl1 = val.getList();
                urllist = new ArrayList<>(bl1.size());
                for (BEValue bev : bl1) {
                    urllist.add(bev.getString());
                }
            } catch (InvalidBEncodingException ibee) {
                // BEP 19 says it's a list but the example there
                // is for a single byte string, and we've seen this
                // in the wild.
                urllist = Collections.singletonList(val.getString());
            }
            this.url_list = urllist;
        }

        // misc. optional  top-level stuff
        val = m.get("comment");
        String st = null;
        if (val != null) {
            try {
                st = val.getString();
            } catch (InvalidBEncodingException ibee) { /* ignored */ }
        }
        this.comment = st;
        val = m.get("created by");
        st = null;
        if (val != null) {
            try {
                st = val.getString();
            } catch (InvalidBEncodingException ibee) { /* ignored */ }
        }
        this.created_by = st;
        val = m.get("creation date");
        long time = 0;
        if (val != null) {
            try {
                time = val.getLong() * 1000;
            } catch (InvalidBEncodingException ibee) { /* ignored */ }
        }
        this.creation_date = time;

        val = m.get("info");
        if (val == null) {
            throw new InvalidBEncodingException("Missing info map");
        }
        Map<String, BEValue> info = val.getMap();
        infoMap = Collections.unmodifiableMap(info);

        val = info.get("name");
        if (val == null) {
            throw new InvalidBEncodingException("Missing name string");
        }
        name = val.getString();
        // We could silently replace the '/', but that messes up the info hash, so just throw
        // instead.
        if (name.indexOf('/') >= 0) {
            throw new InvalidBEncodingException("Invalid name containing '/' " + name);
        }

        // BEP 27
        val = info.get("private");
        if (val != null) {
            Object o = val.getValue();
            // Is it supposed to be a number or a string?
            // i2psnark does it as a string. BEP 27 doesn't say.
            // Transmission does numbers. So does libtorrent.
            // We handle both as of 0.9.9.
            // We switch to storing as number as of 0.9.14.
            boolean privat =
                    "1".equals(o) || ((o instanceof Number) && ((Number) o).intValue() == 1);
            privateTorrent = privat ? 1 : -1;
        } else {
            privateTorrent = 0;
        }

        val = info.get("piece length");
        if (val == null) {
            throw new InvalidBEncodingException("Missing piece length number");
        }
        piece_length = val.getInt();

        val = info.get("pieces");
        if (val == null) {
            // BEP 52
            // We do the check here because a torrent file could be combined v1/v2,
            // so a version 2 value isn't by itself fatal
            val = info.get("meta version");
            if (val != null) {
                int version = val.getInt();
                if (version != 1) {
                    throw new InvalidBEncodingException(
                            "Version " + version + " torrent file not supported");
                }
            }
            throw new InvalidBEncodingException("Missing piece bytes");
        }
        piece_hashes = val.getBytes();

        val = info.get("length");
        if (val != null) {
            // Single file case.
            length = val.getLong();
            files = null;
            lengths = null;
            attributes = null;
        } else {
            // Multi file case.
            val = info.get("files");
            if (val == null) {
                throw new InvalidBEncodingException("Missing length number and/or files list");
            }

            List<BEValue> list = val.getList();
            int size = list.size();
            if (size == 0) {
                throw new InvalidBEncodingException("Zero size files list");
            }

            List<List<String>> mFiles = new ArrayList<>(size);
            List<Long> mLengths = new ArrayList<>(size);
            List<String> mAttributes = null;
            long l = 0;
            for (int i = 0; i < list.size(); i++) {
                Map<String, BEValue> desc = list.get(i).getMap();
                val = desc.get("length");
                if (val == null) {
                    throw new InvalidBEncodingException("Missing length number");
                }
                long len = val.getLong();
                if (len < 0) {
                    throw new InvalidBEncodingException("Negative file length");
                }
                mLengths.add(Long.valueOf(len));
                // check for overflowing the long
                long oldTotal = l;
                l += len;
                if (l < oldTotal) {
                    throw new InvalidBEncodingException("Huge total length");
                }

                val = desc.get("path");
                if (val == null) {
                    throw new InvalidBEncodingException("Missing path list");
                }
                List<BEValue> pathList = val.getList();
                int pathLength = pathList.size();
                if (pathLength == 0) {
                    throw new InvalidBEncodingException("Zero size file path list");
                }

                List<String> file = new ArrayList<>(pathLength);
                Iterator<BEValue> it = pathList.iterator();
                while (it.hasNext()) {
                    String s = it.next().getString();
                    s = Storage.filterName(s);
                    file.add(s);
                }

                // quick dup check - case sensitive, etc. - Storage does a better job
                for (int j = 0; j < i; j++) {
                    if (file.equals(mFiles.get(j))) {
                        throw new InvalidBEncodingException(
                                "Duplicate file path " + DataHelper.toString(file));
                    }
                }

                mFiles.add(Collections.unmodifiableList(file));

                // BEP 47
                val = desc.get("attr");
                if (val != null) {
                    String s = val.getString();
                    if (mAttributes == null) {
                        mAttributes = new ArrayList<>(size);
                        for (int j = 0; j < i; j++) {
                            mAttributes.add("");
                        }
                    }
                    mAttributes.add(s);
                } else {
                    if (mAttributes != null) {
                        mAttributes.add("");
                    }
                }
            }
            files = Collections.unmodifiableList(mFiles);
            lengths = Collections.unmodifiableList(mLengths);
            length = l;
            attributes = mAttributes;
        }

        info_hash = calculateInfoHash();
    }

    /**
     * Efficiently returns the name and the 20 byte SHA1 hash of the info dictionary in a torrent
     * file. Caller must close the stream.
     *
     * @param in the stream containing the bencoded torrent data
     * @param infoHashOut 20-byte array that receives the info hash
     * @return the name field from the info dictionary
     * @throws IOException if the stream cannot be read or the data is malformed
     * @since 0.8.5
     */
    public static String getNameAndInfoHash(InputStream in, byte[] infoHashOut) throws IOException {
        BDecoder bd = new BDecoder(in);
        Map<String, BEValue> m = bd.bdecodeMap().getMap();
        BEValue ibev = m.get("info");
        if (ibev == null) {
            throw new InvalidBEncodingException("Missing info map");
        }
        Map<String, BEValue> i = ibev.getMap();
        BEValue rvbev = i.get("name");
        if (rvbev == null) {
            throw new InvalidBEncodingException("Missing name");
        }
        byte[] h = bd.get_special_map_digest();
        System.arraycopy(h, 0, infoHashOut, 0, 20);
        return rvbev.getString();
    }

    /**
     * Returns the string representing the URL of the tracker for this torrent.
     *
     * @return may be null!
     */
    public String getAnnounce() {
        return announce;
    }

    /**
     * Returns a list of lists of urls.
     *
     * @return tiered announce URLs per BEP 12, or null if none were specified
     * @since 0.9.5
     */
    public List<List<String>> getAnnounceList() {
        return announce_list;
    }

    /**
     * Returns the web seed URLs for BEP 19 HTTP seeding.
     *
     * @return list of web seed URLs, or null if none were specified
     * @since 0.9.48
     */
    public List<String> getWebSeedURLs() {
        return url_list;
    }

    /** Returns the original 20 byte SHA1 hash over the bencoded info map. */
    public byte[] getInfoHash() {
        return info_hash;
    } // XXX - Should we return a clone, just to be sure?

    /**
     * Returns the piece hashes.
     *
     * @return not a copy, do not modify
     * @since public since 0.9.53, was package private
     */
    public byte[] getPieceHashes() {
        return piece_hashes;
    }

    /**
     * Returns the requested name for the file or toplevel directory. If it is a toplevel directory
     * name getFiles() will return a non-null List of file name hierarchy name.
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Is it a private torrent?
     *
     * @return whether private
     * @since 0.9
     */
    public boolean isPrivate() {
        return privateTorrent > 0;
    }

    /**
     * The private tracker flag: 0 (default), 1 (set to 1), -1 (set to 0).
     *
     * @return 0 (default), 1 (set to 1), -1 (set to 0)
     * @since 0.9.62
     */
    public int getPrivateTrackerStatus() {
        return privateTorrent;
    }

    /**
     * Returns a list of lists of file name hierarchies or null if it is a single name. It has the
     * same size as the list returned by getLengths().
     * @return the files
     */
    public List<List<String>> getFiles() {
        return files;
    }

    /**
     * Is this file a padding file?
     *
     * @param filenum the index of the file in the files list
     * @return true if the file has the padding attribute set
     * @since 0.9.48
     */
    public boolean isPaddingFile(int filenum) {
        if (attributes == null || filenum < 0 || filenum >= attributes.size()) {
            return false;
        }
        return attributes.get(filenum).indexOf('p') >= 0;
    }

    /**
     * True if every byte in the given range lies within BEP 47 padding files.
     *
     * <p>Padding bytes are all zeros and are never stored on disk, so chunks or pieces
     * entirely within padding need not be requested from peers (BEP 47).
     *
     * @param offset global byte offset into the torrent
     * @param length number of bytes
     * @return true if the whole range is covered by padding files
     * @since 0.9.71+
     */
    public boolean isRangePadding(long offset, int length) {
        if (files == null || length <= 0) {
            return false;
        }
        long[] ends = fileEnds();
        long end = offset + length;
        int idx = fileIndex(offset);
        while (offset < end) {
            if (idx >= files.size()) {
                return false;
            }
            if (!isPaddingFile(idx)) {
                return false;
            }
            offset = ends[idx];
            idx++;
        }
        return true;
    }

    /**
     * Index of the first file whose cumulative exclusive end offset is above the given position,
     * mapping a torrent byte offset to the file containing it. A position exactly on a file
     * boundary maps to the following file. Single-file torrents always return 0.
     *
     * @param pos torrent byte offset
     * @return file index
     */
    int fileIndex(long pos) {
        long[] ends = fileEnds();
        if (ends == null) {
            return 0;
        }
        int lo = 0;
        int hi = ends.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ends[mid] <= pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Cumulative exclusive end offset of each file; single-file torrents return null. */
    long[] fileEnds() {
        if (lengths == null) {
            return null;
        }
        long[] ends = _fileEnds;
        if (ends == null) {
            long[] computed = new long[lengths.size()];
            long sum = 0;
            for (int i = 0; i < computed.length; i++) {
                sum += lengths.get(i).longValue();
                computed[i] = sum;
            }
            _fileEnds = ends = computed;
        }
        return ends;
    }

    /**
     * Returns a list of Longs indication the size of the individual files, or null if it is a
     * single file. It has the same size as the list returned by getFiles().
     * @return the lengths
     */
    public List<Long> getLengths() {
        return lengths;
    }

    /**
     * The comment string or null. Not available for locally-created torrents.
     *
     * @return the comment
     * @since 0.9.7
     */
    public String getComment() {
        return this.comment;
    }

    /**
     * The created-by string or null. Not available for locally-created torrents.
     *
     * @return the created by
     * @since 0.9.7
     */
    public String getCreatedBy() {
        return this.created_by;
    }

    /**
     * The creation date (ms) or zero. As of 0.9.19, available for locally-created torrents.
     *
     * @return the creation date
     * @since 0.9.7
     */
    public long getCreationDate() {
        return this.creation_date;
    }

    /**
     * Returns the number of pieces in the torrent.
     *
     * @return the piece count computed from the hash array length divided by 20
     */
    public int getPieces() {
        return piece_hashes.length / 20;
    }

    /**
     * Return the length of a piece. All pieces are of equal length except for the last one (<code>
     * getPieces()-1</code>).
     *
     * @throws IndexOutOfBoundsException when piece is equal to or greater then the number of pieces
     *     in the torrent.
     * @return the piece length
     */
    public int getPieceLength(int piece) {
        int pieces = getPieces();
        if (piece >= 0 && piece < pieces - 1) {
            return piece_length;
        } else if (piece == pieces - 1) {
            return (int) (length - ((long) piece * piece_length));
        } else {
            throw new IndexOutOfBoundsException("No piece: " + piece);
        }
    }

    /**
     * Checks that the given piece has the same SHA1 hash as the given byte array. Returns random
     * results or IndexOutOfBoundsExceptions when the piece number is unknown.
     *
     * @param piece the piece index
     * @param bs the byte array containing the piece data
     * @param off the offset within the byte array where the piece data starts
     * @param length the number of bytes in the piece
     * @return true if the computed SHA1 hash matches the stored hash for that piece
     * @throws IndexOutOfBoundsException if the piece index is out of range
     */
    public boolean checkPiece(int piece, byte[] bs, int off, int length) {
        return fast_checkPiece(piece, bs, off, length);
    }

    /**
     * Fast path for piece hash verification. Computes SHA1 of the given data
     * and compares it against the stored piece hash.
     *
     * @param piece the piece index
     * @param bs the byte array containing the piece data
     * @param off the offset within the byte array where the piece data starts
     * @param length the number of bytes in the piece
     * @return true if the computed hash matches the stored hash
     */
    private boolean fast_checkPiece(int piece, byte[] bs, int off, int length) {
        MessageDigest sha1 = SHA1.getInstance();

        sha1.update(bs, off, length);
        byte[] hash = sha1.digest();
        for (int i = 0; i < 20; i++) {
            if (hash[i] != piece_hashes[20 * piece + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks a piece against the stored hashes using a PartialPiece.
     *
     * @param pp the PartialPiece containing the piece data and hash
     * @return true if the hash embedded in the PartialPiece matches the stored hash for that piece
     * @since 0.9.1
     */
    boolean checkPiece(PartialPiece pp) {
        int piece = pp.getPiece();
        byte[] hash;
        try {
            hash = pp.getHash();
        } catch (IOException ioe) {
            // Could be caused by closing a peer connnection
            // we don't want the exception to propagate through
            // to Storage.putPiece()
            if (_log.shouldDebug()) {
                _log.debug("Error checking piece [" + piece + "] for " + name);
            }
            return false;
        }
        for (int i = 0; i < 20; i++) {
            if (hash[i] != piece_hashes[20 * piece + i]) {
                return false;
            }
        }
        return true;
    }

    /** Returns the total length of the torrent in bytes. This includes any padding files. */
    public long getTotalLength() {
        return length;
    }

    /**
     * Returns the total length of the non-padding files in this torrent, or
     * {@link #getTotalLength()} if there are no padding files. The projected downloaded size.
     *
     * @return the length of all non-padding files
     */
    public long getDataLength() {
        if (files == null) {
            return length;
        }
        List<Long> lengths = getLengths();
        long data = 0;
        for (int i = 0; i < lengths.size(); i++) {
            if (!isPaddingFile(i)) {
                data += lengths.get(i).longValue();
            }
        }
        return data;
    }

    /**
     * Returns a human-readable summary of this torrent's metadata.
     *
     * @return string containing name, infohash, announce URL, size, file count, and piece count
     */
    @Override
    public String toString() {
        return "MetaInfo for: "
                + name
                + "\n* InfoHash: "
                + I2PSnarkUtil.toHex(info_hash)
                + "\n* Announce: "
                + announce
                + "\n* Size: "
                + length
                + " bytes, "
                + "Files: "
                + files
                + ", "
                + "Pieces: "
                + piece_hashes.length / 20
                + ", "
                + "Piece Length: "
                + piece_length
                + " bytes";
    }

    /**
     * Creates a copy of this MetaInfo that shares everything except the announce URL. Drops any
     * announce-list. Preserves infohash and info map, including any non-standard fields.
     *
     * @param announce may be null
     */
    public MetaInfo reannounce(String announce) throws InvalidBEncodingException {
        Map<String, BEValue> m = new HashMap<>();
        if (announce != null) {
            m.put("announce", new BEValue(DataHelper.getUTF8(announce)));
        }
        Map<String, BEValue> info = createInfoMap();
        m.put("info", new BEValue(info));
        return new MetaInfo(m);
    }

    /**
     * Returns the bencoded torrent file data. Used by the servlet when saving
     * a torrent generated from local data.
     *
     * @return the bencoded byte array representing the complete torrent file
     */
    public synchronized byte[] getTorrentData() {
        Map<String, Object> m = new HashMap<>();
        if (announce != null) {
            m.put("announce", announce);
        }
        if (announce_list != null) {
            m.put("announce-list", announce_list);
        }
        // misc. optional  top-level stuff
        if (url_list != null) {
            m.put("url-list", url_list);
        }
        if (comment != null) {
            m.put("comment", comment);
        }
        if (created_by != null) {
            m.put("created by", created_by);
        }
        if (creation_date != 0) {
            m.put("creation date", creation_date / 1000);
        }

        Map<String, BEValue> info = createInfoMap();
        m.put("info", info);
        // don't save this locally, we should only do this once
        return BEncoder.bencode(m);
    }

    /**
     * Side effect: Caches infoBytesLength.
     *
     * @return the info bytes
     * @since 0.8.4
     */
    public synchronized byte[] getInfoBytes() {
        if (infoMap == null) {
            createInfoMap();
        }
        byte[] rv = BEncoder.bencode(infoMap);
        infoBytesLength = rv.length;
        return rv;
    }

    /**
     * The size of getInfoBytes(). Cached.
     *
     * @return the info bytes length
     * @since 0.9.48
     */
    public synchronized int getInfoBytesLength() {
        if (infoBytesLength > 0) {
            return infoBytesLength;
        }
        return getInfoBytes().length;
    }

    /**
     * An unmodifiable view of the info map.
     *
     * @return an unmodifiable view of the Map
     */
    private Map<String, BEValue> createInfoMap() {
        // If we loaded this metainfo from a file, we have the map, and we must use it
        // or else we will lose any non-standard keys and corrupt the infohash.
        if (infoMap != null) {
            return Collections.unmodifiableMap(infoMap);
        }
        // we should only get here if serving a magnet on a torrent we created
        // or on edit torrent save
        if (_log.shouldDebug()) {
            _log.debug("Creating new infomap", new Exception());
        }
        // otherwise we must create it
        Map<String, BEValue> info = new HashMap<>();
        info.put("name", new BEValue(DataHelper.getUTF8(name)));
        if (privateTorrent != 0) {
            info.put("private", new BEValue(Integer.valueOf(privateTorrent > 0 ? 1 : 0)));
        }

        info.put("piece length", new BEValue(Integer.valueOf(piece_length)));
        info.put("pieces", new BEValue(piece_hashes));
        if (files == null) {
            info.put("length", new BEValue(Long.valueOf(length)));
        } else {
            List<BEValue> l = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                Map<String, BEValue> file = new HashMap<>();
                List<String> fi = files.get(i);
                List<BEValue> befiles = new ArrayList<>(fi.size());
                for (int j = 0; j < fi.size(); j++) {
                    befiles.add(new BEValue(DataHelper.getUTF8(fi.get(j))));
                }
                file.put("path", new BEValue(befiles));
                file.put("length", new BEValue(lengths.get(i)));
                String attr = null;
                if (attributes != null && i < attributes.size()) {
                    attr = attributes.get(i);
                    if (!attr.isEmpty()) {
                        file.put("attr", new BEValue(DataHelper.getASCII(attr)));
                    }
                }
                l.add(new BEValue(file));
            }
            info.put("files", new BEValue(l));
        }


        infoMap = info;
        return Collections.unmodifiableMap(infoMap);
    }

    /**
     * Computes the 20-byte SHA1 hash over the bencoded info dictionary.
     * Creates the info map if it has not been built yet.
     *
     * @return the 20-byte info hash used as the torrent identifier
     */
    private byte[] calculateInfoHash() {
        Map<String, BEValue> info = createInfoMap();
        if (_log.shouldDebug()) {
            StringBuilder buf = new StringBuilder(128);
            buf.append("info: ");
            for (Map.Entry<String, BEValue> entry : info.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                buf.append(key).append('=');
                buf.append(val.toString());
            }
            _log.debug(buf.toString());
        }
        byte[] infoBytes = BEncoder.bencode(info);
        MessageDigest digest = SHA1.getInstance();
        byte[] hash = digest.digest(infoBytes);
        if (_log.shouldDebug()) {
            _log.debug("[InfoHash " + I2PSnarkUtil.toHex(hash) + "]");
        }
        return hash;
    }

    /**
     * Command-line utility to inspect and optionally modify torrent file metadata.
     * Usage: MetaInfo [-a announceURL] [-c created-by] [-m comment] [-w webseed-url]* file.torrent [file2.torrent...]
     *
     * @param args command-line arguments
     * @since 0.8.5
     */
    public static void main(String[] args) {
        boolean error = false;
        String createdBy = null;
        String announce = null;
        List<String> urlList = null;
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
                        createdBy = g.getOptarg();
                        break;

                    case 'm':
                        comment = g.getOptarg();
                        break;

                    case 'w':
                        if (urlList == null) urlList = new ArrayList<>();
                        urlList.add(g.getOptarg());
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
        if (error || args.length - g.getOptind() <= 0) {
            System.err.println(
                    "Usage: MetaInfo [-a announceURL] [-c created-by] [-m comment] [-w"
                        + " webseed-url]* file.torrent [file2.torrent...]");
            System.exit(1);
        }
        for (int i = g.getOptind(); i < args.length; i++) {
            try (InputStream in = new FileInputStream(args[i])) {
                MetaInfo meta = new MetaInfo(in);
                System.out.println(
                        args[i]
                                + "\nInfoHash:     "
                                + I2PSnarkUtil.toHex(meta.getInfoHash())
                                + "\nAnnounce:     "
                                + meta.getAnnounce()
                                + "\nWebSeed URLs: "
                                + meta.getWebSeedURLs()
                                + "\nCreated By:   "
                                + meta.getCreatedBy()
                                + "\nComment:      "
                                + meta.getComment());

                if (createdBy != null || announce != null || urlList != null || comment != null) {
                    String cb = createdBy != null ? createdBy : meta.getCreatedBy();
                    String an = announce != null ? announce : meta.getAnnounce();
                    String cm = comment != null ? comment : meta.getComment();
                    List<String> urls = urlList != null ? urlList : meta.getWebSeedURLs();
                    MetaInfo meta2 = new MetaInfo(meta, an, meta.getAnnounceList(), cm, cb, urls);
                    File from = new File(args[i]);
                    File to = new File(args[i] + ".bak");
                    if (FileUtil.copy(from, to, true, false)) {
                        try (OutputStream out = new FileOutputStream(from)) {
                            out.write(meta2.getTorrentData());
                        }
                        System.out.println("Modified " + from + " and backed up old file to " + to);
                        System.out.println(
                                args[i]
                                        + "\nInfoHash:     "
                                        + I2PSnarkUtil.toHex(meta2.getInfoHash())
                                        + "\nAnnounce:     "
                                        + meta2.getAnnounce()
                                        + "\nWebSeed URLs: "
                                        + meta2.getWebSeedURLs()
                                        + "\nCreated By:   "
                                        + meta2.getCreatedBy()
                                        + "\nComment:      "
                                        + meta2.getComment());
                    } else {
                        System.err.println("Failed backup of " + from + " to " + to);
                    }
                }
            } catch (IOException ioe) {
                System.err.println("Error in file " + args[i] + ": " + ioe);
            }
        }
    }
}
