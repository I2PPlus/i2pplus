package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Properties;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;

import org.junit.Test;

/**
 * Pins what {@link PersistentDataStore#purgeSlowRouters()} decides, and where it
 * gets the data to decide it with.
 * <p>
 * The purge runs once per boot, over every RouterInfo file in the netdb, and it
 * deletes files, so a wrong tier boundary or a wrong capability letter silently
 * removes usable routers from a starting router - or leaves slow ones in place.
 * The tier cut-off moves with the number of routers stored, so each boundary is
 * pinned on both sides.
 * <p>
 * The second half pins the source of the tier and the capability string. The
 * startup load has already parsed and signature-verified every file it kept, so
 * re-parsing those files costs a second SHA-256 and a second signature verify per
 * router to recover two strings the process is holding anyway. Only a key that is
 * <em>not</em> in memory may be read back off disk.
 *
 * @since 0.9.72+
 */
public class PersistentDataStorePurgeDecisionTest {

    /** Counts on both sides of each tier cut-off, plus the sizes the purge skips entirely. */
    private static final int[] SIZES = {0, 499, 500, 999, 1000, 1999, 2000, 6000};

    /**
     * K, L and M are dropped at every database size, including the smallest ones
     * the purge is asked about.
     */
    @Test
    public void testSlowestTiersPurgedAtEverySize() {
        for (int size : SIZES) {
            assertTrue("K at " + size, PersistentDataStore.isSlowTier("K", size));
            assertTrue("L at " + size, PersistentDataStore.isSlowTier("L", size));
            assertTrue("M at " + size, PersistentDataStore.isSlowTier("M", size));
        }
    }

    /**
     * N is kept while the netdb is small and dropped from exactly 1000 stored
     * routers on up.
     */
    @Test
    public void testNTierCutOffIsExactlyOneThousand() {
        assertFalse(PersistentDataStore.isSlowTier("N", 999));
        assertTrue(PersistentDataStore.isSlowTier("N", 1000));
        assertTrue(PersistentDataStore.isSlowTier("N", 1999));
        assertTrue(PersistentDataStore.isSlowTier("N", 2000));
    }

    /**
     * O is kept below 2000 stored routers and dropped from exactly 2000 on up.
     */
    @Test
    public void testOTierCutOffIsExactlyTwoThousand() {
        assertFalse(PersistentDataStore.isSlowTier("O", 999));
        assertFalse(PersistentDataStore.isSlowTier("O", 1999));
        assertTrue(PersistentDataStore.isSlowTier("O", 2000));
        assertTrue(PersistentDataStore.isSlowTier("O", 6000));
    }

    /**
     * Nothing that is not a slow tier letter counts as slow, so a router with no
     * tier in its capabilities stays in the netdb.
     */
    @Test
    public void testOtherTiersAreNeverSlow() {
        for (int size : SIZES) {
            assertFalse(PersistentDataStore.isSlowTier("P", size));
            assertFalse(PersistentDataStore.isSlowTier("X", size));
            assertFalse(PersistentDataStore.isSlowTier("R", size));
            assertFalse(PersistentDataStore.isSlowTier("U", size));
            // What getBandwidthTier() returns when no tier letter is present.
            assertFalse(PersistentDataStore.isSlowTier("Unknown", size));
            assertFalse(PersistentDataStore.isSlowTier("", size));
        }
    }

    /**
     * A null tier - a RouterInfo that never got as far as parsing its options - is
     * kept rather than deleted.
     */
    @Test
    public void testNullTierIsKept() {
        assertFalse(PersistentDataStore.isSlowTier(null, 0));
        assertFalse(PersistentDataStore.isSlowTier(null, 6000));
    }

    /**
     * D, E and G each mark a router as too congested to route tunnels for anyone
     * else, whatever its tier.
     */
    @Test
    public void testCongestionCapabilitiesAreDegraded() {
        assertTrue(PersistentDataStore.isDegradedCapabilities("D"));
        assertTrue(PersistentDataStore.isDegradedCapabilities("E"));
        assertTrue(PersistentDataStore.isDegradedCapabilities("G"));
        // The letters appear inside a full capability string, not alone.
        assertTrue(PersistentDataStore.isDegradedCapabilities("UOGRbf"));
        assertTrue(PersistentDataStore.isDegradedCapabilities("fRsD"));
    }

    /**
     * An empty, a null, or a healthy capability string is not degraded, and the
     * tier letters are not mistaken for congestion letters.
     */
    @Test
    public void testHealthyCapabilitiesAreNotDegraded() {
        assertFalse(PersistentDataStore.isDegradedCapabilities(""));
        assertFalse(PersistentDataStore.isDegradedCapabilities(null));
        assertFalse(PersistentDataStore.isDegradedCapabilities("UO"));
        assertFalse(PersistentDataStore.isDegradedCapabilities("fRs"));
        // P is a tier, not a congestion flag; D/E/G only.
        assertFalse(PersistentDataStore.isDegradedCapabilities("PXN"));
    }

    /**
     * The purge decision is slow OR degraded, so either half on its own is enough
     * to delete the file, and a router has to clear both to be kept.
     */
    @Test
    public void testPurgeIsSlowOrDegraded() {
        assertTrue(PersistentDataStore.shouldPurgeRouter("K", "fRs", 999));
        assertTrue(PersistentDataStore.shouldPurgeRouter("P", "fRsD", 999));
        assertTrue(PersistentDataStore.shouldPurgeRouter("N", "fRsE", 999));
        assertTrue(PersistentDataStore.shouldPurgeRouter("Unknown", "G", 0));
        assertFalse(PersistentDataStore.shouldPurgeRouter("P", "fRs", 6000));
        assertFalse(PersistentDataStore.shouldPurgeRouter("Unknown", "", 6000));
        assertFalse(PersistentDataStore.shouldPurgeRouter(null, null, 6000));
        // Boundary: the same N router is kept at 999 and purged at 1000.
        assertFalse(PersistentDataStore.shouldPurgeRouter("N", "fRs", 999));
        assertTrue(PersistentDataStore.shouldPurgeRouter("N", "fRs", 1000));
    }

