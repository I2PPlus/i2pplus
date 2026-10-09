package org.cybergarage.upnp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for relative and absolute URL resolution in Device.getAbsoluteURL().
 *
 * Replaces the commented-out main()/test() in Device, which printed the
 * resolved URLs for a handful of path/base combinations but asserted
 * nothing. getAbsoluteURL() is live code - Service uses it to build
 * device description URLs - and previously had no test at all.
 *
 * Expected values follow RFC 3986 relative resolution: an absolute path
 * ("/foo/x") is resolved against the host root and discards the base path,
 * while a relative path ("foo/x") is resolved against the base directory.
 */
public class DeviceAbsoluteURLTest {

    private static final String HOST = "http://aa:123";

    /** A root-relative path resolves against the host root, ignoring the base path. */
    @Test
    public void testAbsolutePathIgnoresBasePath() {
        String[] bases = { HOST + "/", HOST + "/bar/", HOST + "/bar/baz" };
        for (String base : bases) {
            assertEquals(HOST + "/foo/x", new Device().getAbsoluteURL("/foo/x", base, ""));
            assertEquals("location variant", HOST + "/foo/x", new Device().getAbsoluteURL("/foo/x", "", base));
        }
    }

    /** A relative path resolves against the base directory. */
    @Test
    public void testRelativePathResolvesAgainstBase() {
        Device d = new Device();
        assertEquals(HOST + "/foo/x", d.getAbsoluteURL("foo/x", HOST + "/", ""));
        assertEquals(HOST + "/bar/foo/x", d.getAbsoluteURL("foo/x", HOST + "/bar/", ""));
        // no trailing slash: back up to the last slash
        assertEquals(HOST + "/bar/foo/x", d.getAbsoluteURL("foo/x", HOST + "/bar/baz", ""));
    }

    /** The location and base parameters agree for these cases. */
    @Test
    public void testLocationAndBaseAgree() {
        String[] bases = { HOST + "/", HOST + "/bar/", HOST + "/bar/baz" };
        String[] paths = { "/foo/x", "foo/x" };
        for (String base : bases) {
            for (String path : paths) {
                Device d = new Device();
                assertEquals("base vs location for " + path + " against " + base,
                             d.getAbsoluteURL(path, base, ""),
                             d.getAbsoluteURL(path, "", base));
            }
        }
    }

    /** An already absolute URL is returned unchanged. */
    @Test
    public void testAbsoluteUrlUnchanged() {
        assertEquals("http://z:9/q", new Device().getAbsoluteURL("http://z:9/q", "", ""));
        assertEquals("http://z:9/q", new Device().getAbsoluteURL("http://z:9/q", HOST + "/", ""));
    }

    /** An empty path resolves to an empty string. */
    @Test
    public void testEmptyPath() {
        assertEquals("", new Device().getAbsoluteURL("", "", ""));
        assertEquals("", new Device().getAbsoluteURL("", HOST + "/", ""));
    }

    /** A null path resolves to an empty string. */
    @Test
    public void testNullPath() {
        assertEquals("", new Device().getAbsoluteURL(null, "", ""));
    }

    /** With no base and no location an unresolvable path is returned as-is. */
    @Test
    public void testNoBaseReturnsInput() {
        assertEquals("foo/x", new Device().getAbsoluteURL("foo/x", "", ""));
        assertEquals("/foo/x", new Device().getAbsoluteURL("/foo/x", "", ""));
    }

    /**
     * A parent-relative path is concatenated, not normalized: the ".."
     * segment is left in place. This pins the current behaviour rather than
     * RFC 3986 normalization, which HTTP.getAbsoluteURL() does not do.
     */
    @Test
    public void testParentRelativePathIsNotNormalized() {
        assertEquals(HOST + "/bar/baz/../foo/x",
                     new Device().getAbsoluteURL("../foo/x", HOST + "/bar/baz/", ""));
    }
}
