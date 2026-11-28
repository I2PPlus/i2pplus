package net.i2p.router.networkdb.kademlia;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import java.util.concurrent.ConcurrentHashMap;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 * Cache for blinding data as specified in I2P proposal 123.
 * <p>
 * Manages blinded and unblinded key mappings with persistent storage
 * and automatic rollover at midnight UTC. Provides lookup operations
 * for both blinded destinations and their corresponding unblinded hashes.
 *
 * @since 0.9.40
 */
class BlindCache {

    /** The router doing the lookups. */
    private final RouterContext _context;
    /** The blind data, by our unblinded signing key. */
    private final ConcurrentHashMap<SigningPublicKey, BlindData> _cache;
    /** The blind data, by the blinded peer signing key. */
    private final ConcurrentHashMap<SigningPublicKey, BlindData> _reverseCache;
    /** The blind data, by destination hash. */
    private final ConcurrentHashMap<Hash, BlindData> _hashCache;
    /** True when the cache awaits a save. */
    private boolean _changed;

    /** File the cache is persisted to. */
    private static final String PERSIST_FILE = "router.blindcache.dat";
    /** Most entries kept in the cache. */
    private static final int MAX_CACHE_SIZE = 32;

    /**
     * Stat name for how many blind entries the cache held when it was shut down.
     * Written once per netdb shutdown, so it reads as an absent stat for most of
     * a router's life and only carries a value after a netdb restart.
     */
    static final String STAT_CACHE_SIZE = "netDb.blindCache.size";

    /**
     * Periods for {@link #STAT_CACHE_SIZE}. The value is written once, at netdb
     * shutdown, so the day-long period is the one that still holds it when the
     * console next asks.
     */
    private static final long[] RATES = RateConstants.STANDARD_RATES;

    /**
     * Create the one counter this class writes.
     * <p>
     * {@link net.i2p.stat.StatManager#addRateData(String, long)} looks the name
     * up and drops the write when no such stat was created, so this counter
     * reported nothing while looking like a working one. The name was also
     * spelled <code>netdb.</code>, lower-case, while every other counter in the
     * group is <code>netDb.</code>, so it sat outside the group the console and
     * the Tuner rules read even in the runs where it did get written. The name
     * is a constant shared by the write site and the registration so the two
     * cannot drift apart.
     * <p>
     * Declared as required, not plain: {@code createRateStat()} is a no-op
     * unless <code>stat.full</code> is set, and its default is false.
     *
     * @param stats the context's stat manager, never null
     * @since 0.9.72+
     */
    static void registerStats(StatManager stats) {
        stats.createRequiredRateStat(STAT_CACHE_SIZE, "Blind entries held at netdb shutdown",
                                     "NetworkDatabase", RATES);
    }

    /**
     * Caller MUST call {@link #startup()} to load persistent cache from disk.
     *
     * @param ctx the router context
     */
    public BlindCache(RouterContext ctx) {
        _context = ctx;
        _cache = new ConcurrentHashMap<>(32);
        _reverseCache = new ConcurrentHashMap<>(32);
        _hashCache = new ConcurrentHashMap<>(32);
        registerStats(ctx.statManager());
    }

    /**
     * May be restarted by calling startup() again.
     */
    public synchronized void shutdown() {
        if (_changed)
            store();
        _context.statManager().addRateData(STAT_CACHE_SIZE, _cache.size());
        _cache.clear();
        _reverseCache.clear();
        _hashCache.clear();
    }

    /**
     * Enforce maximum cache size.
     */
    private void enforceMaxSize() {
        while (_cache.size() > MAX_CACHE_SIZE) {
            SigningPublicKey key = _cache.keySet().iterator().next();
            BlindData bd = _cache.remove(key);
            if (bd != null) {
                _reverseCache.remove(bd.getBlindedPubKey());
                Hash h = bd.getDestHash();
                if (h != null) _hashCache.remove(h);
            }
        }
    }

    /** Load persistent cache from disk. */
    public synchronized void startup() {
        load();
    }

    /**
     * The hash to lookup for a dest.
     * If known to be blinded, returns the current blinded hash.
     * If not known to be blinded, returns the standard dest hash.
     *
     * @param dest may or may not be blinded
     * @return the unblinded or blinded hash
     */
    public Hash getHash(Destination dest) {
        Hash rv = getBlindedHash(dest);
        if (rv != null)
            return rv;
        return dest.getHash();
    }

