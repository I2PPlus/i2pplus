package net.i2p.router.web.helpers;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable SVG ring chart generator for health-ring dashboards.
 * Produces inline SVG markup compatible with the ring CSS in shared.css.
 *
 * @since 0.9.70+
 */
public class RingRenderer {

    private static final int RING_SIZE = 90;
    private static final int RING_STROKE = 8;
    private static final int RING_RADIUS = (RING_SIZE - RING_STROKE) / 2;
    private static final double RING_CIRCUM = 2 * Math.PI * RING_RADIUS;

    /** Mode: health (green/yellow/red), activity (blue/cyan) */
    public static final String MODE_HEALTH = "health";
    public static final String MODE_ACTIVITY = "activity";
    public static final String MODE_LATENCY = "latency";
    /** Mode: dual-arc ratio (green + red arcs showing a proportion) */
    public static final String MODE_RATIO = "ratio";
    /** Mode: neutral (green/yellow/gray, never red — for uptime, metrics that aren't inherently bad) */
    public static final String MODE_NEUTRAL = "neutral";
    /** Mode: anomaly — color by deviation from the router's own baseline, not an absolute cap */
    public static final String MODE_ANOMALY = "anomaly";

    /** Deviation bands for MODE_ANOMALY, as a fraction of baseline (|current - baseline| / baseline) */
    static final double ANOMALY_GREEN_BAND = 0.25;
    static final double ANOMALY_YELLOW_BAND = 0.75;

    /** Plot type: mini bars at the bottom of the ring */
    public static final String PLOT_BARS = "bars";
    /** Plot type: line chart clipped to the inner circle area */
    public static final String PLOT_LINE = "line";

    private static final AtomicInteger _clipId = new AtomicInteger();

    /**
     * Render a single SVG ring chart.
     *
     * @param score  0.0–1.0 fill level, or &lt; 0 for "collecting" (gray)
     * @param label  short label displayed below the percentage
     * @param value  value string displayed in the center (e.g. "93%", "12", "1.2s")
     * @return inline SVG markup
     */
    public static String renderRing(double score, String label, String value) {
        return renderRing(score, label, value, MODE_HEALTH);
    }

    /**
     * Render a single SVG ring chart with a specified color mode.
     *
     * @param score  0.0–1.0 fill level, or &lt; 0 for "collecting" (gray)
     * @param label  short label displayed below the percentage
     * @param value  value string displayed in the center
     * @param mode   color mode (MODE_HEALTH, MODE_ACTIVITY, MODE_LATENCY)
     * @return inline SVG markup
     * @since 0.9.70+
     */
    public static String renderRing(double score, String label, String value, String mode) {
        return renderRing(score, label, value, mode, null);
    }

    /**
     * Render a single SVG ring chart with sparkline history.
     *
     * @param score   0.0–1.0 fill level, or &lt; 0 for "collecting" (gray)
     * @param label   short label displayed below the percentage
     * @param value   value string displayed in the center
     * @param mode    color mode (MODE_HEALTH, MODE_ACTIVITY, MODE_LATENCY)
     * @param history recent data points for sparkline, or null to omit
     * @return inline SVG markup
     * @since 0.9.70+
     */
    public static String renderRing(double score, String label, String value, String mode, double[] history) {
        return renderRing(score, label, value, mode, history, PLOT_LINE);
    }

    /**
     * Render a single SVG ring chart with sparkline history and configurable plot type.
     *
     * @param score    0.0–1.0 fill level, or &lt; 0 for "collecting" (gray)
     * @param label    short label displayed below the percentage
     * @param value    value string displayed in the center
     * @param mode     color mode (MODE_HEALTH, MODE_ACTIVITY, MODE_LATENCY)
     * @param history  recent data points for sparkline, or null to omit
     * @param plotType PLOT_BARS or PLOT_LINE (defaults to PLOT_LINE when null)
     * @return inline SVG markup
     * @since 0.9.70+
     */
    public static String renderRing(double score, String label, String value, String mode, double[] history, String plotType) {
        return renderRing(score, label, value, mode, history, plotType, null);
    }

