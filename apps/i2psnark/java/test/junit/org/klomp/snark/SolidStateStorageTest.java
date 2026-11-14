package org.klomp.snark;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.util.FileUtil;
import org.junit.Test;

/**
 * Tests for deciding whether the storage a torrent writes to is solid state. A file extended to
 * its full length is later written full of zeros to un-sparses it, which defragments a spinning
 * disk and buys nothing on a flash device while costing the file's whole length in writes. The
 * kernel knows which device a directory sits on and whether that device spins, so snark asks it
 * instead of guessing from the CPU.
 *
 * @since 0.9.72+
 */
public class SolidStateStorageTest {

    /** A tmpfs, which has no queue and therefore no answer. */
    private static final String TMPFS = "34 46 0:30 / /run rw,nosuid,nodev shared:13 - "
                                        + "tmpfs tmpfs rw,size=39070128k,inode64";

    /** The root filesystem, on a partition of a solid state NVMe disk. */
    private static final String ROOT = "46 1 259:4 / / rw,noatime shared:1 - ext4 "
                                       + "/dev/nvme0n1p1 rw,errors=remount-ro";

    /** A separate data partition on a spinning disk. */
    private static final String DATA = "51 46 253:0 / /home/i2p/data rw,relatime shared:1 - "
                                       + "ext4 /dev/dm-0 rw";

    /** A mount whose name starts with another mount's name. */
    private static final String DATABASE = "52 46 8:1 / /database rw shared:1 - ext4 "
                                           + "/dev/sda1 rw";

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SolidStateStorageTest() {}

    /**
     * The most specific mount holding the path wins, and a path under no listed mount has no
     * device.
     */
    @Test
    public void testTheMostSpecificMountWins() {
        List<String> mounts = Arrays.asList(TMPFS, ROOT, DATA, DATABASE);
        assertEquals("253:0", Storage.blockDevice("/home/i2p/data/torrent", mounts));
        assertEquals("0:30", Storage.blockDevice("/run/snark", mounts));
        assertEquals("259:4", Storage.blockDevice("/home/other/torrent", mounts));
        assertEquals("259:4", Storage.blockDevice("/", mounts));
    }

    /**
     * A mount holds a path by whole path elements only, so a directory whose name merely starts
     * with a mount point is not inside it, and when two mounts do hold a path the more specific
     * one wins.
     */
    @Test
    public void testAMountDoesNotCoverAPathThatMerelyStartsWithIt() {
        List<String> narrow = Arrays.asList(TMPFS, DATA);
        // "/database" is more specific than "/", so it wins even though both hold the path
        assertEquals("8:1", Storage.blockDevice("/database/x",
                                                Arrays.asList(TMPFS, ROOT, DATA, DATABASE)));
        assertNull(Storage.blockDevice("/database/x", narrow));
        assertNull(Storage.blockDevice("/var/lib/i2p", narrow));
    }

    /**
     * A malformed or unrelated line names no device and is skipped rather than read as one.
     */
    @Test
    public void testALineWithoutADeviceNumberIsSkipped() {
        List<String> mounts = Arrays.asList("", "garbage", "46 1 not-a-number / / rw - ext4 x rw");
        assertNull(Storage.blockDevice("/anything", mounts));
    }

    /**
     * The kernel writes one digit; anything else is not an answer.
     */
    @Test
    public void testTheRotationalFlagParsesOnlyTheKernelValues() {
        assertEquals(0, Storage.parseRotationalFlag("0\n"));
        assertEquals(1, Storage.parseRotationalFlag("1\n"));
        assertEquals(1, Storage.parseRotationalFlag(" 1 "));
        assertEquals(-1, Storage.parseRotationalFlag(""));
        assertEquals(-1, Storage.parseRotationalFlag("2"));
        assertEquals(-1, Storage.parseRotationalFlag("0 1"));
        assertEquals(-1, Storage.parseRotationalFlag("true"));
    }

    /**
     * A device we cannot name, or one the kernel does not list, has no answer, and no answer is
     * not read as solid state.
     */
    @Test
    public void testAnUnknownDeviceHasNoAnswer() {
        assertEquals(-1, Storage.rotationalFlag(null));
        assertEquals(-1, Storage.rotationalFlag(""));
        assertEquals(-1, Storage.rotationalFlag("bogus"));
        assertEquals(-1, Storage.rotationalFlag("8"));
        assertEquals(-1, Storage.rotationalFlag("-1:0"));
        assertEquals(-1, Storage.rotationalFlag("9999:9999"));
    }

