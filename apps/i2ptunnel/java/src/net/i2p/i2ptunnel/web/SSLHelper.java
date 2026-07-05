package net.i2p.i2ptunnel.web;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for SSL configuration and certificate management in the tunnel web interface
 *
 * @since 0.9.35
 */
public class SSLHelper {

    /**
     * Parses a space-separated argument string, respecting single and double quotes.
     * Adapted from LoadClientAppsJob.
     *
     * @param args the argument string to parse
     * @return non-null list of parsed arguments
     */
    public static List<String> parseArgs(String args) {
        List<String> argList = new ArrayList<>(4);
        StringBuilder buf = new StringBuilder(32);
        boolean isQuoted = false;
        for (int j = 0; j < args.length(); j++) {
            char c = args.charAt(j);
            switch (c) {
                case '\'':
                case '"':
                    if (isQuoted) {
                        String str = buf.toString().trim();
                        if (str.length() > 0)
                            argList.add(str);
                        buf.setLength(0);
                    }
                    isQuoted = !isQuoted;
                    break;
                case ' ':
                case '\t':
                    // whitespace - if we're in a quoted section, keep this as part of the quote,
                    // otherwise use it as a delim
                    if (isQuoted) {
                        buf.append(c);
                    } else {
                        String str = buf.toString().trim();
                        if (str.length() > 0)
                            argList.add(str);
                        buf.setLength(0);
                    }
                    break;
                default:
                    buf.append(c);
                    break;
            }
        }
        if (buf.length() > 0) {
            String str = buf.toString().trim();
            if (str.length() > 0)
                argList.add(str);
        }
        return argList;
    }
}
