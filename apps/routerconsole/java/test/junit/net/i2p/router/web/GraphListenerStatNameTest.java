package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 *  Tests for {@link GraphListener}'s datasource id to stat name map.
 *
 *  <p>The id is a 20-character hash of the stat name and period, minted because JRobin
 *  caps datasource names at 20 characters; it cannot be inverted, so a log line quoting
 *  one on its own names nothing an operator can look up. The map is what makes the
 *  stalled-listener report say "jobQueue.jobLag.60000" instead of "BOPP6eO8n6WPbvCXIZzQ".
 *
 *  <p>Only the static map is under test: {@code GraphListener} itself needs a router
 *  context to open an RRD, and registration happens at that point.
 *
 *  @since 0.9.71+
 */
public class GraphListenerStatNameTest {

    /**
     *  An id nothing registered: pass it through unchanged.
     *
     *  <p>Falling back is deliberate. An unknown id is still evidence - it says some
     *  datasource exists that this console never opened - and logging the id beats
     *  logging nothing or throwing in the middle of a fault report.
     */
    @Test
    public void unknownIdIsLoggedAsItself() {
        assertEquals("NoSuchStat0000000000",
                     GraphListener.statName("NoSuchStat0000000000"));
    }

    /** A registered id resolves to the stat name and period it was minted from. */
    @Test
    public void registeredIdResolvesToItsStatName() {
        GraphListener.registerName("StatNameTestJobQueueL", "jobQueue.jobLag.60000");
        assertEquals("jobQueue.jobLag.60000",
                     GraphListener.statName("StatNameTestJobQueueL"));
    }

    /** The event datasource answers to its own name, not the value datasource's. */
    @Test
    public void eventDatasourceResolvesSeparately() {
        GraphListener.registerName("StatNameTestValueId00", "tunnel.buildSuccess.60000");
        GraphListener.registerName("StatNameTestEventId00", "tunnel.buildSuccess.60000.events");
        assertEquals("tunnel.buildSuccess.60000",
                     GraphListener.statName("StatNameTestValueId00"));
        assertEquals("tunnel.buildSuccess.60000.events",
                     GraphListener.statName("StatNameTestEventId00"));
    }

    /** Registering one id leaves the rest of the map alone. */
    @Test
    public void registrationDoesNotDisturbOtherIds() {
        GraphListener.registerName("StatNameTestKeepId000", "peer.fastPeerCount.60000");
        GraphListener.registerName("StatNameTestId0000001", "peer.activeProfileCount.60000");
        assertEquals("peer.fastPeerCount.60000",
                     GraphListener.statName("StatNameTestKeepId000"));
        assertEquals("peer.activeProfileCount.60000",
                     GraphListener.statName("StatNameTestId0000001"));
    }

    /**
     *  Re-registering the id of a rebuilt listener replaces the entry in place.
     *
     *  <p>A listener rebuild mints the same id from the same stat, so the map must hold
     *  one entry per stat rather than growing one per rebuild.
     */
    @Test
    public void rebuildingAListenerKeepsOneEntryPerStat() {
        GraphListener.registerName("StatNameTestRebuildId", "bw.recvRate.60000");
        GraphListener.registerName("StatNameTestRebuildId", "bw.recvRate.60000");
        assertEquals("bw.recvRate.60000", GraphListener.statName("StatNameTestRebuildId"));
    }

    /** A null id is passed through rather than throwing from the fault report. */
    @Test
    public void nullIdPassesThrough() {
        assertNull(GraphListener.statName(null));
    }

    /** A half-known registration is ignored rather than poisoning the map. */
    @Test
    public void incompleteRegistrationIsIgnored() {
        GraphListener.registerName(null, "jobQueue.jobWait.60000");
        GraphListener.registerName("StatNameTestNoNameId0", null);
        assertEquals("StatNameTestNoNameId0", GraphListener.statName("StatNameTestNoNameId0"));
    }
}
