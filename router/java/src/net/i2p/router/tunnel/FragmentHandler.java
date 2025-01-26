package net.i2p.router.tunnel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.data.i2np.UnknownI2NPMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.HexDump;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SimpleTimer2;

/**
 * Handle fragments at the endpoint of a tunnel, peeling off fully completed 
 * I2NPMessages when they arrive, and dropping fragments if they take too long
 * to arrive.
 *
 * From tunnel-alt.html:

<p>When the gateway wants to deliver data through the tunnel, it first
gathers zero or more <a href="i2np.html">I2NP</a> messages, selects how much padding will be used, 
fragments it across the necessary number of 1KB tunnel messages, and decides how
each I2NP message should be handled by the tunnel endpoint, encoding that
data into the raw tunnel payload:</p>
<ul>
<li>The 4 byte Tunnel ID</li>
<li>The 16 byte IV</li>
<li>the first 4 bytes of the SHA256 of (the remaining preprocessed data concatenated 
    with the IV), using the IV as will be seen on the tunnel endpoint (for
    outbound tunnels), or the IV as was seen on the tunnel gateway (for inbound
    tunnels) (see below for IV processing).</li>
<li>0 or more bytes containing random nonzero integers</li>
<li>1 byte containing 0x00</li>
<li>a series of zero or more { instructions, message } pairs</li>
</ul>

<p>Note that the padding, if any, must be before the instruction/message pairs.
there is no provision for padding at the end.</p>

<p>The instructions are encoded with a single control byte, followed by any
necessary additional information.  The first bit in that control byte determines
how the remainder of the header is interpreted - if it is not set, the message 
is either not fragmented or this is the first fragment in the message.  If it is
set, this is a follow on fragment.</p>

<p>With the first (leftmost or MSB) bit being 0, the instructions are:</p>
<ul>
<li>1 byte control byte:<pre>
      bit 0: is follow on fragment?  (1 = true, 0 = false, must be 0)
   bits 1-2: delivery type
             (0x0 = LOCAL, 0x01 = TUNNEL, 0x02 = ROUTER)
      bit 3: delay included?  (1 = true, 0 = false) (unimplemented)
      bit 4: fragmented?  (1 = true, 0 = false)
      bit 5: extended options?  (1 = true, 0 = false) (unimplemented)
   bits 6-7: reserved</pre></li>
<li>if the delivery type was TUNNEL, a 4 byte tunnel ID</li>
<li>if the delivery type was TUNNEL or ROUTER, a 32 byte router hash</li>
<li>if the delay included flag is true, a 1 byte value (unimplemented):<pre>
      bit 0: type (0 = strict, 1 = randomized)
   bits 1-7: delay exponent (2^value minutes)</pre></li>
<li>if the fragmented flag is true, a 4 byte message ID</li>
<li>if the extended options flag is true (unimplemented):<pre>
   = a 1 byte option size (in bytes)
   = that many bytes</pre></li>
<li>2 byte size of the I2NP message or this fragment</li>
</ul>

<p>If the first bit being 1, the instructions are:</p>
<ul>
<li>1 byte control byte:<pre>
      bit 0: is follow on fragment?  (1 = true, 0 = false, must be 1)
   bits 1-6: fragment number
      bit 7: is last? (1 = true, 0 = false)</pre></li>
<li>4 byte message ID (same one defined in the first fragment)</li>
<li>2 byte size of this fragment</li>
</ul>

<p>The I2NP message is encoded in its standard form, and the 
preprocessed payload must be padded to a multiple of 16 bytes.
The total size, including the tunnel ID and IV, is 1028 bytes.
</p>

 *
 */
class FragmentHandler {
    protected final RouterContext _context;
    protected final Log _log;
    private final Map<Long, FragmentedMessage> _fragmentedMessages;
    private final DefragmentedReceiver _receiver;
    private final AtomicInteger _completed = new AtomicInteger();
    private final AtomicInteger _failed = new AtomicInteger();
    private final boolean _isInbound;
    
    /** don't wait more than this long to completely receive a fragmented message */
    static long MAX_DEFRAGMENT_TIME = 45*1000;
    private static final ByteCache _cache = ByteCache.getInstance(512, TrivialPreprocessor.PREPROCESSED_SIZE);

