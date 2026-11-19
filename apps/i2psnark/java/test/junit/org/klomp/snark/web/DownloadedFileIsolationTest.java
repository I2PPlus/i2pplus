package org.klomp.snark.web;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * A file from a torrent is data, not a page of the console.
 *
 * <p>Snark serves the download directory from the console's own host and port, so
 * an index.html inside a torrent is a same-origin document: its script can read the
 * stats, the logs and the address book, and post to the config pages. The torrent's
 * author picked the file. The isolation is the policy that takes its script away
 * plus the type demotion that takes the document away, and the caching that used to
 * pin that document in the browser for a month.
 *
 * @since 0.9.72+
 */
public class DownloadedFileIsolationTest {

    /** The type every active document is demoted to. */
    private static final String INERT = "application/octet-stream";

    /** The servlet whose MIME table and header writer are under test. */
    private static BasicServlet _servlet;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public DownloadedFileIsolationTest() {}

    /**
     * A servlet to ask about MIME types and to write headers with. No war and no
     * context beyond the global one.
     */
    @BeforeClass
    public static void setUp() {
        _servlet = new BasicServlet();
    }

    /**
     * The content type a file of that name is served as, before isolation.
     *
     * @param name the file name, extension decides the type
     * @return the MIME type the servlet maps that name to
     * @throws IOException on a reflection or MIME lookup failure
     */
    private static String typeOf(String name) throws IOException {
        return fileContent(name).getContentType();
    }

    /**
     * Content for one downloaded file. FileContent is private to the servlet, so it
     * is built by reflection rather than by widening its visibility for the tests.
     *
     * @param name the file name; only the name matters, the file need not exist
     * @return the content the servlet would serve for that name
     * @throws IOException on a reflection or MIME lookup failure
     */
    private static BasicServlet.HttpContent fileContent(String name) throws IOException {
        try {
            Class<?> cls = Class.forName("org.klomp.snark.web.BasicServlet$FileContent");
            Constructor<?> ctor = cls.getDeclaredConstructor(BasicServlet.class, File.class);
            ctor.setAccessible(true);
            return (BasicServlet.HttpContent) ctor.newInstance(_servlet, new File(name));
        } catch (ReflectiveOperationException e) {
            throw new IOException("could not build the file content for " + name, e);
        }
    }

    /**
     * Content that did not come from a torrent - the webapp's own resource shape -
     * so the isolation must not touch it.
     *
     * @param type the type it reports
     * @return a non-file content
     */
    private static BasicServlet.HttpContent packagedContent(String type) {
        return new BasicServlet.HttpContent() {

            @Override
            public String getContentType() { return type; }

            @Override
            public long getLastModified() { return 0; }

            @Override
            public int getCacheTime() { return 60; }

            @Override
            public long getContentLength() { return 10; }

            @Override
            public java.io.InputStream getInputStream() {
                return new java.io.ByteArrayInputStream(new byte[10]);
            }
        };
    }

