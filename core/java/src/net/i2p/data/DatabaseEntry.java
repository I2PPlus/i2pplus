package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */


import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigAlgo;
import net.i2p.crypto.SigType;

/**
 * Base class for data structures stored in the I2P network database (NetDb).
 * 
 * <p>DatabaseEntry provides common infrastructure for NetDb-storable objects:</p>
 * <ul>
 *   <li><strong>Core Types:</strong> Base for {@link LeaseSet} and {@link net.i2p.data.router.RouterInfo}</li>
 *   <li><strong>Unified Interface:</strong> Simplifies NetDB and I2NP implementation</li>
 *   <li><strong>Common Operations:</strong> Shared methods for hashing, routing, and signing</li>
 *   <li><strong>Integrity Protection:</strong> Prevents modification after signing</li>
 * </ul>
 * 
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li><strong>SHA-256 Hash:</strong> Unique identifier for database storage and lookup</li>
 *   <li><strong>Routing Key:</strong> Modified hash for distributed hash Table (DHT) routing</li>
 *   <li><strong>Timestamp:</strong> Creation or last-modified time for cache management</li>
 *   <li><strong>Signatures:</strong> Cryptographic signatures for authenticity verification</li>
 *   <li><strong>Type Constants:</strong> Standardized type identifiers for all NetDb entries</li>
 * </ul>
 * 
 * <p><strong>Supported Entry Types:</strong></p>
 * <ul>
 *   <li>{@link #KEY_TYPE_ROUTERINFO} - Router information and capabilities</li>
 *   <li>{@link #KEY_TYPE_LEASESET} - Legacy LeaseSet format</li>
 *   <li>{@link #KEY_TYPE_LS2} - Modern LeaseSet2 format</li>
 *   <li>{@link #KEY_TYPE_META_LS2} - MetaLeaseSet for advanced routing</li>
 * </ul>
 * 
 * <p><strong>Integrity Protection:</strong></p>
 * <ul>
 *   <li><strong>Immutable After Signing:</strong> Objects cannot be modified once signed</li>
 *   <li><strong>IllegalStateException:</strong> Thrown when modification is attempted</li>
 *   <li><strong>NetDb Protection:</strong> Prevents corruption of network database</li>
 *   <li><strong>Message Safety:</strong> Protects I2NP messages from tampering</li>
 * </ul>
 * 
 * <p><strong>Usage Patterns:</strong></p>
 * <ul>
 *   <li><strong>Network Storage:</strong> Entries stored in distributed hash table</li>
 *   <li><strong>Lookup Operations:</strong> Fast retrieval by hash or routing key</li>
 *   <li><strong>Cache Management:</strong> Timestamp-based expiration and refresh</li>
 *   <li><strong>Floodfill:</strong> Distribution of new and updated entries</li>
 * </ul>
 * 
 * <p><strong>Security Considerations:</strong></p>
 * <ul>
 *   <li><strong>Signature Verification:</strong> Always verify before trusting entries</li>
 *   <li><strong>Timestamp Validation:</strong> Check for stale or expired entries</li>
 *   <li><strong>Hash Collision:</strong> Verify SHA-256 hash matches content</li>
 *   <li><strong>Routing Key Security:</strong> Protect against routing attacks</li>
 * </ul>
 * 
 * <p><strong>Implementation Notes:</strong></p>
 * <ul>
 *   <li><strong>0.8.2:</strong> Initial implementation with retrofitting</li>
 *   <li><strong>Code Consolidation:</strong> Reduces instanceof usage throughout codebase</li>
 *   <li><strong>Thread Safety:</strong> Protection not guaranteed to be thread-safe</li>
 *   <li><strong>Object Reuse:</strong> Do not reuse DatabaseEntry instances</li>
 * </ul>
 * 
 * <p><strong>Warning:</strong></p>
 * <ul>
 *   <li><strong>Direct Modification:</strong> Avoid modifying internal byte[] objects</li>
 *   <li><strong>Corruption Avenues:</strong> Multiple paths can bypass protection</li>
 *   <li><strong>Careful Usage:</strong> Protection is not foolproof against all attacks</li>
 * </ul>
 *
 * @author zzz
 * @since 0.8.2
 */
