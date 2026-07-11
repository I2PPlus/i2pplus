package net.i2p.addressbook;

import static org.junit.Assert.*;

import org.junit.Test;

import net.i2p.client.naming.HostTxtEntry;

/**
 * Tests for HostTxtParser host.txt line parsing.
 *
 * @since 0.9.70+
 */
public class HostTxtParserTest {

    @Test
    public void testSimpleKeyValue() {
        HostTxtEntry e = HostTxtParser.parse("example.i2p=base64dest", false);
        assertNotNull(e);
        assertEquals("example.i2p", e.getName());
        assertEquals("base64dest", e.getDest());
    }

    @Test
    public void testWithProperties() {
        HostTxtEntry e = HostTxtParser.parse("example.i2p=base64dest#!key1=val1#key2=val2", false);
        assertNotNull(e);
        assertEquals("example.i2p", e.getName());
        assertEquals("base64dest", e.getDest());
        assertNotNull(e.getProps());
        assertEquals("val1", e.getProps().getProperty("key1"));
        assertEquals("val2", e.getProps().getProperty("key2"));
    }

    @Test
    public void testCommentLineIgnored() {
        HostTxtEntry e = HostTxtParser.parse("# this is a comment", false);
        assertNull(e);
    }

    @Test
    public void testSemicolonCommentIgnored() {
        HostTxtEntry e = HostTxtParser.parse("; this is a comment", false);
        assertNull(e);
    }

    @Test
    public void testCommandOnlyLineIgnoredWithoutFlag() {
        HostTxtEntry e = HostTxtParser.parse("#!action=remove", false);
        assertNull(e);
    }

    @Test
    public void testCommandOnlyLineParsedWithFlag() {
        HostTxtEntry e = HostTxtParser.parse("#!action=remove", true);
        assertNotNull(e);
        assertNull(e.getName());
        assertNull(e.getDest());
        assertNotNull(e.getProps());
        assertEquals("remove", e.getProps().getProperty("action"));
    }

    @Test
    public void testEmptyNameIgnored() {
        HostTxtEntry e = HostTxtParser.parse("=base64dest", false);
        assertNull(e);
    }

    @Test
    public void testEmptyDestIgnored() {
        HostTxtEntry e = HostTxtParser.parse("example.i2p=", false);
        assertNull(e);
    }

    @Test
    public void testTrailingCommentIgnored() {
        HostTxtEntry e = HostTxtParser.parse("example.i2p=base64dest # trailing comment", false);
        assertNotNull(e);
        assertEquals("example.i2p", e.getName());
        assertEquals("base64dest", e.getDest());
        assertNull(e.getProps());
    }

    @Test
    public void testWhitespaceTrimmed() {
        HostTxtEntry e = HostTxtParser.parse("  example.i2p = base64dest  ", false);
        assertNotNull(e);
        assertEquals("example.i2p", e.getName());
        assertEquals("base64dest", e.getDest());
    }

    @Test
    public void testNameLowercased() {
        HostTxtEntry e = HostTxtParser.parse("EXAMPLE.i2p=base64dest", false);
        assertNotNull(e);
        assertEquals("example.i2p", e.getName());
    }

    @Test
    public void testNoEqualsSignIgnored() {
        HostTxtEntry e = HostTxtParser.parse("just a line with no equals", false);
        assertNull(e);
    }

    @Test
    public void testInvalidPropsReturnsNull() {
        // Invalid base64 in dest would still parse at this level
        HostTxtEntry e = HostTxtParser.parse("x=y#!invalid", false);
        assertNull(e);
    }
}
