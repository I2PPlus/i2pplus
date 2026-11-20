// License: Apache 2.0 / EPL 1.0. See docs/LICENSES.md
package net.i2p.servlet;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;

/**
 * Error handler for the eepsite, picking the error response's content type and
 * charset from the client's headers without ranking them.
 *
 * <p>Jetty's own handler asks {@link org.eclipse.jetty.http.HttpFields} for the
 * Accept and Accept-Charset values as a quality-sorted list, and that sort is
 * quadratic in the number of values the sender sent (CVE-2020-27223). The
 * header is the attacker's, the work is the server's, and the path runs for any
 * error status without a mapped page - a 405, a 413, a 500 - so one request can
 * make the container spend CPU the sender sets. This handler reads the same two
 * headers with a single pass over their values instead.
 *
 * <p>What the client is given is otherwise what Jetty would have given: the
 * type the client asked for most strongly, and the first charset the JVM can
 * encode with. The <code>q</code> values are read in the same single pass, so
 * the ranking is honoured without the quadratic sort, and a type the six
 * argument form cannot write a body for is passed over rather than chosen - it
 * handles only <code>text/html</code>, <code>text/&#42;</code> and
 * <code>&#42;/&#42;</code>, and for anything else it marks the request handled
 * and writes nothing, so picking a type the client named first but this class
 * cannot serve costs the body the client would otherwise have been given.
 *
 * @since 0.9.72+
 */
public class I2PErrorPageErrorHandler extends ErrorPageErrorHandler {

    /**
     * Constructor. {@link I2PDefaultServlet} installs this handler on the
     * context and adds the error pages to it, so a bare instance is the
     * starting state.
     */
    public I2PErrorPageErrorHandler() {}

    /**
     * Writes the error body with the type the client's Accept header asks for
     * most strongly among the ones a body can be written as, chosen without
     * sorting the header.
     *
     * @param baseRequest the Jetty request, for its headers and handled state
     * @param request the servlet request
     * @param response the servlet response to write the body to
     * @param code the status code to report
     * @param message the status message to report
     */
    @Override
    protected void generateAcceptableResponse(Request baseRequest, HttpServletRequest request,
                                              HttpServletResponse response, int code, String message) throws IOException {
        List<String> values = baseRequest.getHttpFields().getValuesList(HttpHeader.ACCEPT);
        if (values.isEmpty()) {
            // no Accept header at all, Jetty's own default
            generateAcceptableResponse(baseRequest, request, response, code, message, MimeTypes.Type.TEXT_HTML.asString());
            return;
        }
        String type = preferredType(values);
        if (type == null) {
            // nothing the client named that a body can be written as: handled,
            // no body - the same answer Jetty gives when every entry it tries
            // falls through the six argument form's switch
            baseRequest.setHandled(true);
            return;
        }
        generateAcceptableResponse(baseRequest, request, response, code, message, type);
    }

    /**
     * Returns a writer in the first charset the client's Accept-Charset header
     * names and this JVM can encode with, chosen without sorting the header.
     *
     * @param baseRequest the Jetty request, for its headers
     * @param request the servlet request
     * @param response the servlet response to take the writer from
     * @return the writer, or null if no charset the client named is usable
     */
    @Override
    protected Writer getAcceptableWriter(Request baseRequest, HttpServletRequest request,
                                         HttpServletResponse response) throws IOException {
        return writerFor(baseRequest.getHttpFields().getValuesList(HttpHeader.ACCEPT_CHARSET), response);
    }

    /**
     * A writer for the response, in the first charset the client's Accept-Charset
     * values name that this JVM can encode with and the response will take.
     *
     * @param values the raw Accept-Charset header values, one per header line,
     *               empty for a request that sent no such header
     * @param response the servlet response to take the writer from
     * @return the writer, or null if no charset is usable or none can be put on
     *         the response
     */
    static Writer writerFor(List<String> values, HttpServletResponse response) {
        // No header at all means Jetty's own default, and it is tried the same
        // way as a named charset, below.
        List<String> names = values.isEmpty() ?
                Collections.singletonList(StandardCharsets.ISO_8859_1.name()) : headerValues(values);
        // Each candidate on its own, in its own catch, as Jetty's loop does:
        // setCharacterEncoding() and getWriter() both throw IllegalStateException
        // once the response's output stream has been taken, and that is normal
        // for an error page written after the handler had started a body. Let it
        // out of here and the container gets an exception where Jetty gave the
        // client a body-less response.
        for (String name : names) {
            String charset = charsetFor(name);
            if (charset == null) {
                continue;
            }
            try {
                response.setCharacterEncoding(charset);
                return response.getWriter();
            } catch (Exception e) {
                // this charset could not be put on the response, try the next
                // one the client named, and give up with no body if there is
                // none left
            }
        }
        return null;
    }