    /**
     * Render a ring with an explicit color class override.
     * When {@code forcedColor} is non-null it is used for the arc/text/plot color
     * instead of the score-derived class (used by MODE_ANOMALY, where color reflects
     * deviation from baseline rather than the raw fill level).
     *
     * @param forcedColor explicit CSS class ("green"/"yellow"/"red"/"gray"), or null
     * @since 0.9.70+
     */
    public static String renderRing(double score, String label, String value, String mode, double[] history,
                                    String plotType, String forcedColor) {
        boolean collecting = score < 0;
        if (score < 0) score = 0;
        if (score > 1) score = 1;
        double offset = RING_CIRCUM * (1.0 - score);
        String cls = (forcedColor != null) ? forcedColor : getColorClass(score, collecting, mode);
        boolean hasHistory = (history != null && history.length > 1);
        if (plotType == null) plotType = PLOT_LINE;
        StringBuilder buf = new StringBuilder(320);
        buf.append("<svg class=ring viewBox=\"0 0 ").append(RING_SIZE).append(' ').append(RING_SIZE).append("\">");
        if (hasHistory && PLOT_LINE.equals(plotType))
            appendLinePlotDefs(buf);
        if (hasHistory && PLOT_LINE.equals(plotType))
            appendLinePlot(buf, history, cls);
        buf.append("<circle class=ring-bg cx=\"").append(RING_SIZE / 2).append("\" cy=\"").append(RING_SIZE / 2)
           .append("\" r=\"").append(RING_RADIUS).append("\"/>");
        if (MODE_RATIO.equals(mode)) {
            // Good arc (0 to score) in green, bad arc (score to 1.0) in red
            buf.append("<circle class=\"ring-arc green\" cx=\"").append(RING_SIZE / 2)
               .append("\" cy=\"").append(RING_SIZE / 2).append("\" r=\"").append(RING_RADIUS)
               .append("\" stroke-dasharray=\"").append(RING_CIRCUM).append(' ').append(RING_CIRCUM)
               .append("\" stroke-dashoffset=\"").append(offset)
               .append("\" transform=\"rotate(-90 ").append(RING_SIZE / 2).append(' ').append(RING_SIZE / 2)
               .append(")\"/>")
               .append("<circle class=\"ring-arc red\" cx=\"").append(RING_SIZE / 2)
               .append("\" cy=\"").append(RING_SIZE / 2).append("\" r=\"").append(RING_RADIUS)
               .append("\" stroke-dasharray=\"").append(RING_CIRCUM).append(' ').append(RING_CIRCUM)
               .append("\" stroke-dashoffset=\"").append(RING_CIRCUM * score)
               .append("\" transform=\"rotate(").append(-90 + 360.0 * score).append(' ').append(RING_SIZE / 2).append(' ').append(RING_SIZE / 2)
               .append(")\"/>");
        } else {
            buf.append("<circle class=\"ring-arc ").append(cls).append("\" cx=\"").append(RING_SIZE / 2)
               .append("\" cy=\"").append(RING_SIZE / 2).append("\" r=\"").append(RING_RADIUS)
               .append("\" stroke-dasharray=\"").append(RING_CIRCUM).append(' ').append(RING_CIRCUM)
               .append("\" stroke-dashoffset=\"").append(offset)
               .append("\" transform=\"rotate(-90 ").append(RING_SIZE / 2).append(' ').append(RING_SIZE / 2)
               .append(")\"/>");
        }
        buf.append("<text class=\"ring-pct ").append(cls).append("\" x=\"").append(RING_SIZE / 2)
           .append("\" y=\"").append(RING_SIZE / 2 - 2).append("\">")
           .append(esc(value)).append("</text>")
           .append("<text class=ring-label x=\"").append(RING_SIZE / 2)
           .append("\" y=\"").append(RING_SIZE / 2 + 12).append("\">")
           .append(esc(label)).append("</text>");
        if (hasHistory && PLOT_BARS.equals(plotType))
            appendBarPlot(buf, history, cls);
        buf.append("</svg>");
        return buf.toString();
    }

