package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.Hash;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests what the blocklist feed file may cost in memory.
 * <p>
 * The feed file is written by {@code NewsFetcher} from a signed news feed, and
 * that writer caps the entries at 2,000. Reading it back had no cap of its own,
 * so a file with more entries than the writer can produce - edited, or with
 * lines injected through the feed's raw {@code updated} text - grew the router
 * hash map without end. Reading the feed also counted nothing at all, which
 * left its version unset and its log line reading "Read 0 valid entries".
 *
 * @since 0.9.72+
 */
public class BlocklistFeedCapTest {

    /** Where the feed file sits under the config dir, as Blocklist names it. */
    private static final String FEED_PATH = "docs/feed/blocklist/blocklist.txt";

    /** Context the blocklist and its config dir come from. */
    private static RouterContext _ctx;

    /** Builds the context, skipping the class when none can be created. */
    @BeforeClass
    public static void setUpClass() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /** Holds no state of its own beyond the context built for the class. */
    public BlocklistFeedCapTest() {}

    /**
     * A feed file longer than the writer's cap is read up to that cap and no
     * further, and its entries are counted, so the version is recorded and the
     * log says what was read.
     *
     * @throws IOException if the feed file cannot be written
     */
    @Test
    public void feedFileIsReadUpToTheCap() throws IOException {
        int extra = 500;
        File feed = writeFeed(Blocklist.MAX_FEED_ENTRIES + extra);
        Blocklist bl = new Blocklist(_ctx);
        try {
            Object blf = blocklistFile(feed, "feed");
            int count = read(bl, blf, new long[8], 0);
            assertEquals("the array entries read", 0, count);
            Map<?, ?> peers = peerBlocklist(bl);
            assertEquals("router hashes taken from an over-long feed",
                         Blocklist.MAX_FEED_ENTRIES, peers.size());
            assertTrue("the feed version was not recorded", version(blf) > 0);
        } finally {
            feed.delete();
        }
    }

    /**
     * A feed at the cap is read whole, so the limit costs nothing to a feed the
     * writer itself produced.
     *
     * @throws IOException if the feed file cannot be written
     */
    @Test
    public void feedFileAtTheCapIsReadWhole() throws IOException {
        File feed = writeFeed(Blocklist.MAX_FEED_ENTRIES);
        Blocklist bl = new Blocklist(_ctx);
        try {
            Object blf = blocklistFile(feed, "feed");
            read(bl, blf, new long[8], 0);
            assertEquals("router hashes taken from a full feed",
                         Blocklist.MAX_FEED_ENTRIES, peerBlocklist(bl).size());
        } finally {
            feed.delete();
        }
    }

    /**
     * Writes a feed file of the given number of router-hash entries, each in the
     * form the writer emits: a reason, a colon, then the base64 hash.
     *
     * @param entries entries to write, each with a distinct hash
     * @return the feed file, in the place the blocklist reads it from
     * @throws IOException if the file cannot be written
     */
    private static File writeFeed(int entries) throws IOException {
        File dir = new File(_ctx.getConfigDir(), "docs/feed/blocklist");
        dir.mkdirs();
        File feed = new File(dir, "blocklist.txt");
        try (FileWriter w = new FileWriter(feed)) {
            w.write("# 2026-01-01T00:00:00Z\n");
            for (int i = 0; i < entries; i++) {
                byte[] hb = new byte[Hash.HASH_LENGTH];
                hb[0] = (byte) (i >>> 8);
                hb[1] = (byte) i;
                hb[2] = (byte) 'z';
                w.write("Blocklist feed 2026-01-01:" + Base64.encode(hb) + "\n");
            }
        }
        feed.setLastModified(System.currentTimeMillis());
        return feed;
    }

    /**
     * Names one blocklist file the way Blocklist does internally.
     *
     * @param f the file
     * @param id the feed identifier
     * @return the BLFile instance
     */
    private static Object blocklistFile(File f, String id) {
        try {
            Class<?> blf = Class.forName("net.i2p.router.Blocklist$BLFile");
            Constructor<?> ctor = blf.getDeclaredConstructor(File.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(f, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Reads one blocklist file into an array.
     *
     * @param bl the blocklist to read into
     * @param blf the BLFile naming the file to read
     * @param blocklist the array to store IPv4 ranges in
     * @param count entries already in the array
     * @return the entry count after the read
     */
    private static int read(Blocklist bl, Object blf, long[] blocklist, int count) {
        try {
            Method m = Blocklist.class.getDeclaredMethod("readBlocklistFile",
                                                         blf.getClass(), long[].class, int.class);
            m.setAccessible(true);
            return (Integer) m.invoke(bl, blf, blocklist, count);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The router hashes the read queued up.
     *
     * @param bl the blocklist that was read
     * @return the pending peer blocklist
     */
    @SuppressWarnings("unchecked")
    private static Map<Hash, String> peerBlocklist(Blocklist bl) {
        try {
            Field f = Blocklist.class.getDeclaredField("_peerBlocklist");
            f.setAccessible(true);
            return (Map<Hash, String>) f.get(bl);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The version recorded for one blocklist file.
     *
     * @param blf the BLFile to read the version from
     * @return the version, 0 if none was recorded
     */
    private static long version(Object blf) {
        try {
            Field f = blf.getClass().getField("version");
            return f.getLong(blf);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
