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
    /** Logger for the console sessions. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SessionManager.class);
    /** Time an idle session stays valid. */
    private static final int SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
    /** Interval between cleanup runs. */
    private static final int CLEANUP_INTERVAL_MS = 60 * 1000; // 1 minute

    /** The single instance of the filter. */
    private static final SessionManager INSTANCE = new SessionManager();
    /** Random source for the session tokens. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The active sessions, by token. */
    private final Map<String, Session> _sessions = new ConcurrentHashMap<>();
    /** Failed logins, by user name. */
    private final Map<String, FailedLogin> _failedLogins = new ConcurrentHashMap<>();
    /** Scheduler for the cleanup jobs. */
    private final ScheduledExecutorService _cleanup;
    /** Failed logins before a lockout. */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    /** Window the failed logins count in. */
    private static final long FAILURE_WINDOW_MS = 5 * (long) 60 * 1000; // 5 minutes
    /**
     * Name of the cookie carrying the session token to the console. A cookie-name
     * is an RFC 6265 token, and '+' is an HTTP separator, not a token character,
     * so a browser that enforces the cookie grammar drops a cookie called
     * I2P+AUTH: the console then hands out a fresh login session on every
     * request. It is also kept distinct from the servlet session cookie
     * I2PSESSIONID, which every webapp on the host shares (see
     * apps/routerconsole/jsp/web.xml) - one name for both would let a login
     * overwrite the session the CSRF nonces are recorded in.
     */
    public static final String SESSION_COOKIE_NAME = "I2PAUTH";
    /**
     * Session attribute under which the console stores the logged-in username.
     */
    public static final String SESSION_ATTR_USER = "user";

    /** Builds the singleton and starts the cleanup. */
    private SessionManager() {
        _cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessCleanup");
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

    /**
     * Returns the single session manager, creating it on first use.
     * @return the singleton instance holding the live session table
     */
    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Create a new session for a user with default 30 min expiry.
     * @param username the user the session is issued to, returned by {@link #validateSession}
     * @return the 64-character hex session token to hand to the client
     */
    public String createSession(String username) {
        return createSession(username, SESSION_TIMEOUT_MS);
    }

    /**
     * Create a new session for a user with custom expiry.
     * @param username the user the session is issued to, returned by validateSession()
     * @param expiryMs expiry time in milliseconds, or -1 for no expiry (forever)
     * @return the 64-character hex session token to hand to the client
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
     * @param token the token to look up; null is treated as invalid
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
        return session.username;
    }

    /**
     * Restore a persisted session with its original token.
     * Used on router restart to maintain login sessions.
     * @param token the original session token from persistence
     * @param username the user the restored session belongs to, returned by validateSession()
     * @param expiresAt absolute expiry time, or -1 for no expiry
     */
    public void restoreSession(String token, String username, long expiresAt) {
        Session session = new Session(username, expiresAt);
        _sessions.put(token, session);
    }

    /**
     * Drops a session token, so validateSession() stops accepting it.
     *
     * @param token the token to drop from the session table; null is ignored
     */
    public void invalidateSession(String token) {
        if (token != null) {
            _sessions.remove(token);
            _log.debug("Invalidated session");
        }
    }

    /**
     * Generates a random session token.
     *
     * @return the token, base64
     */
    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Removes the sessions past their expiry. */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Session> entry : _sessions.entrySet()) {
            long expiresAt = entry.getValue().expiresAt;
            if (expiresAt > 0 && expiresAt < now) {
                _sessions.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            _log.debug("Cleaned up " + removed + " expired sessions");
        }
    }

    /**
     * Stops the cleanup scheduler, ending periodic session expiry.
     */
    public void shutdown() {
        _cleanup.shutdownNow();
    }

    /**
     * Record a failed login attempt from an IP address.
     *
     * @param ip the client address the attempt came from, used as the counter key
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
     * @param ip the client address to test against the recent-failure counters
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
     *
     * @param ip the client address whose failure counter is discarded
     */
    public void clearFailedLogins(String ip) {
        _failedLogins.remove(ip);
    }

    /** Drops the failed logins outside the window. */
    private void cleanupFailedLogins() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, FailedLogin> entry : _failedLogins.entrySet()) {
            if (now - entry.getValue().firstAttempt > FAILURE_WINDOW_MS) {
                _failedLogins.remove(entry.getKey());
            }
        }
    }

    /** One user's failed login count. */
    private static class FailedLogin {
        /** Failed logins counted. */
        final int attempts;
        /** When the first failure happened. */
        final long firstAttempt;
        /**
         * Records one user's failures.
         *
         * @param attempts the failures counted
         * @param firstAttempt when the first failure happened
         */
        FailedLogin(int attempts, long firstAttempt) {
            this.attempts = attempts;
            this.firstAttempt = firstAttempt;
        }
    }

    /** One logged-in console session. */
    private static class Session {
        /** The user name of the session. */
        final String username;
        /** When the session expires. */
        long expiresAt;

        /**
         * Builds one session record.
         *
         * @param username the user name of the session
         * @param expiresAt when the session expires
         */
        Session(String username, long expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }
}
