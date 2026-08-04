package net.i2p.kademlia;

import static org.junit.Assert.*;

import net.i2p.data.Hash;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *  Tests for XORComparator, the XOR-distance ordering used by the DHT.
 *  Covers exact distance computation, ordering invariants, and the
 *  tie-break on identical hashes.
 *
 *  @since 0.9.10
 */
public class XORComparatorTest {

    /** hash with all bytes zero */
    private static Hash h(byte... b) {
        byte[] data = new byte[Hash.HASH_LENGTH];
        System.arraycopy(b, 0, data, 0, b.length);
        return Hash.create(data);
    }

    @Test
    public void testCompareBaseItselfFirst() {
        Hash base = h((byte) 0x80);
        List<Hash> hashes = new ArrayList<>();
        hashes.add(h((byte) 0x01));
        hashes.add(base);
        hashes.add(h((byte) 0x7f));
        hashes.add(h((byte) 0xff));
        Collections.sort(hashes, new XORComparator<Hash>(base));
        // the base hash itself must come first (distance 0)
        assertEquals(base, hashes.get(0));
    }

    @Test
    public void testCompareOrdersByXorDistance() {
        // base = 0x80, candidates: 0x00 (dist 0x80), 0x80 (dist 0), 0xc0 (dist 0x40)
        Hash base = h((byte) 0x80);
        Hash far = h((byte) 0x00);
        Hash self = h((byte) 0x80);
        Hash near = h((byte) 0xc0);
        assertTrue(new XORComparator<Hash>(base).compare(self, far) < 0);
        assertTrue(new XORComparator<Hash>(base).compare(near, far) < 0);
        assertTrue(new XORComparator<Hash>(base).compare(far, near) > 0);
    }

    @Test
    public void testCompareHighOrderBitsDominate() {
        // A differing high-order bit must dominate any number of low-order bits.
        Hash base = h((byte) 0x00);
        // diff in bit 0 (byte 0) only -> distance 0x01
        Hash high = h((byte) 0x01);
        // same high byte as base, diff only in last byte -> distance 0x01 in last byte
        byte[] low = new byte[Hash.HASH_LENGTH];
        low[Hash.HASH_LENGTH - 1] = 0x01;
        Hash lowDist = Hash.create(low);
        // lowDist (0x00...01) is closer than high (0x01...)
        assertTrue(new XORComparator<Hash>(base).compare(high, lowDist) > 0);
        assertTrue(new XORComparator<Hash>(base).compare(lowDist, high) < 0);
    }

    @Test
    public void testCompareEqualHashes() {
        Hash base = h((byte) 0x42);
        Hash a = h((byte) 0x42, (byte) 0x01);
        Hash b = h((byte) 0x42, (byte) 0x01);
        assertEquals(0, new XORComparator<Hash>(base).compare(a, b));
    }

    @Test
    public void testSortingIsStableAndDeterministic() {
        Hash base = h((byte) 0x00);
        List<Hash> hashes = new ArrayList<>();
        byte[] data;
        for (int i = 1; i <= 100; i++) {
            data = new byte[Hash.HASH_LENGTH];
            data[Hash.HASH_LENGTH - 1] = (byte) i;
            hashes.add(Hash.create(data));
        }
        Collections.shuffle(hashes);
        List<Hash> sorted1 = new ArrayList<>(hashes);
        Collections.sort(sorted1, new XORComparator<Hash>(base));
        Collections.shuffle(hashes);
        List<Hash> sorted2 = new ArrayList<>(hashes);
        Collections.sort(sorted2, new XORComparator<Hash>(base));
        assertEquals(sorted1, sorted2);
        // strictly increasing XOR distance from base
        byte[] prev = null;
        for (Hash hh : sorted1) {
            if (prev != null) {
                assertTrue(compareDistance(base, prev, hh.getData()) < 0);
            }
            prev = hh.getData();
        }
    }

    /** reference XOR distance comparison for verification */
    private static int compareDistance(Hash base, byte[] lhs, byte[] rhs) {
        for (int i = 0; i < Hash.HASH_LENGTH; i++) {
            int ld = (lhs[i] ^ base.getData()[i]) & 0xff;
            int rd = (rhs[i] ^ base.getData()[i]) & 0xff;
            if (ld < rd) return -1;
            if (ld > rd) return 1;
        }
        return 0;
    }
}
