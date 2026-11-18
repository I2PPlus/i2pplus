package org.klomp.snark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.BeforeClass;
import org.junit.Test;

import net.i2p.I2PAppContext;

import org.klomp.snark.dht.DHT;

/**
 * Tests that the metadata a magnet resolves reaches the tracker client, and that
 * a private flag in it closes public peer discovery.
 *
 * <p>The defect: setup() runs once, and a torrent added by magnet reaches it with
 * no metadata at all, so the tracker client's copy of the metadata stayed null for
 * the life of the torrent. Every private-torrent gate reads that field - the DHT
 * announce, PEX, the torrent's own announce list, its web seeds - so a private
 * torrent pulled in by magnet had its infohash announced to strangers and its
 * peers shared over PEX forever. Nothing fixes it afterwards: reinitialize()
 * refuses to re-run setup() on a client that is still running, so restarting the
 * torrent from the console keeps the leak.
 *
 * <p>No router and no network: the client is built over mocks, and the fetch line
 * and demand counter it shares with every other torrent are reached by reflection.
 *
 * @since 0.9.72+
 */
public class MagnetPrivateTorrentTest {

    /** The infohash of the torrent every client here stands for. */
    private static final byte[] IH = new byte[20];

    /** Our own peer id, as the announce URL query needs it. */
    private static final byte[] ID = new byte[20];

    private static I2PAppContext _ctx;

    /**
     * One context for the whole group, real enough to hand out a log and a clock.
     *
     * @since 0.9.72+
     */
    @BeforeClass
    public static void globalSetUp() {
        _ctx = new I2PAppContext(new Properties());
    }

    /**
     * A fixture group for the magnet metadata path; it holds no state of its own.
     *
     * @since 0.9.72+
     */
    public MagnetPrivateTorrentTest() {}

    /**
     * A torrent whose metadata has not arrived yet is not private - that is what
     * lets it use the DHT to look the metadata up in the first place.
     */
    @Test
    public void testNoMetadataYetLeavesDiscoveryOpen() {
        TrackerClient tc = newClient(null);
        assertFalse(tc.isPrivateTorrent());
    }

    /**
     * The regression: once the metadata says private, the gates have to see it,
     * or the torrent keeps announcing itself into the DHT and sharing its peers
     * over PEX for as long as it runs.
     */
    @Test
    public void testPrivateMetadataClosesTheGates() {
        TrackerClient tc = newClient(null);
        tc.gotMetaInfo(meta(1));
        assertTrue("the private flag never reached the tracker client", tc.isPrivateTorrent());
    }

    /**
     * Metadata that says the torrent is public, whether the key is absent or set
     * to 0, leaves both gates open.
     */
    @Test
    public void testPublicMetadataKeepsTheGatesOpen() {
        TrackerClient tc = newClient(null);
        tc.gotMetaInfo(meta(0));
        assertFalse(tc.isPrivateTorrent());
        tc.gotMetaInfo(meta(-1));
        assertFalse(tc.isPrivateTorrent());
    }

    /**
     * The gate itself: only a present private flag of 1 closes it, and no
     * metadata at all never does.
     */
    @Test
    public void testTheGateReadsOnlyAPresentPrivateFlag() {
        assertFalse(TrackerClient.isPrivateTorrent(null));
        assertFalse(TrackerClient.isPrivateTorrent(meta(0)));
        assertFalse(TrackerClient.isPrivateTorrent(meta(-1)));
        assertTrue(TrackerClient.isPrivateTorrent(meta(1)));
    }

    /**
     * Learning the torrent is private also has to undo the announce that already
     * happened, or the infohash stays in the DHT until its announce times out.
     */
    @Test
    public void testAPrivateTorrentIsUnannounced() {
        DHT dht = mock(DHT.class);
        TrackerClient tc = newClient(dht);
        tc.gotMetaInfo(meta(1));
        verify(dht).unannounce(IH);
    }

    /** A public torrent keeps its DHT announce. */
    @Test
    public void testAPublicTorrentIsNotUnannounced() {
        DHT dht = mock(DHT.class);
        TrackerClient tc = newClient(dht);
        tc.gotMetaInfo(meta(0));
        verify(dht, never()).unannounce(IH);
    }

