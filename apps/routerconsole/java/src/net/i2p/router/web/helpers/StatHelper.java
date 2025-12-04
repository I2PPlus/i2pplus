package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Set;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.web.HelperBase;
import net.i2p.servlet.util.WriterOutputStream;

/**
 * Helper for statistics page rendering and form processing.
 * @since 0.9.33
 */
public class StatHelper extends HelperBase {
    private String _peer;
    private boolean _full;

    /**
     * Caller should strip HTML (XSS)
     */
    public void setPeer(String peer) {
        _peer = peer;
    }

    /**
     * Set full flag for stats generation
     */
    public void setFull(String f) {
        _full = f != null && f.length() > 0;
    }

    /**
     * Retrieve peer profile or stats based on usage
     */
    public String getProfile() {
        if (_peer == null || _peer.length() <= 0) {
            return "<p class=infohelp id=nopeer>No peer specified</p>";
        }
        if (_peer.length() >= 44) {
            return outputProfile();
        }
        Set<Hash> peers = _context.profileOrganizer().selectAllPeers();
        for (Hash peer : peers) {
            if (peer.toBase64().startsWith(_peer)) {
                return dumpProfile(peer);
            }
        }
        return "Peer profile unavailable";
    }

    /**
     * Get stats page as string or write to output stream
     */
    public String getStats() {
        StatsGenerator gen = new StatsGenerator(_context);
        try {
            if (_out != null) {
                gen.generateStatsPage(_out, _full);
                return "";
            } else {
                StringWriter sw = new StringWriter(32 * 1024);
                gen.generateStatsPage(sw, _full);
                return sw.toString();
            }
        } catch (IOException ioe) {
            return "<b>Error displaying the console.</b>";
        }
    }

    /**
     * Look up based on the full b64 - efficient
     * @since 0.8.5
     */
    private String outputProfile() {
        Hash peer = new Hash();
        try {
            peer.fromBase64(_peer);
            return dumpProfile(peer);
        } catch (DataFormatException dfe) {
            return "Bad peer hash " + _peer;
        }
    }

    /**
     * Dump the profile
     * @since 0.8.5
     */
    private String dumpProfile(Hash peer) {
        try {
            WriterOutputStream wos = new WriterOutputStream(_out);
            boolean success = _context.profileOrganizer().exportProfile(peer, wos);
            if (success) {
                wos.flush();
                _out.flush();
                return "";
            } else {
                return "Peer profile unavailable for: " + _peer;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "IO Error " + e;
        }
    }
}
