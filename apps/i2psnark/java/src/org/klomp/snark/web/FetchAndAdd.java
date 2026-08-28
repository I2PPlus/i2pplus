/*
 * Released into the public domain
 * with no warranty of any kind, either expressed or implied.
 */

package org.klomp.snark.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocketEepGet;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.crypto.SHA1;
import net.i2p.data.DataHelper;
import net.i2p.util.EepGet;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SecureFile;
import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MetaInfo;
import org.klomp.snark.Snark;
import org.klomp.snark.SnarkManager;
import org.klomp.snark.Storage;

/**
 * A cancellable torrent file downloader. We extend Snark so its status may be easily listed in the
 * web table without adding a lot of code there.
 *
 * <p>Upon successful download, this Snark will be deleted and a "real" Snark created.
 *
 * <p>The methods return values similar to a Snark in magnet mode. A fake info hash, which is the
 * SHA1 of the URL, is returned to prevent duplicates.
 *
 * <p>This Snark may be stopped and restarted, although a partially downloaded file is discarded.
 *
 * @since 0.9.1 Moved from I2PSnarkUtil
 */
public class FetchAndAdd extends Snark implements EepGet.StatusListener, Runnable {

    private static final int RETRIES = 20;
    private final I2PAppContext _ctx;
    private final Log _log;
    private final SnarkManager _mgr;
    private final String _url;
    private final byte[] _fakeHash;
    private final String _name;
    private final File _dataDir;
    private volatile long _remaining = -1;
    private volatile long _total = -1;
    private volatile long _transferred;
    private volatile boolean _isRunning;
    private volatile boolean _active;
    private volatile long _started;
    private String _failCause;
    // infrequently-touched runtime state is kept together below
    private Thread _thread;
    private EepGet _eepGet;

    /**
     * Create the downloader; the caller should call _mgr.addDownloader(this), which starts things off.
     *
     * <p>The fake info hash is the SHA1 of the URL, so downloading the same URL twice is
     * detected as a duplicate.
     *
     * @param dataDir null to default to snark data directory
     * @param ctx the ctx
     * @param mgr the manager that owns this downloader
     * @param url the torrent file URL to fetch
     */
    public FetchAndAdd(
            I2PAppContext ctx, SnarkManager mgr, String url, File dataDir) {
        // magnet constructor
        super(mgr.util(), "Torrent download", null, null, null, null, null, null);
        _ctx = ctx;
        _log = ctx.logManager().getLog(FetchAndAdd.class);
        _mgr = mgr;
        _url = url;
        _name = _t("Downloading {0}", url);
        _dataDir = dataDir;
        byte[] fake = SHA1.getInstance().digest(url.getBytes(StandardCharsets.ISO_8859_1));
        _fakeHash = fake;
    }

    /**
     * Begin fetching; called by startTorrent().
     */
    @Override
    public void run() {
        _mgr.addMessageNoEscape(_t("Requesting torrent file: {0}", urlify(_url)));
        File file = get();
        if (!_isRunning) return; // stopped while fetching
        _isRunning = false;
        try {
            if (file != null && file.exists() && file.length() > 0) {
                // remove this in snarks
                _mgr.deleteMagnet(this);
                add(file);
            } else {
                _mgr.addMessageNoEscape(failMessage());
            }
        } finally {
            // discard the temp file in all cases
            if (file != null) file.delete();
        }
    }

    /**
     * Build the "failed to retrieve" user message, appending the sanitized failure cause if any.
     *
     * @return the localized failure message
     */
    private String failMessage() {
        String msg = _t("Failed to retrieve torrent file: {0}", urlify(_url));
        if (_failCause != null)
            return msg + ": " + DataHelper.stripHTML(_failCause);
        return msg;
    }

    /**
     * Fetch the torrent file from _url into a temp file, connecting the I2P tunnel on demand.
     * The returned temp file is registered for deletion on JVM exit.
     *
     * <p>This is modeled on the equivalent logic in I2PSnarkUtil, but registers this class as a
     * status listener so the web page shows live progress.
     *
     * @return the populated temp file, or null on failure (the temp file is already deleted)
     */
    private File get() {
        if (_log.shouldDebug()) _log.debug("Requesting [" + _url + "]");
        File out = null;
        try {
            out = SecureFile.createTempFile("torrentFile", null, _mgr.util().getTempDir());
        } catch (IOException ioe) {
            _log.error("Temporary storage error", ioe);
            _mgr.addMessage("Problem writing file to temp directory: " + ioe.getMessage());
            return null;
        }
        out.deleteOnExit();

        if (!_mgr.util().connected()) {
            _mgr.addMessage(_t("Opening the I2P tunnel") + "...");
            if (!_mgr.util().connect()) return null;
        }
        I2PSocketManager manager = _mgr.util().getSocketManager();
        if (manager == null) return null;
        _eepGet = new I2PSocketEepGet(_ctx, manager, RETRIES, out.getAbsolutePath(), _url);
        _eepGet.addStatusListener(this);
        _eepGet.addHeader("User-Agent", I2PSnarkUtil.EEPGET_USER_AGENT);
        if (_eepGet.fetch()) {
            if (_log.shouldInfo())
                _log.info("Transfer successful: " + _url + " (Size:" + out.length() + " bytes)");
            return out;
        } else {
            if (_log.shouldInfo()) _log.info("Transfer failed: " + _url);
            out.delete();
            return null;
        }
    }

