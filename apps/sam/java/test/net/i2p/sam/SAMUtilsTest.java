package net.i2p.sam;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests SAMUtils standalone parsing logic.
 *
 * @since 0.9.70+
 */
public class SAMUtilsTest {

    @Test
    public void testParseParamsEmpty() throws SAMException {
        java.util.Properties p = SAMUtils.parseParams("");
        assertTrue(p.isEmpty());
    }

    @Test
    public void testParseParamsSingle() throws SAMException {
        java.util.Properties p = SAMUtils.parseParams("key=val");
        assertEquals("val", p.getProperty("key"));
    }

    @Test
    public void testParseParamsMultiple() throws SAMException {
        java.util.Properties p = SAMUtils.parseParams("a=1 b=2 c=3");
        assertEquals("1", p.getProperty("a"));
        assertEquals("2", p.getProperty("b"));
        assertEquals("3", p.getProperty("c"));
    }

    @Test
    public void testParseParamsQuoted() throws SAMException {
        java.util.Properties p = SAMUtils.parseParams("key=\"val ue\"");
        assertEquals("val ue", p.getProperty("key"));
    }

    @Test(expected = SAMException.class)
    public void testParseParamsUnclosedQuote() throws SAMException {
        SAMUtils.parseParams("key=\"unclosed");
    }

    @Test
    public void testParseParamsMixed() throws SAMException {
        java.util.Properties p = SAMUtils.parseParams("a=1 b=\"two words\" c=3");
        assertEquals("1", p.getProperty("a"));
        assertEquals("two words", p.getProperty("b"));
        assertEquals("3", p.getProperty("c"));
    }
}
