/*
 * This file is part of SusDNS project for I2P
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.io.Serializable;
import java.util.Comparator;

/**
 *  Newest first, then alphabetical
 *  @since 0.9.66
 */
public class AddressByDateSorter implements Comparator<AddressBean>, Serializable {
    public int compare(AddressBean a, AddressBean b) {
        String ad = a.getProp("a");
        String bd = b.getProp("a");
        long al;
        long bl;
        if (ad.length() > 0) {al = Long.parseLong(ad);}
        else {al = 0;}
        if (bd.length() > 0) {bl = Long.parseLong(bd);}
        else {bl = 0;}
        if (al < bl) {return 1;}
        if (al > bl) {return -1;}
        return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
    }
}
