package net.i2p.sam.client;

import gnu.getopt.Getopt;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.stat.RateConstants;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.VersionComparator;

import java.nio.charset.StandardCharsets;
/**
 * Swiss army knife tester.
 * Sends a file (datafile) to a peer (b64 dest in peerDestFile).
 * <p>
 * Usage: SAMStreamSend [options] peerDestFile dataFile
 * <p>
 * See apps/sam/doc/README-test.txt for info on test setup.
 * Sends data in one of 5 modes.
 * Optionally uses SSL.
 * Configurable SAM client version.
 *
 */
public class SAMStreamSend {
    /** The app context. */
    private final I2PAppContext _context;
    /** Logger for the SAM client. */
    private final Log _log;
    /** Host of the SAM bridge. */
    private final String _samHost;
    /** Port of the SAM bridge. */
    private final String _samPort;
    /** File holding the peer destination. */
    private final String _destFile;
    /** File holding the data to send. */
    private final String _dataFile;
    /** Extra connection options from the command line. */
    private String _conOptions;
    /** Reader for the SAM session replies. */
    private SAMReader _reader;
    /** Reader for the second master session stream. */
    private SAMReader _reader2;
    /** Whether SAM version 3 is used. */
    private boolean _isV3;
    /** Whether SAM version 3.2 or later is used. */
    private boolean _isV32;
    /** Session id used with SAM version 3. */
    private String _v3ID;

    /** Connection id (Integer) to peer (Flooder). */
    private final Map<String, Sender> _remotePeers;
    /** Factory for the SSL bridge connections. */
    private static I2PSSLSocketFactory sslSocketFactory;

    /** Mode number for stream send. */
    private static final int STREAM=0;
    /** Mode number for datagram send. */
    private static final int DG=1;
    /** Mode number for version 1 datagram send. */
    private static final int V1DG=2;
    /** Mode number for raw send. */
    private static final int RAW=3;
    /** Mode number for version 1 raw send. */
    private static final int V1RAW=4;
    /** Mode number for a master session. */
    private static final int MASTER=8;
    /** The usage message printed on bad arguments. */
    private static final String USAGE = "Usage: SAMStreamSend [-s] [-x] [-m mode] [-v version] [-b samHost] [-p samPort]\n" +
                                        "                     [-o opt=val] [-u user] [-w password] peerDestFile dataDir\n" +
                                        "       modes: stream: 0; datagram: 1; v1datagram: 2; raw: 3; v1raw: 4\n" +
                                        "              default is stream\n" +
                                        "       -s: use SSL\n" +
                                        "       -x: use master session (forces -v 3.3)\n" +
                                        "       multiple -o session options are allowed";

