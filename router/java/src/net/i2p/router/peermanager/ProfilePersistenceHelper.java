package net.i2p.router.peermanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Write profiles to disk at shutdown, read at startup.
 *  The files are gzip compressed, we previously stored them
 *  with a ".dat" extension instead of ".txt.gz", so it wasn't apparent.
 *  Now migrated to a ".txt.gz" extension.
 */
class ProfilePersistenceHelper {
    private final Log _log;
    private final RouterContext _context;

    public final static String PROP_PEER_PROFILE_DIR = "router.profileDir";
    public final static String DEFAULT_PEER_PROFILE_DIR = "peerProfiles";
    private final static String NL = System.getProperty("line.separator");
    private final static String TAB = "\t";
    private final static String HR = "# ----------------------------------------------------------------------------------------";
    private static final String PREFIX = "profile-";
    private static final String SUFFIX = ".txt.gz";
    private static final String UNCOMPRESSED_SUFFIX = ".txt";
    private static final String OLD_SUFFIX = ".dat";
    private static final int MIN_NAME_LENGTH = PREFIX.length() + 44 + OLD_SUFFIX.length();
    private static final String DIR_PREFIX = "p";
    private static final String B64 = Base64.ALPHABET_I2P;
    // Max to read in at startup
    private static final int LIMIT_PROFILES = SystemVersion.isSlow() ? 2000 : 6000;

    private final File _profileDir;
    private Hash _us;

