package org.rrd4j.graph;

import java.awt.Color;

import org.rrd4j.data.DataProcessor;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that a series line can be shaded by its value.
 *
 * <p>A vertical gradient across the plot area colours a line by height, and height on a value
 * axis is the value - so the same mechanism as a fill gradient shades the stroke by magnitude.
 *
 * <p>The span has to be built by the renderer, which is the only party that knows where the
 * plot area ended up once the title, legend and axis margins took their space. These tests
 * therefore pin the stops the definition holds and the shape of the gradient it produces, not
 * a pixel.
 *
 * @since 0.9.71+
 */
public class SeriesValueShadeTest {

    /** Bottom-to-top stops, as a theme would write them. */
    private static final Color[] STOPS = {
        new Color(0x2e, 0xc2, 0x3e, 0x40), new Color(0xf0, 0x00, 0x00, 0x08),
        new Color(0xd0, 0x00, 0x00, 0x08),
    };

    /**
     * Stops reach the line element that asked for them, in the order written.
     *
     * <p>Held per element rather than once per graph, so two plots on one axis can be shaded
     * independently; that is what makes this test read the element back rather than a field.
     */
    @Test
    public void stopsReachTheLineThatAskedForThem() {
        RrdGraphDef def = new RrdGraphDef();
        def.line("in", null, "l", 2f, STOPS);
        Line line = (Line) def.plotElements.get(0);
        assertNotNull("the line lost its shading", line.valueShade);
        assertEquals(STOPS.length, line.valueShade.length);
        for (int i = 0; i < STOPS.length; i++) {
            assertEquals("stop " + i + " is out of order", STOPS[i], line.valueShade[i]);
        }
    }

    /**
     * Two plots on one axis are shaded independently.
     *
     * <p>This was a real limitation: the stops were held once for the whole graph, so a theme
     * could not shade the second plot without shading the first. The plots are independent in
     * every other respect, so they have to be here too.
     */
    @Test
    public void twoLinesOnOneAxisAreShadedIndependently() {
        RrdGraphDef def = new RrdGraphDef();
        def.line("a", Color.RED, "first", 2f, STOPS);
        def.line("b", Color.BLUE, "second", 2f, null);
        Line first = (Line) def.plotElements.get(0);
        Line second = (Line) def.plotElements.get(1);
        assertNotNull("the shaded plot lost its shading", first.valueShade);
        assertNull("an unshaded plot inherited the other plot's shading", second.valueShade);
    }

    /** A line declared without stops is flat, which is what keeps the shipped themes flat. */
    @Test
    public void aLineWithNoStopsIsFlat() {
        RrdGraphDef def = new RrdGraphDef();
        def.line("in", Color.RED, "l", 2f);
        assertNull(((Line) def.plotElements.get(0)).valueShade);
    }

    /**
     * One colour is not a gradient.
     *
     * <p>A single stop has no span to fade across, so it is treated as no shading rather than
     * as a flat paint - which is also what makes naming two colours the way to opt in.
     */
    @Test
    public void fewerThanTwoStopsMeansNoShading() {
        RrdGraphDef def = new RrdGraphDef();
        def.line("in", Color.RED, "l", 2f, new Color[] { Color.RED });
        assertNull(((Line) def.plotElements.get(0)).valueShade);
        def.line("in2", Color.RED, "l", 2f, (Color[]) null);
        assertNull(((Line) def.plotElements.get(1)).valueShade);
    }

    /** The caller cannot mutate the line's stops after handing them over. */
    @Test
    public void theStopsAreCopied() {
        RrdGraphDef def = new RrdGraphDef();
        Color[] mine = STOPS.clone();
        def.line("in", null, "l", 2f, mine);
        mine[0] = Color.MAGENTA;
        assertEquals("the line kept a reference to the caller's array",
                     STOPS[0], ((Line) def.plotElements.get(0)).valueShade[0]);
    }

