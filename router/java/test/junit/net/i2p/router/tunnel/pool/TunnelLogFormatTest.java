package net.i2p.router.tunnel.pool;

import net.i2p.data.Hash;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the tunnel-pool log entries to the house style the router log audit
 * found them breaking: key: value rather than key=value, a capital after
 * "-> ", hashes truncated and bracketed, durations rather than raw epoch
 * milliseconds, and no emitted line over 100 characters.
 */
public class TunnelLogFormatTest {

    /**
     * A failed test on a busy tunnel keeps it, and the entry has to say why
     * without running onto one 165-character line.
     */
    @Test
    public void keepDataCarryingLineIsHouseStyle() {
        String entry = TestJob.keepDataCarryingLine("Outbound Client tunnel [skank.i2p / qiii4iqr]",
                                                    12345L, 1, 3, 45000L);
        assertEquals("Tunnel test failed -> Keeping a data-carrying tunnel" +
                     "\n* Tunnel: Outbound Client tunnel [skank.i2p / qiii4iqr]" +
                     "\n* Verified: 12345 bytes" +
                     " * Failures: 1/3 * Last transfer: 45000ms ago", entry);
        assertFalse("no key=value pairs: " + entry, entry.matches("(?s).*\\w+=\\w.*"));
    }

    /** A deferred retest names the pool size and the failures as values. */
    @Test
    public void thinPoolRetestLineIsHouseStyle() {
        assertEquals("Deferring the ASAP retest -> Pool is thin * Tunnels: 2 * Failures: 1",
                     TestJob.thinPoolRetestLine(2, 1));
    }

    /**
     * The expiration is always in the future, so a duration says what the
     * reader wants; the raw epoch the old line printed said nothing.
     */
    @Test
    public void expirationLineUsesDurationsNotEpochMillis() {
        long now = 1759480000000L;
        String entry = ExpireJob.expirationLine(4989139629878557547L, now + 600000L, now + 1200000L, now);
        assertEquals("Scheduled expiration for TunnelID 4989139629878557547" +
                     " -> Expires in: 10m * Drop in: 20m", entry);
        assertFalse("must not print raw epochs: " + entry, entry.contains(Long.toString(now)));
    }

    /** A missing client pool names the direction and truncates the destination. */
    @Test
    public void noPoolLineTruncatesTheDestination() {
        Hash dest = hash(1);
        String short8 = dest.toBase32().substring(0, 8);
        assertEquals("No inbound client pool for [" + short8 + "] -> No tunnel selected",
                     TunnelPoolManager.noPoolLine(true, dest));
        assertTrue("the direction must be spelled out: " + dest,
                   TunnelPoolManager.noPoolLine(false, dest).startsWith("No outbound client pool for ["));
    }

    /** The failure line is prose about what happened, not the method that noticed. */
    @Test
    public void noPeersSelectedLineIsProse() {
        assertEquals("No peers selected for harry.i2p (inbound pool) -> Cannot build a new tunnel",
                     TunnelPool.noPeersSelectedLine("harry.i2p", true));
        String outbound = TunnelPool.noPeersSelectedLine("[abcdefgh]", false);
        assertTrue("an outbound pool must say so: " + outbound,
                   outbound.startsWith("No peers selected for [abcdefgh] (outbound pool)"));
    }

    /** Every entry above is emitted as-is, so each of its lines has to fit. */
    @Test
    public void entriesStayWithinTheLineLimit() {
        long now = 1759480000000L;
        assertWithinLimit(TestJob.keepDataCarryingLine("Outbound Client tunnel [skank.i2p / qiii4iqr]",
                                                       1234567890L, 11, 33, 450000L));
        assertWithinLimit(TestJob.thinPoolRetestLine(2, 1));
        assertWithinLimit(ExpireJob.expirationLine(4989139629878557547L, now + 600000L, now + 1200000L, now));
        assertWithinLimit(TunnelPoolManager.noPoolLine(true, hash(2)));
        assertWithinLimit(TunnelPool.noPeersSelectedLine("[abcdefgh]", false));
    }

    /**
     * A hash with a known, distinct value, so the truncation the entries use
     * can be pinned exactly.
     */
    private static Hash hash(int b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        data[0] = (byte) b;
        data[Hash.HASH_LENGTH - 1] = (byte) (b + 1);
        return Hash.create(data);
    }

    /**
     * Asserts every emitted line of a multi-line entry is at most 100
     * characters, the house maximum.
     */
    private static void assertWithinLimit(String entry) {
        for (String line : entry.split("\n")) {
            assertTrue("line over 100 chars (" + line.length() + "): " + line, line.length() <= 100);
        }
    }
}
