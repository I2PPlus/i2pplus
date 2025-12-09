/******************************************************************
 *
 *	CyberUtil for Java
 *
 *	Copyright (C) Satoshi Konno 2002-2003
 *
 *	File: FileUtil.java
 *
 *	Revision:
 *
 *	01/12/03
 *		- first revision.
 *
 ******************************************************************/

package org.cybergarage.util;

/**
 * Utility class for string manipulation and conversion operations.
 * This class provides static methods for string validation, type conversion,
 * character searching, and custom trimming operations.
 */
public final class StringUtil {
    /**
     * Checks if a string contains valid data (not null and not empty).
     * 
     * @param value the string to check
     * @return true if the string is not null and has length > 0, false otherwise
     */
    public static final boolean hasData(String value) {
        if (value == null) return false;
        if (value.length() <= 0) return false;
        return true;
    }

    /**
     * Converts a string to an integer value.
     * 
     * @param value the string to convert
     * @return the integer value, or 0 if conversion fails
     */
    public static final int toInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            Debug.warning(e);
        }
        return 0;
    }

    /**
     * Converts a string to a long value.
     * 
     * @param value the string to convert
     * @return the long value, or 0 if conversion fails
     */
    public static final long toLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            Debug.warning(e);
        }
        return 0;
    }

    /**
     * Generic character finding method with customizable search parameters.
     * 
     * @param str the string to search in
     * @param chars the characters to search for
     * @param startIdx the starting index
     * @param endIdx the ending index
     * @param offset the search direction (1 for forward, -1 for backward)
     * @param isEqual true to find matching characters, false to find non-matching
     * @return the index of the found character, or -1 if not found
     */
    public static final int findOf(
            String str, String chars, int startIdx, int endIdx, int offset, boolean isEqual) {
        if (offset == 0) return -1;
        int charCnt = chars.length();
        int idx = startIdx;
        while (true) {
            if (0 < offset) {
                if (endIdx < idx) break;
            } else {
                if (idx < endIdx) break;
            }
            char strc = str.charAt(idx);
            int noEqualCnt = 0;
            for (int n = 0; n < charCnt; n++) {
                char charc = chars.charAt(n);
                if (isEqual == true) {
                    if (strc == charc) return idx;
                } else {
                    if (strc != charc) noEqualCnt++;
                    if (noEqualCnt == charCnt) return idx;
                }
            }
            idx += offset;
        }
        return -1;
    }

    /**
     * Finds the first occurrence of any character from the specified set.
     * 
     * @param str   string to search in
     * @param chars characters to search for
     * @return index of first matching character, or -1 if not found
     */
    public static final int findFirstOf(String str, String chars) {
        return findOf(str, chars, 0, (str.length() - 1), 1, true);
    }

    /**
     * Finds the first character that is NOT in the specified character set.
     * 
     * @param str   string to search in
     * @param chars characters to exclude from search
     * @return index of first non-matching character, or -1 if not found
     */
    public static final int findFirstNotOf(String str, String chars) {
        return findOf(str, chars, 0, (str.length() - 1), 1, false);
    }

    /**
     * Finds the last occurrence of any character from the specified set.
     * 
     * @param str   string to search in
     * @param chars characters to search for
     * @return index of last matching character, or -1 if not found
     */
    public static final int findLastOf(String str, String chars) {
        return findOf(str, chars, (str.length() - 1), 0, -1, true);
    }

    /**
     * Finds the last character that is NOT in the specified character set.
     * 
     * @param str   string to search in
     * @param chars characters to exclude from search
     * @return index of last non-matching character, or -1 if not found
     */
    public static final int findLastNotOf(String str, String chars) {
        return findOf(str, chars, (str.length() - 1), 0, -1, false);
    }

    /**
     * Trims specified characters from both ends of a string.
     * 
     * @param trimStr   string to trim
     * @param trimChars characters to remove from both ends
     * @return trimmed string
     */
    public static final String trim(String trimStr, String trimChars) {
        int spIdx = findFirstNotOf(trimStr, trimChars);
        if (spIdx < 0) {
            String buf = trimStr;
            return buf;
        }
        String trimStr2 = trimStr.substring(spIdx, trimStr.length());
        spIdx = findLastNotOf(trimStr2, trimChars);
        if (spIdx < 0) {
            String buf = trimStr2;
            return buf;
        }
        String buf = trimStr2.substring(0, spIdx + 1);
        return buf;
    }
}
