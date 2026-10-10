package org.rrd4j.graph;

import java.awt.Font;
import java.awt.Paint;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents the time axis (x-axis) in RRD graphs. Handles time-based grid lines, labels, and tick
 * marks with configurable formatting. Automatically selects appropriate time intervals based on
 * graph time range and pixel density.
 */
class TimeAxis extends Axis {
    private static final TimeAxisSetting[] tickSettings = {
        new TimeAxisSetting(0, TimeUnit.SECOND, 30, TimeUnit.MINUTE, 5, TimeUnit.MINUTE, 5, 0),
        new TimeAxisSetting(2, TimeUnit.MINUTE, 1, TimeUnit.MINUTE, 5, TimeUnit.MINUTE, 5, 0),
        new TimeAxisSetting(5, TimeUnit.MINUTE, 2, TimeUnit.MINUTE, 10, TimeUnit.MINUTE, 10, 0),
        new TimeAxisSetting(10, TimeUnit.MINUTE, 5, TimeUnit.MINUTE, 20, TimeUnit.MINUTE, 20, 0),
        new TimeAxisSetting(30, TimeUnit.MINUTE, 10, TimeUnit.HOUR, 1, TimeUnit.HOUR, 1, 0),
        new TimeAxisSetting(60, TimeUnit.MINUTE, 30, TimeUnit.HOUR, 2, TimeUnit.HOUR, 2, 0),
        new TimeAxisSetting(180, TimeUnit.HOUR, 1, TimeUnit.HOUR, 6, TimeUnit.HOUR, 6, 0),
        new TimeAxisSetting(600, TimeUnit.HOUR, 6, TimeUnit.DAY, 1, TimeUnit.DAY, 1, 24 * 3600),
        new TimeAxisSetting(1800, TimeUnit.HOUR, 12, TimeUnit.DAY, 1, TimeUnit.DAY, 2, 24 * 3600),
        new TimeAxisSetting(
                3600, TimeUnit.DAY, 1, TimeUnit.WEEK, 1, TimeUnit.WEEK, 1, 7 * 24 * 3600),
        new TimeAxisSetting(
                3 * 3600L, TimeUnit.WEEK, 1, TimeUnit.MONTH, 1, TimeUnit.WEEK, 2, 7 * 24 * 3600),
        new TimeAxisSetting(
                6 * 3600L, TimeUnit.MONTH, 1, TimeUnit.MONTH, 1, TimeUnit.MONTH, 1, 30 * 24 * 3600),
        new TimeAxisSetting(
                48 * 3600L,
                TimeUnit.MONTH,
                1,
                TimeUnit.MONTH,
                3,
                TimeUnit.MONTH,
                3,
                30 * 24 * 3600),
        new TimeAxisSetting(
                10 * 24 * 3600L,
                TimeUnit.YEAR,
                1,
                TimeUnit.YEAR,
                1,
                TimeUnit.YEAR,
                1,
                365 * 24 * 3600),
    };
    private final ImageParameters im;
    private final ImageWorker worker;
    private final RrdGraphDef gdef;
    private final Mapper mapper;
    /** Tick setting */
    private TimeAxisSetting tickSetting;
    private final double secPerPix;
    private final Calendar calendar;

    /**
     * Used for tests
     *
     * @param rrdGraph the graph whose image and definition set up this axis
     * @param worker the image worker painting the graph
     */
    TimeAxis(RrdGraph rrdGraph, ImageWorker worker) {
        this.im = rrdGraph.im;
        this.worker = worker;
        this.gdef = rrdGraph.gdef;
        this.mapper = new Mapper(this.gdef, this.im);
        this.secPerPix = (im.end - im.start) / (double) im.xsize;
        this.calendar = Calendar.getInstance(gdef.tz, gdef.locale);
        this.calendar.setFirstDayOfWeek(gdef.firstDayOfWeek);
    }

    /**
     * Sets up this axis from an in-progress generator, reusing the image parameters,
     * worker, graph definition and mapper the generator has already built.
     *
     * @param generator the graph generator that is painting the graph this axis belongs to
     */
    TimeAxis(RrdGraphGenerator generator) {
        this.im = generator.im;
        this.worker = generator.worker;
        this.gdef = generator.gdef;
        this.mapper = generator.mapper;
        this.secPerPix = (im.end - im.start) / (double) im.xsize;
        this.calendar = Calendar.getInstance(gdef.tz, gdef.locale);
        this.calendar.setFirstDayOfWeek(gdef.firstDayOfWeek);
    }
    /**
     * Draw
     */

