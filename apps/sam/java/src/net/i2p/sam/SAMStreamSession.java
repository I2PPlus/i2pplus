package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.ByteCache;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * SAM STREAM session class.
 *
 * @author human
 */
class SAMStreamSession implements SAMMessageSess {

    protected final Log _log;
    protected final static int SOCKET_HANDLER_BUF_SIZE = 32768;
    protected final SAMStreamReceiver recv;
    protected final SAMStreamSessionServer server;
    protected final I2PSocketManager socketMgr;

    /** stream id (Long) to SAMStreamSessionSocketReader */
    private final HashMap<Integer,SAMStreamSessionSocketReader> handlersMap = new HashMap<Integer,SAMStreamSessionSocketReader>();
    /** stream id (Long) to StreamSender */
    private final HashMap<Integer,StreamSender> sendersMap = new HashMap<Integer,StreamSender>();

    private final AtomicInteger lastNegativeId = new AtomicInteger();

    // Can we create outgoing connections?
    protected final boolean canCreate;
    private final int listenProtocol;
    private final int listenPort;
    protected final boolean _isOwnSession;

    /**
     * should we flush every time we get a STREAM SEND, or leave that up to
     * the streaming lib to decide?
     */
    protected final boolean forceFlush;

    public static final String PROP_FORCE_FLUSH = "sam.forceFlush";
    public static final String DEFAULT_FORCE_FLUSH = "false";

    /**
     * Create a new SAM STREAM session.
     *
     * Caller MUST call start().
     *
     * @param dest Base64-encoded destination and private keys,
     *             and optional offline signature section (same format as PrivateKeyFile)
     * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH") or "__v3__" if extended by SAMv3StreamSession
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws SAMException
     */
    public SAMStreamSession(String dest, String dir, Properties props,
                            SAMStreamReceiver recv) throws IOException, DataFormatException, SAMException {
        this(new ByteArrayInputStream(Base64.decode(dest)), dir, props, recv);
    }

    /**
     * Create a new SAM STREAM session.
     *
     * Caller MUST call start().
     *
     * @param destStream Input stream containing the binary destination and private keys,
     *                   and optional offline signature section (same format as PrivateKeyFile)
     * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH") or "__v3__" if extended by SAMv3StreamSession
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws SAMException
     */
    protected SAMStreamSession(InputStream destStream, String dir,
                               Properties props,  SAMStreamReceiver recv) throws IOException, DataFormatException, SAMException {
        this.recv = recv;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());

        boolean canReceive;
        boolean startAcceptor;
        if (dir.equals("BOTH")) {
            canCreate = true;
            canReceive = true;
            startAcceptor = true;
        } else if (dir.equals("__v3__")) {
            // we are super to SAMv3StreamSession, don't start thread, he handles it
            canCreate = true;
            canReceive = true;
            startAcceptor = false;
        } else if (dir.equals("CREATE")) {
            canCreate = true;
            canReceive = false;
            startAcceptor = false;
        } else if (dir.equals("RECEIVE")) {
            canCreate = false;
            canReceive = true;
            startAcceptor = true;
        } else {
            _log.error("BUG! Wrong direction passed to SAMStreamSession: " + dir);
            throw new SAMException("BUG! Wrong direction specified!");
        }

        if (_log.shouldDebug())
            _log.debug("SAM STREAM session instantiated");

        Properties allprops = (Properties) System.getProperties().clone();
        allprops.putAll(props);

        String i2cpHost = allprops.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
        int i2cpPort = I2PClient.DEFAULT_LISTEN_PORT;
        String sport = allprops.getProperty(I2PClient.PROP_TCP_PORT);
        if (sport != null) {
            try {
                i2cpPort = Integer.parseInt(sport);
            } catch (NumberFormatException nfe) {
                throw new SAMException("Invalid I2CP port specified [" + sport + "]");
            }
        }
        if (!canReceive)
            allprops.setProperty("i2cp.dontPublishLeaseSet", "true");
        String name = allprops.getProperty("inbound.nickname");
        if (name == null || name.trim().isEmpty()) {
            //name = "SAM TCP Client";
            name = (getClass().getSimpleName().equals("PrimarySession") || getClass().getSimpleName().equals("MasterSession")) ? "SAM Mux Client" : "SAM TCP Client";
            allprops.setProperty("inbound.nickname", name);
        }
        String name2 = allprops.getProperty("outbound.nickname");
        if (name2 == null || name2.trim().isEmpty())
            allprops.setProperty("outbound.nickname", name);

