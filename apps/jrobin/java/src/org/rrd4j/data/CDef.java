package org.rrd4j.data;

/**
 * RRD CDEF (Computed Data Source Definition) implementation.<br>
 * Creates computed data sources using Reverse Polish Notation (RPN) expressions on existing data
 * sources.
 */
class CDef extends Source implements NonRrdSource {
    private final String rpnExpression;

    CDef(String name, String rpnExpression) {
        super(name);
        this.rpnExpression = rpnExpression;
    }

    String getRpnExpression() {
        return rpnExpression;
    }

    /** {@inheritDoc} */
    public void calculate(long tStart, long tEnd, DataProcessor dataProcessor) {
        RpnCalculator calc = new RpnCalculator(rpnExpression, getName(), dataProcessor);
        setValues(calc.calculateValues());
    }
}
