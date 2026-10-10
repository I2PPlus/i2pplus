package net.i2p.router.web.helpers;

import java.io.Serializable;
import java.util.Comparator;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;

/**
 *  Sorts in true binary order, not Base64 string order.
 *  A-Z a-z 0-9 -~
 *
 *  @since 0.9.64
 */
class RouterInfoComparator implements Comparator<RouterInfo>, Serializable {
     private static final long serialVersionUID = 1;
     /**
      * _instance.
      */
     public static final RouterInfoComparator _instance = new RouterInfoComparator();

     /**
      * The comparator reads nothing but the two addresses it is handed, so a bare
      * instance behaves exactly like the _instance singleton.
      */
     RouterInfoComparator() {}

     /**
      * Thread safe, no state
      * @return the instance
      */
     public static RouterInfoComparator getInstance() { return _instance; }

     /**
      *  Compare two RouterInfos by their hash.
      *
      * @param l non-null
      * @param r non-null
      */
    @Override
    public int compare(RouterInfo l, RouterInfo r) {
        Hash lh = l.getIdentity().getHash();
        Hash rh = r.getIdentity().getHash();
        return HashComparator.comp(lh, rh);
    }

     /**
      *  Static comparison of two RouterInfos by their hash.
      *
      * @param l non-null
      * @param r non-null
      * @return negative, zero or positive as the first RouterInfo's identity hash sorts
      *         before, equal to, or after the second
      */
    public static int comp(RouterInfo l, RouterInfo r) {
        Hash lh = l.getIdentity().getHash();
        Hash rh = r.getIdentity().getHash();
        return HashComparator.comp(lh, rh);
    }
}
