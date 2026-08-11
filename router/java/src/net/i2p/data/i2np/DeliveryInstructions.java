package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.InputStream;
import java.io.OutputStream;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;

/**
 * Contains the delivery instructions for garlic cloves.
 * Generic "delivery instructions" are used both in tunnel messages and in
 * garlic cloves, with slight differences. However, the tunnel message generator
 * TrivialPreprocessor and reader FragmentHandler do not use this class,
 * the reading and writing is handled inline there.
 *
 * @author jrandom
 */
public class DeliveryInstructions extends DataStructureImpl {
    /** 0-3, set by delivery mode constants */
    private int _deliveryMode;
    /**
     * DELIVERY_MODE_LOCAL.
     */
    public final static int DELIVERY_MODE_LOCAL = 0;
    /**
     * DELIVERY_MODE_DESTINATION.
     */
    public final static int DELIVERY_MODE_DESTINATION = 1;
    /**
     * DELIVERY_MODE_ROUTER.
     */
    public final static int DELIVERY_MODE_ROUTER = 2;
    /**
     * DELIVERY_MODE_TUNNEL.
     */
    public final static int DELIVERY_MODE_TUNNEL = 3;
    /** Hash of the destination for DESTINATION mode */
    private Hash _destinationHash;
    /** Hash of the router for ROUTER or TUNNEL mode */
    private Hash _routerHash;
    /** Tunnel ID for TUNNEL mode */
    private TunnelId _tunnelId;
    /** Whether a delivery delay was requested */
    private boolean _delayRequested;
    /** Delay duration in seconds (obsolete, not implemented) */
    private long _delaySeconds;

    /** Flag bit pattern for local delivery mode */
    private final static int FLAG_MODE_LOCAL = 0;
    /** Flag bit pattern for destination delivery mode */
    private final static int FLAG_MODE_DESTINATION = 1;
    /** Flag bit pattern for router delivery mode */
    private final static int FLAG_MODE_ROUTER = 2;
    /** Flag bit pattern for tunnel delivery mode */
    private final static int FLAG_MODE_TUNNEL = 3;

    /** Bitmask isolating the mode field in the flag byte */
    private final static int FLAG_MODE = 96;
    /** Bitmask for the delay-requested flag */
    private final static int FLAG_DELAY = 16;

    /**
     *  Immutable local instructions, no options
     *
     *  @since 0.9.9
     */
    public static final DeliveryInstructions LOCAL = new LocalInstructions();

    /**
     *  Returns immutable local instructions, or new
     *
     *  @since 0.9.20
     */
    public static DeliveryInstructions create(byte[] data, int offset) {
        if (data[offset] == 0)
            return LOCAL;
        DeliveryInstructions rv = new DeliveryInstructions();
        rv.readBytes(data, offset);
        return rv;
    }

    /**
     * DeliveryInstructions.
     */
    public DeliveryInstructions() {
        _deliveryMode = -1;
    }

    /**
     * For cloves only (not tunnels), default null
     * Unused — always returns null, feature not implemented.
     * @return the encryption key
     */
    public SessionKey getEncryptionKey() { return /* _encryptionKey */ null; }

    /** Default -1. */
    public int getDeliveryMode() { return _deliveryMode; }

    /**
     *  Delivery mode of the message.
     *
     *  @param mode 0-3
     */
    public void setDeliveryMode(int mode) { _deliveryMode = mode; }

    /** Default null. */
    public Hash getDestination() { return _destinationHash; }

    /** Required for DESTINATION. */
    public void setDestination(Hash dest) { _destinationHash = dest; }

    /** Default null. */
    public Hash getRouter() { return _routerHash; }

    /** Required for ROUTER or TUNNEL. */
    public void setRouter(Hash router) { _routerHash = router; }

    /** Default null. */
    public TunnelId getTunnelId() { return _tunnelId; }

    /** Required for TUNNEL. */
    public void setTunnelId(TunnelId id) { _tunnelId = id; }

    /**
     * Defaults to false.
     * Obsolete — delay not implemented in this release.
     * @return the delay requested
     */
    public boolean getDelayRequested() { return _delayRequested; }

    /**
     * Defaults to false.
     * Obsolete — delay not implemented in this release.
     */
    public void setDelayRequested(boolean req) { _delayRequested = req; }

    /**
     * Defaults to 0.
     * Obsolete — delay not implemented in this release.
     * @return the delay seconds
     */
    public long getDelaySeconds() { return _delaySeconds; }

    /**
     * Defaults to 0.
     * Obsolete — delay not implemented in this release.
     */
    public void setDelaySeconds(long seconds) { _delaySeconds = seconds; }

