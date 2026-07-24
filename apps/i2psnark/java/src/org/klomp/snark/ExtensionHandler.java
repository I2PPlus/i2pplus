package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;

/**
 * REF: BEP 10 Extension Protocol
 *
 * @since 0.8.2
 */
abstract class ExtensionHandler {

    public static final int ID_HANDSHAKE = 0;
    public static final int ID_METADATA = 1;
    public static final String TYPE_METADATA = "ut_metadata";
    public static final int ID_PEX = 2;

    /** not ut_pex since the compact format is different */
    public static final String TYPE_PEX = "i2p_pex";

    public static final int ID_DHT = 3;

    /** not using the option bit since the compact format is different */
    public static final String TYPE_DHT = "i2p_dht";

    /**
     * @since 0.9.31
     */
    public static final int ID_COMMENT = 4;

    /**
     * @since 0.9.31
     */
    public static final String TYPE_COMMENT = "ut_comment";

    /** Pieces * SHA1 Hash length, + 25% extra for file names, bencoding overhead, etc */
    private static final int MAX_METADATA_SIZE = Storage.MAX_PIECES * 20 * 5 / 4;

    private static final int PARALLEL_REQUESTS = 8;

    /**
     * Creates a bencoded extension handshake message.
     *
     * @param metasize the metadata size in bytes, or -1 if unknown
     * @param pexAndMetadata true to advertise PEX and metadata extensions
     * @param dht true to advertise DHT capability
     * @param uploadOnly true to advertise upload-only mode (BEP 21)
     * @param comment true to advertise ut_comment extension
     * @return bencoded outgoing handshake message
     */
    public static byte[] getHandshake(
            int metasize,
            boolean pexAndMetadata,
            boolean dht,
            boolean uploadOnly,
            boolean comment) {
        Map<String, Object> handshake = new HashMap<>();
        Map<String, Integer> m = new HashMap<>();
        if (pexAndMetadata) {
            m.put(TYPE_METADATA, Integer.valueOf(ID_METADATA));
            m.put(TYPE_PEX, Integer.valueOf(ID_PEX));
            if (metasize >= 0) handshake.put("metadata_size", Integer.valueOf(metasize));
        }
        if (dht) {
            m.put(TYPE_DHT, Integer.valueOf(ID_DHT));
        }
        if (comment) {
            m.put(TYPE_COMMENT, Integer.valueOf(ID_COMMENT));
        }
        // include the map even if empty so the far-end doesn't NPE
        handshake.put("m", m);
        handshake.put("p", Integer.valueOf(TrackerClient.PORT));
        handshake.put("v", "I2PSnark");
        handshake.put("reqq", Integer.valueOf(PeerState.MAX_PIPELINE));
        // BEP 21
        if (uploadOnly) handshake.put("upload_only", Integer.valueOf(1));
        return BEncoder.bencode(handshake);
    }

    /**
     * Handles an incoming extension message from a peer.
     *
     * @param peer the peer that sent the message
     * @param listener the peer listener for callbacks
     * @param id the extension message ID
     * @param bs the raw message bytes
     */
    public static void handleMessage(Peer peer, PeerListener listener, int id, byte[] bs) {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(ExtensionHandler.class);
        if (log.shouldInfo())
            log.info(
                    "Received extension message "
                            + id
                            + " ("
                            + bs.length
                            + " bytes) from ["
                            + peer
                            + "]");
        if (id == ID_HANDSHAKE) handleHandshake(peer, listener, bs, log);
        else if (id == ID_METADATA) handleMetadata(peer, listener, bs, log);
        else if (id == ID_PEX) handlePEX(peer, listener, bs, log);
        else if (id == ID_DHT) handleDHT(peer, listener, bs, log);
        else if (id == ID_COMMENT) handleComment(peer, listener, bs, log);
        else if (log.shouldInfo())
            log.info("Unknown extension message " + id + " from [" + peer + "]");
    }

