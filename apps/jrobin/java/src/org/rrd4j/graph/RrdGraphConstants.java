package org.rrd4j.graph;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Stroke;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Class to represent various constants used for graphing. No methods are specified.
 *
 * <p>The fonts settings can be changed use some on the following properties, sorted by increased
 * priority.
 *
 * <ol>
 *   <li><code>org.rrd4j.fonts.properties</code>
 *   <li><code>org.rrd4j.fonts.properties.url</code>
 *   <li><code>org.rrd4j.font.plain</code>
 *   <li><code>org.rrd4j.font.bold</code>
 *   <li><code>org.rrd4j.font.plain.url</code>
 *   <li><code>org.rrd4j.font.bold.url</code>
 * </ol>
 *
 * If either <code>org.rrd4j.fonts.properties</code> or <code>org.rrd4j.fonts.properties.url</code>
 * is used, the file provided contains any other property of lower priority . The last four
 * properties defines directly the plain or bold font. All properties URL related (<code>
 * org.rrd4j.fonts.url</code>, <code>org.rrd4j.font.plain.url</code> and <code>
 * org.rrd4j.font.bold.url</code>) download data from an URL. They are useful when those data are
 * provided by the file system, defined by the OS. The others search for the data in the classpath.
 * So it's easy to provided font-pack as a jar that's put before RRD44J's jar.
 *
 * <p>The default settings uses <code>org.rrd4j.fonts.properties</code> looking for the file <code>
 * /rrd4jfonts.properties</code> in the classpath.
 */
public interface RrdGraphConstants {
    /** Default graph starting time */
    String DEFAULT_START = "end-1d";

    /** Default graph ending time */
    String DEFAULT_END = "now";

    /** HH:mm time format */
    String HH_MM = "HH:mm";

    /** Constant to represent second */
    int SECOND = Calendar.SECOND;

    /** Constant to represent minute */
    int MINUTE = Calendar.MINUTE;

    /** Constant to represent hour */
    int HOUR = Calendar.HOUR_OF_DAY;

    /** Constant to represent day */
    int DAY = Calendar.DAY_OF_MONTH;

    /** Constant to represent week */
    int WEEK = Calendar.WEEK_OF_YEAR;

    /** Constant to represent month */
    int MONTH = Calendar.MONTH;

    /** Constant to represent year */
    int YEAR = Calendar.YEAR;

    /** Constant to represent Monday */
    int MONDAY = Calendar.MONDAY;

    /** Constant to represent Tuesday */
    int TUESDAY = Calendar.TUESDAY;

    /** Constant to represent Wednesday */
    int WEDNESDAY = Calendar.WEDNESDAY;

    /** Constant to represent Thursday */
    int THURSDAY = Calendar.THURSDAY;

    /** Constant to represent Friday */
    int FRIDAY = Calendar.FRIDAY;

    /** Constant to represent Saturday */
    int SATURDAY = Calendar.SATURDAY;

    /** Constant to represent Sunday */
    int SUNDAY = Calendar.SUNDAY;

    /** Index of the canvas color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_CANVAS = 0;

    /** Index of the background color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_BACK = 1;

    /**
     * Index of the top-left graph shade color. Used in {@link RrdGraphDef#setColor(int,
     * java.awt.Paint)}
     */
    int COLOR_SHADEA = 2;

    /**
     * Index of the bottom-right graph shade color. Used in {@link RrdGraphDef#setColor(int,
     * java.awt.Paint)}
     */
    int COLOR_SHADEB = 3;

    /** Index of the minor grid color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_GRID = 4;

    /** Index of the major grid color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_MGRID = 5;

    /** Index of the font color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_FONT = 6;

    /** Index of the frame color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_FRAME = 7;

    /** Index of the arrow color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_ARROW = 8;

    /** Index of the x-axis color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_XAXIS = 9;

    /** Index of the yaxis color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)} */
    int COLOR_YAXIS = 10;



