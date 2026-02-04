package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Use at your own risk.
 * zzz 2008-06
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.app.ClientAppManager;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.GeoIP;
import net.i2p.router.transport.TransportUtil;
import net.i2p.router.transport.udp.UDPTransport;
import net.i2p.update.UpdateManager;
import net.i2p.update.UpdateType;
import net.i2p.util.Addresses;
import net.i2p.util.LHMCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;

/**
 * Manage blocking by IP address, in a manner similar to the Banlist,
 * which blocks by router hash.
 *
 * We also try to keep the two lists in sync: if a router at a given IP is
 * blocked, we will also banlist it "forever" (until the next reboot).
 *
 * While the reverse case (blocking the IP of a router banlisted forever)
 * is not automatic, the transports will call add() below to block the IP,
 * which allows the transports to terminate an inbound connection before
 * the router ident handshake.
 *
 * And the on-disk blocklist can also contain router hashes to be banlisted.
 *
 * So, this class maintains three separate lists:
 *<pre>
 *   1) The list of IP ranges, read in from a file at startup
 *   2) The list of hashes, read in from the same file
 *   3) A list of single IPs, initially empty, added to as needed
 *</pre>
 *
 * Read in the IP blocklist from a file, store it in-memory as efficiently
 * as we can, and perform tests against it as requested.
 *
 * When queried for a peer that is blocklisted but isn't banlisted,
 * banlist it forever, then go back to the file to get the original
 * entry so we can add the reason to the banlist text.
 *
 * On-disk blocklist supports IPv4 only.
 * In-memory supports both IPv4 and IPv6.
 */
public class Blocklist {
    private final Log _log;
    private final RouterContext _context;
    private long _blocklist[];
    private volatile int _blocklistSize;
    private long _countryBlocklist[];
    private int _countryBlocklistSize;
    private final Object _lock = new Object();
    private Entry _wrapSave;
    private final Set<Hash> _inProcess = new HashSet<Hash>(4);
    private final File _blocklistFeedFile;
    private final boolean _haveIPv6;
    private boolean _started;
    private long _lastExpired = 0;
    // temp
    private final Map<Hash, String> _peerBlocklist = new HashMap<Hash, String>(4);

    private static final String PROP_BLOCKLIST_ENABLED = "router.blocklist.enable";
    private static final String PROP_BLOCKLIST_FEEDLIST_ENABLED = "router.blocklistFeed.enable";
    private static final String PROP_BLOCKLIST_TOR_ENABLED = "router.blocklistTor.enable";
    private static final String PROP_BLOCKLIST_COUNTRIES_ENABLED = "router.blocklistCountries.enable";
    private static final String PROP_BLOCKLIST_DETAIL = "router.blocklist.detail";
    private static final String PROP_BLOCKLIST_FILE = "router.blocklist.file";
    private static final String PROP_BLOCKLIST_EXPIRE_INTERVAL = "router.blocklist.expireInterval";
    /**
     * Default blocklist filename in the installation directory.
     */
    public static final String BLOCKLIST_FILE_DEFAULT = "blocklist.txt";
    /**
     * Tor exit nodes blocklist filename.
     */
    public static final String BLOCKLIST_FILE_TOR_EXITS = "blocklist_tor.txt";
    /** Feed blocklist file path */
    private static final String BLOCKLIST_FEED_FILE = "docs/feed/blocklist/blocklist.txt";
    /** Country-based blocklist filename.
     *  @since 0.9.48
     */
    public static final String BLOCKLIST_COUNTRY_FILE = "blocklist-country.txt";

    /**
     *  Limits of transient (in-memory) blocklists.
     *  Note that it's impossible to prevent clogging up
     *  the tables by a determined attacker, esp. on IPv6
     */
    private static final int MAX_IPV4_SINGLES = SystemVersion.isSlow() ? 2048 : 8192;
    private static final int MAX_IPV6_SINGLES = SystemVersion.isSlow() ? 1024 : 4096;
    private static final int MAX_TEMP_BANS = SystemVersion.isSlow() ? 1024 : 4096;

    private final Map<Integer, Object> _singleIPBlocklist = new LHMCache<Integer, Object>(MAX_IPV4_SINGLES);
    private final Map<BigInteger, Object> _singleIPv6Blocklist;
    private final Map<Integer, Long> _tempIPBlocklist = new LHMCache<Integer, Long>(MAX_TEMP_BANS);

    private static final Object DUMMY = Integer.valueOf(0);

    /**
     *  For Update Manager
     *  @since 0.9.48
     */
    public static final String ID_FEED = "feed";
    /** System blocklist ID */
    private static final String ID_SYSTEM = "system";
    /** User-configured blocklist ID */
    private static final String ID_LOCAL = "local";
    /** Country-based blocklist ID */
    private static final String ID_COUNTRY = "country";
    /** User-specified blocklist ID */
    private static final String ID_USER = "user";
    /** Sybil attack blocklist ID */
    public static final String ID_SYBIL = "sybil";
    /** Tor exit nodes blocklist ID */
    public static final String ID_TOR = "tor";

    /**
     *  Constructor.
     *  Router MUST call startup()
     *  @param context the router context
     */
    public Blocklist(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(Blocklist.class);
        _blocklistFeedFile = new File(context.getConfigDir(), BLOCKLIST_FEED_FILE);
        _haveIPv6 = TransportUtil.getIPv6Config(_context, "SSU") != TransportUtil.IPv6Config.IPV6_DISABLED &&
                    Addresses.isConnectedIPv6();
        _singleIPv6Blocklist = _haveIPv6 ? new LHMCache<BigInteger, Object>(MAX_IPV6_SINGLES) : null;
    }

    /** only for testing with main() */
    private Blocklist() {
        _context = null;
        _log = new Log(Blocklist.class);
        _blocklistFeedFile = new File(BLOCKLIST_FEED_FILE);
        _haveIPv6 = TransportUtil.getIPv6Config(_context, "SSU") != TransportUtil.IPv6Config.IPV6_DISABLED &&
                    Addresses.isConnectedIPv6();
        _singleIPv6Blocklist = _haveIPv6 ? new LHMCache<BigInteger, Object>(MAX_IPV6_SINGLES) : null;
    }

