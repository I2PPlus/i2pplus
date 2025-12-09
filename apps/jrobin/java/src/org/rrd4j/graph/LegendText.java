package org.rrd4j.graph;

import java.awt.*;

/**
 * Represents legend text elements in RRD graphs. Extends CommentText to provide colored text for
 * graph legends.
 */
class LegendText extends CommentText {
    /** Color for the legend text and associated legend box */
    final Paint legendColor;

    /**
     * Creates legend text with specified color and text.
     *
     * @param legendColor color for text and legend box
     * @param text legend text content
     */
    LegendText(Paint legendColor, String text) {
        super(text);
        this.legendColor = legendColor;
    }
}
