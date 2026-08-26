package org.klomp.snark;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.junit.Test;

/**
 * Test the multi-dest auto-start delay and persisted running-state
 * filter in SnarkManager.
 *
 * @since 0.9.71+
 */
public class SnarkManagerTest {

    private static final long MIN_DELAY = 30L * 1000;
    private static final long MAX_DELAY = 90L * 1000;

    @Test
    public void testStartDelayBounds() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long delay = SnarkManager.startDelay(rnd);
            assertTrue("delay " + delay, delay >= MIN_DELAY && delay <= MAX_DELAY);
        }
    }

    @Test
    public void testStartDelayVaried() {
        HashSet<Long> seen = new HashSet<>();
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            seen.add(SnarkManager.startDelay(rnd));
        }
        assertTrue("expected multiple distinct delays, got " + seen.size(), seen.size() > 1);
    }

    /**
     * countRealFiles excludes top-level BEP 47 padding files (.pad/_pad) from
     * the max files per torrent count, so a padded torrent is not rejected
     * because of its synthetic zero files. Padding nested below the root is a
     * regular file.
     */
    @Test
    public void testCountRealFiles() {
        List<List<String>> files = new ArrayList<>();
        files.add(Arrays.asList("a.dat"));
        files.add(Arrays.asList(".pad", "64536"));
        files.add(Arrays.asList("b.dat"));
        files.add(Arrays.asList("_pad", "64536-2"));
        assertEquals(2, SnarkManager.countRealFiles(files));

        List<List<String>> allPad = new ArrayList<>();
        allPad.add(Arrays.asList(".pad", "0"));
        allPad.add(Arrays.asList("_pad", "1"));
        assertEquals(0, SnarkManager.countRealFiles(allPad));

        List<List<String>> nested = new ArrayList<>();
        nested.add(Arrays.asList("dir", ".pad", "1"));
        assertEquals(1, SnarkManager.countRealFiles(nested));

        assertEquals(0, SnarkManager.countRealFiles(null));
    }

    /**
     * parseShouldPadFiles reads the config property with a false default,
     * so an absent or unparsable value disables padding.
     */
    @Test
    public void testParseShouldPadFiles() {
        assertFalse(SnarkManager.parseShouldPadFiles(new Properties()));
        Properties yes = new Properties();
        yes.setProperty(SnarkManager.PROP_SHOULD_PAD_FILES, "true");
        assertTrue(SnarkManager.parseShouldPadFiles(yes));
        Properties no = new Properties();
        no.setProperty(SnarkManager.PROP_SHOULD_PAD_FILES, "false");
        assertFalse(SnarkManager.parseShouldPadFiles(no));
        Properties garbage = new Properties();
        garbage.setProperty(SnarkManager.PROP_SHOULD_PAD_FILES, "maybe");
        assertFalse(SnarkManager.parseShouldPadFiles(garbage));
    }

    /**
     * wasPreviouslyRunning decodes the persisted PROP_META_RUNNING flag.
     * null (missing entry) counts as previously running to match the
     * auto-start default; explicit "false" means the torrent was stopped.
     * The round-trip contract: locked_saveTorrentStatus writes "true"
     * on start (stopped=false) and "false" on stop (stopped=true);
     * wasPreviouslyRunning must agree with both.
     */
    @Test
    public void testWasPreviouslyRunning() {
        // null / absent property → defaults to running (auto-start)
        assertTrue(SnarkManager.wasPreviouslyRunning(new Properties()));
        // explicit "true" → running
        Properties running = new Properties();
        running.setProperty(SnarkManager.PROP_META_RUNNING, "true");
        assertTrue(SnarkManager.wasPreviouslyRunning(running));
        // explicit "false" → stopped
        Properties stopped = new Properties();
        stopped.setProperty(SnarkManager.PROP_META_RUNNING, "false");
        assertFalse(SnarkManager.wasPreviouslyRunning(stopped));
        // garbage value → Boolean.parseBoolean returns false → stopped
        Properties garbage = new Properties();
        garbage.setProperty(SnarkManager.PROP_META_RUNNING, "maybe");
        assertFalse(SnarkManager.wasPreviouslyRunning(garbage));
    }
}
