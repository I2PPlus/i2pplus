package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for Storage.check(savedTime, savedBitField), the fast-resume path.
 *
 * <p>Contract under test: on startup, the saved per-torrent bitfield and timestamp are trusted
 * only for files that are unchanged (mtime &lt;= savedTime and length matching). Files that were
 * modified after the last status save (e.g. a crash mid-download, or a user edit) must be
 * re-hashed piece by piece, while unchanged files keep their verified pieces without re-hashing.
 * Only pieces that intersect a changed file may be re-verified.
 *
 * <p>Tests 3-6 pin the per-file granularity behavior and FAIL against the current
 * all-or-nothing implementation (any changed file triggers a full recheck of every piece).
 *
 * @since 0.1.0
 */
public class StorageTest {

    private static final int PIECE_LENGTH = 4096;

    private File _baseDir;
    private File _dataDir;

    @Before
    public void setUp() throws Exception {
        _baseDir = createTempDir("i2psnark-storage-test");
        _dataDir = new File(_baseDir, "data");
        assertTrue(_dataDir.mkdir());
    }

    @After
    public void tearDown() throws Exception {
        deleteTree(_baseDir);
    }

    /** A StorageListener that records every piece verification callback. */
    private static class RecordingListener implements StorageListener {
        final List<Integer> checked = new ArrayList<>();
        final List<Boolean> results = new ArrayList<>();

        @Override
        public void storageCreateFile(Storage storage, String name, long length) {}

        @Override
        public void storageAllocated(Storage storage, long length) {}

        @Override
        public void storageChecked(Storage storage, int num, boolean checked) {
            this.checked.add(Integer.valueOf(num));
            this.results.add(Boolean.valueOf(checked));
        }

        @Override
        public void storageAllChecked(Storage storage) {}

        @Override
        public void storageCompleted(Storage storage) {}

        @Override
        public void setWantedPieces(Storage storage) {}

        @Override
        public void addMessage(String message) {}
    }

    /**
     * Writes two files "a.dat" (aSize) and "b.dat" (bSize) into dataDir with deterministic
     * content and returns a parsed multi-file MetaInfo whose piece hashes match that content.
     */
    private MetaInfo buildTwoFileTorrent(long aSize, long bSize) throws Exception {
        return buildTorrent(Arrays.asList("a.dat", "b.dat"), Arrays.asList(Long.valueOf(aSize), Long.valueOf(bSize)));
    }

    /** Writes the named files with deterministic content and returns a parsed MetaInfo. */
    private MetaInfo buildTorrent(List<String> names, List<Long> sizes) throws Exception {
        long total = 0;
        for (Long size : sizes) {
            total += size.longValue();
        }
        byte[] content = new byte[(int) total];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        long offset = 0;
        for (int i = 0; i < names.size(); i++) {
            File f = new File(_dataDir, names.get(i));
            long size = sizes.get(i).longValue();
            writeFile(f, content, (int) offset, (int) size);
            offset += size;
        }
        return new MetaInfo(new ByteArrayInputStream(buildTorrentBytes(names, sizes, computeHashes(content))));
    }

    /**
     * Writes a single file (true single-file torrent bencode, no "files" key) and returns the
     * parsed MetaInfo. The file must already exist, so check() takes the single-file branch.
     */
    private MetaInfo buildSingleFileTorrent(File file, long size) throws Exception {
        byte[] content = new byte[(int) size];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        writeFile(file, content, 0, content.length);
        byte[] hashes = computeHashes(content);
        StringBuilder sb = new StringBuilder(256);
        sb.append('d');
        sb.append("8:announce").append("19:http://tracker.test");
        sb.append("4:info").append('d');
        sb.append("6:length").append('i').append(size).append('e');
        sb.append("4:name");
        byte[] nb = file.getName().getBytes(StandardCharsets.ISO_8859_1);
        sb.append(nb.length).append(':').append(file.getName());
        sb.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
        sb.append("6:pieces").append(hashes.length).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(hashes, 0, rv, head.length, hashes.length);
        System.arraycopy(tail, 0, rv, head.length + hashes.length, tail.length);
        return new MetaInfo(new ByteArrayInputStream(rv));
    }

    private static byte[] computeHashes(byte[] content) {
        return computeHashes(content, PIECE_LENGTH);
    }

    private static byte[] computeHashes(byte[] content, int pieceLength) {
        int pieceCount = (int) ((content.length + pieceLength - 1) / pieceLength);
        byte[] hashes = new byte[20 * pieceCount];
        MessageDigest md = SHA1.getInstance();
        for (int p = 0; p < pieceCount; p++) {
            int start = p * pieceLength;
            int len = Math.min(pieceLength, content.length - start);
            md.reset();
            md.update(content, start, len);
            System.arraycopy(md.digest(), 0, hashes, 20 * p, 20);
        }
        return hashes;
    }

    /** Builds a bencoded multi-file torrent byte stream. */
    private static byte[] buildTorrentBytes(List<String> names, List<Long> sizes, byte[] pieceHashes) {
        return buildTorrentBytes(names, sizes, null, PIECE_LENGTH, pieceHashes);
    }

