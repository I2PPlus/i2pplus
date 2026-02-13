package net.i2p.router.web;

import static net.i2p.router.web.GraphConstants.*;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.FileSuffixFilter;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import org.rrd4j.core.RrdBackendFactory;
import org.rrd4j.core.RrdNioBackendFactory;

/**
 *  A thread started by RouterConsoleRunner that checks the configuration for
 *  stats to be tracked via jrobin, and adds or deletes RRDs as necessary.
 *
 *  This also contains methods to generate xml, png or svg image output.
 *  The rendering for graphs is in GraphRenderer.
 *
 *  To control memory, the number of simultaneous renderings is limited.
 *
 *  @since 0.6.1.13
 */
public class GraphGenerator implements Runnable, ClientApp {
    private final RouterContext _context;
    private final Log _log;
    /** list of GraphListener instances */
    private final List<GraphListener> _listeners;
    private static int cores = SystemVersion.getCores();
    private static long maxMem = SystemVersion.getMaxMemory();
    /**
     *  Scale concurrent graph rendering based on available memory.
     *  Large graphs with high period counts consume significant memory,
     *  so we limit concurrency on systems with less RAM to prevent OOM.
     *
     *  Memory thresholds:
     *  - < 512MB: 4 concurrent (low memory systems)
     *  - 512MB - 1GB: 6 concurrent
     *  - 1GB - 2GB: 8 concurrent
     *  - 2GB - 4GB: 10 concurrent
     *  - 4GB+: 12 concurrent (default)
     */
    private static final int MAX_CONCURRENT_PNG;
    static {
        if (SystemVersion.isARM()) {
            MAX_CONCURRENT_PNG = Math.max(2, cores / 2);
        } else if (maxMem < 512*1024*1024L) {
            MAX_CONCURRENT_PNG = 4;
        } else if (maxMem < 1024*1024*1024L) {
            MAX_CONCURRENT_PNG = 6;
        } else if (maxMem < 2048*1024*1024L) {
            MAX_CONCURRENT_PNG = 8;
        } else if (maxMem < 4096*1024*1024L) {
            MAX_CONCURRENT_PNG = 10;
        } else {
            MAX_CONCURRENT_PNG = Math.max(12, cores);
        }
    }
    private final Semaphore _sem;
    private volatile boolean _isRunning;
    private ScheduledExecutorService _scheduler;
    private static final String NAME = "GraphGenerator";

    public GraphGenerator(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(getClass());
        _listeners = new CopyOnWriteArrayList<GraphListener>();
        _sem = new Semaphore(MAX_CONCURRENT_PNG, true);
        _context.addShutdownTask(new Shutdown());
    }

    /**
     * @return null if disabled
     */
    public static GraphGenerator instance() {return instance(I2PAppContext.getGlobalContext());}

    /**
     * @return null if disabled
     * @since 0.9.38
     */
    public static GraphGenerator instance(I2PAppContext ctx) {
        ClientApp app = ctx.clientAppManager().getRegisteredApp(NAME);
        return (app != null) ? (GraphGenerator) app : null;
    }

