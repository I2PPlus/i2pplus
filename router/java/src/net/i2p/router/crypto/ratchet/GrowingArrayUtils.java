// License: Apache 2.0. See docs/LICENSES.md
package net.i2p.router.crypto.ratchet;

/**
 * Helper methods for efficient primitive array growth and insertion operations, treating array length as capacity rather than element count for optimal performance
 * All methods in this class assume that the length of an array is equivalent to its capacity and
 * NOT to number of elements in the array. The current size of the array is always passed in as a
 * parameter.
 *
 */
final class GrowingArrayUtils {

    /**
     * Appends an element to the end of the array, growing the array if there is no more room.
     *
     * @param array The array to which to append the element. This must NOT be null.
     * @param currentSize The number of elements in the array. Must be less than or equal to
     *                    array.length.
     *
     * @param element The element to append.
     * @return the array to which the element was appended. This may be different than the given
     *         array.
     */
    public static <T> T[] append(T[] array, int currentSize, T element) {
        assert currentSize <= array.length;

        if (currentSize + 1 > array.length) {
            @SuppressWarnings("unchecked")
            T[] newArray = ArrayUtils.newUnpaddedArray(
                    (Class<T>) array.getClass().getComponentType(), growSize(currentSize));
            System.arraycopy(array, 0, newArray, 0, currentSize);
            array = newArray;
        }
        array[currentSize] = element;
        return array;
    }

    /**
     * Primitive char version of {@link #append(Object[], int, Object)}.
     */
    public static char[] append(char[] array, int currentSize, char element) {
        assert currentSize <= array.length;

        if (currentSize + 1 > array.length) {
            char[] newArray = ArrayUtils.newUnpaddedCharArray(growSize(currentSize));
            System.arraycopy(array, 0, newArray, 0, currentSize);
            array = newArray;
        }
        array[currentSize] = element;
        return array;
    }

    /**
     * Inserts an element into the array at the specified index, growing the array if there is no
     * more room.
     *
     * @param array The array to which to append the element. Must NOT be null.
     * @param currentSize The number of elements in the array. Must be less than or equal to
     *                    array.length.
     *
     * @param element The element to insert.
     * @return the array to which the element was appended. This may be different than the given
     *         array.
     */
    public static <T> T[] insert(T[] array, int currentSize, int index, T element) {
        assert currentSize <= array.length;

        if (currentSize + 1 <= array.length) {
            System.arraycopy(array, index, array, index + 1, currentSize - index);
            array[index] = element;
            return array;
        }

        @SuppressWarnings("unchecked")
        T[] newArray = ArrayUtils.newUnpaddedArray((Class<T>)array.getClass().getComponentType(),
                growSize(currentSize));
        System.arraycopy(array, 0, newArray, 0, index);
        newArray[index] = element;
        System.arraycopy(array, index, newArray, index + 1, array.length - index);
        return newArray;
    }

    /**
     * Primitive char version of {@link #insert(Object[], int, int, Object)}.
     */
    public static char[] insert(char[] array, int currentSize, int index, char element) {
        assert currentSize <= array.length;

        if (currentSize + 1 <= array.length) {
            System.arraycopy(array, index, array, index + 1, currentSize - index);
            array[index] = element;
            return array;
        }

        char[] newArray = ArrayUtils.newUnpaddedCharArray(growSize(currentSize));
        System.arraycopy(array, 0, newArray, 0, index);
        newArray[index] = element;
        System.arraycopy(array, index, newArray, index + 1, array.length - index);
        return newArray;
    }

    /**
     * Given the current size of an array, returns an ideal size to which the array should grow.
     * This is typically double the given size, but should not be relied upon to do so in the
     * future.
     */
    public static int growSize(int currentSize) {
        return currentSize <= 4 ? 8 : currentSize * 2;
    }

    // Uninstantiable
    private GrowingArrayUtils() {}
}
