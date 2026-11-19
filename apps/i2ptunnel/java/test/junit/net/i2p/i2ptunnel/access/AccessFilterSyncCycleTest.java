package net.i2p.i2ptunnel.access;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.KeyCertificate;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;

/**
 * One disk cycle of the tunnel access filter: record what was counted, then re-read
 * the access lists, each half on its own.
 *
 * <p>The defect: {@code record()} and {@code reload()} shared one try, so an
 * {@code IOException} from the recorder — a read-only directory, a full disk, a file
 * another process holds — skipped the reload for that cycle, and while the recorder
 * stayed unwritable the deny and throttle lists were never re-read again for the life
 * of the tunnel. The operator edited the list, the filter kept enforcing the old one,
 * and the only symptom was a CRIT line every five seconds.
 *
 * @since 0.9.72+
 */
public class AccessFilterSyncCycleTest {

    /** The rule both the filter and its recorder apply: one connection per second. */
    private static final Threshold ONE_PER_SECOND = new Threshold(1, 1);

    /** Prefix of the scratch directories the recorder files are written under. */
    private static final String SCRATCH = System.getProperty("java.io.tmpdir") + "/opencode-af-";

    /**
     * A fixture group for the filter's disk cycle; it holds no state.
     *
     * @since 0.9.72+
     */
    public AccessFilterSyncCycleTest() {}

    /**
     * A definition element that names no destinations and counts how often the filter
     * re-read it, which is how the reload is observed.
     *
     * @since 0.9.72+
     */
    private static class CountingElement extends FilterDefinitionElement {

        /** Times this element has been re-read. */
        private int updates;

        /** Builds an element applying the deny rule to nothing. */
        CountingElement() {
            super(Threshold.DENY);
        }

        /**
         * Counts the re-read; the element names no destinations, so it adds none.
         *
         * @param map the destination-to-tracker map to add to
         * @param recorderConnections the widest recorder rule of the definition
         */
        @Override
        void update(Map<Hash, DestTracker> map, int recorderConnections) {
            updates++;
        }

        /**
         * @return times this element has been re-read
         */
        int getUpdates() {
            return updates;
        }
    }

    /**
     * A definition element that reads fine once, when the filter is built, and fails
     * every re-read after that, standing in for a list file that went away.
     *
     * @since 0.9.72+
     */
    private static class FailingElement extends FilterDefinitionElement {

        /** Times this element has been re-read, the first being the filter's startup. */
        private int updates;

        /** Builds an element that will fail its second re-read. */
        FailingElement() {
            super(Threshold.DENY);
        }

        /**
         * Succeeds on the startup read and throws on every re-read after it.
         *
         * @param map the destination-to-tracker map to add to
         * @param recorderConnections the widest recorder rule of the definition
         * @throws IOException on every re-read after the first
         */
        @Override
        void update(Map<Hash, DestTracker> map, int recorderConnections) throws IOException {
            updates++;
            if (updates > 1)
                throw new IOException("the list file is gone");
        }
    }

    /**
     * The defect: a recorder that cannot be written sat in front of the reload in the
     * same try, so the lists the operator edits were never re-read again.
     *
     * @throws IOException if the filter cannot be built
     */
    @Test
    public void testABrokenRecorderDoesNotStopTheReload() throws IOException {
        CountingElement element = new CountingElement();
        AccessFilter filter = filter(element, new File(noSuchDir(), "breach.txt"));
        int seenBefore = element.getUpdates();
        breach(filter);
        filter.syncCycle();
        assertTrue("the access lists were not re-read because the recorder failed",
                   element.getUpdates() > seenBefore);
    }

    /**
     * The other half of the split: a list that cannot be re-read must not stop the
     * recorder writing what it counted, or a broken deny file silently stops the
     * breach log as well.
     *
     * @throws IOException if the filter cannot be built or its scratch directory
     *                     cannot be created
     */
    @Test
    public void testAFailedReloadDoesNotStopTheRecorder() throws IOException {
        File recorder = new File(scratchDir("recorder"), "breach.txt");
        recorder.deleteOnExit();
        AccessFilter filter = filter(new FailingElement(), recorder);
        breach(filter);
        filter.syncCycle();
        assertTrue("the recorder did not write although only the reload failed", recorder.exists());
    }

    /**
     * Stopping cancels both timers and starting again installs one of each, so the
     * documented reopen path cannot leave two purge timers running for the life of
     * the tunnel.
     *
     * @throws IOException if the filter cannot be built or its scratch directory
     *                     cannot be created
     */
    @Test
    public void testARestartHasOneOfEachTimer() throws IOException {
        AccessFilter filter = filter(new CountingElement(),
                                     new File(scratchDir("timers"), "breach.txt"));
        filter.start();
        Object purgeTimer = filter.purger;
        Object syncTimer = filter.syncer;
        assertNotNull(purgeTimer);
        assertNotNull(syncTimer);
        filter.stop();
        assertNull(filter.purger);
        assertNull(filter.syncer);
        filter.start();
        assertNotSame(purgeTimer, filter.purger);
        assertNotSame(syncTimer, filter.syncer);
        filter.stop();
    }

    /**
     * @param element the one rule of the definition, observed by the tests
     * @param recorderFile where the recorder writes breaching destinations
     * @return a filter over that definition, with its timers not yet started
     * @throws IOException if the filter cannot read its definition
     */
    private static AccessFilter filter(FilterDefinitionElement element,
                                       File recorderFile) throws IOException {
        FilterDefinition def = new FilterDefinition(ONE_PER_SECOND,
                new FilterDefinitionElement[] {element},
                new Recorder[] {new Recorder(recorderFile, ONE_PER_SECOND)});
        return new AccessFilter(I2PAppContext.getGlobalContext(), def);
    }

    /**
     * Two connections from one destination, enough to breach the one-per-second
     * rule the recorder writes on.
     *
     * @param filter the filter to feed
     */
    private static void breach(AccessFilter filter) {
        Destination d = destination();
        filter.allowDestination(d);
        filter.allowDestination(d);
    }

    /**
     * A destination with the four fields its hash is computed from. The key material
     * is zeroed: the filter only ever uses the hash, and a destination with no
     * certificate has no hash at all.
     *
     * @return a destination whose hash computes
     */
    private static Destination destination() {
        Destination d = new Destination();
        d.setPublicKey(new PublicKey(new byte[PublicKey.KEYSIZE_BYTES]));
        SigningPublicKey spk = new SigningPublicKey(new byte[SigningPublicKey.KEYSIZE_BYTES]);
        d.setSigningPublicKey(spk);
        d.setPadding(new byte[128 - SigningPublicKey.KEYSIZE_BYTES]);
        d.setCertificate(new KeyCertificate(spk));
        return d;
    }

    /**
     * @param name what the directory is for, in its name
     * @return a new empty directory under the system temp dir
     * @throws IOException if it could not be created
     */
    private static File scratchDir(String name) throws IOException {
        File dir = new File(SCRATCH + name + '-' + System.nanoTime());
        if (!dir.mkdirs())
            throw new IOException("could not create " + dir);
        dir.deleteOnExit();
        return dir;
    }

    /**
     * @return a directory that does not exist, so writing inside it fails
     */
    private static File noSuchDir() {
        return new File(SCRATCH + "gone-" + System.nanoTime());
    }
}