    /**
     * For unit tests only, others use 3-arg constructor
     *
     * @deprecated
     */
    @Deprecated
    public FragmentHandler(RouterContext context, DefragmentedReceiver receiver) {
        this(context, receiver, true);
    }

    /**
     * @param isInbound true for IBEP, false for OBEP
     */
    public FragmentHandler(RouterContext context, DefragmentedReceiver receiver, boolean isInbound) {
        _context = context;
        _log = context.logManager().getLog(FragmentHandler.class);
        _fragmentedMessages = new HashMap<Long, FragmentedMessage>(16);
        _receiver = receiver;
        _isInbound = isInbound;
        // all createRateStat in TunnelDispatcher
    }
    
    /**
     * Receive the raw preprocessed message at the endpoint, parsing out each
     * of the fragments, using those to fill various FragmentedMessages, and 
     * sending the resulting I2NPMessages where necessary.  The received 
     * fragments are all verified.
     *
     * @return ok (false if corrupt)
     */
    public boolean receiveTunnelMessage(byte preprocessed[], int offset, int length) {
        boolean ok = verifyPreprocessed(preprocessed, offset, length);
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to verify preprocessed data (pre.length=" 
                          + preprocessed.length + " off=" +offset + " len=" + length);
            _cache.release(new ByteArray(preprocessed));
            _context.statManager().addRateData("tunnel.corruptMessage", 1);
            return false;
        }
        offset += HopProcessor.IV_LENGTH; // skip the IV
        offset += 4; // skip the hash segment
        int padding = 0;
        while (preprocessed[offset] != (byte)0x00) {
            offset++; // skip the padding
            // AIOOBE http://forum.i2p/viewtopic.php?t=3187
            if (offset >= TrivialPreprocessor.PREPROCESSED_SIZE) {
                _cache.release(new ByteArray(preprocessed));
                _context.statManager().addRateData("tunnel.corruptMessage", 1);
                if (_log.shouldWarn())
                    _log.warn("Corrupt fragment received: off = " + offset);
                return false;
            }
            padding++;
        }
        offset++; // skip the final 0x00, terminating the padding
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Fragments begin at offset=" + offset + " padding=" + padding);
            //_log.debug("fragments: " + Base64.encode(preprocessed, offset, preprocessed.length-offset));
        }
        try {
            while (offset < length) {
                int off = receiveFragment(preprocessed, offset, length);
                if (off < 0) {
                    _context.statManager().addRateData("tunnel.corruptMessage", 1);
                    if (_log.shouldWarn())
                        _log.warn("Corrupt fragment received: off = " + off);
                    return false;
                }
                offset = off;
            }
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            _context.statManager().addRateData("tunnel.corruptMessage", 1);
            if (_log.shouldWarn())
                _log.warn("Corrupt fragment received: offset = " + offset, aioobe);
            return false;
        } catch (NullPointerException npe) {
            if (_log.shouldWarn())
                _log.warn("Corrupt fragment received: offset = " + offset, npe);
            _context.statManager().addRateData("tunnel.corruptMessage", 1);
            return false;
        } catch (RuntimeException e) {
            if (_log.shouldWarn())
                _log.warn("Corrupt fragment received: offset = " + offset, e);
            _context.statManager().addRateData("tunnel.corruptMessage", 1);
            // java.lang.IllegalStateException: don't get the completed size when we're not complete - null fragment i=0 of 1
            // at net.i2p.router.tunnel.FragmentedMessage.getCompleteSize(FragmentedMessage.java:194)
            // at net.i2p.router.tunnel.FragmentedMessage.toByteArray(FragmentedMessage.java:223)
            // at net.i2p.router.tunnel.FragmentHandler.receiveComplete(FragmentHandler.java:380)
            // at net.i2p.router.tunnel.FragmentHandler.receiveSubsequentFragment(FragmentHandler.java:353)
            // at net.i2p.router.tunnel.FragmentHandler.receiveFragment(FragmentHandler.java:208)
            // at net.i2p.router.tunnel.FragmentHandler.receiveTunnelMessage(FragmentHandler.java:92)
            // ...
            // still trying to find root cause
            // let's limit the damage here and skip the:
            // .transport.udp.MessageReceiver: b0rked receiving a message.. wazza huzza hmm?
            //throw e;
            return false;
        } finally {
            // each of the FragmentedMessages populated make a copy out of the
            // payload, which they release separately, so we can release 
            // immediately
            //
            // This is certainly interesting, to wrap the 1024-byte array in a new ByteArray
            // in order to put it in the pool, but it shouldn't cause any harm.
            _cache.release(new ByteArray(preprocessed));
        }
        return true;
    }
    
    public int getCompleteCount() { return _completed.get(); }
    public int getFailedCount() { return _failed.get(); }
    
    private static final ByteCache _validateCache = ByteCache.getInstance(512, TrivialPreprocessor.PREPROCESSED_SIZE);
    
    /**
     * Verify that the preprocessed data hasn't been modified by checking the 
     * H(payload+IV)[0:3] vs preprocessed[16:19], where payload is the data 
     * after the padding.  Remember, the preprocessed data is formatted as
     * { IV + H[0:3] + padding + {instructions, fragment}* }.  This function is
     * very wasteful of memory usage as it doesn't operate inline (since IV and
     * payload are mixed up).  Later it may be worthwhile to explore optimizing
     * this.
     */
    private boolean verifyPreprocessed(byte preprocessed[], int offset, int length) {
        // ByteCache/ByteArray corruption detection
        //byte[] orig = new byte[length];
        //System.arraycopy(preprocessed, 0, orig, 0, length);
        //try {
        //    Thread.sleep(75);
        //} catch (InterruptedException ie) {}

        // now we need to verify that the message was received correctly
        int paddingEnd = HopProcessor.IV_LENGTH + 4;
        while (preprocessed[offset+paddingEnd] != (byte)0x00) {
            paddingEnd++;
            if (offset+paddingEnd >= length) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("cannot verify, going past the end [off=" 
                              + offset + " len=" + length + " paddingEnd=" 
                              + paddingEnd + " data: "
                              + Base64.encode(preprocessed, offset, length));
                return false;
            }
        }
        paddingEnd++; // skip the last
        
        ByteArray ba = _validateCache.acquire(); // larger than necessary, but always sufficient
        byte preV[] = ba.getData();
        int validLength = length - offset - paddingEnd + HopProcessor.IV_LENGTH;
        System.arraycopy(preprocessed, offset + paddingEnd, preV, 0, validLength - HopProcessor.IV_LENGTH);
        System.arraycopy(preprocessed, 0, preV, validLength - HopProcessor.IV_LENGTH, HopProcessor.IV_LENGTH);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("endpoint IV: " + Base64.encode(preV, validLength - HopProcessor.IV_LENGTH, HopProcessor.IV_LENGTH));
        
        byte[] v = SimpleByteCache.acquire(Hash.HASH_LENGTH);
        _context.sha().calculateHash(preV, 0, validLength, v, 0);
        _validateCache.release(ba);
        
        boolean eq = DataHelper.eq(v, 0, preprocessed, offset + HopProcessor.IV_LENGTH, 4);
        if (!eq) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Corrupt tunnel message - verification fails: " + Base64.encode(preprocessed, offset+HopProcessor.IV_LENGTH, 4)
                           + " != " + Base64.encode(v, 0, 4));
                _log.warn("No matching endpoint: # pad bytes: " + (paddingEnd-(HopProcessor.IV_LENGTH+4)-1)
                           + " offset=" + offset + " length=" + length + " paddingEnd=" + paddingEnd + ' '
                           + Base64.encode(preprocessed, offset, length), new Exception("trace"));
            }
        }
        SimpleByteCache.release(v);
        
        if (eq) {
            int excessPadding = paddingEnd - (HopProcessor.IV_LENGTH + 4 + 1);
            if (excessPadding > 0) // suboptimal fragmentation
                _context.statManager().addRateData("tunnel.smallFragments", excessPadding);
            else
                _context.statManager().addRateData("tunnel.fullFragments", 1);
        }
        
        // ByteCache/ByteArray corruption detection
        //if (!DataHelper.eq(preprocessed, 0, orig, 0, length)) {
        //    _log.log(Log.CRIT, "Not equal! orig =\n" + Base64.encode(orig, 0, length) +
        //             "\nprep =\n" + Base64.encode(preprocessed, 0, length),
        //             new Exception("hosed"));
        //}

        return eq;
    }
    
    /** is this a follw up byte? */
    static final byte MASK_IS_SUBSEQUENT = (byte)(1 << 7);
    /** how should this be delivered.  shift this 5 the right and get TYPE_* */
    static final byte MASK_TYPE = (byte)(3 << 5);
    /** is this the first of a fragmented message? */
    static final byte MASK_FRAGMENTED = (byte)(1 << 3);
    /** are there follow up headers? UNIMPLEMENTED */
    static final byte MASK_EXTENDED = (byte)(1 << 2);
    /** for subsequent fragments, which bits contain the fragment #? */
    private static final int MASK_FRAGMENT_NUM = (byte)((1 << 7) - 2); // 0x7E;
    
    static final short TYPE_LOCAL = 0;
    static final short TYPE_TUNNEL = 1;
    static final short TYPE_ROUTER = 2;
    static final short TYPE_UNDEF = 3;
    
    /** 
     * @return the offset for the next byte after the received fragment or -1 on error
     * @throws RuntimeException
     */
    private int receiveFragment(byte preprocessed[], int offset, int length) {
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("CONTROL: 0x" + Integer.toHexString(preprocessed[offset] & 0xff) +
        //               " at offset " + offset);
        if (0 == (preprocessed[offset] & MASK_IS_SUBSEQUENT))
            return receiveInitialFragment(preprocessed, offset, length);
        else
            return receiveSubsequentFragment(preprocessed, offset, length);
    }
    
    /**
     * Handle the initial fragment in a message (or a full message, if it fits)
     *
     * @return offset after reading the full fragment or -1 on error
     * @throws RuntimeException
     */
    private int receiveInitialFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("initial begins at " + offset + " for " + length);
        int type = (preprocessed[offset] & MASK_TYPE) >>> 5;
        boolean fragmented = (0 != (preprocessed[offset] & MASK_FRAGMENTED));
        boolean extended = (0 != (preprocessed[offset] & MASK_EXTENDED));
        offset++;
        
        TunnelId tunnelId = null;
        Hash router = null;
        long messageId = -1;
        
        if (type == TYPE_TUNNEL) {
            if (offset + 4 >= preprocessed.length)
                return -1;
            long id = DataHelper.fromLong(preprocessed, offset, 4);
            // i2pd 2.19 bug? 0 will throw IAE.
            // message checked and discarded below.
            // don't throw so we can process the other fragments if any, if they're from a different message
            if (id != 0)
                tunnelId = new TunnelId(id);
            offset += 4;
        }
        if ( (type == TYPE_ROUTER) || (type == TYPE_TUNNEL) ) {
            if (offset + Hash.HASH_LENGTH >= preprocessed.length)
                return -1;
            //byte h[] = new byte[Hash.HASH_LENGTH];
            //System.arraycopy(preprocessed, offset, h, 0, Hash.HASH_LENGTH);
            //router = new Hash(h);
            router = Hash.create(preprocessed, offset);
            offset += Hash.HASH_LENGTH;
        }
        if (fragmented) {
            if (offset + 4 >= preprocessed.length)
                return -1;
            messageId = DataHelper.fromLong(preprocessed, offset, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("reading messageId " + messageId + " at offset "+ offset 
                           + " type = " + type + " router = " 
                           + (router != null ? router.toBase64().substring(0,4) : "n/a") 
                           + " tunnelId = " + tunnelId);
            offset += 4;
        }
        if (extended) {
            int extendedSize = preprocessed[offset] & 0xff;
            offset++;
            offset += extendedSize; // we don't interpret these yet, but skip them for now
        }
        
        if (offset + 2 >= preprocessed.length)
            return -1;
        int size = (int)DataHelper.fromLong(preprocessed, offset, 2);
        offset += 2;
        
        if (type == TYPE_UNDEF) {
            // do this after the above since we have to return offset
            // no uses for TYPE_LOCAL yet
            // OutboundTunnelEndpoint doesn't check for null Hash, passes it
            // to OutboundMessageDistributor.distribute() which will NPE
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping msg at tunnel endpoint with unsupported delivery instruction type " +
                          type + " rcvr: " + _receiver);
            _context.statManager().addRateData("tunnel.corruptMessage", 1);
        } else if (type == TYPE_TUNNEL && tunnelId == null) {
            // do this after the above since we have to return offset
            // i2pd 2.19 bug? see above
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping msg at tunnel endpoint with delivery instruction to tunnel 0" +
                          " gw: " + router +
                          " fragmented? " + fragmented +
                          " id: " + messageId +
                          " size: " + size +
                          " type: " + (preprocessed[offset] & 0xff));
            _context.statManager().addRateData("tunnel.corruptMessage", 1);
        } else if (fragmented) {
            FragmentedMessage msg;
            synchronized (_fragmentedMessages) {
                msg = _fragmentedMessages.get(Long.valueOf(messageId));
                if (msg == null) {
                    msg = new FragmentedMessage(_context, messageId);
                    _fragmentedMessages.put(Long.valueOf(messageId), msg);
                }
            }

            // synchronized is required, fragments may be arriving in different threads
            synchronized(msg) {
                boolean ok = msg.receive(preprocessed, offset, size, false, router, tunnelId);
                if (!ok) return -1;
                if (msg.isComplete()) {
                    synchronized (_fragmentedMessages) {
                        _fragmentedMessages.remove(Long.valueOf(messageId));
                    }
                    if (msg.getExpireEvent() != null)
                        msg.getExpireEvent().cancel();
                    receiveComplete(msg);
                } else {
                    if (msg.getExpireEvent() == null) {
                        RemoveFailed evt = new RemoveFailed(msg);
                        msg.setExpireEvent(evt);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("In " + MAX_DEFRAGMENT_TIME + " dropping " + messageId);
                        evt.schedule(MAX_DEFRAGMENT_TIME);
                    }
                }
            }
        } else {
            // Unfragmented
            // synchronized not required
            // always complete, never an expire event
            receiveComplete(preprocessed, offset, size, router, tunnelId);
        }
        
        offset += size;
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Handling finished message " + msg.getMessageId() + " at offset " + offset);
        return offset;
    }
    
    /**
     * Handle a fragment beyond the initial fragment in a message
     *
     * @return offset after reading the full fragment or -1 on error
     * @throws RuntimeException
     */
    private int receiveSubsequentFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("subsequent begins at " + offset + " for " + length);
        int fragmentNum = ((preprocessed[offset] & MASK_FRAGMENT_NUM) >>> 1);
        boolean isLast = (0 != (preprocessed[offset] & 1));
        offset++;
        
        long messageId = DataHelper.fromLong(preprocessed, offset, 4);
        offset += 4;
        
        int size = (int)DataHelper.fromLong(preprocessed, offset, 2);
        offset += 2;
        
        if (messageId < 0)
            throw new RuntimeException("Preprocessed message was invalid [messageId =" + messageId + " size=" 
                                       + size + " offset=" + offset + " fragment=" + fragmentNum);
        
        FragmentedMessage msg = null;
        synchronized (_fragmentedMessages) {
            msg = _fragmentedMessages.get(Long.valueOf(messageId));
            if (msg == null) {
                msg = new FragmentedMessage(_context, messageId);
                _fragmentedMessages.put(Long.valueOf(messageId), msg);
            }
        }
        
        // synchronized is required, fragments may be arriving in different threads
        synchronized(msg) {
            boolean ok = msg.receive(fragmentNum, preprocessed, offset, size, isLast);
            if (!ok) return -1;
            
            if (msg.isComplete()) {
                synchronized (_fragmentedMessages) {
                    _fragmentedMessages.remove(Long.valueOf(messageId));
                }
                if (msg.getExpireEvent() != null)
                    msg.getExpireEvent().cancel();
                _context.statManager().addRateData("tunnel.fragmentedComplete", msg.getFragmentCount(), msg.getLifetime());
                receiveComplete(msg);
            } else {
                if (msg.getExpireEvent() == null) {
                    RemoveFailed evt = new RemoveFailed(msg);
                    msg.setExpireEvent(evt);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("In " + MAX_DEFRAGMENT_TIME + " dropping " + msg.getMessageId() + "/" + fragmentNum);
                    evt.schedule(MAX_DEFRAGMENT_TIME);
                }
            }
        }
        
        offset += size;
        return offset;
    }
    
    
    private void receiveComplete(FragmentedMessage msg) {
        if (msg == null)
            return;
        _completed.incrementAndGet();
        byte data[] = null;
        try {
            // toByteArray destroys the contents of the message completely
            data = msg.toByteArray();
            if (data == null)
                throw new I2NPMessageException("null data");   // fragments already released???
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("RECV(" + data.length + "): "); // + Base64.encode(data)  
                           //+ " " + _context.sha().calculateHash(data).toBase64());

            // Read in as unknown message for outbound tunnels,
            // since this will just be packaged in a TunnelGatewayMessage.
            // Not a big savings since most everything is a GarlicMessage
            // and so the readMessage() call is fast.
            // The unencrypted messages at the OBEP are (V)TBMs
            // and perhaps an occasional DatabaseLookupMessage
            I2NPMessage m;
            if (_isInbound) {
                m = new I2NPMessageHandler(_context).readMessage(data);
            } else {
                int utype = data[0] & 0xff;
                m = new UnknownI2NPMessage(_context, utype);
                m.readBytes(data, utype, 1);
            }
            _receiver.receiveComplete(m, msg.getTargetRouter(), msg.getTargetTunnel());
        } catch (I2NPMessageException ime) {
            if (_log.shouldWarn()) {
                _log.warn("Error receiving fragmented message (corrupt?): " + msg, ime);
                if (_log.shouldInfo()) {
                    _log.info("DUMP:\n" + HexDump.dump(data));
                    _log.info("RAW:\n" + Base64.encode(data));
                }
            }
        }
    }

    /**
     *  Zero-copy reception of an unfragmented message
     *  @since 0.9
     */
    private void receiveComplete(byte[] data, int offset, int len, Hash router, TunnelId tunnelId) {
        _completed.incrementAndGet();
        try {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("RECV unfrag(" + len + ')');

            // Read in as unknown message for outbound tunnels,
            // since this will just be packaged in a TunnelGatewayMessage.
            // Not a big savings since most everything is a GarlicMessage
            // and so the readMessage() call is fast.
            // The unencrypted messages at the OBEP are (V)TBMs
            // and perhaps an occasional DatabaseLookupMessage
            I2NPMessage m;
            if (_isInbound) {
                I2NPMessageHandler h = new I2NPMessageHandler(_context);
                h.readMessage(data, offset, len);
                m = h.lastRead();
            } else {
                int utype = data[offset++] & 0xff;
                m = new UnknownI2NPMessage(_context, utype);
                m.readBytes(data, utype, offset, len - 1);
            }
            _receiver.receiveComplete(m, router, tunnelId);
        } catch (I2NPMessageException ime) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Error receiving unfragmented message (corrupt?)", ime);
                _log.warn("DUMP:\n" + HexDump.dump(data, offset, len));
                _log.warn("RAW:\n" + Base64.encode(data, offset, len));
            }
        }
    }

    /**
     * Receive messages out of the tunnel endpoint.  There should be a single 
     * instance of this object per tunnel so that it can tell what tunnel various
     * messages come in on (e.g. to prevent DataMessages arriving from anywhere 
     * other than the client's inbound tunnels)
     * 
     */
    public interface DefragmentedReceiver {
        /**
         * Receive a fully formed I2NPMessage out of the tunnel
         *
         * @param msg message received 
         * @param toRouter where we are told to send the message (null means locally)
         * @param toTunnel where we are told to send the message (null means locally or to the specified router)
         */
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel);
    }
    
    private class RemoveFailed extends SimpleTimer2.TimedEvent {
        private final FragmentedMessage _msg;

        public RemoveFailed(FragmentedMessage msg) {
            super(_context.simpleTimer2());
            _msg = msg;
        }

        public void timeReached() {
            boolean removed;
            synchronized (_fragmentedMessages) {
                removed = (null != _fragmentedMessages.remove(Long.valueOf(_msg.getMessageId())));
            }
            synchronized (_msg) {
                if (removed && !_msg.getReleased()) {
                    _failed.incrementAndGet();
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropped incomplete fragmented message: " + _msg);
                    _context.statManager().addRateData("tunnel.fragmentedDropped", _msg.getFragmentCount(), _msg.getLifetime());
                    _msg.failed();
                } else {
                    // succeeded before timeout
                }
            }
        }
        
    }
}
