package net.i2p.i2ptunnel.irc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.Log;

/**
 *  Thread to do inbound filtering.
 *  Moved from I2PTunnelIRCClient.java
 *
 *  @since 0.8.9
 */
public class IrcInboundFilter implements Runnable {

    private final Socket local;
    private final I2PSocket remote;
    private final StringBuffer expectedPong;
    private final Log _log;
    private final DCCHelper _dccHelper;
    private volatile Runnable onDisconnect;

    public IrcInboundFilter(Socket lcl, I2PSocket rem, StringBuffer pong, Log log) {
        this(lcl, rem, pong, log, null);
    }

    /**
     *  @param helper may be null
     *  @since 0.8.9
     */
    public IrcInboundFilter(Socket lcl, I2PSocket rem, StringBuffer pong, Log log, DCCHelper helper) {
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
            in = new BufferedReader(new InputStreamReader(remote.getInputStream(), "ISO-8859-1"));
            output=local.getOutputStream();
        } catch (IOException e) {
            if (_log.shouldWarn())
                _log.warn("[IRC Client] Inbound Filter: No streams",e);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("[IRC Client] Inbound Filter: Running");
        try {
            while(true)
            {
                try {
                    String inmsg = in.readLine();
                    if(inmsg == null)
                        break;
                    if(inmsg.endsWith("\r"))
                        inmsg=inmsg.substring(0,inmsg.length()-1);
                    // dupe of info level log
                    //if (_log.shouldDebug())
                    //    _log.debug("[IRC Client] In: [" + inmsg + "]");
                    String outmsg = IRCFilter.inboundFilter(inmsg, expectedPong, _dccHelper);
                    if(outmsg != null)
                    {
                        if(!inmsg.equals(outmsg)) {
                            if (_log.shouldWarn()) {
                                _log.warn("[IRC Client] Inbound message FILTERED [" + outmsg + "]");
                                _log.warn("[IRC Client] Inbound message [" + inmsg + "]");
                            }
                        } else {
                            if (_log.shouldInfo())
                                _log.info("[IRC Client] Inbound message [" + outmsg + "]");
                        }
                        outmsg = outmsg + "\r\n";   // rfc1459 sec. 2.3
                        output.write(outmsg.getBytes("ISO-8859-1"));
                        // probably doesn't do much but can't hurt
                        if (!in.ready())
                            output.flush();
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("[IRC Client] Inbound message BLOCKED [" + inmsg + "]");
                    }
                } catch (IOException e1) {
                    if (_log.shouldWarn())
                        _log.warn("[IRC Client] Inbound Filter: Disconnected -> " + e1.getMessage());
                    Runnable callback = onDisconnect;
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }
            }
        } catch (RuntimeException re) {
            _log.error("Error filtering inbound data", re);
        } finally {
            try { in.close(); } catch (IOException e) {}
            try { local.close(); } catch(IOException e) {}
        }
        if(_log.shouldDebug())
            _log.debug("[IRC Client] Inbound Filter: Stopped");
    }
}
