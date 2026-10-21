package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests how a character read while parsing a MIME boundary is written to the
 * log. Control characters and the end of the data would otherwise be logged as
 * a bare number, which a reader cannot match against the broken message on disk.
 *
 * @since 0.9.72+
 */
public class MailPartBoundaryCharTest {

    @Test
    public void testControlCharactersAreDescribed() {
        assertEquals("[end of data]", MailPart.charText(-1));
        assertEquals("[carriage return]", MailPart.charText('\r'));
        assertEquals("[line feed]", MailPart.charText('\n'));
        assertEquals("[space]", MailPart.charText(' '));
    }

    @Test
    public void testOtherBytesAreBracketed() {
        assertEquals("[0x1]", MailPart.charText(1));
        assertEquals("[0x7f]", MailPart.charText(0x7f));
        assertEquals("[x]", MailPart.charText('x'));
    }
}
