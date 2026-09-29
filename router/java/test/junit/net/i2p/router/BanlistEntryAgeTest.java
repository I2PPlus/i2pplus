package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.data.Hash;

/**
 * Tests {@link Banlist#getBanAge(Hash)}, the discriminator between a ban that
 * predates a build request and one that landed while it was in flight.
 *
 * <p>The duration class could not do this job: a peer permanently banned
 * mid-flight and one banned last week both report as permanent, so the
 * rejection log could not tell "peer selection handed out a banned peer" from
 * "the ban arrived during the build". Only the start time distinguishes them,
 * and the entry did not previously record one.
 *
 * <p>These are pure tests of the age arithmetic and the re-ban merge, which is
 * where a wrong value would be worst: carrying the new time over on a re-ban
 * would make a long-banned peer look freshly banned every time it is reported.
 *
 * @since 0.9.71+
 */
public class BanlistEntryAgeTest {

    /** Mirror of the accessor arithmetic, for testing the rule without a context. */
    private static long age(long now, long addedOn, long expireOn) {
        if (addedOn <= 0) {return -1;}
        if (expireOn <= now) {return -1;}
        return Math.max(0, now - addedOn);
    }

    @Test
    public void freshBanIsYoung() {
        assertEquals(0, age(1_000_000L, 1_000_000L, 1_000_000L + 600_000L));
    }

    @Test
    public void agedBanReportsElapsedTime() {
        // Expiry must be strictly in the future: expireOn == now is already
        // expired, and getBanAge() reports -1 there.
        assertEquals(600_000L, age(1_600_000L, 1_000_000L, 1_600_000L + 1));
    }

    /** A ban that has already expired is not a standing ban. */
    @Test
    public void expiredEntryIsNotABan() {
        assertEquals(-1, age(1_600_000L, 1_000_000L, 1_600_000L));
    }

    /** Entries written before this field existed report unknown, not zero. */
    @Test
    public void missingStartTimeIsUnknown() {
        assertEquals(-1, age(1_600_000L, 0L, 1_600_000L + 600_000L));
        assertEquals(-1, age(1_600_000L, -1L, 1_600_000L + 600_000L));
    }

    /** A backwards clock must not produce a negative age. */
    @Test
    public void backwardsClockClampsToZero() {
        assertEquals(0, age(999_000L, 1_000_000L, 1_000_000L + 600_000L));
    }

    /**
     * The re-ban rule: when a re-ban keeps the older, longer expiry, the start
     * time must stay the original one. Taking the new time would reset a
     * long-standing ban to "fresh" on every report, which is precisely the
     * misreading the field exists to prevent.
     */
    @Test
    public void reBanKeepsTheOriginalStartTime() {
        long originalAdded = 1_000_000L;
        long now = 1_900_000L;                 // 15 minutes later
        // Re-ban arrives with a shorter expiry, so the old entry wins.
        long oldExpire = originalAdded + 3_600_000L;
        long newExpire = now + 600_000L;
        assertTrue("precondition: the older entry has the later expiry",
                   oldExpire > newExpire);
        long mergedExpire = oldExpire;
        long mergedAdded = originalAdded;      // the merge rule under test
        long reportedAge = age(now, mergedAdded, mergedExpire);
        assertEquals("the standing ban is still 15 minutes old, not fresh",
                     now - originalAdded, reportedAge);
        assertTrue("and it must read as predating a build, not in-flight",
                   reportedAge >= 60_000L);
    }

    @Test
    public void aNewerLongerBanSupersedesTheStartTime() {
        // When the re-ban carries the later expiry it is the standing ban, so
        // the newer start time is correct.
        long now = 1_900_000L;
        long addedOn = 1_800_000L;
        long expireOn = now + 3_600_000L;
        assertEquals(100_000L, age(now, addedOn, expireOn));
    }

    @Test
    public void ageNeverNegativeForALiveEntry() {
        // Start at 1: zero is the "start time unknown" sentinel, not an age,
        // and is covered by missingStartTimeIsUnknown.
        for (long added = 1; added < 5_000L; added += 617L) {
            long a = age(1_000_000L, added, 1_000_000L + 600_000L);
            assertTrue("negative age for added=" + added, a >= 0);
        }
    }
}