    public void run() {
        // JRobin 1.5.9 crashes these JVMs
        if (SystemVersion.isApache() /* Harmony */ || SystemVersion.isGNU()) /* JamVM or gij */ {
            _log.logAlways(Log.WARN, "Graphing not supported with this JVM: " +
                                     System.getProperty("java.vendor") + ' ' +
                                     System.getProperty("java.version") + " (" +
                                     System.getProperty("java.runtime.name") + ' ' +
                                     System.getProperty("java.runtime.version") + ')');
            return;
        }
        _isRunning = true;
        boolean isPersistent = _context.getBooleanPropertyDefaultTrue(GraphListener.PROP_PERSISTENT);
        int syncThreads;
        if (isPersistent) {
            String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
            String[] rates = DataHelper.split(spec, ",");
            syncThreads = 1;
            // delete files for unconfigured rates
            Set<String> configured = new HashSet<String>(rates.length);
            for (String r : rates) {configured.add(GraphListener.createName(_context, r));}
            File rrdDir = new File(_context.getRouterDir(), GraphListener.RRD_DIR);
            FileFilter filter = new FileSuffixFilter(GraphListener.RRD_PREFIX, GraphListener.RRD_SUFFIX);
            File[] files = rrdDir.listFiles(filter);
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    String name = f.getName();
                    String hash = name.substring(GraphListener.RRD_PREFIX.length(), name.length() - GraphListener.RRD_SUFFIX.length());
                    if (!configured.contains(hash)) {f.delete();}
                }
            }
        } else {
            syncThreads = 0;
            deleteOldRRDs();
        }
        RrdNioBackendFactory.setSyncPoolSize(syncThreads);
        _context.clientAppManager().register(this);
        _scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StatWriter");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        final String[] specsHolder = {""};
        try {
            _scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!_isRunning || !_context.router().isAlive()) {
                        stop();
                        return;
                    }
                    specsHolder[0] = adjustDatabases(specsHolder[0]);
                } catch (Exception e) {
                    _log.error("Failed to sync RRD4J stats to disk", e);
                }
            }, 0, 90, TimeUnit.SECONDS);
        } catch (Exception e) {
            _log.error("Failed to schedule RRD4J sync task", e);
            // Clean up the scheduler to prevent thread leak
            _scheduler.shutdown();
            try {
                if (!_scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    _scheduler.shutdownNow();
                }
            } catch (InterruptedException ie) {
                _scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            _scheduler = null;
        }
    }

    public synchronized void stop() {
        _isRunning = false;
        _context.clientAppManager().unregister(this);
        if (_scheduler != null) {
            _scheduler.shutdown(); // Disable new tasks, let running finish
            try {
                if (!_scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    _scheduler.shutdownNow(); // Force if not terminated in time
                    _scheduler.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                _scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            _scheduler = null;
        }
    }

    /** @since 0.9.38 */
    public static boolean isDisabled(I2PAppContext ctx) {
        return ctx.clientAppManager().getRegisteredApp(NAME) == null;
    }

    /**
     * Disable graph generation until restart
     * See GraphRenderer.render()
     * @since 0.9.6
     */
    static void setDisabled(I2PAppContext ctx) {
        GraphGenerator ss = instance(ctx);
        if (ss != null) {ss.setDisabled();}
    }

    /**
     * Disable graph generation until restart
     * See GraphRenderer.render()
     * @since 0.9.38
     */
    synchronized void setDisabled() {
        if (_isRunning) {
            _isRunning = false;
            stop();
        }
    }

    /////// ClientApp methods

    /**
     * Does nothing, we aren't tracked
     * @since 0.9.38
     */
    public void startup() {}

    /**
     * Does nothing, we aren't tracked
     * @since 0.9.38
     */
    public void shutdown(String[] args) {}

    /** @since 0.9.38 */
    public ClientAppState getState() {return ClientAppState.RUNNING;}

    /** @since 0.9.38 */
    public String getName() {return NAME;}

    /** @since 0.9.38 */
    public String getDisplayName() {return "I2P+ Graph Generator";}

    /////// End ClientApp methods

    /**
     *  List of GraphListener instances
     *  @since public since 0.9.33, was package private
     */
    public List<GraphListener> getListeners() { return _listeners; }

    /**  @since public since 0.9.33, was package private */
    public static final String DEFAULT_DATABASES = "bw.sendRate.60000" +
                                                   ",bw.recvRate.60000" +
                                                   ",jobQueue.jobLag.60000" +
                                                   ",router.activePeers.60000" +
                                                   ",router.cpuLoad.60000" +
                                                   ",router.memoryUsed.60000" +
                                                   ",tunnel.participatingTunnels.60000" +
                                                   ",tunnel.tunnelBuildSuccessAvg.60000" +
                                                   ",tunnel.testSuccessTime.60000";

    /** @since 0.9.62+ */
    public int countGraphs() {return _listeners.size();}

    private String adjustDatabases(String oldSpecs) {
        String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
        if (((spec == null) && (oldSpecs == null)) ||
            ((spec != null) && (oldSpecs != null) && (oldSpecs.equals(spec)))) {
            return oldSpecs;
        }

        Set<Rate> old = parseSpecs(oldSpecs);
        Set<Rate> newSpecs = parseSpecs(spec);

        // remove old ones
        for (Rate r : old) {
            if (!newSpecs.contains(r)) {removeDb(r);}
        }
        // add new ones
        StringBuilder buf = new StringBuilder();
        boolean comma = false;
        for (Rate r : newSpecs) {
            if (!old.contains(r)) {addDb(r);}
            if (comma) {buf.append(',');}
            else {comma = true;}
            buf.append(r.getRateStat().getName()).append(".").append(r.getPeriod());
        }
        return buf.toString();
    }

    private void removeDb(Rate r) {
        for (GraphListener lsnr : _listeners) {
            if (lsnr.getRate().equals(r)) {
                _listeners.remove(lsnr); // no iter.remove() in COWAL
                lsnr.stopListening();
                return;
            }
        }
    }

    private void addDb(Rate r) {
        GraphListener lsnr = new GraphListener(r);
        boolean success = lsnr.startListening();
        if (success) {_listeners.add(lsnr);}
        else {_log.error("Failed to add RRD for rate " + r.getRateStat().getName() + '.' + r.getPeriod());}
    }

    public boolean renderPng(Rate rate, OutputStream out) throws IOException {
        return renderPng(rate, out, DEFAULT_X, DEFAULT_Y, false, false, false, false, -1, 0, true);
    }

    /**
     *  This does the single data graphs.
     *  For the two-data bandwidth graph see renderRatePng().
     *  Synchronized to conserve memory.
     *
     *  @param end number of periods before now
     *  @return success
     */
    public boolean renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                        boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                        int end, boolean showCredit) throws IOException {
        try {
            try {_sem.acquire();}
            catch (InterruptedException ie) {}
            try {
                return locked_renderPng(rate, out, width, height, hideLegend, hideGrid, hideTitle, showEvents,
                                        periodCount, end, showCredit);
            } catch (NoClassDefFoundError ncdfe) {
                setDisabled();
                String s = "Error rendering - disabling graph generation.";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(ncdfe);
                throw ioe;
            }
        } finally {_sem.release();}
    }

    /**
     *  @param end number of periods before now
     */
    private boolean locked_renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend,
                                      boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount,
                                      int end, boolean showCredit) throws IOException {
        if (width > MAX_X) {width = MAX_X;}
        else if (width <= 0) {width = DEFAULT_X;}
        if (height > MAX_Y) {height = MAX_Y;}
        else if (height <= 0) {height = DEFAULT_Y;}
        if (end < 0) {end = 0;}
        for (GraphListener lsnr : _listeners) {
            if (lsnr.getRate().equals(rate)) {
                lsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit);
                return true;
            }
        }
        return false;
    }

    /** @deprecated unused */
    @Deprecated
    public boolean renderPng(OutputStream out, String templateFilename) throws IOException {
        GraphRenderer.render(_context, out, templateFilename);
        return true;
    }

    public boolean getXML(Rate rate, OutputStream out) throws IOException {
        try {
            try {_sem.acquire();}
            catch (InterruptedException ie) {}
            return locked_getXML(rate, out);
        } finally {_sem.release();}
    }

    private boolean locked_getXML(Rate rate, OutputStream out) throws IOException {
        for (GraphListener lsnr : _listeners) {
            if (lsnr.getRate().equals(rate)) {
                lsnr.getData().exportXml(out);
                out.write(DataHelper.getUTF8("<!-- Rate: " + lsnr.getRate().getRateStat().getName() + " for period " + lsnr.getRate().getPeriod() + " -->\n"));
                out.write(DataHelper.getUTF8("<!-- Average data source name: " + lsnr.getName() + " event count data source name: " + lsnr.getEventName() + " -->\n"));
                return true;
            }
        }
        return false;
    }

    /**
     *  This does the two-data bandwidth graph only.
     *  For all other graphs see renderPng() above.
     *  Synchronized to conserve memory.
     *
     *  @param end number of periods before now
     *  @return success
     */
    public boolean renderRatePng(OutputStream out, int width, int height, boolean hideLegend,
                                 boolean hideGrid, boolean hideTitle, boolean showEvents,
                                 int periodCount, int end, boolean showCredit) throws IOException {
        try {
            try {_sem.acquire();}
            catch (InterruptedException ie) {}
            try {return locked_renderRatePng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, end, showCredit);}
            catch (NoClassDefFoundError ncdfe) {
                setDisabled();
                String s = "Error rendering - disabling graph generation.";
                _log.logAlways(Log.WARN, s);
                IOException ioe = new IOException(s);
                ioe.initCause(ncdfe);
                throw ioe;
            }
        } finally {_sem.release();}
    }

    private boolean locked_renderRatePng(OutputStream out, int width, int height, boolean hideLegend,
                                         boolean hideGrid, boolean hideTitle, boolean showEvents,
                                         int periodCount, int end, boolean showCredit) throws IOException {

        // go to some trouble to see if we have the data for the combined bw graph
        GraphListener txLsnr = null;
        GraphListener rxLsnr = null;
        for (GraphListener lsnr : getListeners()) {
            String title = lsnr.getRate().getRateStat().getName();
            if (title.equals("bw.sendRate")) {txLsnr = lsnr;}
            else if (title.equals("bw.recvRate")) {rxLsnr = lsnr;}
        }
        if (txLsnr == null || rxLsnr == null) {throw new IOException("No rates for combined bandwidth graph");}

        if (width > MAX_X) {width = MAX_X;}
        else if (width <= 0) {width = DEFAULT_X;}
        if (height > MAX_Y) {height = MAX_Y;}
        else if (height <= 0) {height = DEFAULT_Y;}
        if (hideTitle) {
            txLsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                             end, showCredit, rxLsnr, null);
        } else {
            txLsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount,
                             end, showCredit, rxLsnr, "[" + _t("Router") + "] " + _t("Bandwidth usage").replace("usage", "Usage"));
        }
        return true;
    }

    /**
     * @param specs statName.period,statName.period,statName.period
     * @return list of Rate objects
     * @since public since 0.9.33, was package private
     */
    public Set<Rate> parseSpecs(String specs) {
        if (specs == null) {return Collections.emptySet();}
        StringTokenizer tok = new StringTokenizer(specs, ",");
        Set<Rate> rv = new HashSet<Rate>();
        while (tok.hasMoreTokens()) {
            String spec = tok.nextToken();
            int split = spec.lastIndexOf('.');
            if ((split <= 0) || (split + 1 >= spec.length())) {continue;}
            String name = spec.substring(0, split);
            String per = spec.substring(split+1);
            long period = -1;
            try {
                period = Long.parseLong(per);
                RateStat rs = _context.statManager().getRate(name);
                if (rs != null) {
                    Rate r = rs.getRate(period);
                    if (r != null) {rv.add(r);}
                }
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }

    /**
     *  Delete the old rrd dir if we are no longer persistent
     *  @since 0.8.7
     */
    private void deleteOldRRDs() {
        File rrdDir = new File(_context.getRouterDir(), GraphListener.RRD_DIR);
        FileUtil.rmdir(rrdDir, false);
    }

    private static final boolean IS_WIN = SystemVersion.isWindows();

    /** translate a string */
    private String _t(String s) {
        // The RRD font doesn't have zh chars, at least on my system
        // Works on 1.5.9 except on windows
        if (IS_WIN && "zh".equals(Messages.getLanguage(_context))) {return s;}
        return Messages.getString(s, _context);
    }

    /**
     *  Make sure any persistent RRDs are closed
     *  @since 0.8.7
     */
    private class Shutdown implements Runnable {
        public void run() {
            setDisabled();
            for (GraphListener lsnr : _listeners) {lsnr.stopListening();} // FIXME could cause exceptions if rendering?
            _listeners.clear();
            stop();
            // Stops the sync thread pool in NIO; noop if not persistent, we set num threads to zero in run() above
            try {RrdBackendFactory.getDefaultFactory().close();}
            catch (IOException ioe) {}
        }
    }

}