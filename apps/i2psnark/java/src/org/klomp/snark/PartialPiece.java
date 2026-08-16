package org.klomp.snark;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Objects;
import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;

/**
 * Represents a piece of a torrent being downloaded, storing partial data either on the heap or in a
 * temporary file if the piece is large.
 *
 * <p>Handles out-of-order chunk reception and manages a BitField tracking received chunks.
 * Implements Comparable for prioritization by piece number and completeness.
 *
 * <p>Usage Notes: - Not thread-safe except for synchronized public methods. - Objects for the same
 * piece should not be shared between peers. - Used extensively by PeerCoordinator and PeerState for
 * request management.
 *
 * @since 0.8.2
 */
class PartialPiece implements Comparable<PartialPiece> {

    private final Piece piece;
    private final byte[] bs; // in-memory storage if piece is small
    private final int pclen; // piece length in bytes
    private final File tempDir;
    /** BEP 47: to recognize padding-only chunks and skip requesting them; may be null */
    private final MetaInfo _meta;
    /** BEP 47: per-piece padding sub-block mask, lazily computed; null until first use */
    private volatile BitField _paddingMask;

    private File tempfile;
    private RandomAccessFile raf;

    // BitField tracking which sub-blocks have been downloaded
    private final BitField bitfield;

    private int off; // offset of next expected chunk start

    private static final int BUFSIZE = PeerState.PARTSIZE; // size of chunk parts
    private static final ByteCache _cache = ByteCache.getInstance(64, BUFSIZE);

    /**
     * BEP 47: sub-block granularity for requests that straddle padding files. A request is a run
     * of consecutive real sub-blocks capped at PARTSIZE, so at most SUB_PARTSIZE bytes of padding
     * are ever requested per boundary. A power of two, so it is the same class of block size as
     * the short tail block every client accepts.
     *
     * <p>Next logical step: request the exact real sub-range of a straddling sub-block instead of
     * the whole sub-block, eliminating the remaining pad waste entirely. Risk: arbitrary (non
     * power-of-two) block sizes are rejected by a few strict peers; reward: up to ~2K average pad
     * bytes per boundary, tens of MB on pad-heavy torrents. Would require a per-peer fallback to
     * sub-block requests.
     */
    private static final int SUB_PARTSIZE = PeerState.PARTSIZE / 4;

    // Threshold for using in-memory storage vs temp file; can be dynamically reduced on OOM
    private static final int MAX_IN_MEM = 1024 * 1024;
    private static int maxInMem = MAX_IN_MEM;

    /**
     * A PartialPiece for the given piece with the specified length. If piece length exceeds
     * threshold or memory is constrained, stores on disk.
     *
     * @param piece The Piece identifier object
     * @param len Length of this piece in bytes (must match piece length)
     * @param tempDir Directory for temporary file storage if needed
     */
    public PartialPiece(Piece piece, int len, File tempDir) {
        this(piece, len, tempDir, null);
    }

