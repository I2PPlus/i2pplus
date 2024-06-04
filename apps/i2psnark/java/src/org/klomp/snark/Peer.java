/* Peer - All public information concerning a peer.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;

import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

public class Peer implements Comparable<Peer>, BandwidthListener
{
  protected final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
  // Identifying property, the peer id of the other side.
  private final PeerID peerID;

  private final byte[] my_id;
  private final byte[] infohash;
  /** will start out null in magnet mode */
  protected MetaInfo metainfo;
  private Map<String, BEValue> handshakeMap;

  // The data in/output streams set during the handshake and used by
  // the actual connections.
  private DataInputStream din;
  private DataOutputStream dout;

  /** running counters */
  private final AtomicLong downloaded = new AtomicLong();
  private final AtomicLong uploaded = new AtomicLong();

  /**                                                                    `
   *  Keeps state for in/out connections.  Non-null when the handshake
   *  was successful, the connection setup and runs.
   *  Do not access directly. All actions should be through Peer methods.
   */
  volatile PeerState state;

  /** shared across all peers on this torrent */
  MagnetState magnetState;

  private I2PSocket sock;

  private boolean deregister = true;
  private static final AtomicLong __id = new AtomicLong();
  private final long _id;
  private final AtomicBoolean _disconnected = new AtomicBoolean();

  final static long CHECK_PERIOD = PeerCoordinator.CHECK_PERIOD; // 40 seconds
  final static int RATE_DEPTH = PeerCoordinator.RATE_DEPTH; // make following arrays RATE_DEPTH long
  private final long uploaded_old[] = {-1,-1,-1};
  private final long downloaded_old[] = {-1,-1,-1};

  private static final byte[] HANDSHAKE = DataHelper.getASCII("BitTorrent protocol");
  // See BEP 4 for definitions
  //  bytes per bt spec:                         0011223344556677
  private static final long OPTION_EXTENSION = 0x0000000000100000l;
  private static final long OPTION_FAST      = 0x0000000000000004l;
  //private static final long OPTION_DHT       = 0x0000000000000001l;
  //private static final long OPTION_AZMP      = 0x1000000000000000l;
  // hybrid support TODO
  private static final long OPTION_V2        = 0x0000000000000010L;
  private long options;
  private final boolean _isIncoming;
  private int _totalCommentsSent;
  private int _maxPipeline = PeerState.MIN_PIPELINE;
  private long connected;
  private long pexLastSent;

  /**
   * Outgoing connection.
   * Creates a disconnected peer given a PeerID, your own id and the
   * relevant MetaInfo.
   * @param metainfo null if in magnet mode
   */
  public Peer(PeerID peerID, byte[] my_id, byte[] infohash, MetaInfo metainfo)
  {
    this.peerID = peerID;
    this.my_id = my_id;
    this.infohash = infohash;
    this.metainfo = metainfo;
    _id = __id.incrementAndGet();
    _isIncoming = false;
    //_log.debug("Creating a new peer with " + peerID.toString(), new Exception("creating"));
  }

  /**
   * Incoming connection.
   * Creates a unconnected peer from the input and output stream got
   * from the socket. Note that the complete handshake (which can take
   * some time or block indefinitely) is done in the calling Thread to
   * get the remote peer id. To completely start the connection call
   * the connect() method.
   *
   * @param metainfo null if in magnet mode
   * @throws IOException when an error occurred during the handshake.
   */
  public Peer(final I2PSocket sock, InputStream in, OutputStream out, byte[] my_id, byte[] infohash, MetaInfo metainfo)
    throws IOException
  {
    this.my_id = my_id;
    this.infohash = infohash;
    this.metainfo = metainfo;
    this.sock = sock;

    byte[] id  = handshake(in, out);
    this.peerID = new PeerID(id, sock.getPeerDestination());
    _id = __id.incrementAndGet();
    if (_log.shouldDebug())
        _log.debug("Creating a new peer " + peerID.toString(), new Exception("creating " + _id));
    _isIncoming = true;
  }

  /**
   * Is this an incoming connection?
   * For RPC
   * @since 0.9.30
   */
  public boolean isIncoming() {
      return _isIncoming;
  }

  /**
   * Returns the id of the peer.
   */
  public PeerID getPeerID()
  {
    return peerID;
  }

  /**
   * Returns the String representation of the peerID.
   */
    @Override
  public String toString()
  {
    if (peerID != null)
      return peerID.toString() + ' ' + _id;
    else
      return "[unknown id] " + _id;
  }

  /**
   * @return socket debug string (for debug printing)
   */
  public String getSocket()
  {
    if (state != null) {
        String r = state.getRequests();
        if (r != null)
            return sock.toString() + "<br><b>Requests:</b> <span class=debugRequests>" + r + "</span>";
    }
    return sock.toString();
  }

  /**
   * The hash code of a Peer is the hash code of the peerID.
   */
    @Override
  public int hashCode()
  {
    return peerID.hashCode() ^ (7777 * (int)_id);
  }

  /**
   * Two Peers are equal when they have the same PeerID.
   * All other properties are ignored.
   */
    @Override
  public boolean equals(Object o)
  {
    if (o instanceof Peer)
      {
        Peer p = (Peer)o;
        return _id == p._id && peerID.equals(p.peerID);
      }
    else
      return false;
  }

  /**
   * Compares the PeerIDs.
   * @deprecated unused?
   */
  @Deprecated
  public int compareTo(Peer p)
  {
    int rv = peerID.compareTo(p.peerID);
    if (rv == 0) {
        if (_id > p._id) return 1;
        else if (_id < p._id) return -1;
        else return 0;
    } else {
        return rv;
    }
  }

  /**
   * Runs the connection to the other peer. This method does not
   * return until the connection is terminated.
   *
   * When the connection is correctly started the connected() method
   * of the given PeerListener is called. If the connection ends or
   * the connection could not be setup correctly the disconnected()
   * method is called.
   *
   * If the given BitField is non-null it is send to the peer as first
   * message.
   *
   * @param uploadOnly if we are complete with skipped files, i.e. a partial seed
   */
  public void runConnection(I2PSnarkUtil util, PeerListener listener, BandwidthListener bwl, BitField bitfield,
                            MagnetState mState, boolean uploadOnly) {
    if (state != null)
      throw new IllegalStateException("Peer already started");

    if (_log.shouldDebug())
        _log.debug("Running connection to " + peerID.toString(), new Exception("connecting"));
    try
      {
        // Do we need to handshake?
        if (din == null)
          {
            // Outgoing connection
            sock = util.connect(peerID);
            if (_log.shouldDebug())
                _log.debug("Connected to " + peerID + ": " + sock);
            if ((sock == null) || (sock.isClosed())) {
                throw new IOException("Unable to reach " + peerID);
            }
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            byte [] id = handshake(in, out);
            byte [] expected_id = peerID.getID();
            if (expected_id == null) {
                peerID.setID(id);
            } else if (Arrays.equals(expected_id, id)) {
                if (_log.shouldDebug())
                    _log.debug("Handshake got matching IDs with " + toString());
            } else {
                throw new IOException("Unexpected peerID '"
                                    + PeerID.idencode(id)
                                    + "' expected '"
                                    + PeerID.idencode(expected_id) + "'");
            }
          } else {
            // Incoming connection
            if (_log.shouldDebug())
                _log.debug("Already have din [" + sock + "] with " + toString());
          }

        // bad idea?
        if (metainfo == null && (options & OPTION_EXTENSION) == 0) {
            if (_log.shouldInfo())
                _log.info("Peer does not support extensions and we need metainfo, dropping");
            throw new IOException("Peer does not support extensions and we need metainfo, dropping");
        }

        PeerConnectionIn in = new PeerConnectionIn(this, din);
        PeerConnectionOut out = new PeerConnectionOut(this, dout);
        PeerState s = new PeerState(this, listener, bwl, metainfo, in, out);

        if ((options & OPTION_EXTENSION) != 0) {
            if (_log.shouldDebug())
                _log.debug("Peer supports extensions, sending reply message");
            int metasize = metainfo != null ? metainfo.getInfoBytesLength() : -1;
            boolean pexAndMetadata = metainfo == null || !metainfo.isPrivate();
            boolean dht = util.getDHT() != null;
            boolean comment = util.utCommentsEnabled();
            out.sendExtension(0, ExtensionHandler.getHandshake(metasize, pexAndMetadata, dht, uploadOnly, comment));
        }

        // Send our bitmap
        if (bitfield != null)
          s.out.sendBitfield(bitfield);

        // We are up and running!
        state = s;
        magnetState = mState;
        connected = util.getContext().clock().now();
        listener.connected(this);

        if (_log.shouldDebug())
            _log.debug("Start running the reader with " + toString());
        // Use this thread for running the incoming connection.
        // The outgoing connection creates its own Thread.
        out.startup();
        Thread.currentThread().setName("Snark reader from " + peerID);
        s.in.run();
      }
    catch(IOException eofe)
      {
        // Ignore, probably just the other side closing the connection.
        // Or refusing the connection, timing out, etc.
        if (_log.shouldDebug())
            _log.debug(this.toString(), eofe);
      }
    catch(Throwable t)
      {
        _log.error(this + ": " + t.getMessage(), t);
        if (t instanceof OutOfMemoryError)
            throw (OutOfMemoryError)t;
      }
    finally
      {
        if (deregister) listener.disconnected(this);
        disconnect();
      }
  }

  /**
   * Sets DataIn/OutputStreams, does the handshake and returns the id
   * reported by the other side.
   */
  private byte[] handshake(InputStream in, OutputStream out)
    throws IOException
  {
    dout = new DataOutputStream(out);

    // Handshake write - header
    dout.write(HANDSHAKE.length);
    dout.write(HANDSHAKE);
    // Handshake write - options
    long myOptions = OPTION_EXTENSION;
    // we can't handle HAVE_ALL or HAVE_NONE if we don't know the number of pieces
    if (metainfo != null)
        myOptions |= OPTION_FAST;
    // FIXME get util here somehow
    //if (util.getDHT() != null)
    //    myOptions |= OPTION_I2P_DHT;
    dout.writeLong(myOptions);
    // Handshake write - metainfo hash
    dout.write(infohash);
    // Handshake write - peer id
    dout.write(my_id);
    dout.flush();

    if (_log.shouldDebug())
        _log.debug("Wrote our shared hash and ID to [" + toString() + "]");

    // Handshake read - header
    din = new DataInputStream(in);
    byte b = din.readByte();
    if (b != HANDSHAKE.length)
      throw new IOException("Handshake failure, expected 19, got "
                            + (b & 0xff) + " on " + sock);

    byte[] bs = new byte[HANDSHAKE.length];
    din.readFully(bs);
    if (!Arrays.equals(HANDSHAKE, bs))
      throw new IOException("Handshake failure, expected "
                            + "'BitTorrent protocol'");

    // Handshake read - options
    options = din.readLong();

    // Handshake read - metainfo hash
    bs = new byte[20];
    din.readFully(bs);
    if (!Arrays.equals(infohash, bs))
      throw new IOException("Unexpected MetaInfo hash");

    // Handshake read - peer id
    din.readFully(bs);
    if (_log.shouldDebug())
        _log.debug("Read the remote side's hash and peerID fully from " + toString());

    if (DataHelper.eq(my_id, bs))
        throw new IOException("Connected to myself");

    if (options != 0) {
        // send them something in runConnection() above
        if (_log.shouldDebug())
            _log.debug("Peer supports options 0x" + Long.toHexString(options) + ": " + toString());
    }

    return bs;
  }

  /** @since 0.9.21 */
  public boolean supportsFast() {
      return (options & OPTION_FAST) != 0;
  }

  /** @since 0.8.4 */
  public Destination getDestination() {
      if (sock == null)
          return null;
      return sock.getPeerDestination();
  }

  /**
   *  Shared state across all peers, callers must sync on returned object
   *  @return non-null
   *  @since 0.8.4
   */
  public MagnetState getMagnetState() {
      return magnetState;
  }

  /** @return could be null @since 0.8.4 */
  public Map<String, BEValue> getHandshakeMap() {
      return handshakeMap;
  }

  /**
   *  @param map non-null
   *  @since 0.8.4
   */
  public void setHandshakeMap(Map<String, BEValue> map) {
      handshakeMap = map;
      BEValue bev = map.get("reqq");
      if (bev != null) {
          try {
              int reqq = bev.getInt();
              _maxPipeline = Math.min(PeerState.MAX_PIPELINE, Math.max(PeerState.MIN_PIPELINE, reqq));
          } catch (InvalidBEncodingException ibee) {}
      } else {
          // BEP 10 "The default in libtorrent is 250"
          _maxPipeline = PeerState.MAX_PIPELINE;
      }
  }

  /**
   *  @return min of PeerState.MIN_PIPELINE, max of PeerState.MAX_PIPELINE
   *  @since 0.9.47
   */
  public int getMaxPipeline() {
      return _maxPipeline;
  }

  /** @since 0.8.4 */
  public void sendExtension(int type, byte[] payload) {
    PeerState s = state;
    if (s != null)
        s.out.sendExtension(type, payload);
  }

  /**
   *  Switch from magnet mode to normal mode
   *  @since 0.8.4
   */
  public void setMetaInfo(MetaInfo meta) {
    metainfo = meta;
    PeerState s = state;
    if (s != null)
        s.setMetaInfo(meta);
  }

  public boolean isConnected()
  {
    return state != null;
  }

  /**
   * Disconnects this peer if it was connected.  If deregister is
   * true, PeerListener.disconnected() will be called when the
   * connection is completely terminated. Otherwise the connection is
   * silently terminated.
   */
  public void disconnect(boolean deregister)
  {
    // Both in and out connection will call this.
    this.deregister = deregister;
    disconnect();
  }

  void disconnect()
  {
    if (!_disconnected.compareAndSet(false, true))
        return;
    PeerState s = state;
    if (s != null)
      {
        // try to save partial piece
        if (this.deregister) {
          PeerListener p = s.listener;
          if (p != null) {
            List<Request> pcs = s.returnPartialPieces();
            if (!pcs.isEmpty())
                p.savePartialPieces(this, pcs);
            // now covered by savePartialPieces
            //p.markUnrequested(this);
          }
        }
        state = null;

        PeerConnectionIn in = s.in;
        if (in != null)
          in.disconnect();
        // this is blocking in streaming, so do this after closing the socket
        // so it won't really block
        //PeerConnectionOut out = s.out;
        //if (out != null)
        //  out.disconnect();
        PeerListener pl = s.listener;
        if (pl != null)
          pl.disconnected(this);
      }
    I2PSocket csock = sock;
    sock = null;
    if ( (csock != null) && (!csock.isClosed()) ) {
        try {
            csock.close();
        } catch (IOException ioe) {
            _log.warn("Error disconnecting " + toString(), ioe);
        }
    }
    if (s != null) {
        // this is blocking in streaming, so do this after closing the socket
        // so it won't really block
        PeerConnectionOut out = s.out;
        if (out != null)
          out.disconnect();
    }
  }

  /**
   * Tell the peer we have another piece.
   */
  public void have(int piece)
  {
    PeerState s = state;
    if (s != null)
      s.havePiece(piece);
  }

  /**
   * Tell the other side that we are no longer interested in any of
   * the outstanding requests (if any) for this piece.
   * @since 0.8.1
   */
  void cancel(int piece) {
    PeerState s = state;
    if (s != null)
      s.cancelPiece(piece);
  }

  /**
   * Are we currently requesting the piece?
   * @deprecated deadlocks
   * @since 0.8.1
   */
  @Deprecated
  boolean isRequesting(int p) {
    PeerState s = state;
    return s != null && s.isRequesting(p);
  }

  /**
   * Update the request queue.
   * Call after adding wanted pieces.
   * @since 0.8.1
   */
  void request() {
    PeerState s = state;
    if (s != null)
      s.addRequest();
  }

  /**
   * Whether or not the peer is interested in pieces we have. Returns
   * false if not connected.
   */
  public boolean isInterested()
  {
    PeerState s = state;
    return (s != null) && s.interested;
  }

  /**
   * Sets whether or not we are interested in pieces from this peer.
   * Defaults to false. When interest is true and this peer unchokes
   * us then we start downloading from it. Has no effect when not connected.
   * @deprecated unused
   */
  @Deprecated
  public void setInteresting(boolean interest)
  {
    PeerState s = state;
    if (s != null)
      s.setInteresting(interest);
  }

  /**
   * Whether or not the peer has pieces we want from it. Returns false
   * if not connected.
   */
  public boolean isInteresting()
  {
    PeerState s = state;
    return (s != null) && s.interesting;
  }

  /**
   * Sets whether or not we are choking the peer. Defaults to
   * true. When choke is false and the peer requests some pieces we
   * upload them, otherwise requests of this peer are ignored.
   */
  public void setChoking(boolean choke)
  {
    PeerState s = state;
    if (s != null)
      s.setChoking(choke);
  }

  /**
   * Whether or not we are choking the peer. Returns true when not connected.
   */
  public boolean isChoking()
  {
    PeerState s = state;
    return (s == null) || s.choking;
  }

  /**
   * Whether or not the peer choked us. Returns true when not connected.
   */
  public boolean isChoked()
  {
    PeerState s = state;
    return (s == null) || s.choked;
  }

  /////// begin BandwidthListener interface ///////

  /**
   * Increment the counter.
   * @since 0.8.4
   */
  public void downloaded(int size) {
      downloaded.addAndGet(size);
      PeerState s = state;
      if (s != null)
          s.getBandwidthListener().downloaded(size);
  }

  /**
   * Increment the counter.
   * @since 0.8.4
   */
  public void uploaded(int size) {
      uploaded.addAndGet(size);
      PeerState s = state;
      if (s != null)
          s.getBandwidthListener().uploaded(size);
  }

  /**
   * Returns the number of bytes that have been downloaded.
   * Can be reset to zero with <code>resetCounters()</code>
   * which is called every CHECK_PERIOD by PeerCheckerTask.
   */
  public long getDownloaded()
  {
      return downloaded.get();
  }

  /**
   * Returns the number of bytes that have been uploaded.
   * Can be reset to zero with <code>resetCounters()</code>
   * which is called every CHECK_PERIOD by PeerCheckerTask.
   */
  public long getUploaded()
  {
      return uploaded.get();
  }

  /**
   * Returns the average rate in Bps
   */
  public long getUploadRate()
  {
    return PeerCoordinator.getRate(uploaded_old);
  }

  public long getDownloadRate()
  {
    return PeerCoordinator.getRate(downloaded_old);
  }

  /**
   * Should we send this many bytes?
   * Do NOT call uploaded() after this.
   * @since 0.9.62
   */
  public boolean shouldSend(int size) {
    PeerState s = state;
    if (s != null) {
        boolean rv = s.getBandwidthListener().shouldSend(size);
        if (rv)
            uploaded.addAndGet(size);
        return rv;
    }
    return false;
  }

  /**
   * Should we request this many bytes?
   * @since 0.9.62
   */
  public boolean shouldRequest(int size) {
    PeerState s = state;
    if (s != null)
        return s.getBandwidthListener().shouldRequest(this, size);
    return false;
  }

  /**
   * Should we request this many bytes?
   * @since 0.9.62
   */
  public boolean shouldRequest(Peer peer, int size) {
    if (peer != this)
        return false;
    PeerState s = state;
    if (s != null)
        return s.getBandwidthListener().shouldRequest(this, size);
    return false;
  }

  /**
   * Current limit in Bps
   * @since 0.9.62
   */
  public long getUpBWLimit() {
    PeerState s = state;
    if (s != null)
      return s.getBandwidthListener().getUpBWLimit();
    return Integer.MAX_VALUE;
  }

  /**
   *  Is snark as a whole over its limit?
   * @since 0.9.62
   */
  public boolean overUpBWLimit()
  {
      PeerState s = state;
      if (s != null)
        return s.getBandwidthListener().overUpBWLimit();
      return false;
  }

  /**
   * Current limit in Bps
   * @since 0.9.62
   */
  public long getDownBWLimit() {
    PeerState s = state;
    if (s != null)
      return s.getBandwidthListener().getDownBWLimit();
    return Integer.MAX_VALUE;
  }

  /**
   * Are we currently over the limit?
   * @since 0.9.62
   */
  public boolean overDownBWLimit() {
    PeerState s = state;
    if (s != null)
      return s.getBandwidthListener().overDownBWLimit();
    return false;
  }

  /**
   * Push the total uploaded/downloaded onto a RATE_DEPTH deep stack
   * Resets the downloaded and uploaded counters to zero.
   */
  void setRateHistory() {
    long up = uploaded.getAndSet(0);
    PeerCoordinator.setRate(up, uploaded_old);
    long down = downloaded.getAndSet(0);
    PeerCoordinator.setRate(down, downloaded_old);
  }

  /////// end BandwidthListener interface ///////

  public long getInactiveTime() {
      PeerState s = state;
      if (s != null) {
          PeerConnectionIn in = s.in;
          PeerConnectionOut out = s.out;
          if (in != null && out != null) {
            long now = System.currentTimeMillis();
            return Math.max(now - out.lastSent, now - in.lastRcvd);
          } else
            return -1; //"state, no out";
      } else {
          return -1; //"no state";
      }
  }

  /** @since 0.9.36 */
  public long getMaxInactiveTime() {
      return isCompleted() && !isInteresting() ?
             PeerCoordinator.MAX_SEED_INACTIVE :
             PeerCoordinator.MAX_INACTIVE;
  }

  /**
   * Send keepalive
   */
  public void keepAlive()
  {
    PeerState s = state;
    if (s != null)
      s.keepAlive();
  }

  /**
   * Retransmit outstanding requests if necessary
   */
  public void retransmitRequests()
  {
    PeerState s = state;
    if (s != null)
      s.retransmitRequests();
  }

  /**
   * Return how much the peer has
   * @return number of completed pieces (not bytes)
   */
  public int completed()
  {
    PeerState s = state;
    if (s == null || s.bitfield == null)
        return 0;
    return s.bitfield.count();
  }

  /**
   * Return if a peer is a seeder
   */
  public boolean isCompleted()
  {
    PeerState s = state;
    if (s == null || s.bitfield == null)
        return false;
    return s.bitfield.complete();
  }

  /** @since 0.9.31 */
  int getTotalCommentsSent() {
    return _totalCommentsSent;
  }

  /** @since 0.9.31 */
  void setTotalCommentsSent(int count) {
    _totalCommentsSent = count;
  }

  /**
   * @return false
   * @since 0.9.49
   */
  public boolean isWebPeer() {
      return false;
  }

  /**
   * @when did handshake complete?
   * @since 0.9.63
   */
  public long getWhenConnected() {
      return connected;
  }

  /**
   * @when did we last send pex peers?
   * @since 0.9.63
   */
  public long getPexLastSent() {
      return pexLastSent;
  }

  /**
   * @when did we last send pex peers?
   * @since 0.9.63
   */
  public void setPexLastSent(long now) {
      pexLastSent = now;
  }
}
