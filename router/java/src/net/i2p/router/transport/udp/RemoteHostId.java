package net.i2p.router.transport.udp;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Addresses;

/**
 * Unique ID for a peer - its IP + port, all bundled into a tidy obj.
 * If the remote peer is not reachable through an IP+port, this contains
 * the hash of their identity.
 *
 */
final class RemoteHostId {
    private final byte[] _ip;
    private final int _port;
    private final Hash _peerHash;
    private final int _hashCode;

    /**
     * Direct (IP + port).
     * @param ip the peer's address bytes, 4 or 16 long
     * @param port the UDP port the peer is reachable on
     */
    public RemoteHostId(byte[] ip, int port) {
        this(ip, port, null);
    }

    /**
     * Indirect (hash only).
     * @param peerHash the hash of the peer's RouterIdentity
     */
    public RemoteHostId(Hash peerHash) {
        this(null, 0, peerHash);
    }

    private RemoteHostId(byte[] ip, int port, Hash peerHash) {
        _ip = ip;
        _port = port;
        _peerHash = peerHash;
        _hashCode = DataHelper.hashCode(_ip) ^ DataHelper.hashCode(_peerHash) ^ _port;
    }

    /**
     * The IP address, or null if indirect.
     * @return the raw address bytes, or null for a hash-only id
     */
    public byte[] getIP() { return _ip; }

    /**
     * The port, or 0 if indirect.
     * @return the peer's UDP port, or 0 for a hash-only id
     */
    public int getPort() { return _port; }

    /**
     * The peer hash, or null if direct.
     * @return the hash of the peer's RouterIdentity, or null for a direct id
     */
    public Hash getPeerHash() { return _peerHash; }

    @Override
    public int hashCode() {
        return _hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;
        if (!(obj instanceof RemoteHostId))
            return false;
        RemoteHostId id = (RemoteHostId)obj;
        return (_port == id._port) && DataHelper.eq(_ip, id._ip) && DataHelper.eq(_peerHash, id._peerHash);
    }

    @Override
    public String toString() {
        if (_ip != null) {
            return Addresses.toString(_ip, _port);
        } else {
            return _peerHash.toBase64();
        }
    }
}