    /**
     * Streams a file over a SAM stream connect, for testing the bridge.
     *
     * @param args the command line options parsed by {@link Getopt}; the last two name the files
     */
    public static void main(String[] args) {
        Getopt g = new Getopt("SAM", args, "sxhb:m:o:p:u:v:w:");
        boolean isSSL = false;
        boolean isMaster = false;
        int mode = STREAM;
        String version = "3.3";
        String host = "127.0.0.1";
        String port = "7656";
        String user = null;
        String password = null;
        StringBuilder optsBuf = new StringBuilder("inbound.length=0 outbound.length=0");
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 's':
                isSSL = true;
                break;

            case 'x':
                isMaster = true;
                break;

            case 'm':
                try {
                    mode = Integer.parseInt(g.getOptarg());
                } catch (NumberFormatException nfe) {
                    System.err.println(USAGE);
                    return;
                }
                if (mode < 0 || mode > V1RAW) {
                    System.err.println(USAGE);
                    return;
                }
                break;

            case 'v':
                version = g.getOptarg();
                break;

            case 'b':
                host = g.getOptarg();
                break;

            case 'o':
                optsBuf.append(' ').append(g.getOptarg());
                break;

            case 'p':
                port = g.getOptarg();
                break;

            case 'u':
                user = g.getOptarg();
                break;

            case 'w':
                password = g.getOptarg();
                break;

            case 'h':
            case '?':
            case ':':
            default:
                System.err.println(USAGE);
                return;
          }  // switch
        } // while

        int startArgs = g.getOptind();
        if (args.length - startArgs != 2) {
            System.err.println(USAGE);
            return;
        }
        if (isMaster) {
            mode += MASTER;
            version = "3.3";
        }
        if ((user == null && password != null) ||
            (user != null && password == null)) {
            System.err.println("both user and password or neither");
            return;
        }
        if (user != null && password != null && VersionComparator.comp(version, "3.2") < 0) {
            System.err.println("user/password require 3.2");
            return;
        }
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        SAMStreamSend sender = new SAMStreamSend(ctx, host, port,
                                                      args[startArgs], args[startArgs + 1]);
        sender.startup(version, isSSL, mode, user, password, optsBuf.toString());
    }

    /**
     * Holds the command line arguments for one stream send.
     *
     * @param ctx the application context supplying the log manager
     * @param samHost the host name or address of the SAM bridge to connect to
     * @param samPort the SAM bridge port, as a string because it comes from the command line
     * @param destFile path to the file holding the destination private key to send from
     * @param dataFile path to the file whose bytes are streamed over the resulting tunnel
     */
    public SAMStreamSend(I2PAppContext ctx, String samHost, String samPort, String destFile, String dataFile) {
        _context = ctx;
        _log = ctx.logManager().getLog(SAMStreamSend.class);

        _samHost = samHost;
        _samPort = samPort;
        _destFile = destFile;
        _dataFile = dataFile;
        _conOptions = "";
        _remotePeers = new HashMap<>();
    }

    /**
     * Negotiates a SAM session and connects it to the destination stream.
     *
     * @param version the SAM protocol version to negotiate, at least 3.2 for user and password
     * @param isSSL true to connect to the bridge over TLS rather than plaintext
     * @param mode the stream connect mode, one of STREAM, PACKET or STREAM_RAW, optionally
     *             or'ed with MASTER to use a master session
     * @param user the bridge username, or null when the bridge requires no authentication
     * @param password the password matching {@code user}, or null for no authentication
     * @param sessionOpts space separated session options appended to the STREAM CONNECT command
     */
    public void startup(String version, boolean isSSL, int mode, String user, String password, String sessionOpts) {
        if (_log.shouldDebug())
            _log.debug("Starting up");
        try {
            Socket sock = connect(isSSL);
            SAMEventHandler eventHandler = new SendEventHandler(_context);
            _reader = new SAMReader(_context, sock.getInputStream(), eventHandler);
            _reader.startReading();
            if (_log.shouldDebug())
                _log.debug("Reader created");
            // codeql[java/unsafe-cert-trust] writing over the hostname-verified TLS socket from I2PSSLSocketFactory
            OutputStream out = sock.getOutputStream();
            String ourDest = handshake(out, version, true, eventHandler, mode, user, password, sessionOpts);
            if (mode >= MASTER)
                mode -= MASTER;
            if (ourDest == null)
                throw new IOException("handshake failed");
            if (_log.shouldDebug())
                _log.debug("Handshake complete.  we are " + ourDest);
            if (_isV3 && mode == STREAM) {
                Socket sock2 = connect(isSSL);
                eventHandler = new SendEventHandler(_context);
                _reader2 = new SAMReader(_context, sock2.getInputStream(), eventHandler);
                _reader2.startReading();
                if (_log.shouldDebug())
                    _log.debug("Reader2 created");
                // codeql[java/unsafe-cert-trust] writing over the hostname-verified TLS socket from I2PSSLSocketFactory
                out = sock2.getOutputStream();
                String ok = handshake(out, version, false, eventHandler, mode, user, password, "");
                if (ok == null)
                    throw new IOException("2nd handshake failed");
                if (_log.shouldDebug())
                    _log.debug("Handshake2 complete");
            }
            if (mode == DG || mode == RAW)
                out = null;
            send(out, eventHandler, mode);
        } catch (IOException e) {
            _log.error("Unable to connect to SAM at " + _samHost + ":" + _samPort, e);
            if (_reader != null)
                _reader.stopReading();
            if (_reader2 != null)
                _reader2.stopReading();
        }
    }

    /** Handles session events for the send. */
    private class SendEventHandler extends SAMEventHandler {
        /**
         * Creates the handler that tracks stream closes reported by the bridge.
         *
         * @param ctx the app context for the handler
         */
        public SendEventHandler(I2PAppContext ctx) { super(ctx); }

        /**
         * Drops the sender the bridge has just reported closed.
         */
        @Override
        public void streamClosedReceived(String result, String id, String message) {
            Sender sender = null;
            synchronized (_remotePeers) {
                sender = _remotePeers.remove(id);
            }
            if (sender != null) {
                sender.closed();
                if (_log.shouldDebug())
                    _log.debug("Connection " + sender.getConnectionId() + " closed to " + sender.getDestination());
            } else {
                _log.error("Not connected to " + id + " but the connection was just closed?");
            }
        }
    }

    /**
     * Opens the connection to the SAM bridge.
     *
     * @param isSSL whether to connect over SSL
     * @return the connected socket
     * @throws IOException if the bridge cannot be reached
     */
    private Socket connect(boolean isSSL) throws IOException {
        int port;
        try {
            port = Integer.parseInt(_samPort);
        } catch (NumberFormatException nfe) {
            throw new IOException("Invalid SAM port: " + _samPort);
        }
        if (!isSSL)
            return new Socket(_samHost, port);
        synchronized(SAMStreamSink.class) {
            if (sslSocketFactory == null) {
                try {
                    sslSocketFactory = new I2PSSLSocketFactory(
                        _context, true, "certificates/sam");
                } catch (GeneralSecurityException gse) {
                    throw new IOException("SSL error", gse);
                }
            }
        }
        SSLSocket sock = (SSLSocket) sslSocketFactory.createSocket(_samHost, port);
        I2PSSLSocketFactory.verifyHostname(_context, sock, _samHost);
        return sock;
    }

    /**
     * Send the HELLO and log in.
     *
     * @param samOut the stream to write the HELLO and session commands to
     * @param version the maximum SAM version to request, e.g. "3.5"
     * @param isMaster is this the control socket
     * @param eventHandler the handler that waits for the HELLO reply and later events
     * @param mode the session mode, one of the STREAM, DG, V1DG or V1RAW
     *             constants, with the MASTER bit added for a master session
     * @param user the SAM user name to log in with, null if no authentication
     * @param password the SAM password to log in with, null if no authentication
     * @param opts extra SESSION CREATE options to append, may be empty
     * @return our b64 dest or null
     */
    private String handshake(OutputStream samOut, String version, boolean isMaster,
                             SAMEventHandler eventHandler, int mode, String user, String password,
                             String opts) {
        synchronized (this) {
            try {
                if (user != null && password != null)
                    samOut.write(("HELLO VERSION MIN=1.0 MAX=" + version + " USER=\"" + user.replace("\"", "\\\"") +
                                  "\" PASSWORD=\"" + password.replace("\"", "\\\"") + "\"\n").getBytes(StandardCharsets.UTF_8));
                else
                    samOut.write(("HELLO VERSION MIN=1.0 MAX=" + version + '\n').getBytes(StandardCharsets.UTF_8));
                samOut.flush();
                if (_log.shouldDebug())
                    _log.debug("Hello sent");
                String hisVersion = eventHandler.waitForHelloReply();
                if (_log.shouldDebug())
                    _log.debug("Hello reply found: " + hisVersion);
                if (hisVersion == null)
                    throw new IOException("Hello failed");
                if (!isMaster)
                    return "OK";
                _isV3 = VersionComparator.comp(hisVersion, "3") >= 0;
                if (_isV3) {
                    _isV32 = VersionComparator.comp(hisVersion, "3.2") >= 0;
                    byte[] id = new byte[5];
                    _context.random().nextBytes(id);
                    _v3ID = Base32.encode(id);
                    if (_isV32)
                        _v3ID = "xx€€xx" + _v3ID;
                    _conOptions = "ID=" + _v3ID;
                }
                boolean masterMode;  // are we using v3.3 master session
                String command;
                if (mode >= MASTER) {
                    masterMode = true;
                    command = "ADD";
                    mode -= MASTER;
                } else {
                    masterMode = false;
                    command = "CREATE DESTINATION=TRANSIENT";
                }
                String style;
                if (mode == STREAM)
                    style = "STREAM";
                else if (mode == DG || mode == V1DG)
                    style = "DATAGRAM";
                else   // RAW or V1RAW
                    style = "RAW";

                if (masterMode) {
                    if (mode == V1DG || mode == V1RAW)
                        throw new IllegalArgumentException("v1 dg/raw incompatible with master session");
                    String req = "SESSION CREATE DESTINATION=TRANSIENT STYLE=MASTER ID=masterSend " + opts + '\n';
                    samOut.write(req.getBytes(StandardCharsets.UTF_8));
                    samOut.flush();
                    if (_log.shouldDebug())
                        _log.debug("SESSION CREATE STYLE=MASTER sent");
                    boolean ok = eventHandler.waitForSessionCreateReply();
                    if (!ok)
                        throw new IOException("SESSION CREATE STYLE=MASTER failed");
                    if (_log.shouldDebug())
                        _log.debug("SESSION CREATE STYLE=MASTER reply found: " + ok);
                    // PORT required even if we aren't listening for this test
                    if (mode != STREAM)
                        opts += " PORT=9999";
                }
                String req = "SESSION " + command + " STYLE=" + style + ' ' + _conOptions + ' ' + opts + '\n';
                samOut.write(req.getBytes(StandardCharsets.UTF_8));
                samOut.flush();
                if (_log.shouldDebug())
                    _log.debug("SESSION " + command + " sent");
                boolean ok;
                if (masterMode)
                    ok = eventHandler.waitForSessionAddReply();
                else
                    ok = eventHandler.waitForSessionCreateReply();
                if (!ok)
                    throw new IOException("SESSION " + command + " failed");
                if (_log.shouldDebug())
                    _log.debug("SESSION " + command + " reply found: " + ok);

                if (masterMode) {
                    // do a bunch more
                    req = "SESSION ADD STYLE=STREAM FROM_PORT=99 ID=stream99\n";
                    samOut.write(req.getBytes(StandardCharsets.UTF_8));
                    req = "SESSION ADD STYLE=STREAM FROM_PORT=98 ID=stream98\n";
                    samOut.write(req.getBytes(StandardCharsets.UTF_8));
                    req = "SESSION REMOVE ID=stream99\n";
                    samOut.write(req.getBytes(StandardCharsets.UTF_8));
                    samOut.flush();
                }
                req = "NAMING LOOKUP NAME=ME\n";
                samOut.write(req.getBytes(StandardCharsets.UTF_8));
                samOut.flush();
                if (_log.shouldDebug())
                    _log.debug("Naming lookup sent");
                String destination = eventHandler.waitForNamingReply("ME");
                if (_log.shouldDebug())
                    _log.debug("Naming lookup reply found: " + destination);
                if (destination == null) {
                    _log.error("No naming lookup reply found!");
                    return null;
                } else {
                    _log.info("We are " + destination);
                }
                return destination;
            } catch (IOException e) {
                _log.error("Error handshaking", e);
                return null;
            }
        }
    }

    /**
     * Sends the data file over the SAM session.
     *
     * @param samOut the SAM command stream
     * @param eventHandler handler for the session events
     * @param mode the send mode
     * @throws IOException if the send fails
     */
    private void send(OutputStream samOut, SAMEventHandler eventHandler, int mode) throws IOException {
        Sender sender = new Sender(samOut, eventHandler, mode);
        boolean ok = sender.openConnection();
        if (ok) {
            I2PAppThread t = new I2PAppThread(sender, "SAM-Send");
            t.start();
        } else {
            throw new IOException("Sender failed to connect");
        }
    }

    /** One open SAM session sending data. */
    private class Sender implements Runnable {
        /** The SAM connection id. */
        private final String _connectionId;
        /** The destination being sent to. */
        private String _remoteDestination;
        /** Stream the data is read from. */
        private InputStream _in;
        /** Whether the session has been closed. */
        private volatile boolean _closed;
        /** When the session opened. */
        private long _started;
        /** Total bytes sent so far. */
        private long _totalSent;
        /** Stream for sending through this session. */
        private final OutputStream _samOut;
        /** Handler for this session's events. */
        private final SAMEventHandler _eventHandler;
        /** The send mode to run. */
        private final int _mode;
        /** Datagram socket for datagram modes. */
        private final DatagramSocket _dgSock;
        /** Bridge address for datagram sends. */
        private final InetSocketAddress _dgSAM;

        /**
         * Registers a sender with the stream, opening a datagram socket for datagram modes.
         *
         * @param samOut the SAM output stream for stream modes, null for datagram modes
         * @param eventHandler the handler tracking events for this stream
         * @param mode the SAM mode, stream, datagram or raw
         * @throws IOException if the datagram socket cannot be opened
         */
        public Sender(OutputStream samOut, SAMEventHandler eventHandler, int mode) throws IOException {
            _samOut = samOut;
            _eventHandler = eventHandler;
            _mode = mode;
            if (mode == DG || mode == RAW) {
                // samOut will be null
                _dgSock = new DatagramSocket();
                _dgSAM = new InetSocketAddress(_samHost, 7655);
            } else {
                _dgSock = null;
                _dgSAM = null;
            }
            synchronized (_remotePeers) {
                if (_v3ID != null)
                    _connectionId = _v3ID;
                else
                    _connectionId = Integer.toString(_remotePeers.size() + 1);
                _remotePeers.put(_connectionId, Sender.this);
            }
        }

        /**
         * Reads the destination key from disk and issues STREAM CONNECT.
         *
         * @return true if the bridge reported the stream connected, false on any error
         */
        public boolean openConnection() {
            InputStream fin = null;
            try {
                fin = new BufferedInputStream(new FileInputStream(_destFile));
                byte[] dest = new byte[1024];
                int read = DataHelper.read(fin, dest);

                _remoteDestination = DataHelper.getUTF8(dest, 0, read);

                _context.statManager().createRateStat("send." + _connectionId + ".totalSent", "Data size sent", "swarm", new long[] { RateConstants.ONE_MINUTE });
                _context.statManager().createRateStat("send." + _connectionId + ".started", "When we start", "swarm", new long[] { RateConstants.ONE_MINUTE });
                _context.statManager().createRateStat("send." + _connectionId + ".lifetime", "How long we talk to a peer", "swarm", new long[] { RateConstants.ONE_MINUTE });

                if (_mode == STREAM) {
                    StringBuilder buf = new StringBuilder(1024);
                    buf.append("STREAM CONNECT ID=").append(_connectionId).append(" DESTINATION=").append(_remoteDestination);
                    // not supported until 3.2 but 3.0-3.1 will ignore
                    if (_isV3)
                        buf.append(" FROM_PORT=1234 TO_PORT=5678");
                    buf.append('\n');
                    byte[] msg = DataHelper.getUTF8(buf.toString());
                    synchronized (_samOut) {
                        _samOut.write(msg);
                        _samOut.flush();
                    }
                    _log.debug("STREAM CONNECT sent, waiting for STREAM STATUS");
                    boolean ok = _eventHandler.waitForStreamStatusReply();
                    if (!ok)
                        throw new IOException("STREAM CONNECT failed");
                }

                _in = new BufferedInputStream(new FileInputStream(_dataFile));
                return true;
            } catch (IOException ioe) {
                _log.error("Unable to connect", ioe);
                return false;
            } finally {
                if(fin != null) {
                    try {
                        fin.close();
                    } catch(IOException ioe) { /* ignored */ }
                }
            }
        }

        /**
         * SAM connection id.
         *
         * @return the connection id
         */
        public String getConnectionId() { return _connectionId; }
        /**
         * Remote destination of the stream.
         *
         * @return the remote destination read from the dest file, or null before
         * the connection is opened
         */
        public String getDestination() { return _remoteDestination; }

        /**
         * Closes the stream once and records its lifetime in the statistics.
         */
        public void closed() {
            if (_closed) return;
            _closed = true;
            long lifetime = _context.clock().now() - _started;
            _context.statManager().addRateData("send." + _connectionId + ".lifetime", lifetime, lifetime);
            try { _in.close(); } catch (IOException ioe) { /* ignored */ }
        }

        /**
         * Sends the data file over the stream in chunks until it ends or closes.
         */
        public void run() {
            _started = _context.clock().now();
            _context.statManager().addRateData("send." + _connectionId + ".started", 1, 0);
            final long toSend = (new File(_dataFile)).length();
            byte[] data = new byte[8192];
            long lastSend = _context.clock().now();
            while (!_closed) {
                try {
                    int read = _in.read(data);
                    long now = _context.clock().now();
                    if (read == -1) {
                        if (_log.shouldDebug())
                            _log.debug("EOF from the data for " + _connectionId + " after " + (now-lastSend));
                        break;
                    } else if (read > 0) {
                        if (_log.shouldDebug())
                            _log.debug("Sending " + read + " on " + _connectionId + " after " + (now-lastSend));
                        lastSend = now;

                        if (_samOut != null) {
                            synchronized (_samOut) {
                                if (!_isV3 || _mode == V1DG || _mode == V1RAW) {
                                    String m;
                                    if (_mode == STREAM) {
                                        m = "STREAM SEND ID=" + _connectionId + " SIZE=" + read + "\n";
                                    } else if (_mode == V1DG) {
                                        m = "DATAGRAM SEND DESTINATION=" + _remoteDestination + " SIZE=" + read + "\n";
                                    } else if (_mode == V1RAW) {
                                        m = "RAW SEND DESTINATION=" + _remoteDestination + " SIZE=" + read + "\n";
                                    } else {
                                        throw new IOException("unsupported mode " + _mode);
                                    }
                                    byte[] msg = DataHelper.getUTF8(m);
                                    _samOut.write(msg);
                                }
                                _samOut.write(data, 0, read);
                                _samOut.flush();
                            }
                        } else {
                            // real datagrams
                            ByteArrayOutputStream baos = new ByteArrayOutputStream(read + 1024);
                            baos.write(DataHelper.getUTF8("3.0 "));
                            baos.write(DataHelper.getUTF8(_v3ID));
                            baos.write((byte) ' ');
                            baos.write(DataHelper.getUTF8(_remoteDestination));
                            if (_isV32) {
                                // only set TO_PORT to test session setting of FROM_PORT
                                if (_mode == RAW)
                                    baos.write(DataHelper.getUTF8(" PROTOCOL=123 TO_PORT=5678"));
                                else
                                    baos.write(DataHelper.getUTF8(" TO_PORT=5678"));
                                baos.write(DataHelper.getUTF8(" SEND_TAGS=19 TAG_THRESHOLD=13 EXPIRES=33 SEND_LEASESET=true"));
                            }
                            baos.write((byte) '\n');
                            baos.write(data, 0, read);
                            // codeql[java/dereferenced-value-may-be-null] baos is unconditionally constructed above; toByteArray() never returns null
                            byte[] pkt = baos.toByteArray();
                            DatagramPacket p = new DatagramPacket(pkt, pkt.length, _dgSAM);
                            _dgSock.send(p);
                            try {
                                Thread.sleep(25);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        _totalSent += read;
                        _context.statManager().addRateData("send." + _connectionId + ".totalSent", _totalSent, 0);
                    }
                } catch (IOException ioe) {
                    _log.error("Error sending", ioe);
                    break;
                }
            }

            if (_samOut != null) {
                if (_isV3) {
                    try {
                        _samOut.close();
                    } catch (IOException ioe) {
                        _log.info("Error closing", ioe);
                    }
                } else {
                    try {
                        byte[] msg = ("STREAM CLOSE ID=" + _connectionId + "\n").getBytes(StandardCharsets.UTF_8);
                        synchronized (_samOut) {
                            _samOut.write(msg);
                            _samOut.flush();
                            // we can't close this yet, we will lose data
                        }
                    } catch (IOException ioe) {
                        _log.info("Error closing", ioe);
                    }
                }
            } else if (_dgSock != null) {
                _dgSock.close();
            }

            closed();
            // stop the reader, since we're only doing this once for testing
            // you wouldn't do this in a real application
            // closing the master socket too fast will kill the data socket flushing through
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (_log.shouldDebug())
                _log.debug("Runner exiting");
            if (toSend != _totalSent)
                _log.error("Only sent " + _totalSent + " of " + toSend + " bytes");
            if (_reader2 != null)
                _reader2.stopReading();
            _reader.stopReading();
        }
    }
}
