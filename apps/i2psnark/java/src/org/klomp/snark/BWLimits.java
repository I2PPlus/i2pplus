/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */
package org.klomp.snark;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSimpleClient;

import java.util.Properties;

/**
 * Retrieves bandwidth limits from an I2P router via I2CP. Caches results and updates at most once
 * every 10 minutes.
 *
 * <p>This method is blocking and returns cached results if called again within the update interval.
 * Returns null on failure.
 *
 * <p>Uses a 5-second timeout internally but typically completes faster. Thread-safe via method
 * synchronization.
 *
 * @author zzz
 */
public class BWLimits {
    private static int[] cachedResult = null;
    private static long lastUpdateTime = 0L; // store time in milliseconds
    private static final long UPDATE_INTERVAL = 10 * 60 * 1000;

    public static synchronized int[] getBWLimits(String host, int port) {
        long now = System.currentTimeMillis();
        if (cachedResult != null && (now - lastUpdateTime) < UPDATE_INTERVAL) {
            // Return cached result if last update was less than 10 minutes ago
            return cachedResult;
        }
        try {
            I2PClient client = new I2PSimpleClient();
            Properties opts = new Properties();
            opts.put(I2PClient.PROP_TCP_HOST, host);
            opts.put(I2PClient.PROP_TCP_PORT, "" + port);
            I2PSession session = client.createSession(null, opts);
            session.connect();
            cachedResult = session.bandwidthLimits();
            session.destroySession();
            lastUpdateTime = System.currentTimeMillis();
        } catch (I2PSessionException ise) {
            I2PAppContext.getGlobalContext()
                    .logManager()
                    .getLog(BWLimits.class)
                    .warn("[I2PSnark] Bandwidth Limiter failed", ise);
        }
        return cachedResult;
    }
}