    public ProfilePersistenceHelper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(ProfilePersistenceHelper.class);
        String dir = _context.getProperty(PROP_PEER_PROFILE_DIR, DEFAULT_PEER_PROFILE_DIR);
        _profileDir = new SecureDirectory(_context.getRouterDir(), dir);
        if (!_profileDir.exists()) {_profileDir.mkdirs();}
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new SecureDirectory(_profileDir, DIR_PREFIX + B64.charAt(j));
            if (!subdir.exists()) {subdir.mkdir();}
        }
    }

    public void setUs(Hash routerIdentHash) {_us = routerIdentHash;}

    /**
     * write out the data from the profile to the file
     * @return success
     */
    public boolean writeProfile(PeerProfile profile) {
        File f = pickFile(profile);
        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new GZIPOutputStream(new SecureFileOutputStream(f)));
            writeProfile(profile, fos, false);
        } catch (IOException ioe) {
            _log.error("Error writing profile to " + f);
            return false;
        } finally {
            if (fos != null) {
                try {fos.close();}
                catch (IOException ioe) {}
            }
        }
        return true;
    }

    /**
     * write out the data from the profile to the stream
     * includes comments
     */
    public void writeProfile(PeerProfile profile, OutputStream out) throws IOException {writeProfile(profile, out, true);}

    /**
     * write out the data from the profile to the stream
     * @param addComments add comment lines to the output
     * @since 0.9.41
     */
    @SuppressWarnings("deprecation")
    public void writeProfile(PeerProfile profile, OutputStream out, boolean addComments) throws IOException {
        String groups = null;
        if (!_context.profileOrganizer().isHighCapacity(profile.getPeer())) {groups = "Standard";}
        else {
            if (_context.profileOrganizer().isFast(profile.getPeer())) {groups = "Fast, High Capacity";}
            else {groups = "High Capacity";}
            if (_context.profileOrganizer().isWellIntegrated(profile.getPeer())) {groups = groups + ", Integrated";}
        }

        StringBuilder buf = new StringBuilder(512);
        RouterInfo info = _context.netDb().lookupRouterInfoLocally(profile.getPeer());
        // round up/down speed, integ, capacity
        int speed = Math.round(profile.getSpeedValue());
        int integration = Math.round(profile.getIntegrationValue());
        int capacity = Math.round(profile.getCapacityValue());
        if (addComments) {
            buf.append(HR).append(NL);
            buf.append("# Profile for peer: ").append(profile.getPeer().toBase64()).append(NL);
            if (_us != null) {buf.append("# as calculated by ").append(_us.toBase64()).append(NL);}
            buf.append(HR).append(NL).append(NL);
            // TODO: copy version, sig, caps to parent header?
            if (info != null) {
                String version = DataHelper.stripHTML(info.getVersion());
                buf.append(HR).append(NL);
                buf.append("# Router Information").append(NL);
                buf.append(HR).append(NL).append(NL);
                buf.append("# ").append("Version: ").append(version != null ? version : "unknown").append(NL);
                buf.append("# ").append("Signature: ").append(DataHelper.stripHTML(info.getIdentity().getSigningPublicKey().getType().toString())).append(NL);
                buf.append("# ").append("Capabilities: ").append(DataHelper.stripHTML(info.getCapabilities()).toUpperCase().replace("XO", "X").replace("PO", "P")).append(NL);
                if (speed > 0) {buf.append("# ").append("Speed: ").append(speed).append(" B/s").append(NL);}
                if (capacity > 0) {buf.append("# ").append("Capacity: ").append(capacity).append(capacity == 1 ? " tunnel" : " tunnels").append(" per hour").append(NL);}
                if (integration > 0) {buf.append("# ").append("Integration: ").append(integration).append(integration == 1 ? " peer" : " peers").append(NL);}
                buf.append("# ").append("Groups: ").append(groups).append(NL);
            } else {buf.append("# No RouterInfo found for peer").append(NL);}
        }
        if (profile.getSpeedBonus() != 0) {add(buf, addComments, "speedBonus", profile.getSpeedBonus(), "Manual Speed Score adjustment: " +  profile.getSpeedBonus());}
        if (profile.getCapacityBonus() != 0) {add(buf, addComments, "capacityBonus", profile.getCapacityBonus(), "Manual Capacity Score adjustment: " +  profile.getCapacityBonus());}
        if (profile.getIntegrationBonus() != 0) {add(buf, addComments, "integrationBonus", profile.getIntegrationBonus(), "Manual Integration Score adjustment:");}
        addDate(buf, addComments, "firstHeardAbout", profile.getFirstHeardAbout(), "First reference to peer received:");
        addDate(buf, addComments, "lastHeardAbout", profile.getLastHeardAbout(), "Last reference to peer received:");
        if (profile.getLastHeardFrom() != 0) {addDate(buf, addComments, "lastHeardFrom", profile.getLastHeardFrom(), "Last message from peer received:");}
        if (profile.getLastSendSuccessful() != 0) {addDate(buf, addComments, "lastSentToSuccessfully", profile.getLastSendSuccessful(), "Last successful message sent to peer:");}
        if (profile.getLastSendFailed() != 0) {addDate(buf, addComments, "lastFailedSend", profile.getLastSendFailed(), "Last failed message to sent peer:");}
        if (profile.getTunnelTestTimeAverage() != 0 && PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME) {
            add(buf, addComments, "tunnelTestTimeAverage", (long) profile.getTunnelTestTimeAverage(), "Average peer response time (ms):" +
                profile.getTunnelTestTimeAverage());
        }
        // TODO: needs clarification - difference between tunnel peak and tunnel peak tunnel? And round down KBps display to 2 decimal places
        if (profile.getPeakThroughputKBps() >= 1) {
            add(buf, addComments, "tunnelPeakThroughput", (long) profile.getPeakThroughputKBps(), "Tunnel Peak throughput (KB/s): " +
                profile.getPeakThroughputKBps());
        }
        if (profile.getPeakTunnelThroughputKBps() >= 1) {
            add(buf, addComments, "tunnelPeakTunnelThroughput", (long) profile.getPeakTunnelThroughputKBps(), "Tunnel Peak Tunnel throughput (KB/s): " +
                profile.getPeakTunnelThroughputKBps());
        }
        if (profile.getPeakTunnel1mThroughputKBps() >= 1) {
            add(buf, addComments, "tunnelPeakTunnel1mThroughput", (long) profile.getPeakTunnel1mThroughputKBps(), "Tunnel Peak Tunnel throughput for 1 minute (KB/s): " +
                profile.getPeakTunnel1mThroughputKBps());
        }
        if (addComments) {buf.append(HR).append(NL).append(NL);}
        out.write(buf.toString().getBytes("UTF-8"));

        if (profile.getIsExpanded()) { // only write out expanded data if, uh, we've got it
            // Don't persist tunnel history for ghost peers (0 accepted, >10 rejected)
            // They'll get a fresh start on next boot
            TunnelHistory th = profile.getTunnelHistory();
            boolean isGhost = th != null && th.getLifetimeAgreedTo() == 0 && th.getLifetimeRejected() > 10;
            if (!isGhost) {
                th.store(out, addComments);
            }
            profile.getTunnelCreateResponseTime().store(out, "tunnelCreateResponseTime", addComments);
            if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME) {
                profile.getTunnelTestResponseTime().store(out, "tunnelTestResponseTime", addComments);
            }
            if (profile.getPeerTestResponseTime() != null) {
                profile.getPeerTestResponseTime().store(out, "peerTestResponseTime", addComments);
            }
        }

        if (profile.getIsExpandedDB()) {
            profile.getDBHistory().store(out, addComments);
            profile.getDbIntroduction().store(out, "dbIntroduction", addComments);
            profile.getDbResponseTime().store(out, "dbResponseTime", addComments);
        }
    }

    /** @since 0.8.5 */
    private static void addDate(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {
            String when = val > 0 ? (new Date(val)).toString() : "Never";
            add(buf, true, name, val, description + ' ' + when);
        } else {add(buf, false, name, val, description);}
    }

    /** @since 0.8.5 */
    private static void add(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {buf.append("# ").append(description).append(NL);}
        else {buf.append(name).append('=').append(val).append(NL);}
    }

    public List<PeerProfile> readProfiles() {
        long start = System.currentTimeMillis();
        long down = _context.router().getEstimatedDowntime();
        long cutoff;
        if (down < 24*60*60*1000L) {
            cutoff = start - 3*24*60*60*1000;  // 3 days for low downtime
        } else if (down < 15*24*60*60*1000L) {
            cutoff = start - down - 14*24*60*60*1000;  // 14 days minus downtime
        } else {
            cutoff = start - 7*24*60*60*1000;  // 7 days default
        }
        List<File> files = selectFiles();
        // Pre-filter stale files by file timestamp to avoid reading them
        List<File> freshFiles = new ArrayList<File>(files.size());
        int staleDeleted = 0;
        for (File f : files) {
            if (f.lastModified() >= cutoff) {
                freshFiles.add(f);
            } else {
                f.delete();
                staleDeleted++;
            }
        }
        if (staleDeleted > 0 && _log.shouldInfo()) {
            _log.info("Deleted " + staleDeleted + " stale profile files by timestamp");
        }
        
        if (freshFiles.size() > LIMIT_PROFILES) {Collections.shuffle(freshFiles, _context.random());}
        List<PeerProfile> profiles = new ArrayList<PeerProfile>(Math.min(LIMIT_PROFILES, freshFiles.size()));
        int count = 0;
        for (File f : freshFiles) {
            if (count >= LIMIT_PROFILES) {
                f.delete();
                continue;
            }
            PeerProfile profile = readProfile(f, cutoff);
            if (profile != null) {
                profiles.add(profile);
                count++;
            }
        }
        
        long duration = System.currentTimeMillis() - start;
        if (_log.shouldInfo()) {_log.info("Loaded " + count + " peer profiles in " + duration + "ms");}
        return profiles;
    }

    private static class ProfileFilter implements FilenameFilter {
        public boolean accept(File dir, String filename) {
            return (filename.startsWith(PREFIX) &&
                    filename.length() >= MIN_NAME_LENGTH &&
                    (filename.endsWith(SUFFIX) || filename.endsWith(OLD_SUFFIX) || filename.endsWith(UNCOMPRESSED_SUFFIX)));
        }
    }

    private List<File> selectFiles() {
        FilenameFilter filter = new ProfileFilter();
        File files[] = _profileDir.listFiles(filter);
        if (files != null && files.length > 0) {migrate(files);}
        List<File> rv = new ArrayList<File>(1024);
        for (int j = 0; j < B64.length(); j++) {
            File subdir = new File(_profileDir, DIR_PREFIX + B64.charAt(j));
            files = subdir.listFiles(filter);
            if (files == null) {continue;}
            for (int i = 0; i < files.length; i++) {rv.add(files[i]);}
        }
        return rv;
    }

    /**
     *  Migrate from one-level to two-level directory structure
     *  @since 0.9.4
     */
    private void migrate(File[] files) {
        for (int i = 0; i < files.length; i++) {
            File from = files[i];
            if (!from.isFile()) {continue;}
            File dir = new File(_profileDir, DIR_PREFIX + from.getName().charAt(PREFIX.length()));
            File to = new File(dir, from.getName());
            FileUtil.rename(from, to);
        }
    }

    /**
     *  Delete profile files with timestamps older than 'age' ago
     *  @return number deleted
     *  @since 0.9.28
     */
    public int deleteOldProfiles(long age) {
        long cutoff = System.currentTimeMillis() - age;
        List<File> files = selectFiles();
        int i = 0;
        for (File f : files) {
            if (!f.isFile()) {continue;}
            if (f.lastModified() < cutoff && files.size() > LIMIT_PROFILES) {
                i++;
                f.delete();
                //_log.warn("Not deleting " + f + " (debugging active)");
            }
        }
        if (_log.shouldWarn()) {
            if (i > 0) {
                _log.warn("Deleted " + i + " STALE peer profiles");
                //_log.warn("Not deleting " + i + " (stale?) peer profiles -> Will expire when read at startup");
            }
        }
        return i;
    }

    /**
     *  @param cutoff delete and return null if older than this (absolute time)
     */
    @SuppressWarnings("deprecation")
    public PeerProfile readProfile(File file, long cutoff) {
        if (file.lastModified() < cutoff) {
            //if (_log.shouldWarn())
            //    _log.warn("Not deleting STALE peer profile " + file.getName() + " -> Will expire when read at startup");
            file.delete();
            return null;
        }
        Hash peer = getHash(file.getName());
        try {
            if (peer == null) {
                if (_log.shouldError()) {_log.error("Peer profile: " + file.getName() + " is not a valid hash -> Ignoring...");}
                file.delete();
                return null;
            }

            PeerProfile profile = new PeerProfile(_context, peer);
            Properties props = new Properties();
            loadProps(props, file);
            long lastSentToSuccessfully = getLong(props, "lastSentToSuccessfully");
            long lastHeardFrom = getLong(props, "lastHeardFrom");
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            String caps = "";

            if (info != null) {caps = DataHelper.stripHTML(info.getCapabilities());}
            else {
                if (_log.shouldDebug()) {_log.debug("Deleting profile without RouterInfo: " + file.getName());}
                file.delete();
                return null;
            }

            if (lastSentToSuccessfully <= cutoff && lastHeardFrom <= cutoff) {
                if (_log.shouldDebug()) {_log.debug("Deleting stale profile: " + file.getName());}
                file.delete();
                return null;
            }

            if (!caps.equals("") && caps.contains("K") || caps.contains("L") || caps.contains("M") || caps.contains("U")) {
                if (_log.shouldDebug()) {_log.debug("Deleting uninteresting profile: " + file.getName() + " -> K, L, M or unreachable");}
                file.delete();
                return null;
            } else if (file.getName().endsWith(OLD_SUFFIX)) {
                // migrate to new file name, ignore failure
                String newName = file.getAbsolutePath();
                newName = newName.substring(0, newName.length() - OLD_SUFFIX.length()) + SUFFIX;
                boolean success = file.renameTo(new File(newName));
                if (!success) {file.delete();} // new file exists and on Windows?
            }

            profile.setCapacityBonus((int) getLong(props, "capacityBonus"));
            profile.setIntegrationBonus((int) getLong(props, "integrationBonus"));
            profile.setSpeedBonus((int) getLong(props, "speedBonus"));

            long fh = getLong(props, "firstHeardAbout");
            if (fh <= 0) {fh = file.lastModified();}
            profile.setFirstHeardAbout(fh);
            long lh = getLong(props, "lastHeardAbout");
            if (lh <= 0) {lh = fh;}
            profile.setLastHeardAbout(lh);
            profile.setLastSendSuccessful(getLong(props, "lastSentToSuccessfully"));
            profile.setLastSendFailed(getLong(props, "lastFailedSend"));
            profile.setLastHeardFrom(getLong(props, "lastHeardFrom"));

            if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME) {
                profile.setTunnelTestTimeAverage(getFloat(props, "tunnelTestTimeAverage"));
            }

            profile.setPeakThroughputKBps(getFloat(props, "tunnelPeakThroughput"));
            profile.setPeakTunnelThroughputKBps(getFloat(props, "tunnelPeakTunnelThroughput"));
            profile.setPeakTunnel1mThroughputKBps(getFloat(props, "tunnelPeakTunnel1mThroughput"));
            profile.getTunnelHistory().load(props);

            // In the interest of keeping the in-memory profiles small,
            // don't load the DB info at all unless there is something interesting there
            // (i.e. floodfills)
            if (getLong(props, "dbHistory.lastLookupSuccessful") > 0 ||
                getLong(props, "dbHistory.lastLookupFailed") > 0 ||
                getLong(props, "dbHistory.lastStoreSuccessful") > 0 ||
                getLong(props, "dbHistory.lastStoreFailed") > 0 &&
                (!caps.equals("") && !caps.contains("K") || !caps.contains("L") || !caps.contains("M") || !caps.contains("U"))) {
                profile.expandDBProfile();
                profile.getDBHistory().load(props);
                profile.getDbIntroduction().load(props, "dbIntroduction", true);
                profile.getDbResponseTime().load(props, "dbResponseTime", true);
            }

            if (!caps.equals("") && !caps.contains("K") || !caps.contains("L") || !caps.contains("M") || !caps.contains("U")) {
                profile.getTunnelCreateResponseTime().load(props, "tunnelCreateResponseTime", true);
                if (PeerProfile.ENABLE_TUNNEL_TEST_RESPONSE_TIME) {
                    profile.getTunnelTestResponseTime().load(props, "tunnelTestResponseTime", true);
                }
            }

            if (_log.shouldDebug()) {
                _log.debug("Loaded profile for [" + peer.toBase64().substring(0,6) + "] from " + file.getName());
            }

            fixupFirstHeardAbout(profile);
            return profile;
        } catch (Exception e) {
            if (_log.shouldWarn()) {_log.warn("Error loading properties from " + file.getAbsolutePath(), e);}
            file.delete();
            return null;
        }
    }

    /**
     *  First heard about wasn't always set correctly before,
     *  set it to the minimum of all recorded timestamps.
     *
     *  @since 0.9.24
     */
    private void fixupFirstHeardAbout(PeerProfile p) {
        long min = Long.MAX_VALUE;
        long t = p.getLastHeardAbout();
        if (t > 0 && t < min) min = t;
        t = p.getLastSendSuccessful();
        if (t > 0 && t < min) min = t;
        t = p.getLastSendFailed();
        if (t > 0 && t < min) min = t;
        t = p.getLastHeardFrom();
        if (t > 0 && t < min) min = t;
        TunnelHistory th = p.getTunnelHistory();
        if (th != null) {
            t = th.getLastAgreedTo();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedCritical();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedBandwidth();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedTransient();
            if (t > 0 && t < min) min = t;
            t = th.getLastRejectedProbabalistic();
            if (t > 0 && t < min) min = t;
            t = th.getLastFailed();
            if (t > 0 && t < min) min = t;
        }
        long fha = p.getFirstHeardAbout();
        if (min > 0 && min < Long.MAX_VALUE && (fha <= 0 || min < fha)) {
            p.setFirstHeardAbout(min);
            if (_log.shouldDebug())
                _log.debug("Fixed FirstHeardAbout time for [" + p.getPeer().toBase64().substring(0,6) + "] to " + (new Date(min)));
        }
    }

    static long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {}
        }
        return 0;
    }

    private final static float getFloat(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Float.parseFloat(val);
            } catch (NumberFormatException nfe) {}
        }
        return 0.0f;
    }

    private void loadProps(Properties props, File file) throws IOException {
        InputStream fin = null;
        try {
            fin = new BufferedInputStream(new FileInputStream(file), 1);
            fin.mark(1);
            int c = fin.read();
            fin.reset();
            if (c == '#') {
                // uncompressed
                if (_log.shouldDebug())
                    _log.debug("Loading " + file.getName());
                DataHelper.loadProps(props, fin);
            } else {
                // compressed (or corrupt...)
                if (_log.shouldDebug())
                    _log.debug("Loading " + file.getName());
                DataHelper.loadProps(props, new GZIPInputStream(fin));
            }
        } finally {
            try {
                if (fin != null) fin.close();
            } catch (IOException e) {}
        }
    }

    private Hash getHash(String name) {
        if (name.length() < PREFIX.length() + 44)
            return null;
        String key = name.substring(PREFIX.length());
        key = key.substring(0, 44);
        //Hash h = new Hash();
        try {
            //h.fromBase64(key);
            byte[] b = Base64.decode(key);
            if (b == null)
                return null;
            Hash h = Hash.create(b);
            return h;
        } catch (RuntimeException dfe) {
            _log.warn("Invalid Base64 [" + key + "]", dfe);
            return null;
        }
    }

    private File pickFile(PeerProfile profile) {
        String hash = profile.getPeer().toBase64();
        File dir = new File(_profileDir, DIR_PREFIX + hash.charAt(0));
        return new File(dir, PREFIX + hash + SUFFIX);
    }

    /**
     * Delete profile files not in 'keepPeers', if total file count > maxProfiles.
     * Prioritizes keeping profiles with recent lastHeardFrom activity.
     */
    public void purgeExcessProfiles(Set<Hash> keepPeers, int maxProfiles) {
        List<File> files = selectFiles();
        if (files.size() <= maxProfiles || keepPeers == null) {
            return; // within limit
        }

        // Build list of (file, lastHeardFrom) for files NOT in keepPeers
        // Sort by lastHeardFrom descending - keep most recently heard from peers
        List<PeerFileMetadata> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();
        long staleThreshold = now - (12 * 60 * 60 * 1000L); // 12 hours

        for (File f : files) {
            Hash peer = getHash(f.getName());
            if (peer == null || keepPeers.contains(peer)) {
                continue; // protected
            }

            // Try to check RouterInfo for bandwidth tier (K/L/M = delete)
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocallyWithoutValidation(peer);
            if (info != null) {
                String tier = info.getBandwidthTier();
                if ("K".equals(tier) || "L".equals(tier) || "M".equals(tier)) {
                    f.delete();
                    continue;
                }
            }

            // Get lastHeardFrom for prioritization - primary eviction criteria
            long lastHeardFrom = 0;
            try {
                Properties props = new Properties();
                loadProps(props, f);
                lastHeardFrom = getLong(props, "lastHeardFrom");
            } catch (IOException e) {
                if (_log.shouldDebug()) _log.debug("Failed to read profile " + f, e);
            }

            candidates.add(new PeerFileMetadata(f, lastHeardFrom, f.lastModified()));
        }

        // Now delete oldest/least active first until within limit
        int overage = files.size() - maxProfiles;
        if (overage <= 0) return;

        // Sort by lastHeardFrom ascending (oldest/least active first), then by file mtime
        candidates.sort((a, b) -> {
            if (a.lastHeardFrom != b.lastHeardFrom) {
                return Long.compare(a.lastHeardFrom, b.lastHeardFrom);
            }
            return Long.compare(a.lastModified, b.lastModified);
        });

        int toDelete = Math.min(overage, candidates.size());

        for (int i = 0; i < toDelete; i++) {
            candidates.get(i).file.delete();
        }

        if (_log.shouldInfo()) {
            _log.info("Purged " + toDelete + " stale profile files (based on lastHeardFrom)");
        }
    }

    // Helper class
    private static class PeerFileMetadata {
        final File file;
        final long lastHeardFrom;
        final long lastModified;
        PeerFileMetadata(File f, long lh, long lm) { file = f; lastHeardFrom = lh; lastModified = lm; }
    }

}
