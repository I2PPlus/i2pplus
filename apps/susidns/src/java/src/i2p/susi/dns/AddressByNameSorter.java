// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.dns;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Comparator for sorting AddressBean by name alphabetically.
 */
public class AddressByNameSorter implements Comparator<AddressBean>, Serializable {
    /**
     * Default constructor.
     */
    public AddressByNameSorter() {
        super();
    }

    /**
     * Compare two AddressBeans alphabetically by display name.
     *
     * @param a the first AddressBean
     * @param b the second AddressBean
     * @return comparison of display names (case-insensitive)
     */
    public int compare(AddressBean a, AddressBean b) {
        return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
    }
}
