/*
 * This file is part of SusDNS project for I2P
 * Created on Sep 02, 2005
 * $Revision: 1.2 $
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

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
    }

    public int compare(AddressBean a, AddressBean b) {
        return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
    }
}
