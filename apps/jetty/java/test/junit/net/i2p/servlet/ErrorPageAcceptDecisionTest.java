package net.i2p.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.junit.Test;

/**
 * The two choices the eepsite's error handler makes from the client's headers:
 * which content type to write an error body as, and which charset to write it
 * in. Both are taken from a header the attacker sends, and Jetty's own handler
 * ranks those headers by quality with a sort that is quadratic in the number of
 * values, so the choices here are made with one pass over the values instead -
 * one pass that still reads the <code>q</code> values, and that only picks a
 * type the handler can actually write a body for.
 *
 * @since 0.9.72+
 */
public class ErrorPageAcceptDecisionTest {

    /** Runs the decisions; it holds no state. */
    public ErrorPageAcceptDecisionTest() {}

    /** One value, one name, in the order it was sent. */
    @Test
    public void oneValueIsOneName() {
        assertEquals(Arrays.asList("text/html"), I2PErrorPageErrorHandler.headerValues(Arrays.asList("text/html")));
    }

    /**
     * A comma-separated value is one name per element, in the client's order:
     * the order is what decides the answer now that the quality values are not
     * ranked.
     */
    @Test
    public void commasSplitInOrder() {
        List<String> values = I2PErrorPageErrorHandler.headerValues(Arrays.asList("text/html, application/xhtml+xml, */*"));
        assertEquals(Arrays.asList("text/html", "application/xhtml+xml", "*/*"), values);
    }

    /** Several header lines are concatenated, first line first. */
    @Test
    public void severalLinesConcatenate() {
        List<String> values = I2PErrorPageErrorHandler.headerValues(Arrays.asList("text/plain", "text/html"));
        assertEquals(Arrays.asList("text/plain", "text/html"), values);
    }

    /**
     * A parameter after a semicolon is not part of the name, and dropping it is
     * what removes the <code>q</code> value along with everything else the client
     * attached.
     */
    @Test
    public void parametersAreDropped() {
        List<String> values = I2PErrorPageErrorHandler.headerValues(Arrays.asList("text/html;q=0.9;level=1, text/plain;q=1"));
        assertEquals(Arrays.asList("text/html", "text/plain"), values);
    }

    /** Whitespace and empty elements - a trailing comma, ",," - carry no name. */
    @Test
    public void blanksAreDropped() {
        List<String> values = I2PErrorPageErrorHandler.headerValues(Arrays.asList("  text/html , , text/plain, "));
        assertEquals(Arrays.asList("text/html", "text/plain"), values);
        assertEquals(Collections.<String> emptyList(), I2PErrorPageErrorHandler.headerValues(Arrays.asList("", ",", "  ")));
    }

    /**
     * The shape the report's lab used to price the sort - 300 elements in one
     * header - is read in full and in order, so nothing is silently truncated to
     * make it cheap.
     */
    @Test
    public void aLargeHeaderIsReadInFull() {
        StringBuilder buf = new StringBuilder(8192);
        for (int i = 0; i < 300; i++) {
            if (i > 0) {buf.append(',');}
            buf.append("t").append(i).append(";q=0.").append(i % 9 + 1);
        }
        List<String> values = I2PErrorPageErrorHandler.headerValues(Collections.singletonList(buf.toString()));
        assertEquals(300, values.size());
        assertEquals("t0", values.get(0));
        assertEquals("t299", values.get(299));
    }

