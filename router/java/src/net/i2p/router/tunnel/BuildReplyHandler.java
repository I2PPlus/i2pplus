package net.i2p.router.tunnel;

import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.BuildResponseRecord;
import net.i2p.data.i2np.EncryptedBuildRecord;
import net.i2p.data.i2np.OutboundTunnelBuildReplyMessage;
import net.i2p.data.i2np.TunnelBuildReplyMessage;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Decrypt the layers of a tunnel build reply message, determining whether the individual
 * hops agreed to participate in the tunnel, or if not, why not.
 *
 */
public class BuildReplyHandler {

    private final I2PAppContext ctx;
    private final Log log;

    /**
     *  @since 0.9.8 (methods were static before)
     */
    public BuildReplyHandler(I2PAppContext context) {
        ctx = context;
        log = ctx.logManager().getLog(BuildReplyHandler.class);
    }

    /**
     * Decrypt the tunnel build reply records.  This overwrites the contents of the reply.
     * Thread safe (no state).
     *
     * Note that this layer-decrypts the build records in-place.
     * Do not call this more than once for a given message.
     *
     * @return status for the records (in record order), or null if the replies were not valid.  Fake records
     *         always have 0 as their value
     */
    public int[] decrypt(TunnelBuildReplyMessage reply, TunnelCreatorConfig cfg, List<Integer> recordOrder) {
        if (reply.getRecordCount() != recordOrder.size()) {
            // somebody messed with us
            log.error("Corrupted build reply, expected " + recordOrder.size() + " records, got " + reply.getRecordCount());
            return null;
        }
        int rv[] = new int[reply.getRecordCount()];
        for (int i = 0; i < rv.length; i++) {
            int hop = recordOrder.get(i).intValue();
            if (BuildMessageGenerator.isBlank(cfg, hop)) {
                // self or unused...
                if (log.shouldLog(Log.DEBUG))
                    log.debug(reply.getUniqueId() + ": skipping record " + i + "/" + hop + " for: " + cfg);
                if (cfg.isInbound() && hop + 1 == cfg.getLength()) { // IBEP
                    byte[] h1 = new byte[Hash.HASH_LENGTH];
                    ctx.sha().calculateHash(reply.getRecord(i).getData(), 0, TunnelBuildReplyMessage.RECORD_SIZE, h1, 0);
                    // get stored hash put here by BuildMessageGenerator
                    Hash h2 = cfg.getBlankHash();
                    if (h2 != null && DataHelper.eq(h1, h2.getData())) {
                        rv[i] = 0;
                    } else {
                        if (log.shouldWarn())
                            log.warn("IBEP record corrupt on " + cfg);
                        // Caller doesn't check value for this hop so fail the whole thing
                        return null;
                    }
                } else {
                    rv[i] = 0;
                }
            } else {
                int ok = decryptRecord(reply, cfg, i, hop);
                if (ok == -1) {
                    if (log.shouldLog(Log.WARN))
                        log.warn(reply.getUniqueId() + ": decrypt record " + i + "/" + hop + " fail: " + cfg);
                    return null;
                } else {
                    if (log.shouldLog(Log.DEBUG))
                        log.debug(reply.getUniqueId() + ": decrypt record " + i + "/" + hop + " success: " + ok + " for " + cfg);
                }
                rv[i] = ok;
            }
        }
        return rv;
    }

    /**
     * Decrypt the record (removing the layers of reply encyption) and read out the status
     *
     * Note that this layer-decrypts the build records in-place.
     * Do not call this more than once for a given message.
     * Do not call for blank hops.
     *
     * @return the status 0-255, or -1 on decrypt failure
     */
    private int decryptRecord(TunnelBuildReplyMessage reply, TunnelCreatorConfig cfg, int recordNum, int hop) {
        EncryptedBuildRecord rec = reply.getRecord(recordNum);
        byte[] data = rec.getData();
        int start = cfg.getLength() - 1;
        if (cfg.isInbound())
            start--; // the last hop in an inbound tunnel response doesn't actually encrypt
        int end = hop;
        boolean isEC = cfg.isEC(hop);
        // chacha decrypt after the loop
        if (isEC)
            end++;
        // do we need to adjust this for the endpoint?
        for (int j = start; j >= end; j--) {
            SessionKey replyKey = cfg.getAESReplyKey(j);
            byte replyIV[] = cfg.getAESReplyIV(j);
            if (log.shouldLog(Log.DEBUG)) {
                log.debug("[" + reply.getUniqueId() + "] Decrypting record " + recordNum + "/" + hop + "/" + j + " with replyKey "
                          + replyKey.toBase64() + "/" + Base64.encode(replyIV) + ": " + cfg);
                log.debug(reply.getUniqueId() + "; Before decrypt: " + Base64.encode(data));
                log.debug("[" + reply.getUniqueId() + "] Full reply received: sz=" + data.length +
                          " data=" + Base64.encode(data, 0, TunnelBuildReplyMessage.RECORD_SIZE));
            }
            ctx.aes().decrypt(data, 0, data, 0, replyKey, replyIV, 0, TunnelBuildReplyMessage.RECORD_SIZE);
            if (log.shouldLog(Log.DEBUG))
                log.debug("[" + reply.getUniqueId() + "] After decrypt: " + Base64.encode(data));
        }
        // ok, all of the layered encryption is stripped, so lets verify it
        // (formatted per BuildResponseRecord.create)
        int rv;
        if (isEC) {
            // For last iteration, do ChaCha instead
            SessionKey replyKey = cfg.getChaChaReplyKey(hop);
            byte[] replyIV = cfg.getChaChaReplyAD(hop);
            if (log.shouldDebug())
                log.debug(reply.getUniqueId() + ": Decrypting chacha/poly record " + recordNum + "/" + hop + " with replyKey "
                          + replyKey.toBase64() + "/" + Base64.encode(replyIV) + ": " + cfg);
            boolean ok = BuildResponseRecord.decrypt(rec, replyKey, replyIV);
            if (!ok) {
                if (log.shouldWarn())
                    log.debug(reply.getUniqueId() + ": chacha reply decrypt fail on " + recordNum + "/" + hop);
                return -1;
            }
            // reply properties TODO
            // this handles both standard records in a build reply message and short records in a OTBRM
            rv = data[rec.length() - 17] & 0xff;
        } else {
            // don't cache the result
            //Hash h = ctx.sha().calculateHash(data, off + Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH);
            byte[] h = SimpleByteCache.acquire(Hash.HASH_LENGTH);
            ctx.sha().calculateHash(data, Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH, h, 0);
            boolean ok = DataHelper.eq(h, 0, data, 0, Hash.HASH_LENGTH);
            if (!ok) {
                if (log.shouldWarn())
                    log.warn(reply.getUniqueId() + ": sha256 reply verify fail on " + recordNum + "/" + hop + ": " + Base64.encode(h) + " calculated, " +
                             Base64.encode(data, 0, Hash.HASH_LENGTH) + " expected\n" +
                             "Record: " + Base64.encode(data, Hash.HASH_LENGTH, TunnelBuildReplyMessage.RECORD_SIZE-Hash.HASH_LENGTH));
                SimpleByteCache.release(h);
                return -1;
            }
            SimpleByteCache.release(h);
            rv = data[TunnelBuildReplyMessage.RECORD_SIZE - 1] & 0xff;
        }
        if (log.shouldLog(Log.DEBUG))
            log.debug(reply.getUniqueId() + ": Verified: " + rv + " for record " + recordNum + "/" + hop);
        return rv;
    }
}
