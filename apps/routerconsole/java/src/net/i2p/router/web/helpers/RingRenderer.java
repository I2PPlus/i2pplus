package net.i2p.router.web.helpers;

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

    /**
     * Render a single SVG ring chart.
     *
     * @param score  0.0–1.0 fill level, or &lt; 0 for "collecting" (gray)
     * @param label  short label displayed below the percentage
     * @param value  value string displayed in the center (e.g. "93%", "12", "1.2s")
     * @return inline SVG markup
     */
    public static String renderRing(double score, String label, String value) {
        boolean collecting = score < 0;
        if (score < 0) score = 0;
        if (score > 1) score = 1;
        double offset = RING_CIRCUM * (1.0 - score);
        String cls = collecting ? "gray" : score >= 0.8 ? "green" : score >= 0.5 ? "yellow" : "red";
        return "<svg class=ring viewBox=\"0 0 " + RING_SIZE + " " + RING_SIZE + "\">"
            + "<circle class=\"ring-bg\" cx=\"" + (RING_SIZE / 2) + "\" cy=\"" + (RING_SIZE / 2)
            + "\" r=\"" + RING_RADIUS + "\"/>"
            + "<circle class=\"ring-arc " + cls + "\" cx=\"" + (RING_SIZE / 2)
            + "\" cy=\"" + (RING_SIZE / 2) + "\" r=\"" + RING_RADIUS
            + "\" stroke-dasharray=\"" + RING_CIRCUM + " " + RING_CIRCUM
            + "\" stroke-dashoffset=\"" + offset
            + "\" transform=\"rotate(-90 " + (RING_SIZE / 2) + " " + (RING_SIZE / 2)
            + ")\"/>"
            + "<text class=\"ring-pct " + cls + "\" x=\"" + (RING_SIZE / 2)
            + "\" y=\"" + (RING_SIZE / 2 - 2) + "\">"
            + esc(value) + "</text>"
            + "<text class=ring-label x=\"" + (RING_SIZE / 2)
            + "\" y=\"" + (RING_SIZE / 2 + 12) + "\">"
            + esc(label) + "</text>"
            + "</svg>";
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
        StringBuilder buf = new StringBuilder(256);
        buf.append("<div class=ring-cell>");
        buf.append(renderRing(score, label, value));
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
