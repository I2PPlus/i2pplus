package org.klomp.snark.dht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.RandomSource;

/*
 *  A Node ID, Hash, and port, and an optional Destination.
 *  This is what DHTNodes remembers. The DHT tracker just stores Hashes.
 *  getData() returns the 54 byte compact info (NID, Hash, port).
 *
 *  Things are a little tricky in KRPC since we exchange Hashes and don't
 *  always have the Destination.
 *  The conpact info is immutable. The Destination may be added later.
 *
 * @since 0.9.2
 * @author zzz
 */

class NodeInfo extends SimpleDataStructure {

    private final NID nID;
    private final Hash hash;
    private Destination dest;
    private final int port;

    public static final int LENGTH = NID.HASH_LENGTH + Hash.HASH_LENGTH + 2;

    /**
     * With a fake NID used for pings
     */
    public NodeInfo(Destination dest, int port) {
        super();
        this.nID = KRPC.FAKE_NID;
        this.dest = dest;
        this.hash = dest.calculateHash();
        this.port = port;
        initialize();
    }

    /**
     * Use this if we have the full destination
     * @throws IllegalArgumentException
     */
    public NodeInfo(NID nID, Destination dest, int port) {
        super();
        this.nID = nID;
        this.dest = dest;
        this.hash = dest.calculateHash();
        this.port = port;
        initialize();
        verify();
    }

    /**
     * No Destination yet available
     * @throws IllegalArgumentException
     */
    public NodeInfo(NID nID, Hash hash, int port) {
        super();
        this.nID = nID;
        this.hash = hash;
        this.port = port;
        initialize();
        verify();
    }

    /**
     * No Destination yet available
     * @param compactInfo 20 byte node ID, 32 byte destHash, 2 byte port
     * @param offset starting at this offset in compactInfo
     * @throws IllegalArgumentException
     * @throws ArrayIndexOutOfBoundsException
     */
    public NodeInfo(byte[] compactInfo, int offset) {
        super();
        byte[] d = new byte[LENGTH];
        System.arraycopy(compactInfo, offset, d, 0, LENGTH);
        setData(d);
        byte[] ndata = new byte[NID.HASH_LENGTH];
        System.arraycopy(d, 0, ndata, 0, NID.HASH_LENGTH);
        this.nID = new NID(ndata);
        this.hash = Hash.create(d, NID.HASH_LENGTH);
        this.port = (int) DataHelper.fromLong(d, NID.HASH_LENGTH + Hash.HASH_LENGTH, 2);
        if (port <= 0 || port >= 65535)
            throw new IllegalArgumentException("Bad port");
        verify();
    }

    /**
     * Create from persistent storage string.
     * Format: NID:Hash:Destination:port
     * First 3 in base 64; Destination may be empty string
     * @throws IllegalArgumentException
     */
    public NodeInfo(String s) throws DataFormatException {
        super();
        String[] parts = DataHelper.split(s, ":", 4);
        if (parts.length != 4)
            throw new DataFormatException("Bad format");
        byte[] nid = Base64.decode(parts[0]);
        if (nid == null || nid.length != NID.HASH_LENGTH)
            throw new DataFormatException("Bad NID");
        nID = new NID(nid);
        byte[] h = Base64.decode(parts[1]);
        if (h == null || h.length != Hash.HASH_LENGTH)
            throw new DataFormatException("Bad hash");
        //hash = new Hash(h);
        hash = Hash.create(h);
        if (parts[2].length() > 0)
            dest = new Destination(parts[2]);
        try {
            port = Integer.parseInt(parts[3]);
        } catch (NumberFormatException nfe) {
            throw new DataFormatException("Bad port", nfe);
        }
        initialize();
    }


    /**
     * Creates 54-byte compact info
     * @throws IllegalArgumentException
     */
    private void initialize() {
        if (port <= 0 || port >= 65535)
            throw new IllegalArgumentException("Bad port");
        byte[] compactInfo = new byte[LENGTH];
        System.arraycopy(nID.getData(), 0, compactInfo, 0, NID.HASH_LENGTH);
        System.arraycopy(hash.getData(), 0, compactInfo, NID.HASH_LENGTH, Hash.HASH_LENGTH);
        DataHelper.toLong(compactInfo, NID.HASH_LENGTH + Hash.HASH_LENGTH, 2, port);
        setData(compactInfo);
    }

    /**
     * Generate a secure NID that matches the Hash and port.
     * Rules: First 4 bytes must match Hash.
     * Next 2 bytes must match Hash ^ port.
     * Remaining bytes may be random.
     *
     * @throws IllegalArgumentException
     */
    public static NID generateNID(Hash h, int p, RandomSource random) {
        byte[] n = new byte[NID.HASH_LENGTH];
        System.arraycopy(h.getData(), 0, n, 0, 6);
        n[4] ^= (byte) (p >> 8);
        n[5] ^= (byte) p;
        random.nextBytes(n, 6, NID.HASH_LENGTH - 6);
        return new NID(n);
    }

    /**
     * Verify the NID matches the Hash.
     * See generateNID() for requirements.
     * @throws IllegalArgumentException on mismatch
     */
    private void verify() {
        if (!KRPC.SECURE_NID)
            return;
        byte[] nb = nID.getData();
        byte[] hb = hash.getData();
        if ((!DataHelper.eq(nb, 0, hb, 0, 4)) ||
            ((nb[4] ^ (port >> 8)) & 0xff) != (hb[4] & 0xff) ||
            ((nb[5] ^ port) & 0xff) != (hb[5] & 0xff))
            throw new IllegalArgumentException("NID/Hash mismatch");
    }

    public int length() {
        return LENGTH;
    }

    public NID getNID() {
        return this.nID;
    }

    /** @return may be null if we don't have it */
    public Destination getDestination() {
        return this.dest;
    }

    public Hash getHash() {
        return this.hash;
    }

    @Override
    public Hash calculateHash() {
        return this.hash;
    }

    /**
     * This can come in later but the hash must match.
     * @throws IllegalArgumentException if hash of dest doesn't match previous hash
     */
    public void setDestination(Destination dest) throws IllegalArgumentException {
        if (this.dest != null)
            return;
        if (!dest.calculateHash().equals(this.hash))
            throw new IllegalArgumentException("Hash mismatch, was: " + this.hash + " new: " + dest.calculateHash());
        this.dest = dest;
    }

    public int getPort() {
        return this.port;
    }

    public long lastSeen() {
        return nID.lastSeen();
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ nID.hashCode() ^ port;
    }

    @Override
    public boolean equals(Object o) {
        try {
            NodeInfo ni = (NodeInfo) o;
            // assume dest matches, ignore it
            return this.hash.equals(ni.hash) && nID.equals(ni.nID) && port == ni.port;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "[" + nID.toBase64().substring(0,8) + "]:" + port + (dest != null ? " -> Known destination" : "");
    }

    /**
     * To persistent storage string.
     * Format: NID:Hash:Destination:port
     * First 3 in base 64; Destination may be empty string
     */
    public String toPersistentString() {
        StringBuilder buf = new StringBuilder(650);
        buf.append(nID.toBase64()).append(':');
        buf.append(hash.toBase64()).append(':');
        if (dest != null)
            buf.append(dest.toBase64());
        buf.append(':').append(port);
        return buf.toString();
    }

}