public abstract class DatabaseEntry extends DataStructureImpl {
    /** these are the same as in i2np's DatabaseStoreMessage */
    public final static int KEY_TYPE_ROUTERINFO = 0;
    public final static int KEY_TYPE_LEASESET = 1;
    /** @since 0.9.38 */
    public final static int KEY_TYPE_LS2 = 3;
    /** @since 0.9.38 */
    public final static int KEY_TYPE_ENCRYPTED_LS2 = 5;
    /** @since 0.9.38 */
    public final static int KEY_TYPE_META_LS2 = 7;
    /** @since 0.9.38 */
    public final static int KEY_TYPE_SERVICE_RECORD = 9;
    /** @since 0.9.38 */
    public final static int KEY_TYPE_SERVICE_LIST = 11;

    protected volatile Signature _signature;
    // synch: this
    private Hash _currentRoutingKey;
    private long _routingKeyGenMod;
    private volatile boolean _receivedAsPublished;
    private volatile boolean _receivedAsReply;

    /**
     * Hash of the client receiving the routerinfo, or null if it was sent directly.
     */
    private Hash _receivedBy;

    /**
     * The Hash of the local client that received this LS,
     * null if the router or unknown.
     *
     * @since 0.9.61
     */
    public Hash getReceivedBy() {
        return _receivedBy;
    }

    /**
     * @since 0.9.61
     */
    public void setReceivedBy(Hash receivedBy) {
        this._receivedBy = receivedBy;
    }

    /**
     * A common interface to the timestamp of the two subclasses.
     * Identical to getEarliestLeaseDate() in LeaseSet,
     * and getPublished() in RouterInfo.
     * Note that for a LeaseSet this will be in the future,
     * and for a RouterInfo it will be in the past.
     * Either way, it's a timestamp.
     *
     * @since 0.8.2
     */
    public abstract long getDate();

    /**
     * Get the keys and the cert
     * Identical to getDestination() in LeaseSet,
     * and getIdentity() in RouterInfo.
     *
     * @return KAC or null
     * @since 0.8.2, public since 0.9.17
     */
    public abstract KeysAndCert getKeysAndCert();

    /**
     * A common interface to the Hash of the two subclasses.
     * Identical to getDestination().calculateHash() in LeaseSet,
     * and getIdentity().getHash() in RouterInfo.
     *
     * @return Hash or null
     * @since 0.8.2
     */
    public Hash getHash() {
        KeysAndCert kac = getKeysAndCert();
        if (kac == null)
            return null;
        return kac.getHash();
    }

    /**
     * Get the type of the data structure.
     * This should be faster than instanceof.
     *
     * @return KEY_TYPE_ROUTERINFO or KEY_TYPE_LEASESET or LS2 types
     * @since 0.8.2
     */
    public abstract int getType();

    /**
     * Convenience method, is the type any variant of leaseset?
     *
     * @return true for any type of LeaseSet, false for RouterInfo, false for others
     * @since 0.9.38
     */
    public boolean isLeaseSet() {
        return isLeaseSet(getType());
    }

    /**
     * Convenience method, is the type any variant of leaseset?
     *
     * @return true for any type of LeaseSet, false for RouterInfo, false for others
     * @since 0.9.38
     */
    public static boolean isLeaseSet(int type) {
        return type == KEY_TYPE_LEASESET ||
               type == KEY_TYPE_LS2 ||
               type == KEY_TYPE_ENCRYPTED_LS2 ||
               type == KEY_TYPE_META_LS2;
    }

    /**
     * Convenience method, is the type any variant of router info?
     *
     * @return true for any type of RouterInfo, false for LeaseSet, false for others
     * @since x.x.x
     */
    public boolean isRouterInfo() {
        return (getType() == KEY_TYPE_ROUTERINFO);
    }

    /**
     * Returns the raw payload data, excluding the signature, to be signed by sign().
     *
     * Most callers should use writeBytes() or toByteArray() instead.
     *
     * FIXME RouterInfo throws DFE and LeaseSet returns null
     * @return null on error ???????????????????????
     */
    protected abstract byte[] getBytes() throws DataFormatException;

