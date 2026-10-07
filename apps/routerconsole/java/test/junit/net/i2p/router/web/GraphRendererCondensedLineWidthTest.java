package net.i2p.router.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Tests which tiles take the condensed plot width.
 *
 * <p>A condensed tile is a narrow frame carrying enough periods that an unsmoothed polyline
 * crowds, so it takes {@code --graph_plotLineWidthCondensed} rather than the ordinary plot
 * weight. The predicate is package-visible so the boundaries can be pinned without a router.
 */
public class GraphRendererCondensedLineWidthTest {

    /** A tile as the graphs page builds it. The smoothing flag is varied only where it matters. */
    private static GraphRenderer.GraphRenderConfig cfg(int width, int periods, boolean smooth) {
        return GraphRenderer.GraphRenderConfig.builder()
                .width(width)
                .periodCount(periods)
                .smooth(smooth)
                .build();
    }

    /** The reported case: narrow frame, 240 periods, bezier smoothing off. */
    @Test
    public void theReportedCaseIsCondensed() {
        assertTrue(GraphRenderer.isCondensedTile(cfg(266, 240, false)));
    }

    /** The base frame of the same page is narrow too, so it is condensed as well. */
    @Test
    public void theBaseFrameIsCondensedToo() {
        assertTrue(GraphRenderer.isCondensedTile(cfg(400, 240, false)));
    }

    /**
     * Smoothing must not change the answer.
     *
     * <p>Smoothing is a rendering default, not a property of the data, so the same narrow tile
     * carrying the same periods is the same tile either way. Gating on it meant a stepped plot
     * and a smoothed one of identical data came out at different weights.
     */
    @Test
    public void smoothingDoesNotChangeTheAnswer() {
        assertTrue(GraphRenderer.isCondensedTile(cfg(266, 240, true)));
        assertTrue(GraphRenderer.isCondensedTile(cfg(266, 240, false)));
    }

    /** A wide frame has room for its points however many periods it carries. */
    @Test
    public void aWideFrameIsNotCondensed() {
        assertFalse(GraphRenderer.isCondensedTile(cfg(600, 240, false)));
        assertFalse(GraphRenderer.isCondensedTile(cfg(2000, 240, false)));
    }

    /** Few periods on a narrow frame is a short series, not a crowded one. */
    @Test
    public void fewPeriodsAreNotCondensed() {
        assertFalse(GraphRenderer.isCondensedTile(cfg(266, 60, false)));
    }

    /** The period threshold is inclusive at the boundary. */
    @Test
    public void thePeriodThresholdIsInclusive() {
        assertTrue(GraphRenderer.isCondensedTile(cfg(266, GraphRendererCondensedLineWidthTest.THRESHOLD, false)));
        assertFalse(GraphRenderer.isCondensedTile(
                cfg(266, GraphRendererCondensedLineWidthTest.THRESHOLD - 1, false)));
    }

    /** The width threshold is inclusive, so 400 counts and 401 does not. */
    @Test
    public void theWidthThresholdIsInclusive() {
        assertTrue(GraphRenderer.isCondensedTile(cfg(400, 240, false)));
        assertFalse(GraphRenderer.isCondensedTile(cfg(401, 240, false)));
    }

    /** The period count at which a narrow unsmoothed tile becomes condensed. */
    static final int THRESHOLD = 180;
}