    /**
     * The hash to lookup for a dest hash.
     * If known to be blinded, returns the current blinded hash.
     * If not known to be blinded, returns h.
     *
     * @param h may or may not be blinded
     * @return the blinded hash or h
     */
    public Hash getHash(Hash h) {
        BlindData bd = _hashCache.get(h);
        if (bd != null)
            return bd.getBlindedHash();
        return h;
    }

    /**
     * The hash to lookup for a dest.
     * If known to be blinded, returns the current blinded hash.
     * If not known to be blinded, returns null.
     *
     * @param dest may or may not be blinded
     * @return the blinded hash or null if not blinded
     */
    public Hash getBlindedHash(Destination dest) {
        BlindData bd = _cache.get(dest.getSigningPublicKey());
        if (bd != null)
            return bd.getBlindedHash();
        return null;
    }

    /**
     * The hash to lookup for a SPK known to be blinded.
     * Default blinded type assumed.
     * Secret assumed null.
     *
     * @param spk known to be blinded
     * @return the blinded hash
     * @throws IllegalArgumentException on various errors
     */
    public Hash getBlindedHash(SigningPublicKey spk) {
        BlindData bd = _cache.get(spk);
        if (bd == null)
            bd = new BlindData(_context, spk, Blinding.getDefaultBlindedType(spk.getType()), null);
        addToCache(bd);
        return bd.getBlindedHash();
    }

    /**
     * Mark a destination as known to be blinded.
     *
     * @param dest known to be blinded
     * @param blindedType null for default
     * @param secret may be null
     * @throws IllegalArgumentException on various errors
     */
    public void setBlinded(Destination dest, SigType blindedType, String secret) {
        SigningPublicKey spk = dest.getSigningPublicKey();
        BlindData bd = _cache.get(spk);
        if (bd != null) {
            bd.setDestination(dest);
        } else {
            if (blindedType == null)
                blindedType = Blinding.getDefaultBlindedType(spk.getType());
            bd = new BlindData(_context, dest, blindedType, secret);
            bd.setDestination(dest);
            addToCache(bd);
        }
    }

    /**
     * Add the destination to the cache entry.
     * Must previously be in cache.
     *
     * @param dest known to be blinded
     * @throws IllegalArgumentException on various errors
     */
    public void setBlinded(Destination dest) {
        SigningPublicKey spk = dest.getSigningPublicKey();
        BlindData bd = _cache.get(spk);
        if (bd != null) {
            bd.setDestination(dest);
            _hashCache.putIfAbsent(dest.getHash(), bd);
        }
    }

    /**
     * Persists immediately if secret or privkey is non-null.
     *
     * @param bd the blind data to add
     */
    public void addToCache(BlindData bd) {
        storeInCache(bd);
        if (bd.getSecret() != null || bd.getAuthPrivKey() != null) {
            store();
        } else {
            synchronized(this) { _changed = true; }
        }
        enforceMaxSize();
    }

    /**
     * Add blinded lookup data to the cache.
     *
     * @param bd the blind data to place in the in-memory caches
     * @throws IllegalArgumentException if the blinded key cannot be derived, a
     *                                  malformed key or an unsupported blinded
     *                                  signature type
     * @since 0.9.41 from {@link #addToCache(BlindData)}
     */
    private void storeInCache(BlindData bd) {
        _cache.put(bd.getUnblindedPubKey(), bd);
        _reverseCache.put(bd.getBlindedPubKey(), bd);
        Destination dest = bd.getDestination();
        if (dest != null)
            _hashCache.put(dest.getHash(), bd);
    }

    /**
     * The cached data or null.
     *
     * @param dest the destination to look up
     * @return the cached BlindData or null
     */
    public BlindData getData(Destination dest) {
        BlindData rv = getData(dest.getSigningPublicKey());
        if (rv != null) {
            Destination d = rv.getDestination();
            if (d == null)
                rv.setDestination(dest);
            else if (!dest.equals(d))
                rv = null; // mismatch ???
        }
        return rv;
    }

