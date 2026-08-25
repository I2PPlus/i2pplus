package org.klomp.snark.web;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the doGetAndPost path classifiers extracted from the request
 * router: AJAX endpoints, index-page detection, and unmanaged-path gating.
 *
 * @since 0.9.71+
 */
public class DoGetAndPostRoutingTest {

    // ---- isAjaxPath ----

    @Test
    public void testAjaxEndpointsMatch() {
        assertTrue(I2PSnarkServlet.isAjaxPath("/.ajax/xhr1.html"));
        assertTrue(I2PSnarkServlet.isAjaxPath("/.ajax/xhrscreenlog.html"));
    }

    @Test
    public void testAjaxNonEndpointsPassThrough() {
        // unknown /.ajax/* must NOT be claimed - container fallback applies
        assertFalse(I2PSnarkServlet.isAjaxPath("/.ajax/other.html"));
        assertFalse(I2PSnarkServlet.isAjaxPath("/.ajax/xhr1.html/x"));
        assertFalse(I2PSnarkServlet.isAjaxPath(null));
        assertFalse(I2PSnarkServlet.isAjaxPath("/"));
        assertFalse(I2PSnarkServlet.isAjaxPath(""));
    }

    // ---- isIndexPath ----

    @Test
    public void testIndexPathVariants() {
        assertTrue(I2PSnarkServlet.isIndexPath(""));
        assertTrue(I2PSnarkServlet.isIndexPath("/"));
        assertTrue(I2PSnarkServlet.isIndexPath("index.jsp"));
    }

    @Test
    public void testNonIndexPaths() {
        assertFalse(I2PSnarkServlet.isIndexPath(null));
        assertFalse(I2PSnarkServlet.isIndexPath("index.jsp/"));
        assertFalse(I2PSnarkServlet.isIndexPath("//"));
        assertFalse(I2PSnarkServlet.isIndexPath("/index.html"));
        assertFalse(I2PSnarkServlet.isIndexPath("/configure"));
    }

    // ---- isUnmanagedPath ----

    @Test
    public void testManagedPagesAreNotUnmanaged() {
        assertFalse(I2PSnarkServlet.isUnmanagedPath("", true, false));
        assertFalse(I2PSnarkServlet.isUnmanagedPath("/", true, false));
        assertFalse(I2PSnarkServlet.isUnmanagedPath("/index.html", false, false));
        assertFalse(I2PSnarkServlet.isUnmanagedPath("/_post", false, false));
        assertFalse(I2PSnarkServlet.isUnmanagedPath("/configure", false, true));
        assertFalse(I2PSnarkServlet.isUnmanagedPath("/foo/configure", false, true));
    }

    @Test
    public void testEverythingElseIsUnmanaged() {
        assertTrue(I2PSnarkServlet.isUnmanagedPath("/foo/", false, false));
        assertTrue(I2PSnarkServlet.isUnmanagedPath("/foo", false, false));
        assertTrue(I2PSnarkServlet.isUnmanagedPath("/.ajax/xhr1.html", false, false));
        // index flag wins over path text
        assertTrue(I2PSnarkServlet.isUnmanagedPath("/weird", false, false));
    }
}
