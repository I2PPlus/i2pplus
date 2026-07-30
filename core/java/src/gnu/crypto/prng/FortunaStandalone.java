package gnu.crypto.prng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import net.i2p.crypto.CryptixAESKeyCache;
import net.i2p.crypto.CryptixRijndael_Algorithm;
import net.i2p.crypto.SHA256Generator;

/**
 * The Fortuna continuously-seeded pseudo-random number generator. This
 * generator is composed of two major pieces: the entropy accumulator
 * and the generator function. The former takes in random bits and
 * incorporates them into the generator's state. The latter takes this
 * base entropy and generates pseudo-random bits from it.
 *
 * <p>There are some things users of this class <em>must</em> be aware of:
 *
 * <dl>
 * <dt>Adding Random Data</dt>
 * <dd>This class does not do any polling of random sources, but rather
 * provides an interface for adding random events. Applications that use
 * this code <em>must</em> provide this mechanism. We use this design
 * because an application writer who knows the system he is targeting
 * is in a better position to judge what random data is available.</dd>
 *
 * <dt>Storing the Seed</dt>
 * <dd>This class implements {@link Serializable} in such a way that it
 * writes a 64 byte seed to the stream, and reads it back again when being
 * deserialized. This is the extent of seed file management, however, and
 * those using this class are encouraged to think deeply about when, how
 * often, and where to store the seed.</dd>
 * </dl>
 *
 * <p><b>References:</b></p>
 *
 * <ul>
 * <li>Niels Ferguson and Bruce Schneier, <i>Practical Cryptography</i>,
 * pp. 155--184. Wiley Publishing, Indianapolis. (2003 Niels Ferguson and
 * Bruce Schneier). ISBN 0-471-22357-3.</li>
 * </ul>
 *
 * Modified by jrandom for I2P to use a standalone gnu-crypto SHA256, Cryptix's AES,
 * to strip out some unnecessary dependencies and increase the buffer size.
 * Renamed from Fortuna to FortunaStandalone so it doesn't conflict with the
 * gnu-crypto implementation, which has been imported into GNU/classpath
 *
 * NOTE: As of 0.8.8, uses the java.security.MessageDigest instead of GNU Sha256Standalone
 */
@SuppressWarnings("java:S2975")
public class FortunaStandalone extends BasePRNGStandalone implements Serializable {
    private static final long serialVersionUID = 0xFACADE;
    private static final int SEED_FILE_SIZE = 64;
    /** number of entropy pools */
    static final int NUM_POOLS = 32;
    /** minimum bytes in pool 0 before reseed */
    static final int MIN_POOL_SIZE = 64;
    /** the underlying PRNG generator */
    protected final IRandomStandalone generator;
    /** null if using DevRandom */
    protected final MessageDigest[] pools;
    /** timestamp of last reseed */
    protected long lastReseed;
    /** current pool index for entropy distribution */
    private int pool;
    /** bytes added to pool 0 */
    protected int pool0Count;
    /** number of reseeds performed */
    protected int reseedCount;
    /** property name for the seed attribute */
    public static final String SEED = "gnu.crypto.prng.fortuna.seed";

    /** With DevRandom disabled. */
    public FortunaStandalone() {this(false);}

    /** @param useDevRandom if true, use DevRandom instead of the Fortuna Generator */
    public FortunaStandalone(boolean useDevRandom) {
        super("Fortuna i2p");
        generator = useDevRandom ? new DevRandom() : new Generator();
        if (useDevRandom) {pools = null;}
        else {
            pools = new MessageDigest[NUM_POOLS];
            for (int i = 0; i < NUM_POOLS; i++) {
                pools[i] = SHA256Generator.getDigestInstance();
            }
        }
    }

  /** Unused, see AsyncFortunaStandalone. @param val the seed data (unused) */
    public void seed(byte[] val) {
        throw new UnsupportedOperationException("use override");
    }

    /** Set up the PRNG instance. */
    public void setup(Map<String, byte[]> attributes) {
        lastReseed = 0;
        reseedCount = 0;
        pool = 0;
        pool0Count = 0;
        generator.init(attributes);
    }

    /** Unused, see AsyncFortunaStandalone */
    public void fillBlock() {
        throw new UnsupportedOperationException("use override");
    }

    /**
     * Return a copy of this object.
     */
    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /** Add a random byte to the entropy pool. */
    @Override
    public void addRandomByte(byte b) {
        if (pools == null) {return;}
        pools[pool].update(b);
        if (pool == 0) {pool0Count++;}
        pool = (pool + 1) % NUM_POOLS;
    }

    /** Add random bytes to the entropy pool. */
    @Override
    public void addRandomBytes(byte[] buf, int offset, int length) {
        if (pools == null) {return;}
        pools[pool].update(buf, offset, length);
        if (pool == 0) {pool0Count += length;}
        pool = (pool + 1) % NUM_POOLS;
    }

