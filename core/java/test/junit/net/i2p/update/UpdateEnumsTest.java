package net.i2p.update;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for the UpdateType and UpdateMethod enums: name/valueOf
 *  round-trip serialization and the documented constant set.
 *  These enums are persisted in config and exchanged between
 *  Checker/Updater implementations, so a missing or reordered
 *  constant would silently break update configuration.
 *
 *  @since 0.9.4
 */
public class UpdateEnumsTest {

    @Test
    public void testUpdateTypeRoundTrip() {
        for (UpdateType t : UpdateType.values()) {
            assertEquals(t, UpdateType.valueOf(t.name()));
            assertNotNull(t.name());
        }
    }

    @Test
    public void testUpdateTypeConstantSet() {
        // these are referenced by the console and update sources
        assertTrue(has(UpdateType.ROUTER_SIGNED));
        assertTrue(has(UpdateType.ROUTER_UNSIGNED));
        assertTrue(has(UpdateType.ROUTER_SIGNED_SU3));
        assertTrue(has(UpdateType.ROUTER_DEV_SU3));
        assertTrue(has(UpdateType.PLUGIN));
        assertTrue(has(UpdateType.GEOIP));
        assertTrue(has(UpdateType.BLOCKLIST));
        assertTrue(has(UpdateType.NEWS_SU3));
        assertTrue(has(UpdateType.API));
    }

    @Test
    public void testUpdateTypeValues() {
        assertEquals(14, UpdateType.values().length);
    }

    @Test
    public void testUpdateMethodRoundTrip() {
        for (UpdateMethod m : UpdateMethod.values()) {
            assertEquals(m, UpdateMethod.valueOf(m.name()));
            assertNotNull(m.name());
        }
    }

    @Test
    public void testUpdateMethodConstantSet() {
        assertTrue(has(UpdateMethod.HTTP));
        assertTrue(has(UpdateMethod.HTTP_CLEARNET));
        assertTrue(has(UpdateMethod.HTTPS_CLEARNET));
        assertTrue(has(UpdateMethod.TORRENT));
        assertTrue(has(UpdateMethod.FILE));
    }

    @Test
    public void testUpdateMethodValues() {
        assertEquals(10, UpdateMethod.values().length);
    }

    private static boolean has(UpdateType t) {
        for (UpdateType x : UpdateType.values()) {
            if (x == t) return true;
        }
        return false;
    }

    private static boolean has(UpdateMethod m) {
        for (UpdateMethod x : UpdateMethod.values()) {
            if (x == m) return true;
        }
        return false;
    }
}
