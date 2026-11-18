package i2p.susi.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the check a POP3 or SMTP command has to pass before it is written to the
 * server's socket.
 *
 * <p>The defect: both writers append their own terminator and write the command
 * through an 8-bit truncation without looking at what is in the string on the way.
 * A CR or LF in a user name or password from the login form ends the command
 * early, and everything after it reaches the server as a second command - so a
 * password can issue a DELE the session never agreed to.
 *
 * @since 0.9.72+
 */
public class CommandUtilSingleLineTest {

    /**
     * A fixture group for the command-line check; it holds no state.
     *
     * @since 0.9.72+
     */
    public CommandUtilSingleLineTest() {}

    /**
     * The two characters that break a command, and the rest of the control range
     * with them: a NUL, a tab and a delete are all unreadable in a credential and
     * none of them belongs in a protocol line.
     */
    @Test
    public void testAControlCharacterEndsACommandEarly() {
        assertFalse(CommandUtil.isSingleLine("PASS p\nass"));
        assertFalse(CommandUtil.isSingleLine("PASS p\rass"));
        assertFalse(CommandUtil.isSingleLine("PASS \0ass"));
        assertFalse(CommandUtil.isSingleLine("USER susi\t"));
        assertFalse(CommandUtil.isSingleLine("RETR \u007f"));
    }

    /**
     * The writers truncate to 8 bits, so a character is judged by the byte it
     * would be sent as: a character whose low byte is a control character is a
     * command break even though the character itself is not one.
     */
    @Test
    public void testTheCheckSeesTheByteThatGetsSent() {
        assertFalse(CommandUtil.isSingleLine("PASS \u010a"));
        assertFalse(CommandUtil.isSingleLine("PASS \u200d"));
    }

    /**
     * Ordinary credentials, including the non-ASCII ones a truncating writer
     * mangles into something else, stay on one line and are not refused.
     */
    @Test
    public void testAPrintableCommandIsAccepted() {
        assertTrue(CommandUtil.isSingleLine("RETR 12"));
        assertTrue(CommandUtil.isSingleLine("PASS p\u00e4ssw0rd!"));
        assertTrue(CommandUtil.isSingleLine("USER susi+tag@example.i2p"));
        assertTrue(CommandUtil.isSingleLine(""));
    }
}
