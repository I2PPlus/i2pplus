package org.klomp.snark;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the peer-discovery decisions that decide how fast a magnet hears from someone.
 *
 * <p>Context these pin. On a default-configured router every tracker announce failed
 * immediately: the announce path required a per-torrent destination that is only created
 * when {@code multiDest} is on, and it is off by default, so all thirteen default
 * {@code http://} open trackers returned null without touching the network. The only
 * remaining source was the DHT, whose routing table was empty. On top of that the first
 * announce was delayed by a random 0-90s sleep, and the DHT fetch was queued behind
 * every tracker announce. The result was minutes, which is the symptom these tests stop
 * from coming back.
 *
 * @since 0.9.71+
 */
public class TrackerClientDiscoveryDecisionTest {

    private static final int INITIAL_SLEEP = 90_000;

    /////////////// first-announce delay

    @Test
    public void aTorrentWithMetaKeepsTheFullSpread() {
        // The spread protects the trackers from a batch start; that concern is real and
        // must not be weakened for torrents that have their metainfo.
        assertEquals(45_000L, TrackerClient.initialAnnounceDelayMs(true, 45_000, 2_000));
    }

    @Test
    public void aMagnetAnnouncesAlmostImmediately() {
        assertEquals("a magnet is the lookup case and must not sleep it out",
                     2_000L, TrackerClient.initialAnnounceDelayMs(false, 45_000, 2_000));
    }

    @Test
    public void magnetDelayIsFarBelowTheLookupItServes() {
        long magnetDelay = TrackerClient.initialAnnounceDelayMs(false, 0, TrackerClient.MAGNET_INITIAL_SLEEP);
        assertTrue("a lookup budget is minutes at most; the magnet delay is "
                   + magnetDelay + "ms",
                   magnetDelay < 10_000);
        assertTrue("the magnet delay must be a real reduction, not the full window",
                   TrackerClient.MAGNET_INITIAL_SLEEP < INITIAL_SLEEP);
    }

    @Test
    public void magnetDelayStillSpreadsABatch() {
        // Zero would let a batch of magnets added at once announce in lockstep.
        assertTrue(TrackerClient.MAGNET_INITIAL_SLEEP > 0);
    }

    /////////////// falling back to a different transport

    @Test
    public void aFruitlessRoundTriesTheFallbackTransport() {
        assertTrue("every announce failed, so the only remaining option is another transport",
                   TrackerClient.shouldTryFallbackTrackers(0, true, true));
    }

    @Test
    public void aSuccessfulRoundDoesNotAlsoTryFallbacks() {
        assertFalse("no reason to double-announce once peers are arriving",
                    TrackerClient.shouldTryFallbackTrackers(5, true, true));
    }

    @Test
    public void noFallbackConfiguredMeansNoFallbackRound() {
        assertFalse(TrackerClient.shouldTryFallbackTrackers(0, false, true));
    }

    @Test
    public void aTorrentThatWantsNoPeersDoesNotTryFallbacks() {
        assertFalse("a seed that is not looking for peers must not announce",
                    TrackerClient.shouldTryFallbackTrackers(0, true, false));
    }

    @Test
    public void aSeedNeverUsesBackupsWhilePrimaryTrackersExist() {
        // Pre-existing contract, kept explicit so the new path cannot weaken it.
        assertFalse(TrackerClient.needBackupTrackers(true, true, true, 0, false));
    }

    @Test
    public void aTrackerlessDownloaderStillUsesBackups() {
        assertTrue(TrackerClient.needBackupTrackers(false, true, true, 0, true));
    }
}