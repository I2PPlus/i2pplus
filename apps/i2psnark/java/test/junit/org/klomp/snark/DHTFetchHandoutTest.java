package org.klomp.snark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.Invocation;

import net.i2p.I2PAppContext;

import org.klomp.snark.dht.DHT;

/**
 * Who owns a torrent's DHT fetch while the drain is working through the line every
 * torrent shares.
 *
 * <p>The defect: the drain looked at the head of the line with peek(), decided the
 * rate and the slots for that client, and only then took it out with poll(). A
 * cancellation landing in that window - the magnet resolving, the torrent turning
 * out to be private - removed the head client, and the poll then took the client
 * behind it out of the line while still handing the fetch to the one it had
 * peeked. That second client was left with neither a queue entry nor a running
 * fetch, and its queued flag stayed true for the life of the torrent, so it never
 * started another DHT lookup or announce. The cancelled client's own fetch then
 * decremented the shared demand a second time, pinning every torrent's rate at the
 * floor.
 *
 * <p>The rule being pinned: the drain dequeues a client before it decides anything
 * about it, so a cancellation can only take out a client the drain is not holding.
 *
 * <p>No router and no network: the clients are built over mocks, the drain is run
 * directly, and the shared line, demand and slot counters are reached by
 * reflection.
 *
 * @since 0.9.72+
 */
public class DHTFetchHandoutTest {

    /**
     * A fixture group for the DHT fetch hand-out; it holds no state of its own.
     *
     * @since 0.9.72+
     */
    public DHTFetchHandoutTest() {}

    /** The infohash of the torrent every client here stands for. */
    private static final byte[] IH = new byte[20];

    /** Our own peer id, as the announce URL query needs it. */
    private static final byte[] ID = new byte[20];

    /** Enough running fetches to fill every slot, so the drain stops before handing one out. */
    private static final int SLOTS_FULL = 99;

    /** How long to wait for a fetch the drain handed out to ask its DHT for peers. */
    private static final int FETCH_GRACE_MS = 5000;

    /** How long to wait for the fetches and drain passes one case started to finish. */
    private static final int QUIESCE_MS = 5000;

    /**
     * Nodes the DHT has to know for the drain to fetch faster than the one-a-minute
     * floor - above the bootstrap floor the drain reads.
     */
    private static final int NODES_TO_FETCH = 40;

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
     * Leaves the shared line, the demand count and the running-fetch count as the
     * next case expects them.
     *
     * @throws Exception if one of the private fields is gone
     */
    @Before
    public void clearSharedState() throws Exception {
        // a fetch handed out by the case before this one drains the line again when
        // it finishes, and that pass must not run over the line this case sets up
        quiesce();
        fetchLine().clear();
        demand().set(0);
        running().set(0);
        draining().set(false);
        // the next start time every fetch waits for, so a case never waits on a
        // spacing left over from the one before it
        field(null, "_dhtFetchNext", AtomicLong.class).set(0L);
    }

    /**
     * The race: a cancellation landing while the drain is deciding about the head
     * of the line must not cost the client behind it its place. The drain holds the
     * head once it has taken it out of the line, so the cancellation finds nothing
     * to remove and the line keeps both clients.
     *
     * <p>The cancellation is injected through the DHT mock's size(), which the drain
     * reads after it has looked at the head and before it hands anything out - the
     * window the bug lived in.
     *
     * @throws Exception if one of the private fields is gone or reflection is not
     *                   allowed to open it
     */
    @Test
    public void aCancellationWhileTheDrainIsDecidingKeepsTheLine() throws Exception {
        ConcurrentLinkedQueue<TrackerClient> line = fetchLine();
        DHT dhtA = mock(DHT.class);
        DHT dhtB = mock(DHT.class);
        TrackerClient a = queuedClient(dhtA);
        TrackerClient b = queuedClient(dhtB);
        // the metadata arrives mid-decision, and with it the private flag
        when(dhtA.size()).thenAnswer(inv -> {
            a.gotMetaInfo(meta(1));
            return 40;
        });
        verify(dhtA, never()).unannounce(any());
        demand().set(2);
        running().set(SLOTS_FULL);

        drain();

        verify(dhtA).unannounce(any());
        assertTrue("the drain discarded the client it was holding", line.contains(a));
        assertTrue("the client behind the one being decided about lost its place",
                   line.contains(b));
        assertTrue("a client the drain put back still believes it is not queued",
                   queued(a).get());
        assertEquals("the cancelled client's demand was given back while it is still queued",
                     2, demand().get());
    }

