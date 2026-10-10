package org.rrd4j.graph;

/**
 * Configuration settings for time axis in RRD graphs. Defines major/minor grid units, label
 * formatting, and time span settings.
 */
class TimeAxisSetting {
    /** Seconds covered by each pixel of the graph. */
    final long secPerPix;
    /** Interval between major grid lines. */
    final TimeUnit majorUnit;
    /** How many major grid lines to draw. */
    final int majorUnitCount;
    /** Interval between minor grid lines. */
    final TimeUnit minorUnit;
    /** How many minor grid lines to draw. */
    final int minorUnitCount;
    /** Interval between axis labels. */
    final TimeUnit labelUnit;
    /** How many axis labels to draw. */
    final int labelUnitCount;
    /** How many label units each label spans. */
    final int labelSpan;
    /** How a label's time is rendered. */
    final TimeLabelFormat format;

    /**
     * Configure a time axis.
     *
     * @param secPerPix seconds covered by each pixel
     * @param minorUnit interval between minor grid lines
     * @param minorUnitCount how many minor grid lines to draw
     * @param majorUnit interval between major grid lines
     * @param majorUnitCount how many major grid lines to draw
     * @param labelUnit interval between axis labels
     * @param labelUnitCount how many axis labels to draw
     * @param labelSpan how many label units each label spans
     * @param format how a label's time is rendered
     */
    TimeAxisSetting(
            long secPerPix,
            TimeUnit minorUnit,
            int minorUnitCount,
            TimeUnit majorUnit,
            int majorUnitCount,
            TimeUnit labelUnit,
            int labelUnitCount,
            int labelSpan,
            TimeLabelFormat format) {
        this.secPerPix = secPerPix;
        this.minorUnit = minorUnit;
        this.minorUnitCount = minorUnitCount;
        this.majorUnit = majorUnit;
        this.majorUnitCount = majorUnitCount;
        this.labelUnit = labelUnit;
        this.labelUnitCount = labelUnitCount;
        this.labelSpan = labelSpan;
        this.format = format;
    }

    /**
     * Configure a time axis with a default label format.
     *
     * @param secPerPix seconds covered by each pixel
     * @param minorUnit interval between minor grid lines
     * @param minorUnitCount how many minor grid lines to draw
     * @param majorUnit interval between major grid lines
     * @param majorUnitCount how many major grid lines to draw
     * @param labelUnit interval between axis labels
     * @param labelUnitCount how many axis labels to draw
     * @param labelSpan how many label units each label spans
     */
    TimeAxisSetting(
            long secPerPix,
            TimeUnit minorUnit,
            int minorUnitCount,
            TimeUnit majorUnit,
            int majorUnitCount,
            TimeUnit labelUnit,
            int labelUnitCount,
            int labelSpan) {
        this.secPerPix = secPerPix;
        this.minorUnit = minorUnit;
        this.minorUnitCount = minorUnitCount;
        this.majorUnit = majorUnit;
        this.majorUnitCount = majorUnitCount;
        this.labelUnit = labelUnit;
        this.labelUnitCount = labelUnitCount;
        this.labelSpan = labelSpan;
        this.format = new SimpleTimeLabelFormat(labelUnit.getLabel());
    }

    /**
     * Copy another axis configuration.
     *
     * @param s the settings to copy
     */
    TimeAxisSetting(TimeAxisSetting s) {
        this.secPerPix = s.secPerPix;
        this.minorUnit = s.minorUnit;
        this.minorUnitCount = s.minorUnitCount;
        this.majorUnit = s.majorUnit;
        this.majorUnitCount = s.majorUnitCount;
        this.labelUnit = s.labelUnit;
        this.labelUnitCount = s.labelUnitCount;
        this.labelSpan = s.labelSpan;
        this.format = s.format;
    }

    /**
     * Configure a time axis from unit counts resolved against the default unit table.
     *
     * @param minorUnit minor grid unit index
     * @param minorUnitCount how many minor grid lines to draw
     * @param majorUnit major grid unit index
     * @param majorUnitCount how many major grid lines to draw
     * @param labelUnit axis label unit index
     * @param labelUnitCount how many axis labels to draw
     * @param labelSpan how many label units each label spans
     * @param format how a label's time is rendered
     */
    TimeAxisSetting(
            int minorUnit,
            int minorUnitCount,
            int majorUnit,
            int majorUnitCount,
            int labelUnit,
            int labelUnitCount,
            int labelSpan,
            TimeLabelFormat format) {
        this(
                0,
                TimeUnit.resolveUnit(minorUnit),
                minorUnitCount,
                TimeUnit.resolveUnit(majorUnit),
                majorUnitCount,
                TimeUnit.resolveUnit(labelUnit),
                labelUnitCount,
                labelSpan,
                format);
    }
    /**
     * With label format
     * @param f the label format applied to the axis tick labels in the returned copy
     * @return a copy of this setting carrying the given label format
     */

    TimeAxisSetting withLabelFormat(TimeLabelFormat f) {
        return new TimeAxisSetting(
                secPerPix,
                minorUnit,
                minorUnitCount,
                majorUnit,
                majorUnitCount,
                labelUnit,
                labelUnitCount,
                labelSpan,
                f);
    }
}
