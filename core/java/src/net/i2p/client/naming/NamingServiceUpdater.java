package net.i2p.client.naming;

import java.util.Properties;

/**
 * Interface for updating naming services.
 * @since 0.8.7
 */
public interface NamingServiceUpdater {

    /**
     *  Should not block.
     *  @param options Updater-specific, may be null
     */
    public void update(Properties options);
}

