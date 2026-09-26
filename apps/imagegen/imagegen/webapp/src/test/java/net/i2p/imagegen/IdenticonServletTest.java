package net.i2p.imagegen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docuverse.identicon.IdenticonCache;
import com.docuverse.identicon.IdenticonUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.ServletConfig;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.data.Hash;
import org.junit.Test;

/**
 * Tests for the identicon request contract in {@link IdenticonServlet}:
 * the documented size limits and defaults, how the "c" parameter is turned
 * into a render code, and that a cache hit returns the cached image.
 */
public class IdenticonServletTest {

    private static final byte[] PNG_BYTES = new byte[] { 1, 2, 3, 4 };

    /**
     * Cache used by the servlet under test, loaded by name through the
     * "cacheProvider" init parameter.
     */
    public static class TestCache implements IdenticonCache {
        static final Map<String, byte[]> ENTRIES = new ConcurrentHashMap<>();

        @Override
        public byte[] get(String key) {
            return ENTRIES.get(key);
        }

        @Override
        public void add(String key, byte[] imageData) {
            ENTRIES.put(key, imageData);
        }

        @Override
        public void remove(String key) {
            ENTRIES.remove(key);
        }

        @Override
        public void removeAll() {
            ENTRIES.clear();
        }
    }

    @Test
    public void sizeDefaultsTo32WhenMissing() {
        assertEquals(32, IdenticonServlet.parseSize(null));
    }

    @Test
    public void sizeDefaultsTo32WhenNotANumber() {
        assertEquals(32, IdenticonServlet.parseSize("big"));
    }

    @Test
    public void sizeClampedToMinimum() {
        assertEquals(16, IdenticonServlet.parseSize("1"));
        assertEquals(16, IdenticonServlet.parseSize("16"));
    }

    @Test
    public void sizeClampedToMaximum() {
        assertEquals(1024, IdenticonServlet.parseSize("2048"));
        assertEquals(1024, IdenticonServlet.parseSize("1024"));
    }

    @Test
    public void sizeInRangeIsUnchanged() {
        assertEquals(64, IdenticonServlet.parseSize("64"));
    }

    @Test
    public void numericCodeIsUsedAsIs() {
        assertEquals(1234, IdenticonServlet.parseCode("1234"));
        assertEquals(-1, IdenticonServlet.parseCode("-1"));
    }

    @Test
    public void base32CodeIsReducedToItsHashCode() {
        byte[] data = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) (i + 1);
        Hash h = Hash.create(data);
        assertEquals(Arrays.hashCode(h.getData()), IdenticonServlet.parseCode(h.toBase32()));
    }

    @Test
    public void nonHashStringIsHashedAsString() {
        assertEquals("peerhash".hashCode(), IdenticonServlet.parseCode("peerhash"));
    }

    @Test
    public void cachedImageIsReturned() throws Exception {
        int code = IdenticonServlet.parseCode("777");
        String etag = IdenticonUtil.getIdenticonETag(code, 32, 1);
        TestCache.ENTRIES.put(etag, PNG_BYTES);
        try {
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public boolean isReady() { return true; }

                @Override
                public void setWriteListener(WriteListener writeListener) { /* unused */ }

                @Override
                public void write(int b) throws IOException { written.write(b); }
            });

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getParameter("c")).thenReturn("777");
            when(request.getParameter("s")).thenReturn("32");
            // not an exact match, so the request is not answered as unmodified
            when(request.getHeader("If-None-Match")).thenReturn(etag + ", other");

            IdenticonServlet servlet = new IdenticonServlet();
            servlet.init(servletConfig());
            servlet.doGet(request, response);

            // a cache hit serves the image; the inverted branch returned 404 here
            verify(response, never()).setStatus(anyInt());
            verify(response).setContentLength(PNG_BYTES.length);
            assertArrayEquals(PNG_BYTES, written.toByteArray());
        } finally {
            TestCache.ENTRIES.clear();
        }
    }

    @Test
    public void missingCodeParameterIsNotFound() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("c")).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        IdenticonServlet servlet = new IdenticonServlet();
        servlet.init(servletConfig());
        servlet.doGet(request, response);

        verify(response).setStatus(404);
    }

    private ServletConfig servletConfig() {
        ServletConfig cfg = mock(ServletConfig.class);
        when(cfg.getInitParameter("version")).thenReturn("1");
        when(cfg.getInitParameter("cacheProvider")).thenReturn(TestCache.class.getName());
        return cfg;
    }
}
