package net.i2p.update;

import java.net.URI;
import java.util.List;

/**
 * Controls one or more types of updates.
 * This must be registered with the UpdateManager.
 *
 * @since 0.9.4
 */
public interface Updater {

    /**
     * Start a download and return a handle to the download task.
     * Should not block.
     *
     * @param type selects what is being fetched, e.g. {@link UpdateType#ROUTER_SIGNED}
     * @param method how it is fetched, e.g. {@link UpdateMethod#HTTP} over I2P
     * @param updateSources Where to get the new version from
     * @param id plugin name or ignored
     * @param newVersion new version to download
     * @param maxTime how long you have
     * @return active task or null if unable to download
     */
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources, String id, String newVersion, long maxTime);
}
