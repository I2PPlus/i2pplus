package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
        int pieceCount = (int) ((content.length + PIECE_LENGTH - 1) / PIECE_LENGTH);
        byte[] hashes = new byte[20 * pieceCount];
        MessageDigest md = SHA1.getInstance();
        for (int p = 0; p < pieceCount; p++) {
            int start = p * PIECE_LENGTH;
            int len = Math.min(PIECE_LENGTH, content.length - start);
            md.reset();
            md.update(content, start, len);
            System.arraycopy(md.digest(), 0, hashes, 20 * p, 20);
        }
        return hashes;
    }

    /** Builds a bencoded multi-file torrent byte stream. */
    private static byte[] buildTorrentBytes(List<String> names, List<Long> sizes, byte[] pieceHashes) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('d');
        sb.append("8:announce").append("19:http://tracker.test");
        sb.append("4:info").append('d');
        sb.append("5:files").append('l');
        for (int i = 0; i < names.size(); i++) {
            sb.append('d');
            sb.append("6:length").append('i').append(sizes.get(i)).append('e');
            sb.append("4:path").append('l');
            byte[] nb = names.get(i).getBytes(StandardCharsets.ISO_8859_1);
            sb.append(nb.length).append(':').append(names.get(i));
            sb.append('e');
            sb.append('e');
        }
        sb.append('e');
        sb.append("4:name").append("4:data");
        sb.append("12:piece length").append('i').append(PIECE_LENGTH).append('e');
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

    private static void setMtime(File f, long time) {
        assertTrue("setLastModified failed for " + f, f.setLastModified(time));
    }
}
