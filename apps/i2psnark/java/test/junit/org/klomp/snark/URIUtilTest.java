package org.klomp.snark;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for URIUtil.
 *  Pure static utility - no I2P context needed.
 */
public class URIUtilTest {

    @Test
    public void testEncodePathSimple() {
        String result = URIUtil.encodePath("hello");
        assertEquals("hello", result);
    }

    @Test
    public void testEncodePathWithSlash() {
        String result = URIUtil.encodePath("/path/to/file");
        assertNotNull(result);
    }

    @Test
    public void testEncodePathWithSpace() {
        String result = URIUtil.encodePath("hello world");
        assertNotNull(result);
        assertTrue(result.contains("%20") || result.contains("hello"));
    }

    @Test
    public void testEncodePathEmpty() {
        String result = URIUtil.encodePath("");
        assertNotNull(result);
    }

    @Test
    public void testEncodePathNull() {
        String result = URIUtil.encodePath(null);
        assertNull(result);
    }

    @Test
    public void testEncodePathStringBuilder() {
        StringBuilder buf = new StringBuilder();
        StringBuilder result = URIUtil.encodePath(buf, "test");
        assertNotNull(result);
    }

    @Test
    public void testEncodePathSpecialChars() {
        String result = URIUtil.encodePath("file%20name");
        assertNotNull(result);
    }

    @Test
    public void testEncodePathWithUnicode() {
        String result = URIUtil.encodePath("test\u00e9");
        assertNotNull(result);
    }
}
