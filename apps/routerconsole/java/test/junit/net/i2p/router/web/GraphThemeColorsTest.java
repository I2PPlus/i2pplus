package net.i2p.router.web;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    // ---- defaults ----

    @Test
    public void withNoStylesheetEveryColourIsTheBuiltInDefault() {
        assertNotNull(GraphThemeColors.lineColor(themeDir, "dark", 0));
        assertNotNull(GraphThemeColors.pathColor(themeDir, "dark", 0));
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
    }

    @Test
    public void anAbsentThemeDirectoryFallsBackRatherThanFailing() {
        assertFalse(new File(themeDir, "noSuchTheme").exists());
        assertNotNull(GraphThemeColors.lineColor(themeDir, "noSuchTheme", 0));
        assertNotNull(GraphThemeColors.pathColor(themeDir, "noSuchTheme", 1));
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "noSuchTheme"), 0.001f);
    }

    @Test
    public void aNullThemeDirectoryFallsBackRatherThanFailing() {
        assertNotNull(GraphThemeColors.lineColor(null, "dark", 0));
        assertNotNull(GraphThemeColors.pathColor(null, "dark", 1));
        assertEquals(1f, GraphThemeColors.dashLength(null, "dark"), 0.001f);
    }

    @Test
    public void aNullThemeNameFallsBack() {
        assertNotNull(GraphThemeColors.lineColor(themeDir, null, 0));
    }

    /** The three themes must stay visually distinct even with no overrides. */
    @Test
    public void theThreeThemesDifferByDefault() {
        Color light = GraphThemeColors.lineColor(themeDir, "light", 0);
        Color dark = GraphThemeColors.lineColor(themeDir, "dark", 0);
        Color midnight = GraphThemeColors.lineColor(themeDir, "midnight", 0);
        assertNotEquals(light, dark);
        assertNotEquals(dark, midnight);
        assertNotEquals(light, midnight);
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
          + "--graph_line_1:#0f9;\n"
          + "--graph_line_2:rgba(255,0,0,.5);\n"
          + "--graph_path_1:#00ff9980;\n"
          + "--graph_path_2:#ee9d;\n"
          + "--graph_dash:2.5;\n"
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
        writeTheme("dark", ":root{--graph_line_1:#0f9;}");
        assertEquals(new Color(0x00, 0xff, 0x99),
            GraphThemeColors.lineColor(themeDir, "dark", 0));
        assertNotEquals(GraphThemeColors.lineColor(themeDir, "dark", 0),
            GraphThemeColors.lineColor(themeDir, "light", 0));
    }

    @Test
    public void aLaterDeclarationInTheSameFileWins() throws IOException {
        writeTheme("dark", ":root{--graph_line_1:#0f9;} :root{--graph_line_1:#f00;}");
        assertEquals(new Color(255, 0, 0),
            GraphThemeColors.lineColor(themeDir, "dark", 0));
    }

    /** A typo must cost one colour, not the frame. */
    @Test
    public void aMalformedValueFallsBackAndLeavesTheOtherSlotAlone() throws IOException {
        writeTheme("dark",
            ":root{--graph_line_1:chartreuse;--graph_line_2:#00f;--graph_path_1:#zzz;}");
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

    @Test
    public void aMalformedDashFallsBackToOnePixel() throws IOException {
        writeTheme("dark", ":root{--graph_dash:dotted;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
    }

    /** An absurd dash length would break the dot pattern, so it is rejected. */
    @Test
    public void anOutOfRangeDashFallsBack() throws IOException {
        writeTheme("dark", ":root{--graph_dash:5000;--graph_path_1:#0f9;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        writeTheme("midnight", ":root{--graph_dash:-2;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
    }

    /** Zero dot ink is the override for "no pattern": the reader reports it as asked. */
    @Test
    public void aZeroDashLengthAsksForASolidLine() throws IOException {
        writeTheme("dark", ":root{--graph_dash:0;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_dash:0 3;}");
        assertEquals("a gap beside a zero dot changes nothing",
                     0f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);

        writeTheme("light", ":root{--graph_dash:0,3;}");
        assertEquals(0f, GraphThemeColors.dashLength(themeDir, "light"), 0.001f);
    }

    /** A zero gap after a real dot derives the gap; it is not a solid-line request. */
    @Test
    public void aZeroGapAfterADotIsNotASolidRequest() throws IOException {
        writeTheme("dark", ":root{--graph_dash:1 0;}");
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
        writeTheme("dark", ":root{--graph_dash:2;}");
        assertEquals(2f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals("an unstated gap must stay unstated",
                     0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);
    }

    /** "1 3" and "1,3" both mean a 1px dot then 3px of space. */
    @Test
    public void aDashPairStatesTheGap() throws IOException {
        writeTheme("dark", ":root{--graph_dash:1 3;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(3f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_dash:2,6;}");
        assertEquals(2f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
        assertEquals(6f, GraphThemeColors.dashGap(themeDir, "midnight"), 0.001f);

        writeTheme("light", ":root{--graph_dash:  1.5 , 4 ;}");
        assertEquals(1.5f, GraphThemeColors.dashLength(themeDir, "light"), 0.001f);
        assertEquals(4f, GraphThemeColors.dashGap(themeDir, "light"), 0.001f);
    }

    @Test
    public void noDashDeclarationMeansOnePixelAndADerivedGap() {
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);
    }

    /**
     * A gap the renderer would have to raise anyway is reported as stated, because this
     * class only reads the stylesheet. The clamping lives in RrdGraphConstants.
     */
    @Test
    public void aTightGapIsPassedThroughForTheRendererToRaise() throws IOException {
        writeTheme("dark", ":root{--graph_dash:1 0.5;}");
        assertEquals(0.5f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);
    }

    @Test
    public void anUnusableDashFallsBackToTheDefault() throws IOException {
        writeTheme("dark", ":root{--graph_dash:dotted;}");
        assertEquals(1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_dash:1 2 3;}");
        assertEquals("a three-value list is refused, not truncated",
                     1f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "midnight"), 0.001f);
    }

    @Test
    public void anOutOfRangeDashValueFallsBack() throws IOException {
        writeTheme("dark", ":root{--graph_dash:100 3;}");
        assertEquals("an absurd dot length is rejected, pair and all",
                     1f, GraphThemeColors.dashLength(themeDir, "dark"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "dark"), 0.001f);

        writeTheme("midnight", ":root{--graph_dash:1 -3;}");
        assertEquals("a negative gap is dropped, leaving the pair's dot",
                     1f, GraphThemeColors.dashLength(themeDir, "midnight"), 0.001f);
        assertEquals(0f, GraphThemeColors.dashGap(themeDir, "midnight"), 0.001f);

        writeTheme("light", ":root{--graph_dash:1 9999;}");
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
        writeTheme("dark", ":root{--graph_line_1:#0f9;}");
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));

        writeTheme("dark", ":root{--graph_line_1:#f00;}");
        touch(new File(new File(themeDir, "dark"), "console.css"), 1_600_000_000_000L);
        assertEquals("the edit was not picked up",
                     new Color(255, 0, 0),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
    }

    /** An unchanged file must keep serving the cached parse. */
    @Test
    public void anUnchangedStylesheetIsNotReparsed() throws IOException {
        File css = writeTheme("dark", ":root{--graph_line_1:#0f9;}");
        long stamp = 1_600_000_000_000L;
        assertTrue("could not set the timestamp", css.setLastModified(stamp));
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));
        // Rewriting the identical bytes and restoring the same timestamp is, by definition,
        // no change; a repeated lookup must not disturb the value.
        writeTheme("dark", ":root{--graph_line_1:#0f9;}");
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
        writeTheme("midnight", ":root{--graph_line_1:#0f9;}");
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
        File css = writeTheme("dark", ":root{--graph_line_1:#0f9;}");
        long stamp = 1_600_000_000_000L;
        assertTrue(css.setLastModified(stamp));
        assertEquals(new Color(0x00, 0xff, 0x99),
                     GraphThemeColors.lineColor(themeDir, "dark", 0));

        // Rewrite different bytes but restore the timestamp, so only the clock can tell.
        writeTheme("dark", ":root{--graph_line_1:#f00;}");
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
        writeTheme("dark", ":root{--graph_line_1:#0f9;}");
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

    // ---- the shipped themes must agree with the built-in defaults ----

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
     * Each shipped theme declares the values the code would have used anyway.
     *
     * <p>This is the check that stops the two from drifting. The CSS exists so a theme
     * author can find and change these colours, not to change how the console looks, so
     * every declaration must resolve to exactly the built-in default. If someone edits a
     * default in {@link GraphThemeColors} without updating the stylesheets, this fails.
     */
    @Test
    public void shippedThemesMatchTheBuiltInDefaults() throws IOException {
        File themes = sourceThemeDir();
        assumeTrue("theme sources not present in this layout", themes != null);
        File bare = folder.newFolder("defaults");
        for (String theme : new String[] { "light", "dark", "midnight", "classic" }) {
            assertTrue("missing theme " + theme, new File(themes, theme).isDirectory());
            for (int plot = 0; plot < GraphThemeColors.PLOTS; plot++) {
                assertEquals(theme + " line " + (plot + 1) + " differs from the default",
                    GraphThemeColors.lineColor(bare, theme, plot),
                    GraphThemeColors.lineColor(themes, theme, plot));
                assertEquals(theme + " path " + (plot + 1) + " differs from the default",
                    GraphThemeColors.pathColor(bare, theme, plot),
                    GraphThemeColors.pathColor(themes, theme, plot));
            }
            assertEquals(theme + " dash differs from the default",
                GraphThemeColors.dashLength(bare, theme),
                GraphThemeColors.dashLength(themes, theme), 0.001f);
        }
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
}