    /**
     * Get the routing key for the structure using the current modifier in the RoutingKeyGenerator.
     * This only calculates a new one when necessary though (if the generator's key modifier changes)
     *
     * @throws IllegalStateException if not in RouterContext
     */
    public Hash getRoutingKey() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!ctx.isRouterContext())
            throw new IllegalStateException("Not in router context");
        RoutingKeyGenerator gen = ctx.routingKeyGenerator();
        long mod = gen.getLastChanged();
        synchronized(this) {
            if (mod != _routingKeyGenMod) {
                _currentRoutingKey = gen.getRoutingKey(getHash());
                _routingKeyGenMod = mod;
            }
            return _currentRoutingKey;
        }
    }

    /**
     * @throws IllegalStateException if not in RouterContext
     */
    public boolean validateRoutingKey() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!ctx.isRouterContext())
            throw new IllegalStateException("Not in router context");
        RoutingKeyGenerator gen = ctx.routingKeyGenerator();
        Hash destKey = getHash();
        Hash rk = gen.getRoutingKey(destKey);
        return rk.equals(getRoutingKey());
    }

    /**
     * Retrieve the proof that the identity stands behind the info here
     *
     */
    public Signature getSignature() {
        return _signature;
    }

    /**
     * Configure the proof that the entity stands behind the info here
     *
     * @throws IllegalStateException if already signed
     */
    public void setSignature(Signature signature) {
        if (_signature != null)
            throw new IllegalStateException();
        _signature = signature;
    }

    /**
     * Sign the structure using the supplied signing key
     *
     * @throws IllegalStateException if already signed
     */
    public void sign(SigningPrivateKey key) throws DataFormatException {
        if (_signature != null)
            throw new IllegalStateException();
        byte[] bytes = getBytes();
        if (bytes == null) throw new DataFormatException("Not enough data to sign");
        if (key == null)
            throw new DataFormatException("No signing key");
        // now sign with the key
        _signature = DSAEngine.getInstance().sign(bytes, key);
        if (_signature == null)
            throw new DataFormatException("Signature failed with " + key.getType() + " key");
    }

    /**
     * Identical to getDestination().getSigningPublicKey() in LeaseSet,
     * and getIdentity().getSigningPublicKey() in RouterInfo.
     *
     * @return SPK or null
     * @since 0.8.2
     */
    protected SigningPublicKey getSigningPublicKey() {
        KeysAndCert kac = getKeysAndCert();
        if (kac == null)
            return null;
        return kac.getSigningPublicKey();
    }

    /**
     * This is the same as isValid() in RouterInfo
     * or verifySignature() in LeaseSet.
     * @return valid
     * @since public since 0.9.47, was protected
     */
    public boolean verifySignature() {
        if (_signature == null)
            return false;
        byte data[];
        try {
            data = getBytes();
        } catch (DataFormatException dfe) {
            return false;
        }
        if (data == null)
            return false;
        // if the data is non-null the SPK will be non-null
        SigningPublicKey spk = getSigningPublicKey();
        SigType type = spk.getType();
        // As of 0.9.28, disallow RSA as it's so slow it could be
        // used as a DoS
        if (type == null || type.getBaseAlgorithm() == SigAlgo.RSA)
            return false;
        return DSAEngine.getInstance().verifySignature(_signature, data, spk);
    }

    /**
     * If true, we received this LeaseSet by a remote peer publishing it to
     * us, rather than by searching for it ourselves or locally creating it.
     * Default false.
     *
     * @since 0.9.58 moved up from LeaseSet
     */
    public boolean getReceivedAsPublished() {
        return _receivedAsPublished;
    }

    /**
     * @since 0.9.58 moved up from LeaseSet
     *
     * use this carefully, when updating the flags make sure the old and new
     * leaseSet are actually equivalent, or simply copy over the reply value,
     * see KademliaNetworkDatabaseFacade.java line 997 for more information.
     */
    public void setReceivedAsPublished() {
        _receivedAsPublished = true;
    }

    /**
     * If true, we received this LeaseSet by searching for it
     * Default false.
     *
     * @since 0.7.14, moved up from LeaseSet in 0.9.58
     */
    public boolean getReceivedAsReply() {
        return _receivedAsReply;
    }

    /**
     * set to true
     *
     * @since 0.7.14, moved up from LeaseSet in 0.9.58
     */
    public void setReceivedAsReply() {
        _receivedAsReply = true;
    }
}
