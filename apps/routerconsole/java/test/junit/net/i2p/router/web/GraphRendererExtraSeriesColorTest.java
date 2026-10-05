package net.i2p.router.web;

import java.awt.Color;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that the two plots on a combined graph are told apart, in every theme.
 *
 * <p>A frame carries at most two series ({@link GraphGroups#MAX_SERIES}), so "which colour
 * is the second line" has exactly one answer and it is themeable. That answer used to be a
 * 60-degree hue rotation of the primary, which meant no theme could state it and the second
 * line was orange rather than the yellow it had been documented as.
 *
 * @since 0.9.71+
 */
public class GraphRendererExtraSeriesColorTest {

    private static final String[] THEMES = { "light", "dark", "midnight" };

    private static float[] hsb(Color c) {
        float[] out = new float[3];
        Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), out);
        return out;
    }

    private static float hue(Color c) { return hsb(c)[0] * 360f; }

    /** The primary is plot 0. */
    private static Color primary(String theme) {
        return GraphRenderer.paletteColorForTest(theme);
    }

    /** The extra series is plot 1. */
    private static Color second(String theme) {
        return GraphRenderer.extraSeriesColor(theme, true, 0);
    }

    /**
     * The core requirement: two lines on one frame must not be the same colour, or the
     * legend describes a graph nobody can read.
     */
    @Test
    public void theTwoSeriesDifferInEveryTheme() {
        for (String theme : THEMES) {
            assertNotEquals(theme + ": the two plots must be told apart",
                            primary(theme), second(theme));
        }
    }

    /**
     * Grouping must not recolour anything. A stat plotted alone and the same stat plotted
     * in a pair are the same measurement, so the same line has to come out the same colour.
     */
    @Test
    public void groupingDoesNotRecolourTheSecondSeries() {
        for (String theme : THEMES) {
            assertEquals(theme + ": filled and line mode must agree on plot 1",
                         second(theme), GraphRenderer.extraSeriesColor(theme, false, 0));
        }
    }

    /**
     * A frame has no third plot, so every extra position resolves to the same second
     * colour. This is what keeps the old palette walk from reappearing.
     */
    @Test
    public void everyExtraPositionResolvesToTheSameSecondColour() {
        for (String theme : THEMES) {
            for (int i = 0; i < 6; i++) {
                assertEquals(theme + " extra " + i,
                             second(theme), GraphRenderer.extraSeriesColor(theme, true, i));
            }
        }
    }

    /** An unrecognised theme falls back to the light palette rather than failing. */
    @Test
    public void anUnknownThemeFallsBackToTheLightPalette() {
        assertEquals(primary("light"), primary("noSuchTheme"));
        assertEquals(second("light"), second("noSuchTheme"));
    }

    /**
     * Distinct hues are what make the two lines separable where they cross. This is the
     * property the old hue-rotation design was for, so it is kept as a requirement even
     * though the colour itself is now themeable.
     */
    @Test
    public void theTwoSeriesAreFarEnoughApartInHue() {
        for (String theme : THEMES) {
            float a = hue(primary(theme));
            float b = hue(second(theme));
            float apart = Math.abs(a - b);
            apart = Math.min(apart, 360f - apart);
            assertTrue(theme + ": hues only " + apart + " degrees apart", apart >= 30f);
        }
    }

    /**
     * The extra series is the theme's own second ink, declared rather than derived.
     *
     * <p>This used to pin a 60-degree rotation of the primary, because that is what the
     * palettes were: the first plot turned into the second. They are declared now - each
     * theme's two lines are the two inks its minigraph plots - so no single hue describes
     * them. What still has to hold is that the renderer asks for plot 1 rather than working
     * one out: that is the wiring this pins, and the declarations themselves are pinned by
     * {@code GraphThemeColorsTest}.
     */
    @Test
    public void theSecondSeriesIsTheThemesSecondPlot() {
        for (String theme : THEMES) {
            assertEquals(theme + ": the extra series is the theme's second plot",
                         GraphThemeColors.lineColor(null, theme, 1), second(theme));
        }
    }

    /**
     * Plot 2's fill is plot 2's line colour, so a frame in filled-path mode carries the same
     * two hues as one in line mode.
     *
     * <p>Scoped to plot 2 on purpose. Plot 1's fill is an independent long-standing colour
     * chosen to sit under its line - blue under the plain theme's navy, for instance - so
     * requiring the pair to match in hue would be imposing a rule the design never had.
     */
    @Test
    public void theSecondPlotsFillMatchesItsLineHue() {
        for (String theme : THEMES) {
            assertEquals(theme + ": plot 2 line and fill must agree on hue",
                         hue(GraphThemeColors.lineColor(null, theme, 1)),
                         hue(GraphThemeColors.pathColor(null, theme, 1)),
                         1f);
        }
    }

    /** Both fills are translucent, so overlapping areas both stay readable. */
    @Test
    public void bothFillsAreTranslucent() {
        for (String theme : THEMES) {
            for (int plot = 0; plot < GraphThemeColors.PLOTS; plot++) {
                int a = GraphThemeColors.pathColor(null, theme, plot).getAlpha();
                assertTrue(theme + " plot " + plot + " fill alpha " + a + " must not be opaque",
                           a > 0 && a < 255);
            }
        }
    }

    /** Both lines are opaque, since a dashed stroke cannot read through a translucency. */
    @Test
    public void bothSeriesAreOpaque() {
        for (String theme : THEMES) {
            assertEquals(theme + " primary alpha", 255, primary(theme).getAlpha());
            assertEquals(theme + " second alpha", 255, second(theme).getAlpha());
        }
    }
}