    /** Default first day of the week (obtained from the default locale) */
    int FIRST_DAY_OF_WEEK = Calendar.getInstance(Locale.getDefault()).getFirstDayOfWeek();

    /** Default graph canvas color */
    Color DEFAULT_CANVAS_COLOR = Color.WHITE;

    /** Default graph background color */
    Color DEFAULT_BACK_COLOR = new Color(245, 245, 245);

    /** Default top-left graph shade color */
    Color DEFAULT_SHADEA_COLOR = new Color(200, 200, 200);

    /** Default bottom-right graph shade color */
    Color DEFAULT_SHADEB_COLOR = new Color(150, 150, 150);

    /** Default minor grid color */
    Color DEFAULT_GRID_COLOR = new Color(171, 171, 171, 95);

    /** Default major grid color */
    Color DEFAULT_MGRID_COLOR = new Color(255, 91, 91, 95);

    /** Default font color */
    Color DEFAULT_FONT_COLOR = Color.BLACK;

    /** Default frame color */
    Color DEFAULT_FRAME_COLOR = Color.BLACK;

    /** Default arrow color */
    Color DEFAULT_ARROW_COLOR = new Color(0, 0, 0, 0);

    /** Default x-axis color */
    Color DEFAULT_XAXIS_COLOR = new Color(51, 51, 63, 255);

    /** Default x-axis color */
    Color DEFAULT_YAXIS_COLOR = new Color(51, 51, 63, 255);

    /** An transparent color */
    Color BLIND_COLOR = new Color(0, 0, 0, 0);

    /** Constant to represent left alignment marker */
    @Deprecated String ALIGN_LEFT_MARKER = Markers.ALIGN_LEFT_MARKER.marker;

    /** Constant to represent left alignment marker, without new line */
    @Deprecated String ALIGN_LEFTNONL_MARKER = Markers.ALIGN_LEFTNONL_MARKER.marker;

    /** Constant to represent centered alignment marker */
    @Deprecated String ALIGN_CENTER_MARKER = Markers.ALIGN_CENTER_MARKER.marker;

    /** Constant to represent right alignment marker */
    @Deprecated String ALIGN_RIGHT_MARKER = Markers.ALIGN_RIGHT_MARKER.marker;

    /** Constant to represent justified alignment marker */
    @Deprecated String ALIGN_JUSTIFIED_MARKER = Markers.ALIGN_JUSTIFIED_MARKER.marker;

    /** Constant to represent "glue" marker */
    @Deprecated String GLUE_MARKER = Markers.GLUE_MARKER.marker;

    /** Constant to represent vertical spacing marker */
    @Deprecated String VERTICAL_SPACING_MARKER = Markers.VERTICAL_SPACING_MARKER.marker;

    /** Constant to represent no justification markers */
    @Deprecated String NO_JUSTIFICATION_MARKER = Markers.NO_JUSTIFICATION_MARKER.marker;

    /** Constant to represent in-memory image name */
    String IN_MEMORY_IMAGE = "-";

    /** Default units length */
    int DEFAULT_UNITS_LENGTH = 9;

    /** Default graph width */
    int DEFAULT_WIDTH = 400;

    /** Default graph height */
    int DEFAULT_HEIGHT = 100;

    /** Default image format */
    String DEFAULT_IMAGE_FORMAT = "gif";

    /** Default image quality, used only for jpeg graphs */
    float DEFAULT_IMAGE_QUALITY = 0.8F; // only for jpegs, not used for png/gif

    /** Default value base */
    double DEFAULT_BASE = 1000;

    /**
     * The file that contains font configuration searched in the class path. The default value is
     * <code>/rrd4jfonts.properties</code>
     */
    String PROPERTYFONTSPROPERTIES = "org.rrd4j.fonts.properties";

    /** A possible URL to a configuration file. */
    String PROPERTYFONTSURL = "org.rrd4j.fonts.properties.url";

