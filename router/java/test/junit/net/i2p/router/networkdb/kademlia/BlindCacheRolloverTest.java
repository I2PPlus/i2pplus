package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.RouterContext;
import net.i2p.stat.StatManager;

import org.junit.Test;

/**
 * The midnight pass over the blind cache keeps every entry it can re-key and
 * drops the ones it cannot, instead of stopping at the first bad one.
 * <p>
 * A blinded key is re-derived from the routing key modifier, and the derivation
 * answers a malformed key or an unsupported blinded signature type with an
 * unchecked {@code IllegalArgumentException}. Left uncaught it ended the pass,
 * so every entry after the bad one lost its reverse-cache mapping, and it
 * reached the routing key job as an exception rather than a requeue. Upstream
 * catches it per entry in 6ad05c0ebc.
 *
 * @since 0.9.72+
 */
public class BlindCacheRolloverTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the class carries no per-test state, only the key length the fixtures
     * are sized against.
     *
     * @since 0.9.72+
     */
    public BlindCacheRolloverTest() {}

    /** Bytes in an Ed25519 signing key, the only type the reverse cache serves. */
    private static final int KEY_LEN = 32;

    /** The context the fixtures' blind data is built in, for its clock. */
    private static final I2PAppContext APP = I2PAppContext.getGlobalContext();

    /**
     * A context the blind cache can build its counter on. The cache registers
     * the counter it writes through the context's stat manager, so a bare mock,
     * whose statManager() answers null, cannot construct one.
     *
     * @return a mocked router context carrying a real stat manager
     */
    private static RouterContext contextWithStats() {
        RouterContext ctx = mock(RouterContext.class);
        when(ctx.statManager()).thenReturn(new StatManager(APP));
        return ctx;
    }

    /**
     * Blind data whose blinded key can be armed to fail, so one entry in the
     * cache can be made to throw on the midnight pass while its neighbours stay
     * good.
     *
     * @since 0.9.72+
     */
    private static final class FlakyBlindData extends BlindData {

        /** The blinded key returned until the entry is armed to fail. */
        private final SigningPublicKey _blind;
        /** True once the midnight pass should fail on this entry. */
        private boolean _fail;

        /**
         * @param ctx the context the blind data was built in
         * @param clear the unblinded signing key, the cache's forward key
         * @param blind the blinded signing key, the cache's reverse key
         */
        FlakyBlindData(I2PAppContext ctx, SigningPublicKey clear, SigningPublicKey blind) {
            super(ctx, clear, SigType.RedDSA_SHA512_Ed25519, null);
            _blind = blind;
        }

        /**
         * The blinded key, or the failure a malformed blinded key produces.
         *
         * @return the blinded key, unless this entry is armed to fail
         * @throws IllegalArgumentException when armed, as a blinded key that
         *                                  cannot be derived does
         */
        @Override
        public SigningPublicKey getBlindedPubKey() {
            if (_fail) {
                throw new IllegalArgumentException("forced, as a blinded key that cannot be derived does");
            }
            return _blind;
        }
    }

    /**
     * One cache entry and the two keys it is reachable by. The keys are opaque
     * bytes - the midnight pass uses them only as map keys, and the blinded one
     * is what the entry hands back, armed or not.
     *
     * @since 0.9.72+
     */
    private static final class Fixture {

        /** The entry put in the cache. */
        final FlakyBlindData _bd;
        /** The unblinded key the forward cache is keyed by. */
        final SigningPublicKey _clear;
        /** The blinded key the reverse cache is keyed by. */
        final SigningPublicKey _blind;

        /**
         * @param seed a byte distinct per entry, so no two fixtures share a key
         */
        Fixture(byte seed) {
            _clear = signingKey(seed);
            _blind = signingKey((byte) (seed + 100));
            _bd = new FlakyBlindData(APP, _clear, _blind);
        }

        /**
         * A signing key of the type the blind cache serves.
         *
         * @param fill the byte every element of the key data is set to
         * @return a 32-byte Ed25519 signing key
         */
        private static SigningPublicKey signingKey(byte fill) {
            byte[] data = new byte[KEY_LEN];
            Arrays.fill(data, fill);
            return new SigningPublicKey(SigType.RedDSA_SHA512_Ed25519, data);
        }
    }

    /**
     * An entry whose blinded key cannot be re-derived is dropped, and the pass
     * goes on to re-key everything after it. At HEAD the exception ended the
     * pass, leaving the reverse cache holding only the entries it had reached.
     */
    @Test
    public void rolloverDropsTheEntryThatCannotBeReKeyed() {
        RouterContext ctx = contextWithStats();
        BlindCache cache = new BlindCache(ctx);
        Fixture first = new Fixture((byte) 1);
        Fixture bad = new Fixture((byte) 2);
        Fixture last = new Fixture((byte) 3);
        cache.addToCache(first._bd);
        cache.addToCache(bad._bd);
        cache.addToCache(last._bd);
        bad._bd._fail = true;

        cache.rollover();

        assertNull("the un-rekeyable entry is still in the forward cache", cache.getData(bad._clear));
        assertNull("the un-rekeyable entry is still in the reverse cache", cache.getReverseData(bad._blind));
        assertNotNull("the entry before it was dropped from the forward cache", cache.getData(first._clear));
        assertNotNull("the entry after it was dropped from the forward cache", cache.getData(last._clear));
        assertNotNull("the entry before it lost its reverse mapping", cache.getReverseData(first._blind));
        assertNotNull("the entry after it lost its reverse mapping", cache.getReverseData(last._blind));
    }

    /**
     * When every entry re-keys, the pass leaves the cache intact - the iterator
     * rewrite must not drop anything on the normal path.
     */
    @Test
    public void rolloverKeepsEveryEntryItCanReKey() {
        RouterContext ctx = contextWithStats();
        BlindCache cache = new BlindCache(ctx);
        Fixture first = new Fixture((byte) 11);
        Fixture second = new Fixture((byte) 12);
        cache.addToCache(first._bd);
        cache.addToCache(second._bd);

        cache.rollover();

        assertNotNull(cache.getData(first._clear));
        assertNotNull(cache.getData(second._clear));
        assertNotNull(cache.getReverseData(first._blind));
        assertNotNull(cache.getReverseData(second._blind));
    }

    /**
     * A rollover with nothing cached is a no-op, and in particular it does not
     * fail on an empty iterator.
     */
    @Test
    public void rolloverOnAnEmptyCacheDoesNothing() {
        RouterContext ctx = contextWithStats();
        BlindCache cache = new BlindCache(ctx);
        cache.rollover();
        Fixture absent = new Fixture((byte) 21);
        assertNull(cache.getReverseData(absent._blind));
    }
}