    /** Add clip path def for the line plot (inner circle) */
    private static void appendLinePlotDefs(StringBuilder buf) {
        int id = _clipId.incrementAndGet();
        int cr = RING_RADIUS - 6;
        buf.append("<defs><clipPath id=cp").append(id).append(">")
           .append("<circle cx=\"").append(RING_SIZE / 2).append("\" cy=\"").append(RING_SIZE / 2)
           .append("\" r=\"").append(cr).append("\"/>")
           .append("</clipPath></defs>");
    }

    /** Append line plot (polyline + area fill) clipped to the inner circle */
    private static void appendLinePlot(StringBuilder buf, double[] history, String cls) {
        int count = 0;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : history) {
            if (!Double.isNaN(v)) {
                count++;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        if (count < 2) return;
        double range = max - min;
        if (range < 0.001) range = 1.0;
        int cx = RING_SIZE / 2, cy = RING_SIZE / 2;
        int cr = RING_RADIUS - 6;
        int pad = 4;
        int xStart = cx - cr + pad;
        int xEnd = cx + cr - pad;
        int step = (count - 1 > 0) ? (xEnd - xStart) / (count - 1) : 0;
        // Constrain to middle third of the clip circle vertically (decorative)
        int clipInnerH = 2 * (cr - pad);
        int thirdH = clipInnerH / 3;
        int yTop = cy - thirdH / 2;
        int yBottom = cy + thirdH / 2;
        int hRange = yBottom - yTop;

        int id = _clipId.get();
        buf.append("<g clip-path=\"url(#cp").append(id).append(")\" class=\"spark-line ").append(cls).append("\">");

        // area fill polygon
        buf.append("<polygon points=\"");
        buf.append(xStart).append(',').append(yBottom).append(' ');
        int idx = 0;
        double[] pts = new double[count * 2];
        for (double v : history) {
            if (Double.isNaN(v)) continue;
            int x = xStart + idx * step;
            double norm = (v - min) / range;
            int y = yBottom - (int) (norm * hRange);
            pts[idx * 2] = x;
            pts[idx * 2 + 1] = y;
            buf.append(x).append(',').append(y).append(' ');
            idx++;
        }
        buf.append(xStart + (count - 1) * step).append(',').append(yBottom);
        buf.append("\" class=spark-area/>");

        // polyline
        buf.append("<polyline points=\"");
        for (int i = 0; i < count; i++) {
            buf.append((int) pts[i * 2]).append(',').append((int) pts[i * 2 + 1]).append(' ');
        }
        buf.append("\"/>");
        buf.append("</g>");
    }

    /**
     * Append sparkline bar chart to the SVG buffer.
     * Histogram bars rendered at the bottom of the ring area.
     */
    private static void appendBarPlot(StringBuilder buf, double[] history, String cls) {
        int count = 0;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : history) {
            if (!Double.isNaN(v)) {
                count++;
                if (v < min) min = v;
                if (v > max) max = v;
            }
        }
        if (count < 2) return;
        int barW = 3;
        int gap = 1;
        int totalW = count * barW + (count - 1) * gap;
        int startX = (RING_SIZE - totalW) / 2;
        int barMaxH = 10;
        int barMinH = 2;
        int baseY = RING_SIZE - 6;
        double range = max - min;
        if (range < 0.001) range = 1.0;
        int idx = 0;
        for (double v : history) {
            if (Double.isNaN(v)) continue;
            double normalized = (v - min) / range;
            int h = (int) (barMinH + normalized * (barMaxH - barMinH));
            int x = startX + idx * (barW + gap);
            int y = baseY - h;
            buf.append("<rect x=\"").append(x).append("\" y=\"").append(y)
               .append("\" width=\"").append(barW).append("\" height=\"").append(h)
               .append("\" class=\"spark-bar ").append(cls).append("\"/>");
            idx++;
        }
    }

