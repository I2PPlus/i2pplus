package net.i2p.router.web;

import static org.junit.Assert.*;

import org.junit.Test;

public class CSSHelperTest {

    @Test
    public void testCapitalizeWord_NormalSentence() {
        assertEquals("Configure I2p", CSSHelper.StringFormatter.capitalizeWord("configure i2p"));
    }

    @Test
    public void testCapitalizeWord_SingleWord() {
        assertEquals("Router", CSSHelper.StringFormatter.capitalizeWord("router"));
    }

    @Test
    public void testCapitalizeWord_AlreadyCapitalized() {
        assertEquals("Router Console", CSSHelper.StringFormatter.capitalizeWord("Router Console"));
    }

    @Test
    public void testCapitalizeWord_MixedCase() {
        assertEquals("I2p Tunnel", CSSHelper.StringFormatter.capitalizeWord("i2p tunnel"));
    }

    @Test
    public void testCapitalizeWord_MultipleSpaces() {
        assertEquals("Config Advanced", CSSHelper.StringFormatter.capitalizeWord("config   advanced"));
    }

    @Test
    public void testCapitalizeWord_LeadingAndTrailingSpaces() {
        assertEquals("Home Page", CSSHelper.StringFormatter.capitalizeWord("  home page  "));
    }

    @Test
    public void testCapitalizeWord_EmptyString() {
        assertEquals("", CSSHelper.StringFormatter.capitalizeWord(""));
    }

    @Test
    public void testCapitalizeWord_NumbersAndSpecialChars() {
        assertEquals("I2cp Stats 2024", CSSHelper.StringFormatter.capitalizeWord("i2cp stats 2024"));
    }

    @Test
    public void testCapitalizeWord_WhitespaceOnly() {
        assertEquals("", CSSHelper.StringFormatter.capitalizeWord("   "));
    }

    @Test
    public void testCapitalizeWord_SingleCharacterWords() {
        assertEquals("A B C", CSSHelper.StringFormatter.capitalizeWord("a b c"));
    }

    @Test
    public void testCapitalizeWord_WithHyphens() {
        assertEquals("String-formatter Test", CSSHelper.StringFormatter.capitalizeWord("string-formatter test"));
    }
}
