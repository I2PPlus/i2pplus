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
        TKN_VAR("") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.token.values[s.slot]);
                s.token_rpi = s.rpi;
            }
        },
        TKN_NUM("") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.token.number);
            }
        },

        // Arithmetics
        TKN_PLUS("+") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.pop() + c.pop());
            }
        },
        TKN_ADDNAN("ADDNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : x1 + x2));
            }
        },
        TKN_MINUS("-") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 - x2);
            }
        },
        TKN_MULT("*") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.pop() * c.pop());
            }
        },
        TKN_DIV("/") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 / x2);
            }
        },
        TKN_MOD("%") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 % x2);
            }
        },

        TKN_SIN("SIN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.sin(c.pop()));
            }
        },
        TKN_COS("COS") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.cos(c.pop()));
            }
        },
        TKN_LOG("LOG") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.log(c.pop()));
            }
        },
        TKN_EXP("EXP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.exp(c.pop()));
            }
        },
        TKN_SQRT("SQRT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.sqrt(c.pop()));
            }
        },
        TKN_ATAN("ATAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.atan(c.pop()));
            }
        },
        TKN_ATAN2("ATAN2") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(Math.atan2(x1, x2));
            }
        },

        TKN_FLOOR("FLOOR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.floor(c.pop()));
            }
        },
        TKN_CEIL("CEIL") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.ceil(c.pop()));
            }
        },

        TKN_DEG2RAD("DEG2RAD") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.toRadians(c.pop()));
            }
        },
        TKN_RAD2DEG("RAD2DEG") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.toDegrees(c.pop()));
            }
        },
        TKN_ROUND("ROUND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.round(c.pop()));
            }
        },
        TKN_POW("POW") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(Math.pow(x1, x2));
            }
        },
        TKN_ABS("ABS") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.abs(c.pop()));
            }
        },
        TKN_RANDOM("RANDOM") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.random());
            }
        },
        TKN_RND("RND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.floor(c.pop() * Math.random()));
            }
        },

        // Boolean operators
        TKN_UN("UN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.isNaN(c.pop()) ? 1 : 0);
            }
        },
        TKN_ISINF("ISINF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.isInfinite(c.pop()) ? 1 : 0);
            }
        },
        TKN_LT("LT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 < x2 ? 1 : 0);
            }
        },
        TKN_LE("LE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 <= x2 ? 1 : 0);
            }
        },
        TKN_GT("GT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 > x2 ? 1 : 0);
            }
        },
        TKN_GE("GE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 >= x2 ? 1 : 0);
            }
        },
        TKN_EQ("EQ") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 == x2 ? 1 : 0);
            }
        },
        TKN_NE("NE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 != x2 ? 1 : 0);
            }
        },
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

        // Comparing values
        TKN_MIN("MIN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.min(c.pop(), c.pop()));
            }
        },
        TKN_MAX("MAX") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.max(c.pop(), c.pop()));
            }
        },
        TKN_MINNAN("MINNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : Math.min(x1, x2)));
            }
        },
        TKN_MAXNAN("MAXNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : Math.max(x1, x2)));
            }
        },
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

        // Processing the stack directly
        TKN_DUP("DUP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.peek());
            }
        },
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
        TKN_POP("POP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.pop();
            }
        },

        // Special values
        TKN_UNKN("UNKN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.NaN);
            }
        },
        TKN_PI("PI") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.PI);
            }
        },
        TKN_E("E") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.E);
            }
        },
        TKN_INF("INF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.POSITIVE_INFINITY);
            }
        },
        TKN_NEGINF("NEGINF") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.NEGATIVE_INFINITY);
            }
        },

        // Logical operator
        TKN_AND("AND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push((x1 != 0 && x2 != 0) ? 1 : 0);
            }
        },
        TKN_OR("OR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push((x1 != 0 || x2 != 0) ? 1 : 0);
            }
        },
        TKN_XOR("XOR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(((x1 != 0 && x2 == 0) || (x1 == 0 && x2 != 0)) ? 1 : 0);
            }
        },

        TKN_PREV("PREV") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push((s.slot == 0) ? Double.NaN : s.token.values[s.slot - 1]);
            }
        },

        // Time and date operator
        TKN_STEP("STEP") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.timeStep);
            }
        },
        TKN_NOW("NOW") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Util.getTime());
            }
        },
        TKN_TIME("TIME") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.timestamps[s.slot]);
            }
        },
        TKN_LTIME("LTIME") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                TimeZone tz = s.getTimeZone();
                c.push(c.timestamps[s.slot] + ((long) tz.getOffset(c.timestamps[s.slot]) / 1000D));
            }
        },
        TKN_YEAR("YEAR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.YEAR));
            }
        },
        TKN_MONTH("MONTH") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.MONTH) + 1);
            }
        },
        TKN_DATE("DATE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.DAY_OF_MONTH));
            }
        },
        TKN_HOUR("HOUR") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.HOUR_OF_DAY));
            }
        },
        TKN_MINUTE("MINUTE") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.MINUTE));
            }
        },
        TKN_SECOND("SECOND") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.SECOND));
            }
        },
        TKN_WEEK("WEEK") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.WEEK_OF_YEAR));
            }
        },
        TKN_SIGN("SIGN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                c.push(Double.isNaN(x1) ? Double.NaN : x1 > 0 ? +1 : x1 < 0 ? -1 : 0);
            }
        },
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
        TKN_COUNT("COUNT") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.slot + 1.0);
            }
        },
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
        TKN_TRENDNAN("TRENDNAN") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                TKN_TREND.do_method(c, s);
            }
        },
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
        TKN_PREDICTSIGMA("PREDICTSIGMA") {
            /** Do method */
            @Override
            void do_method(RpnCalculator c, State s) {
                TKN_PREDICT.do_method(c, s);
            }
        };

        /** token_string. */
        public final String token_string;

        Token_Symbol(String token_string) {
            this.token_string = token_string;
        }
        /** Do method */
        abstract void do_method(RpnCalculator c, State s);
    }
    /** Symbols */
    private static final Token_Symbol[] symbols;
    /** Perfect */
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
    /** Tokens */
    private final Token[] tokens;
    private final RpnStack stack = new RpnStack();
    /** Calculated values */
    private final double[] calculatedValues;
    /** Array of timestamps for the data series */
    private final long[] timestamps;
    /** Time step */
    private final double timeStep;
    /** Sources names */
    private final List<String> sourcesNames;

    /** Rpn calculator */
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
    /** Create token */
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
    /** Calculate values */
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

    /** @return calendar field value from timestamp */
    private double getCalendarField(double timestamp, int field) {
        Calendar calendar = Util.getCalendar((long) (timestamp));
        return calendar.get(field);
    }
    /** Push a value onto the RPN stack */

    private void push(final double x) {
        stack.push(x);
    }
    /** Pop a value from the RPN stack */

    private double pop() {
        return stack.pop();
    }
    /** Examine the top value on the stack without removing it */

    private double peek() {
        return stack.peek();
    }
    /** Clear all values from the RPN calculation stack */

    private void resetStack() {
        stack.reset();
    }

    /** @return true if stack is empty */
    private boolean isStackEmpty() {
        return stack.isEmpty();
    }
    /** RpnStack class. */

    private static final class RpnStack {
        /** Maximum RPN stack depth */
        private static final int MAX_STACK_SIZE = 1000;
        /** RPN stack storage */
        private final double[] stack = new double[MAX_STACK_SIZE];
        /** Current stack position */
        private int pos = 0;
        /** Push a value onto the RPN stack */
        void push(double x) {
            if (pos >= MAX_STACK_SIZE) {
                throw new IllegalArgumentException(
                        "PUSH failed, RPN stack full [" + MAX_STACK_SIZE + "]");
            }
            stack[pos++] = x;
        }
        /** Pop a value from the RPN stack */
        double pop() {
            if (pos <= 0) {
                throw new IllegalArgumentException("POP failed, RPN stack is empty");
            }
            return stack[--pos];
        }
        /** Examine the top value on the stack without removing it */
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

        /** @return true if stack is empty */
        boolean isEmpty() {
            return pos <= 0;
        }
    }
    /** State class. */

    private final class State {
        private int token_rpi;
        private int rpi;
        Token token;
        int slot;

        TimeZone getTimeZone() {
            return RpnCalculator.this.dataProcessor.getTimeZone();
        }
    }
    /** Token class. */

    private static final class Token {
        final Token_Symbol id;
        final double number;
        final String variable;
        final double[] values;

        Token(Token_Symbol id) {
            this.id = id;
            this.values = null;
            this.variable = "";
            this.number = Double.NaN;
        }

        Token(Token_Symbol id, String variable, double[] values) {
            this.id = id;
            this.variable = variable;
            this.values = values;
            this.number = Double.NaN;
        }

        Token(Token_Symbol id, double number) {
            this.id = id;
            this.values = null;
            this.variable = "";
            this.number = number;
        }
    }
}
