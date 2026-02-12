package net.i2p.router;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.RandomSource;
import net.i2p.util.SystemVersion;

/**
 * Wrap up the settings for a pool of tunnels.
 *
 */
public class TunnelPoolSettings {
    private final Hash _destination;
    private String _destinationNickname;
    private int _quantity;
    private int _backupQuantity;
    // private int _rebuildPeriod;
    //private int _duration;
    private int _length;
    private int _lengthVariance;
    private int _lengthOverride;
    private final boolean _isInbound;
    private final boolean _isExploratory;
    private boolean _allowZeroHop;
    private int _IPRestriction;
    private final Properties _unknownOptions;
    private SessionKey _randomKey;
    private int _priority;
    /** @since 0.9.68+ default true, set to false for ping tunnels */
    private boolean _shouldTest = true;
    private final Set<Hash> _aliases;
    private Hash _aliasOf;
    /** @since 0.9.68+ transient first peer exclusions for diversity */
    private transient Set<Hash> _firstPeerExclusions;
    /** @since 0.9.68+ transient last peer exclusions for diversity */
    private transient Set<Hash> _lastPeerExclusions;
    /** prefix used to configure the inbound exploratory pool */
    public static final String      PREFIX_INBOUND_EXPLORATORY = "router.inboundPool.";
    /** prefix used to configure the outbound exploratory pool */
    public static final String      PREFIX_OUTBOUND_EXPLORATORY = "router.outboundPool.";
    public static final String      PROP_NICKNAME = "nickname";
    public static final String      PROP_QUANTITY = "quantity";
    public static final String      PROP_BACKUP_QUANTITY = "backupQuantity";
    public static final String      PROP_DURATION = "duration";
    public static final String      PROP_LENGTH = "length";
    public static final String      PROP_LENGTH_VARIANCE = "lengthVariance";
    /** don't trust this, always true */
    public static final String      PROP_ALLOW_ZERO_HOP = "allowZeroHop";
    public static final String      PROP_IP_RESTRICTION = "IPRestriction";
    public static final String      PROP_PRIORITY = "priority";
    /** @since 0.9.17 */
    public static final String      PROP_RANDOM_KEY = "randomKey";
    /** @since 0.9.68+ */
    public static final String      PROP_SHOULD_TEST = "shouldTest";
    public static final int         DEFAULT_QUANTITY = 3;
    public static final int         DEFAULT_BACKUP_QUANTITY = 0;
    public static final int         DEFAULT_DURATION = 10*60*1000;
    /** Maximum tunnel quantity limit for exploratory pools */
    public static final int         MAX_EXPLORATORY_QUANTITY = 16;
    /** Maximum tunnel quantity limit for client pools */
    public static final int         MAX_CLIENT_QUANTITY = 16;
    private static final boolean    isSlow = SystemVersion.isSlow();
    /** client only */
    private static final int        DEFAULT_IB_LENGTH = 3;
    private static final int        DEFAULT_OB_LENGTH = 3;
    private static final int        DEFAULT_LENGTH_VARIANCE = 0;
    /** expl only */
    private static final int        DEFAULT_IB_EXPL_LENGTH = isSlow ? 2 : 3;
    private static final int        DEFAULT_OB_EXPL_LENGTH = isSlow ? 2 : 3;
    private static final int        DEFAULT_IB_EXPL_LENGTH_VARIANCE = isSlow ? 0 : 1;
    private static final int        DEFAULT_OB_EXPL_LENGTH_VARIANCE = isSlow ? 0 : 1;
    public static final boolean     DEFAULT_ALLOW_ZERO_HOP = false;
    public static final int         DEFAULT_IP_RESTRICTION = 1;    // class A (/8)
    private static final int        MIN_PRIORITY = -25;
    private static final int        MAX_PRIORITY = 25;
    private static final int        EXPLORATORY_PRIORITY = 30;
    private final long _msgIDBloomXor;

