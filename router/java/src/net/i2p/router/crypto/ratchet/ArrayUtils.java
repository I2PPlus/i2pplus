// License: Apache 2.0. See docs/LICENSES.md
package net.i2p.router.crypto.ratchet;

/**
 * Utility methods for creating unpadded arrays with optimal growth sizing
 */
class ArrayUtils {

    private ArrayUtils() { /* cannot be instantiated */ }

    public static char[] newUnpaddedCharArray(int minLen) {
        return new char[minLen];
    }

    public static Object[] newUnpaddedObjectArray(int minLen) {
        return new Object[minLen];
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] newUnpaddedArray(Class<T> _clazz, int minLen) {
        return (T[]) java.lang.reflect.Array.newInstance(_clazz, minLen);
    }
}
