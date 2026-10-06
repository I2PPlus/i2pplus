package net.i2p.crypto;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Defines a wrapper for SHA-256 operation.
 *
 * As of release 0.8.7, uses java.security.MessageDigest by default.
 * As of release 0.9.25, uses only MessageDigest.
 * GNU-Crypto gnu.crypto.hash.Sha256Standalone
 * is removed as of 0.9.28.
 * As of release 0.9.71, the digest pool is lock-free (ConcurrentLinkedQueue)
 * instead of a LinkedBlockingQueue.
 */
public final class SHA256Generator {
    /**
     *  Bound on how many digests are kept for reuse. It caps retained digests,
     *  not concurrency: an exhausted pool simply hands out a fresh digest.
     *
     *  @since 0.9.71+
     */
    private static final int POOL_SIZE = 32;

    /**
     *  Reuse pool of SHA-256 {@link MessageDigest}s.
     *  <p>
     *  A {@link ConcurrentLinkedQueue} rather than a blocking queue, which took a
     *  lock twice per hash.
     *  <p>
     *  Deliberately NOT a {@link ThreadLocal}: digests are not acquire/release
     *  scoped. {@code com.southernstorm.noise}'s {@code SymmetricState} checks one
     *  out in its constructor and holds it for the life of the Noise state, so
     *  more than one can be live on a thread at once and a thread-confined digest
     *  would silently alias them and corrupt the key derivation.
     *
     *  @since 0.9.71+
     */
    private final ConcurrentLinkedQueue<MessageDigest> _digests = new ConcurrentLinkedQueue<>();

    /**
     *  Number of elements in {@link #_digests}, maintained explicitly because
     *  {@link ConcurrentLinkedQueue#size()} is O(n). May drift by a constant
     *  factor under races, which does not matter for a reuse bound.
     *
     *  @since 0.9.71+
     */
    private final AtomicInteger _pooled = new AtomicInteger();

    /**
     *  Unused.
     *  @param context unused
     */
    public SHA256Generator(I2PAppContext context) {}

    /**
     * Instance.
     * @return the instance
     */
    public static final SHA256Generator getInstance() {
        return I2PAppContext.getGlobalContext().sha();
    }

    /**
     * Calculate the SHA-256 hash of the source and cache the result.
     *
     * @param source what to hash
     * @return hash of the source
     */
    public final Hash calculateHash(byte[] source) {
        return calculateHash(source, 0, source.length);
    }

    /**
     *  Calculate the hash and cache the result.
     *
     *  @param source what to hash
     *  @param start the starting offset
     *  @param len the length to hash
     *  @return the hash
     */
    public final Hash calculateHash(byte[] source, int start, int len) {
        MessageDigest digest = acquire();
        digest.update(source, start, len);
        byte[] rv = digest.digest();
        release(digest);
        return Hash.create(rv);
    }

    /**
     * Use this if you only need the data, not a Hash object.
     * Does not cache.
     *
     * @param out needs 32 bytes starting at outOffset
     */
    public final void calculateHash(byte[] source, int start, int len, byte[] out, int outOffset) {
        MessageDigest digest = acquire();
        digest.update(source, start, len);
        try {
            digest.digest(out, outOffset, Hash.HASH_LENGTH);
        } catch (DigestException e) {
            throw new RuntimeException(e);
        } finally {
            release(digest);
        }
    }

    /**
     *  Check out a SHA-256 MessageDigest, reset and ready to use.
     *  <p>
     *  For uses where the one-shot calculateHash() would require copying the
     *  data. Hand it back via {@link #release(MessageDigest)} when done. A
     *  MessageDigest is not thread-safe and must not be shared, so a caller
     *  holding two at once must have checked out two.
     *
     *  @return a reset MessageDigest instance
     *  @since public since 0.9.66
     */
    public MessageDigest acquire() {
        MessageDigest rv = _digests.poll();
        if (rv == null)
            rv = getDigestInstance();
        else
            _pooled.decrementAndGet();
        rv.reset();
        return rv;
    }

    /**
     *  Release a digest back to the pool, reset so that no hashed data survives
     *  in a pooled digest between uses.
     *  <p>
     *  The digest must be SHA-256, i.e. one {@link #acquire()} returned. That
     *  invariant is not re-checked: {@link MessageDigest#getAlgorithm()} is a
     *  provider-dependent string comparison on a hot path.
     *
     *  @param digest must be SHA-256
     *  @since public since 0.9.66
     */
    public void release(MessageDigest digest) {
        digest.reset();
        if (_pooled.get() >= POOL_SIZE)
            return;
        if (_digests.offer(digest))
            _pooled.incrementAndGet();
    }

    /**
     * Return a new MessageDigest from the system libs.
     *
     * @return the digest instance
     * @since 0.8.7, public since 0.8.8 for FortunaStandalone
     */
    public static MessageDigest getDigestInstance() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Hash the first argument and print the base64 result.
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: SHA256Generator 'text to hash'");
            System.exit(1);
        }
        System.out.println(net.i2p.data.Base64.encode(getInstance().calculateHash(net.i2p.data.DataHelper.getUTF8(args[0])).getData()));
    }
}