    /**
     * The harm the rule prevents, with a slot free: the old drain handed the fetch to
     * the client it had looked at and took the one behind it out of the line, so that
     * second client was left with no queue entry, no fetch running, and its queued
     * flag still true - it never asked the DHT for peers again.
     *
     * <p>The mocks say who was served: the drain decides about a client by reading
     * the size of its DHT, and only a fetch that is actually handed out asks that DHT
     * for peers.
     *
     * @throws Exception if one of the private fields is gone or reflection is not
     *                   allowed to open it
     */
    @Test
    public void aCancellationWithASlotFreeStillFetchesTheNextClient() throws Exception {
        DHT dhtA = mock(DHT.class);
        DHT dhtB = mock(DHT.class);
        // enough known nodes and demand that the fetch the drain hands B is started
        // at the fastest spacing, so the case does not wait a minute for a token
        when(dhtA.getPeersAndAnnounce(any(), anyInt(), anyLong(), anyInt(), anyLong(),
                                      anyBoolean(), anyBoolean()))
                .thenReturn(Collections.emptyList());
        when(dhtB.getPeersAndAnnounce(any(), anyInt(), anyLong(), anyInt(), anyLong(),
                                      anyBoolean(), anyBoolean()))
                .thenReturn(Collections.emptyList());
        when(dhtB.size()).thenReturn(NODES_TO_FETCH);
        TrackerClient a = queuedClient(dhtA);
        TrackerClient b = queuedClient(dhtB);
        // the metadata arrives mid-decision, and with it the private flag
        when(dhtA.size()).thenAnswer(inv -> {
            a.gotMetaInfo(meta(1));
            return NODES_TO_FETCH;
        });
        demand().set(TrackerClient.DHT_FETCH_RATE_MAX);
        running().set(0);

        drain();

        assertTrue("the client behind the cancelled one was never decided about",
                   sizeChecks(dhtB) >= 1);
        assertTrue("the fetch the drain handed to the client behind it never finished" +
                   " * queued " + queued(b).get() + " * running " + running().get() +
                   " * stopped " + stopped(b) + " * private " + b.isPrivateTorrent(),
                   fetchFinished(b, FETCH_GRACE_MS));
        assertEquals("the client behind the cancelled one was never given its lookup",
                     1, peerFetchCalls(dhtB));
        quiesce();
    }

    /**
     * A client the drain takes out and cannot serve goes back to the line with its
     * queued flag and its demand intact, so the fetch that frees a slot serves it
     * rather than making the torrent wait a whole announce round.
     *
     * @throws Exception if one of the private fields is gone or reflection is not
     *                   allowed to open it
     */
    @Test
    public void aClientWithNoSlotGoesBackToTheLine() throws Exception {
        ConcurrentLinkedQueue<TrackerClient> line = fetchLine();
        DHT dht = mock(DHT.class);
        TrackerClient a = queuedClient(dht);
        demand().set(1);
        running().set(SLOTS_FULL);

        drain();

        assertTrue("the client was dropped from the line without being fetched", line.contains(a));
        assertTrue("the client was told it is not queued", queued(a).get());
        assertEquals("the demand was given back for a fetch that is still queued",
                     1, demand().get());
    }

    /**
     * A client that halted while it waited is taken out of the line and its own
     * flags given back, and the client behind it keeps its place.
     *
     * @throws Exception if one of the private fields is gone or reflection is not
     *                   allowed to open it
     */
    @Test
    public void aHaltedClientIsTakenOutOfTheLine() throws Exception {
        ConcurrentLinkedQueue<TrackerClient> line = fetchLine();
        TrackerClient a = queuedClient(null);
        TrackerClient b = queuedClient(mock(DHT.class));
        demand().set(2);
        running().set(SLOTS_FULL);

        drain();

        assertFalse("the halted client is still in line", line.contains(a));
        assertTrue("the client behind the halted one lost its place", line.contains(b));
        assertFalse("the halted client still believes its fetch is queued", queued(a).get());
        assertEquals("the halted fetch was not given back as demand", 1, demand().get());
    }

    /**
     * Runs one drain pass over the shared line, on this thread.
     *
     * @throws Exception if the drain cannot be reached
     */
    private static void drain() throws Exception {
        Class<?> cls = Class.forName("org.klomp.snark.TrackerClient$DHTDrain");
        Runnable drain = (Runnable) cls.getDeclaredConstructor().newInstance();
        drain.run();
    }

    /**
     * How many times the drain asked this DHT how many nodes it knows - the call it
     * makes about a client before it hands that client's fetch out. A client the
     * drain takes out of the line and never decides about is a client it never
     * fetched.
     *
     * @param dht the DHT to look at
     * @return the number of size() calls recorded for it
     * @since 0.9.72+
     */
    private static int sizeChecks(DHT dht) {
        int checks = 0;
        for (Invocation inv : mockingDetails(dht).getInvocations()) {
            if ("size".equals(inv.getMethod().getName())) {
                checks++;
            }
        }
        return checks;
    }

    /**
     * How many times a fetch was actually handed to this DHT - every fetch starts
     * with a peer lookup, so a client the drain took out of the line and never
     * served shows no call.
     *
     * @param dht the DHT to look at
     * @return the number of peer lookups recorded for it
     * @since 0.9.72+
     */
    private static int peerFetchCalls(DHT dht) {
        int calls = 0;
        for (Invocation inv : mockingDetails(dht).getInvocations()) {
            String name = inv.getMethod().getName();
            if ("getPeers".equals(name) || "getPeersAndAnnounce".equals(name)) {
                calls++;
            }
        }
        return calls;
    }

