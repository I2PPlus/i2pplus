package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSessionException;
import net.i2p.client.SendMessageOptions;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.i2cp.AbuseReason;
import net.i2p.data.i2cp.AbuseSeverity;
import net.i2p.data.i2cp.CreateLeaseSetMessage;
import net.i2p.data.i2cp.CreateLeaseSet2Message;
import net.i2p.data.i2cp.CreateSessionMessage;
import net.i2p.data.i2cp.DestroySessionMessage;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.ReconfigureSessionMessage;
import net.i2p.data.i2cp.ReportAbuseMessage;
import net.i2p.data.i2cp.SendMessageMessage;
import net.i2p.data.i2cp.SendMessageExpiresMessage;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.data.i2cp.SessionId;
import net.i2p.util.Log;

/**
 * Produces the various I2CP messages that a client session needs to send to the router.
 * <p>
 * This class also enforces outbound bandwidth limits via a token bucket throttler
 * to prevent overwhelming the router or the network. The throttling is applied
 * at the I2CP layer and includes all protocol overhead (streaming, gzip, etc.),
 * but not router-level tunnel encryption or LeaseSet overhead.
 * </p>
 *
 * @author jrandom
 * @since 0.8.4 (throttling added), refactored in later versions
 */
class I2CPMessageProducer {
    private final Log _log;
    private final I2PAppContext _context;

    /** Property name for outbound bandwidth limit (bytes per second) */
    private static final String PROP_MAX_BW = "i2cp.outboundBytesPerSecond";

    /**
     * Typical message size estimate: MTU (1730) + streaming overhead (28) + gzip overhead (23).
     * Used for rounding and minimum rate enforcement.
     */
    private static final int TYP_SIZE = 1730 + 28 + 23;

    /** Minimum allowed rate: 2x typical size to avoid pathological low rates */
    private static final int MIN_RATE = Math.max(2 * TYP_SIZE, 16384);

    /**
     * Maximum burst allowed: up to 2 seconds worth of bandwidth can be accumulated
     * when idle, enabling short bursts without violating long-term rate limits.
     */
    private static final double MAX_BURST_SECONDS = 2.0;

    /**
     * Maximum time (in milliseconds) a message will wait for bandwidth before being dropped.
     * Prevents indefinite blocking, especially for non-expiring messages.
     */
    private static final long MAX_WAIT_MILLIS = 10_000; // 10 seconds

    // Client-side options that must not be sent to the router
    private static final String[] CLIENT_SIDE_OPTIONS = new String[] {
        "i2cp.closeIdleTime",
        "i2cp.closeOnIdle",
        "i2cp.encryptLeaseSet",
        I2PClient.PROP_GZIP,
        "i2cp.leaseSetKey",
        "i2cp.leaseSetPrivateKey",
        "i2cp.leaseSetSigningPrivateKey",
        "i2cp.reduceIdleTime",
        "i2cp.reduceOnIdle",
        I2PClient.PROP_ENABLE_SSL,
        I2PClient.PROP_TCP_HOST,
        I2PClient.PROP_TCP_PORT,
        // Long strings MUST be removed, even in router context,
        // as the session config properties must be serialized to be signed.
        "i2p.reseedURL"
    };

    // Throttling state
    private volatile int _maxBytesPerSecond = 0;
    private double _tokens = 0.0; // accumulated tokens (bytes)
    private long _lastRefillTime = 0;

    // Synchronization
    private final ReentrantLock _lock = new ReentrantLock(true);
    private final Condition _bandwidthAvailable = _lock.newCondition();

    public I2CPMessageProducer(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(I2CPMessageProducer.class);
        _lastRefillTime = context.clock().now();

        // Initialize stats
        context.statManager().createRateStat("client.sendThrottled",
            "Time (ms) waited for bandwidth due to throttling",
            "ClientMessages", new long[] { 60 * 1000 });
        context.statManager().createRateStat("client.sendDropped",
            "Length of message dropped while waiting for bandwidth",
            "ClientMessages", new long[] { 60 * 1000 });
    }

