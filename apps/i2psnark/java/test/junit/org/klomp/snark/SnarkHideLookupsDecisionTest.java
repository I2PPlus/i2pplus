package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Properties;

import org.junit.Test;

/**
 * Tests for the two decisions behind i2psnark.hideLookups: how the config
 * value is parsed, and whether a torrent is listed at all.
 *
 * <p>A zzzot lookup asks the DHT only for a torrent's name and size, so it is
 * not a download the user started, and while the zzzot hashlist page is open
 * lookups appear and vanish from the torrent list between refreshes. Hiding
 * them is config-only, with no config-page control, so the config file is the
 * only thing that documents the key - which is why the key name is pinned
 * here as well as the behaviour.
 *
 * @since 0.9.72+
 */
public class SnarkHideLookupsDecisionTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the decisions under test are static and carry no state.
     *
     * @since 0.9.72+
     */
    public SnarkHideLookupsDecisionTest() {}

    /**
     * The config key is the one documented in i2psnark.config, so a reader
     * copying the commented example gets a key the code reads.
     */
    @Test
    public void testPropNameIsTheDocumentedKey() {
        assertEquals("i2psnark.hideLookups", SnarkManager.PROP_HIDE_LOOKUPS);
        assertEquals("false", SnarkManager.DEFAULT_HIDE_LOOKUPS);
    }

    /**
     * With no property set, lookups stay visible: the default must not change
     * what an existing install shows.
     */
    @Test
    public void testAbsentPropKeepsLookupsVisible() {
        assertFalse(SnarkManager.parseHideLookups(new Properties()));
    }

    /**
     * The property is read as a boolean, so the documented example value and
     * its opposite both work.
     */
    @Test
    public void testPropParsedAsBoolean() {
        assertTrue(SnarkManager.parseHideLookups(props(SnarkManager.PROP_HIDE_LOOKUPS, "true")));
        assertFalse(SnarkManager.parseHideLookups(props(SnarkManager.PROP_HIDE_LOOKUPS, "false")));
    }

    /**
     * Anything that is not "true" leaves lookups visible, including an empty
     * value and a typo, so a broken config line cannot hide torrents.
     */
    @Test
    public void testUnparsablePropKeepsLookupsVisible() {
        assertFalse(SnarkManager.parseHideLookups(props(SnarkManager.PROP_HIDE_LOOKUPS, "")));
        assertFalse(SnarkManager.parseHideLookups(props(SnarkManager.PROP_HIDE_LOOKUPS, "yes")));
        assertFalse(SnarkManager.parseHideLookups(props("i2psnark.hidelookups", "true")));
    }

    /**
     * A lookup is listed only when hiding is off, and a user torrent is always
     * listed: hiding lookups must never hide a download.
     */
    @Test
    public void testListedTruthTable() {
        assertTrue(SnarkManager.isListed(false, false));
        assertTrue(SnarkManager.isListed(false, true));
        assertTrue(SnarkManager.isListed(true, false));
        assertFalse("a lookup is hidden when the config hides lookups",
                    SnarkManager.isListed(true, true));
    }

    /**
     * The decision composed with the real lookup predicate: the name and the
     * temp dir a zzzot lookup is built from are hidden, and a user's torrent
     * sitting beside them is not.
     */
    @Test
    public void testRealNamesComposeWithThePredicate() {
        File lookupDir = new File("/tmp/zzzot-lookup-1234567890");
        File dataDir = new File("/home/user/i2psnark/Ubuntu 24.04");
        assertTrue(SnarkManager.isListed(Snark.isLookupTorrent("Lookup [AE42B6A9]", lookupDir), false));
        assertFalse(SnarkManager.isListed(Snark.isLookupTorrent("Lookup [AE42B6A9]", lookupDir), true));
        assertTrue(SnarkManager.isListed(Snark.isLookupTorrent("Ubuntu 24.04.iso", dataDir), true));
    }

    /**
     * One-property properties, for the parse tests.
     *
     * @param key the property name
     * @param value the property value
     * @return properties carrying just that pair
     */
    private static Properties props(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        return props;
    }
}
