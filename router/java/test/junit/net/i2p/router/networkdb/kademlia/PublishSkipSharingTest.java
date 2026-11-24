package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.Clock;
import org.junit.Before;
import org.junit.Test;

/**
 * The store jobs one publish starts aim at different floodfills,
 * {@link FloodfillNetworkDatabaseFacade#sharedSkipSet(Set)}.
 * <p>Publication starts two store jobs for one LeaseSet, and each job asks for
 * the floodfill nearest the entry's routing key and finishes as soon as that
 * one replies. With a skip set of its own, every job names the same nearest
 * floodfill, so the copies pile up on one peer: a live router sent 9.1 LeaseSet
 * store messages a minute for 3.4 publishes a minute, about two sends for one
 * copy on the network. A searcher that cannot reach that one floodfill then
 * reports a live destination missing, which is how a site comes back after a
 * retry and looks unreachable the first time.
 * <p>{@link StoreState} keeps the skip set it is given by reference and adds
 * every peer it tries to it, so one set shared by the sibling jobs pushes the
 * second job to the next nearest floodfill - two copies for the same messages.
 *
 * @since 0.9.72+
 */
public class PublishSkipSharingTest {

    private RouterContext _ctx;

    /**
     * Test case carrying the publish-spread cases. The context is only there to
     * give a store state a clock, so nothing here needs a running router.
     */
    public PublishSkipSharingTest() {
    }

    /**
     * A mocked context supplying only a clock, so a store state can be built
     * without a running router.
     */
    @Before
    public void setUp() {
        _ctx = mock(RouterContext.class);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(System.currentTimeMillis());
        when(_ctx.clock()).thenReturn(clock);
    }

    /**
     * A floodfill hash told apart only by its first byte.
     *
     * @param id the first byte of the hash
     * @return a fresh 32-byte hash
     */
    private static Hash peer(char id) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) id;
        data[1] = (byte) 0x5a;
        return Hash.create(data);
    }

    /**
     * A store state over the given skip set, standing in for one store job.
     *
     * @param skip the set the job shares, or null for a job of its own
     * @return a state that has not attempted anything yet
     */
    private StoreState job(Set<Hash> skip) {
        return new StoreState(_ctx, peer('k'), new RouterInfo(), skip);
    }

    /**
     * A caller that passes no set gets one, and two calls get two different
     * ones - a publish's jobs share, unrelated publishes do not.
     */
    @Test
    public void testNoSetStillGetsOne() {
        Set<Hash> first = FloodfillNetworkDatabaseFacade.sharedSkipSet(null);
        Set<Hash> second = FloodfillNetworkDatabaseFacade.sharedSkipSet(null);
        assertTrue(first.isEmpty());
        assertNotSame(first, second);
    }

    /**
     * A caller that passes a set keeps it, so the documented behaviour that
     * every attempted peer is added to the caller's set still holds.
     */
    @Test
    public void testCallersSetIsKept() {
        Set<Hash> mine = new HashSet<>(4);
        assertSame(mine, FloodfillNetworkDatabaseFacade.sharedSkipSet(mine));
    }

    /**
     * The spread itself: once one job has tried the nearest floodfill, the job
     * sharing its skip set sees that peer and moves on to the next nearest.
     * With separate sets - what every job got before - the second job names the
     * same floodfill again and the second copy lands on the same peer.
     */
    @Test
    public void testSiblingJobsDoNotRepeatTheNearestPeer() {
        Hash nearest = peer('a');
        Set<Hash> shared = FloodfillNetworkDatabaseFacade.sharedSkipSet(null);
        StoreState first = job(shared);
        StoreState second = job(shared);
        first.addSkipped(nearest);
        assertTrue("the sibling job cannot see what its sibling tried",
                   second.getAttempted().contains(nearest));

        Set<Hash> none = FloodfillNetworkDatabaseFacade.sharedSkipSet(null);
        StoreState alone = job(none);
        Set<Hash> otherSet = FloodfillNetworkDatabaseFacade.sharedSkipSet(null);
        StoreState other = job(otherSet);
        alone.addSkipped(nearest);
        assertTrue(alone.getAttempted().contains(nearest));
        assertTrue("unshared sets must not leak",
                   other.getAttempted().isEmpty());
    }

    /**
     * The set a publish shares is written from several job threads, so adding
     * from two of them at once has to hold both peers and throw nothing. A
     * plain HashSet here is the defect this case checks for.
     *
     * @throws InterruptedException if a worker thread is interrupted while
     *                              joining, which would be an environment
     *                              problem rather than a failure of the set
     * @since 0.9.72+
     */
    @Test
    public void testSharedSetToleratesJobThreads() throws InterruptedException {
        Set<Hash> shared = FloodfillNetworkDatabaseFacade.sharedSkipSet(null);
        final AtomicBoolean failed = new AtomicBoolean();
        Thread one = new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    shared.add(peer((char) ('a' + (i % 20))));
                }
            } catch (RuntimeException e) {
                failed.set(true);
            }
        });
        Thread two = new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    shared.add(peer((char) ('A' + (i % 20))));
                    shared.contains(peer('a'));
                }
            } catch (RuntimeException e) {
                failed.set(true);
            }
        });
        one.start();
        two.start();
        one.join(10_000);
        two.join(10_000);
        assertTrue("a job thread threw on the shared set", !failed.get());
        assertEquals(40, shared.size());
    }
}
