/* RedirectQuery - Redirect query string validation
   Copyright (C) 2024 The I2P+ Project
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark.web;

/**
 *  Validation for the numeric query strings used by the post/redirect/get
 *  pattern in the web UI.
 *
 *  <p>A redirect query string is later handed to
 *  {@code response.sendRedirect()}; it must therefore only ever contain the
 *  known numeric parameters with integer values. Crafted input containing a
 *  scheme, host, or embedded HTML entity must be rejected so the redirect
 *  target can never be an external URL.
 *
 *  @since 0.9.71+
 */
public final class RedirectQuery {

    /** @since 0.9.71+ */
    private RedirectQuery() {}

    /**
     *  Validate a redirect query string. It must be null/empty, or start with
     *  '?' and contain only the {@code p}, {@code sort}, {@code st} and
     *  {@code search} parameters with integer values separated by '&amp;'.
     *  Anything else - a scheme, a host, a different parameter, or an
     *  embedded entity like "&amp;amp;" - is rejected.
     *
     *  @param qs the query string, may be null or empty
     *  @return true if the query string is safe to redirect to
     */
    public static boolean isSafeRedirectQuery(String qs) {
        if (qs == null || qs.isEmpty()) {return true;}
        if (!qs.startsWith("?")) {return false;}
        String[] parts = qs.substring(1).split("&");
        for (String part : parts) {
            if (part.isEmpty()) {continue;}
            int eq = part.indexOf('=');
            if (eq <= 0) {return false;}
            String name = part.substring(0, eq);
            if (!(name.equals("p")
                    || name.equals("sort")
                    || name.equals("st")
                    || name.equals("search"))) {
                return false;
            }
            if (!isValidNumeric(part.substring(eq + 1))) {return false;}
        }
        return true;
    }

    /**
     *  Test whether the given string is exactly a signed 32-bit integer,
     *  without throwing. Unlike a naive {@code matches()} + {@code parseInt()}
     *  combination, an extremely long digit string cannot escape as an
     *  uncaught {@link NumberFormatException}.
     *
     *  @param str the string to test, may be null
     *  @return true if the string parses cleanly to an int
     */
    public static boolean isValidNumeric(String str) {
        if (str == null || str.isEmpty()) {return false;}
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }
}