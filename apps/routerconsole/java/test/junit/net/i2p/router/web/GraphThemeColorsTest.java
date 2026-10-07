package net.i2p.router.web;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Tests for theme-overridable graph plot colours.
 *
 * <p>The contract that matters is that a theme is <i>optional</i>: with no stylesheet, or
 * with a broken declaration in it, every colour still comes back non-null and the frame
 * renders. A theme that cannot be read must never be able to blank out a graph.
 *
 * @since 0.9.71+
 */
public class GraphThemeColorsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File themeDir;

    @Before
    public void setUp() {
        GraphThemeColors.clearCache();
        GraphThemeColors.maxCacheAgeMs = 30_000L;
        themeDir = folder.getRoot();
    }

    @After
    public void tearDown() {
        GraphThemeColors.clearCache();
        GraphThemeColors.maxCacheAgeMs = 30_000L;
    }

    /** Write {@code <theme>/console.css} with the given body. */
    private File writeTheme(String theme, String body) throws IOException {
        File dir = new File(themeDir, theme);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        File css = new File(dir, "console.css");
        Files.write(css.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return css;
    }

    // ---- condensed plot width ----

    /**
     * The condensed slot has its own built-in default, independent of the ordinary plot weight.
     *
     * <p>Not pinned to the ordinary weight on purpose: the point of the variable is that a
     * condensed plot is drawn thinner than a roomy one, so a fallback that simply copied the
     * ordinary weight would make the variable a no-op for any theme that omits it.
     */
    @Test
    public void theCondensedWidthHasItsOwnDefault() {
        assertEquals(2f, GraphThemeColors.plotLineWidthCondensed(themeDir, "dark"), 0.0001f);
    }

    /**
     * A width inside the bounds is used exactly as stated, at the bounds included.
     *
     * <p>The counterpart to {@link #anUnusableLineWidthFallsBack}: that one proves the guard
     * rejects, this one proves it does not reject anything it should keep. The bounds are
     * inclusive on both ends - {@code 0.1} and {@code 16} are declared widths, {@code 0.09} and
     * {@code 16.1} are not.
     */
    @Test
    public void aWidthInsideTheBoundsIsUsedAsStated() throws IOException {
        for (String value : new String[] { "0.1", "1.5", "3", "16" }) {
            writeTheme("dark", ":root{--graph_plotLineWidth:" + value + ";}");
            // The cache keys on the file's last-modified stamp, so successive writes inside one
            // mtime tick are invisible to it and a stale value comes back. Cleared per
            // iteration rather than only in setUp, or the loop silently asserts the first value
            // four times - which is exactly what it did before this was noticed.
            GraphThemeColors.clearCache();
            assertEquals("a declared width of " + value + " was not honoured",
                         Float.parseFloat(value),
                         GraphThemeColors.plotLineWidth(themeDir, "dark"), 0f);
        }
    }

    /** One step outside a bound is no opinion, and takes the built-in default. */
    @Test
    public void oneStepOutsideTheBoundsIsNotAWidth() throws IOException {
        float fallback = GraphThemeColors.plotLineWidth(null, "noSuchTheme");
        for (String value : new String[] { "0.09", "16.1" }) {
            writeTheme("dark", ":root{--graph_plotLineWidth:" + value + ";}");
            GraphThemeColors.clearCache();
            assertEquals("an out-of-range width of " + value + " was used as stated",
                         fallback, GraphThemeColors.plotLineWidth(themeDir, "dark"), 0f);
        }
    }

    /** The filled family is three independent slots, none borrowing from the plot family. */
    @Test
    public void theFilledFamilyIsThreeIndependentSlots() throws IOException {
        writeTheme("dark", ":root{--graph_plotLineWidth:3;--graph_plotLineWidthWide:4;"
                            + "--graph_plotLineWidthCondensed:2;"
                            + "--graph_plotLineWidthFilled:1.5;"
                            + "--graph_plotLineWidthFilledWide:2.5;"
                            + "--graph_plotLineWidthFilledCondensed:1;}");
        assertEquals(3f, GraphThemeColors.plotLineWidth(themeDir, "dark"), 0.0001f);
        assertEquals(4f, GraphThemeColors.plotLineWidthWide(themeDir, "dark"), 0.0001f);
        assertEquals(2f, GraphThemeColors.plotLineWidthCondensed(themeDir, "dark"), 0.0001f);
        assertEquals(1.5f, GraphThemeColors.plotLineWidthFilled(themeDir, "dark"), 0.0001f);
        assertEquals(2.5f, GraphThemeColors.plotLineWidthFilledWide(themeDir, "dark"), 0.0001f);
        assertEquals(1f, GraphThemeColors.plotLineWidthFilledCondensed(themeDir, "dark"), 0.0001f);
    }

    /** The filled plot's edge is its own slot, set apart from every plot-line width. */
    @Test
    public void aThemeMaySetTheFilledEdgeApartFromThePlotWidths() throws IOException {
        writeTheme("dark", ":root{--graph_plotLineWidth:3;--graph_plotLineWidthWide:4;"
                            + "--graph_plotLineWidthCondensed:2;"
                            + "--graph_plotLineWidthFilled:1;}");
        assertEquals(3f, GraphThemeColors.plotLineWidth(themeDir, "dark"), 0.0001f);
        assertEquals(4f, GraphThemeColors.plotLineWidthWide(themeDir, "dark"), 0.0001f);
        assertEquals(2f, GraphThemeColors.plotLineWidthCondensed(themeDir, "dark"), 0.0001f);
        assertEquals(1f, GraphThemeColors.plotLineWidthFilled(themeDir, "dark"), 0.0001f);
    }

    /** A theme may weight the condensed case independently of the ordinary plot. */
    @Test
    public void aThemeMaySetTheCondensedWeightApartFromTheOrdinaryOne() throws IOException {
        writeTheme("dark", ":root{--graph_plotLineWidth:3;--graph_plotLineWidthWide:4;"
                            + "--graph_plotLineWidthCondensed:2;}");
        assertEquals(3f, GraphThemeColors.plotLineWidth(themeDir, "dark"), 0.0001f);
        assertEquals(4f, GraphThemeColors.plotLineWidthWide(themeDir, "dark"), 0.0001f);
        assertEquals(2f, GraphThemeColors.plotLineWidthCondensed(themeDir, "dark"), 0.0001f);
    }

    /** The condensed width is its own slot: overriding it must not disturb the others. */
    @Test
    public void theCondensedOverrideLeavesTheOtherWidthsAlone() throws IOException {
        writeTheme("light", ":root{--graph_plotLineWidth:3;--graph_plotLineWidthWide:4;"
                             + "--graph_plotLineWidthCondensed:1;}");
        assertEquals(3f, GraphThemeColors.plotLineWidth(themeDir, "light"), 0.0001f);
        assertEquals(4f, GraphThemeColors.plotLineWidthWide(themeDir, "light"), 0.0001f);
        assertEquals(1f, GraphThemeColors.plotLineWidthCondensed(themeDir, "light"), 0.0001f);
    }

    // ---- defaults ----

    @Test
    public void withNoStylesheetEveryColourIsTheBuiltInDefault() {
        assertNotNull(GraphThemeColors.lineColor(themeDir, "dark", 0));
        assertNotNull(GraphThemeColors.pathColor(themeDir, "dark", 0));
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
    }

    @Test
    public void anAbsentThemeDirectoryFallsBackRatherThanFailing() {
        assertFalse(new File(themeDir, "noSuchTheme").exists());
        assertNotNull(GraphThemeColors.lineColor(themeDir, "noSuchTheme", 0));
        assertNotNull(GraphThemeColors.pathColor(themeDir, "noSuchTheme", 1));
        // A name with no stylesheet behind it has no palette to honour, so it lands on the one
        // built-in: a solid series line, as every shipped theme asks for of its own accord.
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "noSuchTheme"), 0.001f);
    }

    @Test
    public void aNullThemeDirectoryFallsBackRatherThanFailing() {
        assertNotNull(GraphThemeColors.lineColor(null, "dark", 0));
        assertNotNull(GraphThemeColors.pathColor(null, "dark", 1));
        assertEquals(0f, GraphThemeColors.dashLength(null, "dark"), 0.001f);
    }

    @Test
    public void aNullThemeNameFallsBack() {
        assertNotNull(GraphThemeColors.lineColor(themeDir, null, 0));
    }

    /**
     * With no stylesheet behind it a theme name is not a palette, so every name lands on the
     * one built-in set.
     *
     * <p>There is deliberately nothing per-theme here. The built-ins exist for a stylesheet
     * that is absent or empty - an unknown name, or a layout with no theme at all - and
     * neither case has a look to honour. A theme that does have one states it in its own
     * {@code console.css}, which {@link #everyShippedThemeDeclaresEveryGraphVariable} holds
     * it to.
     */
    @Test
    public void everyThemeFallsBackToTheSameBuiltInLine() {
        Color fallback = GraphThemeColors.lineColor(themeDir, "light", 0);
        assertNotNull(fallback);
        for (String theme : new String[] { "dark", "midnight", "classic", "noSuchTheme" }) {
            assertEquals(theme + " must land on the one built-in line colour",
                         fallback, GraphThemeColors.lineColor(themeDir, theme, 0));
        }
    }

    /** The two plots on a frame must be tellable apart, or the legend lies. */
    @Test
    public void theTwoPlotsDifferByDefault() {
        for (String theme : new String[] { "light", "dark", "midnight" }) {
            assertNotEquals(theme + " plots must differ",
                GraphThemeColors.lineColor(themeDir, theme, 0),
                GraphThemeColors.lineColor(themeDir, theme, 1));
            assertNotEquals(theme + " paths must differ",
                GraphThemeColors.pathColor(themeDir, theme, 0),
                GraphThemeColors.pathColor(themeDir, theme, 1));
        }
    }

    // ---- overrides ----

    @Test
    public void aThemeMayOverrideBothPlotsAndTheDash() throws IOException {
        writeTheme("dark",
            ":root{\n"
          + "--graph_plotLine1:#0f9;\n"
          + "--graph_plotLine2:rgba(255,0,0,.5);\n"
          + "--graph_plotFill1:#00ff9980;\n"
          + "--graph_plotFill2:#ee9d;\n"
          + "--graph_plotDash:2.5;\n"
          + "}\n");
        assertEquals(new Color(0x00, 0xff, 0x99),
            GraphThemeColors.lineColor(themeDir, "dark", 0));
        assertEquals(new Color(255, 0, 0, 128),
            GraphThemeColors.lineColor(themeDir, "dark", 1));
        assertEquals(new Color(0x00, 0xff, 0x99, 0x80),
            GraphThemeColors.pathColor(themeDir, "dark", 0));
        // #ee9d is the four-digit shorthand: R ee, G ee, B 99, A dd.
        assertEquals(new Color(0xee, 0xee, 0x99, 0xdd),
            GraphThemeColors.pathColor(themeDir, "dark", 1));
        assertEquals(2.5f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
    }

    @Test
    public void anOverrideAppliesOnlyToItsOwnTheme() throws IOException {
        writeTheme("dark", ":root{--graph_plotLine1:#0f9;}");
        assertEquals(new Color(0x00, 0xff, 0x99),
            GraphThemeColors.lineColor(themeDir, "dark", 0));
        assertNotEquals(GraphThemeColors.lineColor(themeDir, "dark", 0),
            GraphThemeColors.lineColor(themeDir, "light", 0));
    }

    @Test
    public void aLaterDeclarationInTheSameFileWins() throws IOException {
        writeTheme("dark", ":root{--graph_plotLine1:#0f9;} :root{--graph_plotLine1:#f00;}");
        assertEquals(new Color(255, 0, 0),
            GraphThemeColors.lineColor(themeDir, "dark", 0));
    }

    /** A typo must cost one colour, not the frame. */
    @Test
    public void aMalformedValueFallsBackAndLeavesTheOtherSlotAlone() throws IOException {
        writeTheme("dark",
            ":root{--graph_plotLine1:chartreuse;--graph_plotLine2:#00f;--graph_plotFill1:#zzz;}");
        // The built-in value has to come from a directory with no stylesheet in it, since
        // each theme's defaults differ and comparing across themes would prove nothing.
        File bare = folder.newFolder("bare");
        assertEquals(GraphThemeColors.lineColor(bare, "dark", 0),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
        assertEquals(GraphThemeColors.pathColor(bare, "dark", 0),
                     GraphThemeColors.pathColor(themeDir, "dark", 0));
        // The well-formed neighbour in the same file still applies.
        assertEquals(new Color(0, 0, 255),
                     GraphThemeColors.lineColor(themeDir, "dark", 1));
    }

    /**
     * An unusable value falls back to the built-in, which asks for a solid series line.
     *
     * <p>One value serves every theme, since the fallback is for a stylesheet that says
     * nothing and a name alone is not a palette.
     */
    @Test
    public void aMalformedDashFallsBackToTheBuiltInDot() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:dotted;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
    }

    /** An absurd dash length would break the dot pattern, so it is rejected. */
    @Test
    public void anOutOfRangeDashFallsBack() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:5000;--graph_plotFill1:#0f9;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        writeTheme("midnight", ":root{--graph_plotDash:-2;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
    }

    /** Zero dot ink is the override for "no pattern": the reader reports it as asked. */
    @Test
    public void aZeroDashLengthAsksForASolidLine() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:0;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_plotDash:0 3;}");
        assertEquals("a gap beside a zero dot changes nothing",
                     0f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);

        writeTheme("light", ":root{--graph_plotDash:0,3;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "light"), 0.001f);
    }

    /** A zero gap after a real dot derives the gap; it is not a solid-line request. */
    @Test
    public void aZeroGapAfterADotIsNotASolidRequest() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:1 0;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);
    }

    // ---- the dash pair ----

    /**
     * A bare length keeps the gap derived, which is what the single-value form has always
     * meant: a theme that does not care about spacing should not be able to break the dots.
     */
    @Test
    public void aBareDashLengthLeavesTheGapDerived() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:2;}");
        assertEquals(2f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals("an unstated gap must stay unstated",
                     0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);
    }

    /** "1 3" and "1,3" both mean a 1px dot then 3px of space. */
    @Test
    public void aDashPairStatesTheGap() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:1 3;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(3f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_plotDash:2,6;}");
        assertEquals(2f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
        assertEquals(6f, GraphThemeColors.dashGap(themeDir, "midnight"), 0.001f);

        writeTheme("light", ":root{--graph_plotDash:  1.5 , 4 ;}");
        assertEquals(1.5f, GraphThemeColors.dashLength(themeDir, "light"), 0.001f);
        assertEquals(4f, GraphThemeColors.dashGap(themeDir, "light"), 0.001f);
    }

    /**
     * With no declaration the built-in dot stands, and a zero dot leaves no gap to derive -
     * there is no dot to separate.
     *
     * <p>The same for every theme: the fallback is one value, not one per name, since it
     * exists for a stylesheet that says nothing.
     */
    @Test
    public void noDashDeclarationMeansTheBuiltInDotAndADerivedGap() {
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            assertEquals(theme + " dot", 0f, GraphThemeColors.dashLength(themeDir, theme), 0.001f);
            assertEquals(theme + " gap", 0f, GraphThemeColors.dashGap(themeDir, theme), 0.001f);
        }
    }

    /**
     * A gap the renderer would have to raise anyway is reported as stated, because this
     * class only reads the stylesheet. The clamping lives in RrdGraphConstants.
     */
    @Test
    public void aTightGapIsPassedThroughForTheRendererToRaise() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:1 0.5;}");
        assertEquals(0.5f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);
    }

    @Test
    public void anUnusableDashFallsBackToTheDefault() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:dotted;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_plotDash:1 2 3;}");
        assertEquals("a three-value list is refused, not truncated",
                     0f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "midnight"), 0.001f);
    }

    @Test
    public void anOutOfRangeDashValueFallsBack() throws IOException {
        writeTheme("dark", ":root{--graph_plotDash:100 3;}");
        assertEquals("an absurd dot length is rejected, pair and all",
                     0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_plotDash:1 -3;}");
        assertEquals("a negative gap is dropped, leaving the pair's dot",
                     1f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "midnight"), 0.001f);

        writeTheme("light", ":root{--graph_plotDash:1 9999;}");
        assertEquals("an absurd gap is dropped",
                     0f, GraphThemeColors.dashGap(themeDir, "light"), 0.001f);
    }

    // ---- bounds ----

    /** A frame holds two plots; anything beyond clamps rather than throwing. */
    @Test
    public void plotOrdinalsOutsideTheFrameClampInsteadOfThrowing() {
        assertEquals(GraphThemeColors.lineColor(themeDir, "dark", 0),
                     GraphThemeColors.lineColor(themeDir, "dark", -1));
        assertEquals(GraphThemeColors.lineColor(themeDir, "dark", 1),
                     GraphThemeColors.lineColor(themeDir, "dark", 7));
    }

    // ---- caching ----

    /**
     * A theme edit has to show up without a router restart, the way a minigraph change
     * shows up on refresh. The minigraph works because the browser re-reads the stylesheet;
     * these are resolved server-side, so the cache has to notice the file changed.
     *
     * <p>lastModified is set explicitly rather than relying on write order, because a
     * filesystem with coarse timestamps would hide two writes in the same second and the
     * test would then pass or fail for reasons that have nothing to do with the code.
     */
    @Test
    public void editingAThemeTakesEffectWithoutARestart() throws IOException {
        writeTheme("dark", ":root{--graph_plotLine1:#0f9;}");
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));

        writeTheme("dark", ":root{--graph_plotLine1:#f00;}");
        touch(new File(new File(themeDir, "dark"), "console.css"), 1_600_000_000_000L);
        assertEquals("the edit was not picked up",
                     new Color(255, 0, 0),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
    }

    /** An unchanged file must keep serving the cached parse. */
    @Test
    public void anUnchangedStylesheetIsNotReparsed() throws IOException {
        File css = writeTheme("dark", ":root{--graph_plotLine1:#0f9;}");
        long stamp = 1_600_000_000_000L;
        assertTrue("could not set the timestamp", css.setLastModified(stamp));
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
        // Rewriting the identical bytes and restoring the same timestamp is, by definition,
        // no change; a repeated lookup must not disturb the value.
        writeTheme("dark", ":root{--graph_plotLine1:#0f9;}");
        assertTrue(css.setLastModified(stamp));
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
    }

    /**
     * A theme deployed after startup must not be stuck on the "no stylesheet" result that
     * was cached the first time it was asked about.
     */
    @Test
    public void aThemeThatAppearsLaterIsNotRememberedAsAbsent() throws IOException {
        assertNotNull(GraphThemeColors.lineColor(themeDir, "midnight", 0));
        assertEquals("absent means the built-in default",
                     GraphThemeColors.lineColor(new File(themeDir, "elsewhere"), "midnight", 0),
                     GraphThemeColors.lineColor(themeDir, "midnight", 0));
        writeTheme("midnight", ":root{--graph_plotLine1:#0f9;}");
        assertEquals("a stylesheet that appeared after startup was ignored",
                     new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "midnight", 0));
    }

    /**
     * The periodic re-read, for a filesystem that does not report a changed timestamp.
     *
     * <p>The timestamp check alone would miss this: the file's last-modified is pinned, so
     * the entry looks current forever. The age check is what re-reads it anyway.
     */
    @Test
    public void theStylesheetIsRereadPeriodicallyEvenIfTheTimestampLooksUnchanged()
            throws IOException {
        File css = writeTheme("dark", ":root{--graph_plotLine1:#0f9;}");
        long stamp = 1_600_000_000_000L;
        assertTrue(css.setLastModified(stamp));
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));

        // Rewrite different bytes but restore the timestamp, so only the clock can tell.
        writeTheme("dark", ":root{--graph_plotLine1:#f00;}");
        assertTrue(css.setLastModified(stamp));
        assertEquals("with a fresh-enough entry the old value stands",
                     new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));

        // Age the entry past the interval rather than sleeping.
        GraphThemeColors.maxCacheAgeMs = 0L;
        assertEquals("the periodic re-read must pick the change up",
                     new Color(255, 0, 0),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
    }

    /** The re-read must be coalesced, so a page of tiles does not re-read per tile. */
    @Test
    public void oneReReadServesEveryTileOnThePage() throws IOException {
        writeTheme("dark", ":root{--graph_plotLine1:#0f9;}");
        GraphThemeColors.maxCacheAgeMs = 60_000L;
        // First lookup parses and stamps; the rest must reuse it even though the interval
        // is long, which is only true if parsedAt is updated by the first parse.
        for (int i = 0; i < 5; i++) {
            assertEquals(new Color(0x00, 0xff, 0x99),
                         GraphThemeColors.lineColor(themeDir, "dark", 0));
        }
        assertTrue(GraphThemeColors.maxCacheAgeMs > 0);
    }

    private static void touch(File f, long stamp) {
        assertTrue("could not set the timestamp on " + f, f.setLastModified(stamp));
    }

    // ---- gradient fills ----

    /**
     * A fill may be a wash between two inks, the form the minigraph's own fill variables use.
     *
     * <p>The alpha of each stop is honoured, so a wash that fades out fades: this is the
     * {@code #48f5} to {@code #48f2} of a light theme's {@code --minigraph_in_fill}, where the
     * fourth digit is the alpha.
     */
    @Test
    public void aFillMayBeAGradientWithAlphaOnEachStop() throws IOException {
        writeTheme("light", ":root{--graph_plotFill1:linear-gradient(#48f5,#48f2);}");
        Paint paint = GraphThemeColors.pathPaint(themeDir, "light", 0, 100);
        assertTrue("a declared gradient must be painted as one, not as a flat colour: " + paint,
                   paint instanceof GradientPaint);
        GradientPaint gradient = (GradientPaint) paint;
        assertEquals(new Color(0x44, 0x88, 0xff, 0x55), gradient.getColor1());
        assertEquals(new Color(0x44, 0x88, 0xff, 0x22), gradient.getColor2());
    }

    /** The wash runs over the frame's height, top to bottom. */
    @Test
    public void aGradientSpansTheFrameHeight() throws IOException {
        writeTheme("light", ":root{--graph_plotFill1:linear-gradient(#f00,#00f);}");
        GradientPaint gradient =
            (GradientPaint) GraphThemeColors.pathPaint(themeDir, "light", 0, 120);
        assertEquals(0d, gradient.getPoint1().getY(), 0.001d);
        assertEquals(120d, gradient.getPoint2().getY(), 0.001d);
    }

    /** A stated direction is skipped rather than obeyed: an area is a vertical wash. */
    @Test
    public void aStatedDirectionIsSkipped() throws IOException {
        writeTheme("light", ":root{--graph_plotFill1:linear-gradient(to bottom,#f00 0%,#00f 100%);}");
        Paint paint = GraphThemeColors.pathPaint(themeDir, "light", 0, 50);
        assertTrue("a direction and stop positions must not stop the wash being painted: " + paint,
                   paint instanceof GradientPaint);
        GradientPaint gradient = (GradientPaint) paint;
        assertEquals(Color.RED, gradient.getColor1());
        assertEquals(Color.BLUE, gradient.getColor2());
    }

    /**
     * One stop is a colour, not a wash, and an unreadable one is no declaration at all.
     *
     * <p>Both fall back to the fill's ink, which for a gradient is its first stop.
     */
    @Test
    public void aGradientNeedsTwoStopsAndEveryStopMustParse() throws IOException {
        writeTheme("light", ":root{--graph_plotFill1:linear-gradient(#0f9);}");
        assertEquals("a single stop is not a gradient",
                     new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.pathPaint(themeDir, "light", 0, 80));

        writeTheme("dark", ":root{--graph_plotFill1:linear-gradient(#f00, notacolour);}");
        assertEquals("a wash with an unreadable stop is not painted",
                     GraphThemeColors.pathColor(null, "dark", 0),
                     GraphThemeColors.pathPaint(themeDir, "dark", 0, 80));
    }

    /** A gradient has no single colour, so the ink callers want is its first stop. */
    @Test
    public void aGradientsInkIsItsFirstStop() throws IOException {
        writeTheme("light", ":root{--graph_plotFill1:linear-gradient(#48f5,#48f2);}");
        assertEquals(new Color(0x44, 0x88, 0xff, 0x55),
                     GraphThemeColors.pathColor(themeDir, "light", 0));
    }

    // ---- the frame's other elements ----

    /**
     * Text, axes and both grids are the theme's to state, each with a built-in per theme.
     *
     * <p>Every one of these was a constant in the renderer, so a theme could not have an axis
     * or a gridline in its own colour. All four are read from the stylesheet now, and fall
     * back per theme when a theme says nothing.
     */
    @Test
    public void theFrameElementsAreThemeable() throws IOException {
        writeTheme("midnight", ":root{--graph_textColor:#c9ceff;--graph_axisColor:#c9ceff80;"
                               + "--graph_gridMinor:#20408040;--graph_gridMajor:#ff20c070;}");
        assertEquals(new Color(0xc9, 0xce, 0xff), GraphThemeColors.textColor(themeDir, "midnight"));
        assertEquals(new Color(0xc9, 0xce, 0xff, 0x80),
                     GraphThemeColors.axisColor(themeDir, "midnight"));
        assertEquals(new Color(0x20, 0x40, 0x80, 0x40),
                     GraphThemeColors.gridMinorColor(themeDir, "midnight"));
        assertEquals(new Color(0xff, 0x20, 0xc0, 0x70),
                     GraphThemeColors.gridMajorColor(themeDir, "midnight"));
    }

    /**
     * A theme that states none of the frame colours gets the one built-in set, never null.
     *
     * <p>Not one set per theme any more. The built-ins exist for a stylesheet that is absent
     * or empty, and a theme name on its own is not a palette; a theme with a look of its own
     * states it in its own {@code console.css}. Every shipped theme declares all of them.
     */
    @Test
    public void everyThemeFallsBackToTheSameBuiltInFrameColours() {
        for (String theme : new String[] { "light", "dark", "midnight", "classic", "noSuchTheme" }) {
            assertNotNull(theme + " font", GraphThemeColors.textColor(null, theme));
            assertNotNull(theme + " axis", GraphThemeColors.axisColor(null, theme));
            assertNotNull(theme + " grid", GraphThemeColors.gridMinorColor(null, theme));
            assertNotNull(theme + " mgrid", GraphThemeColors.gridMajorColor(null, theme));
        }
        assertEquals(GraphThemeColors.textColor(null, "light"),
                     GraphThemeColors.textColor(null, "midnight"));
        assertEquals(GraphThemeColors.gridMinorColor(null, "light"),
                     GraphThemeColors.gridMinorColor(null, "midnight"));
    }

    /**
     * The plot background, the edge shading, the restart rule and the compact-tile gridline are
     * the theme's to state too.
     *
     * <p>Each of these was a constant the renderer chose by theme name, which put the palette
     * in two places and let the two drift.
     */
    @Test
    public void theRemainingFrameColoursAreThemeable() throws IOException {
        writeTheme("midnight", ":root{--graph_background:#020018c0;--graph_edgeShade:#00000000;"
                               + "--graph_restartMarker:#dc1030dc;--graph_gridCompact:#20408040;}");
        assertEquals(new Color(0x02, 0x00, 0x18, 0xc0),
                     GraphThemeColors.backgroundColor(themeDir, "midnight"));
        assertEquals(new Color(0, 0, 0, 0),
                     GraphThemeColors.edgeShadeColor(themeDir, "midnight"));
        assertEquals(new Color(0xdc, 0x10, 0x30, 0xdc),
                     GraphThemeColors.restartMarkerColor(themeDir, "midnight"));
        assertEquals(new Color(0x20, 0x40, 0x80, 0x40),
                     GraphThemeColors.compactGridColor(themeDir, "midnight"));
    }

    // ---- line width ----

    /** A theme states how heavy its plots look; both widths are its own to pick. */
    @Test
    public void bothLineWidthsAreThemeable() throws IOException {
        writeTheme("dark", ":root{--graph_plotLineWidth:1.25;--graph_plotLineWidthWide:3;}");
        assertEquals(1.25f, GraphThemeColors.plotLineWidth(themeDir, "dark"), 0f);
        assertEquals(3f, GraphThemeColors.plotLineWidthWide(themeDir, "dark"), 0f);
    }

    /**
     * A width that is absent, unparseable or out of range falls back rather than drawing.
     *
     * <p>Zero and a negative width would drop the line entirely, and a very wide one would
     * cover the data it is meant to show, so both are treated as no opinion.
     *
     * <p>Asserted against the no-theme answer rather than a literal. What this test is about is
     * that the fallback <em>path</em> runs and yields whatever the built-in default is; pinning
     * the number here as well only created a second, conflicting statement of what the default
     * is. {@link #theLineWidthFallbackMatchesTheLightStylesheet} is where the value is pinned, to
     * what the shipped themes declare.
     */
    @Test
    public void anUnusableLineWidthFallsBack() throws IOException {
        float plain = GraphThemeColors.plotLineWidth(null, "noSuchTheme");
        float wide = GraphThemeColors.plotLineWidthWide(null, "noSuchTheme");
        float condensed = GraphThemeColors.plotLineWidthCondensed(null, "noSuchTheme");
        for (String body : new String[] {
                ":root{--graph_plotLineWidth:0;--graph_plotLineWidthWide:thick;"
                        + "--graph_plotLineWidthCondensed:0;}",
                ":root{--graph_plotLineWidth:-1;--graph_plotLineWidthWide:999;"
                        + "--graph_plotLineWidthCondensed:-1;}",
                ":root{--graph_plotLineWidth:thick;--graph_plotLineWidthWide:0;"
                        + "--graph_plotLineWidthCondensed:999;}" }) {
            writeTheme("dark", body);
            assertEquals(plain, GraphThemeColors.plotLineWidth(themeDir, "dark"), 0f);
            assertEquals(wide, GraphThemeColors.plotLineWidthWide(themeDir, "dark"), 0f);
            assertEquals(condensed, GraphThemeColors.plotLineWidthCondensed(themeDir, "dark"), 0f);
        }
    }

    // ---- per-plot independence ----

    /**
     * Each of the four plot variables is read on its own.
     *
     * <p>These were not independent twice: the value-shade lookup once hardcoded the first
     * plot, and the extra-series ordinal was computed separately for the colour and the fill
     * and disagreed with the shade, so a series could take one plot's colour and another
     * plot's gradient. Each case below sets exactly one of the four, so a lookup borrowing
     * another's slot shows up as two slots shaded rather than one.
     */
    @Test
    public void eachPlotVariableIsReadIndependently() throws IOException {
        writeTheme("light", ":root{"
                           + "--graph_plotLine1:#0f0 #f00;"
                           + "--graph_plotLine2:#222;"
                           + "--graph_plotFill1:#333;"
                           + "--graph_plotFill2:#00f #f0f;}");
        assertEquals("plot 1 line took the wrong slot", 2,
                     GraphThemeColors.lineValueShade(themeDir, "light", 0).length);
        assertNull("plot 2 line inherited plot 1's gradient",
                   GraphThemeColors.lineValueShade(themeDir, "light", 1));
        // Plot 1's fill is the flat #333; plot 2's is the gradient. Checked on plot 1 with the
        // colour and on plot 2 with the paint, since a flat colour is the negative case.
        assertEquals("plot 1 fill took the wrong slot", new Color(0x33, 0x33, 0x33),
                     GraphThemeColors.pathColor(themeDir, "light", 0));
        assertTrue("plot 2 fill took the wrong slot",
                   GraphThemeColors.pathPaint(themeDir, "light", 1, 100) instanceof GradientPaint);
    }

    /** The mirror image, so a lookup cannot be reading only the first slot by accident. */
    @Test
    public void theSecondSlotIsReadOnItsOwnToo() throws IOException {
        writeTheme("light", ":root{"
                           + "--graph_plotLine1:#111;"
                           + "--graph_plotLine2:#00f #f0f;"
                           + "--graph_plotFill1:#333;"
                           + "--graph_plotFill2:#444;}");
        assertNull("plot 1 line borrowed plot 2's gradient",
                   GraphThemeColors.lineValueShade(themeDir, "light", 0));
        assertEquals("plot 2 line took the wrong slot", 2,
                     GraphThemeColors.lineValueShade(themeDir, "light", 1).length);
        assertEquals(new Color(0x44, 0x44, 0x44),
                     GraphThemeColors.pathColor(themeDir, "light", 1));
    }

    /** A line's flat colour is the gradient's first stop, so the legend matches the theme. */
    @Test
    public void aShadedPlotStillReportsAFlatColourForItsLegend() throws IOException {
        writeTheme("light", ":root{--graph_plotLine1:#2ec23e40 #f0000008;}");
        assertEquals(new Color(0x2e, 0xc2, 0x3e, 0x40),
                     GraphThemeColors.lineColor(themeDir, "light", 0));
    }

    /**
     * An extra series' shading comes from its own plot's declaration.
     *
     * <p>The inverted-ordinal bug was invisible while every shipped theme draws both plots
     * flat, because a flat plot has no stops to ask for. This writes a theme that shades slot 1
     * and leaves slot 0 flat - the case that distinguishes the two - and checks that a lookup
     * for an extra series resolves to slot 1.
     */
    @Test
    public void anExtraSeriesResolvesTheSecondPlotsShading() throws IOException {
        writeTheme("light", ":root{--graph_plotLine1:#4488ffaa;"
                               + "--graph_plotLine2:#00f000 #f0f000;}");
        Color[] shade = GraphThemeColors.lineValueShade(themeDir, "light", 1);
        assertNotNull("the theme's second plot is not shaded, so this proves nothing", shade);
        assertEquals(2, shade.length);
        assertEquals("the first plot took the second's shading",
                     new Color(0x44, 0x88, 0xff, 0xaa),
                     GraphThemeColors.lineColor(themeDir, "light", 0));
    }

    /**
     * Every shipped theme declares every plot-line width the renderer may ask for.
     *
     * <p>Presence is the contract, not a value. A theme that omits one falls back to the
     * built-in default, which is a separate question with its own test
     * ({@link #anUnusableLineWidthFallsBack}). This one only notices a variable that has
     * stopped being declared at all - which is how the condensed width went missing from
     * every theme without anything failing.
     */
    @Test
    public void everyShippedThemeDeclaresEveryPlotLineWidth() throws IOException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            String text = new String(Files.readAllBytes(
                    new File(new File(themes, theme), "console.css").toPath()),
                    StandardCharsets.UTF_8);
            for (String var : new String[] { GraphThemeColors.VAR_PLOT_LINE_WIDTH,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_WIDE,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_CONDENSED,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_FILLED,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_FILLED_WIDE,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_FILLED_CONDENSED }) {
                assertNotNull(theme + "/console.css does not declare " + var,
                              declaredValue(text, var));
            }
        }
    }

    /**
     * A declared width has to be a usable one, or the theme is treated as having no opinion.
     */
    @Test
    public void aShippedPlotLineWidthIsAUsableNumber() throws IOException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            String text = new String(Files.readAllBytes(
                    new File(new File(themes, theme), "console.css").toPath()),
                    StandardCharsets.UTF_8);
            for (String var : new String[] { GraphThemeColors.VAR_PLOT_LINE_WIDTH,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_WIDE,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_CONDENSED,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_FILLED,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_FILLED_WIDE,
                                             GraphThemeColors.VAR_PLOT_LINE_WIDTH_FILLED_CONDENSED }) {
                String declared = declaredValue(text, var);
                assertNotNull(theme + "/console.css does not declare " + var, declared);
                float parsed;
                try {
                    parsed = Float.parseFloat(declared);
                } catch (NumberFormatException nfe) {
                    throw new AssertionError(theme + " declares " + var + " as " + declared
                                             + ", which is not a number");
                }
                assertTrue(theme + " declares " + var + " as " + declared + ", out of range",
                           parsed > 0f && !Float.isNaN(parsed));
            }
        }
    }

    /** The wide width is the one a tile past the width threshold asks for. */
    @Test
    public void theWideWidthThresholdIsAboveTheCommonTileWidth() {
        assertEquals(800, GraphThemeColors.WIDE_WIDTH);
    }

    /** A stated colour is used as written, including its alpha. */
    @Test
    public void aStatedFrameColourKeepsItsAlpha() throws IOException {
        writeTheme("light", ":root{--graph_gridMajor:#f0a8;}");
        assertEquals(new Color(0xff, 0x00, 0xaa, 0x88),
                     GraphThemeColors.gridMajorColor(themeDir, "light"));
    }

    /** An unusable declaration is no declaration, so the built-in colour stands. */
    @Test
    public void anUnusableFrameColourFallsBack() throws IOException {
        writeTheme("light", ":root{--graph_textColor:rebeccapurple;}");
        assertEquals(GraphThemeColors.textColor(null, "light"),
                     GraphThemeColors.textColor(themeDir, "light"));
    }

    /** Both grids take a dot pattern, and either can be dashed while the other stays solid. */
    @Test
    public void eachGridTakesItsOwnDash() throws IOException {
        writeTheme("dark", ":root{--graph_gridMinorDash:1 3;}");
        assertEquals(1f, GraphThemeColors.gridMinorDash(themeDir, "dark"), 0.001f);
        assertEquals(3f, GraphThemeColors.gridMinorDashGap(themeDir, "dark"), 0.001f);
        assertEquals("the major grid keeps the built-in pattern, solid",
                     0f, GraphThemeColors.gridMajorDash(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.gridMajorDashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_gridMajorDash:2 5;}");
        assertEquals("the minor grid keeps the built-in pattern, solid",
                     0f, GraphThemeColors.gridMinorDash(themeDir, "midnight"), 0.001f);
        assertEquals(0f, GraphThemeColors.gridMinorDashGap(themeDir, "midnight"), 0.001f);
        assertEquals(2f, GraphThemeColors.gridMajorDash(themeDir, "midnight"), 0.001f);
        assertEquals(5f, GraphThemeColors.gridMajorDashGap(themeDir, "midnight"), 0.001f);
    }

    /**
     * Gridline dashes go through the same length and gap rules as a series dash, so an
     * unusable one falls back to the built-in pattern: a solid gridline, for every theme.
     */
    @Test
    public void anUnusableGridDashFallsBackToTheBuiltInPattern() throws IOException {
        writeTheme("dark", ":root{--graph_gridMinorDash:1 2 3;}");
        assertEquals("a three-value list is refused, not truncated",
                     0f, GraphThemeColors.gridMinorDash(themeDir, "dark"), 0.001f);
        writeTheme("midnight", ":root{--graph_gridMajorDash:100;}");
        assertEquals(0f, GraphThemeColors.gridMajorDash(themeDir, "midnight"), 0.001f);
        writeTheme("light", ":root{--graph_gridMinorDash:-2;}");
        assertEquals(0f, GraphThemeColors.gridMinorDash(themeDir, "light"), 0.001f);
    }

    // ---- the shipped themes ----

    /**
     * Locate the theme sources by walking up to the repository root.
     *
     * @return the theme directory, or null when running outside a source tree
     */
    private static File sourceThemeDir() {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir,
                    "installer/resources/console/themes/console");
            if (candidate.isDirectory()) {return candidate;}
        }
        return null;
    }

    /**
     * The installed location must match where a router actually keeps its themes.
     *
     * <p>This is the bug that made every theme edit look like it did nothing: the path
     * pointed at {@code webapps/console/themes/console}, which does not exist. The failure
     * is silent, because a missing stylesheet is the same as a theme that declares nothing,
     * so both fall back to the built-in defaults.
     */
    @Test
    public void theInstalledThemePathIsWhereTheRouterKeepsThemes() {
        assertEquals("docs/themes/console", GraphThemeColors.THEME_SUBDIR);
        // webapps holds the deployed console war; the themes are not inside it.
        assertFalse("themes are not under webapps",
                    GraphThemeColors.THEME_SUBDIR.startsWith("webapps"));
    }

    /**
     * Every custom property the renderer reads, taken from the reader's own constants.
     *
     * <p>Read by reflection rather than written out, so adding a variable to
     * {@link GraphThemeColors} and forgetting a stylesheet is caught here instead of showing
     * up as a graph quietly drawn in the fallback colour.
     *
     * @return the {@code --graph_*} names, including the numbered per-plot pair
     * @throws IllegalAccessException if a constant cannot be read reflectively
     */
    private static String[] graphVariables() throws IllegalAccessException {
        List<String> vars = new ArrayList<String>();
        for (Field field : GraphThemeColors.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() == String.class && Modifier.isStatic(mods)
                    && Modifier.isPublic(mods) && field.getName().startsWith("VAR_")) {
                vars.add((String) field.get(null));
            }
        }
        // The two per-plot prefixes are package-visible rather than public - they name a family
        // of variables rather than one - so the numbered names are built out of them here.
        for (int plot = 0; plot < GraphThemeColors.PLOTS; plot++) {
            vars.add(GraphThemeColors.VAR_PLOT_LINE_PREFIX + (plot + 1));
            vars.add(GraphThemeColors.VAR_PLOT_FILL_PREFIX + (plot + 1));
        }
        assertTrue("no graph variables found to check", vars.size() > 4);
        return vars.toArray(new String[vars.size()]);
    }

    /** Whether a stylesheet declares the custom property on a line of its own. */
    private static boolean declares(String css, String var) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(var) + "\\s*:").matcher(css).find();
    }

    /**
     * Every variable the code reads must be declared by every shipped theme.
     *
     * <p>This is the invariant that replaced the old "the stylesheets agree with the built-in
     * tables" check, and it is the one that matters now: the stylesheets are the canonical
     * statement of a theme's palette and the built-ins are a single fallback for a theme that
     * declares nothing. A shipped theme never wants that fallback, so a variable it forgets
     * would silently render in light's colour while looking, from the stylesheet, like a
     * deliberate choice. Pinning the declarations is what stops that.
     */
    @Test
    public void everyShippedThemeDeclaresEveryGraphVariable()
            throws IOException, IllegalAccessException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        String[] vars = graphVariables();
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            File dir = new File(themes, theme);
            assertTrue("missing theme " + theme, dir.isDirectory());
            File css = new File(dir, "console.css");
            assertTrue("missing " + theme + " stylesheet", css.isFile());
            String text = new String(Files.readAllBytes(css.toPath()), StandardCharsets.UTF_8);
            for (String var : vars) {
                assertTrue(theme + " does not declare " + var, declares(text, var));
            }
        }
    }

    /**
     * The grid a too-small tile keeps must be one of that theme's own two grids.
     *
     * <p>When the minor grid is dropped, the surviving gridline used to be chosen in code by
     * theme name - the minor colour on the dark themes, the major on the light ones - and is
     * now stated by {@code --graph_gridCompact}. That moves the decision into the stylesheet,
     * which is where it belongs, but nothing else checks it: the coverage test above only asks
     * whether the variable is <em>declared</em>, so a theme could declare any colour at all and
     * still pass. It did, briefly - dark and midnight were given light's minor, painting their
     * small tiles a red gridline against a beige and a blue one respectively.
     *
     * <p>Copying one of the two grid colours is the whole of what this variable is for, so
     * that is what it is held to.
     */
    @Test
    public void everyThemeKeepsOneOfItsOwnGridsOnASmallTile()
            throws IOException, IllegalAccessException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            File css = new File(new File(themes, theme), "console.css");
            String text = new String(Files.readAllBytes(css.toPath()), StandardCharsets.UTF_8);
            String minor = declaredValue(text, GraphThemeColors.VAR_GRID_MINOR);
            String major = declaredValue(text, GraphThemeColors.VAR_GRID_MAJOR);
            String compact = declaredValue(text, GraphThemeColors.VAR_GRID_COMPACT);
            assertNotNull(theme + " declares no minor grid", minor);
            assertNotNull(theme + " declares no major grid", major);
            assertNotNull(theme + " declares no compact grid", compact);
            assertTrue(theme + " keeps '" + compact + "' on a small tile, which is neither its"
                       + " minor grid (" + minor + ") nor its major (" + major + ")",
                       compact.equalsIgnoreCase(minor) || compact.equalsIgnoreCase(major));
        }
    }

    /** The value a stylesheet gives one custom property, or null when it declares none. */
    private static String declaredValue(String css, String var) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(var) + "\\s*:\\s*([^;]+);")
            .matcher(css);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Every theme's two lines must still be tellable apart, now that the colours come
     * from a stylesheet a human edited.
     */
    @Test
    public void shippedThemesKeepTwoDistinguishableLines() throws IOException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            assertNotEquals(theme,
                GraphThemeColors.lineColor(themes, theme, 0),
                GraphThemeColors.lineColor(themes, theme, 1));
        }
    }

    /**
     * The lines and fills are the two inks the minigraph plots.
     *
     * <p>The console shows both on one page - a frame in the graph list, a sparkline beside
     * it - so the same series has to arrive in the same ink either way. The minigraph states
     * its inks as {@code --minigraph_in} and {@code --minigraph_out}, which is where these
     * values come from.
     */
    @Test
    public void theShippedThemesPlotTheirMinigraphInks() throws IOException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        for (String theme : new String[] { "light", "midnight", "classic" }) {
            for (int plot = 0; plot < GraphThemeColors.PLOTS; plot++) {
                Color ink = minigraphInk(themes, theme, plot);
                assertNotNull(theme + " has no minigraph ink for plot " + plot, ink);
                // Channel by channel: "the same ink" is about hue, and the line and the
                // fill carry different alpha on the themes that state one.
                Color line = GraphThemeColors.lineColor(themes, theme, plot);
                assertEquals(theme + " line " + (plot + 1) + " red", ink.getRed(), line.getRed());
                String at = theme + " line " + (plot + 1);
                assertEquals(at + " green", ink.getGreen(), line.getGreen());
                assertEquals(at + " blue", ink.getBlue(), line.getBlue());
                Color path = GraphThemeColors.pathColor(themes, theme, plot);
                assertEquals(theme + " path " + (plot + 1) + " must be the same ink",
                             ink.getRed(), path.getRed());
                assertEquals(ink.getGreen(), path.getGreen());
                assertEquals(ink.getBlue(), path.getBlue());
            }
        }
    }

    /** The theme's minigraph ink for a plot: {@code --minigraph_in}, then {@code _out}. */
    private static Color minigraphInk(File themes, String theme, int plot) {
        File css = new File(new File(themes, theme), "console.css");
        if (!css.isFile()) {return null;}
        String wanted = plot == 0 ? "--minigraph_in:" : "--minigraph_out:";
        try {
            for (String line : new String(Files.readAllBytes(css.toPath()),
                                          StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.startsWith(wanted)) {
                    return org.jfree.svg.SvgColor.parse(line.substring(wanted.length())
                                                         .replace(";", "").trim());
                }
            }
        } catch (IOException ioe) {
            return null;
        }
        return null;
    }
}
