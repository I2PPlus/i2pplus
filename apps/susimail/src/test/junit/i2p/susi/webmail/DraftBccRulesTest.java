package i2p.susi.webmail;

import static org.junit.Assert.*;

import i2p.susi.util.Buffer;
import i2p.susi.util.ReadBuffer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.i2p.data.Base64;
import org.junit.Test;

/**
 * Tests how Draft.setBody() reads the header block of a saved draft.
 *
 * <p>A draft body is written attachment headers first and the identity headers after
 * them, so the block a draft is read back with is
 * <code>X-I2P-Attachment…, Date:, From:, To:, Cc:, Bcc:, Subject:</code>. Every header
 * in that block has to be looked at: stopping at the first one that is not an attachment
 * header loses the Bcc line behind it, and a draft sent from there blind-copies nobody
 * without saying so.
 *
 * <p>A body is also set more than once on the same object as the mail cache loads and
 * reloads a message, so a parse has to start from what the last one found being gone, or
 * the attachments are counted again each time.
 *
 * @since 0.9.72+
 */
public class DraftBccRulesTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one;
     * the drafts under test are built inside each method and nothing is held here.
     *
     * @since 0.9.72+
     */
    public DraftBccRulesTest() {}

    /**
     * Builds the body of a saved draft: the given header lines, a blank line, then the
     * message text.
     *
     * @param headers the header lines, in the order a draft writes them
     * @return a buffer a Draft can be given
     */
    private static Buffer draftBody(String... headers) {
        StringBuilder sb = new StringBuilder();
        for (String header : headers) {
            sb.append(header).append("\r\n");
        }
        sb.append("\r\n").append("the message text");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new ReadBuffer(bytes, 0, bytes.length);
    }

    /**
     * The attachment header line a draft writes: base64 name, content type, transfer
     * encoding and base64 path, comma separated.
     *
     * @param name the attachment's file name
     * @param path the file holding its bytes
     * @return the header line
     */
    private static String attachmentHeader(String name, String path) {
        return "X-I2P-Attachment: " + Base64.encode(name) + ",application/octet-stream," +
               "base64," + Base64.encode(path);
    }

    /**
     * A small file to point an attachment header at, so the attachment has a size to
     * report. The caller deletes it.
     *
     * @return a file holding seven bytes
     * @throws IOException if the file cannot be written
     */
    private static File attachmentData() throws IOException {
        File data = File.createTempFile("draft-attach", ".dat");
        Files.write(data.toPath(), "1234567".getBytes(StandardCharsets.UTF_8));
        return data;
    }

    /**
     * A draft read back the way composeDraft() writes it: the attachment header first,
     * then the identity headers, Bcc among them. The Bcc has to survive, and so does the
     * attachment.
     *
     * @throws IOException if the attachment's file cannot be written
     */
    @Test
    public void testBccAfterTheAttachmentHeaderIsFound() throws IOException {
        File data = attachmentData();
        try {
            Buffer body = draftBody(attachmentHeader("note.dat", data.getPath()),
                                    "Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                    "From: <me@mail.i2p>",
                                    "To: <you@mail.i2p>",
                                    "Bcc: <hidden@mail.i2p>",
                                    "Subject: kept");
            Draft draft = new Draft("uidl1");
            draft.setBody(body);
            String[] bcc = draft.getBcc();
            assertNotNull("the Bcc header behind the identity headers was not read", bcc);
            assertEquals(1, bcc.length);
            assertEquals("<hidden@mail.i2p>", bcc[0]);
            List<Attachment> attachments = draft.getAttachments();
            assertEquals(1, attachments.size());
            assertEquals("note.dat", attachments.get(0).getFileName());
            assertEquals("the size is the body plus what is attached",
                         body.getLength() + 7, draft.getSize());
        } finally {
            data.delete();
        }
    }

    /**
     * A draft with no attachments at all: its block starts at Date:, and that is not the
     * end of the headers either.
     */
    @Test
    public void testBccIsFoundWithNoAttachments() {
        Draft draft = new Draft("uidl2");
        draft.setBody(draftBody("Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                "From: <me@mail.i2p>",
                                "To: <you@mail.i2p>",
                                "Bcc: <hidden@mail.i2p>",
                                "Subject: kept"));
        String[] bcc = draft.getBcc();
        assertNotNull("the Bcc header was not read from a draft with no attachments", bcc);
        assertEquals(1, bcc.length);
        assertEquals("<hidden@mail.i2p>", bcc[0]);
        assertEquals(0, draft.getAttachments().size());
    }

    /**
     * Setting the same body twice must not count the attachment twice or grow the size:
     * the mail cache sets a body again when it loads a message it already holds.
     *
     * @throws IOException if the attachment's file cannot be written
     */
    @Test
    public void testSecondParseDoesNotDuplicate() throws IOException {
        File data = attachmentData();
        try {
            String attach = attachmentHeader("note.dat", data.getPath());
            Draft draft = new Draft("uidl3");
            draft.setBody(draftBody(attach,
                                    "Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                    "Bcc: <hidden@mail.i2p>"));
            long firstSize = draft.getSize();
            draft.setBody(draftBody(attach,
                                    "Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                    "Bcc: <hidden@mail.i2p>"));
            assertEquals("the second parse added the attachment again",
                         1, draft.getAttachments().size());
            assertEquals("the second parse changed the size", firstSize, draft.getSize());
            assertEquals(1, draft.getBcc().length);
        } finally {
            data.delete();
        }
    }

    /**
     * A body may carry two Bcc lines; both sets of recipients are kept, and merging them
     * does not run off the end of the array the draft already holds.
     */
    @Test
    public void testTwoBccLinesMerge() {
        Draft draft = new Draft("uidl4");
        draft.setBody(draftBody("Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                "Bcc: <one@mail.i2p>",
                                "Bcc: <two@mail.i2p>"));
        String[] bcc = draft.getBcc();
        assertNotNull(bcc);
        assertEquals(2, bcc.length);
        assertEquals("<one@mail.i2p>", bcc[0]);
        assertEquals("<two@mail.i2p>", bcc[1]);
    }

    /**
     * A header name is case-insensitive, so a draft written with <code>BCC:</code> or
     * <code>x-i2p-attachment:</code> still reads.
     *
     * @throws IOException if the attachment's file cannot be written
     */
    @Test
    public void testHeaderNamesAreMatchedWithoutRegardToCase() throws IOException {
        File data = attachmentData();
        try {
            String attach = attachmentHeader("note.dat", data.getPath())
                                             .replace("X-I2P-Attachment: ", "x-i2p-attachment: ");
            Draft draft = new Draft("uidl5");
            draft.setBody(draftBody(attach,
                                    "Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                    "BCC: <hidden@mail.i2p>"));
            assertEquals(1, draft.getAttachments().size());
            String[] bcc = draft.getBcc();
            assertNotNull("a BCC header in another case was not read", bcc);
            assertEquals("<hidden@mail.i2p>", bcc[0]);
        } finally {
            data.delete();
        }
    }

    /**
     * A draft with no Bcc line at all has no blind copies, and that is not an error.
     */
    @Test
    public void testNoBccLineMeansNoBlindCopies() {
        Draft draft = new Draft("uidl6");
        draft.setBody(draftBody("Date: Sun, 16 Nov 2026 12:00:00 +0000",
                                "To: <you@mail.i2p>",
                                "Subject: kept"));
        assertNull(draft.getBcc());
    }
}
