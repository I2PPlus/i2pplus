package org.klomp.snark.web;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

/**
 * A range answer has to say how much body it sends.
 *
 * <p>Snark serves the download directory straight from the console's origin, and a
 * resume-capable downloader asks for a piece of it. When the answer is 416 the
 * servlet writes no body at all, and it used to advertise the whole file length
 * while doing so: a client that honours Content-Length waits for bytes that never
 * arrive. The multi-range punt lands on the same branch, so a well-formed request
 * gets the malformed answer too.
 *
 * @since 0.9.72+
 */
public class RangeResponseTest {

    /** The bytes the fixture file holds. */
    private static final byte[] FILE = "0123456789".getBytes(StandardCharsets.US_ASCII);

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public RangeResponseTest() {}

    /**
     * Content of a known length that records whether its stream was closed.
     */
    private static class TrackedContent implements BasicServlet.HttpContent {

        /** The body to hand out. */
        private final byte[] _data;

        /** Whether getInputStream() has been closed. */
        private boolean _closed;

        /**
         * Content over the given bytes.
         *
         * @param data the bytes the fixture file holds
         */
        TrackedContent(byte[] data) {
            _data = data;
        }

        /**
         * The type the file is served as.
         *
         * @return a generic binary type
         */
        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        /**
         * When the file changed.
         *
         * @return no modification time
         */
        @Override
        public long getLastModified() {
            return 0;
        }

        /**
         * How long the browser may keep it.
         *
         * @return no cache time, so the servlet writes no Cache-Control max-age
         */
        @Override
        public int getCacheTime() {
            return -1;
        }

        /**
         * How many bytes the whole file holds.
         *
         * @return the length of the body
         */
        @Override
        public long getContentLength() {
            return _data.length;
        }

        /**
         * The body, with a stream that says when it was closed.
         *
         * @return a stream over the whole body
         */
        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(_data) {

                /**
                 * Records the close so the test can require it.
                 */
                @Override
                public void close() throws IOException {
                    _closed = true;
                }
            };
        }

        /**
         * Whether the stream handed out by getInputStream() was closed.
         *
         * @return true if closed
         */
        boolean isClosed() {
            return _closed;
        }
    }

    /**
     * A request asking for one range header.
     *
     * @param range the Range header value, null for no header at all
     * @return the request to answer
     */
    private static HttpServletRequest request(String range) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        List<String> headers = range == null ? null : Collections.singletonList(range);
        Enumeration<String> en = headers == null ? null : Collections.enumeration(headers);
        when(req.getHeaders("Range")).thenReturn(en);
        return req;
    }

    /**
     * A response whose body is captured and whose headers are recorded.
     *
     * @param out where the body bytes land
     * @return the response to write to
     * @throws IOException on a stream error in the fixture
     */
    private static HttpServletResponse response(ByteArrayOutputStream out) throws IOException {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        ServletOutputStream sos = mock(ServletOutputStream.class);
        doAnswer(inv -> {
            out.write((byte[]) inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(sos).write(any(byte[].class), anyInt(), anyInt());
        when(resp.getOutputStream()).thenReturn(sos);
        return resp;
    }

    /**
     * The defect: an unsatisfiable range promised the whole file and sent nothing.
     *
     * @throws IOException on a stream error in the fixture
     */
    @Test
    public void testAnUnsatisfiableRangeAdvertisesNoBody() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServletResponse resp = response(out);
        new BasicServlet().sendData(request("bytes=50000000-"), resp, new TrackedContent(FILE));

        verify(resp).setStatus(416);
        verify(resp).setContentLength(0);
        verify(resp, never()).setContentLength(FILE.length);
        verify(resp).setHeader("Content-Range", "bytes */" + FILE.length);
        assertEquals("a 416 wrote a body", 0, out.size());
    }

    /**
     * A multi-range request is punted to the same 416, so a request with nothing
     * wrong with it gets the malformed answer too.
     *
     * @throws IOException on a stream error in the fixture
     */
    @Test
    public void testAMultiRangePuntAdvertisesNoBody() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServletResponse resp = response(out);
        new BasicServlet().sendData(request("bytes=0-1,4-5"), resp, new TrackedContent(FILE));

        verify(resp).setStatus(416);
        verify(resp).setContentLength(0);
        verify(resp).setHeader("Content-Range", "bytes */" + FILE.length);
        assertEquals("a 416 wrote a body", 0, out.size());
    }

    /**
     * A satisfiable range still gets its four bytes and the length of those bytes,
     * so a resume keeps working.
     *
     * @throws IOException on a stream error in the fixture
     */
    @Test
    public void testASatisfiableRangeStillGetsItsBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServletResponse resp = response(out);
        new BasicServlet().sendData(request("bytes=2-5"), resp, new TrackedContent(FILE));

        verify(resp).setStatus(206);
        verify(resp).setContentLength(4);
        verify(resp).setHeader("Content-Range", "bytes 2-5/" + FILE.length);
        assertEquals("2345", new String(out.toByteArray(), StandardCharsets.US_ASCII));
    }

    /**
     * Every range answer closes the file it opened. The 416 branch returns without
     * ever reaching copy(), which is what closes the streams on the other two paths,
     * so it needs a close of its own.
     *
     * @throws IOException on a stream error in the fixture
     */
    @Test
    public void testEveryRangeAnswerClosesTheFile() throws IOException {
        TrackedContent not = send("bytes=50000000-");
        assertTrue("the 416 left the file open", not.isClosed());

        TrackedContent partial = send("bytes=2-5");
        assertTrue("the 206 left the file open", partial.isClosed());

        TrackedContent whole = send(null);
        assertTrue("the whole-file answer left the file open", whole.isClosed());
    }

    /**
     * Answers one range request over the fixture file and throws the answer away.
     *
     * @param range the Range header value, null for no header at all
     * @return the content that was served, to ask whether its file was closed
     * @throws IOException on a stream error in the fixture
     */
    private static TrackedContent send(String range) throws IOException {
        TrackedContent content = new TrackedContent(FILE);
        new BasicServlet().sendData(request(range), response(new ByteArrayOutputStream()), content);
        return content;
    }
}