    /**
     * Builds a bencoded multi-file torrent byte stream with optional per-file BEP 47 attributes and
     * a custom piece length.
     *
     * @param attrs per-file attribute strings, or null for no attr keys
     * @param pieceLength the piece length in bytes
     */
    private static byte[] buildTorrentBytes(
            List<String> names, List<Long> sizes, List<String> attrs, int pieceLength, byte[] pieceHashes) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('d');
        sb.append("8:announce").append("19:http://tracker.test");
        sb.append("4:info").append('d');
        sb.append("5:files").append('l');
        for (int i = 0; i < names.size(); i++) {
            sb.append('d');
            if (attrs != null) {
                String attr = attrs.get(i);
                sb.append("4:attr").append(attr.length()).append(':').append(attr);
            }
            sb.append("6:length").append('i').append(sizes.get(i)).append('e');
            sb.append("4:path").append('l');
            byte[] nb = names.get(i).getBytes(StandardCharsets.ISO_8859_1);
            sb.append(nb.length).append(':').append(names.get(i));
            sb.append('e');
            sb.append('e');
        }
        sb.append('e');
        sb.append("4:name").append("4:data");
        sb.append("12:piece length").append('i').append(pieceLength).append('e');
        sb.append("6:pieces").append(pieceHashes.length).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + pieceHashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(pieceHashes, 0, rv, head.length, pieceHashes.length);
        System.arraycopy(tail, 0, rv, head.length + pieceHashes.length, tail.length);
        return rv;
    }

    private static void writeFile(File f, byte[] content, int off, int len) throws IOException {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content, off, len);
        } finally {
            fos.close();
        }
    }

    /** Flips one byte at the given offset so the file no longer matches its piece hashes. */
    private static void corrupt(File f, long offset) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            raf.seek(offset);
            byte b = raf.readByte();
            raf.seek(offset);
            raf.writeByte(b ^ 0x55);
        } finally {
            raf.close();
        }
    }

    private Storage newStorage(MetaInfo mi, StorageListener listener) {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        return new Storage(util, _dataDir, mi, listener, true);
    }

    private static File createTempDir(String prefix) throws IOException {
        File f = File.createTempFile(prefix, "");
        assertTrue(f.delete());
        assertTrue(f.mkdir());
        return f;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                deleteTree(kid);
            }
        }
        f.delete();
    }

    // ----- current behavior, must stay green -----

    /** No saved data: every piece must be hashed and verified. */
    @Test
    public void testFullCheckWhenNoSavedData() throws Exception {
        MetaInfo mi = buildTwoFileTorrent(PIECE_LENGTH * 2, PIECE_LENGTH * 2);
        RecordingListener l = new RecordingListener();
        Storage s = newStorage(mi, l);
        s.check(0, null);
        assertTrue(s.complete());
        assertEquals(0, s.needed());
        assertEquals(Arrays.asList(0, 1, 2, 3), l.checked);
    }

    /** BEP 47: a pad-only piece is counted complete on a full recheck without hashing. */
    @Test
    public void testRecheckTrustsPadOnlyPiece() throws Exception {
        // a.dat [0, 16K) real piece, pad [16K, 32K) pad-only piece
        int pieceLength = PIECE_LENGTH;
        List<String> names = Arrays.asList("a.dat", ".pad/16384");
        List<Long> sizes = Arrays.asList(Long.valueOf(pieceLength), Long.valueOf(pieceLength));
        List<String> attrs = Arrays.asList("", "p");
        byte[] content = new byte[2 * pieceLength];
        for (int i = 0; i < pieceLength; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        // a.dat must exist so the recheck hashes; pad bytes are zeros
        writeFile(new File(_dataDir, "a.dat"), content, 0, pieceLength);
        MetaInfo mi =
                new MetaInfo(
                        new ByteArrayInputStream(
                                buildTorrentBytes(
                                        names,
                                        sizes,
                                        attrs,
                                        pieceLength,
                                        computeHashes(content, pieceLength))));
        RecordingListener l = new RecordingListener();
        Storage s = newStorage(mi, l);
        s.check(0, null);
        assertTrue(s.complete());
        assertEquals(0, s.needed());
        assertTrue(s.getBitField().get(1)); // pad-only piece trusted without hashing
    }

    /** All files unchanged (mtime <= savedTime, lengths match): no hashing at all, bitfield trusted. */
    @Test
    public void testTrustPathSkipsHashingWhenFilesUnchanged() throws Exception {
        MetaInfo mi = buildTwoFileTorrent(PIECE_LENGTH * 2, PIECE_LENGTH * 2);
        Storage s1 = newStorage(mi, new RecordingListener());
        s1.check(0, null);
        BitField full = new BitField(s1.getBitField().getFieldBytes(), mi.getPieces());
        long savedTime = System.currentTimeMillis();
        // corrupt b.dat but keep its mtime before the save time - the trust path must not detect it
        corrupt(new File(_dataDir, "b.dat"), 0);
        setMtime(new File(_dataDir, "a.dat"), savedTime - 60000);
        setMtime(new File(_dataDir, "b.dat"), savedTime - 60000);

        RecordingListener l2 = new RecordingListener();
        Storage s2 = newStorage(mi, l2);
        s2.check(savedTime, full);
        assertTrue(s2.complete());
        assertEquals(0, l2.checked.size());
    }

    /** Single-file variant of the trust path. */
    @Test
    public void testSingleFileTrustPathSkipsHashing() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        File base = new File(_dataDir, "single.dat");
        MetaInfo mi = buildSingleFileTorrent(base, PIECE_LENGTH * 2);
        RecordingListener l1 = new RecordingListener();
        Storage s1 = new Storage(util, base, mi, l1, true);
        s1.check(0, null);
        BitField full = new BitField(s1.getBitField().getFieldBytes(), mi.getPieces());
        long savedTime = System.currentTimeMillis();
        setMtime(base, savedTime - 60000);

        RecordingListener l2 = new RecordingListener();
        Storage s2 = new Storage(util, base, mi, l2, true);
        s2.check(savedTime, full);
        assertTrue(s2.complete());
        assertEquals(0, l2.checked.size());
    }

    // ----- desired per-file granularity, FAIL on the current all-or-nothing implementation -----

    /**
     * b.dat modified after the save: only b.dat's pieces (2, 3) may be re-verified; a.dat's
     * pieces (0, 1) must be trusted without re-hashing.
     */
    @Test
    public void testOnlyPiecesInChangedFileAreRechecked() throws Exception {
        MetaInfo mi = buildTwoFileTorrent(PIECE_LENGTH * 2, PIECE_LENGTH * 2);
        Storage s1 = newStorage(mi, new RecordingListener());
        s1.check(0, null);
        BitField full = new BitField(s1.getBitField().getFieldBytes(), mi.getPieces());
        long savedTime = System.currentTimeMillis();
        File b = new File(_dataDir, "b.dat");
        corrupt(b, 0);
        corrupt(b, PIECE_LENGTH);
        setMtime(b, savedTime + 60000);

        RecordingListener l2 = new RecordingListener();
        Storage s2 = newStorage(mi, l2);
        s2.check(savedTime, full);
        assertEquals(Arrays.asList(2, 3), l2.checked);
        assertEquals(Arrays.asList(Boolean.FALSE, Boolean.FALSE), l2.results);
        assertTrue(s2.getBitField().get(0));
        assertTrue(s2.getBitField().get(1));
        assertFalse(s2.getBitField().get(2));
        assertFalse(s2.getBitField().get(3));
        assertFalse(s2.complete());
    }

    /**
     * Piece 1 spans a.dat and b.dat (a.dat = 5000 bytes). Only b.dat is modified: piece 1 must be
     * re-verified (it intersects the changed file) while piece 0 stays trusted. This guards the
     * piece-to-file boundary logic.
     */
    @Test
    public void testSpanningPieceRecheckedWhenAdjacentFileChanged() throws Exception {
        MetaInfo mi = buildTwoFileTorrent(5000, 3000);
        assertEquals(2, mi.getPieces());
        Storage s1 = newStorage(mi, new RecordingListener());
        s1.check(0, null);
        BitField full = new BitField(s1.getBitField().getFieldBytes(), mi.getPieces());
        long savedTime = System.currentTimeMillis();
        corrupt(new File(_dataDir, "b.dat"), 0);
        setMtime(new File(_dataDir, "b.dat"), savedTime + 60000);

        RecordingListener l2 = new RecordingListener();
        Storage s2 = newStorage(mi, l2);
        s2.check(savedTime, full);
        assertEquals(Arrays.asList(1), l2.checked);
        assertTrue(s2.getBitField().get(0));
        assertFalse(s2.getBitField().get(1));
    }

    /**
     * Partial saved state (only a.dat verified, b.dat still missing) and b.dat modified after the
     * save: only b.dat's pieces may be re-verified; a.dat's verified pieces stay trusted.
     */
    @Test
    public void testPartialSavedStateRechecksOnlyChangedFilePieces() throws Exception {
        MetaInfo mi = buildTwoFileTorrent(PIECE_LENGTH * 2, PIECE_LENGTH * 2);
        Storage s1 = newStorage(mi, new RecordingListener());
        s1.check(0, null);
        BitField partial = new BitField(mi.getPieces());
        partial.set(0);
        partial.set(1);
        long savedTime = System.currentTimeMillis();
        File b = new File(_dataDir, "b.dat");
        corrupt(b, 0);
        corrupt(b, PIECE_LENGTH);
        setMtime(b, savedTime + 60000);

        RecordingListener l2 = new RecordingListener();
        Storage s2 = newStorage(mi, l2);
        s2.check(savedTime, partial);
        assertEquals(Arrays.asList(2, 3), l2.checked);
        assertTrue(s2.getBitField().get(0));
        assertTrue(s2.getBitField().get(1));
        assertFalse(s2.getBitField().get(2));
        assertFalse(s2.getBitField().get(3));
    }

    /**
     * b.dat has the wrong length even with an old mtime: only b.dat's pieces may be re-verified,
     * proving the length check is per-file and independent of the mtime check.
     */
    @Test
    public void testLengthMismatchRechecksOnlyThatFile() throws Exception {
        MetaInfo mi = buildTwoFileTorrent(PIECE_LENGTH * 2, PIECE_LENGTH * 2);
        Storage s1 = newStorage(mi, new RecordingListener());
        s1.check(0, null);
        BitField full = new BitField(s1.getBitField().getFieldBytes(), mi.getPieces());
        long savedTime = System.currentTimeMillis();
        File b = new File(_dataDir, "b.dat");
        RandomAccessFile raf = new RandomAccessFile(b, "rw");
        try {
            raf.setLength(PIECE_LENGTH / 2);
        } finally {
            raf.close();
        }
        setMtime(b, savedTime - 60000);

        RecordingListener l2 = new RecordingListener();
        Storage s2 = newStorage(mi, l2);
        s2.check(savedTime, full);
        assertEquals(Arrays.asList(2, 3), l2.checked);
        assertTrue(s2.getBitField().get(0));
        assertTrue(s2.getBitField().get(1));
        assertFalse(s2.getBitField().get(2));
        assertFalse(s2.getBitField().get(3));
    }

    // ----- BEP 47 padding -----

    /**
     * Deterministic content for the given size: byte i has value (i * 31 + 7).
     */
    private static byte[] content(int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        return content;
    }

    /** Creates a torrent from _dataDir and returns the resulting metainfo. */
    private MetaInfo createTorrent() throws Exception {
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Storage created =
                new Storage(
                        util,
                        _dataDir,
                        "http://tracker.test",
                        null,
                        null,
                        false,
                        null,
                        new ArrayList<>());
        return created.getMetaInfo();
    }

    /**
     * Multi-file creation with unaligned files: a .pad entry is inserted after each file except
     * the last so files end on piece boundaries, pad content hashes as zeros, the serialized
     * .torrent file round-trips losslessly, and the torrent verifies end to end.
     */
    @Test
    public void testCreationPadsUnalignedMultiFileTorrent() throws Exception {
        byte[] c = content(2000);
        writeFile(new File(_dataDir, "a.dat"), c, 0, 1000);
        writeFile(new File(_dataDir, "b.dat"), c, 1000, 1000);
        MetaInfo mi = createTorrent();
        // 64 KiB pieces (256 KiB default / 4): a.dat pads 64536 bytes, b.dat is last and stays
        assertEquals(3, mi.getFiles().size());
        assertEquals(Long.valueOf(1000), mi.getLengths().get(0));
        assertEquals(Long.valueOf(64536), mi.getLengths().get(1));
        assertEquals(Long.valueOf(1000), mi.getLengths().get(2));
        assertEquals(Arrays.asList(".pad", "64536"), mi.getFiles().get(1));
        assertFalse(mi.isPaddingFile(0));
        assertTrue(mi.isPaddingFile(1));
        assertFalse(mi.isPaddingFile(2));
        assertEquals(2, mi.getPieces());
        assertEquals(66536, mi.getTotalLength());
        // The written .torrent file (same bytes locked_writeMetaInfo persists) must re-parse
        // with identical info hash, lengths, attributes, and piece hashes. Parse renames the
        // dotfile .pad to _pad (Storage.filterName), matching the on-disk layout
        byte[] torrentData = mi.getTorrentData();
        MetaInfo reparsed = new MetaInfo(new ByteArrayInputStream(torrentData));
        assertArrayEquals(mi.getInfoHash(), reparsed.getInfoHash());
        assertEquals(Arrays.asList("a.dat"), reparsed.getFiles().get(0));
        assertEquals(Arrays.asList("_pad", "64536"), reparsed.getFiles().get(1));
        assertEquals(Arrays.asList("b.dat"), reparsed.getFiles().get(2));
        assertEquals(mi.getLengths(), reparsed.getLengths());
        assertEquals(mi.getPieces(), reparsed.getPieces());
        assertEquals(mi.getTotalLength(), reparsed.getTotalLength());
        assertTrue(reparsed.isPaddingFile(1));
        assertFalse(reparsed.isPaddingFile(0));
        assertFalse(reparsed.isPaddingFile(2));
        // A fresh runtime open of the same dir must verify every piece: the pad hashes as zeros
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        RecordingListener l = new RecordingListener();
        Storage s = new Storage(util, _dataDir, reparsed, l, true);
        s.check(0, null);
        assertTrue(s.complete());
        assertEquals(0, s.needed());
        // Padding is never materialized on disk
        assertFalse(new File(new File(_dataDir, "_pad"), "64536").exists());
        assertFalse(new File(new File(_dataDir, ".pad"), "64536").exists());
    }

    /** Creation with already-aligned files adds no pads and no attributes. */
    @Test
    public void testCreationSkipsPadsForAlignedFiles() throws Exception {
        byte[] c = content(2 * 65536);
        writeFile(new File(_dataDir, "a.dat"), c, 0, 65536);
        writeFile(new File(_dataDir, "b.dat"), c, 65536, 65536);
        MetaInfo mi = createTorrent();
        assertEquals(2, mi.getFiles().size());
        assertFalse(mi.isPaddingFile(0));
        assertFalse(mi.isPaddingFile(1));
        assertEquals(131072, mi.getTotalLength());
        assertEquals(2, mi.getPieces());
        // Round-trip: no pad entries or attributes may appear for aligned files
        MetaInfo reparsed = new MetaInfo(new ByteArrayInputStream(mi.getTorrentData()));
        assertEquals(mi.getFiles(), reparsed.getFiles());
        assertArrayEquals(mi.getInfoHash(), reparsed.getInfoHash());
        assertFalse(reparsed.isPaddingFile(0));
        assertFalse(reparsed.isPaddingFile(1));
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        RecordingListener l = new RecordingListener();
        Storage s = new Storage(util, _dataDir, mi, l, true);
        s.check(0, null);
        assertTrue(s.complete());
    }

    /** Single-file creation is never padded (no file list, no root-level attr support). */
    @Test
    public void testCreationNeverPadsSingleFile() throws Exception {
        File f = new File(_dataDir, "single.dat");
        byte[] c = content(5000);
        writeFile(f, c, 0, 5000);
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        Storage created =
                new Storage(util, f, "http://tracker.test", null, null, false, null, new ArrayList<>());
        MetaInfo mi = created.getMetaInfo();
        assertNull(mi.getFiles());
        assertEquals(5000, mi.getTotalLength());
        assertEquals(1, mi.getPieces());
        RecordingListener l = new RecordingListener();
        Storage s = new Storage(util, f, mi, l, true);
        s.check(0, null);
        assertTrue(s.complete());
        assertFalse(new File(_dataDir, ".pad").exists());
    }

    /**
     * Re-creating a torrent from a directory that already holds materialized pad files (e.g. the
     * data dir of a torrent created by an older version or another client) must not absorb the
     * synthetic files, and must produce the same metainfo. Covers both the .pad and the
     * parse-renamed _pad layouts.
     */
    @Test
    public void testRecreateSkipsExistingPadDir() throws Exception {
        byte[] c = content(2000);
        writeFile(new File(_dataDir, "a.dat"), c, 0, 1000);
        writeFile(new File(_dataDir, "b.dat"), c, 1000, 1000);
        MetaInfo first = createTorrent();
        // Materialize the pad file entry manually, as a foreign client would
        File pad = new File(new File(_dataDir, ".pad"), "64536");
        assertTrue(pad.getParentFile().mkdirs() || pad.getParentFile().isDirectory());
        writeFile(pad, new byte[64536], 0, 64536);
        MetaInfo second = createTorrent();
        assertEquals(first.getFiles(), second.getFiles());
        assertArrayEquals(first.getInfoHash(), second.getInfoHash());
        // Parse-renamed layout: a _pad directory must be skipped too
        assertTrue(new File(_dataDir, ".pad").renameTo(new File(_dataDir, "_pad")));
        MetaInfo third = createTorrent();
        assertEquals(first.getFiles(), third.getFiles());
        assertArrayEquals(first.getInfoHash(), third.getInfoHash());
    }

    /** Creation from a truly empty folder fails with the plain no-data message. */
    @Test
    public void testCreationEmptyDirThrowsNoData() throws Exception {
        File empty = new File(_dataDir, "empty");
        assertTrue(empty.mkdir());
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        try {
            new Storage(
                    util,
                    empty,
                    "http://tracker.test",
                    null,
                    null,
                    false,
                    null,
                    new ArrayList<>());
            fail("expected IOException");
        } catch (IOException ioe) {
            assertEquals("Torrent contains no data", ioe.getMessage());
        }
    }

    /** Creation skips unreadable files with a warning; only readable data is torrented. */
    @Test
    public void testCreationSkipsUnreadableFile() throws Exception {
        File sub = new File(_dataDir, "mixed");
        assertTrue(sub.mkdir());
        byte[] c = content(2000);
        writeFile(new File(sub, "good.dat"), c, 0, 1000);
        File bad = new File(sub, "bad.dat");
        writeFile(bad, c, 1000, 1000);
        assertTrue(bad.setReadable(false));
        org.junit.Assume.assumeFalse("running as root, cannot test permissions", bad.canRead());
        I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
        MetaInfo mi =
                new Storage(
                                util,
                                sub,
                                "http://tracker.test",
                                null,
                                null,
                                false,
                                null,
                                new ArrayList<>())
                        .getMetaInfo();
        assertEquals(1, mi.getFiles().size());
        assertEquals(1000, mi.getTotalLength());
    }

    /** The no-data message distinguishes a permission problem from an empty folder. */
    @Test
    public void testNoDataMessage() {
        assertEquals(
                "Torrent contains no data",
                Storage.noDataMessage(new File("/data"), new ArrayList<String>()));
        String msg =
                Storage.noDataMessage(
                        new File("/data"), Arrays.asList("/data/sub1", "/data/sub2"));
        assertTrue(msg, msg.contains("2 file(s)"));
        assertTrue(msg, msg.contains("/data/sub1"));
        assertFalse(msg, msg.contains("empty"));
    }

    private static void setMtime(File f, long time) {
        assertTrue("setLastModified failed for " + f, f.setLastModified(time));
    }

    // ----- putPiece rectification -----

    /** A no-op bandwidth listener for filling PartialPieces. */
    private static class NullBandwidthListener implements BandwidthListener {
        @Override
        public long getUploadRate() { return 0; }

        @Override
        public long getDownloadRate() { return 0; }

        @Override
        public void uploaded(int size) {}

        @Override
        public void downloaded(int size) {}

        @Override
        public boolean shouldSend(int size) { return false; }

        @Override
        public boolean shouldRequest(Peer peer, int size) { return false; }

        @Override
        public long getUpBWLimit() { return 0; }

        @Override
        public long getDownBWLimit() { return 0; }

        @Override
        public boolean overUpBWLimit() { return false; }

        @Override
        public boolean overDownBWLimit() { return false; }
    }

    /** Builds a PartialPiece filled with the deterministic content of the given piece. */
    private PartialPiece fullPiece(MetaInfo mi, int piece) throws Exception {
        int len = mi.getPieceLength(piece);
        PartialPiece pp = new PartialPiece(new Piece(piece), len, null);
        byte[] data = new byte[len];
        long base = (long) piece * PIECE_LENGTH;
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((base + i) * 31 + 7 & 0xff);
        }
        pp.read(new DataInputStream(new ByteArrayInputStream(data)), 0, len, new NullBandwidthListener());
        return pp;
    }

    /**
     * When the data file cannot be opened read-write (read-only storage state
     * or file permissions), checkRAF() opens it "r" and the write fails with
     * EBADF - the exact failure seen in the field ("Error writing to storage
     * [piece N]", "Caused by: Bad file descriptor"). putPiece must close the
     * handle, make the file writable, and retry with a forced RW reopen, so
     * the piece lands instead of the torrent stopping.
     */
    @Test
    public void testPutPieceRetriesAfterReadOnlyHandle() throws Exception {
        File f = new File(_dataDir, "single.dat");
        MetaInfo mi = buildSingleFileTorrent(f, PIECE_LENGTH * 3);
        corrupt(f, PIECE_LENGTH); // piece 1 no longer matches its hash, so it stays wanted
        assertTrue(f.setWritable(false));
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            RecordingListener l = new RecordingListener();
            Storage s = new Storage(util, f, mi, l, true);
            // piece 1 is re-verified bad; the file handle stays open read-only
            s.check(0, null);
            assertFalse(s.getBitField().get(1));
            assertTrue(s.putPiece(fullPiece(mi, 1)));
            assertTrue(s.getBitField().get(1));
            // the retried write must have landed on disk, restoring the original byte
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                raf.seek(PIECE_LENGTH);
                assertEquals((byte) ((PIECE_LENGTH) * 31 + 7 & 0xff), raf.readByte());
            } finally {
                raf.close();
            }
        } finally {
            f.setWritable(true);
        }
    }

    /** classify maps OS errno text and typed NIO exceptions to classified errors. */
    @Test
    public void testClassifyStorageError() {
        // Unix errno text via plain java.io exceptions
        assertClassify(StorageError.NO_SPACE, new IOException("No space left on device"));
        assertClassify(StorageError.PERMISSION, new IOException("Permission denied"));
        assertClassify(StorageError.READ_ONLY, new IOException("Read-only file system"));
        assertClassify(StorageError.QUOTA, new IOException("Disk quota exceeded"));
        assertClassify(StorageError.IO_ERROR, new IOException("Input/output error"));
        assertClassify(StorageError.TOO_MANY_OPEN, new IOException("Too many open files"));
        assertClassify(StorageError.STALE_HANDLE, new IOException("Bad file descriptor"));
        assertClassify(StorageError.MISSING, new IOException("No such file or directory"));
        assertClassify(StorageError.IS_DIRECTORY, new IOException("Is a directory"));
        assertClassify(StorageError.NAME_TOO_LONG, new IOException("File name too long"));
        assertClassify(StorageError.OTHER, new IOException((String) null));
        assertClassify(StorageError.OTHER, new IOException("something odd"));
        // Windows canonical texts
        assertClassify(StorageError.NO_SPACE, new IOException("There is not enough space on the disk."));
        assertClassify(StorageError.MISSING, new IOException("The system cannot find the file specified"));
        assertClassify(StorageError.PERMISSION, new IOException("Access is denied"));
        assertClassify(StorageError.PERMISSION, new IOException("A required privilege is not held by the client"));
        assertClassify(StorageError.IO_ERROR, new IOException("The device is not ready"));
        assertClassify(StorageError.STALE_HANDLE, new IOException("The process cannot access the file because it is being used by another process"));
    }

    /** typed NIO exceptions classify without any message parsing */
    @Test
    public void testClassifyTypedExceptions() {
        assertClassify(StorageError.MISSING, new NoSuchFileException("/data/torrent/file1"));
        assertClassify(StorageError.MISSING, new FileNotFoundException("/data/torrent/file1"));
        assertClassify(StorageError.PERMISSION, new AccessDeniedException("/data/torrent/file1"));
        assertClassify(StorageError.IS_DIRECTORY, new NotDirectoryException("/data/torrent/file1"));
        assertClassify(StorageError.READ_ONLY, new FileSystemException("/data/torrent/file1", null, "Read-only file system"));
        assertClassify(StorageError.STALE_HANDLE, new FileSystemException("/data/torrent/file1", null, "Bad file descriptor"));
        assertClassify(StorageError.MISSING, new FileSystemException("/data/torrent/file1", null, "No such file or directory"));
        // a path containing an errno phrase must not mislead the reason-only match
        assertClassify(StorageError.OTHER, new FileSystemException("/data/no space left on device/file1", null, "Something else went wrong"));
        assertClassify(StorageError.OTHER, new FileSystemException("/data/torrent/file1"));
    }

    /** describe preserves the raw text only for unclassified errors */
    @Test
    public void testDescribe() {
        assertEquals("No space left on device", StorageError.describe(new IOException("No space left on device")));
        assertEquals("Permission denied", StorageError.describe(new AccessDeniedException("/x")));
        assertEquals("I/O error: something odd", StorageError.describe(new IOException("something odd")));
        assertEquals("I/O error", StorageError.describe(new IOException((String) null)));
    }

    /** the fatal set is the deduplicated single source of truth for stopping a torrent */
    @Test
    public void testFatalFlags() {
        StorageError[] fatal = {StorageError.NO_SPACE, StorageError.PERMISSION, StorageError.READ_ONLY,
                                StorageError.QUOTA, StorageError.IO_ERROR, StorageError.IS_DIRECTORY,
                                StorageError.NAME_TOO_LONG};
        for (StorageError e : fatal) {
            assertTrue(e.name(), e.isFatal());
        }
        StorageError[] transientErrors = {StorageError.TOO_MANY_OPEN, StorageError.STALE_HANDLE,
                                          StorageError.MISSING, StorageError.OTHER};
        for (StorageError e : transientErrors) {
            assertFalse(e.name(), e.isFatal());
        }
    }

    private static void assertClassify(StorageError expected, IOException ioe) {
        assertEquals(expected.name(), expected, StorageError.classify(ioe));
    }

    /** BEP 47: isRangePadding() maps byte ranges onto padding files. */
    @Test
    public void testIsRangePadding() throws Exception {
        // a.dat [0,1000), pad [1000,1500), b.dat [1500,2500) — one 4096-byte piece
        List<String> names = Arrays.asList("a.dat", ".pad/500", "b.dat");
        List<Long> sizes =
                Arrays.asList(Long.valueOf(1000), Long.valueOf(500), Long.valueOf(1000));
        List<String> attrs = Arrays.asList("", "p", "");
        byte[] content = new byte[2500];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        MetaInfo mi =
                new MetaInfo(
                        new ByteArrayInputStream(
                                buildTorrentBytes(
                                        names,
                                        sizes,
                                        attrs,
                                        PIECE_LENGTH,
                                        computeHashes(content, PIECE_LENGTH))));

        assertFalse(mi.isRangePadding(0, 999)); // inside a.dat
        assertFalse(mi.isRangePadding(0, 1));
        assertTrue(mi.isRangePadding(1000, 500)); // exactly the pad file
        assertTrue(mi.isRangePadding(1000, 1));
        assertTrue(mi.isRangePadding(1499, 1));
        assertFalse(mi.isRangePadding(999, 3)); // spans a.dat -> pad
        assertFalse(mi.isRangePadding(1000, 501)); // spans pad -> b.dat
        assertFalse(mi.isRangePadding(1500, 500)); // inside b.dat, exact pad boundary
        assertFalse(mi.isRangePadding(0, 2500)); // whole torrent
        assertFalse(mi.isRangePadding(1000, 0)); // zero length

        // single-file torrents have no padding
        MetaInfo single = buildSingleFileTorrent(new File(_dataDir, "s.dat"), 2048);
        assertFalse(single.isRangePadding(0, 100));
        assertFalse(single.isRangePadding(0, 2048));
    }

    /** BEP 47: getRequest() never requests padding-only sub-blocks and the piece still completes. */
    @Test
    public void testPartialPieceSkipsPaddingChunks() throws Exception {
        int pieceLength = 2 * PeerState.PARTSIZE; // one piece, eight 4K sub-blocks
        // a.dat [0, 128K) real, pad [128K, 256K) padding
        List<String> names = Arrays.asList("a.dat", ".pad/131072");
        List<Long> sizes =
                Arrays.asList(
                        Long.valueOf(PeerState.PARTSIZE), Long.valueOf(PeerState.PARTSIZE));
        List<String> attrs = Arrays.asList("", "p");
        byte[] content = new byte[pieceLength];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        for (int i = PeerState.PARTSIZE; i < pieceLength; i++) {
            content[i] = 0; // pad bytes are zeros
        }
        MetaInfo mi =
                new MetaInfo(
                        new ByteArrayInputStream(
                                buildTorrentBytes(
                                        names,
                                        sizes,
                                        attrs,
                                        pieceLength,
                                        computeHashes(content, pieceLength))));
        assertFalse(mi.isRangePadding(0, PeerState.PARTSIZE));
        assertTrue(mi.isRangePadding(PeerState.PARTSIZE, PeerState.PARTSIZE));

        PartialPiece pp = new PartialPiece(new Piece(0), pieceLength, _dataDir, mi);
        Request r = pp.getRequest();
        assertNotNull(r);
        assertEquals(0, r.off);
        // the real sub-blocks merge into one full-size request
        assertEquals(PeerState.PARTSIZE, r.len);
        // chunk 0 arrives; the padding-only sub-blocks must never be requested
        markAll(pp, 0, 4);
        assertNull(pp.getRequest());
        assertTrue(pp.isComplete());

        // without metainfo nothing is padding, so the second half is requested in full
        PartialPiece noMeta = new PartialPiece(new Piece(0), pieceLength, _dataDir);
        Request r2 = noMeta.getRequest();
        assertNotNull(r2);
        assertEquals(0, r2.off);
        assertEquals(PeerState.PARTSIZE, r2.len);
        markAll(noMeta, 0, 4);
        Request r3 = noMeta.getRequest();
        assertNotNull(r3);
        assertEquals(PeerState.PARTSIZE, r3.off);
        assertEquals(PeerState.PARTSIZE, r3.len);
        assertFalse(noMeta.isComplete());

        // four chunks [pad, real, real, real]: out-of-order arrival of chunk 1 must not
        // corrupt the request offset (a bogus-offset or zero-length Request would be
        // generated otherwise)
        int pieceLength4 = 4 * PeerState.PARTSIZE;
        List<String> names4 = Arrays.asList(".pad/131072", "x.dat", "y.dat", "z.dat");
        List<Long> sizes4 =
                Arrays.asList(
                        Long.valueOf(PeerState.PARTSIZE),
                        Long.valueOf(PeerState.PARTSIZE),
                        Long.valueOf(PeerState.PARTSIZE),
                        Long.valueOf(PeerState.PARTSIZE));
        List<String> attrs4 = Arrays.asList("p", "", "", "");
        MetaInfo mi4 =
                new MetaInfo(
                        new ByteArrayInputStream(
                                buildTorrentBytes(
                                        names4,
                                        sizes4,
                                        attrs4,
                                        pieceLength4,
                                        computeHashes(new byte[pieceLength4], pieceLength4))));
        PartialPiece pp4 = new PartialPiece(new Piece(0), pieceLength4, _dataDir, mi4);
        markAll(pp4, 4, 4); // out-of-order arrival of chunk 1
        Request r4 = pp4.getRequest();
        assertNotNull(r4);
        assertEquals(2 * PeerState.PARTSIZE, r4.off);
        assertEquals(PeerState.PARTSIZE, r4.len);
        markAll(pp4, 8, 4);
        markAll(pp4, 12, 4);
        // chunk 0 was padding: skipped, no request, piece complete
        assertNull(pp4.getRequest());
        assertTrue(pp4.isComplete());
    }

    /**
     * BEP 47: a chunk straddling a padding file is fetched at sub-block granularity, so the
     * fully-padding sub-block is never requested and at most one 4K sub-block of padding is
     * wasted.
     */
    @Test
    public void testPartialPieceCapsStraddlingPad() throws Exception {
        // a.dat [0, 10K) real, pad [10K, 16K): sub-block 2 straddles, sub-block 3 is all padding
        int pieceLength = PeerState.PARTSIZE;
        List<String> names = Arrays.asList("a.dat", ".pad/6144");
        List<Long> sizes = Arrays.asList(Long.valueOf(10240), Long.valueOf(6144));
        List<String> attrs = Arrays.asList("", "p");
        byte[] content = new byte[pieceLength];
        for (int i = 0; i < pieceLength; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        for (int i = 10240; i < pieceLength; i++) {
            content[i] = 0; // pad bytes are zeros
        }
        MetaInfo mi =
                new MetaInfo(
                        new ByteArrayInputStream(
                                buildTorrentBytes(
                                        names,
                                        sizes,
                                        attrs,
                                        pieceLength,
                                        computeHashes(content, pieceLength))));
        assertTrue(mi.isRangePadding(12288, 4096)); // sub-block 3 fully padding
        assertFalse(mi.isRangePadding(8192, 4096)); // sub-block 2 straddles

        PartialPiece pp = new PartialPiece(new Piece(0), pieceLength, _dataDir, mi);
        // the real run [0, 12K) is one request; the all-padding sub-block [12K, 16K) is skipped
        Request r = pp.getRequest();
        assertNotNull(r);
        assertEquals(0, r.off);
        assertEquals(12288, r.len);
        // the three real sub-blocks arrive; the pad sub-block is marked as received, piece complete
        markAll(pp, 0, 3);
        assertNull(pp.getRequest());
        assertTrue(pp.isComplete());
    }

    /** BEP 47: a long padding tail leaves a single real sub-block; no padding is requested. */
    @Test
    public void testPartialPieceSkipsLongPadTail() throws Exception {
        // a.dat [0, 4K) real, pad [4K, 16K)
        int pieceLength = PeerState.PARTSIZE;
        List<String> names = Arrays.asList("a.dat", ".pad/12288");
        List<Long> sizes = Arrays.asList(Long.valueOf(4096), Long.valueOf(12288));
        List<String> attrs = Arrays.asList("", "p");
        byte[] content = new byte[pieceLength];
        for (int i = 0; i < 4096; i++) {
            content[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        MetaInfo mi =
                new MetaInfo(
                        new ByteArrayInputStream(
                                buildTorrentBytes(
                                        names,
                                        sizes,
                                        attrs,
                                        pieceLength,
                                        computeHashes(content, pieceLength))));
        assertTrue(mi.isRangePadding(4096, 4096));

        PartialPiece pp = new PartialPiece(new Piece(0), pieceLength, _dataDir, mi);
        Request r = pp.getRequest();
        assertNotNull(r);
        assertEquals(0, r.off);
        assertEquals(4096, r.len); // only the real sub-block, no padding requested
        markAll(pp, 0, 1);
        assertNull(pp.getRequest());
        assertTrue(pp.isComplete());
    }

    /**
     * getDownloaded() on an empty piece whose length is not a multiple of the 4K sub-block size
     * must return 0, not throw (the short-tail correction must not index the bitfield when
     * nothing is received).
     */
    @Test
    public void testPartialPieceGetDownloadedEmpty() {
        int pieceLength = PeerState.PARTSIZE + 2048; // not a multiple of 4096
        PartialPiece pp = new PartialPiece(new Piece(0), pieceLength, _dataDir);
        assertEquals(0, pp.getDownloaded());
        // one sub-block received: 4096 - (4096 - 2048) short-tail correction
        pp.markSubBlock(0);
        assertEquals(2048, pp.getDownloaded());
        // fully received: the full length, including the short tail
        markAll(pp, 1, 4);
        assertTrue(pp.isComplete());
        assertEquals(pieceLength, pp.getDownloaded());
    }

    /**
     * A temp-file piece (piece length above MAX_IN_MEM) whose trailing sub-blocks are padding is
     * only written up to the last real sub-block, leaving the temp file shorter than the piece.
     * getHash() must still complete: the pad suffix reads back as zeros, not EOF.
     */
    @Test
    public void testPartialPieceTempFilePadSuffixHash() throws Exception {
        int pieceLength = 1024 * 1024 + 8192; // > MAX_IN_MEM: temp-file path
        PartialPiece pp = new PartialPiece(new Piece(0), pieceLength, _dataDir);
        byte[] data = new byte[1024 * 1024];
        pp.read(new DataInputStream(new ByteArrayInputStream(data)), 0, data.length, new StubBWL());
        int subBlocks = pieceLength / 4096;
        pp.markSubBlock(subBlocks - 2); // trailing pad sub-blocks, never written to disk
        pp.markSubBlock(subBlocks - 1);
        assertTrue(pp.isComplete());
        assertEquals(20, pp.getHash().length);
    }

    /** Minimal BandwidthListener for partial piece read tests. */
    private static class StubBWL implements BandwidthListener {

        @Override
        public long getUploadRate() {
            return 0;
        }

        @Override
        public long getDownloadRate() {
            return 0;
        }

        @Override
        public void uploaded(int size) {}

        @Override
        public void downloaded(int size) {}

        @Override
        public boolean shouldSend(int size) {
            return true;
        }

        @Override
        public boolean shouldRequest(Peer peer, int size) {
            return true;
        }

        @Override
        public long getUpBWLimit() {
            return -1;
        }

        @Override
        public long getDownBWLimit() {
            return -1;
        }

        @Override
        public boolean overUpBWLimit() {
            return false;
        }

        @Override
        public boolean overDownBWLimit() {
            return false;
        }
    }

    /** Marks the given sub-blocks as received, simulating their arrival. */
    private static void markAll(PartialPiece pp, int start, int count) {
        for (int i = 0; i < count; i++) {
            pp.markSubBlock(start + i);
        }
    }
}
