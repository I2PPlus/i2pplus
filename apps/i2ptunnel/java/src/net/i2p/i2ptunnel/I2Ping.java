/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import gnu.getopt.Getopt;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * I2P ping utility for CLI use.
 * <p>
 * Warning: Not stable API. Sample code for I2PTunnel CLI only.
 * Not for external use.
 */
public class I2Ping extends I2PTunnelClientBase {

    public static final String PROP_COMMAND = "command";
    private static final int PING_COUNT = 10;
    private static final int CPING_COUNT = 5;
    private static final int PING_TIMEOUT = 90 * 1000;
    private static final long PING_DISTANCE = 1000;
    private int MAX_SIMUL_PINGS = 16; // matches usage text
    private volatile boolean finished;

    private final Object simulLock = new Object();
    private int simulPings;
    private long lastPingTime;
    private boolean fromList = false;

    private static class PingResult {
        final boolean success;
        final long duration;

        PingResult(boolean success, long duration) {
            this.success = success;
            this.duration = duration;
        }
    }

    /**
     *  tunnel.getOptions must contain "command".
     *  @throws IllegalArgumentException if it doesn't
     */
    public I2Ping(Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(-1, ownDest, l, notifyThis, "I2Ping", tunnel);
        if (!tunnel.getClientOptions().containsKey(PROP_COMMAND)) {
            throw new IllegalArgumentException("Options does not contain " + PROP_COMMAND);
        }
    }

    /**
     *  Overrides super. No client ServerSocket is created.
     */
    @Override
    public void run() {
        try {
            verifySocketManager();
            l.log(" • Tunnels established for I2Ping client");
            notifyEvent("openBaseClientResult", "ok");
        } catch (Exception e) {
            l.log(" ✖ Failed to establish tunnels: " + e.getMessage());
            notifyEvent("openBaseClientResult", "error");
            close(false);
            return;
        }

        synchronized (this) {
            listenerReady = true;
            notifyAll();
        }

        try {
            runCommand(getTunnel().getClientOptions().getProperty(PROP_COMMAND));
        } catch (InterruptedException ex) {
            l.log(" ✖ I2Ping was interrupted during execution");
            _log.error("Pinger interrupted", ex);
        } catch (IOException ex) {
            _log.error("Pinger exception", ex);
        }
        finished = true;
        close(false);
    }