    /**
     * A fetch that is waiting its turn in the line shared by every torrent is
     * taken out of it, and gives back the demand it was holding, or the drain
     * starts a lookup for a torrent that must not do one.
     *
     * @throws Exception if one of the private fields is gone or reflection is
     *                   not allowed to open it
     */
    @Test
    public void testAQueuedFetchIsTakenOutOfTheLine() throws Exception {
        ConcurrentLinkedQueue<TrackerClient> line = fetchLine();
        line.clear();
        TrackerClient tc = newClient(null);
        AtomicBoolean queued = field(tc, "_dhtQueued", AtomicBoolean.class);
        AtomicInteger demand = field(null, "_dhtFetchDemand", AtomicInteger.class);
        queued.set(true);
        line.add(tc);
        demand.set(1);
        tc.gotMetaInfo(meta(1));
        assertFalse("the private torrent is still in line for a fetch", line.contains(tc));
        assertFalse("the torrent still believes its fetch is queued", queued.get());
        assertEquals("the queued fetch was not given back as demand", 0, demand.get());
        line.clear();
    }

    /**
     * A fetch that has already started keeps its queue entry, its queued flag and
     * its demand: the fetch thread's own finally block gives all three back, and
     * taking them here would count the same fetch out twice.
     *
     * @throws Exception if one of the private fields is gone or reflection is
     *                   not allowed to open it
     */
    @Test
    public void testARunningFetchKeepsWhatTheFetchThreadOwns() throws Exception {
        ConcurrentLinkedQueue<TrackerClient> line = fetchLine();
        line.clear();
        TrackerClient tc = newClient(null);
        AtomicBoolean queued = field(tc, "_dhtQueued", AtomicBoolean.class);
        AtomicInteger demand = field(null, "_dhtFetchDemand", AtomicInteger.class);
        queued.set(true);
        demand.set(1);
        tc.gotMetaInfo(meta(1));
        assertTrue(queued.get());
        assertEquals(1, demand.get());
        line.clear();
    }

    /**
     * A tracker client for a torrent added by magnet: no metadata, nothing
     * started, and every collaborator a mock.
     *
     * @param dht the DHT to hand back from the util, null for a torrent with no
     *            DHT available
     * @return a client over the shared context
     */
    private static TrackerClient newClient(DHT dht) {
        I2PSnarkUtil util = mock(I2PSnarkUtil.class);
        when(util.getContext()).thenReturn(_ctx);
        when(util.getDHTForTorrent(IH)).thenReturn(dht);
        Snark snark = mock(Snark.class);
        when(snark.getInfoHash()).thenReturn(IH);
        when(snark.getID()).thenReturn(ID);
        return new TrackerClient(util, null, null, mock(PeerCoordinator.class), snark);
    }

    /**
     * A one-file torrent's metadata with the private flag set as asked.
     *
     * @param privateFlag 1 for private, 0 for the key absent, -1 for an explicit
     *                    "not private"
     * @return metadata a magnet would resolve
     */
    private static MetaInfo meta(int privateFlag) {
        return new MetaInfo("http://tracker.example/announce", "test torrent", null,
                            null, null, 16384, new byte[20], 1024L, privateFlag,
                            null, null, null, null);
    }

    /**
     * The fetch line every torrent queues on, shared by all clients.
     *
     * @return the static queue
     * @throws Exception if the field is gone or reflection is not allowed to
     *                   open it
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<TrackerClient> fetchLine() throws Exception {
        return (ConcurrentLinkedQueue<TrackerClient>) field(null, "_dhtFetchQueue",
                                                            ConcurrentLinkedQueue.class);
    }

    /**
     * Read one of the tracker client's private fields.
     *
     * @param target the client to read from, null to read a static field
     * @param name the field name
     * @param type the type to hand back
     * @param <T> the field's type
     * @return the value the field holds
     * @throws Exception if the field is gone or reflection is not allowed to
     *                   open it
     */
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field f = TrackerClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(target));
    }
}