    /**
     * Tell SnarkManager to copy the torrent file over and add it to the Snarks list. This Snark may
     * then be deleted.
     */
    private void add(File file) {
        _mgr.addMessageNoEscape(_t("Torrent downloaded from {0}", urlify(_url)));
        try {
            byte[] fileInfoHash = new byte[20];
            String name;
            try (FileInputStream in = new FileInputStream(file)) {
                name = MetaInfo.getNameAndInfoHash(in, fileInfoHash);
            }
            Snark snark = _mgr.getTorrentByInfoHash(fileInfoHash);
            if (snark != null) {
                _mgr.addMessage(
                        _t(
                                "Torrent with this info hash is already running: {0}",
                                snark.getBaseName()));
                return;
            }

            String originalName = Storage.filterName(name);
            name = originalName + ".torrent";
            File torrentFile = new File(_mgr.getDataDir(), name);

            String canonical = torrentFile.getCanonicalPath();

            if (torrentFile.exists()) {
                if (_mgr.getTorrent(canonical) != null)
                    _mgr.addMessage(_t("Torrent already running: {0}", name));
                else
                    _mgr.addMessage(_t("Torrent already in the queue: {0}", name));
            } else {
                // This may take a LONG time to create the storage.
                boolean ok = _mgr.copyAndAddTorrent(file, canonical, _dataDir);
                if (!ok) throw new IOException("Unknown error - check logs");
                snark = _mgr.getTorrentByBaseName(originalName);
                if (snark != null)
                    snark.startTorrent();
                else
                    throw new IOException("Unknown error - check logs");
            }
        } catch (IOException ioe) {
            _mgr.addMessageNoEscape(appendCause(_t("Torrent at {0} was not valid", urlify(_url)), ioe));
        } catch (OutOfMemoryError oom) {
            _mgr.addMessageNoEscape(
                    appendCause(_t("ERROR - Out of memory, cannot create torrent from {0}", urlify(_url)), oom));
        }
    }

    /**
     * Append the exception message, stripped of HTML, to a localized notice.
     *
     * @param prefix the base message
     * @param t the throwable whose message to append
     * @return the combined message
     */
    static String appendCause(String prefix, Throwable t) {
        return prefix + ": " + DataHelper.stripHTML(t.getMessage());
    }

    // Snark overrides so all the buttons and stats on the web page work

    /**
     * Begin (or restart) the fetch on a fresh background thread.
     *
     * <p>Because a partially downloaded file is discarded on stop, restart simply resets all
     * progress state and fetches again from scratch.
     */
    @Override
    public synchronized void startTorrent() {
        if (_isRunning) return;
        _remaining = -1;
        _transferred = 0;
        _failCause = null;
        _started = _util.getContext().clock().now();
        _isRunning = true;
        _active = false;
        _thread = new I2PAppThread(this, "TorrEepGet", true);
        _thread.start();
    }

    @Override
    public synchronized void stopTorrent() {
        if (_thread != null && _isRunning) {
            if (_eepGet != null) _eepGet.stopFetching();
            _thread.interrupt();
        }
        _isRunning = false;
        _active = false;
    }

    @Override
    public boolean isStopped() {
        return !_isRunning;
    }

    @Override
    public String getName() {
        return _name;
    }

    // Both getName() and getBaseName() intentionally alias _name so the web table
    // renders this pending downloader with a stable display label.
    @Override
    public String getBaseName() {
        return _name;
    }

    /**
     * The fake info hash (SHA1 of the URL), which makes re-downloading the same URL a duplicate.
     *
     * @return the fake info hash
     */
    @Override
    public byte[] getInfoHash() {
        return _fakeHash;
    }

    /**
     * The torrent file size, or -1 before the size is known.
     *
     * @return torrent file size or -1
     */
    @Override
    public long getTotalLength() {
        return _total;
    }

    /**
     * The torrent file size, or -1. No padding information is available while fetching, so this
     * equals {@link #getTotalLength()}.
     *
     * @return torrent file size or -1
     */
    @Override
    public long getDataLength() {
        return _total;
    }

    /**
     * The remaining bytes, or -1 when done so the web lists us as "complete" instead of "seeding".
     *
     * @return -1 when done so the web lists us as "complete" instead of "seeding"
     */
    @Override
    public long getRemainingLength() {
        long rv = _remaining;
        return rv > 0 ? rv : -1;
    }

    /**
     * The torrent file bytes remaining, or -1 before the size is known.
     *
     * @return torrent file bytes remaining or -1
     */
    @Override
    public long getNeededLength() {
        return _remaining;
    }

