package net.i2p.router.networkdb.kademlia;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.i2p.data.Hash;
import net.i2p.kademlia.KBucket;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.util.Clock;

/**
 * Tests {@link StaleContactTrimmer}: a full bucket evicts the entry with the
 * oldest contact evidence, but only past the grace window; otherwise the
 * newcomer is rejected.
 *
 * @since 0.9.71
 */
public class StaleContactTrimmerTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 60 * 60 * 1000L;
    private static final Random RANDOM = new Random();

    private static Hash randomHash() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        RANDOM.nextBytes(data);
        return new Hash(data);
    }

    @SuppressWarnings("unchecked")
    private KBucket<Hash> bucketOf(Hash... entries) {
        KBucket<Hash> bucket = mock(KBucket.class);
        Set<Hash> set = new LinkedHashSet<>();
        for (Hash h : entries) {set.add(h);}
        when(bucket.getEntries()).thenReturn(set);
        when(bucket.remove(any(Hash.class))).thenReturn(true);
        return bucket;
    }

    private RouterContext context(ProfileOrganizer organizer) {
        RouterContext ctx = mock(RouterContext.class);
        Clock clock = mock(Clock.class);
        when(ctx.clock()).thenReturn(clock);
        when(clock.now()).thenReturn(NOW);
        when(ctx.profileOrganizer()).thenReturn(organizer);
        when(ctx.commSystem()).thenReturn(mock(CommSystemFacade.class));
        return ctx;
    }

    @Test
    public void evictsLeastRecentlySeenWhenStale() {
        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        Hash fresh = randomHash(), stale = randomHash(), middling = randomHash();
        profile(organizer, fresh, NOW - HOUR);
        profile(organizer, stale, NOW - 10 * 24 * HOUR);
        profile(organizer, middling, NOW - 5 * 24 * HOUR);
        KBucket<Hash> bucket = bucketOf(fresh, stale, middling);
        StaleContactTrimmer trimmer = new StaleContactTrimmer(context(organizer));
        assertTrue(trimmer.trim(bucket, randomHash()));
        verify(bucket).remove(stale);
        verify(bucket, never()).remove(fresh);
    }

    @Test
    public void rejectsWhenAllEntriesFresh() {
        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        Hash a = randomHash(), b = randomHash();
        profile(organizer, a, NOW - HOUR);
        profile(organizer, b, NOW - 2 * HOUR);
        KBucket<Hash> bucket = bucketOf(a, b);
        StaleContactTrimmer trimmer = new StaleContactTrimmer(context(organizer));
        assertFalse(trimmer.trim(bucket, randomHash()));
        verify(bucket, never()).remove(any(Hash.class));
    }

    @Test
    public void entryWithoutProfileEvictedFirst() {
        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        Hash fresh = randomHash(), unknown = randomHash();
        profile(organizer, fresh, NOW - HOUR);
        when(organizer.getProfileNonblocking(unknown)).thenReturn(null);
        KBucket<Hash> bucket = bucketOf(fresh, unknown);
        StaleContactTrimmer trimmer = new StaleContactTrimmer(context(organizer));
        assertTrue(trimmer.trim(bucket, randomHash()));
        verify(bucket).remove(unknown);
    }

    @Test
    public void graceWindowProtectsExactlyAtBoundary() {
        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        Hash atBoundary = randomHash();
        profile(organizer, atBoundary, NOW - StaleContactTrimmer.GRACE);
        KBucket<Hash> bucket = bucketOf(atBoundary);
        StaleContactTrimmer trimmer = new StaleContactTrimmer(context(organizer));
        assertFalse(trimmer.trim(bucket, randomHash()));
    }

    @Test
    public void establishedPeerNeverEvicted() {
        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        Hash staleButConnected = randomHash(), staleUnconnected = randomHash();
        profile(organizer, staleButConnected, NOW - 10 * 24 * HOUR);
        profile(organizer, staleUnconnected, NOW - 5 * 24 * HOUR);
        RouterContext ctx = context(organizer);
        when(ctx.commSystem().isEstablished(staleButConnected)).thenReturn(true);
        KBucket<Hash> bucket = bucketOf(staleButConnected, staleUnconnected);
        StaleContactTrimmer trimmer = new StaleContactTrimmer(ctx);
        assertTrue(trimmer.trim(bucket, randomHash()));
        // the connected peer is skipped even though it is the oldest
        verify(bucket).remove(staleUnconnected);
        verify(bucket, never()).remove(staleButConnected);
    }

    @Test
    public void allConnectedRejectsNewcomer() {
        ProfileOrganizer organizer = mock(ProfileOrganizer.class);
        Hash a = randomHash(), b = randomHash();
        profile(organizer, a, NOW - 10 * 24 * HOUR);
        profile(organizer, b, NOW - 10 * 24 * HOUR);
        RouterContext ctx = context(organizer);
        when(ctx.commSystem().isEstablished(a)).thenReturn(true);
        when(ctx.commSystem().isEstablished(b)).thenReturn(true);
        KBucket<Hash> bucket = bucketOf(a, b);
        StaleContactTrimmer trimmer = new StaleContactTrimmer(ctx);
        assertFalse(trimmer.trim(bucket, randomHash()));
        verify(bucket, never()).remove(any(Hash.class));
    }

    private void profile(ProfileOrganizer organizer, Hash peer, long lastSeen) {
        PeerProfile prof = mock(PeerProfile.class);
        when(prof.getLastHeardAbout()).thenReturn(lastSeen);
        when(prof.getLastHeardFrom()).thenReturn(0L);
        when(prof.getLastSendSuccessful()).thenReturn(0L);
        when(organizer.getProfileNonblocking(peer)).thenReturn(prof);
    }
}