    public void runCommand(String cmd) throws InterruptedException, IOException {
        long timeout = PING_TIMEOUT;
        int count = PING_COUNT;
        boolean countPing = false;
        boolean reportTimes = true;
        String hostListFile = null;
        int localPort = 0;
        int remotePort = 0;
        boolean error = false;
        String[] argv = DataHelper.split(cmd, " ");
        Getopt g = new Getopt("ping", argv, "t:m:n:chl:f:p:");
        int c;
        while ((c = g.getopt()) != -1) {
            switch (c) {
                case 't':
                    timeout = Long.parseLong(g.getOptarg());
                    if (timeout < 100) {
                        timeout *= 1000;
                    }
                    break;
                case 'm':
                    MAX_SIMUL_PINGS = Integer.parseInt(g.getOptarg());
                    break;
                case 'n':
                    count = Integer.parseInt(g.getOptarg());
                    break;
                case 'c':
                    countPing = true;
                    count = CPING_COUNT;
                    break;
                case 'h':
                    fromList = true;
                    if (hostListFile != null) {
                        error = true;
                    } else {
                        hostListFile = "hosts.txt";
                    }
                    break;
                case 'l':
                    fromList = true;
                    if (hostListFile != null) {
                        error = true;
                    } else {
                        hostListFile = g.getOptarg();
                    }
                    break;
                case 'f':
                    localPort = Integer.parseInt(g.getOptarg());
                    break;
                case 'p':
                    remotePort = Integer.parseInt(g.getOptarg());
                    break;
                case '?':
                case ':':
                default:
                    error = true;
            }
        }

        int remaining = argv.length - g.getOptind();
        if (error || remaining > 1 || (remaining <= 0 && hostListFile == null) || (remaining > 0 && hostListFile != null)) {
            System.out.println(usage());
            return;
        }

        if (hostListFile != null) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(hostListFile), "UTF-8"));
                String line;
                List<PingHandler> pingHandlers = new ArrayList<PingHandler>();
                int i = 0;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("!")) {
                        continue;
                    }
                    if (line.indexOf('=') != -1) {
                        line = line.substring(0, line.indexOf('=')).trim();
                    }
                    if (line.isEmpty()) continue;
                    PingHandler ph = new PingHandler(line, count, localPort, remotePort, timeout, countPing, reportTimes);
                    ph.start();
                    pingHandlers.add(ph);
                    if (++i > 1) {
                        reportTimes = false;
                    }
                }
                for (Thread t : pingHandlers) {
                    t.join();
                }
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (IOException ignored) {}
                }
            }
            return;
        }

        String host = argv[g.getOptind()];
        Thread t = new PingHandler(host, count, localPort, remotePort, timeout, countPing, reportTimes);
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
        t.join();
    }

    public static String usage() {
        return
            "Usage:\n" +
            "  ping [opts] <b32|b64|host>   pings a single host\n" +
            "  ping [opts] -h               pings all hosts in hosts.txt in current directory\n" +
            "  ping [opts] -l <file>        pings a list of hosts in specified file\n\n" +
            "Options:\n" +
            "  -c           require 5 consecutive pongs to report success\n" +
            "  -m <value>   max concurrent pings (default 16)\n" +
            "  -n <value>   number of pings (default 10)\n" +
            "  -t <value>   timeout in milliseconds (default 8000)\n" +
            "  -f <value>   from (source) port\n" +
            "  -p <value>   to (destination) port";
    }

    @Override
    public boolean close(boolean forced) {
        if (!open) return true;
        super.close(forced);
        if (!forced && !finished) {
            l.log(" • There are still pings running!");
            return false;
        }
        l.log(" • I2Ping client closed");
        return true;
    }

    /**
     * Attempts to "ping" by opening an I2P socket to the destination.
     * Returns true if connection succeeds within timeout.
     */
    private PingResult ping(Destination dest, int fromPort, int toPort, long timeout) throws I2PException {
        long pingStart = System.currentTimeMillis();
        try {
            boolean success = sockMgr.ping(dest, fromPort, toPort, timeout);
            long duration = System.currentTimeMillis() - pingStart;
            return new PingResult(success, duration);
        } catch (IllegalArgumentException e) {
            _log.error("Invalid ping parameters", e);
            return new PingResult(false, System.currentTimeMillis() - pingStart);
        }
    }

    @Override
    protected void clientConnectionRun(Socket s) {}

    private class PingHandler extends I2PAppThread {
        private final String destination;
        private final int cnt;
        private final long timeout;
        private final boolean countPing;
        private final boolean reportTimes;
        private final int localPort;
        private final int remotePort;

        public PingHandler(String dest, int count, int fromPort, int toPort,
                           long timeout, boolean countPings, boolean report) {
            this.destination = dest;
            cnt = count;
            localPort = fromPort;
            remotePort = toPort;
            this.timeout = timeout;
            countPing = countPings;
            reportTimes = report;
            setName("PingHandler for " + dest);
        }

        @Override
        public void run() {
            l.log(" • PingHandler starting for destination: " + destination);
            Destination dest;
            try {
                dest = lookup(destination);
            } catch (I2PException e) {
                l.log(" ✖ Destination lookup failed for '" + destination + "': " + e.getMessage());
                return;
            }
            if (dest == null) {
                l.log(" • Ignoring unresolvable destination: " + destination);
                return;
            }
            l.log(" ✔ Destination resolved: " + dest.toBase32());

            // Wait up to 30 seconds for tunnels
            long tunnelReadyTimeout = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < tunnelReadyTimeout) {
                if (sockMgr != null && !sockMgr.isDestroyed()) {
                    try {
                        I2PSession session = sockMgr.getSession();
                        if (session != null && !session.isClosed()) {
                            break;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    l.log(" ✖ Interrupted while waiting for tunnels");
                    return;
                }
            }

            if (sockMgr == null || sockMgr.isDestroyed()) {
                l.log(" ✖ Socket manager unavailable, aborting ping");
                return;
            }
            try {
                I2PSession session = sockMgr.getSession();
                if (session == null || session.isClosed()) {
                    l.log(" ✖ Session not ready, aborting ping");
                    return;
                }
            } catch (Exception e) {
                l.log(" ✖ Session check failed: " + e.getMessage());
                return;
            }

            int pass = 0;
            long totalTime = 0;
            boolean allSuccess = true;
            List<PingResult> results = new ArrayList<PingResult>();

            for (int i = 0; i < cnt; i++) {
                l.log(" • Attempting ping " + (i + 1) + "/" + cnt + "...");
                PingResult result;
                try {
                    result = ping(dest, localPort, remotePort, timeout);
                } catch (I2PException e) {
                    l.log(" ✖ Ping error: " + e.getMessage());
                    result = new PingResult(false, 0);
                }

                results.add(result);

                if (countPing) {
                    if (!result.success) {
                        allSuccess = false;
                    }
                } else {
                    if (reportTimes && !fromList) {
                        if (result.success) {
                            pass++;
                            totalTime += result.duration;
                            l.log(" ✔ " + (i + 1) + ": \t " + result.duration + "ms");
                        } else {
                            l.log(" ✖ " + (i + 1) + ": \t ------");
                        }
                    }
                }
            }

            StringBuilder result = new StringBuilder();
            if (countPing) {
                if (allSuccess) {
                    result.append(" ✔ + (").append(cnt).append(" consecutive successes) ");
                } else {
                    result.append(" ✖ (not all pings succeeded) ");
                }
                result.append("‣ ").append(destination);
            } else {
                int successful = 0;
                long totalDuration = 0;
                for (PingResult pr : results) {
                    if (pr.success) {
                        successful++;
                        totalDuration += pr.duration;
                    }
                }
                result.append(" • Results for ").append(destination).append(": ");
                result.append(successful).append(" / ").append(cnt).append(" pongs received");
                if (successful > 0) {
                    result.append(", average response ").append(totalDuration / successful).append("ms");
                }
            }
            l.log(result.toString());
        }

        private Destination lookup(String name) throws I2PException {
            if (name == null || name.isEmpty()) {
                return null;
            }

            name = name.trim();

            // Handle .b32.i2p
            if (name.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
                String b32 = name.substring(0, name.length() - 8);
                if (b32.length() != 52) {
                    return null;
                }
                try {
                    byte[] data = Base32.decode(b32);
                    if (data != null) {
                        Destination dest = new Destination();
                        dest.fromByteArray(data);
                        return dest;
                    }
                } catch (Exception e) {
                    l.log(" ✖ B32 decode failed for: " + name + ": " + e.getMessage());
                    _log.warn("B32 decode failed for: " + name, e);
                }
                return null;
            }

            // Handle base64 (exactly 516 chars, no whitespace)
            if (name.length() == 516) {
                try {
                    return new Destination(name);
                } catch (Exception e) {
                    // not base64, fall through
                }
            }

            // Use naming service (hostname like stats.i2p)
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            Destination dest = ctx.namingService().lookup(name);
            if (dest != null) {
                return dest;
            }

            // Fallback: tunnel context
            try {
                I2PAppContext tunnelCtx = getTunnel().getContext();
                if (tunnelCtx != ctx) {
                    dest = tunnelCtx.namingService().lookup(name);
                    if (dest != null) {
                        return dest;
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            // Final fallback: session lookup
            if (sockMgr != null) {
                try {
                    I2PSession session = sockMgr.getSession();
                    if (session != null) {
                        return session.lookupDest(name);
                    }
                } catch (I2PSessionException e) {
                    _log.warn("Session lookup failed for: " + name, e);
                }
            }

            return null;
        }
    }
}