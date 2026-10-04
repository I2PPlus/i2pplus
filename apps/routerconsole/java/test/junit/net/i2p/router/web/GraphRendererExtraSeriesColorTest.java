package net.i2p.router.web;

import java.awt.Color;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests the colour of the extra series in a multi-series graph.
 *
 * <p>Combining two stats has to look like plotting them one at a time: the same two
 * series should not change colour because they were grouped. The first extra therefore
 * takes the colour a two-series graph has always used for its second line, and only a
 * third series onward reaches for the palette.
 *
 * @since 0.9.71+
 */
public class GraphRendererExtraSeriesColorTest {

    /** The colour a two-series graph has always given its second line, per theme. */
    private static Color legacySecond(String theme) {
        return GraphRenderer.extraSeriesColor(theme, false, 0);
    }

    @Test
    public void theFirstExtraMatchesTheLegacySecondSeries() {
        for (String theme : new String[] { "dark", "midnight", "light" }) {
            assertEquals(theme + ": grouping must not recolour the second series",
                         legacySecond(theme),
                         GraphRenderer.extraSeriesColor(theme, true, 0));
        }
    }

    @Test
    public void groupingAndSplittingAgreeOnEveryExtraPosition() {
        for (String theme : new String[] { "dark", "midnight", "light" }) {
            for (int i = 0; i < 6; i++) {
                assertEquals(theme + " extra " + i,
                             legacySecond(theme),
                             GraphRenderer.extraSeriesColor(theme, false, i));
            }
        }
    }

    private static float[] hsb(Color c) {
        float[] out = new float[3];
        Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), out);
        return out;
    }

    private static float hue(Color c) {
        return hsb(c)[0] * 360f;
    }

    private static float saturation(Color c) {
        return hsb(c)[1];
    }

    private static float brightness(Color c) {
        return hsb(c)[2];
    }

    private static float hueDistance(Color a, Color b) {
        float d = Math.abs(hue(a) - hue(b));
        return Math.min(d, 360f - d);
    }

    /** The second series is yellow, far round the wheel from the green primary. */
    @Test
    public void theSecondSeriesIsYellow() {
        float h = hue(GraphRenderer.extraSeriesColor("dark", true, 0));
        assertTrue("expected a yellow hue, got " + h, h > 45f && h < 75f);
    }

    /**
     * Separable where the two lines cross. Below about 60 degrees apart, two lines of
     * similar brightness are genuinely hard to tell apart.
     */
    @Test
    public void theTwoSeriesAreFarEnoughApartInHue() {
        for (String theme : new String[] { "dark", "midnight", "light" }) {
            Color primary = GraphRenderer.paletteColorForTest(theme);
            Color second = GraphRenderer.extraSeriesColor(theme, true, 0);
            float d = hueDistance(primary, second);
            assertTrue(theme + ": series only " + d + " degrees apart", d >= 60f);
        }
    }

    /** Rotating the hue must not change how heavy the line looks. */
    @Test
    public void theSecondSeriesKeepsThePrimaryWeight() {
        for (String theme : new String[] { "dark", "midnight", "light" }) {
            Color primary = GraphRenderer.paletteColorForTest(theme);
            Color second = GraphRenderer.extraSeriesColor(theme, true, 0);
            assertEquals(theme + ": brightness must match", brightness(primary), brightness(second), 0.02f);
            assertEquals(theme + ": saturation must match", saturation(primary), saturation(second), 0.02f);
        }
    }

    @Test
    public void electricRaisesSaturationWithoutMovingHueOrBrightness() {
        Color base = new Color(100, 200, 160);
        Color hot = GraphRenderer.electric(base);
        assertTrue("saturation must rise", saturation(hot) > saturation(base));
        assertEquals("hue is unchanged", hue(base), hue(hot), 1f);
        assertEquals("brightness is unchanged", brightness(base), brightness(hot), 0.001f);
    }

    @Test
    public void rotationKeepsSaturationAndBrightness() {
        Color base = new Color(100, 200, 160);
        Color rotated = GraphRenderer.rotateHue(base, 60f);
        assertEquals(saturation(base), saturation(rotated), 0.001f);
        assertEquals(brightness(base), brightness(rotated), 0.001f);
        assertTrue(hueDistance(base, rotated) >= 60f);
    }

    /** A third series has no legacy colour to match, so it comes from the palette. */
    @Test
    public void aThirdSeriesUsesThePalette() {
        Color second = GraphRenderer.extraSeriesColor("dark", true, 0);
        Color third = GraphRenderer.extraSeriesColor("dark", true, 1);
        assertNotEquals("the third series must differ from the second", second, third);
    }

    /** Series must stay distinguishable across a whole graph. */
    @Test
    public void consecutiveSeriesDiffer() {
        for (String theme : new String[] { "dark", "midnight", "light" }) {
            for (int i = 1; i < 6; i++) {
                assertNotEquals(theme + " series " + (i - 1) + " vs " + i,
                                GraphRenderer.extraSeriesColor(theme, true, i - 1),
                                GraphRenderer.extraSeriesColor(theme, true, i));
            }
        }
    }

    @Test
    public void seriesColoursAreThemeSpecific() {
        assertNotEquals(GraphRenderer.extraSeriesColor("dark", true, 1),
                        GraphRenderer.extraSeriesColor("midnight", true, 1));
    }

    @Test
    public void anUnknownThemeFallsBackToTheLightPalette() {
        assertEquals(GraphRenderer.extraSeriesColor("light", false, 0),
                     GraphRenderer.extraSeriesColor("no-such-theme", false, 0));
    }
}