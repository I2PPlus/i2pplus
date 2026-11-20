package i2p.susi.webmail;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import i2p.susi.util.MemoryBuffer;
import org.junit.Test;

/**
 * Pins what the mailbox shows for text the correspondent wrote.
 *
 * <p>The list rows, the attachment labels and the compose form escape HTML in
 * mail-supplied text, which stops markup but says nothing about characters that
 * have no width or that reverse the text around them. A sender of "paypal" plus the
 * right-to-left override plus "leap.com" renders reversed, and a subject can carry
 * zero-width spaces, so what the operator reads is not the string that was sent -
 * and on a reply that name is handed back into the From box as if it were the
 * correspondent's own.
 *
 * <p>The other half is the opposite mistake: a filter written to remove what is
 * invisible also removed what merely looks odd, and combining diacritical marks are
 * ordinary text sent in the decomposed form. Both directions are pinned here, at the
 * filter and through the header parser that feeds the list rows.
 *
 * @since 0.9.72+
 */
public class SenderDisplayTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public SenderDisplayTest() {}

    /**
     * The defect: a bidi override in the middle of a name reverses what is rendered,
     * and the escaper leaves it alone because it is not markup.
     */
    @Test
    public void testABidiOverrideIsRemoved() {
        assertEquals("paypal", Mail.displayable("pay\u202Epal"));
        assertEquals("paypal", Mail.displayable("pay\u202Dpal"));
        assertEquals("paypal", Mail.displayable("pay\u2066pal"));
        assertEquals("paypal", Mail.displayable("pay\u2069pal"));
    }

    /**
     * The characters that take up no width at all: zero-width space and joiners, the
     * word joiner and invisible operators, and the byte-order mark.
     */
    @Test
    public void testInvisibleCharactersAreRemoved() {
        assertEquals("paypal", Mail.displayable("p\u200Ba\u200Bypal"));
        assertEquals("paypal", Mail.displayable("paypal\u200B"));
        assertEquals("paypal", Mail.displayable("pay\u200Cpal"));
        assertEquals("paypal", Mail.displayable("pay\u2060pal"));
        assertEquals("paypal", Mail.displayable("\uFEFFpaypal"));
        assertEquals("paypal", Mail.displayable("pay\u200Fpal"));
    }

    /**
     * A combining mark is the word, not a hidden character. Text sent in the
     * decomposed form - the usual case from macOS - is a base letter plus its mark,
     * and stripping that range rewrote the sender and the subject, handed the
     * mangled form back into the reply header, and saved it into the draft. The mark
     * is left exactly as it was sent; nothing here composes it.
     */
    @Test
    public void testACombiningMarkSurvives() {
        assertEquals("cafe\u0301", Mail.displayable("cafe\u0301"));
        assertEquals("u\u0308ber", Mail.displayable("u\u0308ber"));
    }

    /**
     * Text that is simply not ASCII is left alone: precomposed accents, CJK and the
     * scripts that reach the mailbox every day survive untouched.
     */
    @Test
    public void testOrdinaryNonLatin1TextIsUnchanged() {
        String[] keep = {"caf\u00e9", "\u4e2d\u6587", "\u0410\u0431\u0432",
                         "\u00fcber", "na\u00efve"};
        for (String s : keep)
            assertEquals("changed: " + s, s, Mail.displayable(s));
    }

    /**
     * A null stays null so the callers that already pass one keep working, and text
     * with nothing to remove comes back as it went in.
     */
    @Test
    public void testNullAndPlainTextAreUntouched() {
        assertNull(Mail.displayable(null));
        assertEquals("", Mail.displayable(""));
        assertEquals("plain text <a>", Mail.displayable("plain text <a>"));
    }

    /**
     * The filter runs before the escaper, which is the order the labels use: the
     * invisible character is gone and the markup is still escaped.
     */
    @Test
    public void testTheEscapedLabelIsFilteredThenEscaped() {
        assertEquals("pay&lt;pal&gt;", WebMail.quoteDisplay("pay\u202E<pal>"));
        assertEquals("", WebMail.quoteDisplay(null));
    }

    /**
     * The defect end to end: a header line as it arrives from the server, parsed the
     * way the list page reads it.
     *
     * @throws IOException on a buffer error in the fixture
     */
    @Test
    public void testAParsedHeaderCarriesNoHiddenCharacters() throws IOException {
        Mail mail = mailWith("From: \"a\u202Eb\" <x@y.i2p>\r\n" +
                             "Subject: hi\u200Bthere\u202E\r\n" +
                             "Date: Sun, 16 Nov 2026 12:00:00 +0000\r\n" +
                             "\r\n");

        assertEquals("ab", mail.shortSender);
        assertEquals("hithere", mail.shortSubject);
        assertFalse("the sender kept a formatting character", hasFormatting(mail.sender));
        assertFalse("the subject kept a formatting character", hasFormatting(mail.subject));
    }

    /**
     * The same path on a name and a subject sent in the decomposed form, which is
     * what macOS and many mobile keyboards produce: the mark is part of the word, and
     * it used to be stripped out of the strings the reply header and any saved draft
     * are built from, so "Renée" arrived, rendered as "Renee", and went back out that
     * way.
     *
     * @throws IOException on a buffer error in the fixture
     */
    @Test
    public void testAParsedHeaderKeepsADecomposedNameAndSubject() throws IOException {
        Mail mail = mailWith("From: \"Rene\u0301e\" <x@y.i2p>\r\n" +
                             "Subject: cafe\u0301 morning\r\n" +
                             "Date: Sun, 16 Nov 2026 12:00:00 +0000\r\n" +
                             "\r\n");

        assertEquals("\"Rene\u0301e\" <x@y.i2p>", mail.sender);
        assertEquals("cafe\u0301 morning", mail.subject);
        assertFalse("the subject kept a formatting character", hasFormatting(mail.subject));
    }

    /**
     * A message parsed from the header lines as they arrive from the server.
     *
     * @param raw the header lines, terminated by a blank line
     * @return the parsed message, never null
     * @throws IOException on a buffer error in the fixture
     */
    private static Mail mailWith(String raw) throws IOException {
        MemoryBuffer buf = new MemoryBuffer(raw.length() + 16);
        OutputStream out = buf.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.close();
        buf.writeComplete(true);

        Mail mail = new Mail("1");
        mail.setHeader(buf);
        return mail;
    }

    /**
     * Whether any character in the invisible or directional ranges survives in text.
     * Combining marks are not one of them - they are the word, and they are expected
     * to survive.
     *
     * @param s the text to check, may be null
     * @return true if it still holds one of them
     */
    private static boolean hasFormatting(String s) {
        if (s == null) {return false;}
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) ||
                (c >= 0x2060 && c <= 0x2064) || (c >= 0x2066 && c <= 0x2069) ||
                c == 0xfeff)
                return true;
        }
        return false;
    }
}