    /**
     * Update the bandwidth limit based on session options.
     * The configured limit is rounded up to the next multiple of {@link #TYP_SIZE}
     * and clamped to at least {@link #MIN_RATE} for usability.
     *
     * @param session the session whose options to read
     * @since 0.8.4
     */
    public void updateBandwidth(I2PSessionImpl session) {
        String max = session.getOptions().getProperty(PROP_MAX_BW);
        if (max != null) {
            try {
                int iMax = Integer.parseInt(max);
                if (iMax > 0) {
                    // Round up to next TYP_SIZE block and add small fudge for small messages
                    _maxBytesPerSecond = 256 + Math.max(MIN_RATE, TYP_SIZE * ((iMax + TYP_SIZE - 1) / TYP_SIZE));
                } else {
                    _maxBytesPerSecond = 0; // disable throttling
                }
            } catch (NumberFormatException ignored) {
                // Keep current value
            }
        }
        if (_log.shouldDebug())
            _log.debug("Setting max outbound bandwidth to " + _maxBytesPerSecond + " Bps");
    }

    /**
     * Strip client-side options from session properties before sending to router.
     * Also removes any key or value longer than 255 characters, as these cannot
     * be serialized in signed session configs.
     *
     * @param session the session
     * @return a new Properties object safe for router transmission
     * @since 0.9.38
     */
    private Properties getRouterOptions(I2PSessionImpl session) {
        Properties props = new Properties();
        props.putAll(session.getOptions());
        for (String option : CLIENT_SIDE_OPTIONS) {
            props.remove(option);
        }
        for (Iterator<Map.Entry<Object, Object>> iter = props.entrySet().iterator(); iter.hasNext(); ) {
            Map.Entry<Object, Object> e = iter.next();
            String key = (String) e.getKey();
            String val = (String) e.getValue();
            boolean keyTooLong = key.length() > 255;
            boolean valTooLong = val.length() > 255;
            boolean bothKeyAndValTooLong = keyTooLong && valTooLong;
            if (keyTooLong || valTooLong) {
                if (_log.shouldWarn()) {
                    String message = "Stripping [" + key + "] in session config -> ";
                    if (keyTooLong) {message += "Key";}
                    if (keyTooLong && valTooLong) {message += " and value";}
                    else if (valTooLong) {message += "Value";}
                    message += " too long, max permitted is 255 bytes\n* Value: " + val;
                    _log.warn(message);
                }
                iter.remove();
            }
        }
        return props;
    }

    /**
     * Establish a new session with the router by sending a {@link CreateSessionMessage}.
     *
     * <p>
     * This method initializes the session negotiation by:
     * <ul>
     *   <li>Updating the outbound bandwidth settings for the session (if configured).</li>
     *   <li>Building a {@link SessionConfig} populated with router-safe options.</li>
     *   <li>Handling offline signatures when the session operates in offline mode.</li>
     *   <li>Signing the session configuration to attest integrity and authenticity.</li>
     *   <li>Sending the signed configuration to the router via the session channel.</li>
     * </ul>
     * </p>
     *
     * @param session the session to connect
     * @throws I2PSessionException if the session configuration cannot be signed or if the offline signature is expired
     */
    public void connect(I2PSessionImpl session) throws I2PSessionException {
        // Apply any bandwidth limits configured for this session
        updateBandwidth(session);

        // Prepare the CreateSessionMessage to carry the SessionConfig
        CreateSessionMessage msg = new CreateSessionMessage();

        // Build the session configuration based on current session state
        SessionConfig cfg = new SessionConfig(session.getMyDestination());

        // Gather router-safe options (client-side options stripped and length-validated)
        Properties p = getRouterOptions(session);
        boolean isOffline = session.isOffline();

        // If offline, ensure LS type is set unless already provided
        if (isOffline && !p.containsKey(RequestLeaseSetMessageHandler.PROP_LS_TYPE))
            p.setProperty(RequestLeaseSetMessageHandler.PROP_LS_TYPE, "3");

        cfg.setOptions(p);

        // If offline, attach the offline signature data for router verification
        if (isOffline) {
            long exp = session.getOfflineExpiration();
            if (exp < _context.clock().now()) {
                String s = "Offline signature for tunnel expired " + DataHelper.formatTime(exp);
                _log.log(Log.CRIT, s);
                throw new I2PSessionException(s);
            }
            cfg.setOfflineSignature(exp,
                                    session.getTransientSigningPublicKey(),
                                    session.getOfflineSignature());
        }

        // Sign the router-facing session configuration
        try {
            cfg.signSessionConfig(session.getPrivateKey());
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Unable to sign the session config", dfe);
        }

        // Attach the signed configuration to the message and send
        msg.setSessionConfig(cfg);
        session.sendMessage_unchecked(msg);
    }


