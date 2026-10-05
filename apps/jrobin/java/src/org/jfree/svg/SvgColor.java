package org.jfree.svg;

import java.awt.Color;

/**
 * Parses the CSS colour forms allowed for graph theme variables.
 *
 * <p>Graph colours are moving from values hard-coded into the SVG to CSS custom
 * properties, so a theme can set them by name. That means the server has to read
 * whatever the theme author wrote and turn it into a {@link Color}, and it has to
 * accept the shorthand forms people actually write.
 *
 * <p>Supported, all with an optional alpha:
 * <ul>
 *   <li>{@code #rgb} and {@code #rgba} — {@code #f0a}, {@code #ee9d}</li>
 *   <li>{@code #rrggbb} and {@code #rrggbbaa} — {@code #00ff99}, {@code #00ff9980}</li>
 *   <li>{@code rgb(r,g,b)} and {@code rgba(r,g,b,a)} — {@code rgb(0,255,153)},
 *       {@code rgba(0,255,153,.5)}</li>
 * </ul>
 *
 * <p>Alpha is 0–255 as written in hex, or 0–1 as written in {@code rgb}/{@code rgba},
 * matching CSS. Shorthand digits are <i>doubled</i>, so {@code #ee9d} is
 * {@code #eeee99dd} — the CSS rule, not "expand each digit once".
 *
 * <p>Anything unrecognised returns {@code null} rather than a wrong colour: a theme
 * with a typo in one variable should fall back to the built-in value for that one
 * series, not silently render every plot in the default colour.
 *
 * @since 0.9.71+
 */
public final class SvgColor {

    private SvgColor() {}

    /**
     * Parse a CSS colour.
     *
     * @param value the text to parse, may be null
     * @return the colour, or null if {@code value} is not a form this understands
     */
    public static Color parse(String value) {
        if (value == null) {return null;}
        String text = value.trim();
        if (text.isEmpty()) {return null;}
        if (text.charAt(0) == '#') {
            return parseHex(text.substring(1));
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("rgba(") || lower.startsWith("rgb(")) {
            return parseFunctional(lower);
        }
        return null;
    }

    /** @param digits the text after '#', 3, 4, 6 or 8 characters */
    private static Color parseHex(String digits) {
        int len = digits.length();
        if (len != 3 && len != 4 && len != 6 && len != 8) {return null;}
        int[] component = new int[4];
        if (len <= 4) {
            // Shorthand: each digit is doubled, so 'e' means 0xee.
            for (int i = 0; i < len; i++) {
                int v = hexValue(digits.charAt(i));
                if (v < 0) {return null;}
                component[i] = v * 16 + v;
            }
            if (len == 3) {component[3] = 255;}
        } else {
            for (int i = 0; i < len / 2; i++) {
                int hi = hexValue(digits.charAt(i * 2));
                int lo = hexValue(digits.charAt(i * 2 + 1));
                if (hi < 0 || lo < 0) {return null;}
                component[i] = hi * 16 + lo;
            }
            if (len == 6) {component[3] = 255;}
        }
        return new Color(component[0], component[1], component[2], component[3]);
    }

    private static Color parseFunctional(String text) {
        boolean hasAlpha = text.startsWith("rgba(");
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open) {return null;}
        String[] parts = text.substring(open + 1, close).split(",");
        int want = hasAlpha ? 4 : 3;
        if (parts.length != want) {return null;}
        int[] c = new int[4];
        c[3] = 255;
        for (int i = 0; i < 3; i++) {
            Integer v = parseChannel(parts[i]);
            if (v == null) {return null;}
            c[i] = v;
        }
        if (hasAlpha) {
            // CSS alpha is 0..1; percent form is also accepted.
            String a = parts[3].trim();
            Integer alpha;
            if (a.endsWith("%")) {
                Float pct = parseFloat(a.substring(0, a.length() - 1));
                alpha = pct == null ? null
                                    : Math.round(pct * 255f / 100f);
            } else {
                Float f = parseFloat(a);
                alpha = f == null ? null : Math.round(clamp01(f) * 255f);
            }
            if (alpha == null) {return null;}
            c[3] = alpha;
        }
        return new Color(c[0], c[1], c[2], c[3]);
    }

    private static Integer parseChannel(String part) {
        String s = part.trim();
        if (s.endsWith("%")) {
            Float pct = parseFloat(s.substring(0, s.length() - 1));
            return pct == null ? null : Math.round(clamp01(pct / 100f) * 255f);
        }
        Float f = parseFloat(s);
        return f == null ? null : Math.round(clamp01(f / 255f) * 255f);
    }

    /**
     * Parse a decimal without {@code Float.parseFloat}, so a stray character yields
     * null instead of an exception propagating out of a theme lookup.
     */
    private static Float parseFloat(String s) {
        String t = s.trim();
        if (t.isEmpty()) {return null;}
        int i = 0;
        if (t.charAt(0) == '+' || t.charAt(0) == '-') {i++;}
        boolean digits = false;
        boolean dot = false;
        for (; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {digits = true;}
            else if (c == '.' && !dot) {dot = true;}
            else {return null;}
        }
        if (!digits) {return null;}
        try {
            return Float.valueOf(t);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private static float clamp01(float f) {
        if (f < 0f) {return 0f;}
        return f > 1f ? 1f : f;
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {return c - '0';}
        if (c >= 'a' && c <= 'f') {return c - 'a' + 10;}
        if (c >= 'A' && c <= 'F') {return c - 'A' + 10;}
        return -1;
    }

    /**
     * Render a colour the way a theme author would write it: hex, shortened to the
     * smallest form that is still exact, with alpha appended when not opaque.
     *
     * @param color the colour to render, may be null
     * @return a CSS colour string, or null when {@code color} is null
     */
    public static String format(Color color) {
        if (color == null) {return null;}
        StringBuilder sb = new StringBuilder(9).append('#');
        int r = color.getRed(), g = color.getGreen(), b = color.getBlue(), a = color.getAlpha();
        if (a != 255) {
            sb.append(hex2(r)).append(hex2(g)).append(hex2(b)).append(hex2(a));
            return sb.toString();
        }
        // Each channel's digits repeat, so #00ff99 can be written #0f9.
        if (isShort(r) && isShort(g) && isShort(b)) {
            return sb.append(hexDigit(r >> 4)).append(hexDigit(g >> 4)).append(hexDigit(b >> 4))
                     .toString();
        }
        return sb.append(hex2(r)).append(hex2(g)).append(hex2(b)).toString();
    }

    private static boolean isShort(int v) {
        return (v >> 4) == (v & 0x0f);
    }

    private static String hex2(int v) {
        return new String(new char[] { hexDigit(v >> 4), hexDigit(v & 0x0f) });
    }

    private static char hexDigit(int v) {
        return (char) (v < 10 ? '0' + v : 'a' + v - 10);
    }
}