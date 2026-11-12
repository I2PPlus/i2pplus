package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import java.util.Locale;

import org.junit.Test;

/**
 * Tests that the definition parser treats the threshold and rule keywords
 * case-insensitively, tolerates the whitespace an edited file actually
 * contains, and reports a bad rule by the line number the operator has to go
 * look at.
 *
 * @since 0.9.72+
 */
public class DefinitionParserCaseTest {

    /** A destination usable in an explicit rule. */
    private static final String B32 = "gbbkv6ordyceofn542c2aa2o24s77mbis3pzio77yegf4glqsmwq.b32.i2p";

    @Test
    public void testCapitalizedRuleParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"15/5 Default"}));
    }

    @Test
    public void testCapitalizedExplicitRuleParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"15/5 Explicit " + B32}));
    }

    /**
     * The threshold word is case-insensitive as well as the rule word, and
     * "deny" is a threshold, not a rule - a line with two threshold words is
     * still rejected.
     *
     * @throws Exception not expected; both lines here parse
     */
    @Test
    public void testCapitalizedThresholdParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"Deny Default"}));
        assertNotNull(DefinitionParser.parse(new String[] {"ALLOW default"}));
    }

    @Test(expected = InvalidDefinitionException.class)
    public void testTwoThresholdWordsRejected() throws Exception {
        DefinitionParser.parse(new String[] {"15/5 deny default"});
    }

    @Test(expected = InvalidDefinitionException.class)
    public void testUnknownRuleStillRejected() throws Exception {
        DefinitionParser.parse(new String[] {"15/5 Bogus"});
    }

    @Test
    public void testLowercaseRuleStillParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"15/5 default"}));
    }

    /**
     * A doubled space between the fields used to split into an empty field, so
     * the line was rejected even though it reads correctly.
     *
     * @throws Exception not expected; both lines here parse
     */
    @Test
    public void testDoubledWhitespaceParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"15/5    default"}));
        assertNotNull(DefinitionParser.parse(new String[] {"deny     default"}));
    }

    @Test
    public void testTabSeparatedParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"15/5\tfile\t/tmp/x.txt"}));
    }

    @Test
    public void testIndentedLineParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"   15/5 default   "}));
    }

    /**
     * A file path is taken verbatim, spaces included, so collapsing the
     * separators must not touch what follows the rule word.
     *
     * @throws Exception not expected; the line parses
     */
    @Test
    public void testPathWithSpacesKept() throws Exception {
        FilterDefinition def = DefinitionParser.parse(new String[] {"15/5 file /tmp/a b/c.txt"});
        assertEquals(1, def.getElements().length);
    }

    /**
     * The path is found by position, not by searching the line for the rule word,
     * so an uppercase rule word does not make the line unparseable.
     *
     * @throws Exception not expected; the line parses
     */
    @Test
    public void testUppercaseFileRuleKeepsThePath() throws Exception {
        FilterDefinition def = DefinitionParser.parse(new String[] {"15/5 FILE /tmp/a b/c.txt"});
        assertEquals(1, def.getElements().length);
    }

    /**
     * A file or record rule with no path named is rejected rather than reading a
     * blank file name.
     *
     * @throws Exception the expected {@link InvalidDefinitionException} for the
     *                   path-less rule
     */
    @Test(expected = InvalidDefinitionException.class)
    public void testFileRuleWithoutPathRejected() throws Exception {
        DefinitionParser.parse(new String[] {"15/5 file"});
    }

    /**
     * Blank and comment lines are skipped, and the line number reported for a bad
     * rule is the number in the file, not in the filtered list.
     */
    @Test
    public void testBadRuleNamesTheFileLine() {
        String[] definition = {"# a comment", "", "15/5 default", "bogus rule here"};
        try {
            DefinitionParser.parse(definition);
            fail("An unknown threshold must be rejected");
        } catch (InvalidDefinitionException expected) {
            assertTrue("The message must name the line the operator has to fix: " + expected.getMessage(),
                       expected.getMessage().contains("Line 4"));
        }
    }

    /**
     * The keywords are ASCII, so the fold must not follow the JVM default
     * locale: in Turkish "FILE".toLowerCase() yields a dotless i and the rule
     * stops parsing.
     *
     * @throws Exception not expected; every line here parses under the Turkish
     *                   locale
     */
    @Test
    public void testTurkishLocaleStillParses() throws Exception {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertNotNull(DefinitionParser.parse(new String[] {"15/5 FILE /tmp/x.txt"}));
            assertNotNull(DefinitionParser.parse(new String[] {"15/5 RECORD /tmp/x.txt"}));
            assertNotNull(DefinitionParser.parse(new String[] {"15/5 DEFAULT"}));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
