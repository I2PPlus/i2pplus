package net.i2p.i2ptunnel.irc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.Log;

/**
 *  Thread to do outbound filtering.
 *  Moved from I2PTunnelIRCClient.java
 *
 *  @since 0.8.9
 */
public class IrcOutboundFilter implements Runnable {

    private final Socket local;
    private final I2PSocket remote;
    private final StringBuffer expectedPong;
    private final Log _log;
    private final DCCHelper _dccHelper;
    private volatile Runnable onDisconnect;
    private volatile Timer pingTimer;
    private static final long PING_INTERVAL_MS = 30 * 1000; // 30 seconds

    public IrcOutboundFilter(Socket lcl, I2PSocket rem, StringBuffer pong, Log log) {
        this(lcl, rem, pong, log, null);
    }

    /**
     *  @param helper may be null
     *  @since 0.8.9
     */
    public IrcOutboundFilter(Socket lcl, I2PSocket rem, StringBuffer pong, Log log, DCCHelper helper) {
        local = lcl;
        remote = rem;
        expectedPong = pong;
        _log = log;
        _dccHelper = helper;
    }

    /**
     *  Set a callback to run when the filter disconnects.
     *  @param callback the callback to run on disconnect
     *  @since 0.9.68+
     */
    public void setOnDisconnect(Runnable callback) {
        onDisconnect = callback;
    }

    public void run() {
        // Todo: Don't use BufferedReader - IRC spec limits line length to 512 but...
        BufferedReader in;
        OutputStream output;
        try {
            in = new BufferedReader(new InputStreamReader(local.getInputStream(), "ISO-8859-1"));
            output=remote.getOutputStream();
        } catch (IOException e) {
            if (_log.shouldWarn())
                _log.warn("[IRC Client] Outbound Filter: No streams", e);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("[IRC Client] Outbound Filter: Running");
        
        // Start automatic ping timer to keep tunnels active
        pingTimer = new Timer("IRC Ping Timer", true);
        pingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    String ping = "PING :i2p-tunnel-keepalive\r\n";
                    output.write(ping.getBytes("ISO-8859-1"));
                    output.flush();
                    if (_log.shouldDebug()) {
                        _log.debug("[IRC Client] Sent automatic keepalive PING");
                    }
                } catch (IOException ioe) {
                    // Socket closed or error, cancel timer
                    pingTimer.cancel();
                }
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS);
        
        try {
            while(true)
            {
                try {
                    String inmsg = in.readLine();
                    if(inmsg==null)
                        break;
                    if(inmsg.endsWith("\r"))
                        inmsg=inmsg.substring(0,inmsg.length()-1);
                    // dupe of info level log
                    //if (_log.shouldDebug())
                    //    _log.debug("[IRC Client] Out: [" + inmsg + "]");
                    String outmsg = IRCFilter.outboundFilter(inmsg, expectedPong, _dccHelper);
                    if(outmsg!=null)
                    {
                        if(!inmsg.equals(outmsg)) {
                            if (_log.shouldInfo()) {
                                _log.info("[IRC Client] Outbound message FILTERED [" + outmsg + "]");
                                _log.info("[IRC Client] Outbound message [" + inmsg + "]");
                            }
                        } else {
                            if (_log.shouldInfo())
                                _log.info("[IRC Client] Outbound message [" + outmsg + "]");
                        }
                        outmsg = outmsg + "\r\n"; // rfc1459 sec. 2.3
                        output.write(outmsg.getBytes("ISO-8859-1"));
                        // save 250 ms in streaming
                        // Check ready() so we don't split the initial handshake up into multiple streaming messages
                        if (!in.ready())
                            output.flush();
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("[IRC Client] Outbound message BLOCKED [" + inmsg + "]");
                    }
                } catch (IOException e1) {
                    if (_log.shouldWarn())
                        _log.warn("[IRC Client] Outbound Filter: disconnected \n* Reason: " + e1.getMessage());
                    Runnable callback = onDisconnect;
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }
            }
        } catch (RuntimeException re) {
            _log.error("[IRC Client] Error filtering outbound data", re);
        } finally {
            if (pingTimer != null) {
                pingTimer.cancel();
            }
            try { remote.close(); } catch (IOException e) {}
        }
        if (_log.shouldDebug())
            _log.debug("[IRC Client] Outbound Filter: Stopped");
    }
}