    /**
     * A response that records nothing but what the writer puts on it.
     *
     * @return a mocked response with no content type set in advance
     */
    private static HttpServletResponse response() {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getContentType()).thenReturn(null);
        return resp;
    }

    /**
     * The types that run as documents or scripts all come back inert, whatever the
     * charset or the parameter list after them.
     */
    @Test
    public void testActiveTypesAreDemoted() {
        String[] active = {"text/html", "text/html; charset=utf-8", "application/xhtml+xml",
                           "image/svg+xml", "application/rss+xml", "text/xml", "application/xml",
                           "application/javascript", "text/javascript", "application/ecmascript"};
        for (String type : active)
            assertEquals(type + " was left active", INERT, BasicServlet.inertType(type));
    }

    /**
     * The types the console displays inline stay themselves: snark's own pages show
     * images, video, audio and text from the torrent.
     */
    @Test
    public void testDisplayTypesAreLeftAlone() {
        String[] inline = {"image/png", "image/gif", "video/mp4", "audio/mpegurl", "text/plain",
                           "application/pdf", "font/woff2"};
        for (String type : inline)
            assertEquals(type + " was demoted", type, BasicServlet.inertType(type));
        assertNull(BasicServlet.inertType(null));
    }

    /**
     * A downloaded HTML file is served as bytes with an attachment disposition, so
     * it cannot render, and under a policy that allows it no script at all.
     *
     * @throws IOException on a reflection or MIME lookup failure
     */
    @Test
    public void testDownloadedHtmlIsInert() throws IOException {
        String html = typeOf("index.html");
        assertEquals("the fixture's html type changed", "text/html", html);
        HttpServletResponse resp = response();
        _servlet.writeHeaders(resp, fileContent("index.html"), 1234);
        verify(resp).setContentType(INERT);
        verify(resp).setHeader("Content-Disposition", "attachment");
        verify(resp).setHeader(eq("Content-Security-Policy"), contains("sandbox"));
        verify(resp).setHeader(eq("Content-Security-Policy"), contains("script-src 'none'"));
        verify(resp).setHeader("Referrer-Policy", "no-referrer");
        verify(resp).setHeader("Cross-Origin-Resource-Policy", "same-origin");
    }

    /**
     * A downloaded image keeps its type and stays inline - only the isolation
     * headers go on it.
     *
     * @throws IOException on a reflection or MIME lookup failure
     */
    @Test
    public void testDownloadedImageStaysInline() throws IOException {
        HttpServletResponse resp = response();
        _servlet.writeHeaders(resp, fileContent("photo.png"), 500);
        verify(resp).setContentType("image/png");
        verify(resp, never()).setHeader(eq("Content-Disposition"), anyString());
        verify(resp).setHeader(eq("Content-Security-Policy"), contains("sandbox"));
    }

    /**
     * A downloaded file is not immutable: the same name in the download directory
     * can belong to the next torrent, and a pinned copy would come back with the
     * old type and the old isolation.
     *
     * @throws IOException on a reflection or MIME lookup failure
     */
    @Test
    public void testDownloadedFilesAreNotPinnedImmutable() throws IOException {
        HttpServletResponse resp = response();
        _servlet.writeHeaders(resp, fileContent("photo.png"), 500);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(resp).setHeader(eq("Cache-Control"), value.capture());
        assertTrue("the cache directive was missing: " + value.getValue(),
                   value.getValue().contains("max-age="));
        assertFalse("a downloaded file was pinned immutable: " + value.getValue(),
                    value.getValue().contains("immutable"));
    }

    /**
     * The webapp's own resources are not files from a torrent: they keep their type,
     * their scripts and their caching, and take none of the isolation headers.
     *
     * @throws IOException on a reflection or MIME lookup failure
     */
    @Test
    public void testPackagedResourcesAreNotIsolated() throws IOException {
        HttpServletResponse resp = response();
        _servlet.writeHeaders(resp, packagedContent("text/html"), 10);
        verify(resp).setContentType("text/html");
        verify(resp, never()).setHeader(eq("Content-Security-Policy"), anyString());
        verify(resp, never()).setHeader(eq("Content-Disposition"), anyString());
        verify(resp, never()).setHeader(eq("Cross-Origin-Resource-Policy"), anyString());
    }

    /**
     * A revalidated download answers 304 after a response.reset(), which took the
     * isolation headers off with everything else - and the browser keeps the copy it
     * is being told to reuse, so they have to go back on.
     *
     * @throws Exception on a reflection failure or a stream error in the fixture
     */
    @Test
    public void testARevalidatedDownloadKeepsTheIsolation() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getDateHeader("If-Modified-Since")).thenReturn(System.currentTimeMillis());
        HttpServletResponse resp = response();
        when(resp.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
        BasicServlet.HttpContent content = fileContent("index.html");

        assertFalse(_servlet.passConditionalHeaders(req, resp, content));

        verify(resp).setStatus(304);
        verify(resp).setHeader(eq("Content-Security-Policy"), contains("sandbox"));
        verify(resp).setHeader("Cross-Origin-Resource-Policy", "same-origin");
    }
}
