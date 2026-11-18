package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

/**
 * Tests the name written inside a downloaded attachment's ZIP archive.
 *
 * <p>The defect: the HTTP header was given the sanitized attachment name while the
 * entry inside the archive was given the raw one straight out of the message's
 * Content-Disposition header. A message saying filename="../../evil.sh" therefore
 * produced an archive whose single entry was ../../evil.sh, and whatever the user
 * unzipped it with wrote the file outside the directory they chose - Zip Slip.
 *
 * @since 0.9.72+
 */
public class WebMailZipEntryNameTest {

    /**
     * A fixture group for the archive entry name; it holds no state.
     *
     * @since 0.9.72+
     */
    public WebMailZipEntryNameTest() {}

    /**
     * The regression: a header filename that names a path is written as one path
     * segment, whatever shape the path takes.
     */
    @Test
    public void testAPathStaysInsideOneSegment() {
        String[] paths = {"../../escape.txt", "..\\..\\escape.txt", "/absolute.txt",
                          "C:\\escape.txt", "a/b/c/d.txt", ".. /space.txt"};
        for (int i = 0; i < paths.length; i++) {
            String name = WebMail.zipEntryName(paths[i]);
            assertFalse(paths[i] + " -> " + name + " kept a slash", name.contains("/"));
            assertFalse(paths[i] + " -> " + name + " kept a backslash", name.contains("\\"));
            assertFalse(paths[i] + " -> " + name + " kept a drive", name.contains(":"));
        }
    }

    /**
     * The degenerate names a message can carry - an empty filename, or one that is
     * only dots - become a placeholder, because an entry named nothing or named
     * ".." is something an extractor cannot write.
     */
    @Test
    public void testADegenerateNameBecomesAPlaceholder() {
        assertEquals("attachment", WebMail.zipEntryName(""));
        assertEquals("attachment", WebMail.zipEntryName("   "));
        assertEquals("attachment", WebMail.zipEntryName("."));
        assertEquals("attachment", WebMail.zipEntryName(".."));
    }

    /**
     * An ordinary name is kept as the sanitizer keeps it - a space joins the
     * characters a filesystem or a header cannot hold - and a non-ASCII one
     * flattens exactly as the header name beside it does, so the two agree.
     */
    @Test
    public void testAnOrdinaryNameIsPreserved() {
        assertEquals("report.txt", WebMail.zipEntryName("report.txt"));
        assertEquals("photo_(1).jpg", WebMail.zipEntryName("photo (1).jpg"));
        assertEquals("susi_.txt", WebMail.zipEntryName("susiä.txt"));
    }

    /**
     * The name goes into a real archive and comes back out of it unchanged, with
     * the payload intact, so the guard is not simply making the archive unwritable.
     *
     * @throws IOException if the archive cannot be written or read back
     */
    @Test
    public void testTheNameAndPayloadSurviveARealArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        zip.putNextEntry(new ZipEntry(WebMail.zipEntryName("../../escape.txt")));
        zip.write("payload".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        ZipEntry entry = in.getNextEntry();
        assertNotNull("the archive has no entry", entry);
        assertEquals(".._.._escape.txt", entry.getName());
        byte[] payload = new byte[7];
        assertEquals(7, in.read(payload));
        assertEquals("payload", new String(payload, StandardCharsets.UTF_8));
        assertTrue("a second entry appeared", in.getNextEntry() == null);
    }
}
