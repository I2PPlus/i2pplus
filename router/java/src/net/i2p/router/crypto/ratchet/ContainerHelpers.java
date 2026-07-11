// License: Apache 2.0. See docs/LICENSES.md
package net.i2p.router.crypto.ratchet;

/**
 * Binary search utilities for array operations without argument validation overhead
 */
class ContainerHelpers {

    private ContainerHelpers() {}

    // This is Arrays.binarySearch(), but doesn't do any argument validation.
    static int binarySearch(char[] array, int size, char value) {
        int lo = 0;
        int hi = size - 1;

        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final char midVal = array[mid];

            if (midVal < value) {
                lo = mid + 1;
            } else if (midVal > value) {
                hi = mid - 1;
            } else {
                return mid;  // value found
            }
        }
        return ~lo;  // value not present
    }
}
