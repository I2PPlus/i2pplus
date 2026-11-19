package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import i2p.susi.util.ReadBuffer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.i2p.data.Base64;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What a draft is allowed to name as an attachment.
 *
 * <p>The defect: a draft stores each attachment as a path written into its own body, and
 * that body is read back verbatim, so the path was taken on trust and the file opened
 * wherever it pointed. A draft can arrive from a restored backup or from the import/
 * directory rather than from the upload that created it, and then a path like
 * ../../../secret names any file the router's user can read.
 *
 * @since 0.9.72+
 */
public class DraftAttachmentPathTest {

    /** Scratch account directories, removed after each case. */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * A fixture group for the draft attachment path rules; it holds no state.
     *
     * @since 0.9.72+
     */
    public DraftAttachmentPathTest() {}

    /**
     * A file of this account's own uploads is opened.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testAPathInsideTheAttachmentsDirectoryIsAccepted() throws IOException {
        File dir = folder.newFolder("attachments");
        File upload = new File(dir, "susimail-attachment-1");
        write(upload, "data");
        File found = Draft.attachmentFile(upload.getAbsolutePath(), dir);
        assertNotNull(found);
        assertEquals(upload.getAbsolutePath(), found.getAbsolutePath());
    }

    /**
     * The defect: a path that leaves the attachments directory is refused, whether it
     * is written with .. or simply points elsewhere.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testAPathOutsideTheAttachmentsDirectoryIsRefused() throws IOException {
        File dir = folder.newFolder("attachments2");
        File outside = folder.newFile("secret");
        assertNull(Draft.attachmentFile(outside.getAbsolutePath(), dir));
        String escaped = new File(dir, "sub/../../secret").getPath();
        assertNull(Draft.attachmentFile(escaped, dir));
    }

    /**
     * A link in the attachments directory does not make whatever it points at an
     * upload, even when the link's own name is inside.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testASymlinkIsRefused() throws IOException {
        File dir = folder.newFolder("attachments3");
        File target = folder.newFile("elsewhere");
        File link = new File(dir, "susimail-attachment-link");
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return; // no symlink support on this filesystem
        }
        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertNull(Draft.attachmentFile(link.getPath(), dir));
    }

    /**
     * A path is one file, not the directory itself, and an empty one is no path.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testTheDirectoryItselfAndAnEmptyPathAreRefused() throws IOException {
        File dir = folder.newFolder("attachments4");
        assertNull(Draft.attachmentFile(dir.getAbsolutePath(), dir));
        assertNull(Draft.attachmentFile("", dir));
        assertNull(Draft.attachmentFile(null, dir));
    }

    /**
     * A path that wanders through . and .. but ends inside the directory is still an
     * upload, so it is kept.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testAPathThatResolvesBackInsideIsAccepted() throws IOException {
        File dir = folder.newFolder("attachments5");
        File sub = new File(dir, "sub");
        assertTrue(sub.mkdir());
        File upload = new File(dir, "upload");
        write(upload, "data");
        String roundabout = new File(sub, "../upload").getPath();
        assertNotNull(Draft.attachmentFile(roundabout, dir));
    }

    /**
     * With no directory to confine to, the path is used as written - that is the
     * draft built only to write attachment headers out, which never reads them back.
     */
    @Test
    public void testNoDirectoryMeansNoConfinement() {
        File f = Draft.attachmentFile("/anywhere/at/all", null);
        assertNotNull(f);
        assertEquals("/anywhere/at/all", f.getPath());
    }

    /**
     * The defect end to end: a draft whose attachment header names a file outside the
     * account's directory parses with no attachment at all.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testADraftNamingAForeignFileParsesWithoutIt() throws IOException {
        File dir = folder.newFolder("attachments6");
        File outside = folder.newFile("secret2");
        write(outside, "the operator's mail");
        Draft draft = new Draft("uidl-1", dir);
        draft.setBody(buffer(draftBody(outside.getAbsolutePath())));
        assertFalse(draft.hasAttachment());
        assertEquals(0, draft.getAttachments().size());
    }

    /**
     * And the ordinary case still works: an upload in the account's directory is
     * parsed, sized and kept.
     * @throws IOException if the scratch directory or a fixture file cannot be created
     */
    @Test
    public void testADraftNamingItsOwnUploadParsesIt() throws IOException {
        File dir = folder.newFolder("attachments7");
        File upload = new File(dir, "susimail-attachment-42");
        write(upload, "the attachment");
        Draft draft = new Draft("uidl-2", dir);
        draft.setBody(buffer(draftBody(upload.getAbsolutePath())));
        assertTrue(draft.hasAttachment());
        Attachment a = draft.getAttachments().get(0);
        assertEquals("notes.txt", a.getFileName());
        assertEquals(upload.length(), a.getSize());
    }

    /**
     * @param path the path the attachment header is to name
     * @return a draft body as the webmail writes one
     */
    private static String draftBody(String path) {
        return "X-I2P-Attachment: " + Base64.encode("notes.txt") + ',' +
               "text/plain" + ',' + "quoted-printable" + ',' + Base64.encode(path) +
               "\r\nFrom: <someone@example.i2p>\r\n\r\nthe body\r\n";
    }

    /**
     * @param f the file to create
     * @param s its contents
     * @throws IOException if it cannot be written
     */
    private static void write(File f, String s) throws IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param s the draft as its writer wrote it
     * @return a buffer over those bytes
     */
    private static ReadBuffer buffer(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new ReadBuffer(b, 0, b.length);
    }
}
