package org.rrd4j.graph;

/**
 * Configuration settings for time axis in RRD graphs. Defines major/minor grid units, label
 * formatting, and time span settings.
 */
class TimeAxisSetting {
    /** Sec per pix */
    final long secPerPix;
    /** Major unit */
    final TimeUnit majorUnit;
    /** Major unit count */
    final int majorUnitCount;
    /** Minor unit */
    final TimeUnit minorUnit;
    /** Minor unit count */
    final int minorUnitCount;
    /** Label unit */
    final TimeUnit labelUnit;
    /** Label unit count */
    final int labelUnitCount;
    /** Label span */
    final int labelSpan;
    /** Format */
    final TimeLabelFormat format;

    /** Create TimeAxisSetting */
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

    /** Create TimeAxisSetting */
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

    /** Copy constructor */
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

    /** Create TimeAxisSetting */
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
