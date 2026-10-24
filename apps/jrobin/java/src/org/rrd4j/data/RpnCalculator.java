package org.rrd4j.data;

import com.tomgibara.crinch.hashing.PerfectStringHash;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.rrd4j.core.Util;

/**
 * Reverse Polish Notation (RPN) expression calculator.<br>
 * Evaluates mathematical expressions on RRD data sources using RPN notation for efficient
 * computation.
 */
class RpnCalculator {
    /** Token_Symbol class. */
    private enum Token_Symbol {
        /** Token referencing a data source variable. */
        TKN_VAR("") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.token.values[s.slot]);
                s.token_rpi = s.rpi;
            }
        },
        /** Token holding a literal number. */
        TKN_NUM("") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.token.number);
            }
        },

        /** Token adding two values. */
        TKN_PLUS("+") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.pop() + c.pop());
            }
        },
        /** Token adding two values, treating unknown as zero. */
        TKN_ADDNAN("ADDNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : x1 + x2));
            }
        },
        /** Token subtracting two values. */
        TKN_MINUS("-") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 - x2);
            }
        },
        /** Token multiplying two values. */
        TKN_MULT("*") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.pop() * c.pop());
            }
        },
        /** Token dividing two values. */
        TKN_DIV("/") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 / x2);
            }
        },
        /** Token taking the remainder of a division. */
        TKN_MOD("%") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 % x2);
            }
        },

        /** Token taking the sine of an angle in radians. */
        TKN_SIN("SIN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.sin(c.pop()));
            }
        },
        /** Token taking the cosine of an angle in radians. */
        TKN_COS("COS") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.cos(c.pop()));
            }
        },
        /** Token taking the base-10 logarithm. */
        TKN_LOG("LOG") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.log(c.pop()));
            }
        },
        /** Token raising e to a power. */
        TKN_EXP("EXP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.exp(c.pop()));
            }
        },
        /** Token taking the square root. */
        TKN_SQRT("SQRT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.sqrt(c.pop()));
            }
        },
        /** Token taking the arctangent, in radians. */
        TKN_ATAN("ATAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.atan(c.pop()));
            }
        },
        /** Token taking the two-argument arctangent, in radians. */
        TKN_ATAN2("ATAN2") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(Math.atan2(x1, x2));
            }
        },

        /** Token rounding down to an integer. */
        TKN_FLOOR("FLOOR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.floor(c.pop()));
            }
        },
        /** Token rounding up to an integer. */
        TKN_CEIL("CEIL") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.ceil(c.pop()));
            }
        },

        /** Token converting degrees to radians. */
        TKN_DEG2RAD("DEG2RAD") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.toRadians(c.pop()));
            }
        },
        /** Token converting radians to degrees. */
        TKN_RAD2DEG("RAD2DEG") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.toDegrees(c.pop()));
            }
        },
        /** Token rounding to the nearest integer. */
        TKN_ROUND("ROUND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.round(c.pop()));
            }
        },
        /** Token raising one value to the power of another. */
        TKN_POW("POW") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(Math.pow(x1, x2));
            }
        },
        /** Token taking the absolute value. */
        TKN_ABS("ABS") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.abs(c.pop()));
            }
        },
        /** Token pushing a pseudo-random value. */
        TKN_RANDOM("RANDOM") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.random());
            }
        },
        /** Token rounding to the nearest integer, alias of ROUND. */
        TKN_RND("RND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.floor(c.pop() * Math.random()));
            }
        },

        /** Token testing whether a value is unknown. */
        TKN_UN("UN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.isNaN(c.pop()) ? 1 : 0);
            }
        },
        /** Token testing whether a value is infinite. */
        TKN_ISINF("ISINF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.isInfinite(c.pop()) ? 1 : 0);
            }
        },
        /** Token testing less-than. */
        TKN_LT("LT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 < x2 ? 1 : 0);
            }
        },
        /** Token testing less-or-equal. */
        TKN_LE("LE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 <= x2 ? 1 : 0);
            }
        },
        /** Token testing greater-than. */
        TKN_GT("GT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 > x2 ? 1 : 0);
            }
        },
        /** Token testing greater-or-equal. */
        TKN_GE("GE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 >= x2 ? 1 : 0);
            }
        },
        /** Token testing equality. */
        TKN_EQ("EQ") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 == x2 ? 1 : 0);
            }
        },
        /** Token testing inequality. */
        TKN_NE("NE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 != x2 ? 1 : 0);
            }
        },
        /** Token choosing one of two values by a condition. */
        TKN_IF("IF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x3 = c.pop();
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 != 0 ? x2 : x3);
            }
        },

        /** Token keeping the smaller of two values. */
        TKN_MIN("MIN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.min(c.pop(), c.pop()));
            }
        },
        /** Token keeping the larger of two values. */
        TKN_MAX("MAX") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.max(c.pop(), c.pop()));
            }
        },
        /** Token keeping the smaller value, unknowns ignored. */
        TKN_MINNAN("MINNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : Math.min(x1, x2)));
            }
        },
        /** Token keeping the larger value, unknowns ignored. */
        TKN_MAXNAN("MAXNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : Math.max(x1, x2)));
            }
        },
        /** Token limiting a value to a range. */
        TKN_LIMIT("LIMIT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x3 = c.pop();
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 < x2 || x1 > x3 ? Double.NaN : x1);
            }
        },

        /** Token duplicating the top of the stack. */
        TKN_DUP("DUP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.peek());
            }
        },
        /** Token exchanging the top two stack entries. */
        TKN_EXC("EXC") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x2);
                c.push(x1);
            }
        },
        /** Token discarding the top of the stack. */
        TKN_POP("POP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.pop();
            }
        },

        /** Token pushing an unknown value. */
        TKN_UNKN("UNKN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.NaN);
            }
        },
        /** Token pushing pi. */
        TKN_PI("PI") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.PI);
            }
        },
        /** Token pushing e. */
        TKN_E("E") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.E);
            }
        },
        /** Token pushing positive infinity. */
        TKN_INF("INF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.POSITIVE_INFINITY);
            }
        },
        /** Token pushing negative infinity. */
        TKN_NEGINF("NEGINF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.NEGATIVE_INFINITY);
            }
        },

        /** Token taking the logical and of two values. */
        TKN_AND("AND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push((x1 != 0 && x2 != 0) ? 1 : 0);
            }
        },
        /** Token taking the logical or of two values. */
        TKN_OR("OR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push((x1 != 0 || x2 != 0) ? 1 : 0);
            }
        },
        /** Token taking the logical exclusive or of two values. */
        TKN_XOR("XOR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(((x1 != 0 && x2 == 0) || (x1 == 0 && x2 != 0)) ? 1 : 0);
            }
        },

        /** Token pushing the previous step's value. */
        TKN_PREV("PREV") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push((s.slot == 0) ? Double.NaN : s.token.values[s.slot - 1]);
            }
        },

        /** Token pushing the consolidation step, in seconds. */
        TKN_STEP("STEP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.timeStep);
            }
        },
        /** Token pushing the current time. */
        TKN_NOW("NOW") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Util.getTime());
            }
        },
        /** Token pushing the current time slot. */
        TKN_TIME("TIME") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.timestamps[s.slot]);
            }
        },
        /** Token pushing the current local time. */
        TKN_LTIME("LTIME") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                TimeZone tz = s.getTimeZone();
                c.push(c.timestamps[s.slot] + ((long) tz.getOffset(c.timestamps[s.slot]) / 1000D));
            }
        },
        /** Token pushing the year of the current time. */
        TKN_YEAR("YEAR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.YEAR));
            }
        },
        /** Token pushing the month of the current time. */
        TKN_MONTH("MONTH") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.MONTH) + 1);
            }
        },
        /** Token pushing the day of the month. */
        TKN_DATE("DATE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.DAY_OF_MONTH));
            }
        },
        /** Token pushing the hour of the day. */
        TKN_HOUR("HOUR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.HOUR_OF_DAY));
            }
        },
        /** Token pushing the minute of the hour. */
        TKN_MINUTE("MINUTE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.MINUTE));
            }
        },
        /** Token pushing the second of the minute. */
        TKN_SECOND("SECOND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.SECOND));
            }
        },
        /** Token pushing the day of the week. */
        TKN_WEEK("WEEK") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.WEEK_OF_YEAR));
            }
        },
        /** Token pushing the sign of a value. */
        TKN_SIGN("SIGN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                c.push(Double.isNaN(x1) ? Double.NaN : x1 > 0 ? +1 : x1 < 0 ? -1 : 0);
            }
        },
        /** Token sorting a variable's values. */
        TKN_SORT("SORT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                int n = (int) c.pop();
                double[] array = new double[n];
                for (int i = 0; i < n; i++) {
                    array[i] = c.pop();
                }
                Arrays.sort(array);
                for (int i = 0; i < n; i++) {
                    c.push(array[i]);
                }
            }
        },
        /** Token reversing a variable's values. */
        TKN_REV("REV") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                int n = (int) c.pop();
                double[] array = new double[n];
                for (int i = 0; i < n; i++) {
                    array[i] = c.pop();
                }
                for (int i = 0; i < n; i++) {
                    c.push(array[i]);
                }
            }
        },
        /** Token averaging a variable's values. */
        TKN_AVG("AVG") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                int count = 0;
                int n = (int) c.pop();
                double sum = 0.0;
                while (n > 0) {
                    double x1 = c.pop();
                    n--;

                    if (Double.isNaN(x1)) {
                        continue;
                    }
                    sum += x1;
                    count++;
                }
                if (count > 0) {
                    c.push(sum / count);
                } else {
                    c.push(Double.NaN);
                }
            }
        },
        /** Token counting a variable's known values. */
        TKN_COUNT("COUNT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.slot + 1.0);
            }
        },
        /** Token computing a moving-average trend. */
        TKN_TREND("TREND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                int dur = (int) c.pop();
                c.pop();
                /*
                 * OK, so to match the output from rrdtool, we have to go *forward* 2 timeperiods.
                 * So at t[59] we use the average of t[1]..t[61]
                 *
                 */

                if ((s.slot + 1) < Math.ceil(dur / c.timeStep)) {
                    c.push(Double.NaN);
                } else {
                    double[] vals = c.dataProcessor.getValues(c.tokens[s.token_rpi].variable);
                    boolean ignorenan = s.token.id == TKN_TRENDNAN;
                    double accum = 0.0;
                    int count = 0;

                    int start = (int) (Math.ceil(dur / c.timeStep));
                    int row = 2;
                    while ((s.slot + row) > vals.length) {
                        row--;
                    }

                    for (; start > 0; start--) {
                        double val = vals[s.slot + row - start];
                        if (ignorenan || !Double.isNaN(val)) {
                            accum = Util.sum(accum, val);
                            ++count;
                        }
                    }
                    c.push((count == 0) ? Double.NaN : (accum / count));
                }
            }
        },
        /** Token computing a moving-average trend, unknowns kept. */
        TKN_TRENDNAN("TRENDNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                TKN_TREND.do_method(c, s);
            }
        },
        /** Token predicting a value from a trend. */
        TKN_PREDICT("PREDICT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.pop(); // Clear the value of our variable

                /* the local averaging window (similar to trend, but better here, as we get better statistics thru numbers)*/
                int locstepsize = (int) c.pop();
                /* the number of shifts and range-checking*/
                int num_shifts = (int) c.pop();
                double[] multipliers;

                // handle negative shifts special
                if (num_shifts < 0) {
                    multipliers = new double[1];
                    multipliers[0] = c.pop();
                } else {
                    multipliers = new double[num_shifts];
                    for (int i = 0; i < num_shifts; i++) {
                        multipliers[i] = c.pop();
                    }
                }

                /* the real calculation */
                double val;

                /* the info on the datasource */
                double[] vals = c.dataProcessor.getValues(c.tokens[s.rpi - 1].variable);

                int locstep = (int) Math.ceil((float) locstepsize / (float) c.timeStep);

                /* the sums */
                double sum = 0;
                double sum2 = 0;
                int count = 0;

                /* now loop for each position */
                int doshifts = Math.abs(num_shifts);
                for (int loop = 0; loop < doshifts; loop++) {
                    /* calculate shift step */
                    int shiftstep;
                    if (num_shifts < 0) {
                        shiftstep = loop * (int) multipliers[0];
                    } else {
                        shiftstep = (int) multipliers[loop];
                    }
                    if (shiftstep < 0) {
                        throw new RuntimeException("negative shift step not allowed: " + shiftstep);
                    }
                    shiftstep = (int) Math.ceil((float) shiftstep / (float) c.timeStep);
                    /* loop all local shifts */
                    for (int i = 0; i <= locstep; i++) {
                        int offset = shiftstep + i;
                        if ((offset >= 0) && (offset < s.slot)) {
                            /* get the value */
                            val = vals[s.slot - offset];

                            /* and handle the non NAN case only*/
                            if (!Double.isNaN(val)) {
                                sum = Util.sum(sum, val);
                                sum2 = Util.sum(sum2, val * val);
                                count++;
                            }
                        }
                    }
                }
                /* do the final calculations */
                val = Double.NaN;
                if (s.token.id == TKN_PREDICT) {
                    /* the average */
                    if (count > 0) {
                        val = sum / count;
                    }
                } else {
                    if (count > 1) {
                        /* the sigma case */
                        val = count * sum2 - sum * sum;
                        if (val < 0) {
                            val = Double.NaN;
                        } else {
                            val = Math.sqrt(val / (count * (count - 1.0)));
                        }
                    }
                }
                c.push(val);
            }
        },
        /** Token giving the sigma of a trend prediction. */
        TKN_PREDICTSIGMA("PREDICTSIGMA") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                TKN_PREDICT.do_method(c, s);
            }
        };

        /** token_string. */
        public final String token_string;

        /**
         * Binds a token to its source text.
         *
         * @param token_string the text that names this token
         */
        Token_Symbol(String token_string) {
            this.token_string = token_string;
        }
        /**
         * Do method
         *
         * @param c the calculator evaluating this token
         * @param s the mutable evaluation state for the current slot
         */
        abstract void do_method(RpnCalculator c, State s);
    }
    /** Every token, for name lookup. */
    private static final Token_Symbol[] symbols;
    /** Perfect hash mapping token text to its symbol. */
    private static final PerfectStringHash perfect;

    static {
        List<String> tokenStrings = new ArrayList<>(Token_Symbol.values().length);
        for (Token_Symbol s : Token_Symbol.values()) {
            if (!s.token_string.isEmpty()) {
                tokenStrings.add(s.token_string);
            }
        }

        String[] array = tokenStrings.toArray(new String[0]);
        perfect = new PerfectStringHash(array);
        symbols = new Token_Symbol[tokenStrings.size()];
        for (Token_Symbol s : Token_Symbol.values()) {
            int hash = perfect.hashAsInt(s.token_string);
            if (hash >= 0) {
                symbols[hash] = s;
            }
        }
    }
    /** Rpn expression */
    private final String rpnExpression;
    /** Source name */
    private final String sourceName;
    /** Data processor */
    private final DataProcessor dataProcessor;
    /** The parsed RPN expression. */
    private final Token[] tokens;
    /** The stack the expression evaluates on. */
    private final RpnStack stack = new RpnStack();
    /** Calculated values */
    private final double[] calculatedValues;
    /** Array of timestamps for the data series */
    private final long[] timestamps;
    /** Time step */
    private final double timeStep;
    /** Sources names */
    private final List<String> sourcesNames;

    /**
     * Rpn calculator
     * @param rpnExpression the RPN expression, its tokens separated by commas
     * @param sourceName the data series an unqualified PREV token refers to
     * @param dataProcessor supplies the timestamps and the per-source value arrays
     */
    RpnCalculator(String rpnExpression, String sourceName, DataProcessor dataProcessor) {
        this.rpnExpression = rpnExpression;
        this.sourceName = sourceName;
        this.dataProcessor = dataProcessor;
        this.timestamps = dataProcessor.getTimestamps();
        this.timeStep = (timestamps[1] - timestamps[0]);
        this.calculatedValues = new double[timestamps.length];
        this.sourcesNames = Arrays.asList(dataProcessor.getSourceNames());
        String[] tokensString = rpnExpression.split(" *, *");
        tokens = new Token[tokensString.length];
        for (int i = 0; i < tokensString.length; i++) {
            tokens[i] = createToken(tokensString[i].trim());
        }
    }
    /**
     * Create token
     *
     * @param parsedText one RPN token string: a symbol, PREV, PREV(variable), a number or a source name
     * @return the token built from the text
     */
    private Token createToken(String parsedText) {
        Token token;
        int hash = perfect.hashAsInt(parsedText);
        if (hash >= 0) {
            token = new Token(symbols[hash]);
        } else if (parsedText.equals("PREV")) {
            token = new Token(Token_Symbol.TKN_PREV, sourceName, calculatedValues);
        } else if (parsedText.startsWith("PREV(") && parsedText.endsWith(")")) {
            String variable = parsedText.substring(5, parsedText.length() - 1);
            token = new Token(Token_Symbol.TKN_PREV, variable, dataProcessor.getValues(variable));
        } else if (Util.isDouble(parsedText)) {
            token = new Token(Token_Symbol.TKN_NUM, Util.parseDouble(parsedText));
        } else if (sourcesNames.contains(parsedText)) {
            token =
                    new Token(
                            Token_Symbol.TKN_VAR, parsedText, dataProcessor.getValues(parsedText));
        } else {
            throw new IllegalArgumentException("Unexpected RPN token encountered: " + parsedText);
        }
        return token;
    }
    /**
     * Calculate values
     * @return one value per timestamp, the expression evaluated at each step
     */
    double[] calculateValues() {
        State s = new State();
        for (int slot = 0; slot < timestamps.length; slot++) {
            resetStack();
            s.rpi = 0;
            s.token_rpi = -1;
            for (Token token : tokens) {
                s.token = token;
                s.slot = slot;
                token.id.do_method(this, s);
                s.rpi++;
            }
            calculatedValues[slot] = pop();
            // check if stack is empty only on the first try
            if (slot == 0 && !isStackEmpty()) {
                throw new IllegalArgumentException(
                        "Stack not empty at the end of calculation. "
                                + "Probably bad RPN expression ["
                                + rpnExpression
                                + "]");
            }
        }
        return calculatedValues;
    }

    /**
     * Calendar field value from timestamp.
     *
     * @param timestamp the unix time in seconds
     * @param field the java.util.Calendar field constant to read
     * @return the value of that field at the time
     */
    private double getCalendarField(double timestamp, int field) {
        Calendar calendar = Util.getCalendar((long) (timestamp));
        return calendar.get(field);
    }
    /**
     * Push a value onto the RPN stack
     *
     * @param x the value to push
     */

    private void push(final double x) {
        stack.push(x);
    }
    /**
     * Pop a value from the RPN stack
     *
     * @return the top value, removed from the stack
     */

    private double pop() {
        return stack.pop();
    }
    /**
     * Examine the top value on the stack without removing it
     *
     * @return the top value, left on the stack
     */

    private double peek() {
        return stack.peek();
    }
    /** Clear all values from the RPN calculation stack */

    private void resetStack() {
        stack.reset();
    }

    /**
     * Whether the stack holds no values.
     * @return true if the stack is empty
     */
    private boolean isStackEmpty() {
        return stack.isEmpty();
    }
    /** RpnStack class. */

    private static final class RpnStack {
        /** The stack an RPN expression is evaluated on. */
        private RpnStack() {}
        /** Maximum RPN stack depth */
        private static final int MAX_STACK_SIZE = 1000;
        /** RPN stack storage */
        private final double[] stack = new double[MAX_STACK_SIZE];
        /** Current stack position */
        private int pos = 0;
        /**
         * Push a value onto the RPN stack
         *
         * @param x the value to push
         */
        void push(double x) {
            if (pos >= MAX_STACK_SIZE) {
                throw new IllegalArgumentException(
                        "PUSH failed, RPN stack full [" + MAX_STACK_SIZE + "]");
            }
            stack[pos++] = x;
        }
        /**
         * Pop a value from the RPN stack
         *
         * @return the top value, removed from the stack
         */
        double pop() {
            if (pos <= 0) {
                throw new IllegalArgumentException("POP failed, RPN stack is empty");
            }
            return stack[--pos];
        }
        /**
         * Examine the top value on the stack without removing it
         *
         * @return the top value, left on the stack
         */
        double peek() {
            if (pos <= 0) {
                throw new IllegalArgumentException("PEEK failed, RPN stack is empty");
            }
            return stack[pos - 1];
        }
        /** Reset the allocator state */
        void reset() {
            pos = 0;
        }

        /**
         * Whether the stack holds no values.
         * @return true if the stack is empty
         */
        boolean isEmpty() {
            return pos <= 0;
        }
    }
    /** State class. */

    private final class State {
        /** Holds the calculator's position in the data. */
        private State() {}
        /** Index of the token being evaluated. */
        private int token_rpi;
        /** Index of the RRA row being evaluated. */
        private int rpi;
        /** The token being evaluated. */
        Token token;
        /** Stack slot being written. */
        int slot;

        /**
         * Gives the zone the date tokens use.
         *
         * @return the time zone
         */
        TimeZone getTimeZone() {
            return RpnCalculator.this.dataProcessor.getTimeZone();
        }
    }
    /** Token class. */

    private static final class Token {
        /** The token this represents. */
        final Token_Symbol id;
        /** The literal number, if this is one. */
        final double number;
        /** The variable's name, if this is one. */
        final String variable;
        /** The variable's values, if this is one. */
        final double[] values;

        /**
         * Creates an operator or constant token.
         *
         * @param id the token to create
         */
        Token(Token_Symbol id) {
            this.id = id;
            this.values = null;
            this.variable = "";
            this.number = Double.NaN;
        }

        /**
         * Creates a variable token.
         *
         * @param id the token to create
         * @param variable the variable's name
         * @param values the variable's values
         */
        Token(Token_Symbol id, String variable, double[] values) {
            this.id = id;
            this.variable = variable;
            this.values = values;
            this.number = Double.NaN;
        }

        /**
         * Creates a literal-number token.
         *
         * @param id the token to create
         * @param number the literal value
         */
        Token(Token_Symbol id, double number) {
            this.id = id;
            this.values = null;
            this.variable = "";
            this.number = number;
        }
    }
}
