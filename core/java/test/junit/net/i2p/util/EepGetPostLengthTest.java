package net.i2p.util;

import static org.junit.Assert.*;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import org.junit.Test;

/**
 * Pins that a POST body's Content-length counts the bytes on the wire.
 *
 * <p>EepGet builds the whole request - headers and body together - as a String and
 * writes it to the socket as UTF-8. The header used to carry the String's character
 * count, so any non-ASCII body was declared shorter than what followed it: the server
 * read the declared prefix and dropped the rest, and the body arrived truncated,
 * usually with the last character cut in half. Nothing reported it - the fetch
 * returned true.
 *
 * @since 0.9.72+
 */
public class EepGetPostLengthTest {

    /** A URL with a host, enough for the request line. */
    private static final String URL = "http://example.test/post";

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public EepGetPostLengthTest() {}

    /**
     * The request EepGet would write for a body.
     *
     * @param body the POST body, as the caller wrote it
     * @return the request text, headers and body together
     * @throws IOException on a bad URL in the fixture
     */
    private static String requestFor(String body) throws IOException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        return new EepGet(ctx, false, null, -1, 0, null, URL, body).getRequest();
    }

    /**
     * The length the request advertises for its body.
     *
     * @param request the request text
     * @return the Content-length value
     */
    private static int advertised(String request) {
        String marker = "Content-length: ";
        int at = request.indexOf(marker);
        assertTrue("no Content-length in the request", at >= 0);
        int end = request.indexOf("\r\n", at);
        return Integer.parseInt(request.substring(at + marker.length(), end));
    }

    /**
     * The body the request actually carries, after the header terminator.
     *
     * @param request the request text
     * @return the body as the socket would see it
     */
    private static String bodyOf(String request) {
        int at = request.indexOf("\r\n\r\n");
        assertTrue("no header terminator in the request", at >= 0);
        return request.substring(at + 4);
    }

    /**
     * The defect: an accented body is one byte per accent longer on the wire than
     * the String is in Java.
     *
     * @throws IOException on a bad URL in the fixture
     */
    @Test
    public void testAnAccentedBodyIsDeclaredInBytes() throws IOException {
        String body = "caf\u00e9 \u4e2d\u6587 \ud83d\ude00";
        String request = requestFor(body);
        assertEquals(body, bodyOf(request));
        assertEquals("the header counted characters, not bytes",
                     DataHelper.getUTF8(body).length, advertised(request));
        assertEquals("the wire length of the fixture body", 17, advertised(request));
        // what the server actually reads: the headers, then exactly that many bytes
        String head = request.substring(0, request.indexOf("\r\n\r\n") + 4);
        assertEquals("the declared body is not the body on the wire",
                     DataHelper.getUTF8(head).length + advertised(request),
                     DataHelper.getUTF8(request).length);
    }

    /**
     * An ASCII body is unchanged by this: one character is one byte, and the common
     * POST must keep the length it always declared.
     *
     * @throws IOException on a bad URL in the fixture
     */
    @Test
    public void testAnAsciiBodyIsUnchanged() throws IOException {
        String body = "name=value&other=1234";
        String request = requestFor(body);
        assertEquals(body.length(), advertised(request));
        assertEquals(body, bodyOf(request));
    }

    /**
     * A lone surrogate is not a character, and the encoder turns it into the
     * replacement character. The header has to count what the encoder emits, which
     * is what the same call does here, so the pin is that the two agree.
     *
     * @throws IOException on a bad URL in the fixture
     */
    @Test
    public void testALoneSurrogateIsCountedAsTheEncoderWritesIt() throws IOException {
        String body = "tail\uDC00";
        String request = requestFor(body);
        assertEquals(DataHelper.getUTF8(body).length, advertised(request));
    }

    /**
     * An empty body is not a POST at all, so no Content-length is written and the
     * fix must not invent one.
     *
     * @throws IOException on a bad URL in the fixture
     */
    @Test
    public void testAnEmptyBodySendsNoContentLength() throws IOException {
        String request = requestFor("");
        assertEquals(-1, request.indexOf("Content-length: "));
    }
}
