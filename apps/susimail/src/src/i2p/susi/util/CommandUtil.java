package i2p.susi.util;

/**
 * Shared rules for the command lines a mail server is sent, so the POP3 and SMTP
 * writers refuse the same strings for the same reason.
 *
 * @since 0.9.72+
 */
public class CommandUtil {

    /**
     * Groups static helpers, so nothing instantiates this; the constructor only
     * states that an instance would carry no state.
     */
    public CommandUtil() {}

    /**
     * Whether a command is one line as far as the server will read it. Both
     * writers take the string through an 8-bit truncation, so a character above
     * 127 is only wrong in its own way, while a control character breaks the
     * protocol: a CR or LF in a user name or a password from the login form ends
     * the command early, and everything after it reaches the server as a second
     * command nobody asked for.
     *
     * @param cmd the command about to be written, never null
     * @return true if no character, taken as the byte the writer sends, is a
     *         control character or a delete
     * @since 0.9.72+
     */
    public static boolean isSingleLine(String cmd) {
        for (int i = 0; i < cmd.length(); i++) {
            int c = cmd.charAt(i) & 0xff;
            if (c < ' ' || c == 0x7f) {return false;}
        }
        return true;
    }
}
