package org.rrd4j.graph;

import java.util.Locale;
import org.rrd4j.data.DataProcessor;

/**
 * Represents comment text elements in RRD graphs. Handles text resolution, marker processing, and
 * positioning for graph comments and legends.
 */
class CommentText implements RrdGraphConstants {
    /** Original text before resolution */
    protected final String text;

    /** Text after variable substitution and marker processing */
    String resolvedText;

    /** End-of-text alignment marker */
    Markers marker;

    /** Whether this comment is enabled for display */
    boolean enabled;

    /** Screen coordinates for positioning */
    int x, y;

    /**
     * Creates a new comment text element.
     *
     * @param text the original text content
     */
    CommentText(String text) {
        this.text = text;
    }

    /**
     *  Resolves the text with variable substitution and marker processing.
     *
     *  @param l the locale for formatting
     *  @param dproc the data processor for resolving data references
     *  @param valueScaler the value scaler for number formatting
     */
    void resolveText(Locale l, DataProcessor dproc, ValueScaler valueScaler) {
        resolvedText = text;
        marker = null;
        if (resolvedText != null) {
            for (Markers m : Markers.values()) {
                String tryMarker = m.marker;
                if (resolvedText.endsWith(tryMarker)) {
                    marker = m;
                    resolvedText =
                            resolvedText.substring(0, resolvedText.length() - tryMarker.length());
                    trimIfGlue();
                    break;
                }
            }
        }
        enabled = resolvedText != null;
    }

    /**
     *  Trims trailing whitespace if the marker is GLUE_MARKER.
     */
    void trimIfGlue() {
        if (Markers.GLUE_MARKER == marker) {
            resolvedText = resolvedText.replaceFirst("\\s+$", "");
        }
    }

    /**
     *  Returns whether this comment is a print item.
     *  @return true if this is a print comment
     */
    boolean isPrint() {
        return false;
    }

    /**
     *  Returns whether this comment is a valid graph element.
     *  @return true if enabled and not a print item
     */
    boolean isValidGraphElement() {
        return !isPrint() && enabled;
    }
}