    /**
     * The cached data or null.
     *
     * @param spk the unblinded public key
     * @return the cached BlindData or null
     */
    public BlindData getData(SigningPublicKey spk) {
        SigType type = spk.getType();
        if (type != SigType.EdDSA_SHA512_Ed25519 &&
            type != SigType.RedDSA_SHA512_Ed25519)
            return null;
        return _cache.get(spk);
    }

    /**
     * The cached data or null.
     *
     * @param spk the blinded public key
     * @return the cached BlindData or null
     */
    public BlindData getReverseData(SigningPublicKey spk) {
        SigType type = spk.getType();
        if (type != SigType.RedDSA_SHA512_Ed25519)
            return null;
        return _reverseCache.get(spk);
    }

    /**
     * Refresh all the data at midnight.
     * <p>
     * An entry whose blinded key cannot be re-derived for the new day is dropped
     * from the cache instead of aborting the pass. One bad entry would otherwise
     * leave every entry after it without a reverse-cache mapping, and the caller
     * in the routing key job with an exception instead of a requeue.
     */
    public synchronized void rollover() {
        _reverseCache.clear();
        for (Iterator<BlindData> iter = _cache.values().iterator(); iter.hasNext(); ) {
            BlindData bd = iter.next();
            try {
                _reverseCache.put(bd.getBlindedPubKey(), bd);
            } catch (IllegalArgumentException iae) {
                iter.remove();
            }
        }
    }

    /**
     * For console ConfigKeyringHelper.
     * @return list is copied
     * @since 0.9.41
     */
    public synchronized List<BlindData> getData() {
        List<BlindData> rv = new ArrayList<>(_cache.size());
        rv.addAll(_cache.values());
        return rv;
    }

    /**
     * For console ConfigKeyringHelper.
     * Persists immediately if removed.
     *
     * @param spk the unblinded public key
     * @return true if removed
     * @since 0.9.41
     */
    public boolean removeBlindData(SigningPublicKey spk) {
        boolean rv = false;
        BlindData bd = _cache.remove(spk);
        if (bd != null) {
            rv = true;
            _reverseCache.remove(bd.getBlindedPubKey());
            Hash h = bd.getDestHash();
            if (h != null)
                _hashCache.remove(h);
            store();
        }
        return rv;
    }

