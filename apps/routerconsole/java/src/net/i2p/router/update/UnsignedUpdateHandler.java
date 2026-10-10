package net.i2p.router.update;

import static net.i2p.update.UpdateMethod.*;
import static net.i2p.update.UpdateType.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.NewsHelper;
import net.i2p.update.*;

/**
 * <p>Handles the request to update the router by firing off an
 * {@link net.i2p.util.EepGet} call to download the latest unsigned zip file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is copied to the
 * router directory, and if configured the router is restarted to complete
 * the update process.
 * </p>
 */
class UnsignedUpdateHandler implements Checker, Updater {
    private final RouterContext _context;
    private final ConsoleUpdateManager _mgr;

    /**
     * Hold the router context and the update manager that the checker and
     * runner created here need; no work happens in the constructor.
     *
     * @param ctx router context used for config and clock access
     * @param mgr console update manager that runs the download and shows status
     */
    public UnsignedUpdateHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _mgr = mgr;
    }

    /**
     * The single download location for the unsigned update, taken from the
     * {@code ConfigUpdateHandler} zip URL property. Unlike the signed
     * sources this list never has more than one entry.
     *
     * @return null if none
     * @since 0.9.4
     */
    public List<URI> getUpdateSources() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL);
        if (url == null)
            return null;

        try {
            return Collections.singletonList(new URI(url));
        } catch (URISyntaxException use) {
            return null;
        }
    }

    /**
     * Offer a checker for the unsigned router zip, or nothing at all if this
     * type or method is not the one being asked about, no source is
     * configured, or no previous check time has been stamped yet.
     *
     * @param currentVersion ignored, we use time stored in a property
     */
    @Override
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if (type != UpdateType.ROUTER_UNSIGNED || method != UpdateMethod.HTTP) {return null;}


        List<URI> updateSources = getUpdateSources();
        if (updateSources == null) {return null;}

        long ms = _context.getProperty(NewsHelper.PROP_LAST_UPDATE_TIME, 0L);
        long now = _context.clock().now();
        _context.router().saveConfig(NewsHelper.PROP_LAST_UPDATE_TIME, Long.toString(now));

        if (ms <= 0) {
            // We don't know what version you have, so stamp it with the current time, and we'll look
            // for something newer next time around.
            return null;
        }

        return new UnsignedUpdateChecker(_context, _mgr, updateSources, ms);
    }

    /**
     * Start a download and return a handle to the download task.
     * Should not block.
     *
     * @param id plugin name or ignored
     * @param maxTime how long you have
     * @return active task or null if unable to download
     */
    @Override
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                             String id, String newVersion, long maxTime) {
        if (type != ROUTER_UNSIGNED || method != HTTP || updateSources.isEmpty() || NewsHelper.isUpdateInProgress()) {
            return null;
        }
        UpdateRunner update = new UnsignedUpdateRunner(_context, _mgr, updateSources);
        // set status before thread to ensure UI feedback
        if (updateSources.toString().contains("skank") && type == ROUTER_UNSIGNED) {
            _mgr.notifyProgress(update, "<span id=contactserver class=volatile><b>" +
            _mgr._t("Updating I2P").replace("Updating I2P", "Contacting I2P+ update server") + "&hellip;</b></span>");
        } else {
            _mgr.notifyProgress(update, "<span id=contactserver class=volatile><b>" +
            _mgr._t("Updating I2P").replace("Updating I2P", "Contacting I2P update server") + "&hellip;</b></span>");
        }
        return update;
    }

}