    /**
     *  Not supported, use readBytes(byte[], int)
     *  @throws UnsupportedOperationException always
     */
    public void readBytes(InputStream in) {
        throw new UnsupportedOperationException();
    }

    /**
     * Populate the delivery instructions from the byte array, starting at the given offset.
     * @return the number of bytes read
     */
    public int readBytes(byte[] data, int offset) {
        int cur = offset;
        int flags = data[cur] & 0xff;
        cur++;

        setDeliveryMode(flagMode(flags));
        switch (flagMode(flags)) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                //byte[] destHash = new byte[Hash.HASH_LENGTH];
                //System.arraycopy(data, cur, destHash, 0, Hash.HASH_LENGTH);
                Hash dh = Hash.create(data, cur);
                cur += Hash.HASH_LENGTH;
                setDestination(dh);
                break;
            case FLAG_MODE_ROUTER:
                //byte[] routerHash = new byte[Hash.HASH_LENGTH];
                //System.arraycopy(data, cur, routerHash, 0, Hash.HASH_LENGTH);
                Hash rh = Hash.create(data, cur);
                cur += Hash.HASH_LENGTH;
                setRouter(rh);
                break;
            case FLAG_MODE_TUNNEL:
                //byte[] tunnelRouterHash = new byte[Hash.HASH_LENGTH];
                //System.arraycopy(data, cur, tunnelRouterHash, 0, Hash.HASH_LENGTH);
                Hash trh = Hash.create(data, cur);
                cur += Hash.HASH_LENGTH;
                setRouter(trh);
                setTunnelId(new TunnelId(DataHelper.fromLong(data, cur, 4)));
                cur += 4;
                break;
        }