    /**
     * Render a ring cell (ring + optional tooltip) for use inside a grid.
     *
     * @param score   0.0–1.0 fill level
     * @param label   short label
     * @param value   value string in the center
     * @param details tooltip lines, or null/empty for no tooltip
     * @return HTML for the ring-cell div
     */
    public static String renderRingCell(double score, String label, String value, String[] details) {
        return renderRingCell(score, label, value, details, MODE_HEALTH);
    }

    /**
     * Render a ring cell with a specified color mode.
     *
     * @param score   0.0–1.0 fill level
     * @param label   short label
     * @param value   value string in the center
     * @param details tooltip lines, or null/empty for no tooltip
     * @param mode    color mode (MODE_HEALTH, MODE_ACTIVITY, MODE_LATENCY)
     * @return HTML for the ring-cell div
     * @since 0.9.70+
     */
    public static String renderRingCell(double score, String label, String value, String[] details, String mode) {
        return renderRingCell(score, label, value, details, mode, null);
    }

    /**
     * Render a ring cell with sparkline history bars.
     *
     * @param score   0.0–1.0 fill level
     * @param label   short label
     * @param value   value string in the center
     * @param details tooltip lines, or null/empty for no tooltip
     * @param mode    color mode (MODE_HEALTH, MODE_ACTIVITY, MODE_LATENCY)
     * @param history recent data points for sparkline, or null to omit
     * @return HTML for the ring-cell div
     * @since 0.9.70+
     */
    public static String renderRingCell(double score, String label, String value, String[] details, String mode, double[] history) {
        return renderRingCell(score, label, value, details, mode, history, null);
    }

    /**
     * Render a ring cell with an explicit color class override.
     * When {@code forcedColor} is non-null it is used for the arc/text/plot color
     * instead of the score-derived class (used by MODE_ANOMALY).
     *
     * @param forcedColor explicit CSS class ("green"/"yellow"/"red"/"gray"), or null
     * @since 0.9.70+
     */
    public static String renderRingCell(double score, String label, String value, String[] details, String mode,
                                        double[] history, String forcedColor) {
        StringBuilder buf = new StringBuilder(256);
        buf.append("<div class=ring-cell>");
        buf.append(renderRing(score, label, value, mode, history, null, forcedColor));
        if (details != null && details.length > 0) {
            buf.append("<div class=ring-tip>");
            for (int i = 0; i < details.length; i++) {
                if (i > 0) buf.append("<br>");
                buf.append(esc(details[i]));
            }
            buf.append("</div>");
        }
        buf.append("</div>");
        return buf.toString();
    }

    // ---- Multi-section ring (N-way split) ----

    /**
     * A section of a multi-section ring with a proportion and color class.
     * Values are normalized to sum to 1.0 at render time.
     */
    public static class RingSection {
        /** proportion (0.0–1.0) */
        public final double value;
        /** CSS color class: &quot;green&quot;, &quot;red&quot;, &quot;yellow&quot;, &quot;blue&quot;, &quot;cyan&quot;, &quot;gray&quot; */
        public final String cssClass;

        public RingSection(double value, String cssClass) {
            this.value = value;
            this.cssClass = cssClass;
        }
    }