    /**
     * The usable names in a comma-separated header, in the order the client sent
     * them: each value split on commas, each parameter after a semicolon - the
     * <code>q</code> value included - dropped, blanks removed. One pass over the
     * characters, which is the point.
     *
     * @param values the raw header values, one per header line
     * @return the names, never null, empty if the header held nothing usable
     */
    static List<String> headerValues(List<String> values) {
        List<String> rv = new ArrayList<>(values.size());
        for (String value : values) {
            for (String token : value.split(",")) {
                int semi = token.indexOf(';');
                if (semi >= 0) {token = token.substring(0, semi);}
                token = token.trim();
                if (!token.isEmpty()) {rv.add(token);}
            }
        }
        return rv;
    }

    /**
     * Which Accept value to write the error body as: the entry with the highest
     * <code>q</code> among the ones the six argument form can write a body for.
     * A tie goes to the one the client named first, and an entry the client
     * rejected with <code>q=0</code> is dropped, as Jetty's own sort drops it.
     *
     * <p>Skipping the unwritable entries is the point. Jetty's loop stops at the
     * first entry it tries, because {@link org.eclipse.jetty.server.handler.ErrorHandler}
     * marks the request handled before it gets here, so choosing an entry this
     * class cannot serve is not a harmless choice - it costs the client the body
     * that a later entry would have produced.
     *
     * @param values the raw Accept header values, one per header line
     * @return the type to write the body as, or null if the client named none
     *         this class can serve
     */
    static String preferredType(List<String> values) {
        String best = null;
        double bestQuality = -1;
        for (String value : values) {
            for (String token : value.split(",")) {
                String type = token;
                double quality = 1;
                int semi = type.indexOf(';');
                if (semi >= 0) {
                    quality = qualityOf(type.substring(semi + 1));
                    type = type.substring(0, semi);
                }
                type = type.trim();
                if (type.isEmpty() || quality <= 0 || !writesBody(type)) {
                    continue;
                }
                if (quality > bestQuality) {
                    bestQuality = quality;
                    best = type;
                }
            }
        }
        return best;
    }

    /**
     * Whether the six argument form writes a body for a content type at all. It
     * switches on exactly these three strings and returns without writing for
     * anything else, so a type outside this set can only ever buy an empty body.
     *
     * @param type the content type, as the client wrote it, parameters removed
     * @return true if a body is written for that type
     */
    static boolean writesBody(String type) {
        return "text/html".equals(type) || "text/*".equals(type) || "*/*".equals(type);
    }

    /**
     * The <code>q</code> value of an entry, from the parameters after the first
     * semicolon. An entry with no <code>q</code> parameter is wanted as much as
     * one with <code>q=1</code>; one whose <code>q</code> does not parse is
     * worth nothing, which is how Jetty reads it too.
     *
     * @param params the text after the first semicolon of the entry
     * @return the quality, 0 for an entry the client rejected or could not name
     */
    static double qualityOf(String params) {
        for (String param : params.split(";")) {
            int eq = param.indexOf('=');
            if (eq < 0 || !"q".equals(param.substring(0, eq).trim())) {
                continue;
            }
            try {
                return Double.parseDouble(param.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                // not a number the client meant, treat it as rejected
                return 0;
            }
        }
        return 1;
    }

    /**
     * Which charset to write the error body in. A bare <code>*</code> means the
     * client has no preference and UTF-8 is the better answer for a page nobody
     * else is reading; otherwise the first name this JVM can encode with.
     *
     * @param names the names from Accept-Charset, in the order the client sent them
     * @return the charset to use, or null if none of them is usable
     */
    static String preferredCharset(List<String> names) {
        for (String name : names) {
            String charset = charsetFor(name);
            if (charset != null) {
                return charset;
            }
        }
        return null;
    }

    /**
     * The name to put on the response for one charset the client named, or null
     * for a name this JVM cannot encode with.
     *
     * @param name the charset name, as the client wrote it
     * @return the charset to use, or null if this name is not usable
     */
    static String charsetFor(String name) {
        if ("*".equals(name)) {
            return StandardCharsets.UTF_8.name();
        }
        try {
            return Charset.forName(name).name();
        } catch (IllegalCharsetNameException e) {
            // not a name at all, try the next one the client named
        } catch (UnsupportedCharsetException e) {
            // a name this JVM cannot encode with, try the next one
        }
        return null;
    }
}
