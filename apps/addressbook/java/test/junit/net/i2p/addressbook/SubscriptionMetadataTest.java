package net.i2p.addressbook;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

/**
 * Pins that a subscription finds the metadata stored under its own URL.
 *
 * <p>The etags, last-modified and last-fetched files are keyed by subscription URL,
 * and they were read through the same reader as the settings file, which folds every
 * key to lower case. The locations file is not folded, so any subscription URL with an
 * upper case letter in the host or the path never matched its own entry: the ETag and
 * last-modified headers came back null, so no conditional GET was ever sent and the
 * whole body was re-fetched every cycle, and the last-fetched time reset to zero, which
 * defeated the delay between fetches.
 *
 * @since 0.9.72+
 */
public class SubscriptionMetadataTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SubscriptionMetadataTest() {}

    /** A URL with upper case in both the host and the path. */
    private static final String URL = "http://Example.i2p/Hosts.txt";

    /** The ETag stored for that URL. */
    private static final String ETAG = "\"aBc-123\"";

    /** The last-modified date stored for that URL. */
    private static final String LAST_MODIFIED = "Wed, 18 Nov 2026 09:00:00 GMT";

    /** The last-fetched time stored for that URL. */
    private static final long LAST_FETCHED = 1763_000_000_000L;

    /**
     * A directory for one case's files, removed when the case ends.
     *
     * @param name what to call it
     * @return a new empty directory in the system temp directory
     * @throws IOException if it cannot be created
     */
    private static File tempDir(String name) throws IOException {
        return Files.createTempDirectory("ab-meta-" + name).toFile();
    }

    /**
     * Writes a locations file holding the given URLs.
     *
     * @param dir the directory to write it in
     * @param urls one URL per line
     * @return a subscriptions.txt holding those URLs, in order
     * @throws IOException if it cannot be written
     */
    private static File locations(File dir, String... urls) throws IOException {
        File file = new File(dir, "subscriptions.txt");
        Files.write(file.toPath(), Arrays.asList(urls));
        return file;
    }

    /**
     * Writes one metadata file the way the address book does it.
     *
     * @param dir the directory to write it in
     * @param name the file name
     * @param key the URL to store the value under
     * @param value the value to store
     * @return a metadata file holding that one key, value pair
     * @throws IOException if it cannot be written
     */
    private static File metadata(File dir, String name, String key,
                                 String value) throws IOException {
        File file = new File(dir, name);
        ConfigParser.write(Collections.singletonMap(key, value), file);
        return file;
    }

    /**
     * Writes one metadata file keyed by the mixed-case URL.
     *
     * @param dir the directory to write it in
     * @param name the file name
     * @param value the value to store under URL
     * @return a metadata file keyed by the mixed-case URL
     * @throws IOException if it cannot be written
     */
    private static File metadata(File dir, String name, String value) throws IOException {
        return metadata(dir, name, URL, value);
    }

    /**
     * Loads a subscription list over the given files.
     *
     * @param dir the directory holding them
     * @return the list, with one subscription
     * @throws IOException if a file cannot be read
     */
    private static SubscriptionList load(File dir) throws IOException {
        return new SubscriptionList(new File(dir, "subscriptions.txt"), new File(dir, "etags"),
                                    new File(dir, "last-modified"), new File(dir, "last-fetched"),
                                    0, Collections.<String>emptyList(), null, -1);
    }

    /**
     * The defect: a mixed-case URL's stored metadata was read under a folded key and
     * never found, so the subscription came back with nothing.
     *
     * @throws IOException if a file cannot be written or read
     */
    @Test
    public void testAMixedCaseUrlFindsItsOwnMetadata() throws IOException {
        File dir = tempDir("find");
        locations(dir, URL);
        metadata(dir, "etags", ETAG);
        metadata(dir, "last-modified", LAST_MODIFIED);
        metadata(dir, "last-fetched", Long.toString(LAST_FETCHED));
        Subscription sub = load(dir).getSubscriptions().get(0);
        assertEquals(ETAG, sub.getEtag());
        assertEquals(LAST_MODIFIED, sub.getLastModified());
        assertEquals(LAST_FETCHED, sub.getLastFetched());
    }

    /**
     * What the defect looked like instead: the metadata files were correct on disk,
     * written with the URL as the operator typed it, and only the read lost it.
     *
     * @throws IOException if a file cannot be written or read
     */
    @Test
    public void testTheMetadataFileOnDiskKeepsTheUrl() throws IOException {
        File dir = tempDir("disk");
        File etags = metadata(dir, "etags", ETAG);
        String contents = new String(Files.readAllBytes(etags.toPath()), StandardCharsets.UTF_8);
        assertTrue("the file does not carry the URL as written: " + contents,
                   contents.contains(URL + "="));
    }

    /**
     * A write() round trip keeps the metadata findable, which is what the fetch cycle
     * does every time it runs.
     *
     * @throws IOException if a file cannot be written or read
     */
    @Test
    public void testMetadataSurvivesAWriteRoundTrip() throws IOException {
        File dir = tempDir("round");
        locations(dir, URL);
        metadata(dir, "etags", ETAG);
        metadata(dir, "last-modified", LAST_MODIFIED);
        metadata(dir, "last-fetched", Long.toString(LAST_FETCHED));
        SubscriptionList list = load(dir);
        list.write();
        Subscription sub = load(dir).getSubscriptions().get(0);
        assertEquals(ETAG, sub.getEtag());
        assertEquals(LAST_MODIFIED, sub.getLastModified());
        assertEquals(LAST_FETCHED, sub.getLastFetched());
    }

    /**
     * A lower-case URL, the shape of the built-in defaults, was never affected and
     * still works.
     *
     * @throws IOException if a file cannot be written or read
     */
    @Test
    public void testALowerCaseUrlIsUnaffected() throws IOException {
        File dir = tempDir("lower");
        String lower = URL.toLowerCase(Locale.US);
        locations(dir, lower);
        metadata(dir, "etags", lower, ETAG);
        Subscription sub = load(dir).getSubscriptions().get(0);
        assertEquals(ETAG, sub.getEtag());
    }

    /**
     * The settings reader still folds its keys - the settings file's names are
     * compared in lower case throughout the daemon, and the local_addressbook to
     * master_addressbook migration depends on it.
     *
     * @throws IOException if a file cannot be written or read
     */
    @Test
    public void testTheSettingsReaderStillFoldsKeys() throws IOException {
        File dir = tempDir("settings");
        File file = new File(dir, "config.txt");
        Files.write(file.toPath(), Arrays.asList("Mixed_Key = Value", "update_delay=60"));
        Map<String, String> settings = ConfigParser.parse(file);
        assertEquals("Value", settings.get("mixed_key"));
        assertEquals("60", settings.get("update_delay"));
        assertNull(settings.get("Mixed_Key"));
    }

    /**
     * The metadata reader keeps the key exactly as written, which is the whole fix,
     * and still trims the surrounding spaces.
     *
     * @throws IOException if a file cannot be written or read
     */
    @Test
    public void testTheMetadataReaderKeepsTheKey() throws IOException {
        File dir = tempDir("keep");
        File file = new File(dir, "etags");
        Files.write(file.toPath(), Arrays.asList(" " + URL + " =  abc "));
        Map<String, String> etags = ConfigParser.parseSubscriptionMetadata(file);
        assertTrue("the key was folded: " + etags.keySet(), etags.containsKey(URL));
        assertEquals("abc", etags.get(URL));
    }
}
