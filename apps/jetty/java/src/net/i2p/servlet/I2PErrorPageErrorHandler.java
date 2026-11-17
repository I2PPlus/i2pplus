// License: Apache 2.0 / EPL 1.0. See docs/LICENSES.md
package net.i2p.servlet;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
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
 * first type the client named, in the order it named them rather than by
 * <code>q</code> value, and the first charset the JVM can encode with. Jetty's
 * own loop over the sorted types stops at the first one, because the six
 * argument form it calls marks the request handled before writing anything, so
 * taking the first value here costs nothing the sorted version gained.
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
     * Writes the error body with the first content type the client's Accept
     * header names, chosen without sorting the header.
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
        List<String> types = headerValues(values);
        if (types.isEmpty()) {
            // an Accept header with nothing usable in it: handled, no body
            baseRequest.setHandled(true);
            return;
        }
        generateAcceptableResponse(baseRequest, request, response, code, message, types.get(0));
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
        List<String> values = baseRequest.getHttpFields().getValuesList(HttpHeader.ACCEPT_CHARSET);
        String charset;
        if (values.isEmpty()) {
            charset = StandardCharsets.ISO_8859_1.name();
        } else {
            charset = preferredCharset(headerValues(values));
            if (charset == null) {return null;}
        }
        response.setCharacterEncoding(charset);
        return response.getWriter();
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
     * Which charset to write the error body in. A bare <code>*</code> means the
     * client has no preference and UTF-8 is the better answer for a page nobody
     * else is reading; otherwise the first name this JVM can encode with.
     *
     * @param names the names from Accept-Charset, in the order the client sent them
     * @return the charset to use, or null if none of them is usable
     */
    static String preferredCharset(List<String> names) {
        for (String name : names) {
            if ("*".equals(name)) {return StandardCharsets.UTF_8.name();}
            try {
                return Charset.forName(name).name();
            } catch (IllegalCharsetNameException e) {
                // not a name at all, try the next one the client named
            } catch (UnsupportedCharsetException e) {
                // a name this JVM cannot encode with, try the next one
            }
        }
        return null;
    }
}
