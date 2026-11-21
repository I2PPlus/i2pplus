package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.EOFException;

import org.junit.Test;

import net.i2p.client.I2PSessionException;

/**
 * Unit tests for {@link I2PTunnel#keyFileFailure}, the helper that turns a
 * failed key-file load into something an operator can act on. The session layer
 * only says it could not read the stream; the reason - how many bytes were
 * missing, which key type is unsupported - is on the innermost cause, and the
 * old message ("Can't create socket manager") discarded it.
 *
 * @since 0.9.72+
 */
public class KeyFileFailureTest {

    /**
     * Test case that builds failure chains the session layer produces and checks
     * what the operator is told about each.
     */
    public KeyFileFailureTest() {
    }

    /** A truncated key file, as the data layer reports it. */
    @Test
    public void testReportsTheInnermostReason() {
        EOFException eof = new EOFException("EOF after reading 29 bytes of 32 byte value");
        I2PSessionException ise = new I2PSessionException("Error reading the destination key stream", eof);
        String msg = I2PTunnel.keyFileFailure("Cannot read the private key file infobase.dat", ise);
        assertEquals("Cannot read the private key file infobase.dat -> " +
                     "EOF after reading 29 bytes of 32 byte value", msg);
    }

    /** The session layer's own wording must not stand in for the reason. */
    @Test
    public void testDoesNotStopAtTheGenericSessionMessage() {
        EOFException eof = new EOFException("EOF after reading 0 bytes of 256 byte value");
        I2PSessionException ise = new I2PSessionException("Error reading the destination key stream", eof);
        String msg = I2PTunnel.keyFileFailure("Cannot read the private key file foo.dat", ise);
        assertFalse("the generic message hides the defect: " + msg,
                    msg.contains("Error reading the destination key stream"));
        assertTrue(msg.contains("256 byte value"));
    }

    /** A cause with nothing to say is named by its class rather than left blank. */
    @Test
    public void testFallsBackToTheClassNameWithoutAReason() {
        I2PSessionException ise = new I2PSessionException("Error reading the destination key stream",
                                                          new EOFException());
        String msg = I2PTunnel.keyFileFailure("Cannot read the private key file bar.dat", ise);
        assertEquals("Cannot read the private key file bar.dat -> EOFException", msg);
    }

    /** A chain of any depth is walked to the end. */
    @Test
    public void testWalksAChainOfAnyDepth() {
        RuntimeException deep = new RuntimeException("Unsupported signature type: 42");
        I2PSessionException ise = new I2PSessionException("Error reading the destination key stream",
                new java.io.IOException("read failed", deep));
        String msg = I2PTunnel.keyFileFailure("Cannot read the private key file baz.dat", ise);
        assertEquals("Cannot read the private key file baz.dat -> Unsupported signature type: 42", msg);
    }

    /** A failure with no cause at all still reports its own message. */
    @Test
    public void testSingleLinkChain() {
        I2PSessionException ise = new I2PSessionException("Router does not support sub-sessions");
        String msg = I2PTunnel.keyFileFailure("Cannot build a session", ise);
        assertEquals("Cannot build a session -> Router does not support sub-sessions", msg);
    }
}