    /**
     * A PartialPiece for the given piece with the specified length. If piece length exceeds
     * threshold or memory is constrained, stores on disk.
     *
     * @param piece The Piece identifier object
     * @param len Length of this piece in bytes (must match piece length)
     * @param tempDir Directory for temporary file storage if needed
     * @param meta the torrent meta info, for skipping BEP 47 padding-only chunks, or null
     * @since 0.9.71+
     */
    public PartialPiece(Piece piece, int len, File tempDir, MetaInfo meta) {
        this.piece = Objects.requireNonNull(piece);
        this.pclen = len;
        this.tempDir = tempDir;
        this._meta = meta;
        this.bitfield = new BitField((len + SUB_PARTSIZE - 1) / SUB_PARTSIZE);

        byte[] tempBs = null;
        try {
            if (len <= MAX_IN_MEM) {
                try {
                    tempBs = new byte[len];
                } catch (OutOfMemoryError oom) {
                    if (maxInMem > PeerState.PARTSIZE) maxInMem /= 2;
                    Log log = I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class);
                    log.logAlways(Log.WARN, "OOM creating new partial piece -> RAM cache reduced to " +
                                              maxInMem / 1024 + "KB");
                    // fall through to use temp file
                }
            }
            // else use temp file (tempBs stays null)
        } finally {
            // Final single assignment
            this.bs = tempBs;
        }
    }

    /**
     * Creates the temporary file and opens a RandomAccessFile. Synchronized for safe concurrent
     * use.
     *
     * @throws IOException if file creation or opening fails
     * @since 0.9.1
     */
    private synchronized void createTemp() throws IOException {
        tempfile = SecureFile.createTempFile("piece_" + piece.getId() + '_', null, tempDir);
        raf = new RandomAccessFile(tempfile, "rw");
    }

    /**
     * Creates a Request object for the next missing chunk in this piece. Returns null if the entire
     * piece is complete. Chunks lying entirely within BEP 47 padding files are marked as received
     * (their data is all zeros) and never requested.
     *
     * @return the next Request for downloading or null if complete
     * @since 0.9.1
     */
    public synchronized Request getRequest() {
        return getRequest(0);
    }

    /**
     * Creates a Request object for the next missing chunk at or after the given offset. Chunks
     * lying entirely within BEP 47 padding files are marked as received (their data is all zeros)
     * and never requested. A request is a run of consecutive real sub-blocks capped at PARTSIZE,
     * so a chunk straddling a padding file is fetched at sub-block granularity, wasting at most
     * SUB_PARTSIZE bytes of padding.
     *
     * @param minOffset byte offset to start scanning at; the caller advances it past the last
     *        request so in-flight chunks are not re-requested
     * @return the next Request for downloading or null if complete
     */
    public synchronized Request getRequest(int minOffset) {
        int sz = bitfield.size();
        int s = (minOffset + SUB_PARTSIZE - 1) / SUB_PARTSIZE;
        for (; s < sz; s++) {
            if (bitfield.get(s)) {
                continue;
            }
            if (isPaddingSubBlock(s)) {
                markSubBlock(s);
                continue;
            }
            int offset = s * SUB_PARTSIZE;
            int len = SUB_PARTSIZE;
            int s2 = s + 1;
            // merge consecutive real sub-blocks up to a full PARTSIZE request
            while (len < PeerState.PARTSIZE
                    && s2 < sz
                    && !bitfield.get(s2)
                    && !isPaddingSubBlock(s2)) {
                len += SUB_PARTSIZE;
                s2++;
            }
            return new Request(this, offset, Math.min(len, pclen - offset));
        }
        return null;
    }

    /**
     * Marks a sub-block as received without any data, for BEP 47 padding-only sub-blocks whose
     * bytes are all zeros. The in-memory buffer stays zero-filled, so the piece hashes correctly
     * once the remaining sub-blocks arrive. Does not advance the sequential offset; the caller's
     * scan does that.
     *
     * @param subBlock zero-based sub-block index
     * @since 0.9.71+
     */
    public synchronized void markSubBlock(int subBlock) {
        piece.setActive();
        if (!bitfield.get(subBlock)) {
            bitfield.set(subBlock);
        }
    }

    /**
     * True if the sub-block lies entirely within BEP 47 padding files. The padding layout of a
     * piece never changes, so the sub-block mask is computed once on first use.
     *
     * @param subBlock zero-based sub-block index
     */
    boolean isPaddingSubBlock(int subBlock) {
        if (_meta == null) {
            return false;
        }
        BitField mask = _paddingMask;
        if (mask == null) {
            mask = new BitField(bitfield.size());
            // every piece except the last is full length, so the piece start is id * full piece length
            long pieceStart = (long) piece.getId() * _meta.getPieceLength(0);
            int sz = bitfield.size();
            for (int i = 0; i < sz; i++) {
                int offset = i * SUB_PARTSIZE;
                int len = Math.min(pclen - offset, SUB_PARTSIZE);
                if (_meta.isRangePadding(pieceStart + offset, len)) {
                    mask.set(i);
                }
            }
            _paddingMask = mask;
        }
        return mask.get(subBlock);
    }

    /**
     * Returns the piece number.
     *
     * @return piece number identifier
     */
    public int getPiece() {
        return piece.getId();
    }

    /**
     * Returns the length of the piece in bytes.
     *
     * @return piece length
     * @since 0.9.1
     */
    public int getLength() {
        return pclen;
    }

    /**
     * Checks if the entire piece has been downloaded.
     *
     * @return true if complete, else false
     * @since 0.9.62
     */
    public synchronized boolean isComplete() {
        return bitfield.complete();
    }

    /**
     * Checks if any chunks of this piece have been downloaded.
     *
     * @return true if any data present, false if none
     * @since 0.9.63
     */
    public synchronized boolean hasData() {
        return bitfield.count() > 0;
    }

    /**
     * Checks if the given sub-block index has been downloaded.
     *
     * @param subBlock zero-based sub-block index
     * @return true if sub-block downloaded, false otherwise
     * @since 0.9.63
     */
    public synchronized boolean hasSubBlock(int subBlock) {
        return bitfield.get(subBlock);
    }

    /**
     * Returns the total number of bytes downloaded in this piece, accurately accounting for holes.
     *
     * @return number of bytes downloaded
     * @since 0.9.63
     */
    public synchronized int getDownloaded() {
        if (bitfield.complete()) return pclen;

        int count = bitfield.count();
        int downloaded = count * SUB_PARTSIZE;
        int remainder = pclen % SUB_PARTSIZE;
        if (remainder != 0 && bitfield.get(count - 1)) {
            downloaded -= SUB_PARTSIZE - remainder;
        }
        return downloaded;
    }

    /**
     * Computes and returns the SHA1 hash of the complete piece data. Caller must ensure piece
     * completeness and synchronize before calling.
     *
     * @return the hash
     * @throws IOException if data is incomplete or read fails
     * @since 0.9.1
     */
    public byte[] getHash() throws IOException {
        MessageDigest sha1 = SHA1.getInstance();
        if (bs != null) {
            sha1.update(bs);
        } else {
            int read = 0;
            int buflen = Math.min(pclen, BUFSIZE);
            ByteArray ba = (buflen == BUFSIZE) ? _cache.acquire() : null;
            try {
                byte[] buf = (ba != null) ? ba.getData() : new byte[buflen];
                synchronized (this) {
                    if (raf == null) throw new IOException("File not created");
                    raf.seek(0);
                    while (read < pclen) {
                        int toRead = Math.min(buf.length, pclen - read);
                        raf.readFully(buf, 0, toRead);
                        read += toRead;
                        sha1.update(buf, 0, toRead);
                    }
                }
                if (read < pclen) throw new IOException("Incomplete piece data");
            } finally {
                if (ba != null) _cache.release(ba, false);
            }
        }
        return sha1.digest();
    }

    /**
     * Reads data from the input stream into this piece starting at offset. Marks chunks as
     * downloaded in the bitfield. Handles out-of-order chunks by logging warnings.
     *
     * @param din input stream to read from
     * @param offset offset to read into the piece (must be multiple of chunk size)
     * @param len length of data to read
     * @param bwl bandwidth listener to report downloaded bytes
     * @throws IOException on I/O errors or incorrect offset
     * @since 0.9.1
     */
    public void read(DataInputStream din, int offset, int len, BandwidthListener bwl)
            throws IOException {
        if (offset % SUB_PARTSIZE != 0) throw new IOException("Bad offset " + offset);
        int subBlock = offset / SUB_PARTSIZE;

        if (bs != null) {
            // Read directly into the memory buffer
            int bytesRead = 0;
            while (bytesRead < len) {
                int n = din.read(bs, offset + bytesRead, len - bytesRead);
                if (n < 0) throw new EOFException();
                bytesRead += n;
                bwl.downloaded(n);
            }
            synchronized (this) {
                handleChunkReception(subBlock, offset, len);
            }
        } else {
            // Use temporary file
            ByteArray ba = null;
            try {
                byte[] tmp;
                if (len == BUFSIZE) {
                    ba = _cache.acquire();
                    tmp = ba.getData();
                } else {
                    tmp = new byte[len];
                }
                int bytesRead = 0;
                while (bytesRead < len) {
                    int n = din.read(tmp, bytesRead, len - bytesRead);
                    if (n < 0) throw new EOFException();
                    bytesRead += n;
                    bwl.downloaded(n);
                }
                synchronized (this) {
                    if (raf == null) createTemp();
                    raf.seek(offset);
                    raf.write(tmp);
                    handleChunkReception(subBlock, offset, len);
                }
            } finally {
                if (ba != null) {
                    _cache.release(ba, false);
                }
            }
        }
    }

    /**
     * Handles updating the bitfield and offset when a chunk is received. Logs warnings if
     * out-of-order chunks or holes are detected. Caller must synchronize before calling.
     *
     * @param subBlock first sub-block index received (may span several)
     * @param offset byte offset in the piece corresponding to the sub-block
     * @param len length of data in bytes
     */
    private void handleChunkReception(int subBlock, int offset, int len) {
        piece.setActive();
        int end = (offset + len + SUB_PARTSIZE - 1) / SUB_PARTSIZE;
        for (int i = subBlock; i < end; i++) {
            if (bitfield.get(i)) {
                info("Already have sub-block " + i + " on " + this);
            } else {
                bitfield.set(i);
            }
        }
        if (this.off == offset) {
            this.off += len;
            // Advance offset if holes filled
            int sz = bitfield.size();
            for (int i = subBlock + 1; i < sz; i++) {
                if (!bitfield.get(i)) break;
                info("Hole filled in before sub-block " + i + " on " + this + ' ' + bitfield);
                if (i == sz - 1) off = pclen;
                else off += SUB_PARTSIZE;
            }
        } else {
            info("Out of order chunk " + subBlock + " on " + this + ' ' + bitfield);
        }
    }

    /**
     * Writes piece data from the given offset and length to the output. Caller must synchronize on
     * the output stream and piece for thread safety.
     *
     * @param out destination output to write data to
     * @param offset offset in this piece to start writing from
     * @param len length of data to write
     * @throws IOException on I/O failure
     * @since 0.9.1
     */
    public void write(DataOutput out, int offset, int len) throws IOException {
        if (bs != null) {
            out.write(bs, offset, len);
        } else {
            int read = 0;
            int buflen = Math.min(len, BUFSIZE);
            ByteArray ba = (buflen == BUFSIZE) ? _cache.acquire() : null;
            try {
                byte[] buf = (ba != null) ? ba.getData() : new byte[buflen];
                synchronized (this) {
                    if (raf == null) throw new IOException("Piece data not available");
                    raf.seek(offset);
                    while (read < len) {
                        int rd = Math.min(buf.length, len - read);
                        raf.readFully(buf, 0, rd);
                        out.write(buf, 0, rd);
                        read += rd;
                    }
                }
            } finally {
                if (ba != null) _cache.release(ba, false);
            }
        }
    }

    /**
     * Releases temporary file and resources associated with this piece. Safe to call multiple
     * times.
     *
     * @since 0.9.1
     */
    public void release() {
        if (bs == null) {
            synchronized (this) {
                if (raf != null) {
                    try {
                        raf.close();
                    } catch (IOException ignored) { /* ignored */ }
                    tempfile.delete();
                    raf = null;
                }
            }
        }
    }

    /**
     * Compares this piece with another by piece number, highest priority first, then by highest
     * downloaded first.
     *
     * @param other the other PartialPiece to compare to
     * @return negative if this piece has higher priority, positive if lower, zero if equal
     */
    @Override
    public int compareTo(PartialPiece other) {
        int diff = this.piece.compareTo(other.piece);
        if (diff != 0) return diff;
        // Reverse order by downloaded bytes (more downloaded prioritized)
        return Integer.compare(other.getDownloaded(), this.getDownloaded());
    }

    @Override
    public int hashCode() {
        return 7777 * piece.getId();
    }

    /**
     * Equality based on piece ID only.
     *
     * @param o Object to compare
     * @return true if representing the same piece ID, else false
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PartialPiece)) return false;
        PartialPiece other = (PartialPiece) o;
        return this.piece.getId() == other.piece.getId();
    }

    @Override
    public String toString() {
        return "Partial(" + piece.getId() + ',' + off + ',' + getDownloaded() + ',' + pclen + ')';
    }

    /**
     * Logs a warning message for this class.
     *
     * @param s warning message string
     * @since 0.9.62
     */
    public static void warn(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class).warn(s);
    }

    /**
     * Logs an info message for this class.
     *
     * @param s info message string
     * @since 0.9.68+
     */
    public static void info(String s) {
        I2PAppContext.getGlobalContext().logManager().getLog(PartialPiece.class).info(s);
    }
}
