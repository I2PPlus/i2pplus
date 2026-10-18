package net.i2p.router.peermanager;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

/**
 * Tests for {@link ProfilePersistenceHelper#purgeCapacityBonus} — the
 * on-load removal of persisted capacity-bonus keys, so a manual bonus
 * never survives a router restart and stale values from old profile
 * files cannot be revived.
 *
 * @since 0.9.71+
 */
public class ProfilePersistencePurgeTest {

    @Test
    public void removesCapacityBonusKeys() {
        Properties props = new Properties();
        props.setProperty("capacityBonus", "-30");
        props.setProperty("capacityBonusLastUpdate", "12345");
        props.setProperty("speedBonus", "50");
        ProfilePersistenceHelper.purgeCapacityBonus(props);
        assertNull(props.getProperty("capacityBonus"));
        assertNull(props.getProperty("capacityBonusLastUpdate"));
        assertEquals("50", props.getProperty("speedBonus"));
    }

    @Test
    public void toleratesMissingKeys() {
        Properties props = new Properties();
        props.setProperty("integrationBonus", "7");
        ProfilePersistenceHelper.purgeCapacityBonus(props);
        assertEquals("7", props.getProperty("integrationBonus"));
        assertEquals(1, props.size());
    }
}