    /**
     *  Exploratory tunnel
     */
    public TunnelPoolSettings(boolean isInbound) {
        this(null, isInbound);
    }

    /**
     *  Client tunnel unless dest == null
     */
    public TunnelPoolSettings(Hash dest, boolean isInbound) {
        _destination = dest;
        _isExploratory = dest == null;
        _isInbound = isInbound;
        _quantity = DEFAULT_QUANTITY;
        _backupQuantity = DEFAULT_BACKUP_QUANTITY;
        // _rebuildPeriod = DEFAULT_REBUILD_PERIOD;
        //_duration = DEFAULT_DURATION;

        if (isInbound) {
            _length = _isExploratory ? DEFAULT_IB_EXPL_LENGTH : DEFAULT_IB_LENGTH;
            _lengthVariance = _isExploratory ? DEFAULT_IB_EXPL_LENGTH_VARIANCE : DEFAULT_LENGTH_VARIANCE;
        } else {
            _length = _isExploratory ? DEFAULT_OB_EXPL_LENGTH : DEFAULT_OB_LENGTH;
            _lengthVariance = _isExploratory ? DEFAULT_OB_EXPL_LENGTH_VARIANCE : DEFAULT_LENGTH_VARIANCE;
        }

        _lengthOverride = -1;
        _allowZeroHop = DEFAULT_ALLOW_ZERO_HOP;
        _IPRestriction = DEFAULT_IP_RESTRICTION;
        _unknownOptions = new Properties();
        _randomKey = generateRandomKey();
        _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);

