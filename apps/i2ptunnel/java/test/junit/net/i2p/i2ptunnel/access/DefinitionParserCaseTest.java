package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests that the access-list definition parser treats the rule word
 * case-insensitively, like the threshold before it — "15/5 Deny default"
 * used to fail while "15/5 deny default" parsed.
 *
 * @since 0.9.72+
 */
public class DefinitionParserCaseTest {

    @Test
    public void testCapitalizedRuleParses() throws Exception {
        FilterDefinition def = DefinitionParser.parse(new String[] {"15/5 Default"});
        assertNotNull(def);
    }

    @Test
    public void testCapitalizedExplicitRuleParses() throws Exception {
        FilterDefinition def = DefinitionParser.parse(new String[] {"15/5 Explicit " +
            "gbbkv6ordyceofn542c2aa2o24s77mbis3pzio77yegf4glqsmwq.b32.i2p"});
        assertNotNull(def);
    }

    @Test(expected = InvalidDefinitionException.class)
    public void testUnknownRuleStillRejected() throws Exception {
        DefinitionParser.parse(new String[] {"15/5 Deny default"});
    }

    @Test
    public void testLowercaseRuleStillParses() throws Exception {
        assertNotNull(DefinitionParser.parse(new String[] {"15/5 default"}));
    }
}