    /**
     * A RouterInfo held in memory is judged as it stands; the file is not opened,
     * even though the file on disk here is unreadable garbage.
     *
     * @throws Exception if the signed RouterInfo fixture could not be built
     */
    @Test
    public void testInMemoryRouterInfoIsUsedWithoutReadingFile() throws Exception {
        RouterInfo inMemory = routerInfo("P");
        File garbage = tempFile("routerInfo-garbage.dat", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 9});
        RouterInfo used = PersistentDataStore.resolveRouterInfo(inMemory, garbage);
        assertSame("the in-memory entry must be judged as it is", inMemory, used);
    }

    /**
     * A key that is not in memory falls back to the file: the tier read back is
     * the one that was written, so the fallback still sees the stored capabilities.
     *
     * @throws Exception if the RouterInfo file could not be written
     */
    @Test
    public void testMissingFromMemoryFallsBackToTheFile() throws Exception {
        RouterInfo written = routerInfo("K");
        File file = writeRouterInfoFile(written, "K");
        assertEquals("K", written.getBandwidthTier());
        RouterInfo read = PersistentDataStore.resolveRouterInfo(null, file);
        assertNotNull(read);
        assertEquals("K", read.getBandwidthTier());
        assertEquals("K", read.getCapabilities());
    }

    /**
     * The fallback really does parse and verify: garbage on disk is rejected
     * rather than judged, exactly as before the in-memory shortcut existed, so the
     * caller's exception handler still runs and the file survives one bad read.
     *
     * @throws Exception if the garbage file could not be written
     */
    @Test
    public void testCorruptFileIsRejected() throws Exception {
        File garbage = tempFile("routerInfo-garbage.dat", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 9});
        try {
            PersistentDataStore.resolveRouterInfo(null, garbage);
            fail("a corrupt RouterInfo file must not be judged");
        } catch (DataFormatException dfe) {
            // expected
        } catch (IOException ioe) {
            // expected, a corrupt structure may hit EOF first
        }
    }

    /**
     * A file that has vanished between the listing and the read reports an IO
     * failure instead of an unusable RouterInfo.
     *
     * @throws Exception if the temp directory could not be created
     */
    @Test
    public void testMissingFileIsRejected() throws Exception {
        File gone = new File(tempDir(), "routerInfo-never-written.dat");
        assertFalse(gone.exists());
        try {
            PersistentDataStore.resolveRouterInfo(null, gone);
            fail("a vanished RouterInfo file must not be judged");
        } catch (IOException ioe) {
            // expected
        }
    }

    /**
     * A RouterInfo carrying the given capability string, signed with freshly
     * generated keys so {@code readBytes(verifySig = true)} accepts it.
     *
     * @param caps the capabilities string to store, e.g. "K" or "fRsP"
     * @return a signed RouterInfo whose tier and capabilities read back from disk
     * @throws Exception if key generation or signing fails
     */
    private static RouterInfo routerInfo(String caps) throws Exception {
        Object[] pki = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)pki[0];
        PrivateKey privKey = (PrivateKey)pki[1];
        Object[] signing = KeyGenerator.getInstance().generateSigningKeypair();
        SigningPublicKey signingPubKey = (SigningPublicKey)signing[0];
        SigningPrivateKey signingPrivKey = (SigningPrivateKey)signing[1];

        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        ident.setPublicKey(pubKey);
        ident.setSigningPublicKey(signingPubKey);

        RouterInfo ri = new RouterInfo();
        ri.setIdentity(ident);
        ri.setAddresses(new HashSet<RouterAddress>());
        Properties options = new Properties();
        options.setProperty("router.version", "0.9.72");
        if (caps != null) {
            options.setProperty(RouterInfo.PROP_CAPABILITIES, caps);
        }
        ri.setOptions(options);
        ri.setPublished(System.currentTimeMillis());
        ri.sign(signingPrivKey);
        // Touch the private key so the compiler cannot fold the keypair away.
        assertNotNull(privKey);
        return ri;
    }

    /**
     * The RouterInfo written to a real file, as the netdb keeps it.
     *
     * @param ri the RouterInfo to write, already signed
     * @param caps its capability string, used to name the file
     * @return the file created under a temporary directory
     * @throws Exception if the RouterInfo cannot be encoded or written
     */
    private static File writeRouterInfoFile(RouterInfo ri, String caps) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ri.writeBytes(baos);
        return tempFile("routerInfo-" + caps + "-" + System.nanoTime() + ".dat", baos.toByteArray());
    }

    /**
     * A scratch directory outside the workspace for test files.
     *
     * @return an existing directory, created on first use
     */
    private static File tempDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "opencode/netdb-purge-test");
        dir.mkdirs();
        return dir;
    }

    /**
     * A file holding the given bytes under {@link #tempDir()}.
     *
     * @param name the file name to create
     * @param bytes the contents to write
     * @return the file that was written
     * @throws IOException if the file cannot be written
     */
    private static File tempFile(String name, byte[] bytes) throws IOException {
        File f = new File(tempDir(), name);
        f.deleteOnExit();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(bytes);
        }
        return f;
    }
}