    /**
     * Load from file.
     * Format:
     * sigtype,bsigtype,authtype,timestamp,b64 pubkey,[b64 secret],[b64 auth privkey],[b64 dest]
     * <p>
     * If timestamp is positive, it's a creation date;
     * if negative, it's a negative expiration date.
     */
    private synchronized void load() {
        File file = new File(_context.getConfigDir(), PERSIST_FILE);
        if (!file.exists())
            return;
        Log log = _context.logManager().getLog(BlindCache.class);
        long now = _context.clock().now();
        int count = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new BufferedInputStream(new FileInputStream(file)), StandardCharsets.ISO_8859_1))) {
            String line = null;
            while ( (line = br.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                try {
                    BlindData bd = fromPersistentString(line);
                    long exp = bd.getExpiration();
                    if (exp > 0 && exp < now) {
                        if (log.shouldInfo())
                            log.info("Skipping expired entry " + bd);
                        continue;
                    }
                    storeInCache(bd);
                    count++;
                } catch (IllegalArgumentException iae) {
                    if (log.shouldWarn())
                        log.warn("Error reading cache entry: " + line, iae);
                } catch (DataFormatException dfe) {
                    if (log.shouldWarn())
                        log.warn("Error reading cache entry: " + line, dfe);
                }
            }
            _changed = false;
        } catch (IOException ioe) {
            if (log.shouldWarn() && file.exists())
                log.warn("Error reading the blinding cache file", ioe);
        }
        if (log.shouldInfo())
            log.info("Loaded " + count + " entries from " + file);
    }

    /** Writes the cache to the file. */
    private synchronized void store() {
        if (_cache.isEmpty())
            return;
        Log log = _context.logManager().getLog(BlindCache.class);
        int count = 0;
        File file = new File(_context.getConfigDir(), PERSIST_FILE);
        try (PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), StandardCharsets.ISO_8859_1)))) {
            out.println("# Blinding cache entries. Format is: sigtype,bsigtype,authtype,time,key,[secret],[privkey],[dest]");
            for (BlindData bd : _cache.values()) {
                 out.println(toPersistentString(bd));
                 count++;
            }
            if (out.checkError())
                throw new IOException("Failed write to " + file);
            _changed = false;
        } catch (IOException ioe) {
            if (log.shouldWarn())
                log.warn("Error writing the blinding cache File", ioe);
        }
        if (log.shouldInfo())
            log.info("Stored " + count + " entries to " + file);
    }

    /**
     * Format:
     * sigtype,bsigtype,authtype,timestamp,b64 pubkey,[b64 secret],[b64 auth privkey],[b64 dest]
     * <p>
     * If timestamp is positive, it's a creation date;
     * if negative, it's a negative expiration date.
     *
     * @param line the comma-separated record as written by {@link #toPersistentString(BlindData)}
     * @return the parsed blind data, never null
     * @throws DataFormatException if the line is malformed, or a signature type
     *                             is unknown or unavailable
     */
    private BlindData fromPersistentString(String line) throws DataFormatException {
        String[] ss = DataHelper.split(line, ",", 8);
        if (ss.length != 8)
            throw new DataFormatException("Bad format");
        int ist1;
        int ist2;
        int auth;
        long time;
        try {
            ist1 = Integer.parseInt(ss[0]);
            ist2 = Integer.parseInt(ss[1]);
            auth = Integer.parseInt(ss[2]);
            time = Long.parseLong(ss[3]);
        } catch (NumberFormatException nfe) {
            throw new DataFormatException("Bad codes", nfe);
        }
        SigType st1 = SigType.getByCode(ist1);
        SigType st2 = SigType.getByCode(ist2);
        if (st1 == null || !st1.isAvailable() || st2 == null || !st2.isAvailable())
            throw new DataFormatException("Bad codes");
        SigningPublicKey spk = new SigningPublicKey(st1);
        spk.fromBase64(ss[4]);
        String secret;
        if (!ss[5].isEmpty()) {
            byte[] b = Base64.decode(ss[5]);
            if (b == null)
                throw new DataFormatException("Bad secret");
            secret = DataHelper.getUTF8(b);
        } else {
            secret = null;
        }
        PrivateKey privkey;
        if (!ss[6].isEmpty()) {
            byte[] b = Base64.decode(ss[6]);
            if (b == null)
                throw new DataFormatException("Bad privkey");
            privkey = new PrivateKey(EncType.ECIES_X25519, b);
        } else {
            privkey = null;
        }
        BlindData rv;
        if (!ss[7].isEmpty()) {
            Destination dest = new Destination(ss[7]);
            if (!spk.equals(dest.getSigningPublicKey()))
                throw new DataFormatException("SigningPublickKey mismatch");
            rv = new BlindData(_context, dest, st2, secret, auth, privkey);
        } else {
            rv = new BlindData(_context, spk, st2, secret, auth, privkey);
        }
        if (time >= 0) {
            rv.setDate(time);
        } else {
            rv.setDate(_context.clock().now());
            rv.setExpiration(0 - time);
        }
        return rv;
    }

    /**
     * Format:
     * sigtype,bsigtype,authtype,timestamp,b64 pubkey,[b64 secret],[b64 auth privkey],[b64 dest]
     * <p>
     * If timestamp is positive, it's a creation date;
     * if negative, it's a negative expiration date.
     *
     * @param bd the blind data to encode
     * @return the comma-separated record, parseable by {@link #fromPersistentString(String)}
     */
    private static String toPersistentString(BlindData bd) {
        StringBuilder buf = new StringBuilder(1024);
        SigningPublicKey spk = bd.getUnblindedPubKey();
        buf.append(spk.getType().getCode()).append(',');
        buf.append(bd.getBlindedSigType().getCode()).append(',');
        buf.append(bd.getAuthType()).append(',');
        long time = bd.getExpiration();
        if (time > 0)
            time = 0 - time;
        else
            time = bd.getDate();
        buf.append(time).append(',');
        buf.append(spk.toBase64()).append(',');
        String secret = bd.getSecret();
        if (secret != null && !secret.isEmpty())
            buf.append(Base64.encode(secret));
        buf.append(',');
        PrivateKey pk = bd.getAuthPrivKey();
        if (pk != null)
            buf.append(pk.toBase64());
        buf.append(',');
        Destination dest = bd.getDestination();
        if (dest != null)
            buf.append(dest.toBase64());
        return buf.toString();
    }
}