    /**
     * Three stops must survive as three.
     *
     * <p>The two-stop gradient type holds exactly two colours, so a theme naming a third - the
     * usual reason is to fade out faster at the top - would be silently truncated to the first
     * pair. Three stops route through the multi-stop type instead, so all of them are painted.
     */
    @Test
    public void threeStopsProduceAThreeStopGradient() throws Exception {
        java.lang.reflect.Method m =
            RrdGraphGenerator.class.getDeclaredMethod("valueShadePaint", Color[].class);
        m.setAccessible(true);
        java.awt.Paint paint =
            (java.awt.Paint) m.invoke(generatorWithPlotArea(), (Object) STOPS);
        assertTrue("three stops collapsed to a two-colour paint: " + paint,
                   paint instanceof java.awt.LinearGradientPaint);
        java.awt.LinearGradientPaint lgp = (java.awt.LinearGradientPaint) paint;
        assertEquals("a stop was dropped", 3, lgp.getColors().length);
        assertEquals(STOPS[0], lgp.getColors()[0]);
        assertEquals(STOPS[2], lgp.getColors()[2]);
    }

    /** Two stops use the two-colour type, which is all it holds. */
    @Test
    public void twoStopsProduceATwoStopGradient() throws Exception {
        java.lang.reflect.Method m =
            RrdGraphGenerator.class.getDeclaredMethod("valueShadePaint", Color[].class);
        m.setAccessible(true);
        java.awt.Paint paint = (java.awt.Paint) m.invoke(
            generatorWithPlotArea(),
            (Object) new Color[] { Color.RED, Color.BLUE });
        assertTrue(paint instanceof java.awt.GradientPaint);
    }

    /**
     * The gradient must span the plot area, not the frame.
     *
     * <p>This is the whole mechanism: a point's colour is decided by its height, and height
     * only means value if the ends of the gradient are the ends of the plot area. Spanning the
     * frame instead would make the same colour mean different values on differently sized
     * tiles.
     */
    @Test
    public void theGradientSpansThePlotArea() throws Exception {
        java.lang.reflect.Method m =
            RrdGraphGenerator.class.getDeclaredMethod("valueShadePaint", Color[].class);
        m.setAccessible(true);
        RrdGraphGenerator gen = generatorWithPlotArea();
        // Plot area is yorigin=100, ysize=60, so the span is y 40..100.
        java.awt.Paint paint = (java.awt.Paint) m.invoke(gen, (Object) new Color[] {
            Color.RED, Color.BLUE });
        assertTrue(paint instanceof java.awt.GradientPaint);
        java.awt.GradientPaint gp = (java.awt.GradientPaint) paint;
        assertEquals(40d, gp.getPoint1().getY(), 0.001d);
        assertEquals(100d, gp.getPoint2().getY(), 0.001d);
    }

    /**
     * No plot area means no gradient, rather than a rejected paint.
     *
     * <p>A gradient across a zero-length span is refused outright by the gradient types, so a
     * degenerate frame has to leave the line flat instead of losing it.
     */
    @Test
    public void aPlotAreaWithNoHeightLeavesTheLineFlat() throws Exception {
        java.lang.reflect.Method m =
            RrdGraphGenerator.class.getDeclaredMethod("valueShadePaint", Color[].class);
        m.setAccessible(true);
        RrdGraphGenerator gen = generatorWithPlotArea();
        ImageParameters im = plotAreaOf(gen);
        im.ysize = 0;
        assertNull(m.invoke(gen, (Object) new Color[] { Color.RED, Color.BLUE }));
    }

    /** The plot box of a generator that has not been given one, so a test can set it. */
    private static ImageParameters plotAreaOf(RrdGraphGenerator gen) throws Exception {
        java.lang.reflect.Field f = RrdGraphGenerator.class.getDeclaredField("im");
        f.setAccessible(true);
        return (ImageParameters) f.get(gen);
    }

    /** A generator whose plot box is set, without needing a full graph definition. */
    private static RrdGraphGenerator generatorWithPlotArea() throws Exception {
        java.lang.reflect.Constructor<RrdGraphGenerator> ctor =
            RrdGraphGenerator.class.getDeclaredConstructor(RrdGraphDef.class, ImageWorker.class,
                                                          DataProcessor.class);
        ctor.setAccessible(true);
        RrdGraphGenerator gen =
            ctor.newInstance(new RrdGraphDef(), new SVGImageWorker(0, 0), null);
        ImageParameters im = plotAreaOf(gen);
        im.yorigin = 100;
        im.ysize = 60;
        return gen;
    }
}