    /**
     *  Get the blocklist expiration interval from configuration.
     *  @return the expiration interval in milliseconds, or 0 if disabled
     */
    private int expireInterval() {
        String expireIntervalValue = _context.getProperty(PROP_BLOCKLIST_EXPIRE_INTERVAL, "0");
        try {
            Integer expireIntervalInt = 0;
            if (expireIntervalValue.endsWith("s")) {
                expireIntervalValue = expireIntervalValue.substring(0, expireIntervalValue.length() - 1);
                expireIntervalInt = Integer.parseInt(expireIntervalValue) * 1000;
            } else if (expireIntervalValue.endsWith("m")) {
                expireIntervalValue = expireIntervalValue.substring(0, expireIntervalValue.length() - 1);
                expireIntervalInt = Integer.parseInt(expireIntervalValue) * 60000;
            } else if (expireIntervalValue.endsWith("h")) {
                expireIntervalValue = expireIntervalValue.substring(0, expireIntervalValue.length() - 1);
                expireIntervalInt = Integer.parseInt(expireIntervalValue) * 3600000;
            } else if (expireIntervalValue.endsWith("d")) {
                expireIntervalValue = expireIntervalValue.substring(0, expireIntervalValue.length() - 1);
                expireIntervalInt = Integer.parseInt(expireIntervalValue) * 86400000;
            } else {
                expireIntervalInt = Integer.parseInt(expireIntervalValue);
            }
            if (expireIntervalInt < 0) {expireIntervalInt = 0;}
            return expireIntervalInt;
        } catch(NumberFormatException nfe) {
            if (_log.shouldLog(_log.ERROR)) {
                _log.error("Format error in " + PROP_BLOCKLIST_EXPIRE_INTERVAL, nfe);
            }
        }
        // if we don't have a valid value in this field, return 0 which is the same as disabling it.
        return 0;
    }