    /** A bare <code>*</code> means no preference, and UTF-8 answers that best. */
    @Test
    public void aWildcardCharsetIsAnsweredWithUtf8() {
        assertEquals(StandardCharsets.UTF_8.name(), I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("*")));
        assertEquals(StandardCharsets.UTF_8.name(), I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("nosuch", "*")));
    }

    /** The first name this JVM can encode with wins, in the client's order. */
    @Test
    public void theFirstUsableCharsetWins() {
        assertEquals(StandardCharsets.ISO_8859_1.name(),
                     I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("ISO-8859-1", "UTF-8")));
        assertEquals(StandardCharsets.UTF_8.name(),
                     I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("UTF-8", "ISO-8859-1")));
    }

    /** A name is reported as the JVM spells it, so the header cannot pick an alias at random. */
    @Test
    public void theCharsetIsReportedByItsCanonicalName() {
        assertEquals(StandardCharsets.UTF_8.name(), I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("utf-8")));
        assertEquals(StandardCharsets.US_ASCII.name(), I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("ascii")));
    }

    /**
     * A name that is not a name, and a name no JVM here can encode with, are
     * skipped rather than answered with: an encoding the JVM does not have would
     * fail the response for every host.
     */
    @Test
    public void unusableCharsetsAreSkipped() {
        assertEquals(StandardCharsets.UTF_8.name(),
                     I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("not a charset!", "UTF-8")));
        assertEquals(StandardCharsets.UTF_8.name(),
                     I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("x-i2p-made-up", "UTF-8")));
    }

    /** Nothing usable at all means no writer, which is what Jetty does too. */
    @Test
    public void noUsableCharsetMeansNoWriter() {
        assertNull(I2PErrorPageErrorHandler.preferredCharset(Collections.<String> emptyList()));
        assertNull(I2PErrorPageErrorHandler.preferredCharset(Arrays.asList("not a charset!", "x-i2p-made-up")));
    }

    /**
     * The handler asks {@link HttpFields} for the header values and treats an
     * empty list as "the client sent no such header", so that has to be what an
     * absent header comes back as - not null.
     */
    @Test
    public void anAbsentHeaderIsAnEmptyList() {
        HttpFields fields = new HttpFields();
        List<String> absent = fields.getValuesList(HttpHeader.ACCEPT);
        assertNotNull(absent);
        assertTrue(absent.isEmpty());
        fields.add(new HttpField(HttpHeader.ACCEPT, "text/html;q=0.5"));
        List<String> present = fields.getValuesList(HttpHeader.ACCEPT);
        assertEquals(1, present.size());
        assertEquals("text/html;q=0.5", present.get(0));
    }

    /**
     * A header repeated on several lines comes back as one value per line, which
     * is what the single pass over the values then reads in order.
     */
    @Test
    public void repeatedHeadersComeBackInOrder() {
        HttpFields fields = new HttpFields();
        List<String> lines = new ArrayList<>(3);
        lines.add("text/plain");
        lines.add("text/html");
        lines.add("*/*");
        for (String line : lines) {
            fields.add(new HttpField(HttpHeader.ACCEPT, line));
        }
        List<String> values = fields.getValuesList(HttpHeader.ACCEPT);
        assertEquals(lines, values);
        assertEquals(Arrays.asList("text/plain", "text/html", "*/*"),
                     I2PErrorPageErrorHandler.headerValues(values));
    }

    /**
     * The six argument form writes a body for three types and nothing at all for
     * any other, and it marks the request handled before Jetty's loop can move
     * on, so a type outside this set can only ever buy an empty error page.
     */
    @Test
    public void onlyThreeTypesGetABody() {
        assertTrue(I2PErrorPageErrorHandler.writesBody("text/html"));
        assertTrue(I2PErrorPageErrorHandler.writesBody("text/*"));
        assertTrue(I2PErrorPageErrorHandler.writesBody("*/*"));
        assertFalse(I2PErrorPageErrorHandler.writesBody("text/plain"));
        assertFalse(I2PErrorPageErrorHandler.writesBody("application/json"));
        assertFalse(I2PErrorPageErrorHandler.writesBody("application/xhtml+xml"));
    }

    /**
     * The load-bearing case: a scripted client that asks for JSON first and HTML
     * second still gets the HTML page. Picking the first name the client wrote
     * chose application/json, the six argument form wrote nothing for it, and the
     * client got an empty body beside its 404 - the HTML it had also asked for.
     */
    @Test
    public void aTypeThatCannotBeWrittenIsPassedOver() {
        assertEquals("text/html",
                     I2PErrorPageErrorHandler.preferredType(Arrays.asList("application/json, text/html")));
        assertEquals("text/*",
                     I2PErrorPageErrorHandler.preferredType(Arrays.asList("image/png", "text/plain", "text/*")));
    }

    /**
     * The quality is honoured, in one pass rather than by Jetty's quadratic
     * sort: the most-wanted writable type wins even when the client named it
     * second, and a type the client named first but wanted less gets passed over.
     */
    @Test
    public void theMostWantedWritableTypeWins() {
        assertEquals("text/html",
                     I2PErrorPageErrorHandler.preferredType(
                             Arrays.asList("text/*;q=0.5, text/html;q=0.9")));
        assertEquals("text/html",
                     I2PErrorPageErrorHandler.preferredType(
                             Arrays.asList("text/html;q=0.9, text/*;q=0.5")));
        assertEquals("text/*",
                     I2PErrorPageErrorHandler.preferredType(
                             Arrays.asList("text/html;q=0.2, text/*;q=0.7")));
    }

    /** Equal quality, as a browser sends it, leaves the client's own order. */
    @Test
    public void aTieGoesToTheClientOrder() {
        assertEquals("text/*",
                     I2PErrorPageErrorHandler.preferredType(Arrays.asList("text/*, text/html")));
        assertEquals("text/html",
                     I2PErrorPageErrorHandler.preferredType(Arrays.asList("text/html, text/*")));
        assertEquals("text/html",
                     I2PErrorPageErrorHandler.preferredType(
                             Arrays.asList("text/html;q=1, text/*;q=1.0")));
    }

    /**
     * A type the client rejected with q=0 is dropped, as Jetty's own sort drops
     * it: serving a page in the one form the sender refused is not a preference
     * the sender asked for.
     */
    @Test
    public void aRejectedTypeIsDropped() {
        assertEquals("*/*",
                     I2PErrorPageErrorHandler.preferredType(Arrays.asList("text/html;q=0, */*")));
        assertNull(I2PErrorPageErrorHandler.preferredType(Arrays.asList("text/html;q=0")));
        // and a q that is not a number at all is worth nothing, which is how
        // Jetty reads it too
        assertNull(I2PErrorPageErrorHandler.preferredType(Arrays.asList("text/html;q=nonsense")));
    }

    /** A header naming nothing this class can write means no body at all. */
    @Test
    public void nothingWritableMeansNoType() {
        assertNull(I2PErrorPageErrorHandler.preferredType(Arrays.asList("application/json")));
        assertNull(I2PErrorPageErrorHandler.preferredType(Arrays.asList("image/png, image/jpeg")));
        assertNull(I2PErrorPageErrorHandler.preferredType(Collections.<String> emptyList()));
        assertNull(I2PErrorPageErrorHandler.preferredType(Arrays.asList("", ",", "  ")));
    }

    /**
     * The quality is read from anywhere in the parameters after the type, and a
     * parameter that is not q leaves the entry wanted as usual - a browser sends
     * text/html with a level or a charset beside it.
     */
    @Test
    public void otherParametersDoNotMoveTheQuality() {
        assertEquals(0.5, I2PErrorPageErrorHandler.qualityOf(";q=0.5;level=1"), 0);
        assertEquals(0.5, I2PErrorPageErrorHandler.qualityOf(";level=1;q=0.5"), 0);
        assertEquals(1, I2PErrorPageErrorHandler.qualityOf(";level=1"), 0);
        assertEquals(1, I2PErrorPageErrorHandler.qualityOf(";charset=utf-8"), 0);
        assertEquals(1, I2PErrorPageErrorHandler.qualityOf(""), 0);
        assertEquals("text/html",
                     I2PErrorPageErrorHandler.preferredType(
                             Arrays.asList("text/html;q=0.5;level=1, */*;q=0.4")));
    }

    /**
     * A response whose output stream has already been taken - normal for an
     * error page written after the handler started a body - gives no writer
     * rather than an IllegalStateException out of the handler and into the
     * container. Jetty's own loop catches per charset and falls through to null.
     *
     * @throws Exception never - stubbing {@code getWriter()}, which declares
     *                   IOException, is the only reason the clause is there
     */
    @Test
    public void aResponseThatCannotTakeAWriterGivesNoWriter() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenThrow(new IllegalStateException("getOutputStream() already called"));
        Writer writer = I2PErrorPageErrorHandler.writerFor(Arrays.asList("UTF-8, ISO-8859-1"), response);
        assertNull("the exception escaped the handler", writer);
        verify(response).setCharacterEncoding(StandardCharsets.UTF_8.name());
        verify(response).setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
    }

    /**
     * The same response taken in order: the first charset that works is the one
     * used, and a name the JVM cannot encode is never put on the response. With
     * no header at all the default is the ISO-8859-1 Jetty would have used.
     *
     * @throws Exception never - stubbing {@code getWriter()}, which declares
     *                   IOException, is the only reason the clause is there
     */
    @Test
    public void theFirstCharsetThatWorksIsUsed() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        Writer writer = I2PErrorPageErrorHandler.writerFor(Arrays.asList("x-i2p-made-up, UTF-8"), response);
        assertNotNull(writer);
        verify(response).setCharacterEncoding(StandardCharsets.UTF_8.name());
        verify(response, never()).setCharacterEncoding("x-i2p-made-up");
        writer = I2PErrorPageErrorHandler.writerFor(Collections.<String> emptyList(), response);
        assertNotNull(writer);
        verify(response).setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
    }
}