    // Reading and writing this object is equivalent to storing and retrieving the seed.
    /** Write object */
    private void writeObject(ObjectOutputStream out) throws IOException {
        byte[] seed = new byte[SEED_FILE_SIZE];
        generator.nextBytes(seed);
        out.write(seed);
    }

    /** Read object */
    private void readObject(ObjectInputStream in) throws IOException {
        byte[] seed = new byte[SEED_FILE_SIZE];
        in.readFully(seed);
        generator.addRandomBytes(seed);
    }

    /**
     * The Fortuna generator function. The generator is a PRNG in its own
     * right; Fortuna itself is basically a wrapper around this generator
     * that manages reseeding in a secure way.
     */
    @SuppressWarnings("java:S2975")
    private static class Generator extends BasePRNGStandalone implements Cloneable {
        private static final int LIMIT = 1 << 20;
        private final MessageDigest hash;
        private final byte[] counter;
        private final byte[] key;
        /** current encryption key built from the keying material */
        private Object cryptixKey;
        private CryptixAESKeyCache.KeyCacheEntry cryptixKeyBuf;
        private boolean seeded;

        /** PRNG generator instance. */
        public Generator () {
            super("Fortuna.generator.i2p");
            this.hash = SHA256Generator.getDigestInstance();
            counter = new byte[16]; //cipher.defaultBlockSize()];
            buffer = new byte[16]; //cipher.defaultBlockSize()];
            int keysize = 32;
            key = new byte[keysize];
            cryptixKeyBuf = CryptixAESKeyCache.createNew();
        }

        /**
         * Return a copy of this object.
         */
        @Override
        public Object clone() {
            try {
                return super.clone();
            } catch (CloneNotSupportedException e) {
                throw new InternalError(e);
            }
        }

        /** Return the next random byte. */
        @Override
        public final byte nextByte() {
            byte[] b = new byte[1];
            nextBytes(b, 0, 1);
            return b[0];
        }

        /** Fill the output buffer with random bytes. */
        @Override
        public final void nextBytes(byte[] out, int offset, int length) {
            if (!seeded) {
                throw new IllegalStateException("generator not seeded");
            }

            int count = 0;
            do {
                int amount = Math.min(LIMIT, length - count);
                super.nextBytes(out, offset+count, amount);
                count += amount;

                for (int i = 0; i < key.length; i += counter.length) {
                    //fillBlock(); // inlined
                    CryptixRijndael_Algorithm.blockEncrypt(counter, buffer, 0, 0, cryptixKey);
                    incrementCounter();
                    int l = Math.min(key.length - i, 16); //cipher.currentBlockSize());
                    System.arraycopy(buffer, 0, key, i, l);
                }
                resetKey();
            }
            while (count < length);
            //fillBlock(); // inlined
            CryptixRijndael_Algorithm.blockEncrypt(counter, buffer, 0, 0, cryptixKey);
            incrementCounter();
            ndx = 0;
        }

        /** Add a random byte to the generator seed. */
        @Override
        public final void addRandomByte(byte b) {
            addRandomBytes(new byte[] { b });
        }

        /** Add random bytes to the generator seed. */
        @Override
        public final void addRandomBytes(byte[] seed, int offset, int length) {
            hash.update(key, 0, key.length);
            hash.update(seed, offset, length);
            byte[] newkey = hash.digest();
            System.arraycopy(newkey, 0, key, 0, Math.min(key.length, newkey.length));
            //hash.doFinal(key, 0);
            resetKey();
            incrementCounter();
            seeded = true;
        }

        /** Fill the PRNG output block. */
        public final void fillBlock() {
            CryptixRijndael_Algorithm.blockEncrypt(counter, buffer, 0, 0, cryptixKey);
            incrementCounter();
        }

        /** Set up the PRNG instance. */
        public void setup(Map<String, byte[]> attributes) {
            seeded = false;
            Arrays.fill(key, (byte) 0);
            Arrays.fill(counter, (byte) 0);
            byte[] seed = attributes.get(SEED);
            if (seed != null) {addRandomBytes(seed);}
        }

        /**
         * Resets the cipher's key. This is done after every reseed, which
         * combines the old key and the seed, and processes that throigh the
         * hash function.
         */
        private final void resetKey() {
            try {cryptixKey = CryptixRijndael_Algorithm.makeKey(key, 16, cryptixKeyBuf);}
            catch (InvalidKeyException ike) {throw new Error("hrmf", ike);}
        }

        /**
         * Increment `counter' as a sixteen-byte little-endian unsigned integer
         * by one.
         */
        private final void incrementCounter() {
            for (int i = 0; i < counter.length; i++) {
                counter[i]++;
                if (counter[i] != 0) {break;}
            }
        }
    }
}
