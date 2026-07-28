package edu.internet2.ndt;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Class that defines utility methods used by the NDT code
 */
public class NDTUtils {
    /** default constructor */

    /**
     * Utility method to print double value up to the hundredth place.
     *
     * @param paramDblToFormat Double numbers to format
     * @return String value of double number
     */
    public static String prtdbl(double paramDblToFormat) {
        String str = null;
        int i;

        if (paramDblToFormat == 0) {return ("0");}
        str = Double.toString(paramDblToFormat);
        i = str.indexOf(".");
        i = i + 3;
        if (i > str.length()) {i = i - 1;}
        if (i > str.length()) {i = i - 1;}
        return (str.substring(0, i));
    }

    /**
     * Utility method to check if the given string is empty ("") or null.
     *
     * @param str String to check
     * @return true is the given string is empty; otherwise false
     */
    public static boolean isEmpty(String str) {return str == null || str.length() == 0;}

    /**
     * Utility method to check if the given string is not empty ("") or null.
     *
     * @param str String to check
     * @return true is the given string is not empty; otherwise false
     */
    public static boolean isNotEmpty(String str) {return !isEmpty(str);}

    /**
     * Utility method to encode the given string using UTF-8 encoding
     *
     * @param str String to encode
     * @return encoded string with replacing '+' to '%20'
     */
    public static String urlEncode(String str) {
        try {return URLEncoder.encode(str, "utf-8").replace("+", "%20");}
        catch (UnsupportedEncodingException e) {throw new IllegalArgumentException(e);}
    }

}
