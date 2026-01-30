package org.klomp.snark;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SyntheticREDQueue;

/**
 * Bandwidth and bandwidth limits
 *
 * <p>Maintain three bandwidth estimators: Sent, received, and requested.
 *
 * <p>There are three layers of BandwidthListeners:
 *
 * <pre>
 *  BandwidthManager (total)
 *      PeerCoordinator (per-torrent)
 *          Peer/WebPeer (per-connection)
 * </pre>
 *
 * Here at the top, we use SyntheticRedQueues for accurate and current moving averages of up, down,
 * and requested bandwidth.
 *
 * <p>At the lower layers, simple weighted moving averages of three buckets of time
 * PeerCoordinator.CHECK_PERIOD each are used for up and down, and requested is delegated here.
 *
 * <p>The lower layers must report to the next-higher layer.
 *
 * <p>At the Peer layer, we report inbound piece data per-read, not per-piece, to get a smoother
 * inbound estimate.
 *
 * <p>Only the following data are counted by the BandwidthListeners:
 *
 * <ul>
 *   <li>Pieces (both Peer and WebPeer)
 *   <li>ut_metadata
 * </ul>
 *
 * No overhead at any layer is accounted for.
 *
 * @since 0.9.62
 */
public class BandwidthManager implements BandwidthListener {

    private final I2PAppContext _context;
    private final Log _log;
    private SyntheticREDQueue _up, _down, _req;

    BandwidthManager(I2PAppContext ctx, int upLimit, int downLimit) {
        _context = ctx;
        _log = ctx.logManager().getLog(BandwidthManager.class);
        int absoluteUpLimit = 9999 * 1024;
        int absoluteDownLimit = 9999 * 1024;

        int up = Math.min(upLimit, absoluteUpLimit);
        int down = Math.min(downLimit, absoluteDownLimit);

        _up = new SyntheticREDQueue(ctx, up);
        _down = new SyntheticREDQueue(ctx, down);
        // Allow down limit a little higher based on testing
        // Allow req limit a little higher still because it uses RED
        // so it actually kicks in sooner.
        _req = new SyntheticREDQueue(ctx, down * 110 / 100);
    }

    /** Current limit in Bps */
    void setUpBWLimit(long upLimit) {
        int limit = (int) Math.min(upLimit, Integer.MAX_VALUE);
        if (limit != getUpBWLimit()) _up = new SyntheticREDQueue(_context, limit);
    }

    /** Current limit in Bps */
    void setDownBWLimit(long downLimit) {
        int limit = (int) Math.min(downLimit, Integer.MAX_VALUE);
        if (limit != getDownBWLimit()) {
            _down = new SyntheticREDQueue(_context, limit);
            _req = new SyntheticREDQueue(_context, limit * 110 / 100);
        }
    }

    /** The average rate in Bps */
    long getRequestRate() {
        return (long) (1000f * _req.getBandwidthEstimate());
    }

    // begin BandwidthListener interface

    /** The average rate in Bps */
    public long getUploadRate() {
        return (long) (1000f * _up.getBandwidthEstimate());
    }

    /** The average rate in Bps */
    public long getDownloadRate() {
        return (long) (1000f * _down.getBandwidthEstimate());
    }

    /** We unconditionally sent this many bytes */
    public void uploaded(int size) {
        _up.addSample(size);
    }

    /** We received this many bytes */
    public void downloaded(int size) {
        _down.addSample(size);
    }

    /** Should we send this many bytes? Do NOT call uploaded() if this returns true. */
    public boolean shouldSend(int size) {
        boolean rv = _up.offer(size, 1.0f);
        if (!rv && _log.shouldWarn())
            _log.warn(
                    "Declining send request for "
                            + size
                            + " bytes -> Upload rate: "
                            + DataHelper.formatSize(getUploadRate())
                            + "B/s");
        return rv;
    }

    /**
     * Should we request this many bytes?
     *
     * @param peer ignored
     */
    public boolean shouldRequest(Peer peer, int size) {
        boolean rv = !overDownBWLimit() && _req.offer(size, 1.0f);
        if (!rv && _log.shouldWarn())
            _log.warn(
                    "Not requesting "
                            + size
                            + " bytes (bandwidth limit exceeded) -> Download / Request rate: "
                            + DataHelper.formatSize(getDownloadRate())
                            + "B/s"
                            + " / "
                            + DataHelper.formatSize(getRequestRate())
                            + "B/s");
        return rv;
    }

    /** Current limit in BPS */
    public long getUpBWLimit() {
        return _up.getMaxBandwidth();
    }

    /** Current limit in BPS */
    public long getDownBWLimit() {
        return _down.getMaxBandwidth();
    }

    /** Are we currently over the limit? */
    public boolean overUpBWLimit() {
        return getUploadRate() > getUpBWLimit();
    }

    /** Are we currently over the limit? */
    public boolean overDownBWLimit() {
        return getDownloadRate() > getDownBWLimit();
    }

    /** In HTML for debug page */
    @Override
    public String toString() {
        String separator = " <span class=bullet>&nbsp;&bullet;&nbsp;</span> ";
        return "<div class=debugStats id=bwManager>"
                + "<span id=bw_in class=stat><b>Bandwidth In:</b> <span class=dbug>"
                + _down.toString().trim().replace("* ", "").replace("Bytes", "B")
                + "</span></span>"
                + separator
                + "<span id=bw_out class=stat><b>Bandwidth Out:</b> <span class=dbug>"
                + _up.toString().trim().replace("* ", "").replace("Bytes", "B")
                + "</span></span>"
                + "</div>";
    }
}