    /**
     * Render a multi-section ring SVG with proportional colored arcs.
     *
     * @param sections array of sections (values normalized to sum 1.0)
     * @param value    center text (e.g. &quot;70%&quot;)
     * @param label    label below the center text
     * @return inline SVG markup
     * @since 0.9.70+
     */
    public static String renderRing(RingSection[] sections, String value, String label) {
        StringBuilder buf = new StringBuilder(320);
        int cx = RING_SIZE / 2, cy = RING_SIZE / 2;
        buf.append("<svg class=ring viewBox=\"0 0 ").append(RING_SIZE).append(' ').append(RING_SIZE).append("\">");
        // sum for normalization
        double total = 0;
        for (RingSection s : sections) { if (s.value > 0) total += s.value; }
        if (total <= 0) total = 1;
        buf.append("<circle class=ring-bg cx=\"").append(cx).append("\" cy=\"").append(cy)
           .append("\" r=\"").append(RING_RADIUS).append("\"/>");
        double cumulative = 0;
        for (RingSection s : sections) {
            if (s.value <= 0) { cumulative += s.value; continue; }
            double pct = s.value / total;
            double rotation = -90 + (360.0 * cumulative / total);
            double offset = RING_CIRCUM * (1.0 - pct);
            buf.append("<circle class=\"ring-arc ").append(esc(s.cssClass))
               .append("\" cx=\"").append(cx).append("\" cy=\"").append(cy)
               .append("\" r=\"").append(RING_RADIUS)
               .append("\" stroke-dasharray=\"").append(RING_CIRCUM).append(' ').append(RING_CIRCUM)
               .append("\" stroke-dashoffset=\"").append(offset)
               .append("\" transform=\"rotate(").append(rotation).append(' ').append(cx).append(' ').append(cy)
               .append(")\"/>");
            cumulative += s.value;
        }
        buf.append("<text class=ring-pct x=\"").append(cx)
           .append("\" y=\"").append(cy - 2).append("\">")
           .append(esc(value)).append("</text>")
           .append("<text class=ring-label x=\"").append(cx)
           .append("\" y=\"").append(cy + 12).append("\">")
           .append(esc(label)).append("</text>")
           .append("</svg>");
        return buf.toString();
    }

    /**
     * Render a multi-section ring cell.
     *
     * @param sections array of sections (values normalized to sum 1.0)
     * @param value    center text
     * @param label    label below the center text
     * @param details  tooltip lines, or null/empty to omit
     * @return HTML for the ring-cell div
     * @since 0.9.70+
     */
    public static String renderRingCell(RingSection[] sections, String value, String label, String[] details) {
        StringBuilder buf = new StringBuilder(256);
        buf.append("<div class=ring-cell>");
        buf.append(renderRing(sections, value, label));
        if (details != null && details.length > 0) {
            buf.append("<div class=ring-tip>");
            for (int i = 0; i < details.length; i++) {
                if (i > 0) buf.append("<br>");
                buf.append(esc(details[i]));
            }
            buf.append("</div>");
        }
        buf.append("</div>");
        return buf.toString();
    }

    /**
     * Determine the CSS color class based on score, collecting state, and mode.
     *
     * @param score      0.0–1.0 (never negative here)
     * @param collecting true if data is still being gathered
     * @param mode       color mode
     * @return CSS class name
     */
    static String getColorClass(double score, boolean collecting, String mode) {
        if (collecting) return "gray";
        if (MODE_ACTIVITY.equals(mode)) {
            return score >= 0.8 ? "blue" : score >= 0.5 ? "cyan" : "gray";
        }
        if (MODE_NEUTRAL.equals(mode)) {
            return score >= 0.8 ? "green" : score >= 0.5 ? "yellow" : "cyan";
        }
        // MODE_ANOMALY color is supplied by the caller (forcedColor); fall back to
        // health thresholds here so a missing override still renders sensibly
        if (MODE_ANOMALY.equals(mode)) {
            return score >= 0.8 ? "green" : score >= 0.5 ? "yellow" : "red";
        }
        // MODE_HEALTH and MODE_LATENCY share same thresholds
        return score >= 0.8 ? "green" : score >= 0.5 ? "yellow" : "red";
    }

    /** HTML-escape a string */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder buf = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': buf.append("&amp;"); break;
                case '<': buf.append("&lt;"); break;
                case '>': buf.append("&gt;"); break;
                case '"': buf.append("&#34;"); break;
                case '\'': buf.append("&#39;"); break;
                default: buf.append(c);
            }
        }
        return buf.toString();
    }
}
