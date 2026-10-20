/**
 * EdDSA-Java by str4d.
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa.spec;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import net.i2p.crypto.eddsa.Utils;

import org.junit.Assert;
import org.junit.Test;

public class EdDSAPrivateKeySpecTest {
    static final byte[] ZERO_SEED = Utils.hexToBytes("0000000000000000000000000000000000000000000000000000000000000000");
    // H(k) for the zero seed, clamped: byte 31 is (digest[31] & 63) | 64 = 0x56.
    // The upstream vector carried the unclamped 0x96 here, which only ever
    // "passed" because the from-H ctor used to clamp the caller's array in
    // place, mutating this very constant between tests.
    static final byte[] ZERO_H = Utils.hexToBytes(
            "5046adc1dba838867b2bbbfdd0c3423e58b57970b5267a90f57960924a87f156"
            + "0a6a85eaa642dac835424b5d7c8d637c00408c7a73da672b7f498521420b6dd3");
    static final byte[] ZERO_PK = Utils.hexToBytes("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29");

    static final EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    @Test
    public void testEdDSAPrivateKeySpecFromSeed() {
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ZERO_SEED, ed25519);
        assertThat(key.getSeed(), is(equalTo(ZERO_SEED)));
        assertThat(key.getH(), is(equalTo(ZERO_H)));
        assertThat(key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

    @Test
    public void incorrectSeedLengthThrows() {
        try {
            new EdDSAPrivateKeySpec(new byte[2], ed25519);
            Assert.fail("exception not thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("seed length is wrong", expected.getMessage());
        }
    }

    @Test
    public void testEdDSAPrivateKeySpecFromH() {
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ed25519, ZERO_H);
        assertThat(key.getSeed(), is(nullValue()));
        assertThat(key.getH(), is(equalTo(ZERO_H)));
        assertThat(key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

    @Test
    public void incorrectHashLengthThrows() {
        try {
            new EdDSAPrivateKeySpec(ed25519, new byte[2]);
            fail("exception not thrown");
        } catch (IllegalArgumentException expected) {
            assertEquals("hash length is wrong", expected.getMessage());
        }
    }

    /**
     * The seed ctor must copy the seed; mutating the caller's array afterwards
     * must not change the derived key material.
     */
    @Test
    public void seedNotRetained() {
        byte[] seed = ZERO_SEED.clone();
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(seed, ed25519);
        byte[] aBefore = key.geta();
        java.util.Arrays.fill(seed, (byte) 0xff);
        assertThat("mutating caller seed must not change stored seed", key.getSeed(), is(equalTo(ZERO_SEED)));
        assertThat("mutating caller seed must not change derived scalar", key.geta(), is(equalTo(aBefore)));
        assertThat("mutating caller seed must not change public point",
                   key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

    /**
     * The from-H ctor must never clamp the caller's array in place.
     */
    @Test
    public void fromHDoesNotMutateCallerArray() {
        byte[] h = ZERO_H.clone();
        byte[] pristine = ZERO_H.clone();
        new EdDSAPrivateKeySpec(ed25519, h);
        assertThat("from-H ctor must leave the caller's array untouched", h, is(equalTo(pristine)));
    }

    /**
     * An unclamped H must be clamped on the internal copy only, and the
     * derived key must match the clamped vector.
     */
    @Test
    public void fromHClampsCopyNotCallerArray() {
        byte[] h = ZERO_H.clone();
        h[0] |= 7;                 // un-clear the clamped low bits
        h[31] &= 0x3f;             // clear the forced bit 6
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ed25519, h);
        assertThat("clamping must happen on the copy", key.getH(), is(equalTo(ZERO_H)));
        assertThat("derived public point must match clamped vector", key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }

    /**
     * Getters hand out copies; scribbling on them must not corrupt the key.
     */
    @Test
    public void gettersReturnCopies() {
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(ZERO_SEED.clone(), ed25519);
        byte[] aBefore = key.geta();
        key.getSeed()[0] = 0x7f;
        key.getH()[0] = 0x7f;
        key.geta()[0] = 0x7f;
        assertThat(key.getSeed(), is(equalTo(ZERO_SEED)));
        assertThat(key.getH(), is(equalTo(ZERO_H)));
        assertThat(key.geta(), is(equalTo(aBefore)));
    }

    /**
     * The direct (a, aPoint, spec) ctor must copy a; mutating the argument
     * afterwards must not change the derived public point.
     */
    @Test
    public void directCtorCopiesScalar() {
        EdDSAPrivateKeySpec ref = new EdDSAPrivateKeySpec(ZERO_SEED, ed25519);
        byte[] a = ref.geta();
        EdDSAPrivateKeySpec key = new EdDSAPrivateKeySpec(a, null, ed25519);
        java.util.Arrays.fill(a, (byte) 0);
        assertThat("scribbling on the ctor argument must not change the public point",
                   key.getA().toByteArray(), is(equalTo(ZERO_PK)));
    }
}
