/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 *  Warning - not necessarily a stable API.
 *  Used by I2PTunnel CLI only. Consider this sample code.
 *  Not for use outside this package.
 */
public class I2Ping extends I2PTunnelClientBase {

    public static final String PROP_COMMAND = "command";
    private static final int PING_COUNT = 10;
    private static final int CPING_COUNT = 5;
    private static final int PING_TIMEOUT = 8*1000;
    private static final long PING_DISTANCE = 1000;
    private int MAX_SIMUL_PINGS = 16;
    private volatile boolean finished;

    private final Object simulLock = new Object();
    private int simulPings;
    private long lastPingTime;
    private boolean fromList = false;

    /**
     *  tunnel.getOptions must contain "command".
     *  @throws IllegalArgumentException if it doesn't
     */
    public I2Ping(Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(-1, ownDest, l, notifyThis, "I2Ping", tunnel);
        if (!tunnel.getClientOptions().containsKey(PROP_COMMAND)) {
            // todo clean up
            throw new IllegalArgumentException("Options does not contain " + PROP_COMMAND);
        }
    }

    /**
     *  Overrides super. No client ServerSocket is created.
     */
    @Override
    public void run() {
        // Notify constructor that port is ready
        synchronized (this) {
            listenerReady = true;
            notifyAll();
            l.log(" • Tunnels ready for I2Ping client");
        }
        try {
            runCommand(getTunnel().getClientOptions().getProperty(PROP_COMMAND));
        } catch (InterruptedException ex) {
            l.log(" ✖ Interrupted");
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
          case 't':  // timeout
            timeout = Long.parseLong(g.getOptarg());
                // convenience, convert msec to sec
                if (timeout < 100)
                    timeout *= 1000;
            break;

          case 'm': // max simultaneous pings
            MAX_SIMUL_PINGS = Integer.parseInt(g.getOptarg());
            break;

          case 'n': // number of pings
            count = Integer.parseInt(g.getOptarg());
            break;

          case 'c': // "count" ping
            countPing = true;
            count = CPING_COUNT;
            break;

          case 'h': // ping all hosts
            fromList = true;
            if (hostListFile != null)
                error = true;
            else
                hostListFile = "hosts.txt";
            break;

          case 'l':  // ping a list of hosts
            fromList = true;
            if (hostListFile != null)
                error = true;
            else
                hostListFile = g.getOptarg();
            break;

          case 'f': // local port
            localPort = Integer.parseInt(g.getOptarg());
            break;

          case 'p': // remote port
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
                if (line.startsWith("#")) continue; // comments
                if (line.startsWith(";")) continue;
                if (line.startsWith("!")) continue;
                if (line.indexOf('=') != -1) { // maybe file is hosts.txt?
                    line = line.substring(0, line.indexOf('='));
                }
                PingHandler ph = new PingHandler(line, count, localPort, remotePort,
                                                 timeout, countPing, reportTimes);
                ph.start();
                pingHandlers.add(ph);
                if (++i > 1)
                    reportTimes = false;
            }
            br.close();
            for (Thread t : pingHandlers)
                t.join();
            return;
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
      }

      String host = argv[g.getOptind()];
      Thread t = new PingHandler(host, count, localPort, remotePort,
                                 timeout, countPing, reportTimes);
      t.setPriority(Thread.NORM_PRIORITY);
      t.start();
      t.join();
    }

    /**
     *  With newlines except for last line
     *  @since 0.9.12
     */
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
            l.log(" ‣ There are still pings running!");
            return false;
        }
        l.log(" ‣ I2Ping client closed");
        return true;
    }

    private boolean ping(Destination dest, int fromPort, int toPort, long timeout) throws I2PException {
        try {
            synchronized (simulLock) {
                while (simulPings >= MAX_SIMUL_PINGS) {
                    simulLock.wait();
                }
                simulPings++;
                while (lastPingTime + PING_DISTANCE > System.currentTimeMillis()) {
                    // no wait here, to delay all pingers
                    Thread.sleep(PING_DISTANCE / 2);
                }
                lastPingTime = System.currentTimeMillis();
            }
            boolean sent = sockMgr.ping(dest, fromPort, toPort, timeout);
            synchronized (simulLock) {
                simulPings--;
                simulLock.notifyAll();
            }
            return sent;
        } catch (InterruptedException ex) {
            _log.error("Interrupted", ex);
            return false;
        }
    }

    /**
     *  Does nothing.
     *  @since 0.9.11
     */
    protected void clientConnectionRun(Socket s) {}

    private class PingHandler extends I2PAppThread {
        private final String destination;
        private final int cnt;
        private final long timeout;
        private final boolean countPing;
        private final boolean reportTimes;
        private final int localPort;
        private final int remotePort;

        /**
         *  As of 0.9.11, does NOT start itself.
         *  Caller must call start()
         *  @param dest b64 or b32 or host name
         */
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
            try {
                Destination dest = lookup(destination);
                if (dest == null) {
                    l.log(" • Ignoring unresolvable destination: " + destination);
                    return;
                }
                int pass = 0;
                int fail = 0;
                long totalTime = 0;
                StringBuilder pingResults = new StringBuilder(2 * cnt + destination.length() + 3);
                for (int i = 0; i < cnt; i++) {
                    boolean sent = ping(dest, localPort, remotePort, timeout);
                    if (countPing) {
                        if (!sent) {
                            pingResults.append(i).append(" ");
                            break;
                        } else if (i == cnt - 1) {
                            pingResults.append("+ ");
                        }
                    } else {
                        if (reportTimes && !fromList) {
                            if (sent) {
                                pass++;
                                long rtt = System.currentTimeMillis() - lastPingTime;
                                totalTime += rtt;
                                l.log(" ✔ " + (i+1) + ":   \t " + rtt + "ms");
                            } else {
                                fail++;
                                l.log(" ✖ " + (i+1) + ":   \t ------");
                            }
                        } else {
                            pingResults.append(sent ? " ✔" : " ✖");
                        }
                    }
                    //System.out.println(sent + " -> " + destination);
                }

                if (reportTimes && !fromList) {
                    pingResults.append(" • Results for ").append(destination).append(": ");
                    pingResults.append(pass).append(" / ").append(pass + fail).append(" pongs received");
                } else {
                    pingResults.append(" ‣ ").append(destination);
                }
                if (pass > 0 && reportTimes && !fromList)
                    pingResults.append(", average response ").append(totalTime/pass).append("ms");
                l.log(pingResults.toString());
            } catch (I2PException ex) {
                _log.error("Error pinging " + destination, ex);
            }
        }

        /**
         *  @param name b64 or b32 or host name
         *  @since 0.9.11
         */
        private Destination lookup(String name) {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            boolean b32 = name.length() == 60 && name.toLowerCase(Locale.US).endsWith(".b32.i2p");
            if (ctx.isRouterContext() && !b32) {
                // Local lookup.
                // Even though we could do b32 outside router ctx here,
                // we do it below instead so we can use the session,
                // which we can't do with lookup()
                Destination dest = ctx.namingService().lookup(name);
                if (dest != null || ctx.isRouterContext() || name.length() >= 516)
                    return dest;
            }
            try {
                I2PSession sess = sockMgr.getSession();
                return sess.lookupDest(name);
            } catch (I2PSessionException ise) {
                _log.error("Error looking up " + name, ise);
                return null;
            }
        }
    }
}
