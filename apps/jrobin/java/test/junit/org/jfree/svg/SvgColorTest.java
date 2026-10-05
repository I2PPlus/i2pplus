package org.jfree.svg;

import java.awt.Color;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for CSS colour parsing used by graph theme variables.
 *
 * <p>Theme authors write shorthand, so the parser has to accept what people actually
 * type. The rule that matters most is that a shorthand digit is <i>doubled</i>:
 * {@code #ee9d} is {@code #eeee99dd}, not {@code #e9d9}.
 *
 * @since 0.9.71+
 */
public class SvgColorTest {

    private static void assertRgba(String css, int r, int g, int b, int a) {
        Color c = SvgColor.parse(css);
        assertNotNull(css + " should parse", c);
        assertEquals(css + " red", r, c.getRed());
        assertEquals(css + " green", g, c.getGreen());
        assertEquals(css + " blue", b, c.getBlue());
        assertEquals(css + " alpha", a, c.getAlpha());
    }

    // ---- the shorthand form called out in the request ----

    @Test
    public void fourDigitShorthandDoublesEachDigit() {
        // #ee9d -> #eeee99dd
        assertRgba("#ee9d", 0xee, 0xee, 0x99, 0xdd);
    }

    @Test
    public void threeDigitShorthandIsOpaque() {
        assertRgba("#0f9", 0x00, 0xff, 0x99, 255);
    }

    @Test
    public void shorthandIsCaseInsensitive() {
        assertRgba("#EE9D", 0xee, 0xee, 0x99, 0xdd);
    }

    // ---- long forms ----

    @Test
    public void sixDigitHexIsOpaque() {
        assertRgba("#00ff99", 0x00, 0xff, 0x99, 255);
    }

    @Test
    public void eightDigitHexCarriesAlpha() {
        assertRgba("#00ff9980", 0x00, 0xff, 0x99, 0x80);
    }

    @Test
    public void eightDigitShorthandLikeFormIsStillLongForm() {
        assertRgba("#0f98", 0x00, 0xff, 0x99, 0x88);
    }

    // ---- functional forms ----

    @Test
    public void rgbAndRgba() {
        assertRgba("rgb(0,255,153)", 0, 255, 153, 255);
        assertRgba("rgba(0,255,153,0.5)", 0, 255, 153, 128);
        assertRgba("RGBA(0,255,153,1)", 0, 255, 153, 255);
    }

    @Test
    public void percentChannels() {
        assertRgba("rgb(0%,100%,60%)", 0, 255, 153, 255);
    }

    @Test
    public void percentAlpha() {
        assertRgba("rgba(0,255,153,50%)", 0, 255, 153, 128);
    }

    @Test
    public void whitespaceIsTolerated() {
        assertRgba("  #00ff99  ", 0, 255, 153, 255);
        assertRgba("rgb( 0 , 255 , 153 )", 0, 255, 153, 255);
    }

    // ---- bad input yields null, never a wrong colour ----

    @Test
    public void unparseableInputIsNull() {
        assertNull(SvgColor.parse(null));
        assertNull(SvgColor.parse(""));
        assertNull(SvgColor.parse("   "));
        assertNull(SvgColor.parse("nonsense"));
        assertNull(SvgColor.parse("#"));
        assertNull(SvgColor.parse("#12"));          // 2 digits: neither shorthand nor long
        assertNull(SvgColor.parse("#12345"));
        assertNull(SvgColor.parse("#1234567"));
        assertNull(SvgColor.parse("#gggggg"));
        assertNull(SvgColor.parse("rgb(1,2)"));     // too few channels
        assertNull(SvgColor.parse("rgb(1,2,3,4)")); // too many for rgb()
        assertNull(SvgColor.parse("rgba(1,2,3)"));  // too few for rgba()
    }

    /** A theme typo must fall back for that one value, not throw during rendering. */
    @Test
    public void malformedNumbersDoNotThrow() {
        assertNull(SvgColor.parse("rgb(a,b,c)"));
        assertNull(SvgColor.parse("rgba(0,0,0,x)"));
        assertNull(SvgColor.parse("rgb(0,0,)"));
    }

    @Test
    public void alphaIsClampedRatherThanRejected() {
        assertRgba("rgba(0,0,0,2)", 0, 0, 0, 255);
        assertRgba("rgba(0,0,0,-1)", 0, 0, 0, 0);
    }

    // ---- format ----

    @Test
    public void formatShortensWhenExact() {
        assertEquals("#0f9", SvgColor.format(new Color(0x00, 0xff, 0x99)));
        // Alpha is never shortened: #0f98 would read as a different colour.
        assertEquals("#ee99dddd", SvgColor.format(new Color(0xee, 0x99, 0xdd, 0xdd)));
    }

    @Test
    public void formatKeepsLongFormWhenShorteningWouldLose() {
        // 0x12 does not repeat, so #123456 cannot become a 3-digit form.
        assertEquals("#123456", SvgColor.format(new Color(0x12, 0x34, 0x56, 255)));
    }

    @Test
    public void formatIncludesAlphaWhenNotOpaque() {
        String s = SvgColor.format(new Color(0x00, 0xff, 0x99, 0x80));
        assertEquals("#00ff9980", s);
    }

    /** Round-tripping is what lets a theme value be read and written unchanged. */
    @Test
    public void formatThenParseIsStable() {
        Color c = new Color(0x12, 0x34, 0x56, 0x78);
        assertEquals(c, SvgColor.parse(SvgColor.format(c)));
        Color opaque = new Color(0x12, 0x34, 0x56);
        assertEquals(opaque, SvgColor.parse(SvgColor.format(opaque)));
    }

    @Test
    public void formatOfNullIsNull() {
        assertNull(SvgColor.format(null));
    }
}