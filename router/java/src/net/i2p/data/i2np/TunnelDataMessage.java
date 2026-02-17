package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.TunnelId;
import net.i2p.util.ByteCache;

/**
 * Defines the message sent between routers as part of the tunnel delivery
 *
 * The tunnel ID is changed in-place by TunnelParticipant.send(), so
 * we can't reuse the checksum on output, but we still subclass
 * FastI2NPMessageImpl so we don't verify the checksum on input...
 * because this is a high-usage class.
 *
 */
public class TunnelDataMessage extends FastI2NPMessageImpl {
    private long _tunnelId;
    private TunnelId _tunnelIdObj;
    private byte[] _data;
    private ByteArray _dataBuf;

    public final static int MESSAGE_TYPE = 18;
    public static final int DATA_SIZE = 1024;
    /** if we can't deliver a tunnel message in 10s, forget it */
    private static final int EXPIRATION_PERIOD = 10*1000;

    private static final ByteCache _cache;
    /**
     * When true, it means this tunnelDataMessage is being used as part of a tunnel processing
     * pipeline, where the byte array is acquired during the TunnelDataMessage's creation
     * (per readMessage), held onto through several transitions (updating and moving that array
     * between different TunnelDataMessage instances or the fragment handler's cache, etc), until
     * it is finally released back into the cache when written to the next peer (or explicitly by
     * the fragment handler's completion). Setting this to false just increases memory churn.
     *
     * This is tricky to get right and avoid data corruption.
     */
    private static final boolean PIPELINED_CACHE = true;

    static {
        if (PIPELINED_CACHE) {_cache = ByteCache.getInstance(512, DATA_SIZE);}
        else {_cache = null;}
    }

    /** For use-after-free checks. Always false if PIPELINED_CACHE is false. */
    private boolean _hadCache;

    public TunnelDataMessage(I2PAppContext context) {
        super(context);
        setMessageExpiration(context.clock().now() + EXPIRATION_PERIOD);
    }

    public long getTunnelId() {return _tunnelId;}

    /**
     *  (correctly) Invalidates stored checksum
     */
    public void setTunnelId(long id) {
        _hasChecksum = false;
        _tunnelId = id;
    }

    public TunnelId getTunnelIdObj() {
        if (_tunnelIdObj == null) {
            if (_context.isRouterContext()) {
                _tunnelIdObj = ((net.i2p.router.RouterContext) _context).tunnelIdManager().getOrCreate(_tunnelId);
            } else {
                _tunnelIdObj = new TunnelId(_tunnelId);
            }
        }
        return _tunnelIdObj;
    }

    /**
     *  (correctly) Invalidates stored checksum
     */
    public void setTunnelId(TunnelId id) {
        _hasChecksum = false;
        _tunnelIdObj = id;
        _tunnelId = id.getTunnelId();
    }

    public byte[] getData() {
        if (_hadCache && _dataBuf == null) {
            RuntimeException e = new RuntimeException("TDM data buf use after free");
            _log.error("TDM boom", e);
            throw e;
        }
        return _data;
    }

    /**
     *  @throws IllegalStateException if data previously set, to protect saved checksum
     */
    public void setData(byte data[]) {
        if (_data != null) {throw new IllegalStateException();}
        if ((data == null) || (data.length <= 0)) {throw new IllegalArgumentException("Empty tunnel payload?");}
        _data = data;
    }

    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        _tunnelId = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        if (_tunnelId <= 0) {throw new I2NPMessageException("Invalid tunnel Id " + _tunnelId);}

        /**
         * We can't cache it in trivial form, as other components (e.g. HopProcessor)
         * call getData() and use it as the buffer to write with.  It is then used
         * again to pass to the 'receiver', which may even cache it in a FragmentMessage.
         */
        if (PIPELINED_CACHE) {
            _dataBuf = _cache.acquire();
            _data = _dataBuf.getData();
            _hadCache = true;
        } else {_data = new byte[DATA_SIZE];}
        System.arraycopy(data, curIndex, _data, 0, DATA_SIZE);
    }

    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() {return 4 + DATA_SIZE;}
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ((_tunnelId <= 0) || (_data == null)) {
            throw new I2NPMessageException("Not enough data to write out (id=" + _tunnelId + ")");
        }
        if (_data.length <= 0) {
            throw new I2NPMessageException("Not enough data to write out (data.length=" + _data.length + ")");
        }
        if (_hadCache && _dataBuf == null) {
            I2NPMessageException e = new I2NPMessageException("TDM data buf use after free");
            _log.error("TDM boom", e);
            throw e;
        }

        DataHelper.toLong(out, curIndex, 4, _tunnelId);
        curIndex += 4;
        System.arraycopy(_data, 0, out, curIndex, DATA_SIZE);
        curIndex += _data.length;

        // We can use from the cache, we just can't release to the cache, due to the bug
        // noted above. In effect, this means that transmitted TDMs don't get their
        // dataBufs released - but received TDMs do (via FragmentHandler)
        return curIndex;
    }

    public int getType() {return MESSAGE_TYPE;}

    @Override
    public int hashCode() {return (int)_tunnelId +DataHelper.hashCode(_data);}

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof TunnelDataMessage)) {
            TunnelDataMessage msg = (TunnelDataMessage)object;
            return _tunnelId == msg.getTunnelId() && DataHelper.eq(getData(),msg.getData());
        } else {return false;}
    }

    @Override
    public byte[] toByteArray() {
        byte rv[] = super.toByteArray();
        if (rv == null) {throw new RuntimeException("unable to toByteArray(): " + toString());}
        return rv;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        long uid = getUniqueId();
        String paddedUid = String.format("%010d", uid);
        buf.append("TunnelDataMessage [");
        buf.append(paddedUid).append("]");
        return buf.toString();
    }

}