    /**
     * Waits for the fetch handed to this client to finish - the moment its queued
     * flag comes off and its share of the demand goes back.
     *
     * @param tc the client to watch
     * @param ms how long to wait, in millis
     * @return true if the fetch finished inside the wait
     * @throws Exception if one of the private fields is gone
     */
    private static boolean fetchFinished(TrackerClient tc, int ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until && queued(tc).get()) {
            Thread.sleep(20);
        }
        return !queued(tc).get();
    }

    /**
     * @param tc the client to read
     * @return true if this client has been halted
     * @throws Exception if the field is gone
     */
    private static boolean stopped(TrackerClient tc) throws Exception {
        return ((Boolean) field(tc, "stop", Boolean.class)).booleanValue();
    }

    /**
     * Waits for the fetches the drain handed out, and the drain pass each one
     * schedules when it finishes, to be over, so the shared counters are quiet before
     * the next case sets them up.
     *
     * @throws Exception if one of the private fields is gone
     */
    private static void quiesce() throws Exception {
        long until = System.currentTimeMillis() + QUIESCE_MS;
        while (System.currentTimeMillis() < until &&
               (running().get() > 0 || draining().get())) {
            Thread.sleep(20);
        }
    }

    /**
     * @return the flag saying a drain pass is running
     * @throws Exception if the field is gone
     */
    private static AtomicBoolean draining() throws Exception {
        return field(null, "_dhtDraining", AtomicBoolean.class);
    }

    /**
     * A client with a fetch waiting its turn: queued, in the line, and holding a
     * DHT to fetch from.
     *
     * @param dht the DHT to hand the client, null for one that has none
     * @return a client ready to be drained
     * @throws Exception if one of the private fields is gone
     */
    private static TrackerClient queuedClient(DHT dht) throws Exception {
        I2PSnarkUtil util = mock(I2PSnarkUtil.class);
        when(util.getContext()).thenReturn(_ctx);
        when(util.getDHTForTorrent(IH)).thenReturn(dht);
        Snark snark = mock(Snark.class);
        when(snark.getInfoHash()).thenReturn(IH);
        when(snark.getID()).thenReturn(ID);
        TrackerClient tc = new TrackerClient(util, null, null, mock(PeerCoordinator.class), snark);
        // a client that has not run setup() is stopped, and the drain treats a
        // stopped client as halted while it waited
        setField(tc, "stop", Boolean.FALSE);
        setField(tc, "_pendingDHT", dht);
        queued(tc).set(true);
        fetchLine().add(tc);
        return tc;
    }

    /**
     * @param tc the client to read
     * @return the flag saying its fetch is in the line
     * @throws Exception if the field is gone
     */
    private static AtomicBoolean queued(TrackerClient tc) throws Exception {
        return field(tc, "_dhtQueued", AtomicBoolean.class);
    }

    /**
     * @return the demand every torrent's queued fetches add up to
     * @throws Exception if the field is gone
     */
    private static AtomicInteger demand() throws Exception {
        return field(null, "_dhtFetchDemand", AtomicInteger.class);
    }

    /**
     * @return the count of DHT fetches running right now
     * @throws Exception if the field is gone
     */
    private static AtomicInteger running() throws Exception {
        return field(null, "_dhtFetchRunning", AtomicInteger.class);
    }

    /**
     * The fetch line every torrent queues on, shared by all clients.
     *
     * @return the static queue
     * @throws Exception if the field is gone or reflection is not allowed to open it
     */
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<TrackerClient> fetchLine() throws Exception {
        return (ConcurrentLinkedQueue<TrackerClient>) field(null, "_dhtFetchQueue",
                                                            ConcurrentLinkedQueue.class);
    }

    /**
     * A one-file torrent's metadata with the private flag set as asked.
     *
     * @param privateFlag 1 for private, 0 for the key absent
     * @return metadata a magnet would resolve
     */
    private static MetaInfo meta(int privateFlag) {
        return new MetaInfo("http://tracker.example/announce", "test torrent", null,
                            null, null, 16384, new byte[20], 1024L, privateFlag,
                            null, null, null, null);
    }

    /**
     * Put a value in one of the tracker client's private fields.
     *
     * @param target the client to write to, null to write a static field
     * @param name the field name
     * @param value the value to leave there
     * @throws Exception if the field is gone or reflection is not allowed to open it
     */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = TrackerClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Read one of the tracker client's private fields.
     *
     * @param target the client to read from, null to read a static field
     * @param name the field name
     * @param type the type to hand back
     * @param <T> the field's type
     * @return the value the field holds
     * @throws Exception if the field is gone or reflection is not allowed to open it
     */
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field f = TrackerClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(target));
    }
}