    boolean draw() {
        chooseTickSettings();
        // early return, avoid exceptions
        if (tickSetting == null) {
            return false;
        }

        drawMinorTicks();
        drawMajorTicks();
        // The major unit is a whole multiple of the minor unit in every tick setting, so
        // every major position is also a minor position. The minor pass therefore has to
        // leave the major positions alone, or each major gridline gets emitted twice, once
        // by each pass, drawn on top of itself. Collected only when the minor grid will
        // actually draw, since that is the only consumer.
        drawMinorGrids(gdef.noMinorGrid ? Collections.emptySet() : collectMajorGridX());
        drawMajorGrids();
        drawLabels();

        return true;
    }

    /**
     * Walks the major unit positions and returns the x coordinates they land on.
     *
     * <p>Collected before anything is drawn so the minor pass can leave them alone. Keyed on
     * the mapped x rather than on the timestamp because the major unit can be a month or a
     * year, which has no fixed length in seconds to test a position against.
     *
     * @return the x coordinates claimed by a major gridline, never null
     * @since 0.9.71+
     */
    private Set<Integer> collectMajorGridX() {
        Set<Integer> xs = new HashSet<>();
        adjustStartingTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
        for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
            if (status == 0) {
                long time = calendar.getTime().getTime() / 1000L;
                xs.add(mapper.xtr(time));
            }
            findNextTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
        }
        return xs;
    }
    /**
     * Draw minor ticks
     */

    private void drawMinorTicks() {
        if (!gdef.noMinorGrid && gdef.drawTicks()) {
            adjustStartingTime(tickSetting.minorUnit, tickSetting.minorUnitCount);
            Paint color = gdef.getColor(ElementsNames.grid);
            int y0 = im.yorigin;
            for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
                if (status == 0) {
                    long time = calendar.getTime().getTime() / 1000L;
                    int x = mapper.xtr(time);
                    worker.drawLine(x, y0 - 1, x, y0 + 1, color, gdef.tickStroke);
                }
                findNextTime(tickSetting.minorUnit, tickSetting.minorUnitCount);
            }
        }
    }
    /**
     * Draw major ticks
     */

    private void drawMajorTicks() {
        if (gdef.drawTicks()) {
            adjustStartingTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
            Paint color = gdef.getColor(ElementsNames.mgrid);
            int y0 = im.yorigin;
            for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
                if (status == 0) {
                    long time = calendar.getTime().getTime() / 1000L;
                    int x = mapper.xtr(time);
                    worker.drawLine(x, y0 - 2, x, y0 + 2, color, gdef.tickStroke);
                }
                findNextTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
            }
        }
    }
    /**
     * Draw minor grids, skipping any position a major gridline already claimed.
     *
     * @param majorX x coordinates owned by the major grid, drawn by {@link #drawMajorGrids()}
     */
    private void drawMinorGrids(Set<Integer> majorX) {
        if (!gdef.noMinorGrid) {
            adjustStartingTime(tickSetting.minorUnit, tickSetting.minorUnitCount);
            Paint color = gdef.getColor(ElementsNames.grid);
            int y0 = im.yorigin, y1 = y0 - im.ysize;
            for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
                if (status == 0) {
                    long time = calendar.getTime().getTime() / 1000L;
                    int x = mapper.xtr(time);
                    if (!majorX.contains(x)) {
                        worker.drawLine(x, y0, x, y1, color, gdef.gridStroke);
                    }
                }
                findNextTime(tickSetting.minorUnit, tickSetting.minorUnitCount);
            }
        }
    }
    /**
     * Draw major grids
     */

    private void drawMajorGrids() {
        adjustStartingTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
        Paint color = gdef.getColor(ElementsNames.mgrid);
        int y0 = im.yorigin, y1 = y0 - im.ysize;
        for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
            if (status == 0) {
                long time = calendar.getTime().getTime() / 1000L;
                int x = mapper.xtr(time);
                worker.drawLine(x, y0, x, y1, color, gdef.majorGridStroke);
            }
            findNextTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
        }
    }
    /**
     * Draw labels
     */

    private void drawLabels() {
        Font font = gdef.getFont(FONTTAG_AXIS);
        Paint color = gdef.getColor(ElementsNames.font);
        adjustStartingTime(tickSetting.labelUnit, tickSetting.labelUnitCount);
        int y = im.yorigin + (int) worker.getFontHeight(font) + 2;
        for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
            String label = tickSetting.format.format(calendar, gdef.locale);
            long time = calendar.getTime().getTime() / 1000L;
            int x1 = mapper.xtr(time);
            int x2 = mapper.xtr(time + tickSetting.labelSpan);
            int labelWidth = (int) worker.getStringWidth(label, font);
            int x = x1 + (x2 - x1 - labelWidth) / 2;
            if (x >= im.xorigin && x + labelWidth <= im.xorigin + im.xsize) {
                worker.drawString(label, x, y, font, color);
            }
            findNextTime(tickSetting.labelUnit, tickSetting.labelUnitCount);
        }
    }
    /**
     * Find next time
     */

    private void findNextTime(TimeUnit timeUnit, int timeUnitCount) {
        switch (timeUnit) {
            case SECOND:
                calendar.add(Calendar.SECOND, timeUnitCount);
                break;
            case MINUTE:
                calendar.add(Calendar.MINUTE, timeUnitCount);
                break;
            case HOUR:
                calendar.add(Calendar.HOUR_OF_DAY, timeUnitCount);
                break;
            case DAY:
                calendar.add(Calendar.DAY_OF_MONTH, timeUnitCount);
                break;
            case WEEK:
                calendar.add(Calendar.DAY_OF_MONTH, 7 * timeUnitCount);
                break;
            case MONTH:
                calendar.add(Calendar.MONTH, timeUnitCount);
                break;
            case YEAR:
                calendar.add(Calendar.YEAR, timeUnitCount);
                break;
        }
    }

    private int getTimeShift() {
        long time = calendar.getTime().getTime() / 1000L;
        return (time < im.start) ? -1 : (time > im.end) ? +1 : 0;
    }
    /**
     * Adjust starting time
     */

    private void adjustStartingTime(TimeUnit timeUnit, int timeUnitCount) {
        calendar.setTime(new Date(im.start * 1000L));
        switch (timeUnit) {
            case SECOND:
                calendar.add(Calendar.SECOND, -(calendar.get(Calendar.SECOND) % timeUnitCount));
                break;
            case MINUTE:
                calendar.set(Calendar.SECOND, 0);
                calendar.add(Calendar.MINUTE, -(calendar.get(Calendar.MINUTE) % timeUnitCount));
                break;
            case HOUR:
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.add(
                        Calendar.HOUR_OF_DAY,
                        -(calendar.get(Calendar.HOUR_OF_DAY) % timeUnitCount));
                break;
            case DAY:
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                break;
            case WEEK:
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                int diffDays = calendar.get(Calendar.DAY_OF_WEEK) - calendar.getFirstDayOfWeek();
                if (diffDays < 0) {
                    diffDays += 7;
                }
                calendar.add(Calendar.DAY_OF_MONTH, -diffDays);
                break;
            case MONTH:
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.DAY_OF_MONTH, 1);
                calendar.add(Calendar.MONTH, -(calendar.get(Calendar.MONTH) % timeUnitCount));
                break;
            case YEAR:
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.DAY_OF_MONTH, 1);
                calendar.set(Calendar.MONTH, 0);
                calendar.add(Calendar.YEAR, -(calendar.get(Calendar.YEAR) % timeUnitCount));
                break;
        }
    }
    /**
     * Choose tick settings
     */

    private void chooseTickSettings() {
        if (gdef.timeAxisSetting != null) {
            tickSetting = new TimeAxisSetting(gdef.timeAxisSetting);
        } else {
            for (TimeAxisSetting i : tickSettings) {
                if (secPerPix < i.secPerPix) {
                    break;
                } else {
                    tickSetting = i;
                }
            }
        }
        if (gdef.timeLabelFormat != null) {
            tickSetting = tickSetting.withLabelFormat(gdef.timeLabelFormat);
        } else {
            gdef.formatProvider
                    .apply(tickSetting.labelUnit)
                    .ifPresent(f -> tickSetting = tickSetting.withLabelFormat(f));
        }
    }
}
