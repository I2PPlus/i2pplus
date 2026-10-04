package org.rrd4j.graph;

import java.awt.Color;
import java.io.ByteArrayOutputStream;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that the console theme survives the resize the renderer always performs.
 *
 * <p>The renderer builds the image worker at 0x0 and resizes it to the real dimensions
 * before drawing. That resize rebuilds the underlying graphics object, so a theme set
 * only in the constructor was discarded with it. Every graph then had to infer its theme
 * from the finished drawing, which only works when the graph fills an area - a line-only
 * multi-series graph had nothing to infer from and came out in the classic colours.
 *
 * @since 0.9.71+
 */
public class SVGImageWorkerThemeResizeTest {

    private static final String DARK_TEXT = "#f4f4be";
    private static final String CLASSIC_TEXT = "#33333f";
    private static final String MIDNIGHT_TEXT = "#c9ceff";

    /** Build at 0x0 with a theme, resize like the renderer does, and emit. */
    private static String svgAfterResize(SVGImageWorker w) throws Exception {
        w.resize(300, 120);
        w.fillRect(10, 10, 120, 60, Color.WHITE);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        w.makeImage(os);
        return os.toString("UTF-8");
    }

    /** The regression, in the exact shape the renderer triggers. */
    @Test
    public void aLineOnlyGraphKeepsTheThemeAcrossAResize() throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, "dark");
        String svg = svgAfterResize(w);
        assertTrue("dark theme text expected after resize, got:\n" + head(svg),
                   svg.contains(DARK_TEXT));
        assertFalse("the classic text colour leaked in", svg.contains(CLASSIC_TEXT));
    }

    @Test
    public void withoutAResizeTheThemeStillApplies() throws Exception {
        SVGImageWorker w = new SVGImageWorker(300, 120, false, false, "dark");
        w.fillRect(10, 10, 120, 60, Color.WHITE);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        w.makeImage(os);
        String svg = os.toString("UTF-8");
        assertTrue("dark theme text expected", svg.contains(DARK_TEXT));
    }

    /**
     * Midnight has its own font colour rather than the dark beige, so what matters is
     * that it is not the classic one: a line-only graph that kept its theme cannot have
     * fallen through to the classic branch.
     */
    @Test
    public void midnightAlsoSurvivesTheResize() throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, "midnight");
        String svg = svgAfterResize(w);
        assertFalse("midnight must not render with the classic text colour",
                    svg.contains(CLASSIC_TEXT));
        assertTrue("expected the midnight text colour, got:\n" + head(svg),
                   svg.contains(MIDNIGHT_TEXT));
    }

    /** No theme means infer from the drawing, which is the pre-existing behaviour. */
    @Test
    public void aNullThemeIsNotForcedToDark() throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, null);
        String svg = svgAfterResize(w);
        assertFalse("a line-only drawing has nothing to infer from",
                    svg.contains(DARK_TEXT));
    }

    /** Resizing repeatedly must not lose it either. */
    @Test
    public void repeatedResizesKeepTheTheme() throws Exception {
        SVGImageWorker w = new SVGImageWorker(0, 0, false, false, "dark");
        w.resize(200, 100);
        w.resize(400, 200);
        w.fillRect(10, 10, 120, 60, Color.WHITE);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        w.makeImage(os);
        assertTrue(os.toString("UTF-8").contains(DARK_TEXT));
    }

    private static String head(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}