    /**
     *  Loads the following files in-order:
     *  $I2P/blocklist.txt
     *  $I2P/blocklist_tor.txt
     *  ~/.i2p/blocklist.txt
     *  ~/.i2p/docs/feed/blocklist/blocklist.txt
     *  ~/.i2p/blocklist-countries.txt
     *  File if specified with router.blocklist.file
     */
    public synchronized void startup() {
        if (_started) {return;}
        _started = true;
        boolean blocklistEnabled = _context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_ENABLED);
        boolean blocklistTorEnabled =  blocklistEnabled && _context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_TOR_ENABLED);
        boolean blocklistFeedEnabled = blocklistEnabled && _context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_FEEDLIST_ENABLED);
        boolean blocklistCountryEnabled = (_context.router().isHidden() && _context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_COUNTRIES_ENABLED)) ||
                                          _context.getBooleanProperty(GeoIP.PROP_BLOCK_MY_COUNTRY);

        if (!blocklistEnabled) {
            _log.warn("All blocklists disabled -> \'router.blocklist.enable=false\' is configured");
            disable();
            return;
        }

        List<BLFile> files = new ArrayList<BLFile>(5);
        File blFile = new File(_context.getBaseDir(), BLOCKLIST_FILE_DEFAULT); // install dir
        files.add(new BLFile(blFile, ID_SYSTEM));

        if (blocklistTorEnabled) {
            blFile = new File(_context.getBaseDir(), BLOCKLIST_FILE_TOR_EXITS);
            files.add(new BLFile(blFile, ID_TOR));
        } else {_log.warn("Tor blocklist disabled -> \'router.blocklistTor.enable=false\' is configured");}

        if (blocklistFeedEnabled) {
            files.add(new BLFile(_blocklistFeedFile, ID_FEED));
        } else {_log.warn("Feed blocklist disabled -> \'router.blocklistFeed.enable=false\' is configured");}

        if (blocklistCountryEnabled) {
            blFile = new File(_context.getConfigDir(), BLOCKLIST_COUNTRY_FILE);
            files.add(new BLFile(blFile, ID_COUNTRY));
            if (_context.getBooleanProperty(PROP_BLOCKLIST_COUNTRIES_ENABLED)) {
                _log.warn("Countries blocklist enabled -> \'router.blocklistCountries.enable=true\' is configured");
            } else if (_context.router().isHidden()) {
                _log.warn("Countries blocklist enabled -> Router is operating in hidden mode");
            }
        }

        if (blocklistEnabled && !_context.getConfigDir().equals(_context.getBaseDir())) {
            blFile = new File(_context.getConfigDir(), BLOCKLIST_FILE_DEFAULT); // config dir
            files.add(new BLFile(blFile, ID_LOCAL));
        }

        // user specified
        String file = _context.getProperty(PROP_BLOCKLIST_FILE);
        if (blocklistEnabled && file != null && !file.equals(BLOCKLIST_FILE_DEFAULT)) {
            blFile = new File(file);
            if (!blFile.isAbsolute()) {blFile = new File(_context.getConfigDir(), file);}
            files.add(new BLFile(blFile, ID_USER));
        }

        Job job = new ReadinJob(files);
        // Run immediately, so it's initialized before netdb. As this is called by Router.runRouter()
        // before job queue parallel operation, this will block StartupJob, and will complete before
        // netdb initialization. If there is a huge blocklist, it will delay router startup, but it's
        // important to have this initialized before we read in the netdb.
        _context.jobQueue().addJob(job);
        if (expireInterval() > 0) {
            Job cleanupJob = new CleanupJob();
            cleanupJob.getTiming().setStartAfter(_context.clock().now() + expireInterval());
            _context.jobQueue().addJob(cleanupJob);
        }
    }

    /**
     *  @since 0.9.48
     */
    private static class BLFile {
        public final File file;
        public final String id;
        public long version;
        public BLFile(File f, String s) {file = f; id = s;}
    }

    /**
     *  Delay telling update manager until it's there
     *  @since 0.9.48
     */
    private class VersionNotifier extends SimpleTimer2.TimedEvent {
        public final List<BLFile> blfs;

        public VersionNotifier(List<BLFile> bf) {
            super(_context.simpleTimer2(), 2*60*1000L);
            blfs = bf;
        }

        public void timeReached() {
            ClientAppManager cmgr = _context.clientAppManager();
            if (cmgr != null) {
                UpdateManager umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
                if (umgr != null) {
                    for (BLFile blf : blfs) {
                        if (blf.version > 0) {
                            umgr.notifyInstalled(UpdateType.BLOCKLIST, blf.id, Long.toString(blf.version));
                        }
                    }
                } else {_log.warn("No update manager");}
            }
        }
    }

    private class CleanupJob extends JobImpl {
        public CleanupJob() {super(_context);}
        public String getName() {
            if (expireInterval() > 0) {
                String expiry = expireInterval() >= 600000 ? (expireInterval() / 60 / 1000) + "m" : (expireInterval() / 1000 + "s");
                return "Expire blocklist at user-defined interval of " + expiry;
            } else {return "Expire blocklist";}
        }
        public void runJob() {
            clear();
            _lastExpired = System.currentTimeMillis();
            if (_log.shouldDebug()) {_log.debug("Expiring blocklist entrys at" + _lastExpired);}
            super.requeue(expireInterval()); // schedule the next one
        }
    }

    private void clear() {
        synchronized(_singleIPBlocklist) {_singleIPBlocklist.clear();}
        if (_singleIPv6Blocklist != null) {
            synchronized(_singleIPv6Blocklist) {_singleIPv6Blocklist.clear();}
        }
    }

    private class ReadinJob extends JobImpl {
        private final List<BLFile> _files;

        /**
         *  @param files not necessarily existing, but avoid dups
         */
        public ReadinJob (List<BLFile> files) {
            super(_context);
            _files = files;
        }

        public String getName() {return "Read Blocklist";}

        public void runJob() {
            synchronized (_lock) {
                _blocklist = allocate(_files);
                if (_blocklist == null) {return;}
                int ccount = process();
                if (ccount <= 0) {disable(); return;}
                _blocklistSize = merge(_blocklist, ccount);
                // we're done with _peerBlocklist, but leave it in case we need it for a later readin
            }
            new VersionNotifier(_files); // schedules itself
        }

        private int process() {
            int count = 0;
                try {
                    for (BLFile blf : _files) {count = readBlocklistFile(blf, _blocklist, count);}
                } catch (OutOfMemoryError oom) {
                    _log.log(Log.CRIT, "OOM processing the blocklist");
                    disable();
                    return 0;
                }
            for (Hash peer : _peerBlocklist.keySet()) {
                String reason;
                String comment = _peerBlocklist.get(peer);
                String peerhash = peer.toBase64().substring(0,6);
                if (comment != null) {reason = " <b>➜</b> " + _x("Hash") + ": " + peerhash;}
                else {reason = " <b>➜</b> " + _x("Banned by Router Hash");}
                banlistRouter(peer, reason, comment);
            }
            _peerBlocklist.clear();
            return count;
        }
    }

    private void banlistRouter(Hash peer, String reason, String comment) {
        if (expireInterval() > 0) {
            _context.banlist().banlistRouter(peer, reason, comment, null, expireInterval());
        } else {
            _context.banlist().banlistRouterForever(peer, reason, comment);
        }
    }

    /**
     *  The blocklist-country.txt file was created or updated.
     *  Read it in. Not required normally, as the country file
     *  is read by startup().
     *  @since 0.9.48
     */
    public synchronized void addCountryFile() {
        File blFile = new File(_context.getConfigDir(), BLOCKLIST_COUNTRY_FILE);
        BLFile blf = new BLFile(blFile, ID_COUNTRY);
        List<BLFile> c = Collections.singletonList(blf);
        long[] cb = allocate(c);
        if (cb == null) {return;}
        int count = readBlocklistFile(blf, cb, 0);
        if (count <= 0) {return;}
        ClientAppManager cmgr = _context.clientAppManager();
        if (cmgr != null) {
            UpdateManager umgr = (UpdateManager) cmgr.getRegisteredApp(UpdateManager.APP_NAME);
            if (umgr != null) {
                umgr.notifyInstalled(UpdateType.BLOCKLIST, ID_COUNTRY, Long.toString(blFile.lastModified()));
            }
        }
        count = merge(cb, count);
        _countryBlocklistSize = count;
        _countryBlocklist = cb;
    }

    /**
     *  Disable the blocklist and clear all entries.
     */
    public void disable() {
        // hmm better block out any checks in process
        synchronized (_lock) {
            _blocklistSize = 0;
            _blocklist = null;
        }
    }

    /**
     *  Allocate an array for blocklist entries.
     *  @param files the list of blocklist files to read
     *  @return the allocated array, or null on out of memory error
     *  @since 0.9.18 split out from readBlocklistFile()
     */
    private long[] allocate(List<BLFile> files) {
        int maxSize = 0;
        for (BLFile blf : files) {maxSize += getSize(blf.file);}
        try {return new long[maxSize + files.size()];} // extra for wrapsave
        catch (OutOfMemoryError oom) {
            _log.log(Log.CRIT, "OOM creating the blocklist");
            return null;
        }
    }

   /**
    * Read in and parse the blocklist.
    * The blocklist need not be sorted, and may contain overlapping entries.
    *
    * Acceptable formats (IPV4 only):
    *   #comment (# must be in column 1)
    *   comment:IP-IP
    *   comment:morecomments:IP-IP
    *   IP-IP
    *   (comments also allowed before any of the following)
    *   IP/masklength
    *   IP
    *   hostname (DNS looked up at list readin time, not dynamically, so may not be much use)
    *   44-byte Base64 router hash
    *
    * Acceptable formats (IPV6 only):
    *   comment:IPv6 (must replace : with ; e.g. abcd;1234;0;12;;ff)
    *   IPv6 (must replace : with ; e.g. abcd;1234;0;12;;ff)
    *
    * No whitespace allowed after the last ':'.
    *
    * For further information and downloads:
    *   http://www.bluetack.co.uk/forums/index.php?autocom=faq&CODE=02&qid=17
    *   http://blocklist.googlepages.com/
    *   http://www.cymru.com/Documents/bogon-list.html
    *
    *
    * Must call allocate() before and merge() after.
    *
    *  @param blocklist out parameter, entries stored here
    *  @param count current number of entries
    *  @return new number of entries
    */
    private int readBlocklistFile(BLFile blf, long[] blocklist, int count) {
        File blFile = blf.file;
        if (blFile == null || (!blFile.exists()) || blFile.length() <= 0) {
            if (_log.shouldWarn()) {_log.warn("Blocklist file not found: " + blFile);}
            return count;
        }

        long start = _context.clock().now();
        int oldcount = count;
        int badcount = 0;
        int peercount = 0;
        int feedcount = 0;
        long ipcount = 0;
        final boolean isFeedFile = blFile.equals(_blocklistFeedFile);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(blFile), "UTF-8"));
            String source = blFile.toString();
            String buf = null;
            while ((buf = br.readLine()) != null) {
                Entry e = parse(buf, true);
                if (e == null) {
                    badcount++;
                    continue;
                }
                if (e.peer != null) {
                    _peerBlocklist.put(e.peer, e.comment);
                    peercount++;
                    continue;
                }
                byte[] ip1 = e.ip1;
                if (ip1.length == 4) {
                    byte[] ip2 = e.ip2;
                    store(ip1, ip2, blocklist, count++);
                    ipcount += 1 + toInt(ip2) - toInt(ip1); // includes dups, oh well
                } else {add(ip1, source);} // IPv6
            }
        } catch (IOException ioe) {
            if (_log.shouldError()) {_log.error("Error reading the blocklist file", ioe);}
            return count;
        } catch (OutOfMemoryError oom) {
            disable();
            _log.log(Log.CRIT, "OOM reading the blocklist");
            return 0;
        } finally {
            if (br != null) try {br.close();} catch (IOException ioe) {}
        }

        if (_wrapSave != null) {
            // the extra record generated in parse() by a line that
            // wrapped around 128.0.0.0
            store(_wrapSave.ip1, _wrapSave.ip2, blocklist, count++);
            ipcount += 1 + toInt(_wrapSave.ip2) - toInt(_wrapSave.ip1);
            _wrapSave = null;
        }
        int read = isFeedFile ? feedcount : (count - oldcount);
        // save to tell the update manager
        if (read > 0) {blf.version = blFile.lastModified();}
        if (_log.shouldWarn()) {
            _log.warn("Stats for " + blFile + ":" +
                      "\n* Removed " + badcount + " bad entries and comment lines" +
                      "\n* Read " + read + " valid entries from the blocklist " + blFile +
                      "\n* Blocking " + ipcount + " IPs and " + peercount + " hashes (" + (ipcount + peercount) + " total)" +
                      "\n* Blocklist processing finished, took: " + (_context.clock().now() - start) + "ms");
        }
        return count;
    }

    /**
     *  @param count valid entries in blocklist before merge
     *  @return count valid entries in blocklist after merge
     *  @since 0.9.18 split out from readBlocklistFile()
     */
    private int merge(long[] blocklist, int count) {
        long start = _context.clock().now();
        // This is a standard signed sort, so the entries will be ordered
        // 128.0.0.0 ... 255.255.255.255 0.0.0.0 .... 127.255.255.255
        // But that's ok.
        int removed = 0;
        try {
            Arrays.sort(blocklist, 0, count);
            removed = removeOverlap(blocklist, count);
            if (removed > 0) {
                // Sort again to remove the dups that were "zeroed" out as 127.255.255.255-255.255.255.255
                Arrays.sort(blocklist, 0, count);
                // sorry, no realloc to save memory, don't want to blow up now
            }
        } catch (OutOfMemoryError oom) {
            disable();
            _log.log(Log.CRIT, "OOM sorting the blocklist");
            return 0;
        }
        int blocklistSize = count - removed;
        if (_log.shouldInfo()) {
            _log.info("Merged Stats:" +
                      "\n* Read " + count + " total entries from the blocklists" +
                      "\n* Merged " + removed + " overlapping entries" +
                      "\n* Result is " + blocklistSize + " entries" +
                      "\n* Blocklist processing finished in " + (_context.clock().now() - start) + "ms");
        }
        return blocklistSize;
    }

    /**
     *  The result of parsing one line.
     */
    private static class Entry {
        /** the comment extracted from the line */
        final String comment;
        /** the starting IP address */
        final byte ip1[];
        /** the ending IP address */
        final byte ip2[];
        /** the router hash, if this is a hash entry */
        final Hash peer;

        /**
         *  @param c the comment
         *  @param h the router hash (may be null)
         *  @param i1 the starting IP
         *  @param i2 the ending IP
         */
        public Entry(String c, Hash h, byte[] i1, byte[] i2) {
             comment = c;
             peer = h;
             ip1 = i1;
             ip2 = i2;
        }
    }

    /**
     *  Parse one line, returning a temp data structure with the result
     */
    private Entry parse(String buf, boolean shouldLog) {
        byte[] ip1;
        byte[] ip2;
        int start1 = 0;
        int end1 = buf.length();
        if (end1 <= 0) {return null;}  // blank
        int start2 = -1;
        int mask = -1;
        String comment = null;
        int index = buf.indexOf('#');
        if (index == 0) {return null;} // comment
        index = buf.lastIndexOf(':');
        if (index >= 0) {
            comment = buf.substring(0, index);
            start1 = index + 1;
        }
        if (end1 - start1 == 44 && buf.substring(start1).indexOf('.') < 0) {
            byte b[] = Base64.decode(buf.substring(start1));
            if (b != null) {return new Entry(comment, Hash.create(b), null, null);}
        }
        index = buf.indexOf('-', start1);
        if (index >= 0) {
            end1 = index;
            start2 = index + 1;
        } else {
            index = buf.indexOf('/', start1);
            if (index >= 0) {
                end1 = index;
                mask = index + 1;
            }
        }
        if (end1 - start1 <= 0) {return null;} // blank
        try {
            String sip = buf.substring(start1, end1);
            sip = sip.replace(';', ':'); // IPv6
            InetAddress pi = InetAddress.getByName(sip);
            if (pi == null) {return null;}
            ip1 = pi.getAddress();
            if (start2 >= 0) {
                pi = InetAddress.getByName(buf.substring(start2));
                if (pi == null) {return null;}
                ip2 = pi.getAddress();
                if (ip2.length != 4) {throw new UnknownHostException();}
                if ((ip1[0] & 0xff) < 0x80 && (ip2[0] & 0xff) >= 0x80) {
                    if (_wrapSave == null) {
                        // don't cross the boundary 127.255.255.255 - 128.0.0.0 because we are sorting using signed arithmetic
                        _wrapSave = new Entry(comment, null, new byte[] {(byte)0x80,0,0,0}, new byte[] {ip2[0], ip2[1], ip2[2], ip2[3]});
                        ip2 = new byte[] {127, (byte)0xff, (byte)0xff, (byte)0xff};
                    } else {throw new NumberFormatException();} // We only save one entry crossing the boundary, throw the rest out

                }
                for (int i = 0; i < 4; i++) {
                     if ((ip2[i] & 0xff) > (ip1[i] & 0xff)) {break;}
                     if ((ip2[i] & 0xff) < (ip1[i] & 0xff)) {throw new NumberFormatException();} // backwards
                }
            } else if (mask >= 0) {
                int m = Integer.parseInt(buf.substring(mask));
                if (m < 3 || m > 32) {throw new NumberFormatException();}
                ip2 = new byte[4];
                // ick
                for (int i = 0; i < 4; i++) {ip2[i] = ip1[i];}
                for (int i = 0; i < 32-m; i++) {ip2[(31-i)/8] |= (0x01 << (i%8));}
            } else {ip2 = ip1;}
        } catch (UnknownHostException uhe) {
            if (shouldLog) {_log.logAlways(Log.WARN, "Format error in the blocklist file: " + buf);}
            return null;
        } catch (NumberFormatException nfe) {
            if (shouldLog) {_log.logAlways(Log.WARN, "Format error in the blocklist file: " + buf);}
            return null;
        } catch (IndexOutOfBoundsException ioobe) {
            if (shouldLog) {_log.logAlways(Log.WARN, "Format error in the blocklist file: " + buf);}
            return null;
        }
        return new Entry(comment, null, ip1, ip2);
    }

    /**
     *  Count the number of entries in a blocklist file.
     *  @param blFile the blocklist file to count
     *  @return the number of non-comment, non-blank lines
     */
    private int getSize(File blFile) {
        if ( (!blFile.exists()) || (blFile.length() <= 0) ) return 0;
        int lines = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(blFile), "ISO-8859-1"));
            String s;
            while ((s = br.readLine()) != null) {
                if (s.length() > 0 && !s.startsWith("#")) {lines++;}
            }
        } catch (IOException ioe) {
            if (_log.shouldWarn()) {_log.warn("Error reading the blocklist file", ioe);}
            return 0;
        } finally {
            if (br != null) {
                try {br.close();}
                catch (IOException ioe) {}
            }
        }
        return lines;
    }

    /**
     *  Merge and remove overlapping entries from a sorted list.
     *  Caller must re-sort if return code is > 0.
     *  @param blist the sorted blocklist array
     *  @param count the number of valid entries in the array
     *  @return the number of overlapping entries removed
     */
    private int removeOverlap(long blist[], int count) {
        if (count <= 0) {return 0;}
        int lines = 0;
        for (int i = 0; i < count - 1; ) {
            int removed = 0;
            int to = getTo(blist[i]);
            for (int next = i + 1; next < count; next++) {
                if (to < getFrom(blist[next])) {break;}
                if (_log.shouldInfo()) {
                    _log.info("Combining entries " + toStr(blist[i]) + " and " + toStr(blist[next]));
                }
                int nextTo = getTo(blist[next]);
                if (nextTo > to) {store(getFrom(blist[i]), nextTo, blist, i);} // else entry next is totally inside entry i
                blist[next] = Long.MAX_VALUE; // to be removed with another sort
                lines++;
                removed++;
            }
            i += removed + 1;
        }
        return lines;
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     */
    public void add(String ip) {
        if (!_haveIPv6 && ip.indexOf(':') >= 0) {return;}
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) {return;}
        add(pib, null);
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     * @param source for logging only, may be null
     * @since 0.9.57
     */
    public void add(String ip, String source) {
        if (!_haveIPv6 && ip.indexOf(':') >= 0) {return;}
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) {return;}
        add(pib, source);
    }

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     */
    public void add(byte ip[]) {add(ip, null);}

    /**
     * Maintain a simple in-memory single-IP blocklist
     * This is used for new additions, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     * @param source for logging only, may be null
     * @since 0.9.57
     */
    public void add(byte ip[], String source) {
        boolean rv;
        if (ip.length == 4) {
            // don't ever block ourselves
            String us = _context.getProperty(UDPTransport.PROP_IP);
            if (us != null) {
                byte[] usb = Addresses.getIP(us);
                if (usb != null && DataHelper.eq(usb, ip)) {
                    if (_log.shouldWarn()) {_log.warn("Not adding our own IP " + us, new Exception());}
                    return;
                }
            }
            rv = add(toInt(ip));
            if (rv) {_context.commSystem().removeExemption(Addresses.toString(ip));}
        } else if (ip.length == 16) {
            if (!_haveIPv6) {return;}
            String us = _context.getProperty(UDPTransport.PROP_IPV6);
            if (us != null) { // don't ever block ourselves
                byte[] usb = Addresses.getIP(us);
                if (usb != null && DataHelper.eq(usb, ip)) {
                    if (_log.shouldWarn()) {_log.warn("Not adding our own IP " + us, new Exception());}
                    return;
                }
            }
            rv = add(new BigInteger(1, ip));
            if (rv) {_context.commSystem().removeExemption(Addresses.toCanonicalString(ip));}
        } else {return;}

        if (rv) {
            // lower log level at startup when initializing from blocklist files
            if (_log.shouldInfo()) {
                if (source == null) {
                    _log.info("Banning " + Addresses.toString(ip) + " for duration of session -> Blocklist entry");
                } else {
                    _log.info("Banning " + Addresses.toString(ip) + " for duration of session -> " + source);
                }
            }
        }
    }

    /**
     * Remove from the in-memory single-IP blocklist.
     * This is only works to undo add()s, NOT for the main list
     * of IP ranges read in from the file.
     *
     * @param ip IPv4 or IPv6
     * @since 0.9.28
     */
    public void remove(byte ip[]) {
        if (ip.length == 4) {remove(toInt(ip));}
        else if (ip.length == 16) {
            if (!_haveIPv6) {return;}
            remove(new BigInteger(1, ip));
        }
    }

    /**
     * @return true if it was NOT previously on the list
     */
    private boolean add(int ip) {
        // save space, don't put in both
        if (isPermanentlyBlocklisted(ip)) {return false;}
        Integer iip = Integer.valueOf(ip);
        synchronized(_singleIPBlocklist) {return _singleIPBlocklist.put(iip, DUMMY) == null;}
    }

    /**
     * @since 0.9.28
     */
    private void remove(int ip) {
        Integer iip = Integer.valueOf(ip);
        synchronized(_singleIPBlocklist) {_singleIPBlocklist.remove(iip);}
        _tempIPBlocklist.remove(iip);
    }

    /**
     *  Add a temporary IP block with expiration time.
     *  For use by transports to block abusive IPs.
     *
     *  @param ip IPv4 address
     *  @param durationMs duration in milliseconds (e.g. 8*60*60*1000 for 8 hours)
     *  @param source for logging, may be null
     *  @since 0.9.68+
     */
    public void addTemporary(byte ip[], long durationMs, String source) {
        if (ip.length != 4) {return;}
        int ipInt = toInt(ip);
        long expireOn = _context.clock().now() + durationMs;
        Integer iip = Integer.valueOf(ipInt);
        synchronized(_tempIPBlocklist) {
            Long existing = _tempIPBlocklist.get(iip);
            if (existing != null && existing > expireOn) {
                return; // Already blocked longer
            }
            _tempIPBlocklist.put(iip, expireOn);
        }
        if (_log.shouldInfo()) {
            _log.info("Temporarily banning " + Addresses.toString(ip) + " for " + (durationMs / 3600000) +
                      " hours -> " + (source != null ? source : "Abuse"));
        }
    }

    /**
     *  Check if IP is temporarily blocked.
     *
     *  @param ip IPv4 address
     *  @return true if blocked
     *  @since 0.9.68+
     */
    public boolean isTemporaryBlocklisted(byte ip[]) {
        if (ip.length != 4) {return false;}
        int ipInt = toInt(ip);
        Integer iip = Integer.valueOf(ipInt);
        synchronized(_tempIPBlocklist) {
            Long expireOn = _tempIPBlocklist.get(iip);
            if (expireOn == null) {return false;}
            if (_context.clock().now() > expireOn) {
                _tempIPBlocklist.remove(iip);
                return false;
            }
            return true;
        }
    }

    private boolean isOnSingleList(int ip) {
        Integer iip = Integer.valueOf(ip);
        synchronized(_singleIPBlocklist) {return _singleIPBlocklist.get(iip) != null;}
    }

    /**
     * @param ip IPv6 non-negative
     * @return true if it was NOT previously on the list
     * @since IPv6
     */
    private boolean add(BigInteger ip) {
        if (_singleIPv6Blocklist != null) {
            synchronized(_singleIPv6Blocklist) {return _singleIPv6Blocklist.put(ip, DUMMY) == null;}
        }
        return false;
    }

    /**
     * @param ip IPv6 non-negative
     * @since 0.9.28
     */
    private void remove(BigInteger ip) {
        if (_singleIPv6Blocklist != null) {
            synchronized(_singleIPv6Blocklist) {_singleIPv6Blocklist.remove(ip);}
        }
    }

    /**
     * @param ip IPv6 non-negative
     * @since IPv6
     */
    private boolean isOnSingleList(BigInteger ip) {
        if (_singleIPv6Blocklist != null) {
            synchronized(_singleIPv6Blocklist) {return _singleIPv6Blocklist.get(ip) != null;}
        }
        return false;
    }

    /**
     * Will not contain duplicates.
     */
    private List<byte[]> getAddresses(Hash peer) {
        RouterInfo pinfo = _context.netDb().lookupRouterInfoLocally(peer);
        if (pinfo == null) {return Collections.emptyList();}
        return getAddresses(pinfo);
    }

    /**
     * Will not contain duplicates.
     * @since 0.9.29
     */
    private List<byte[]> getAddresses(RouterInfo pinfo) {
        List<byte[]> rv = new ArrayList<byte[]>(4);
        for (RouterAddress pa : pinfo.getAddresses()) { // for each peer address
            byte[] pib = pa.getIP();
            if (pib == null) continue;
            if (!_haveIPv6 && pib.length == 16) {continue;}
            // O(n**2)
            boolean dup = false;
            for (int i = 0; i < rv.size(); i++) {
                if (DataHelper.eq(rv.get(i), pib)) {dup = true; break;}
            }
            if (!dup) {rv.add(pib);}
         }
         return rv;
    }

    /**
     *  Check if a peer is blocklisted by IP address.
     *  If so, and it isn't banlisted, banlist it forever or for the configured override period.
     *  @param peer the router hash to check
     *  @return true if the peer's IP is in the blocklist
     *  @since 0.9.29
     */
    public boolean isBlocklisted(Hash peer) {
        List<byte[]> ips = getAddresses(peer);
        if (ips.isEmpty()) {return false;}
        for (byte[] ip : ips) {
            if (isBlocklisted(ip)) {
                if (!_context.banlist().isBanlisted(peer)) {banlist(peer, ip);} // nice knowing you...
                return true;
            }
        }
        return false;
    }

    /**
     *  Check if a peer is blocklisted by IP address.
     *  If so, and it isn't banlisted, banlist it forever or for the configured override period.
     *  @param pinfo the router info to check
     *  @return true if the peer's IP is in the blocklist
     *  @since 0.9.29
     */
    public boolean isBlocklisted(RouterInfo pinfo) {
        List<byte[]> ips = getAddresses(pinfo);
        if (ips.isEmpty()) {return false;}
        for (byte[] ip : ips) {
            if (isBlocklisted(ip)) {
                Hash peer = pinfo.getHash();
                if (!_context.banlist().isBanlisted(peer)) {banlist(peer, ip);} // nice knowing you...
                return true;
            }
        }
        return false;
    }

    /**
     *  Check if an IP address is blocklisted.
     *  Calling this externally won't banlist the peer, this is just an IP check.
     *  @param ip the IP address as a string (IPv4 or IPv6)
     *  @return true if the IP is blocklisted
     */
    public boolean isBlocklisted(String ip) {
        if (!_haveIPv6 && ip.indexOf(':') >= 0) {return false;}
        byte[] pib = Addresses.getIPOnly(ip);
        if (pib == null) {return false;}
        return isBlocklisted(pib);
    }

    /**
     *  Check if an IP address is blocklisted.
     *  Calling this externally won't banlist the peer, this is just an IP check.
     *  @param ip the IP address as a byte array (IPv4 or IPv6)
     *  @return true if the IP is blocklisted
     */
    public boolean isBlocklisted(byte ip[]) {
        if (ip.length == 4) {
            if (isBlocklisted(toInt(ip))) {return true;}
            return isTemporaryBlocklisted(ip);
        }
        if (ip.length == 16) {
            if (!_haveIPv6) {return false;}
            return isOnSingleList(new BigInteger(1, ip));
        }
        return false;
    }

    /**
     * First check the single-IP list. Then do a binary search through the in-memory range list which
     * is a sorted array of longs. The array is sorted in signed order, but we don't care.
     * Each long is ((from << 32) | to)
     */
    private synchronized boolean isBlocklisted(int ip) {
        if (isOnSingleList(ip)) {return true;}
        if (_countryBlocklist != null) {
            if (isPermanentlyBlocklisted(ip, _countryBlocklist, _countryBlocklistSize)) {return true;}
        }
        return isPermanentlyBlocklisted(ip);
    }

    /**
     *  Check if an IP is permanently blocklisted using binary search.
     *  Public for console only, not a public API.
     *  @param ip the IPv4 address as an integer
     *  @return true if the IP is in the permanent blocklist
     *  @since 0.9.45 split out from above, public since 0.9.48 for console
     */
    public boolean isPermanentlyBlocklisted(int ip) {
        return isPermanentlyBlocklisted(ip, _blocklist, _blocklistSize);
    }

    /**
     * Do a binary search through the in-memory range list which is a sorted array of longs.
     * The array is sorted in signed order, but we don't care.
     * Each long is ((from << 32) | to)
     *
     * @since 0.9.48 split out from above
     */
    private static boolean isPermanentlyBlocklisted(int ip, long[] blocklist, int blocklistSize) {
        int hi = blocklistSize - 1;
        if (hi <= 0) {return false;}
        int lo = 0;
        int cur = hi / 2;

        while  (!match(ip, blocklist[cur])) {
            if (isHigher(ip, blocklist[cur])) {lo = cur;}
            else {hi = cur;}
            if (hi - lo <= 1) { // make sure we get the last one
                if (lo == cur) {cur = hi;}
                else {cur = lo;}
                break;
            } else {cur = lo + ((hi - lo) / 2);}
        }
        return match(ip, blocklist[cur]);
    }

    /**
     *  Check if an IP is included in a compressed blocklist entry.
     *  @param ip the IP to check
     *  @param entry the compressed blocklist entry
     *  @return true if the IP is within the entry's range
     */
    private static boolean match(int ip, long entry) {
        if (getFrom(entry) > ip) {return false;}
        return (ip <= getTo(entry));
    }

    /**
     *  Check if an IP is higher than the entry's starting IP.
     *  @param ip the IP to check
     *  @param entry the compressed blocklist entry
     *  @return true if the IP is higher than the entry's starting IP
     */
    private static boolean isHigher(int ip, long entry) {return ip > getFrom(entry);}

    /* Methods to get and store the from/to values in the array */

    /**
     *  Extract the starting IP from a compressed blocklist entry.
     *  Public for console only, not a public API.
     *  @param entry the compressed blocklist entry
     *  @return the starting IP as an integer
     *  @since public since 0.9.48
     */
    public static int getFrom(long entry) {return (int) ((entry >> 32) & 0xffffffff);}

    /**
     *  Extract the ending IP from a compressed blocklist entry.
     *  Public for console only, not a public API.
     *  @param entry the compressed blocklist entry
     *  @return the ending IP as an integer
     *  @since public since 0.9.48
     */
    public static int getTo(long entry) {return (int) (entry & 0xffffffff);}

    /**
     * The in-memory blocklist is an array of longs, with the format ((from IP) << 32) | (to IP)
     * The XOR is so the signed sort is in normal (unsigned) order.
     *
     * So the size is (cough) almost 2MB for the 240,000 line splist.txt.
     *
     */
    private static long toEntry(byte ip1[], byte ip2[]) {
        long entry = 0;
        for (int i = 0; i < 4; i++) {entry |= ((long) (ip2[i] & 0xff)) << ((3-i)*8);}
        for (int i = 0; i < 4; i++) {entry |= ((long) (ip1[i] & 0xff)) << (32 + ((3-i)*8));}
        return entry;
    }

    /**
     *  Store an IPv4 range as a compressed entry in the blocklist array.
     *  @param ip1 the starting IP as a byte array
     *  @param ip2 the ending IP as a byte array
     *  @param blocklist the blocklist array to store in
     *  @param idx the index to store at
     */
    private static void store(byte ip1[], byte ip2[], long[] blocklist, int idx) {
        blocklist[idx] = toEntry(ip1, ip2);
    }

    /**
     *  Store an IPv4 range as a compressed entry in the blocklist array.
     *  @param ip1 the starting IP as an integer
     *  @param ip2 the ending IP as an integer
     *  @param blocklist the blocklist array to store in
     *  @param idx the index to store at
     */
    private static void store(int ip1, int ip2, long[] blocklist, int idx) {
        long entry = ((long) ip1) << 32;
        entry |= ((long)ip2) & 0xffffffff;
        blocklist[idx] = entry;
    }

    /**
     *  Convert an IPv4 address from byte array to integer.
     *  @param ip the IP address as a byte array
     *  @return the IP address as an integer
     */
    private static int toInt(byte ip[]) {
        int rv = 0;
        for (int i = 0; i < 4; i++) {rv |= (ip[i] & 0xff) << ((3-i)*8);}
        return rv;
    }

    /**
     *  Convert a compressed blocklist entry to a string representation.
     *  @param entry the compressed blocklist entry
     *  @return the string representation (e.g., "127.0.0.1-127.0.0.255")
     */
    private static String toStr(long entry) {
        StringBuilder buf = new StringBuilder(32);
        for (int i = 7; i >= 0; i--) {
            buf.append((entry >> (8*i)) & 0xff);
            if (i == 4) {buf.append('-');}
            else if (i > 0) {buf.append('.');}
        }
        return buf.toString();
    }

    /**
     *  Convert an IPv4 address to a string representation.
     *  Public for console only, not a public API.
     *  @param ip the IPv4 address as an integer
     *  @return the string representation (e.g., "192.168.1.1")
     *  @since public since 0.9.48
     */
    public static String toStr(int ip) {
        StringBuilder buf = new StringBuilder(16);
        for (int i = 3; i >= 0; i--) {
            buf.append((ip >> (8*i)) & 0xff);
            if (i > 0) {buf.append('.');}
        }
        return buf.toString();
    }

    /**
     * We don't keep the comment field in-memory, so we have to go back out to the file to find it.
     *
     * Put this in a job because we're looking for the actual line in the blocklist file, this could take a while.
     *
     */
    private void banlist(Hash peer, byte[] ip) {
        if (!_haveIPv6 && ip.length == 16) {return;} // Don't bother unless we have IPv6
        String sip = Addresses.toString(ip); // Temporary reason, until the job finishes
        String reason = " <b>➜</b> " + _x("Blocklist") + ": " + sip;
        if (sip != null && sip.startsWith("127.") || "0:0:0:0:0:0:0:1".equals(sip) ||
            sip.startsWith("192.168.") || sip.startsWith("10.") ||
            (ip != null && ip.length == 4 && (ip[0] * 0xff) == 172 && ip[1] >= 16 && ip[1] <= 31)) {
            // i2pd bug, possibly at startup, don't ban forever
            _context.banlist().banlistRouter(peer, reason, sip, null, _context.clock().now() + Banlist.BANLIST_DURATION_PRIVATE);
            return;
        }
        banlistRouter(peer, reason, sip);
        if (!_context.getBooleanPropertyDefaultTrue(PROP_BLOCKLIST_DETAIL)) {return;}
        boolean shouldRunJob;
        int number;
        synchronized (_inProcess) {
            number = _inProcess.size();
            shouldRunJob = _inProcess.add(peer);
        }
        if (!shouldRunJob) {return;}
        // Get the IPs now because it won't be in the netdb by the time the job runs
        Job job = new BanlistJob(peer, getAddresses(peer));
        if (number > 0) {job.getTiming().setStartAfter(_context.clock().now() + (30*1000l * number));}
        _context.jobQueue().addJob(job);
    }

    private class BanlistJob extends JobImpl {
        private final Hash _peer;
        private final List<byte[]> _ips;
        public BanlistJob (Hash p, List<byte[]> ips) {
            super(_context);
            _peer = p;
            _ips = ips;
        }
        public String getName() {return "Enforce Blocklist IP Ban";}
        public void runJob() {
            banlistRouter(_peer, _ips, expireInterval());
            synchronized (_inProcess) {_inProcess.remove(_peer);}
        }
    }

    /**
     * Look up the original record so we can record the reason in the banlist.
     * That's the only reason to do this.
     * Only synchronize to cut down on the I/O load.
     * Additional jobs can wait.
     * Although could this clog up the job queue runners? Yes.
     * So we also stagger these jobs.
     *
     */
    private void banlistRouter( Hash peer, String reason, String reasonCode, long duration) {
        if (duration > 0) {
            _context.banlist().banlistRouter(peer, reason, reasonCode, null, System.currentTimeMillis() + expireInterval());
        } else {
            _context.banlist().banlistRouterForever(peer, reason, reasonCode);
        }
    }

    private synchronized void banlistRouter(Hash peer, List<byte[]> ips, long duration) {
        // This only checks one file for now, pick the best one
        File blFile = null; // user specified
        String file = _context.getProperty(PROP_BLOCKLIST_FILE);
        if (file != null) {
            blFile = new File(file);
            if (!blFile.isAbsolute()) {blFile = new File(_context.getConfigDir(), file);}
            if (!blFile.exists()) {blFile = null;}
        }
        if (blFile == null) {blFile = new File(_context.getBaseDir(), BLOCKLIST_FILE_DEFAULT);}  // install dir

        if ((!blFile.exists()) || blFile.length() <= 0) {
            // just ban it and be done
            if (_log.shouldWarn()) {
                _log.warn("Banning [" + peer.toBase64().substring(0,6) + "] for duration of session -> Blocklist entry");
            }
            banlistRouter(peer, " <b>➜</b> Banned", "Banned", expireInterval());
            return;
        }

        // Look through the file for each address to find which one was the cause
        for (Iterator<byte[]> iter = ips.iterator(); iter.hasNext();) {
            byte ip[] = iter.next();
            int ipint = toInt(ip);
            String sip = Addresses.toString(ip);
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(blFile), "UTF-8"));
                String buf = null;
                // Assume the file is unsorted, so go through the whole thing
                while ((buf = br.readLine()) != null) {
                    Entry e = parse(buf, false);
                    if (e == null || e.peer != null) {continue;}
                    if (match(ipint, toEntry(e.ip1, e.ip2))) {
                        try {br.close();} catch (IOException ioe) {}
                        String reason = " <b>➜</b> " + _x("Blocklist") + ": " + sip;
                        if (_log.shouldWarn()) {
                            _log.warn("Banning [" + peer.toBase64().substring(0,6) + "] for duration of session -> Blocklist entry");
                        }
                        banlistRouter(peer, reason, buf.toString(), expireInterval());
                        return;
                    }
                }
            } catch (IOException ioe) {
                if (_log.shouldWarn())
                    _log.warn("Error reading the blocklist file", ioe);
            } finally {
                if (br != null) try {br.close();} catch (IOException ioe) {}
            }
        }
        // We already banlisted in banlist(peer), that's good enough
    }

    /**
     *  Single IPs blocked until restart. Unsorted.
     *
     *  Public for console only, not a public API
     *  As of 0.9.57, will not contain IPs permanently banned,
     *  except for ones banned permanently after being added to the transient list.
     *
     *  @return a copy, unsorted
     *  @since 0.9.48
     */
    public List<Integer> getTransientIPv4Blocks() {
        synchronized(_singleIPBlocklist) {
            return new ArrayList<Integer>(_singleIPBlocklist.keySet());
        }
    }

    /**
     *  Single IPs blocked until restart. Unsorted.
     *
     *  Public for console only, not a public API
     *
     *  @return a copy, unsorted
     *  @since 0.9.48
     */
    public List<BigInteger> getTransientIPv6Blocks() {
        if (!_haveIPv6) {return Collections.<BigInteger>emptyList();}
        if (_singleIPv6Blocklist != null) {
            synchronized(_singleIPv6Blocklist) {return new ArrayList<BigInteger>(_singleIPv6Blocklist.keySet());}
        }
        return Collections.<BigInteger>emptyList();
    }

    /**
     *  IP ranges blocked until restart. Sorted, but as signed longs, so 128-255 are first
     *
     *  Public for console only, not a public API
     *
     *  @param max maximum entries to return
     *  @return a copy, sorted
     *  @since 0.9.48
     */
    public synchronized long[] getPermanentBlocks(int max) {
        long[] rv;
        if (_blocklistSize <= max) {
            rv = new long[_blocklistSize];
            System.arraycopy(_blocklist, 0, rv, 0, _blocklistSize);
        } else {
            // skip ahead to the positive entries
            int i = 0;
            for (; i < _blocklistSize; i++) {
                int from = Blocklist.getFrom(_blocklist[i]);
                if (from >= 0) {break;}
            }
            int sz = Math.min(_blocklistSize - i, max);
            rv = new long[sz];
            System.arraycopy(_blocklist, i, rv, 0, sz);
        }
        return rv;
    }

    /**
     *  Get the size of the permanent blocklist.
     *  Public for console only, not a public API.
     *  @return the number of entries in the permanent blocklist
     *  @since 0.9.48
     */
    public synchronized int getBlocklistSize() {return _blocklistSize;}

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers. It does not translate!
     *  @return s
     */
    private static final String _x(String s) {return s;}

}
