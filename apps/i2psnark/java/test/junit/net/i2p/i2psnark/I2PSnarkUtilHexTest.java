package net.i2p.i2psnark;

import java.util.Random;

import org.junit.Test;
import static org.junit.Assert.*;

import net.i2p.data.DataHelper;

import org.klomp.snark.I2PSnarkUtil;

/**
 * Tests for {@link I2PSnarkUtil#toHex(byte[])}, the info-hash hex formatter
 * that used to build a StringBuilder with {@code Integer.toHexString} per byte
 * and now delegates to the canonical {@link DataHelper#toString(byte[])}.
 *
 * <p>The output feeds magnet URIs, .torrent file names and the HTML rows, so it
 * is pinned byte for byte against the former implementation.
 *
 * @since 0.9.71+
 */
public class I2PSnarkUtilHexTest {

    /** The former implementation: one Integer.toHexString per byte. */
    private static String refToHex(byte[] b) {
        StringBuilder buf = new StringBuilder(40);
        for (int i = 0; i < b.length; i++) {
            int bi = b[i] & 0xff;
            if (bi < 16) {
                buf.append('0');
            }
            buf.append(Integer.toHexString(bi));
        }
        return buf.toString();
    }

    @Test
    public void matchesFormerImplementationForEveryTwoByteValue() {
        for (int v = 0; v < 0x10000; v++) {
            byte[] b = {(byte)(v >> 8), (byte) v};
            assertEquals(refToHex(b), I2PSnarkUtil.toHex(b));
        }
    }

    @Test
    public void matchesFormerImplementationOverRandomInfoHashes() {
        Random rnd = new Random(20260824L);
        for (int t = 0; t < 20000; t++) {
            byte[] h = new byte[20];
            rnd.nextBytes(h);
            if (t % 5 == 0) { h[0] = 0; }
            if (t % 6 == 0) { h[19] = 0; }
            if (t % 7 == 0) { java.util.Arrays.fill(h, (byte) 0x0f); }
            if (t % 11 == 0) { java.util.Arrays.fill(h, (byte) 0); }
            assertEquals(refToHex(h), I2PSnarkUtil.toHex(h));
        }
    }

    /**
     * Pins why this delegates to DataHelper.toString rather than to
     * DataHelper.toHexString: a zero byte must still occupy two hex digits,
     * which the BigInteger-backed conversion drops.
     */
    @Test
    public void leadingZeroBytesKeepBothHexDigits() {
        byte[] hash = new byte[20];
        hash[0] = 1;
        assertEquals(40, I2PSnarkUtil.toHex(hash).length());
        assertEquals(39, DataHelper.toHexString(hash).length());
        assertEquals("000102", I2PSnarkUtil.toHex(new byte[]{0, 1, 2}));
        assertEquals("000f10", I2PSnarkUtil.toHex(new byte[]{0, 0x0f, 0x10}));
        assertEquals("00000000", I2PSnarkUtil.toHex(new byte[4]));
        assertEquals("00", I2PSnarkUtil.toHex(new byte[]{0}));
    }

    @Test
    public void lowerCaseAndFixedWidth() {
        assertEquals("", I2PSnarkUtil.toHex(new byte[0]));
        assertEquals("ff", I2PSnarkUtil.toHex(new byte[]{(byte) 0xff}));
        assertEquals(40, I2PSnarkUtil.toHex(new byte[20]).length());
        assertEquals(I2PSnarkUtil.toHex(new byte[20]).toLowerCase(java.util.Locale.US),
                     I2PSnarkUtil.toHex(new byte[20]));
    }
}