    /**
     * Send a {@link DestroySessionMessage} to the router to tear down the session.
     * Does not close the underlying socket.
     *
     * @param session the session to disconnect
     * @throws I2PSessionException if message cannot be sent
     */
    public void disconnect(I2PSessionImpl session) throws I2PSessionException {
        if (session.isClosed()) return;
        DestroySessionMessage dmsg = new DestroySessionMessage();
        SessionId id = session.getSessionId();
        if (id == null)
            id = I2PSessionImpl.DUMMY_SESSION;
        dmsg.setSessionId(id);
        session.sendMessage_unchecked(dmsg);
    }

    /**
     * Send a message to the router for delivery to a destination.
     * This overload uses default expiration (0) and flags (0).
     *
     * @param session the sending session
     * @param dest destination
     * @param nonce message ID (0 = no status reply)
     * @param payload message content
     * @param tag unused (no end-to-end crypto)
     * @param key unused
     * @param tags unused
     * @param newKey unused
     * @param expires expiration timestamp (0 = none)
     * @throws I2PSessionException if session is closed or payload is null
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload, SessionTag tag,
                            SessionKey key, Set<SessionTag> tags, SessionKey newKey, long expires) throws I2PSessionException {
        sendMessage(session, dest, nonce, payload, expires, 0);
    }

    /**
     * Send a message with explicit expiration and flags.
     *
     * @param session the sending session
     * @param dest destination
     * @param nonce message ID (0 = no status reply)
     * @param payload message content
     * @param expires expiration timestamp (0 = none)
     * @param flags message flags
     * @throws I2PSessionException if session is closed or payload is null
     * @since 0.8.4
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload,
                            long expires, int flags) throws I2PSessionException {
        if (!updateBps(payload.length, expires)) {
            // Message was dropped due to throttling or expiration
            return;
        }
        SessionId sid = session.getSessionId();
        if (sid == null) {
            _log.error(session.toString() + " cannot send message, session closed", new Exception());
            return;
        }
        Payload data = createPayload(payload);
        SendMessageMessage msg;
        if (expires > 0 || flags > 0) {
            SendMessageExpiresMessage smsg = new SendMessageExpiresMessage(sid, dest, data, nonce);
            smsg.setExpiration(expires);
            smsg.setFlags(flags);
            msg = smsg;
        } else {
            msg = new SendMessageMessage(sid, dest, data, nonce);
        }
        session.sendMessage(msg);
    }

    /**
     * Send a message using {@link SendMessageOptions}.
     *
     * @param session the sending session
     * @param dest destination
     * @param nonce message ID (0 = no status reply)
     * @param payload message content
     * @param options message options (expiration, flags, etc.)
     * @throws I2PSessionException if session is closed or payload is null
     * @since 0.9.2
     */
    public void sendMessage(I2PSessionImpl session, Destination dest, long nonce, byte[] payload,
                            SendMessageOptions options) throws I2PSessionException {
        long expires = options.getTime();
        if (!updateBps(payload.length, expires)) {
            return;
        }
        SessionId sid = session.getSessionId();
        if (sid == null) {
            _log.error(session.toString() + " cannot send message, session closed", new Exception());
            return;
        }
        Payload data = createPayload(payload);
        SendMessageMessage msg = new SendMessageExpiresMessage(sid, dest, data, nonce, options);
        session.sendMessage(msg);
    }

