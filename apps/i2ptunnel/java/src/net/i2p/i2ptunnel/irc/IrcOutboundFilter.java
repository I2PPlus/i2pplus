package net.i2p.i2ptunnel.irc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.Log;

/**
 * Filters outbound IRC messages, applying filtering rules before sending them over an I2PSocket.
 * Runs as a separate thread handling outbound message filtering.
 * @since 0.8.9
 */
public class IrcOutboundFilter implements Runnable {

    private final Socket local;
    private final I2PSocket remote;
    private final StringBuffer expectedPong;
    private final Log _log;
    private final DCCHelper _dccHelper;

    /**
     * Constructs the outbound filter thread.
     * @param lcl the local socket to read outbound messages from
     * @param rem the remote I2PSocket to write filtered messages to
     * @param pong buffer tracking expected PONG responses
     * @param log logging instance
     * @param helper optional DCC helper, may be null
     * @since 0.8.9
     */
    public IrcOutboundFilter(Socket lcl, I2PSocket rem, StringBuffer pong, Log log, DCCHelper helper) {
        local = lcl;
        remote = rem;
        expectedPong = pong;
        _log = log;
        _dccHelper = helper;
    }

    /**
     * Constructs the outbound filter without a DCC helper.
     * @param lcl the local socket to read outbound messages from
     * @param rem the remote I2PSocket to write filtered messages to
     * @param pong buffer tracking expected PONG responses
     * @param log logging instance
     * @since 0.8.9
     */
    public IrcOutboundFilter(Socket lcl, I2PSocket rem, StringBuffer pong, Log log) {
        this(lcl, rem, pong, log, null);
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(local.getInputStream(), "ISO-8859-1"));
             OutputStream output = remote.getOutputStream()) {

            if (_log.shouldDebug())
                _log.debug("[IRC Client] Outbound Filter: Running");

            String inmsg;
            while ((inmsg = in.readLine()) != null) {
                if (inmsg.endsWith("\r"))
                    inmsg = inmsg.substring(0, inmsg.length() - 1);

                String outmsg = IRCFilter.outboundFilter(inmsg, expectedPong, _dccHelper);

                if (outmsg != null) {
                    if (!inmsg.equals(outmsg) && _log.shouldWarn()) {
                        _log.warn("[IRC Client] Outbound message FILTERED [" + outmsg + "]");
                        _log.warn("[IRC Client] Original message [" + inmsg + "]");
                    } else if (_log.shouldInfo()) {
                        _log.info("[IRC Client] Outbound message [" +
                                  (outmsg.startsWith("PASS ") ? "PASS ************" : outmsg) + "]"); // don't log passwords
                    }
                    outmsg += "\r\n";
                    output.write(outmsg.getBytes("ISO-8859-1"));
                    if (!in.ready())
                        output.flush();
                } else if (_log.shouldWarn()) {
                    _log.warn("[IRC Client] Outbound message BLOCKED [" + inmsg + "]");
                }
            }
        } catch (IOException e) {
            if (_log.shouldWarn())
                _log.warn("[IRC Client] Outbound Filter: disconnected \n* Reason: " + e.getMessage());
        } catch (RuntimeException re) {
            _log.error("[IRC Client] Error filtering outbound data", re);
        } finally {
            try { remote.close(); } catch (IOException ignored) {}
            if (_log.shouldDebug())
                _log.debug("[IRC Client] Outbound Filter: Stopped");
        }
    }
}