        if (flagDelay(flags)) {
            long delay = DataHelper.fromLong(data, cur, 4);
            cur += 4;
            setDelayRequested(true);
            setDelaySeconds(delay);
        } else {
            setDelayRequested(false);
        }
        return cur - offset;
    }

    /** Extract the delivery mode from the flags byte. */
    private static int flagMode(int flags) {
        int v = flags & FLAG_MODE;
        v >>>= 5;
        return v;
    }

    /** Whether the delay flag is set in the flags byte. */
    private static boolean flagDelay(int flags) {
        return (0 != (flags & FLAG_DELAY));
    }

    /** Encodes the current delivery mode and delay into flag byte */
    private int getFlags() {
        int val = 0;

        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                val = FLAG_MODE_DESTINATION << 5;
                break;
            case FLAG_MODE_ROUTER:
                val = FLAG_MODE_ROUTER << 5;
                break;
            case FLAG_MODE_TUNNEL:
                val = FLAG_MODE_TUNNEL << 5;
                break;
        }
        if (getDelayRequested())
            val |= FLAG_DELAY;
        return val;
    }

    /** Size of the serialized additional delivery info in bytes */
    private int getAdditionalInfoSize() {
        int additionalSize = 0;

        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                if (_destinationHash == null) throw new IllegalStateException("Destination hash is not set");
                additionalSize += Hash.HASH_LENGTH;
                break;
            case FLAG_MODE_ROUTER:
                if (_routerHash == null) throw new IllegalStateException("Router hash is not set");
                additionalSize += Hash.HASH_LENGTH;
                break;
            case FLAG_MODE_TUNNEL:
                if ( (_routerHash == null) || (_tunnelId == null) ) throw new IllegalStateException("Router hash or tunnel ID is not set");
                additionalSize += Hash.HASH_LENGTH;
                additionalSize += 4; // tunnelId
                break;
        }

        if (getDelayRequested()) {
            additionalSize += 4;
        }
        return additionalSize;
    }

    /** Serializes additional delivery info (hash, tunnel ID, delay) into buffer */
    private int getAdditionalInfo(byte[] rv, int offset) {
        int origOffset = offset;

        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                if (_destinationHash == null) throw new IllegalStateException("Destination hash is not set");
                System.arraycopy(_destinationHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                break;
            case FLAG_MODE_ROUTER:
                if (_routerHash == null) throw new IllegalStateException("Router hash is not set");
                System.arraycopy(_routerHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                break;
            case FLAG_MODE_TUNNEL:
                if ( (_routerHash == null) || (_tunnelId == null) ) throw new IllegalStateException("Router hash or tunnel ID is not set");
                System.arraycopy(_routerHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                DataHelper.toLong(rv, offset, 4, _tunnelId.getTunnelId());
                offset += 4;
                break;
        }
        if (getDelayRequested()) {
            DataHelper.toLong(rv, offset, 4, getDelaySeconds());
            offset += 4;
        }
        return offset - origOffset;
    }

    /**
     *  Not supported, use writeBytes(byte[], int)
     *  @throws UnsupportedOperationException always
     */
    public void writeBytes(OutputStream out) {
        throw new UnsupportedOperationException();
    }

    /**
     * Serialize the delivery instructions into the byte array, starting at the given offset.
     * @return the number of bytes written to the target
     */
    public int writeBytes(byte[] target, int offset) {
        if ( (_deliveryMode < 0) || (_deliveryMode > FLAG_MODE_TUNNEL) ) throw new IllegalStateException("Invalid data: mode = " + _deliveryMode);
        int flags = getFlags();
        int origOffset = offset;
        target[offset++] = (byte) flags;
        offset += getAdditionalInfo(target, offset);
        return offset - origOffset;
    }

    /**
     * Size of the serialized delivery instructions in bytes.
     * @return the size
     */
    public int getSize() {
        return 1 + getAdditionalInfoSize(); // flags +
    }

    /**
     * Compare the delivery instructions for equality, including mode, delay, and target fields.
     */
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof DeliveryInstructions)) {return false;}
        DeliveryInstructions instr = (DeliveryInstructions)obj;
        return (getDelayRequested() == instr.getDelayRequested()) &&
               (getDelaySeconds() == instr.getDelaySeconds()) &&
               (getDeliveryMode() == instr.getDeliveryMode()) &&
               //(getEncrypted() == instr.getEncrypted()) &&
               DataHelper.eq(getDestination(), instr.getDestination()) &&
               DataHelper.eq(getEncryptionKey(), instr.getEncryptionKey()) &&
               DataHelper.eq(getRouter(), instr.getRouter()) &&
               DataHelper.eq(getTunnelId(), instr.getTunnelId());
    }

    /**
     * Hash code combining the delay, mode, and target fields.
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return (int)getDelaySeconds() +
                    getDeliveryMode() +
                    DataHelper.hashCode(getDestination()) +
                    DataHelper.hashCode(getEncryptionKey()) +
                    DataHelper.hashCode(getRouter()) +
                    DataHelper.hashCode(getTunnelId());
    }

    /**
     * String form for debugging, showing the mode and any delay or target fields.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("\n* Delivery mode: ");
        switch (getDeliveryMode()) {
            case DELIVERY_MODE_LOCAL:
                buf.append("Local");
                break;
            case DELIVERY_MODE_DESTINATION:
                break;
            case DELIVERY_MODE_ROUTER:
                buf.append("Router");
                break;
            case DELIVERY_MODE_TUNNEL:
                buf.append("Tunnel");
                break;
        }
        if (_delayRequested) {buf.append("\n* Delay seconds: ").append(getDelaySeconds());}
        if (_destinationHash != null) {buf.append("\n* Destination: ").append(getDestination());}
        if (_tunnelId != null) {buf.append("\n* TunnelId: ").append(getTunnelId());}
        if (_routerHash != null) {buf.append("\n* Router: ").append(getRouter());}
        return buf.toString();
    }

    /**
     *  An immutable local delivery instructions with no options
     *  for efficiency.
     *
     *  @since 0.9.9
     */
    private static final class LocalInstructions extends DeliveryInstructions {
        //private static final byte flag = DELIVERY_MODE_LOCAL << 5;  // 0

        /**
         * Delivery mode for this local delivery.
         * @return the delivery mode
         */
        @Override
        public int getDeliveryMode() { return DELIVERY_MODE_LOCAL; }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public void setDeliveryMode(int mode) {
            throw new RuntimeException("immutable");
        }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public void setDestination(Hash dest) {
            throw new RuntimeException("immutable");
        }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public void setRouter(Hash router) {
            throw new RuntimeException("immutable");
        }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public void setTunnelId(TunnelId id) {
            throw new RuntimeException("immutable");
        }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public void setDelayRequested(boolean req) {
            throw new RuntimeException("immutable");
        }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public void setDelaySeconds(long seconds) {
            throw new RuntimeException("immutable");
        }

        /**
         * Throws, as local delivery instructions are immutable.
         */
        @Override
        public int readBytes(byte[] data, int offset) {
            throw new RuntimeException("immutable");
        }

        /**
         * Writes the zero flag byte, as local delivery has no additional info.
         */
        @Override
        public int writeBytes(byte[] target, int offset) {
            target[offset] = 0;
            return 1;
        }

        /**
         * Local delivery serializes to a single flag byte.
         * @return the size
         */
        @Override
        public int getSize() {
            return 1;
        }

        /**
         * String form for debugging.
         */
        @Override
        public String toString() {
            return "\n\tDelivery Instructions:" +
                    "\n* Delivery mode: local";
        }
    }
}