    @Override
    public long getDownloadRate() {
        if (_isRunning && _active) {
            long time = _ctx.clock().now() - _started;
            if (time > 1000) {
                long rv = (_transferred * 1000) / time;
                if (rv >= 100) return rv;
            }
        }
        return 0;
    }

    @Override
    public long getDownloaded() {
        return downloaded(_total, _remaining);
    }

    /**
     * Bytes downloaded so far, given the reported total and remaining.
     *
     * <p>Before the first byte is received the total is -1 (size unknown); in that case 0 is
     * reported rather than a bogus negative/positive value derived from the -1 sentinel.
     *
     * @param total the total size, possibly -1 while the size is unknown
     * @param remaining the bytes remaining, possibly -1 while unknown
     * @return the bytes downloaded, 0 while the total is unknown
     */
    static long downloaded(long total, long remaining) {
        // before the first byte, total is -1, so guard against reporting a bogus value
        if (total < 0) return 0;
        return total - remaining;
    }

    @Override
    public int getPeerCount() {
        return (_isRunning && _active && _transferred > 0) ? 1 : 0;
    }

    @Override
    public int getTrackerSeenPeers() {
        return (_transferred > 0) ? 1 : 0;
    }

    // End Snark overrides

    // EepGet status listeners to maintain the state for the web page.
    // Each callback collapses down to updateState(), which derives the total size, the bytes
    // remaining, the bytes transferred, and the active flag used by the Snark overrides above.

    /**
     * Called when a download attempt fails.
     *
     * @param url the URL being downloaded
     * @param bytesTransferred bytes transferred so far
     * @param bytesRemaining bytes remaining to transfer
     * @param currentAttempt the current attempt number
     * @param numRetries the maximum number of retries
     * @param cause the exception that caused the failure, or null
     */
    public void attemptFailed(
            String url,
            long bytesTransferred,
            long bytesRemaining,
            int currentAttempt,
            int numRetries,
            Exception cause) {
        if (cause != null) _failCause = cause.toString();
        updateState(bytesTransferred, bytesRemaining, -1, false);
    }

    /**
     * Called when bytes are transferred during download.
     *
     * @param alreadyTransferred total bytes transferred before this write
     * @param currentWrite number of bytes in this write
     * @param bytesTransferred total bytes transferred so far
     * @param bytesRemaining bytes remaining to transfer
     * @param url the URL being downloaded
     */
    public void bytesTransferred(
            long alreadyTransferred,
            int currentWrite,
            long bytesTransferred,
            long bytesRemaining,
            String url) {
        // total = what's already on disk + what was just written + what still remains
        long total = bytesRemaining >= 0 ? bytesRemaining + currentWrite + alreadyTransferred : -1;
        updateState(bytesTransferred, bytesRemaining, total, true);
    }

    /**
     * Called when a transfer completes successfully.
     *
     * @param alreadyTransferred total bytes transferred before this call
     * @param bytesTransferred bytes transferred in this call
     * @param bytesRemaining bytes remaining (should be 0)
     * @param url the URL being downloaded
     * @param outputFile the output file path
     * @param notModified true if the server returned 304
     */
    public void transferComplete(
            long alreadyTransferred,
            long bytesTransferred,
            long bytesRemaining,
            String url,
            String outputFile,
            boolean notModified) {
        // total = what was already on disk + what still remains once the write completes
        long total = bytesRemaining >= 0 ? bytesRemaining + alreadyTransferred : -1;
        updateState(bytesTransferred, bytesRemaining, total, false);
    }

    /**
     * Called when a transfer fails.
     *
     * @param url the URL being downloaded
     * @param bytesTransferred bytes transferred so far
     * @param bytesRemaining bytes remaining to transfer
     * @param currentAttempt the current attempt number
     */
    public void transferFailed(
            String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        updateState(bytesTransferred, bytesRemaining, -1, false);
    }

    /**
     * Called when an HTTP header is received. Not needed here.
     */
    @Override
    public void headerReceived(String url, int attemptNum, String key, String val) { /* no-op */ }

    /**
     * Called when a download attempt is starting. Not needed here.
     */
    @Override
    public void attempting(String url) { /* no-op */ }

    /**
     * Update the shared progress state reported by the Snark overrides.
     *
     * <p>The remaining and total sizes are only updated once a known non-negative size is
     * reported; a {@code total} of -1 leaves the last known total in place (used by the failure
     * callbacks, which report remaining bytes but no total).
     *
     * @param transferred bytes transferred so far
     * @param remaining bytes remaining to transfer
     * @param total the total size, or -1 to leave the current total unchanged
     * @param active whether a transfer is currently in progress
     */
    private void updateState(long transferred, long remaining, long total, boolean active) {
        if (remaining >= 0) {
            _remaining = remaining;
        }
        if (total >= 0) {
            _total = total;
        }
        _transferred = transferred;
        _active = active;
    }

    // End of EepGet status listeners

    private String _t(String s) {
        return _mgr.util().getString(s);
    }

    private String _t(String s, String o) {
        return _mgr.util().getString(s, o);
    }

    private static String urlify(String s) {
        return I2PSnarkServlet.urlify(s);
    }
}
