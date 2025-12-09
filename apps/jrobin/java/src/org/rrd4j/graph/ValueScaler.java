package org.rrd4j.graph;

/**
 * Utility class for scaling values with appropriate units in RRD graphs. Handles automatic unit
 * selection and value scaling for optimal display.
 */
class ValueScaler {

    private final double base;
    private double magfact = -1; // nothing scaled before, rescale
    private String unit;

    /**
     * Creates a new value scaler with the specified base.
     *
     * @param base the base for unit scaling (typically 1000 for SI units)
     */
    ValueScaler(double base) {
        this.base = base;
    }

    /**
     * Scales a value with appropriate unit selection.
     *
     * @param value the value to scale
     * @param mustRescale if true, forces recalculation of scaling factors
     * @return scaled value with unit
     */
    Scaled scale(double value, boolean mustRescale) {
        // I2P avoid NaN in legend
        if (Double.isNaN(value)) {
            value = 0.0;
        }
        Scaled scaled;
        if (mustRescale) {
            scaled = rescale(value);
        } else if (magfact >= 0) {
            // already scaled, need not rescale
            scaled = new Scaled(value / magfact, unit);
        } else {
            // scaling not requested, but never scaled before - must rescale anyway
            scaled = rescale(value);
            // if zero, scale again on the next try
            if (scaled.value == 0.0 || Double.isNaN(scaled.value)) {
                magfact = -1.0;
            }
        }
        return scaled;
    }

    private Scaled rescale(double value) {
        int sindex;
        if (value == 0.0 || Double.isNaN(value)) {
            sindex = 0;
            magfact = 1.0;
        } else {
            sindex = (int) (Math.floor(Math.log(Math.abs(value)) / Math.log(base)));
            magfact = Math.pow(base, sindex);
        }
        unit = String.valueOf(FindUnit.resolveSymbol(sindex));
        // I2P show 0.xxx instead of xxx m
        if (unit.equals("m")) {
            unit = "";
            magfact *= 1000;
        }
        return new Scaled(value / magfact, unit);
    }

    /** Represents a scaled value with its corresponding unit. */
    static class Scaled {
        final double value;
        final String unit;

        public Scaled(double value, String unit) {
            this.value = value;
            this.unit = unit;
        }

        void dump() {
            System.out.println("[" + value + unit + "]");
        }
    }
}
