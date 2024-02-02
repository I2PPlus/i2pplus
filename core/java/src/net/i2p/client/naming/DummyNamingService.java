/*
 * free (adj.): unencumbered; not under the control of others
 * Written by mihi in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */
package net.i2p.client.naming;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.LHMCache;
import net.i2p.util.SystemVersion;

/**
 * A Dummy naming service that can only handle base64 and b32 destinations.
 *
 * @since public since 0.9.31
 */
public class DummyNamingService extends NamingService {

    protected static final int BASE32_HASH_LENGTH = 52;   // 1 + Hash.HASH_LENGTH * 8 / 5
    public final static String PROP_B32 = "i2p.naming.hostsTxt.useB32";
    protected static final int CACHE_MAX_SIZE = SystemVersion.isAndroid() ? 32 : 128;
    public static final int DEST_SIZE = 516;                    // Std. Base64 length (no certificate)

    /**
     *  The LRU cache, with no expiration time.
     *  Classes should take care to call removeCache() for any entries that
     *  are invalidated.
     */
    private static final Map<String, Destination> _cache = new LHMCache<String, Destination>(CACHE_MAX_SIZE);

    /**
     * The naming service should only be constructed and accessed through the
     * application context.  This constructor should only be used by the
     * appropriate application context itself.
     *
     */
    protected DummyNamingService(I2PAppContext context) {
        super(context);
    }

    /**
     *  @param hostname mixed case as it could be a key
     *  @param lookupOptions input parameter, NamingService-specific, can be null
     *  @param storedOptions output parameter, NamingService-specific, any stored properties will be added if non-null
     *  @return dest or null
     *  @since 0.8.7
     */
    @Override
    public Destination lookup(String hostname, Properties lookupOptions, Properties storedOptions) {
        if (hostname.endsWith(".i2p.alt")) {
            // RFC 9476
            hostname = hostname.substring(0, hostname.length() - 4);
        }
        Destination d = getCache(hostname);
        if (d != null)
            return d;

        // If it's long, assume it's a key.
        if (hostname.length() >= 516) {
            d = lookupBase64(hostname);
            // What the heck, cache these too
            if (d != null)
                putCache(hostname, d);
            return d;
        }

        // Try Base32 decoding
        if (hostname.length() >= BASE32_HASH_LENGTH + 8 && hostname.toLowerCase(Locale.US).endsWith(".b32.i2p") &&
                _context.getBooleanPropertyDefaultTrue(PROP_B32)) {
            try {
                if (hostname.length() == BASE32_HASH_LENGTH + 8) {
                    // b32
                    d = LookupDest.lookupBase32Hash(_context, hostname.substring(0, BASE32_HASH_LENGTH));
                } else {
                    // b33
                    d = LookupDest.lookupHostname(_context, hostname);
                }
                if (d != null) {
                    putCache(hostname, d);
                    return d;
                }
            } catch (I2PSessionException i2pse) {
                _log.warn("couldn't lookup b32",i2pse);
            }
        }

        return null;
    }

    /**
     *  Provide basic static caching for all services
     *  @param s case-sensitive, could be a hostname or a full b64 string
     */
    protected static void putCache(String s, Destination d) {
        if (d == null)
            return;
        synchronized (_cache) {
            _cache.put(s, d);
        }
    }

    /**
     *  @param s case-sensitive, could be a hostname or a full b64 string
     *  @return cached dest or null
     */
    protected static Destination getCache(String s) {
        synchronized (_cache) {
            return _cache.get(s);
        }
    }

    /**
     *  @param s case-sensitive, could be a hostname or a full b64 string
     *  @since 0.8.7
     */
    protected static void removeCache(String s) {
        synchronized (_cache) {
            _cache.remove(s);
        }
    }

    /** @since 0.8.1 */
    protected static void clearCache() {
        synchronized (_cache) {
            _cache.clear();
        }
    }
}
