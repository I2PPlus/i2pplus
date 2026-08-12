// License: Apache 2.0. See docs/LICENSES.md
package net.i2p.router.crypto.ratchet;

/**
 * Utility methods for creating unpadded arrays with optimal growth sizing
 */
class ArrayUtils {

    private ArrayUtils() { /* cannot be instantiated */ }

    /**
     * Return a new char array of the specified minimum length.
     *
     * @param minLen the minimum length
     * @return the new array
     */
    public static char[] newUnpaddedCharArray(int minLen) {
        return new char[minLen];
    }

    /**
     * Return a new Object array of the specified minimum length.
     *
     * @param minLen the minimum length
     * @return the new array
     */
    public static Object[] newUnpaddedObjectArray(int minLen) {
        return new Object[minLen];
    }

    /**
     * Return a new typed array of the specified minimum length.
     *
     * @param <T> the component type
     * @param clazz the class of the component type
     * @param minLen the minimum length
     * @return the new array
     */
    @SuppressWarnings("unchecked")
    public static <T> T[] newUnpaddedArray(Class<T> clazz, int minLen) {
        return (T[]) java.lang.reflect.Array.newInstance(clazz, minLen);
    }
}
