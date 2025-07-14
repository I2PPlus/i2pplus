/* PeerConnectionIn - Handles incomming messages and hands them to PeerState.
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;

import java.io.DataInputStream;
import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

class PeerConnectionIn implements Runnable {
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PeerConnectionIn.class);
    private final Peer peer;
    private final DataInputStream din;

    // The max length of a complete message in bytes.
    // The biggest is the piece message, for which the length is the
    // request size (32K) plus 9. (we could also check if Storage.MAX_PIECES / 8
    // in the bitfield message is bigger but it's currently 5000/8 = 625 so don't bother)
    private static final int MAX_MSG_SIZE = Math.max(PeerState.PARTSIZE + 9, MagnetState.CHUNK_SIZE + 100); // 100 for the ext msg dictionary

    private volatile Thread thread;
    private volatile boolean quit;

    long lastRcvd;

    public PeerConnectionIn(Peer peer, DataInputStream din) {
        this.peer = peer;
        this.din = din;
        lastRcvd = System.currentTimeMillis();
    }

    void disconnect() {
        if (quit) {return;}
        quit = true;
        Thread t = thread;
        if (t != null) {t.interrupt();}
        if (din != null) {
            try {din.close();}
            catch (IOException ioe) {}
        }
    }

    public void run() {
        thread = Thread.currentThread();
        try {
            while (!quit) {
                final PeerState ps = peer.state;
                if (ps == null) {break;}

                // Common variables used for some messages.
                int piece;
                int begin;
                int len;

                // Wait till we hear something...
                int i = din.readInt();
                lastRcvd = System.currentTimeMillis();
                if (i < 0 || i > MAX_MSG_SIZE) {throw new IOException("Unexpected length prefix: " + i);}

                if (i == 0) {
                    if (_log.shouldDebug()) {_log.debug("Received keepalive from [" + peer + "]");}
                    ps.keepAliveMessage();
                    continue;
                }

                byte b = din.readByte();
                switch (b) {
                    case Message.CHOKE:
                        if (_log.shouldDebug()) {
                            _log.debug("Received choke from [" + peer + "]");
                        }
                        ps.chokeMessage(true);
                        break;

                    case Message.UNCHOKE:
                        if (_log.shouldDebug()) {
                            _log.debug("Received unchoke from [" + peer + "]");
                        }
                        ps.chokeMessage(false);
                        break;

                    case Message.INTERESTED:
                        if (_log.shouldDebug()) {
                            _log.debug("Received interested from [" + peer +"]");
                        }
                        ps.interestedMessage(true);
                        break;

                    case Message.UNINTERESTED:
                        if (_log.shouldDebug()) {
                            _log.debug("Received not interested from [" + peer +"]");
                        }
                        ps.interestedMessage(false);
                        break;

                    case Message.HAVE:
                        piece = din.readInt();
                        if (_log.shouldDebug()) {
                            _log.debug("Received havePiece(" + piece + ") from [" + peer +"]");
                        }
                        ps.haveMessage(piece);
                        break;

                    case Message.BITFIELD:
                        byte[] bitmap = new byte[i-1];
                        din.readFully(bitmap);
                        if (_log.shouldDebug()) {
                            _log.debug("Received bitmap from [" + peer  + "] (Size: " + (i-1) + ")");
                        }
                        ps.bitfieldMessage(bitmap);
                        break;

                    case Message.REQUEST:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = din.readInt();
                        if (_log.shouldDebug()) {
                            _log.debug("Received request from [" + peer + "] for [Piece " + piece + "] (Start: " + begin + ")");
                        }
                        ps.requestMessage(piece, begin, len);
                        break;

                        case Message.PIECE:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = i-9;
                        Request req = ps.getOutstandingRequest(piece, begin, len);
                        if (req != null) {
                            req.read(din, peer);
                            if (_log.shouldDebug()) {
                                _log.debug("Received data(" + piece + "," + begin + ") from [" + peer +"]");
                            }
                            ps.pieceMessage(req);
                        } else {
                            // XXX - Consume but throw away afterwards.
                            int rcvd = din.skipBytes(len);
                            if (rcvd != len) {throw new IOException("EOF reading unwanted data");}
                            if (_log.shouldDebug()) {
                                _log.debug("Received UNWANTED data from [" + peer + "] for [Piece " + piece + "] (Start: " + begin + ")");
                            }
                        }
                        break;

                    case Message.CANCEL:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = din.readInt();
                        if (_log.shouldDebug()) {
                            _log.debug("Received cancel from [" + peer + "] for [Piece " + piece + "] (Start: " + begin + ")");
                        }
                        ps.cancelMessage(piece, begin, len);
                        break;

                    case Message.PORT:
                        int port = din.readUnsignedShort();
                        if (_log.shouldDebug()) {
                            _log.debug("Received port message from [" + peer +"]");
                        }
                        ps.portMessage(port);
                        break;

                    case Message.EXTENSION:
                        int id = din.readUnsignedByte();
                        byte[] payload = new byte[i-2];
                        din.readFully(payload);
                        if (_log.shouldDebug()) {
                            _log.debug("Received extension message from [" + peer + "]");
                        }
                        ps.extensionMessage(id, payload);
                        break;

                    // fast extensions below here
                    case Message.SUGGEST:
                        piece = din.readInt();
                        ps.suggestMessage(piece);
                        if (_log.shouldDebug()) {
                            _log.debug("Received suggest(" + piece + ") from [" + peer + "]");
                        }
                        break;

                    case Message.HAVE_ALL:
                        ps.haveMessage(true);
                        if (_log.shouldDebug()) {
                            _log.debug("Received have_all from [" + peer + "]");
                        }
                        break;

                    case Message.HAVE_NONE:
                        ps.haveMessage(false);
                        if (_log.shouldDebug()) {
                            _log.debug("Received have_none from [" + peer + "]");
                        }
                        break;

                    case Message.REJECT:
                        piece = din.readInt();
                        begin = din.readInt();
                        len = din.readInt();
                        ps.rejectMessage(piece, begin, len);
                        if (_log.shouldDebug()) {
                            _log.debug("Received reject from [" + peer + "] for [Piece " + piece + "] (Start: " +
                                        begin + " Length: " + len + " bytes)");
                        }
                        break;

                    case Message.ALLOWED_FAST:
                        piece = din.readInt();
                        ps.allowedFastMessage(piece);
                        if (_log.shouldDebug()) {
                            _log.debug("Received allowed_fast(" + piece + ") from [" + peer + "]");
                        }
                        break;

                    default:
                        byte[] bs = new byte[i-1];
                        din.readFully(bs);
                        ps.unknownMessage(b, bs);
                        if (_log.shouldDebug()) {
                            _log.debug("Received unknown message from [" + peer + "]");
                        }
                }
            }
        } catch (IOException ioe) {
            // Ignore, probably the other side closed connection.
            if (_log.shouldInfo()) {
                _log.info("IOError communicating with [" + peer + "] \n* Reason: " + ioe.getMessage());
            }
        } catch (RuntimeException t) {
            _log.error("Error communicating with [" + peer + "] \n* " + t.getMessage());
        } finally {peer.disconnect();}
    }

}
