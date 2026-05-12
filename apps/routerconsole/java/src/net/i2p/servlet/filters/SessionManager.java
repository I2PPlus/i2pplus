package net.i2p.servlet.filters;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Simple session manager for console authentication.
 * Stores sessions in memory with expiration.
 */
public class SessionManager {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SessionManager.class);
    private static final int SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
    private static final int CLEANUP_INTERVAL_MS = 60 * 1000; // 1 minute

    private static final SessionManager INSTANCE = new SessionManager();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> _sessions = new ConcurrentHashMap<>();
    private final Map<String, FailedLogin> _failedLogins = new ConcurrentHashMap<>();
    private final ScheduledExecutorService _cleanup;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long FAILURE_WINDOW_MS = 5 * 60 * 1000; // 5 minutes
    private static final long FAILURE_LOCKOUT_MS = 15 * 60 * 1000; // 15 minutes

    public static final String SESSION_COOKIE_NAME = "I2P+AUTH";
    public static final String SESSION_ATTR_USER = "user";

    private SessionManager() {
        _cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionCleanup");
            t.setDaemon(true);
            return t;
        });
        _cleanup.scheduleAtFixedRate(this::cleanupExpiredSessions,
                                     CLEANUP_INTERVAL_MS,
                                     CLEANUP_INTERVAL_MS,
                                     TimeUnit.MILLISECONDS);
        _cleanup.scheduleAtFixedRate(this::cleanupFailedLogins,
                                     CLEANUP_INTERVAL_MS,
                                     CLEANUP_INTERVAL_MS,
                                     TimeUnit.MILLISECONDS);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Create a new session for a user with default 30 min expiry.
     * @return session token
     */
    public String createSession(String username) {
        return createSession(username, SESSION_TIMEOUT_MS);
    }

    /**
     * Create a new session for a user with custom expiry.
     * @param username the username
     * @param expiryMs expiry time in milliseconds, or -1 for no expiry (forever)
     * @return session token
     */
    public String createSession(String username, long expiryMs) {
        String token = generateToken();
        long expiresAt = expiryMs > 0 ? System.currentTimeMillis() + expiryMs : -1;
        Session session = new Session(username, expiresAt);
        _sessions.put(token, session);
        return token;
    }

    /**
     * Validate a session token.
     * @return username if valid, null if invalid/expired
     */
    public String validateSession(String token) {
        if (token == null) return null;
        Session session = _sessions.get(token);
        if (session == null) return null;
        if (session.expiresAt > 0 && System.currentTimeMillis() > session.expiresAt) {
            _sessions.remove(token);
            return null;
        }
        if (session.expiresAt > 0) {
            session.expiresAt = System.currentTimeMillis() + SESSION_TIMEOUT_MS;
        }
        return session.username;
    }

    /**
     * Invalidate a session.
     */
    public void invalidateSession(String token) {
        if (token != null) {
            _sessions.remove(token);
            _log.debug("Invalidated session");
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Session> entry : _sessions.entrySet()) {
            if (entry.getValue().expiresAt < now) {
                _sessions.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            _log.debug("Cleaned up " + removed + " expired sessions");
        }
    }

    public void shutdown() {
        _cleanup.shutdownNow();
    }

    /**
     * Record a failed login attempt from an IP address.
     */
    public void recordFailedLogin(String ip) {
        long now = System.currentTimeMillis();
        FailedLogin failed = _failedLogins.get(ip);
        if (failed == null) {
            failed = new FailedLogin(1, now);
        } else if (now - failed.firstAttempt > FAILURE_WINDOW_MS) {
            failed = new FailedLogin(1, now);
        } else {
            failed = new FailedLogin(failed.attempts + 1, failed.firstAttempt);
        }
        _failedLogins.put(ip, failed);
    }

    /**
     * Check if IP is blocked due to too many failed attempts.
     * @return true if blocked
     */
    public boolean isBlocked(String ip) {
        FailedLogin failed = _failedLogins.get(ip);
        if (failed == null) return false;
        long now = System.currentTimeMillis();
        if (now - failed.firstAttempt > FAILURE_WINDOW_MS) {
            _failedLogins.remove(ip);
            return false;
        }
        return failed.attempts >= MAX_FAILED_ATTEMPTS;
    }

    /**
     * Clear failed login attempts for an IP after successful login.
     */
    public void clearFailedLogins(String ip) {
        _failedLogins.remove(ip);
    }

    private void cleanupFailedLogins() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, FailedLogin> entry : _failedLogins.entrySet()) {
            if (now - entry.getValue().firstAttempt > FAILURE_WINDOW_MS) {
                _failedLogins.remove(entry.getKey());
            }
        }
    }

    private static class FailedLogin {
        final int attempts;
        final long firstAttempt;
        FailedLogin(int attempts, long firstAttempt) {
            this.attempts = attempts;
            this.firstAttempt = firstAttempt;
        }
    }

    private static class Session {
        final String username;
        long expiresAt;

        Session(String username, long expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }
}
