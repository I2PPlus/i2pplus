package org.klomp.snark;

import net.i2p.data.DataHelper;
import net.i2p.util.RandomSource;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple state for the download of the metainfo, shared between Peer and ExtensionHandler.
 *
 * <p>Nothing is synchronized here! Caller must synchronize on this for everything!
 *
 * <p>Reference: BEP 9
 *
 * @since 0.8.4 author zzz
 */
class MagnetState {
    public static final int CHUNK_SIZE = 16 * 1024;

    private final byte[] infohash;
    private boolean complete;

    /** if false, nothing below is valid */
    private boolean isInitialized;

    private int metaSize;
    private int totalChunks;

    /** bitfield for the metainfo chunks - will remain null if we start out complete */
    private BitField requested;

    private BitField have;

    /** bitfield for the metainfo */
    private byte[] metainfoBytes;

    /** only valid when finished */
    private MetaInfo metainfo;

    /**
     * @param meta null for new magnet
     */
    public MagnetState(byte[] iHash, MetaInfo meta) {
        infohash = iHash;
        if (meta != null) {
            metainfo = meta;
            initialize(meta.getInfoBytesLength());
            complete = true;
        }
    }

    /**
     * Call this for a new magnet when you have the size
     *
     * @throws IllegalArgumentException
     */
    public void initialize(int size) {
        if (isInitialized) throw new IllegalArgumentException("Already set");
        isInitialized = true;
        metaSize = size;
        totalChunks = (size + (CHUNK_SIZE - 1)) / CHUNK_SIZE;
        if (metainfo != null) {
            metainfoBytes = metainfo.getInfoBytes();
        } else {
            // we don't need these if complete
            have = new BitField(totalChunks);
            requested = new BitField(totalChunks);
            metainfoBytes = new byte[metaSize];
        }
    }

    /**
     * Call this for a new magnet when the download is complete.
     *
     * @throws IllegalArgumentException
     */
    public void setMetaInfo(MetaInfo meta) {
        metainfo = meta;
    }

    /**
     * @throws IllegalArgumentException
     */
    public MetaInfo getMetaInfo() {
        if (!complete) throw new IllegalArgumentException("Not complete");
        return metainfo;
    }

    /**
     * @throws IllegalArgumentException
     */
    public int getSize() {
        if (!isInitialized) throw new IllegalArgumentException("Not initialized");
        return metaSize;
    }

    /**
     * Check if the magnet state has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Check if the magnet download is complete.
     *
     * @return true if complete, false otherwise
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     * Get the size of a specific chunk.
     *
     * @param chunk the chunk number
     * @return the size of the chunk in bytes
     */
    public int chunkSize(int chunk) {
        return Math.min(CHUNK_SIZE, metaSize - (chunk * CHUNK_SIZE));
    }

    /**
     * Get the number of chunks remaining to be downloaded.
     *
     * @return the number of chunks remaining
     * @throws IllegalArgumentException if not initialized
     */
    public int chunksRemaining() {
        if (!isInitialized) throw new IllegalArgumentException("Not initialized");
        if (complete) return 0;
        return totalChunks - have.count();
    }

    /**
     * Get the next chunk number to request. Uses a random selection algorithm to avoid requesting
     * the same chunks repeatedly.
     *
     * @return the next chunk number to request
     * @throws IllegalArgumentException if not initialized or complete
     */
    public int getNextRequest() {
        if (!isInitialized) throw new IllegalArgumentException("Not initialized");
        if (complete) throw new IllegalArgumentException("Complete");
        int rand = RandomSource.getInstance().nextInt(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            int chk = (i + rand) % totalChunks;
            if (!(have.get(chk) || requested.get(chk))) {
                requested.set(chk);
                return chk;
            }
        }
        // all requested - end game
        for (int i = 0; i < totalChunks; i++) {
            int chk = (i + rand) % totalChunks;
            if (!have.get(chk)) return chk;
        }
        throw new IllegalArgumentException("Complete");
    }

    /**
     * Get the data for a specific chunk.
     *
     * @param chunk the chunk number to retrieve
     * @return the chunk data as a byte array
     * @throws IllegalArgumentException if not complete or chunk number is invalid
     */
    public byte[] getChunk(int chunk) {
        if (!complete) throw new IllegalArgumentException("Not complete");
        if (chunk < 0 || chunk >= totalChunks)
            throw new IllegalArgumentException("Bad chunk number");
        int size = chunkSize(chunk);
        byte[] rv = new byte[size];
        System.arraycopy(metainfoBytes, chunk * CHUNK_SIZE, rv, 0, size);
        // use meta.getInfoBytes() so we don't save it in memory
        return rv;
    }

    /**
     * Save a chunk of data to the magnet state.
     *
     * @param chunk the chunk number to save
     * @param data the byte array containing the chunk data
     * @param off the offset in the data array where the chunk starts
     * @param length the length of the chunk data
     * @return true if this was the last piece, false otherwise
     * @throws IllegalArgumentException if not initialized, chunk number is invalid, or length is
     *     incorrect
     * @throws Exception if there's an error building the MetaInfo
     */
    public boolean saveChunk(int chunk, byte[] data, int off, int length) throws Exception {
        if (!isInitialized) throw new IllegalArgumentException("Not initialized");
        if (chunk < 0 || chunk >= totalChunks)
            throw new IllegalArgumentException("bad chunk number");
        if (have.get(chunk)) return false; // shouldn't happen if synced
        int size = chunkSize(chunk);
        if (size != length) throw new IllegalArgumentException("Bad chunk length");
        System.arraycopy(data, off, metainfoBytes, chunk * CHUNK_SIZE, size);
        have.set(chunk);
        boolean done = have.complete();
        if (done) {
            metainfo = buildMetaInfo();
            complete = true;
        }
        return done;
    }

    /**
     * @return true if this was the last piece
     * @throws NullPointerException IllegalArgumentException, IOException, ...
     */
    private MetaInfo buildMetaInfo() throws Exception {
        // top map has nothing in it but the info map (no announce)
        Map<String, BEValue> map = new HashMap<String, BEValue>();
        InputStream is = new ByteArrayInputStream(metainfoBytes);
        BDecoder dec = new BDecoder(is);
        BEValue bev = dec.bdecodeMap();
        map.put("info", bev);
        MetaInfo newmeta = new MetaInfo(map);
        if (!DataHelper.eq(newmeta.getInfoHash(), infohash)) {
            // Disaster. Start over. ExtensionHandler will catch
            // the IOE and disconnect the peer, hopefully we will
            // find a new peer.
            // TODO: Count fails and give up eventually
            have = new BitField(totalChunks);
            requested = new BitField(totalChunks);
            throw new IOException("Info hash mismatch");
        }
        return newmeta;
    }
}
