package net.i2p.router.sybil;

import java.math.BigInteger;
import net.i2p.data.router.RouterInfo;

/**
 *  A pair of routers and the distance between them.
 *
 *  @since 0.9.38 moved from SybilRenderer
 */
public class Pair implements Comparable<Pair> {
    public final RouterInfo r1, r2;
    public final BigInteger dist;

    public Pair(RouterInfo ri1, RouterInfo ri2, BigInteger distance) {
        r1 = ri1; r2 = ri2; dist = distance;
    }

    public int compareTo(Pair p) {
        return this.dist.compareTo(p.dist);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pair)) return false;
        Pair p = (Pair) o;
        return r1.equals(p.r1) && r2.equals(p.r2) && dist.equals(p.dist);
    }

    @Override
    public int hashCode() {
        int result = r1.hashCode();
        result = 31 * result + r2.hashCode();
        result = 31 * result + dist.hashCode();
        return result;
    }
}