        _isOwnSession = true;
        if (_log.shouldDebug())
            _log.debug("Creating I2PSocketManager...");
        try {
            // we do it this way so we get exceptions
            socketMgr = I2PSocketManagerFactory.createDisconnectedManager(destStream,
                                                            i2cpHost, i2cpPort, allprops);
            socketMgr.getSession().connect();
        } catch (I2PSessionException ise) {
            throw new SAMException("Error creating I2PSocketManager: " + ise.getMessage(), ise);
        }

        socketMgr.addDisconnectListener(new DisconnectListener());

        forceFlush = Boolean.parseBoolean(allprops.getProperty(PROP_FORCE_FLUSH, DEFAULT_FORCE_FLUSH));

        if (Boolean.parseBoolean(props.getProperty("i2p.streaming.enforceProtocol")))
            listenProtocol = I2PSession.PROTO_STREAMING;
        else
            listenProtocol = I2PSession.PROTO_ANY;
        listenPort = I2PSession.PORT_ANY;


        if (startAcceptor) {
            server = new SAMStreamSessionServer();
        } else {
            server = null;
        }
    }

    /**
     * Create a new SAM STREAM session on an existing socket manager.
     * v3 only.
     *
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     * @throws IOException
     * @throws DataFormatException
     * @throws SAMException
     * @since 0.9.25
     */
    protected SAMStreamSession(I2PSocketManager mgr, Properties props, SAMStreamReceiver recv, int listenport)
                               throws IOException, DataFormatException, SAMException {
        this.recv = recv;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        if (_log.shouldDebug())
            _log.debug("SAM STREAM session instantiated");
        canCreate = true;
        Properties allprops = (Properties) System.getProperties().clone();
        allprops.putAll(props);
        _isOwnSession = false;
        socketMgr = mgr;
        socketMgr.addDisconnectListener(new DisconnectListener());
        forceFlush = Boolean.parseBoolean(allprops.getProperty(PROP_FORCE_FLUSH, DEFAULT_FORCE_FLUSH));
        listenProtocol = I2PSession.PROTO_STREAMING;
        listenPort = listenport;
        server = null;
    }

    /*
     * @since 0.9.25
     */
    public void start() {
        if (server != null) {
            Thread t = new I2PAppThread(server, "SAMStreamSessionServer");
            t.start();
        }
    }

    /*
     * @since 0.9.25
     */
    public int getListenProtocol() {
        return listenProtocol;
    }

    /*
     * @since 0.9.25
     */
    public int getListenPort() {
        return listenPort;
    }

    protected class DisconnectListener implements I2PSocketManager.DisconnectListener {
        public void sessionDisconnected() {
            close();
        }
    }

    /**
     * Get the SAM STREAM session Destination.
     *
     * @return The SAM STREAM session Destination.
     */
    public Destination getDestination() {
        return socketMgr.getSession().getMyDestination();
    }

    /**
     * Connect the SAM STREAM session to the specified Destination
     *
     * @param id Unique id for the connection
     * @param dest Base64-encoded Destination to connect to
     * @param props Options to be used for connection
     *
     * @return true if successful
     * @throws DataFormatException if the destination is not valid
     * @throws SAMInvalidDirectionException if trying to connect through a
     *                                      receive-only session
     * @throws ConnectException if the destination refuses connections
     * @throws NoRouteToHostException if the destination can't be reached
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there's another I2P-related error
     * @throws IOException
     */
    public boolean connect ( int id, String dest, Properties props ) throws I2PException, ConnectException, NoRouteToHostException, DataFormatException, InterruptedIOException, SAMInvalidDirectionException, IOException {
        if (!canCreate) {
            if (_log.shouldDebug())
                _log.debug("Trying to create an outgoing connection using a receive-only session");
            throw new SAMInvalidDirectionException("Trying to create connections through a receive-only session");
        }

        if (checkSocketHandlerId(id)) {
            if (_log.shouldDebug())
                _log.debug("The specified id (" + id + ") is already in use");
            return false;
        }

        Destination d = SAMUtils.getDest(dest);

        I2PSocketOptions opts = socketMgr.buildOptions(props);
        if (props.getProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT) == null)
            opts.setConnectTimeout(60 * 1000);

        if (_log.shouldDebug())
            _log.debug("Connecting new I2PSocket...");

	// blocking connection (SAMv1)

        I2PSocket i2ps = socketMgr.connect(d, opts);

        createSocketHandler(i2ps, id);

	recv.notifyStreamOutgoingConnection ( id, "OK", null );

        return true;
    }

    /**
     * Send bytes through a SAM STREAM session.
     *
     * @param id Stream Id
     * @param in Datastream input
     * @param size Count of bytes to send
     * @return True if the data was queued for sending, false otherwise
     * @throws IOException
     */
    public boolean sendBytes(int id, InputStream in, int size) throws IOException {
        StreamSender sender = getSender(id);

        if (sender == null) {
            if (_log.shouldWarn())
                _log.warn("Trying to send bytes through nonexistent handler " +id);
            // even though it failed, we need to read those bytes!
            for (int i = 0; i < size; i++) {
                int c = in.read();
                if (c == -1)
                    break;
            }
            return false;
        }

        sender.sendBytes(in, size);
        return true;
    }

    /**
     * Close a SAM STREAM session.
     *
     */
    public void close() {
        if (server != null) {
            server.stopRunning();
        }
        removeAllSocketHandlers();
        recv.stopStreamReceiving();
        if (_isOwnSession)
            socketMgr.destroySocketManager();
    }

    /**
     * Is the underlying streaming socket manager destroyed?
     *
     * @since 0.9.61
     */
    public boolean isDestroyed() { return socketMgr.isDestroyed(); }

    /**
     * Close a connection managed by the SAM STREAM session.
     *
     * @param id Connection id
     * @return true on success
     */
    public boolean closeConnection(int id) {
        if (!checkSocketHandlerId(id)) {
            if (_log.shouldDebug())
                _log.debug("The specified id (" + id + ") does not exist!");
            return false;
        }
        removeSocketHandler(id);

        return true;
    }

    /**
     *  Unsupported
     *  @throws I2PSessionException always
     *  @since 0.9.25 moved from subclass SAMv3StreamSession to implement SAMMessageSess
     */
    public boolean sendBytes(String s, byte[] b, int pr, int fp, int tp) throws I2PSessionException {
        throw new I2PSessionException("Unsupported in STREAM or MASTER session");
    }

    /**
     *  Unsupported
     *  @throws I2PSessionException always
     *  @since 0.9.25
     */
    public boolean sendBytes(String s, byte[] b, int pr, int fp, int tp,
                             boolean sendLeaseSet, int sendTags,
                             int tagThreshold, int expiration)
                                 throws I2PSessionException {
        throw new I2PSessionException("Unsupported in STREAM or MASTER session");
    }

    /**
     * Create a new SAM STREAM session socket handler, detaching its thread.
     *
     * @param s Socket to be handled
     * @param id Socket id, or 0 if it must be auto-generated
     *
     * @return An id associated to the socket handler
     */
    protected int createSocketHandler ( I2PSocket s, int id ) {
        SAMStreamSessionSocketReader reader;
        StreamSender sender;
        if (id == 0) {
            id = createUniqueId();
        }

        try {
            reader = newSAMStreamSessionSocketReader(s, id);
            sender = newStreamSender(s, id);
        } catch (IOException e) {
            _log.error("IOException when creating SAM STREAM session socket handler", e);
            recv.stopStreamReceiving();
            return 0;
        }

        synchronized (handlersMap) {
            handlersMap.put(Integer.valueOf(id), reader);
            sendersMap.put(Integer.valueOf(id), sender);
        }

        I2PAppThread t = new I2PAppThread(reader, "SAMReader" + id);
        t.start();
        t = new I2PAppThread(sender, "SAMSender" + id);
        t.start();

        return id;
    }

    /* Create an unique id, either positive or negative */
    private int createUniqueId() {
        return lastNegativeId.decrementAndGet();
    }

    /**
     * Get a SAM STREAM session socket handler.
     *
     * @param id Handler id
     * @return SAM StreamSender handler
     */
    protected SAMStreamSessionSocketReader getSocketReader ( int id ) {
        synchronized (handlersMap) {
            return handlersMap.get(Integer.valueOf(id));
        }
    }
    private StreamSender getSender(int id) {
        synchronized (handlersMap) {
            return sendersMap.get(Integer.valueOf(id));
        }
    }

    /**
     * Check whether a SAM STREAM session socket handler id is still in use.
     *
     * @param id Handler id
     * @return True if in use
     */
    protected boolean checkSocketHandlerId ( int id ) {
        synchronized (handlersMap) {
            return (!(handlersMap.get(Integer.valueOf(id)) == null));
        }
    }

    /**
     * Remove and gracefully close a SAM STREAM session socket handler.
     *
     * @param id Handler id to be removed
     */
    protected void removeSocketHandler ( int id ) {
        SAMStreamSessionSocketReader reader;
        StreamSender sender;

        synchronized (handlersMap) {
            Integer iid = Integer.valueOf(id);
            reader = handlersMap.remove(iid);
            sender = sendersMap.remove(iid);
        }

        if (reader != null)
            reader.stopRunning();
        if (sender != null)
            sender.shutDownGracefully();
        if (_log.shouldDebug())
            _log.debug("Removed SAM STREAM session socket handler (gracefully) " + id);
    }

    /**
     * Remove and hard close all the socket handlers managed by this SAM
     * STREAM session.
     *
     */
    private void removeAllSocketHandlers() {
        synchronized (handlersMap) {
            for (Map.Entry<Integer, SAMStreamSessionSocketReader> e : handlersMap.entrySet()) {
                 Integer id = e.getKey();
                 e.getValue().stopRunning();
                 sendersMap.get(id).shutDownGracefully();
            }
            handlersMap.clear();
            sendersMap.clear();
        }
    }

    /**
     * SAM STREAM session server, running in its own thread.  It will
     * wait for incoming connections from the I2P network.
     *
     * @author human
     */
    public class SAMStreamSessionServer implements Runnable {

        private final Object runningLock = new Object();
        private volatile boolean stillRunning = true;

        private final I2PServerSocket serverSocket;

        /**
         * Create a new SAM STREAM session server
         *
         */
        public SAMStreamSessionServer() {
            if (_log.shouldDebug())
                _log.debug("Instantiating new SAM STREAM session server");

            serverSocket = socketMgr.getServerSocket();
        }

        /**
         * Stop a SAM STREAM session server
         *
         */
        public void stopRunning() {
            if (_log.shouldDebug())
                _log.debug("SAMStreamSessionServer.stopRunning() invoked");
            synchronized (runningLock) {
                if (stillRunning) {
                    stillRunning = false;
                    try {
                        serverSocket.close();
                    } catch (I2PException e) {
                        _log.error("I2PException caught", e);
                    }
                }
            }
        }

        public void run() {
            if (_log.shouldDebug())
                _log.debug("SAM STREAM session server running");
            I2PSocket i2ps;

            while (stillRunning) {
                try {
                    i2ps = serverSocket.accept();
                    if (i2ps == null)
                        break;

                   if (_log.shouldDebug())
                        _log.debug("New incoming connection");

                    int id = createSocketHandler(i2ps, 0);
                    if (id == 0) {
                        _log.error("SAM STREAM session handler not created!");
                        i2ps.reset();
                        continue;
                    }

                    if (_log.shouldDebug())
                        _log.debug("New connection id: " + id);

                    recv.notifyStreamIncomingConnection ( id, i2ps.getPeerDestination() );
                } catch (I2PException e) {
                    if (_log.shouldDebug())
                        _log.debug("Caught I2PException", e);
                    break;
                } catch (IOException e) {
                    if (_log.shouldDebug())
                        _log.debug("Caught IOException", e);
                    break;
                }
            }

            try {
                serverSocket.close(); // In case it wasn't closed, yet
            } catch (I2PException e) {
                if (_log.shouldDebug())
                    _log.debug("Caught I2PException", e);
            }

            close();

            if (_log.shouldDebug())
                _log.debug("Shutting down SAM STREAM session server");
        }

    }


    boolean setReceiveLimit ( int id, long limit, boolean nolimit )
    {
        if (_log.shouldDebug())
            _log.debug ( "Protocol v1 does not support a receive limit for streams" );
	return false ;
    }

    /**
     * SAM STREAM socket reader, running in its own thread.  It forwards
     * forward data to/from an I2P socket.
     *
     * @author human
     */
    public class SAMStreamSessionSocketReader implements Runnable {

        protected final I2PSocket i2pSocket;

        protected final Object runningLock = new Object();

        protected volatile boolean stillRunning = true;

        protected final int id;

        /**
         * Create a new SAM STREAM session socket reader
         *
         * @param s Socket to be handled
	 * @param id Unique id assigned to the handler
	 * @throws IOException
         */
        public SAMStreamSessionSocketReader ( I2PSocket s, int id ) throws IOException {
            i2pSocket = s;
            this.id = id;
	}

        /**
         * Stop a SAM STREAM session socket reader thread immediately.
         */
        public void stopRunning() {}

        public void run() {}

    }

    protected SAMStreamSessionSocketReader
      newSAMStreamSessionSocketReader ( I2PSocket s, int id ) throws IOException {
        return new SAMv1StreamSessionSocketReader ( s, id );
    }

    public class SAMv1StreamSessionSocketReader extends SAMStreamSessionSocketReader {
        /**
         * Create a new SAM STREAM session socket reader
         *
         * @param s Socket to be handled
	 * @param id Unique id assigned to the handler
	 * @throws IOException
         */

        public SAMv1StreamSessionSocketReader ( I2PSocket s, int id ) throws IOException {
            super(s, id);
            if (_log.shouldDebug())
                _log.debug("Instantiating new SAM STREAM session socket reader");
        }

        /**
         * Stop a SAM STREAM session socket reader thead immediately.
         *
         */
        @Override
        public void stopRunning() {
            if (_log.shouldDebug())
                _log.debug("stopRunning() invoked on socket reader " + id);
            synchronized (runningLock) {
                if (stillRunning) {
                    stillRunning = false;
                }
                runningLock.notifyAll() ;
            }
        }

        @Override
        public void run() {
            if (_log.shouldDebug())
                _log.debug("run() called for socket reader " + id);

            int read = -1;
            ByteBuffer data = ByteBuffer.allocate(SOCKET_HANDLER_BUF_SIZE);

            try {
                InputStream in = i2pSocket.getInputStream();

                while (stillRunning) {
                    ((Buffer)data).clear();
                    read = Channels.newChannel(in).read(data);
                    if (read == -1) {
                        if (_log.shouldDebug())
                            _log.debug("Handler " + id + ": connection closed");
                        break;
                    }
                    // not ByteBuffer to avoid Java 8/9 issues with flip()
                    ((Buffer)data).flip();
                    recv.receiveStreamBytes(id, data);
                }
            } catch (IOException e) {
                if (_log.shouldDebug())
                    _log.debug("Caught IOException", e);
            }

            try {
                i2pSocket.close();
            } catch (IOException e) {
                if (_log.shouldDebug())
                    _log.debug("Caught IOException", e);
            }

            if (stillRunning) {
                removeSocketHandler(id);
                // FIXME: we need error reporting here!
                try {
                    recv.notifyStreamDisconnection(id, "OK", null);
                } catch (IOException e) {
                    if (_log.shouldDebug())
                        _log.debug("Error sending disconnection notice for handler "
                               + id, e);
                }
            }

            if (_log.shouldDebug())
                _log.debug("Shutting down SAM STREAM session socket handler " +id);
        }
    }


    /**
     * Lets us push data through the stream without blocking, (even after exceeding
     * the I2PSocket's buffer)
     */
    protected static abstract class StreamSender implements Runnable {

        protected final int _id;
        protected final I2PSocket i2pSocket;

        public StreamSender ( I2PSocket s, int id ) throws IOException {
            _id = id;
            i2pSocket = s;
	}

        /**
	 * Send bytes through the SAM STREAM session socket sender
	 *
	 * @param in Data input stream
	 * @param size Count of bytes to send
	 * @throws IOException if the client didnt provide enough data
	 */
        public abstract void sendBytes ( InputStream in, int size ) throws IOException;


        /**
	 * Stop a SAM STREAM session socket sender thread immediately
	 *
	 */
        public abstract void stopRunning();

        /**
	 * Stop a SAM STREAM session socket sender gracefully: stop the
	 * sender thread once all pending data has been sent.
	 */
        public abstract void shutDownGracefully();

        public abstract void run();
    }

    protected StreamSender newStreamSender ( I2PSocket s, int id ) throws IOException {
      return new V1StreamSender ( s, id ) ;
    }

    private class V1StreamSender extends StreamSender
      {
        private final List<ByteArray> _data;
        private final ByteCache _cache;
        private final OutputStream _out;
        private volatile boolean _stillRunning, _shuttingDownGracefully;
        private final Object runningLock = new Object();

	public V1StreamSender ( I2PSocket s, int id ) throws IOException {
	    super ( s, id );
            _data = new ArrayList<ByteArray>(1);
            _cache = ByteCache.getInstance(4, 32*1024);
            _out = s.getOutputStream();
            _stillRunning = true;
        }

        /**
         * Send bytes through the SAM STREAM session socket sender
         *
         * @throws IOException if the client didnt provide enough data
         */
        @Override
        public void sendBytes(InputStream in, int size) throws IOException {
            if (_log.shouldDebug())
                _log.debug("Handler " + _id + ": sending " + size + " bytes");

            ByteArray ba = _cache.acquire();
            int read = DataHelper.read(in, ba.getData(), 0, size);
            if (read != size)
                throw new IOException("Insufficient data from the SAM client (" + read + "/" + size + ")");

            ba.setValid(read);
            synchronized (_data) {
                _data.add(ba);
                _data.notifyAll();
            }
        }

        /**
         * Stop a SAM STREAM session socket sender thread immediately
         *
         */
        @Override
        public void stopRunning() {
            if (_log.shouldDebug())
                _log.debug("stopRunning() invoked on socket sender " + _id);
            synchronized (runningLock) {
                if (_stillRunning) {
                    _stillRunning = false;
                    try {
                        i2pSocket.close();
                    } catch (IOException e) {
                        if (_log.shouldDebug())
                            _log.debug("Caught IOException", e);
                    }
                    synchronized (_data) {
                        _data.clear();
                        _data.notifyAll();
                    }
                }
            }
        }

        /**
         * Stop a SAM STREAM session socket sender gracefully: stop the
         * sender thread once all pending data has been sent.
         */
        @Override
        public void shutDownGracefully() {
            if (_log.shouldDebug())
                _log.debug("shutDownGracefully() invoked on socket sender " + _id);
            _shuttingDownGracefully = true;
        }

        @Override
        public void run() {
            if (_log.shouldDebug())
                _log.debug("run() called for socket sender " + _id);
            ByteArray data = null;
            while (_stillRunning) {
                data = null;
                try {
                    synchronized (_data) {
                        if (!_data.isEmpty()) {
                            data = _data.remove(0);
                        } else if (_shuttingDownGracefully) {
                            /* No data left and shutting down gracefully?
                               If so, stop the sender. */
                            stopRunning();
                            break;
                        } else {
                            /* Wait for data. */
                            _data.wait(5000);
                        }
                    }

                    if (data != null) {
                        try {
                            _out.write(data.getData(), 0, data.getValid());
                            if (forceFlush) {
                                // i dont like doing this, but it clears the buffer issues
                                _out.flush();
                            }
                        } catch (IOException ioe) {
                            // ok, the stream failed, but the SAM client didn't
                            if (_log.shouldWarn())
                                _log.warn("Stream failed", ioe);

                            removeSocketHandler(_id);
                            stopRunning();

                        } finally {
                            _cache.release(data);
                        }
                    }
                } catch (InterruptedException ie) {}
            }
            synchronized (_data) {
                _data.clear();
            }
        }
    }
}