    /**
     * Handles an incoming extension handshake message.
     *
     * @param peer the peer that sent the message
     * @param listener the peer listener for callbacks
     * @param bs the raw message bytes
     * @param log the logger instance
     */
    private static void handleHandshake(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldDebug()) log.debug("Received handshake message from [" + peer + "]");
        try {
            // this throws NPE on missing keys
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            peer.setHandshakeMap(map);
            Map<String, BEValue> msgmap = map.get("m").getMap();

            if (log.shouldDebug())
                log.debug("Peer [" + peer + "] supports extensions: " + msgmap.keySet());

            MagnetState state = peer.getMagnetState();

            if (msgmap.get(TYPE_METADATA) == null) {
                if (log.shouldDebug())
                    log.debug("[" + peer + "] does not support metadata extension");
                // drop if we need metainfo and we haven't found anybody yet
                synchronized (state) {
                    if (!state.isInitialized()) {
                        if (log.shouldDebug())
                            log.debug("Dropping [" + peer + "] - we need metadata!");
                        peer.disconnect();
                    }
                }
                return;
            }

            BEValue msize = map.get("metadata_size");
            if (msize == null) {
                if (log.shouldDebug())
                    log.debug("[" + peer + "] does not have the metainfo size yet");
                // drop if we need metainfo and we haven't found anybody yet
                synchronized (state) {
                    if (!state.isInitialized()) {
                        if (log.shouldDebug())
                            log.debug("Dropping [" + peer + "] - we need metadata!");
                        peer.disconnect();
                    }
                }
                return;
            }
            int metaSize = msize.getInt();
            if (log.shouldDebug()) log.debug("Received the metainfo size: " + metaSize);

            int remaining;
            synchronized (state) {
                if (state.isComplete()) return;

                if (state.isInitialized()) {
                    if (state.getSize() != metaSize) {
                        if (log.shouldDebug())
                            log.debug("Wrong metainfo size " + metaSize + " from [" + peer + "]");
                        peer.disconnect();
                        return;
                    }
                } else {
                    // initialize it
                    if (metaSize > MAX_METADATA_SIZE) {
                        if (log.shouldDebug())
                            log.debug("Huge metainfo size " + metaSize + " from [" + peer + "]");
                        peer.disconnect(false);
                        return;
                    }
                    if (log.shouldInfo())
                        log.info(
                                "Initialized state, metadata size = "
                                        + metaSize
                                        + " from ["
                                        + peer
                                        + "]");
                    state.initialize(metaSize);
                }
                remaining = state.chunksRemaining();
            }

            // send requests for chunks
            int count = Math.min(remaining, PARALLEL_REQUESTS);
            for (int i = 0; i < count; i++) {
                int chk;
                synchronized (state) {
                    chk = state.getNextRequest();
                }
                if (log.shouldInfo()) log.info("Requesting chunk " + chk + " from [" + peer + "]");
                // ignore the rv, always request
                peer.shouldRequest(state.chunkSize(chk));
                sendRequest(peer, chk);
            }
        } catch (Exception e) {
            if (log.shouldWarn()) log.warn("Handshake exception from [" + peer + "]", e);
        }
    }

    private static final int TYPE_REQUEST = 0;
    private static final int TYPE_DATA = 1;
    private static final int TYPE_REJECT = 2;

    /**
     * Handles an incoming metadata extension message (BEP 9).
     *
     * @param peer the peer that sent the message
     * @param listener the peer listener for callbacks
     * @param bs the raw message bytes
     * @param log the logger instance
     */
    private static void handleMetadata(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldDebug()) log.debug("Received metadata message from [" + peer + "]");
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int type = map.get("msg_type").getInt();
            int piece = map.get("piece").getInt();

            MagnetState state = peer.getMagnetState();
            if (type == TYPE_REQUEST) {
                if (log.shouldDebug())
                    log.debug("Received request for " + piece + " from [" + peer + "]");
                byte[] pc;
                int totalSize;
                synchronized (state) {
                    pc = state.getChunk(piece);
                    totalSize = state.getSize();
                }
                sendPiece(peer, piece, pc, totalSize);
                // Do this here because PeerConnectionOut only reports for PIECE messages
                peer.uploaded(pc.length);
            } else if (type == TYPE_DATA) {
                boolean done;
                int chk = -1;
                synchronized (state) {
                    if (state.isComplete()) return;
                    int len = is.available();
                    peer.downloaded(len);
                    // this checks the size
                    done = state.saveChunk(piece, bs, bs.length - len, len);
                    if (log.shouldInfo())
                        log.info("Received chunk " + piece + " from [" + peer + "]");
                    if (!done) chk = state.getNextRequest();
                }
                // out of the lock
                if (done) {
                    // Done!
                    // PeerState will call the listener (peer coord), who will
                    // check to see if the MagnetState has it
                    if (log.shouldWarn()) log.warn("Received last chunk from [" + peer + "]");
                } else {
                    // get the next chunk
                    if (log.shouldInfo())
                        log.info("Requesting chunk [" + chk + "] from [" + peer + "]");
                    // ignore the rv, always request
                    peer.shouldRequest(state.chunkSize(chk));
                    sendRequest(peer, chk);
                }
            } else if (type == TYPE_REJECT) {
                if (log.shouldWarn()) log.warn("Received reject message from [" + peer + "]");
                peer.disconnect(false);
            } else {
                if (log.shouldWarn())
                    log.warn("Received unknown metadata message from [" + peer + "]");
                peer.disconnect(false);
            }
        } catch (Exception e) {
            if (log.shouldInfo())
                log.info("Received metadata ext. message exception from [" + peer + "]", e);
            // fatal ?
            peer.disconnect(false);
        }
    }

    /**
     * Sends a metadata chunk request to a peer.
     *
     * @param peer the peer to send the request to
     * @param piece the chunk number to request
     */
    private static void sendRequest(Peer peer, int piece) {
        sendMessage(peer, TYPE_REQUEST, piece);
    }

    /** REQUEST and REJECT are the same except for message type */
    /**
     * Sends a metadata request or reject message to a peer.
     *
     * @param peer the peer to send the message to
     * @param type the message type (TYPE_REQUEST or TYPE_REJECT)
     * @param piece the chunk number
     */
    private static void sendMessage(Peer peer, int type, int piece) {
        Map<String, Object> map = new HashMap<>();
        map.put("msg_type", Integer.valueOf(type));
        map.put("piece", Integer.valueOf(piece));
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_METADATA).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no metadata capability
        }
    }

    /**
     * Sends a metadata chunk data message to a peer.
     *
     * @param peer the peer to send the data to
     * @param piece the chunk number
     * @param data the chunk data bytes
     * @param totalSize the total metadata size
     */
    private static void sendPiece(Peer peer, int piece, byte[] data, int totalSize) {
        Map<String, Object> map = new HashMap<>();
        map.put("msg_type", Integer.valueOf(TYPE_DATA));
        map.put("piece", Integer.valueOf(piece));
        map.put("total_size", Integer.valueOf(totalSize));
        byte[] dict = BEncoder.bencode(map);
        byte[] payload = new byte[dict.length + data.length];
        System.arraycopy(dict, 0, payload, 0, dict.length);
        System.arraycopy(data, 0, payload, dict.length, data.length);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_METADATA).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no metadata caps
        }
    }

    private static final int HASH_LENGTH = 32;

    /**
     * Handles an incoming PEX message. Uses the "added" key as a single string of concatenated
     * 32-byte peer hashes. added.f and dropped are unsupported.
     *
     * @param peer the peer that sent the message
     * @param listener the peer listener for callbacks
     * @param bs the raw message bytes
     * @param log the logger instance
     */
    private static void handlePEX(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldDebug()) log.debug("Received PEX msg from [" + peer + "]");
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            bev = map.get("added");
            if (bev == null) return;
            byte[] ids = bev.getBytes();
            if (ids.length < HASH_LENGTH) return;
            int len = Math.min(ids.length, (I2PSnarkUtil.MAX_CONNECTIONS - 1) * HASH_LENGTH);
            List<PeerID> peers = new ArrayList<>(len / HASH_LENGTH);
            for (int off = 0; off < len; off += HASH_LENGTH) {
                byte[] hash = new byte[HASH_LENGTH];
                System.arraycopy(ids, off, hash, 0, HASH_LENGTH);
                if (DataHelper.eq(hash, peer.getPeerID().getDestHash())) continue;
                PeerID pID = new PeerID(hash, listener.getUtil());
                peers.add(pID);
            }
            listener.gotPeers(peer, peers);
        } catch (Exception e) {
            if (log.shouldInfo()) log.info("PEX messsage exception from [" + peer + "]", e);
        }
    }

    /**
     * Handles an incoming DHT port message.
     *
     * @param peer the peer that sent the message
     * @param listener the peer listener for callbacks
     * @param bs the raw message bytes
     * @param log the logger instance
     */
    private static void handleDHT(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldDebug()) log.debug("Received DHT messsage from [" + peer + "]");
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int qport = map.get("port").getInt();
            int rport = map.get("rport").getInt();
            listener.gotPort(peer, qport, rport);
        } catch (Exception e) {
            if (log.shouldInfo()) log.info("DHT messsage exception from [" + peer + "]", e);
        }
    }

    /**
     * Sends a PEX message with the given peer list. added.f and dropped are unsupported.
     *
     * @param peer the peer to send the PEX message to
     * @param pList non-null list of peers to share
     */
    public static void sendPEX(Peer peer, List<Peer> pList) {
        if (pList.isEmpty()) return;
        Map<String, Object> map = new HashMap<>();
        byte[] peers = new byte[HASH_LENGTH * pList.size()];
        int off = 0;
        for (Peer p : pList) {
            System.arraycopy(p.getPeerID().getDestHash(), 0, peers, off, HASH_LENGTH);
            off += HASH_LENGTH;
        }
        map.put("added", peers);
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_PEX).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no PEX caps
        }
    }

    /**
     * Sends DHT port numbers to a peer.
     *
     * @param peer the peer to send the DHT ports to
     * @param qport the query port
     * @param rport the response port
     */
    public static void sendDHT(Peer peer, int qport, int rport) {
        Map<String, Object> map = new HashMap<>();
        map.put("port", Integer.valueOf(qport));
        map.put("rport", Integer.valueOf(rport));
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_DHT).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no DHT caps
        }
    }

    /**
     * Handles an incoming comment request or response message.
     *
     * @param peer the peer that sent the message
     * @param listener the peer listener for callbacks
     * @param bs the raw message bytes
     * @param log the logger instance
     */
    private static void handleComment(Peer peer, PeerListener listener, byte[] bs, Log log) {
        if (log.shouldDebug()) log.debug("Received comment messsage from [" + peer + "]");
        try {
            InputStream is = new ByteArrayInputStream(bs);
            BDecoder dec = new BDecoder(is);
            BEValue bev = dec.bdecodeMap();
            Map<String, BEValue> map = bev.getMap();
            int type = map.get("msg_type").getInt();
            if (type == 0) {
                // request
                int num = 20;
                BEValue b = map.get("num");
                if (b != null) num = b.getInt();
                listener.gotCommentReq(peer, num);
            } else if (type == 1) {
                // response
                List<BEValue> list = map.get("comments").getList();
                if (list.isEmpty()) return;
                List<Comment> comments = new ArrayList<>(list.size());
                long now = I2PAppContext.getGlobalContext().clock().now();
                for (BEValue li : list) {
                    Map<String, BEValue> m = li.getMap();
                    String owner = m.get("owner").getString();
                    String text = m.get("text").getString();
                    // 0-5 range for rating is enforced by Comment constructor
                    int rating = m.get("like").getInt();
                    long time = now - (Math.max(0, m.get("timestamp").getInt()) * 1000L);
                    Comment c = new Comment(text, owner, rating, time, false);
                    comments.add(c);
                }
                listener.gotComments(peer, comments);
            } else {
                if (log.shouldInfo())
                    log.info("Unknown comment messsage type " + type + " from [" + peer + "]");
            }
        } catch (Exception e) {
            if (log.shouldInfo()) log.info("Comment messsage exception from [" + peer + "]", e);
        }
    }

    private static final byte[] COMMENTS_FILTER = new byte[64];

    /**
     * Sends a comment request to a peer.
     *
     * @param peer the peer to request comments from
     * @param num the maximum number of comments to request
     */
    public static void sendCommentReq(Peer peer, int num) {
        Map<String, Object> map = new HashMap<>();
        map.put("msg_type", Integer.valueOf(0));
        map.put("num", Integer.valueOf(num));
        map.put("filter", COMMENTS_FILTER);
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_COMMENT).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no caps
        }
    }

    /**
     * Sends comments to a peer. Caller must synchronize on comments.
     *
     * @param peer the peer to send comments to
     * @param num the maximum number of comments to send
     * @param comments non-null set of comments to send
     */
    public static void locked_sendComments(Peer peer, int num, CommentSet comments) {
        int toSend = Math.min(num, comments.size());
        if (toSend <= 0) return;
        Map<String, Object> map = new HashMap<>();
        map.put("msg_type", Integer.valueOf(1));
        List<Object> lc = new ArrayList<>(toSend);
        long now = I2PAppContext.getGlobalContext().clock().now();
        int i = 0;
        for (Comment c : comments) {
            if (i++ >= toSend) break;
            Map<String, Object> mc = new HashMap<>();
            String s = c.getName();
            mc.put("owner", s != null ? s : "");
            s = c.getText();
            mc.put("text", s != null ? s : "");
            mc.put("like", Integer.valueOf(c.getRating()));
            mc.put("timestamp", Long.valueOf((now - c.getTime()) / 1000L));
            lc.add(mc);
        }
        map.put("comments", lc);
        byte[] payload = BEncoder.bencode(map);
        try {
            int hisMsgCode = peer.getHandshakeMap().get("m").getMap().get(TYPE_COMMENT).getInt();
            peer.sendExtension(hisMsgCode, payload);
        } catch (Exception e) {
            // NPE, no caps
        }
    }
}