    /**
     * The name of the plain font, used to define the {@link #DEFAULT_SMALL_FONT} and the {@link
     * RrdGraphDef#GATOR_FONT}. To be found in the classpath.
     */
    String PROPERTYFONTPLAIN = "org.rrd4j.font.plain";

    /**
     * The name of the bold font, used to define the {@link #DEFAULT_LARGE_FONT}. To be found in the
     * classpath.
     */
    String PROPERTYFONTBOLD = "org.rrd4j.font.bold";

    /**
     * A URL to the plain font, used to define the {@link #DEFAULT_SMALL_FONT} and the {@link
     * RrdGraphDef#GATOR_FONT}.
     */
    String PROPERTYFONTPLAINURL = "org.rrd4j.font.plain.url";

    /** A URL to the bold font, used to define the {@link #DEFAULT_LARGE_FONT}. */
    String PROPERTYFONTBOLDURL = "org.rrd4j.font.bold.url";

    /**
     * Font constructor, to use embedded fonts. Not really useful outside internal use for RRD4J.
     */
    class FontConstructor {
        private static final Properties fileProps = new Properties();

        static {
            refreshConf();
        }

        private FontConstructor() {}

        /** Used for tests */
        static void refreshConf() {
            fileProps.clear();
            Optional.ofNullable(
                            System.getProperty(PROPERTYFONTSPROPERTIES, "/rrd4jfonts.properties"))
                    .filter(s -> !s.isEmpty())
                    .map(RrdGraphConstants.class::getResourceAsStream)
                    .ifPresent(
                            t -> {
                                try {
                                    fileProps.load(t);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
            Optional.ofNullable(System.getProperty(PROPERTYFONTSURL))
                    .filter(s -> !s.isEmpty())
                    .ifPresent(
                            t -> {
                                try {
                                    fileProps.load(new URL(t).openStream());
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
            for (String prop :
                    new String[] {
                        PROPERTYFONTPLAIN,
                        PROPERTYFONTBOLD,
                        PROPERTYFONTPLAINURL,
                        PROPERTYFONTBOLDURL
                    }) {
                Optional.ofNullable(System.getProperty(prop))
                        .filter(s -> !s.isEmpty())
                        .ifPresent(s -> fileProps.put(prop, s));
            }
        }

        /**
         * Return the default RRD4J's default font for the given strength
         *
         * @param type {@link java.awt.Font#BOLD} for a bold fond, any other value return plain
         *     style.
         * @param size the size for the new Font
         * @return a new {@link java.awt.Font} instance
         */
        public static Font getFont(int type, int size) {
            /*
                        Function<String, InputStream> fontStream;
                        String fontPath = fileProps.getProperty(type == Font.BOLD ? PROPERTYFONTBOLDURL : PROPERTYFONTPLAINURL);
                        if (fontPath!= null) {
                            fontStream = s -> {
                                try {
                                    return new URL(s).openStream();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            };
                        } else {
                            fontPath = fileProps.getProperty(type == Font.BOLD ? PROPERTYFONTBOLD : PROPERTYFONTPLAIN);
                            fontStream = RrdGraphConstants.class::getResourceAsStream;
                        }
                        try (InputStream fontstream = fontStream.apply(fontPath)) {
                            return Font.createFont(Font.TRUETYPE_FONT, fontstream).deriveFont((float)size);
                        } catch (FontFormatException | IOException e) {
                            throw new RuntimeException(e);
                        }
            */
            return new Font("Monospaced", type, size);
        }
    }

    /** Default graph small font */
    Font DEFAULT_SMALL_FONT = FontConstructor.getFont(Font.PLAIN, 10);

    /** Default graph large font */
    Font DEFAULT_LARGE_FONT = FontConstructor.getFont(Font.BOLD, 12);

    /** Font for the Gator */
    Font GATOR_FONT = FontConstructor.getFont(Font.PLAIN, 9);

    /** Used internally */
    double LEGEND_LEADING = 1.2; // chars

    /** Used internally */
    double LEGEND_LEADING_SMALL = 0.7; // chars

    /** Used internally */
    double LEGEND_BOX_SPACE = 1.2; // chars

    /** Used internally */
    double LEGEND_BOX = 0.7; // chars

    /** Used internally */
    int LEGEND_INTERSPACING = 2; // chars

    /** Used internally */
    int PADDING_LEFT = 0; // pix

    /** Used internally */
    int PADDING_TOP = 5; // pix

    /** Used internally */
    int PADDING_TITLE = 7; // pix

    /** Used internally */
    int PADDING_RIGHT = 20; // pix

    /** Used internally */
    int PADDING_PLOT = 2; // chars

    /** Used internally */
    double PADDING_LEGEND = 2.1; // chars

    /** Used internally */
    int PADDING_BOTTOM = 6; // pix

    /** Used internally */
    int PADDING_VLABEL = 8; // pix

    /** Stroke used to draw grid */
    Stroke GRID_STROKE = new BasicStroke(1);

    /**
     * Ink length of one dot in a series line, in device pixels.
     *
     * @since 0.9.71
     */
    float SERIES_DOT = 1f;

    /**
     * The stroke a plotted series is drawn with.
     *
     * <p>A graph carrying more than one series draws dotted; a graph with a single series
     * draws solid. Dots are what separate two overlapping series, and a lone series has
     * nothing to be separated from - dashing it only adds noise and makes the line look
     * broken. Dots also help where two series of similar hue cross, which colour alone
     * does not resolve.
     *
     * <p>The gap is derived from the stroke width rather than fixed, because a round cap
     * extends each dot by half the width on <i>both</i> ends. With a one-on-one-off pattern
     * a 1.5px line puts 2.5px of ink into a 2px period, so consecutive dots overlap and the
     * line renders solid - dotted in the markup and not on screen. The gap has to exceed
     * the width for the dots to stay separate at any weight; twice the width is the
     * tightest that still leaves a visible space.
     *
     * @param seriesCount how many independently plotted series the graph carries
     * @param width requested line width in pixels
     * @param dashLength ink length of one dot; zero draws a solid line, and negative
     *                   values fall back to {@link #SERIES_DOT}
     * @param dashGap space after the dot, as a CSS {@code --graph_plotDash} pair would state it.
     *                Values at or below zero, or too small to keep the dots apart, fall
     *                back to the derived gap.
     * @return a solid stroke for a lone series or a zero dot length, otherwise round dots
     * @since 0.9.71
     */
    static Stroke seriesStroke(int seriesCount, float width, float dashLength, float dashGap) {
        // A zero dot length is a theme asking for no pattern at all: a solid line.
        if (dashLength == 0f) {
            return new BasicStroke(width);
        }
        if (seriesCount < 2) {
            return new BasicStroke(width);
        }
        float dot = dashLength > 0f ? dashLength : SERIES_DOT;
        float minimum = minimumDashGap(dot, width);
        // A stated gap is honoured only when it leaves the dots visibly separate; asking
        // for a tighter one gets the minimum rather than a solid line from a dotted
        // definition.
        float gap = dashGap >= minimum ? dashGap : minimum;
        return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                               new float[] { dot, gap },
                               0f);
    }

    /**
     * The stroke a gridline is drawn with.
     *
     * <p>A zero dot length is a theme asking for solid gridlines, which is what they have
     * always been; anything else is a dot-and-gap pattern, with the gap raised to
     * {@link #minimumDashGap} when a theme states one too small to keep the dots apart.
     *
     * @param width requested stroke width in pixels
     * @param dashLength ink length of one dot; zero draws a solid line
     * @param dashGap space after the dot, or zero to derive it
     * @return the stroke to draw gridlines with
     * @since 0.9.71
     */
    static Stroke gridStroke(float width, float dashLength, float dashGap) {
        if (dashLength <= 0f) {
            return new BasicStroke(width);
        }
        float minimum = minimumDashGap(dashLength, width);
        float gap = dashGap >= minimum ? dashGap : minimum;
        return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                               new float[] { dashLength, gap },
                               0f);
    }

    /**
     * The smallest gap that keeps consecutive dots from touching at the given stroke width.
     *
     * <p>A round cap extends each dash by half the width on both ends, so one dot lays down
     * {@code dot + width} of ink. Dots stay separate while the period exceeds that, which
     * makes {@code width} the hard limit; twice the width is the tightest value that still
     * leaves a space you can see at small sizes.
     *
     * @param dot ink length of one dot
     * @param width stroke width
     * @return the gap to use when a theme states none
     * @since 0.9.71
     */
    static float minimumDashGap(float dot, float width) {
        return Math.max(dot * 2f, width * 2f);
    }

    /**
     * As {@link #seriesStroke(int, float, float, float)}, at the default dot length and a
     * derived gap.
     *
     * @param seriesCount how many independently plotted series the graph carries
     * @param width requested line width in pixels
     * @param dashLength ink length of one dot; zero draws a solid line
     * @return a solid stroke for a lone series, otherwise round dots
     */
    static Stroke seriesStroke(int seriesCount, float width, float dashLength) {
        return seriesStroke(seriesCount, width, dashLength, 0f);
    }

    /**
     * As {@link #seriesStroke(int, float, float)}, at the default dot length.
     *
     * @param seriesCount how many independently plotted series the graph carries
     * @param width requested line width in pixels
     * @return a solid stroke for a lone series, otherwise round dots
     */
    static Stroke seriesStroke(int seriesCount, float width) {
        return seriesStroke(seriesCount, width, SERIES_DOT);
    }


    /** Stroke used to draw ticks */
    Stroke TICK_STROKE = new BasicStroke(0);

    /**
     * Allowed font tag names which can be used in {@link
     * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
     * java.awt.Font)} method
     */
    enum FontTag {
        /**
         * Index of the default font. Used in {@link
         * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
         * java.awt.Font)}
         */
        DEFAULT,
        /**
         * Index of the title font. Used in {@link
         * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
         * java.awt.Font)}
         */
        TITLE,
        /**
         * Index of the axis label font. Used in {@link
         * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
         * java.awt.Font)}
         */
        AXIS,
        /**
         * Index of the vertical unit label font. Used in {@link
         * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
         * java.awt.Font)}
         */
        UNIT,
        /**
         * Index of the graph legend font. Used in {@link
         * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
         * java.awt.Font)}
         */
        LEGEND,
        /**
         * Index of the edge watermark font. Used in {@link
         * org.rrd4j.graph.RrdGraphDef#setFont(org.rrd4j.graph.RrdGraphConstants.FontTag,
         * java.awt.Font)}
         */
        WATERMARK;
        /**
         * Set
         * @param f the font to record for this tag, replacing any earlier entry
         * @param fonts the per-tag font array, indexed by each tag's ordinal
         */

        public void set(Font f, Font[] fonts) {
            fonts[this.ordinal()] = f;
        }
        /**
         * Get
         * @param f unused, accepted only to mirror {@link #set(Font, Font[])}
         * @param fonts the per-tag font array, indexed by each tag's ordinal
         * @return the font recorded for this tag, or null if none was set
         */

        public Font get(Font f, Font[] fonts) {
            return fonts[this.ordinal()];
        }
    }

    /** Default font tag */
    FontTag FONTTAG_DEFAULT = FontTag.DEFAULT;

    /** Title font tag */
    FontTag FONTTAG_TITLE = FontTag.TITLE;

    /** Axis font tag */
    FontTag FONTTAG_AXIS = FontTag.AXIS;

    /** Unit font tag */
    FontTag FONTTAG_UNIT = FontTag.UNIT;

    /** Legend font tag */
    FontTag FONTTAG_LEGEND = FontTag.LEGEND;

    /** Watermark font tag */
    FontTag FONTTAG_WATERMARK = FontTag.WATERMARK;
}