    /**
     * On a real Linux host, every mount backed by a block device answers whether it spins,
     * including a partition, which has no queue of its own and is answered by the disk under it.
     *
     * @throws Exception if the mount table cannot be read
     */
    @Test
    public void testARealHostAnswersForItsBlockDevices() throws Exception {
        Path mountinfo = Paths.get(Storage.MOUNTINFO);
        assumeTrue("no kernel mount table here", Files.isReadable(mountinfo));
        assumeTrue("no sysfs here", Files.isDirectory(Paths.get(Storage.SYS_BLOCK)));
        List<String> lines = Files.readAllLines(mountinfo, StandardCharsets.UTF_8);
        int answered = 0;
        for (String line : lines) {
            String[] f = line.split(" ");
            if (f.length < 5 || f[2].startsWith("0:")) {
                continue; // a memory or virtual filesystem, which has no queue
            }
            int flag = Storage.rotationalFlag(f[2]);
            assertTrue("no rotational flag for " + f[2] + " (" + line + ")",
                       flag == 0 || flag == 1);
            answered++;
        }
        assertTrue("the mount table lists no block device", answered > 0);
    }

    /**
     * A real directory resolves to a device, so the check is not reading a path that never
     * matches a mount.
     *
     * @throws Exception if the directory or the mount table cannot be read
     */
    @Test
    public void testARealDirectoryResolvesToADevice() throws Exception {
        Path mountinfo = Paths.get(Storage.MOUNTINFO);
        assumeTrue("no kernel mount table here", Files.isReadable(mountinfo));
        List<String> lines = Files.readAllLines(mountinfo, StandardCharsets.UTF_8);
        File dir = new File(System.getProperty("java.io.tmpdir")).getCanonicalFile();
        assertNotNull("the scratch directory is under no listed mount",
                      Storage.blockDevice(dir.getAbsolutePath(), lines));
    }

    /**
     * Only a file that was empty before we extended it, on a device that spins, with
     * preallocation configured on, gets written full of zeros.
     */
    @Test
    public void testBallooningHappensOnlyWhereItPays() {
        assertTrue(Storage.shouldBalloon(true, true, false, false));
        assertFalse(Storage.shouldBalloon(true, true, false, true));
        assertFalse(Storage.shouldBalloon(true, true, true, false));
        assertFalse(Storage.shouldBalloon(true, false, false, false));
        assertFalse(Storage.shouldBalloon(false, true, false, false));
        assertFalse(Storage.shouldBalloon(false, true, false, true));
    }

    /**
     * A storage asks about the device its own data directory sits on, and reaches the same
     * answer the mount table gives for that directory - so the check is wired to the real
     * device, not to a guess.
     *
     * @throws Throwable if the fixture torrent cannot be read
     */
    @Test
    public void testAStorageAsksAboutItsOwnDirectory() throws Throwable {
        File dir = File.createTempFile("snark-ssd-", "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        try {
            I2PSnarkUtil util = new I2PSnarkUtil(I2PAppContext.getGlobalContext());
            MetaInfo mi = new MetaInfo(new ByteArrayInputStream(torrentBytes(2)));
            Storage st = new Storage(util, dir, mi, null, false);
            Path mountinfo = Paths.get(Storage.MOUNTINFO);
            List<String> lines = Files.isReadable(mountinfo)
                    ? Files.readAllLines(mountinfo, StandardCharsets.UTF_8)
                    : Collections.<String>emptyList();
            boolean expected =
                    Storage.rotationalFlag(Storage.blockDevice(dir.getCanonicalPath(), lines))
                    == 0;
            assertEquals(expected, st.isSolidState());
        } finally {
            FileUtil.rmdir(dir, false);
        }
    }

    /**
     * The bencoded bytes of a torrent with that many pieces, all zero hashes.
     *
     * @param pieces how many pieces the torrent is cut into
     * @return the metainfo bytes
     */
    private static byte[] torrentBytes(int pieces) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('d');
        sb.append("8:announce");
        sb.append("19:http://tracker.test");
        sb.append("4:info");
        sb.append('d');
        sb.append("6:length").append('i').append((long) 16384 * pieces).append('e');
        sb.append("4:name").append("9:test.file");
        sb.append("12:piece length").append('i').append(16384).append('e');
        sb.append("6:pieces").append(20 * pieces).append(':');
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] hashes = new byte[20 * pieces];
        byte[] tail = "ee".getBytes(StandardCharsets.ISO_8859_1);
        byte[] rv = new byte[head.length + hashes.length + tail.length];
        System.arraycopy(head, 0, rv, 0, head.length);
        System.arraycopy(hashes, 0, rv, head.length, hashes.length);
        System.arraycopy(tail, 0, rv, head.length + hashes.length, tail.length);
        return rv;
    }
}
