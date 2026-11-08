package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.router.transport.udp.PacketBuilder2.Fragment;
import org.junit.Test;

/**
 * Pins the extent of one {@link PeerState2} sent-message expiry sweep.
 *
 * <p>The incremental cursor added in f0471ca7dd examined a bounded slice of a
 * per-tick copy of {@code _sentMessages} and stopped there. On an established
 * peer that was strictly worse than the single full pass it replaced: an
 * in-flight message is neither complete nor expired, so the scan never reached
 * its early exit and walked the whole snapshot anyway, while the copy cost an
 * allocation per tick and a removable entry could wait several ticks to free
 * its buffers. These tests hold the sweep to one pass over the live map.
 *
 * @since 0.9.72+
 */
public class PeerState2ExpireSweepTest {

    /** A fragment whose message is done (complete) and no longer needs the map. */
    private static Fragment complete() {
        OutboundMessageState state = mock(OutboundMessageState.class);
        when(state.isComplete()).thenReturn(true);
        return new Fragment(state, 0);
    }

    /** A fragment whose message timed out and is being dropped. */
    private static Fragment expired(long now) {
        OutboundMessageState state = mock(OutboundMessageState.class);
        when(state.isExpired(now)).thenReturn(true);
        return new Fragment(state, 0);
    }

    /** A fragment still waiting for its ACK, so its entry has to stay. */
    private static Fragment live() {
        OutboundMessageState state = mock(OutboundMessageState.class);
        return new Fragment(state, 0);
    }

    private static List<Fragment> frags(Fragment... fs) {
        return new ArrayList<>(Arrays.asList(fs));
    }

    /** An empty map is a no-op that examines nothing. */
    @Test
    public void emptyMapExaminesNothing() {
        Map<Long, List<Fragment>> map = new ConcurrentHashMap<>();
        assertEquals(0, PeerState2.expireSentMessages(map, 1000L, null));
        assertTrue(map.isEmpty());
    }

    /**
     * The regression guard: every removable entry is gone after ONE call, and
     * the sweep reports the whole map, not a batch of it.
     */
    @Test
    public void oneCallRemovesEveryExpiredEntry() {
        long now = 10_000L;
        Map<Long, List<Fragment>> map = new ConcurrentHashMap<>();
        for (long i = 1; i <= 200; i++) {
            map.put(i, frags(expired(now)));
        }
        assertEquals(200, PeerState2.expireSentMessages(map, now, null));
        assertTrue("removable entries survived the sweep: " + map.size(), map.isEmpty());
    }

    /** Complete (fully ACKed) messages are reaped the same way expired ones are. */
    @Test
    public void oneCallRemovesEveryCompleteEntry() {
        Map<Long, List<Fragment>> map = new ConcurrentHashMap<>();
        for (long i = 1; i <= 50; i++) {
            map.put(i, frags(complete()));
        }
        assertEquals(50, PeerState2.expireSentMessages(map, 1000L, null));
        assertTrue(map.isEmpty());
    }

    /**
     * An entry is only removed when EVERY fragment is finished, so a multi-fragment
     * message still waiting on one ACK keeps its buffers.
     */
    @Test
    public void entryWithOneLiveFragmentIsKept() {
        long now = 10_000L;
        Map<Long, List<Fragment>> map = new ConcurrentHashMap<>();
        map.put(1L, frags(complete(), expired(now), live()));
        map.put(2L, frags(expired(now), expired(now)));
        assertEquals(2, PeerState2.expireSentMessages(map, now, null));
        assertTrue(map.containsKey(1L));
        assertFalse(map.containsKey(2L));
    }

    /** A wholly live map is left alone, and the sweep still reports its extent. */
    @Test
    public void liveEntriesAreNeverDropped() {
        Map<Long, List<Fragment>> map = new ConcurrentHashMap<>();
        for (long i = 1; i <= 30; i++) {
            map.put(i, frags(live()));
        }
        assertEquals(30, PeerState2.expireSentMessages(map, 1000L, null));
        assertEquals(30, map.size());
    }

    /**
     * The removal is value-checked, so if the ACK path swapped the fragment list
     * out from under the sweep, the new list is not dropped with the old one.
     */
    @Test
    public void supersededListIsNotRemoved() {
        Map<Long, List<Fragment>> backing = new ConcurrentHashMap<>();
        List<Fragment> oldFrags = frags(complete());
        backing.put(7L, oldFrags);
        // The sweep iterates a snapshot taken before the ACK path replaced the value.
        Map<Long, List<Fragment>> stale = new StaleSnapshotMap(backing);
        List<Fragment> newFrags = frags(live());
        backing.put(7L, newFrags);

        PeerState2.expireSentMessages(stale, 1000L, null);
        assertTrue("the replacement list was dropped", backing.containsKey(7L));
        assertSame(newFrags, backing.get(7L));
    }

    /**
     * A sweep over a large live map stays proportional to the map: it walks it
     * once and allocates nothing, which is what the cursor version lost.
     */
    @Test
    public void sweepIsOnePassOverTheLiveMap() {
        Map<Long, List<Fragment>> map = new LinkedHashMap<>();
        for (long i = 1; i <= 1000; i++) {
            map.put(i, frags(live()));
        }
        CountingMap counter = new CountingMap(map);
        assertEquals(1000, PeerState2.expireSentMessages(counter, 1000L, null));
        assertEquals("entrySet() was copied more than once", 1, counter.copies);
        assertEquals("the map was mutated while nothing was removable", 0, counter.removes);
    }

    /** Entry set is a snapshot of the backing map, to model a concurrent replace. */
    private static class StaleSnapshotMap extends AbstractMap<Long, List<Fragment>> {
        private final Map<Long, List<Fragment>> _backing;
        private final Set<Entry<Long, List<Fragment>>> _snapshot;

        StaleSnapshotMap(Map<Long, List<Fragment>> backing) {
            _backing = backing;
            _snapshot = new LinkedHashSet<>(backing.entrySet());
        }

        @Override
        public Set<Entry<Long, List<Fragment>>> entrySet() {
            return _snapshot;
        }

        @Override
        public boolean remove(Object key, Object value) {
            return _backing.remove(key, value);
        }
    }

    /** Counts how often the sweep copies the entry set and how often it removes. */
    private static class CountingMap extends AbstractMap<Long, List<Fragment>> {
        private final Map<Long, List<Fragment>> _backing;
        private int copies;
        private int removes;

        CountingMap(Map<Long, List<Fragment>> backing) {
            _backing = backing;
        }

        @Override
        public Set<Entry<Long, List<Fragment>>> entrySet() {
            copies++;
            return new LinkedHashSet<>(_backing.entrySet());
        }

        @Override
        public List<Fragment> remove(Object key) {
            removes++;
            return _backing.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            removes++;
            return _backing.remove(key, value);
        }
    }

    /** Sanity check on the fixtures: a live fragment is neither done nor late. */
    @Test
    public void fixturesAreDistinct() {
        long now = 1000L;
        OutboundMessageState live = mock(OutboundMessageState.class);
        assertFalse(live.isComplete());
        assertFalse(live.isExpired(now));
    }
}