        if (_isExploratory && !_isInbound)
            _priority = EXPLORATORY_PRIORITY;
        if (!_isExploratory)
            _aliases = new ConcurrentHashSet<Hash>(4);
        else
            _aliases = null;
    }

    /** how many tunnels should be available at all times */
    public int getQuantity() { return _quantity; }
    public void setQuantity(int quantity) {
        int max = _isExploratory ? MAX_EXPLORATORY_QUANTITY : MAX_CLIENT_QUANTITY;
        _quantity = Math.min(Math.max(quantity, 0), max);
    }

    /** how many backup tunnels should be kept waiting in the wings */
    public int getBackupQuantity() { return _backupQuantity; }
    public void setBackupQuantity(int quantity) {
        int max = _isExploratory ? MAX_EXPLORATORY_QUANTITY : MAX_CLIENT_QUANTITY;
        _backupQuantity = Math.min(Math.max(quantity, 0), max);
    }

    /**
     *  Convenience - enforces maximum limits
     *  @return getQuantity() + getBackupQuantity(), capped at max allowed
     *  @since 0.8.11
     */
    public int getTotalQuantity() {
        int max = _isExploratory ? MAX_EXPLORATORY_QUANTITY : MAX_CLIENT_QUANTITY;
        return Math.min(_quantity + _backupQuantity, max);
    }

    /** how long before tunnel expiration should new tunnels be built */
    // public int getRebuildPeriod() { return _rebuildPeriod; }
    // public void setRebuildPeriod(int periodMs) { _rebuildPeriod = periodMs; }

    /**
     *  How many remote hops should be in the tunnel NOT including us
     *  @return 0 to 7
     */
    public int getLength() { return _length; }

    /**
     *  How many remote hops should be in the tunnel NOT including us
     *  @param length 0 to 7 (not enforced here)
     */
    public void setLength(int length) { _length = length; }

    /**
     * If there are no tunnels to build with, will this pool allow 0 hop tunnels?
     *
     * As of 0.9.44, same goes for exploratory:
     * Prior to 0.9.35, generally true for client.
     * As of 0.9.35, generally false for client, but true if
     * getLength() + Math.min(getLengthVariance(), 0) &lt;= 0,
     * OR if getLengthOverride() == 0
     * OR if setAllowZeroHop(true) was called or set in properties.
     */
    public boolean getAllowZeroHop() {
        return _allowZeroHop ||
               _length + Math.min(_lengthVariance, 0) <= 0 ||
               _lengthOverride == 0;
    }

    /**
     * If there are no tunnels to build with, will this pool allow 0 hop tunnels?
     * No effect on exploratory (always true)
     *
     * @param ok if true, getAllowZeroHop() will always return true
     *           if false, getAllowZeroHop will return as documented.
     * @deprecated unused
     */
    @Deprecated
    public void setAllowZeroHop(boolean ok) {
        if (!_isExploratory)
            _allowZeroHop = ok;
    }

    /**
     * how should the length be varied.  if negative, this randomly skews from
     * (length - variance) to (length + variance), or if positive, from length
     * to (length + variance), inclusive.
     *
     */
    public int getLengthVariance() { return _lengthVariance; }
    public void setLengthVariance(int variance) { _lengthVariance = variance; }

    /**
     * A temporary length to be used due to network conditions.
     * If less than zero, the standard length should be used.
     * Unused until 0.8.11
     */
    public int getLengthOverride() { return _lengthOverride; }

    /**
     * A temporary length to be used due to network conditions.
     * If less than zero, the standard length will be used.
     * Unused until 0.8.11
     */
    public void setLengthOverride(int length) { _lengthOverride = length; }

    /** is this an inbound tunnel? */
    public boolean isInbound() { return _isInbound; }

    /** is this an exploratory tunnel (or a client tunnel) */
    public boolean isExploratory() { return _isExploratory; }

    // Duration is hardcoded
    //public int getDuration() { return _duration; }
    //public void setDuration(int ms) { _duration = ms; }

    /** what destination is this a client tunnel for (or null if exploratory) */
    public Hash getDestination() { return _destination; }

    /**
     *  Other destinations that use the same tunnel (or null if exploratory)
     *  Modifiable, concurrent, not a copy
     *  @since 0.9.21
     */
    public Set<Hash> getAliases() {
        return _aliases;
    }

    /**
     *  Other destination that this is an alias of (or null).
     *  If non-null, don't build tunnels.
     *  @since 0.9.21
     */
    public Hash getAliasOf() {
        return _aliasOf;
    }


    /**
     *  Set other destination that this is an alias of (or null).
     *  If non-null, don't build tunnels.
     *  @since 0.9.21
     */
    public void setAliasOf(Hash h) {
        _aliasOf = h;
    }

    /**
     *  Get the set of first peer exclusions for diversity.
     *  These are peers that should not be used as the first hop in new tunnels
     *  because they are already used as the first hop in existing tunnels.
     *  @return Set of peer hashes to exclude, or null if none set
     *  @since 0.9.68+
     */
    public Set<Hash> getFirstPeerExclusions() {
        return _firstPeerExclusions;
    }

    /**
     *  Set the first peer exclusions for diversity.
     *  These are peers that should not be used as the first hop in new tunnels
     *  because they are already used as the first hop in existing tunnels.
     *  This is a transient setting used during tunnel building.
     *  @param exclusions Set of peer hashes to exclude, or null to clear
     *  @since 0.9.68+
     */
    public void setFirstPeerExclusions(Set<Hash> exclusions) {
        _firstPeerExclusions = exclusions;
    }

    /**
     *  Get the set of last peer exclusions for diversity.
     *  These are peers that should not be used as the last hop in new tunnels
     *  because they are already used as the last hop in existing tunnels.
     *  @return Set of peer hashes to exclude, or null if none set
     *  @since 0.9.68+
     */
    public Set<Hash> getLastPeerExclusions() {
        return _lastPeerExclusions;
    }

    /**
     *  Set the last peer exclusions for diversity.
     *  These are peers that should not be used as the last hop in new tunnels
     *  because they are already used as the last hop in existing tunnels.
     *  This is a transient setting used during tunnel building.
     *  @param exclusions Set of peer hashes to exclude, or null to clear
     *  @since 0.9.68+
     */
    public void setLastPeerExclusions(Set<Hash> exclusions) {
        _lastPeerExclusions = exclusions;
    }

    /**
     *  random key used for peer ordering
     *
     *  @return non-null
     */
    public SessionKey getRandomKey() { return _randomKey; }

    /** what user supplied name was given to the client connected (can be null) */
    public String getDestinationNickname() { return _destinationNickname; }
    public void setDestinationNickname(String name) { _destinationNickname = name; }

    /**
     *  How many bytes to match to determine if a router's IP is too close to another's
     *  to be in the same tunnel
     *  (1-4, 0 to disable)
     *
     *  Support removed in the ClientPeerSelector in 0.8.6; restored in 0.9.53
     *
     *  @return 0-4 Number of bytes to match to determine if peers in the same IP range should
     *          not be in the same tunnel. 0 = disable check; 1 = /8; 2 = /16; 3 = /24; 4 = exact IP match
     */
    public int getIPRestriction() { return _IPRestriction; }

    /**
     *  Outbound message priority - for outbound tunnels only
     *  @return -25 to +30, default 30 for outbound exploratory and 0 for others
     *  @since 0.9.4
     */
    public int getPriority() { return _priority; }

    /**
     *  Whether this tunnel should be tested.
     *  @return true (default) for most tunnels, false for ping tunnels and other short-lived tunnels
     *  @since 0.9.68+
     */
    public boolean shouldTest() { return _shouldTest; }

    /**
     *  @return non-null
     */
    public Properties getUnknownOptions() { return _unknownOptions; }

    public long getMsgIdBloomXor() { return _msgIDBloomXor; }

    /**
     *  Defaults in props are NOT honored.
     *  In-JVM client side must promote defaults to the primary map.
     *
     *  @param prefix non-null
     */
    public void readFromProperties(String prefix, Properties props) {
        for (Map.Entry<Object, Object> e : props.entrySet()) {
            String name = (String) e.getKey();
            String value = (String) e.getValue();
            if (name.startsWith(prefix)) {
                if (name.equalsIgnoreCase(prefix + PROP_ALLOW_ZERO_HOP)) {
                    if (!_isExploratory)
                        _allowZeroHop = getBoolean(value, DEFAULT_ALLOW_ZERO_HOP);
                } else if (name.equalsIgnoreCase(prefix + PROP_BACKUP_QUANTITY))
                    _backupQuantity = getInt(value, DEFAULT_BACKUP_QUANTITY);
                //else if (name.equalsIgnoreCase(prefix + PROP_DURATION))
                //    _duration = getInt(value, DEFAULT_DURATION);
                else if (name.equalsIgnoreCase(prefix + PROP_LENGTH))
                    _length = getInt(value, _isInbound ?
                                            (_isExploratory ? DEFAULT_IB_EXPL_LENGTH : DEFAULT_IB_LENGTH) :
                                            (_isExploratory ? DEFAULT_OB_EXPL_LENGTH : DEFAULT_OB_LENGTH));
                else if (name.equalsIgnoreCase(prefix + PROP_LENGTH_VARIANCE))
                    _lengthVariance = getInt(value, _isExploratory ?
                                                        (_isInbound ?
                                                             DEFAULT_IB_EXPL_LENGTH_VARIANCE :
                                                             DEFAULT_OB_EXPL_LENGTH_VARIANCE) :
                                                        DEFAULT_LENGTH_VARIANCE);
                else if (name.equalsIgnoreCase(prefix + PROP_QUANTITY))
                    _quantity = getInt(value, DEFAULT_QUANTITY);
                // else if (name.equalsIgnoreCase(prefix + PROP_REBUILD_PERIOD))
                //     _rebuildPeriod = getInt(value, DEFAULT_REBUILD_PERIOD);
                else if (name.equalsIgnoreCase(prefix + PROP_NICKNAME))
                    _destinationNickname = value;
                else if (name.equalsIgnoreCase(prefix + PROP_IP_RESTRICTION)) {
                    int r = getInt(value, DEFAULT_IP_RESTRICTION);
                    if (r > 4)
                        r = 4;
                    else if (r < 0)
                        r = 0;
                    _IPRestriction = r;
                } else if ((!_isInbound) && name.equalsIgnoreCase(prefix + PROP_PRIORITY)) {
                     int def = _isExploratory ? EXPLORATORY_PRIORITY : 0;
                     int max = _isExploratory ? EXPLORATORY_PRIORITY : MAX_PRIORITY;
                    _priority = Math.min(max, Math.max(MIN_PRIORITY, getInt(value, def)));
                } else if (name.equalsIgnoreCase(prefix + PROP_RANDOM_KEY)) {
                    byte[] rk = Base64.decode(value);
                    if (rk != null && rk.length == SessionKey.KEYSIZE_BYTES)
                        _randomKey = new SessionKey(rk);
                } else if (name.equalsIgnoreCase(prefix + PROP_SHOULD_TEST)) {
                    _shouldTest = getBoolean(value, true);
                } else
                    _unknownOptions.setProperty(name.substring(prefix.length()), value);
            }
        }
    }

    /**
     *  @param prefix non-null
     */
    public void writeToProperties(String prefix, Properties props) {
        if (props == null) return;
        props.setProperty(prefix + PROP_ALLOW_ZERO_HOP, Boolean.toString(_allowZeroHop));
        props.setProperty(prefix + PROP_BACKUP_QUANTITY, Integer.toString(_backupQuantity));
        //props.setProperty(prefix + PROP_DURATION, ""+_duration);
        props.setProperty(prefix + PROP_LENGTH, Integer.toString(_length));
        props.setProperty(prefix + PROP_LENGTH_VARIANCE, Integer.toString(_lengthVariance));
        if (_destinationNickname != null)
            props.setProperty(prefix + PROP_NICKNAME, _destinationNickname);
        props.setProperty(prefix + PROP_QUANTITY, Integer.toString(_quantity));
        // props.setProperty(prefix + PROP_REBUILD_PERIOD, ""+_rebuildPeriod);
        props.setProperty(prefix + PROP_IP_RESTRICTION, Integer.toString(_IPRestriction));
        if (!_isInbound)
            props.setProperty(prefix + PROP_PRIORITY, Integer.toString(_priority));
        for (Map.Entry<Object, Object> e : _unknownOptions.entrySet()) {
            String name = (String) e.getKey();
            String val = (String) e.getValue();
            props.setProperty(prefix + name, val);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        Properties p = new Properties();
        writeToProperties("", p);
        for (Map.Entry<Object, Object> e : p.entrySet()) {
            String name = (String) e.getKey();
            String val = (String) e.getValue();
            buf.append("\n* ").append(name).append(" = ").append(val);
        }
        buf.append("\n* Inbound? ").append(_isInbound ? "Inbound" : "Outbound")
           .append(_isExploratory ? " / Exploratory" : "");
        return buf.toString();
    }

    // used for strict peer ordering
    private static SessionKey generateRandomKey() {
        byte data[] = new byte[SessionKey.KEYSIZE_BYTES];
        RandomSource.getInstance().nextBytes(data);
        return new SessionKey(data);
    }

    private static final boolean getBoolean(String str, boolean defaultValue) {
        if (str == null) return defaultValue;
        boolean v = Boolean.parseBoolean(str) ||
                    "YES".equals(str.toUpperCase(Locale.US));
        return v;
    }

    private static final int getInt(String str, int defaultValue) { return (int)getLong(str, defaultValue); }

    private static final long getLong(String str, long defaultValue) {
        if (str == null) return defaultValue;
        try {
            long val = Long.parseLong(str);
            return val;
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }
}
