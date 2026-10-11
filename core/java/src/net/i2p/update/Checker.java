package net.i2p.update;

/**
 * Controls one or more types of updates.
 * This must be registered with the UpdateManager.
 *
 * @since 0.9.4
 */
public interface Checker {

    /**
     * Check for updates.
     * Should not block.
     * If any are found, call back to notifyVersionAvailable() in
     * {@link UpdateManager} - the overload taking a sourceMap when more
     * than one update method was found at once.
     *
     * @param type the kind of update to look for, e.g. {@link UpdateType#ROUTER_SIGNED}
     * @param method the transport to fetch it over, e.g. {@link UpdateMethod#HTTP}
     * @param id plugin name or ignored
     * @param currentVersion the running version, compared against the remote
     *                       one so an equal or older release is not offered
     * @param maxTime how long you have
     * @return active task or null if unable to check
     */
    public UpdateTask check(UpdateType type, UpdateMethod method, String id, String currentVersion, long maxTime);
}
