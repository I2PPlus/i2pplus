package net.i2p.sam;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Properties;

import org.junit.Test;

/**
 * The first line of a SAM connection decides whether there is a SAM session at
 * all. Anything that is not {@code HELLO VERSION}, a browser's
 * {@code POST / HTTP/1.1} to the SAM port included, used to be logged and then
 * answered with {@code HELLO REPLY RESULT=OK}, so the lines that followed it ran
 * as SAM commands.
 *
 * @since 0.9.72+
 */
public class SAMHelloGateTest {

    /** Holds no state; each case parses one line. */
    public SAMHelloGateTest() {}

    /** The hello the protocol defines, with and without the optional range. */
    @Test
    public void aSamHelloPassesTheGate() {
        assertTrue(hello("HELLO VERSION"));
        assertTrue(hello("HELLO VERSION MIN=3.2 MAX=3.3"));
        assertTrue(hello("HELLO VERSION MIN=1 MAX=99.99"));
    }

    /**
     * A browser reaching the SAM port, and a SAM command sent before the
     * hello. None of these open a session.
     */
    @Test
    public void anythingElseFailsTheGate() {
        assertFalse(hello("POST / HTTP/1.1"));
        assertFalse(hello("GET /?x=1 HTTP/1.1"));
        assertFalse(hello("GET / HTTP/1.1"));
        assertFalse(hello("PUT /a/b HTTP/1.1"));
        assertFalse(hello("DEST GENERATE"));
        assertFalse(hello("SESSION CREATE STYLE=TUNNEL"));
        assertFalse(hello("STREAM FORWARD ID=app HOST=evil.example PORT=9001"));
        assertFalse(hello(""));
        assertFalse(hello("HELLO"));
        assertFalse(hello("HELLO VER"));
        assertFalse(hello("GOODBYE VERSION"));
    }

    /**
     * @param line the first line of a connection
     * @return true if the SAM hello gate would accept it
     */
    private static boolean hello(String line) {
        Properties props;
        try {
            props = SAMUtils.parseParams(line);
        } catch (SAMException se) {
            throw new AssertionError("parseParams rejected " + line, se);
        }
        return SAMHandlerFactory.isHelloVersion((String) props.remove(SAMUtils.COMMAND),
                                                (String) props.remove(SAMUtils.OPCODE));
    }
}