    /**
     * Token bucket bandwidth throttler.
     * <p>
     * This method enforces a long-term average outbound rate of {@link #_maxBytesPerSecond},
     * while allowing bursts up to {@link #MAX_BURST_SECONDS} seconds of that rate.
     * </p>
     * <p>
     * If bandwidth is unavailable, the caller will wait (blocking) until:
     * <ul>
     *   <li>Enough tokens accumulate, OR</li>
     *   <li>The message expires (if {@code expires > 0}), OR</li>
     *   <li>Maximum wait time ({@link #MAX_WAIT_MILLIS}) is reached.</li>
     * </ul>
     * In the latter two cases, the message is dropped.
     * </p>
     *
     * @param len the message length in bytes
     * @param expires absolute expiration time (ms since epoch), or 0 for no expiration
     * @return {@code true} if the message should be sent, {@code false} if dropped
     */
    private boolean updateBps(int len, long expires) {
        // No throttling if disabled
        if (_maxBytesPerSecond <= 0)
            return true;

        _lock.lock();
        try {
            long totalWaited = 0;
            while (true) {
                long now = _context.clock().now();

                // Refill tokens based on elapsed time
                double elapsedSeconds = (now - _lastRefillTime) / 1000.0;
                if (elapsedSeconds > 0) {
                    _tokens = Math.min(
                        _maxBytesPerSecond * MAX_BURST_SECONDS,
                        _tokens + elapsedSeconds * _maxBytesPerSecond
                    );
                    _lastRefillTime = now;
                }

                // If we have enough tokens, consume and send
                if (_tokens >= len) {
                    _tokens -= len;
                    if (_log.shouldDebug())
                        _log.debug("Sending " + len + " byte message; tokens remaining: " + (int)_tokens);
                    return true;
                }

                // Check if message is already expired
                if (expires > 0 && expires <= now) {
                    _context.statManager().addRateData("client.sendDropped", len, 0);
                    if (_log.shouldWarn())
                        _log.warn("Dropping " + len + " byte message - expired while waiting for bandwidth");
                    return false;
                }

                // Calculate how long we need to wait for enough tokens
                double needed = len - _tokens;
                double waitSeconds = needed / _maxBytesPerSecond;
                long waitMillis = (long) (waitSeconds * 1000);

                // Cap wait time to avoid indefinite blocking
                if (waitMillis > MAX_WAIT_MILLIS - totalWaited) {
                    waitMillis = Math.max(0, MAX_WAIT_MILLIS - totalWaited);
                }

                // If message has expiration, respect it
                if (expires > 0) {
                    long timeUntilExpiry = expires - now;
                    if (waitMillis > timeUntilExpiry) {
                        waitMillis = timeUntilExpiry;
                    }
                }

                // If no time left to wait, drop
                if (waitMillis <= 0) {
                    _context.statManager().addRateData("client.sendDropped", len, 0);
                    if (_log.shouldWarn())
                        _log.warn("Dropping " + len + " byte message - insufficient bandwidth within constraints");
                    return false;
                }

                // Wait for bandwidth or timeout
                totalWaited += waitMillis;
                _context.statManager().addRateData("client.sendThrottled", waitMillis, 0);
                if (_log.shouldDebug())
                    _log.debug("Throttling " + len + " byte message; waiting " + waitMillis + " ms (total: " + totalWaited + " ms)");

                try {
                    _bandwidthAvailable.await(waitMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt(); // restore interrupt status
                }

                // Loop to re-check tokens and expiration
            }
        } finally {
            _lock.unlock();
        }
    }

    /**
     * Create a {@link Payload} object from raw bytes.
     * End-to-end encryption is no longer used at this layer; the payload
     * is stored directly as encrypted data.
     *
     * @param payload the raw message bytes
     * @return a new Payload instance
     * @throws I2PSessionException if payload is null
     */
    private static Payload createPayload(byte[] payload) throws I2PSessionException {
        if (payload == null)
            throw new I2PSessionException("No payload specified");
        Payload data = new Payload();
        data.setEncryptedData(payload);
        return data;
    }

    /**
     * Report abusive behavior to the router.
     *
     * @param session the reporting session
     * @param msgId ID of the offending message
     * @param severity abuse severity level
     * @throws I2PSessionException if message cannot be sent
     */
    public void reportAbuse(I2PSessionImpl session, int msgId, int severity) throws I2PSessionException {
        ReportAbuseMessage msg = new ReportAbuseMessage();
        MessageId id = new MessageId();
        id.setMessageId(msgId);
        msg.setMessageId(id);
        AbuseReason reason = new AbuseReason();
        reason.setReason("Not specified");
        msg.setReason(reason);
        AbuseSeverity sv = new AbuseSeverity();
        sv.setSeverity(severity);
        msg.setSeverity(sv);
        session.sendMessage(msg);
    }

    /**
     * Respond to a LeaseSet request by sending a {@link CreateLeaseSetMessage} or
     * {@link CreateLeaseSet2Message} back to the router.
     * <p>
     * Note: This method does not create the LeaseSet; it only sends it.
     * </p>
     *
     * @param session the session
     * @param leaseSet the LeaseSet to send
     * @param signingPriv signing private key (ignored for LS2)
     * @param privs list of private keys for decryption
     * @throws I2PSessionException if session is closed or message cannot be sent
     */
    public void createLeaseSet(I2PSessionImpl session, LeaseSet leaseSet, SigningPrivateKey signingPriv,
                               List<PrivateKey> privs) throws I2PSessionException {
        CreateLeaseSetMessage msg;
        int type = leaseSet.getType();
        if (type == DatabaseEntry.KEY_TYPE_LEASESET) {
            msg = new CreateLeaseSetMessage();
            msg.setPrivateKey(privs.get(0));
            msg.setSigningPrivateKey(signingPriv);
        } else {
            CreateLeaseSet2Message msg2 = new CreateLeaseSet2Message();
            for (PrivateKey priv : privs) {
                msg2.addPrivateKey(priv);
            }
            msg = msg2;
        }
        msg.setLeaseSet(leaseSet);
        SessionId sid = session.getSessionId();
        if (sid == null) {
            _log.error(session.toString() + " cannot create LeaseSet, session closed", new Exception());
            return;
        }
        msg.setSessionId(sid);
        session.sendMessage_unchecked(msg);
    }

    /**
     * Update the number of tunnels used by the session.
     *
     * @param session the session to reconfigure
     * @param tunnels number of tunnels (0 = use original configured value)
     * @throws I2PSessionException if session config signing fails
     */
    public void updateTunnels(I2PSessionImpl session, int tunnels) throws I2PSessionException {
        ReconfigureSessionMessage msg = new ReconfigureSessionMessage();
        SessionConfig cfg = new SessionConfig(session.getMyDestination());
        Properties props = getRouterOptions(session);
        if (tunnels > 0) {
            String stunnels = Integer.toString(tunnels);
            props.setProperty("inbound.quantity", stunnels);
            props.setProperty("outbound.quantity", stunnels);
            props.setProperty("inbound.backupQuantity", "0");
            props.setProperty("outbound.backupQuantity", "0");
        }
        cfg.setOptions(props);
        try {
            cfg.signSessionConfig(session.getPrivateKey());
        } catch (DataFormatException dfe) {
            throw new I2PSessionException("Unable to sign the session configuration", dfe);
        }
        msg.setSessionConfig(cfg);
        SessionId sid = session.getSessionId();
        if (sid == null) {
            _log.error(session.toString() + " cannot update config, session closed", new Exception());
            return;
        }
        msg.setSessionId(sid);
        session.sendMessage(msg);
    }
}