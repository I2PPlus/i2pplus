package gnu.crypto.prng;

import java.io.Serializable;
import java.util.Map;

/**
 * <p>An abstract class to facilitate implementing PRNG algorithms.</p>
 *
 * Modified slightly by jrandom for I2P (removing unneeded exceptions)
 */
public abstract class BasePRNGStandalone implements IRandomStandalone, Serializable {

   // Constants and variables
   // -------------------------------------------------------------------------

   /** The canonical name prefix of the PRNG algorithm. */
    protected final String name;

   /** Indicate if this instance has already been initialised or not. */
    protected volatile boolean initialised;

   /** A temporary buffer to serve random bytes. */
    protected volatile byte[] buffer;

   /** The index into buffer of where the next byte will come from. */
    protected int ndx;

   // Constructor(s)
   // -------------------------------------------------------------------------

   /**
    * <p>Trivial constructor for use by concrete subclasses.</p>
    *
    * @param name the canonical name of this instance.
    */
    protected BasePRNGStandalone(String name) {
        this.name = name;
        buffer = new byte[0];
    }

   // Class methods
   // -------------------------------------------------------------------------

   // Instance methods
   // -------------------------------------------------------------------------

   // IRandomStandalone interface implementation ----------------------------------------

    @Override
    public String name() {
        return name;
    }

    @Override
    public void init(Map<String, byte[]> attributes) {
        this.setup(attributes);

        ndx = 0;
        initialised = true;
    }

    public byte nextByte() throws IllegalStateException {//, LimitReachedException {
        if (!initialised) {
            throw new IllegalStateException();
        }
        return nextByteInternal();
    }

    public void nextBytes(byte[] out) throws IllegalStateException {//, LimitReachedException {
        nextBytes(out, 0, out.length);
    }

    public void nextBytes(byte[] out, int offset, int length)
        throws IllegalStateException //, LimitReachedException
    {
        if (!initialised)
            throw new IllegalStateException("not initialized");

        if (length == 0)
            return;

        if (offset < 0 || length < 0 || offset + length > out.length)
            throw new ArrayIndexOutOfBoundsException("offset=" + offset + " length="
                                                  + length + " limit=" + out.length);

        if (buffer == null)
            throw new IllegalStateException("Random is shut down - do you have a static ref?");
        if (ndx >= buffer.length) {
            fillBlock();
            ndx = 0;
        }
        int count = 0;
        while (count < length) {
            int amount = Math.min(buffer.length - ndx, length - count);
            System.arraycopy(buffer, ndx, out, offset+count, amount);
            count += amount;
            ndx += amount;
            if (ndx >= buffer.length) {
                fillBlock();
                ndx = 0;
            }
        }
    }

    @Override
    public void addRandomByte(byte b) {
        throw new UnsupportedOperationException("random state is non-modifiable");
    }

    public void addRandomBytes(byte[] buffer) {
        addRandomBytes(buffer, 0, buffer.length);
    }

    public void addRandomBytes(byte[] buffer, int offset, int length) {
        throw new UnsupportedOperationException("random state is non-modifiable");
    }

   // Instance methods
   // -------------------------------------------------------------------------

    public boolean isInitialised() {
        return initialised;
    }

    private byte nextByteInternal() {//throws LimitReachedException {
        if (buffer == null)
            throw new IllegalStateException("Random is shut down - do you have a static ref?");
        if (ndx >= buffer.length) {
            this.fillBlock();
            ndx = 0;
        }

        return buffer[ndx++];
    }

   // abstract methods to implement by subclasses -----------------------------

    @Override
  public Object clone() throws CloneNotSupportedException
    {
        return super.clone();
    }

    public abstract void setup(Map<String, byte[]> attributes);

    public abstract void fillBlock(); //throws LimitReachedException;
}
