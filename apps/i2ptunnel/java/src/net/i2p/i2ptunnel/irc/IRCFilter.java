package net.i2p.i2ptunnel.irc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.util.Log;


/**
 * Static methods to filter individual lines.
 * Moved from I2PTunnelIRCClient.java
 *
 * @since 0.8.9
 */
abstract class IRCFilter {

    private static final boolean ALLOW_ALL_DCC_IN = false;
    private static final boolean ALLOW_ALL_DCC_OUT = false;
    /** does not override DCC handling */
    private static final boolean ALLOW_ALL_CTCP_IN = false;
    /** does not override DCC handling */
    private static final boolean ALLOW_ALL_CTCP_OUT = false;

    private static final Set<String> _allowedInbound;
    static {
        final String[] allowedCommands =
        {
                // "NOTICE", // can contain CTCP
                "PING",
                //"PONG",
                "MODE",
                "JOIN",
                "NICK",
                "QUIT",
                "PART",
                "WALLOPS",
                "ERROR",
                "KICK",
                "H", // "hide operator status" (after kicking an op)
                "TOPIC",
                "AUTHENTICATE", // SASL, also requires CAP below
                // http://tools.ietf.org/html/draft-mitchell-irc-capabilities-01
                "CAP",
                "PROTOCTL",
                "AWAY",
                "ACCOUNT",  // https://ircv3.net/specs/extensions/account-notify
                "CHGHOST"   // https://ircv3.net/specs/extensions/chghost
        };
        _allowedInbound = new HashSet<String>(Arrays.asList(allowedCommands));
    }

    /*************************************************************************
     *
     *  Modify or filter a single inbound line.
     *
     *  @param helper may be null
     *  @return the original or modified line, or null if it should be dropped.
     */
    public static String inboundFilter(String s, StringBuffer expectedPong, DCCHelper helper) {

        String field[] = DataHelper.split(s, " ", 5);
        String command;
        int idx=0;


        try {
            // https://www.unrealircd.org/docs/Message_tags
            // https://ircv3.net/specs/extensions/message-tags.html
            if (field[0].charAt(0) == '@')
                idx++;
            if (field[idx].charAt(0) == ':')
                idx++;
            command = field[idx++].toUpperCase(Locale.US);
        } catch (IndexOutOfBoundsException ioobe) {
            // server sent borked command?
           //_log.warn("Dropping defective message: index out of bounds while extracting command.");
           return null;
        }

        idx++; //skip victim

        // Allow numerical responses
        try {
            Integer.parseInt(command);
            return s;
        } catch(NumberFormatException nfe){}


        if ("PONG".equals(command)) {
            // Turn the received ":irc.freshcoffee.i2p PONG irc.freshcoffee.i2p :127.0.0.1"
            // into ":127.0.0.1 PONG 127.0.0.1 " so that the caller can append the client's extra parameter
            // though, does 127.0.0.1 work for irc clients connecting remotely?  and for all of them?  sure would
            // be great if irc clients actually followed the RFCs here, but i guess thats too much to ask.
            // If we haven't PINGed them, or the PING we sent isn't something we know how to filter, this
            // is blank.
            //
            // String pong = expectedPong.length() > 0 ? expectedPong.toString() : null;
            // If we aren't going to rewrite it, pass it through
            String pong = expectedPong.length() > 0 ? expectedPong.toString() : s;
            expectedPong.setLength(0);
            return pong;
        }

        // Allow all allowedCommands
        if (_allowedInbound.contains(command)) {
                return s;
        }

        // Allow PRIVMSG, but block CTCP.
        if("PRIVMSG".equals(command) || "NOTICE".equals(command))
        {
            String msg;
            msg = field[idx++];

            if(msg.indexOf(0x01) >= 0) // CTCP marker ^A can be anywhere, not just immediately after the ':'
            {
                // CTCP

                // don't even try to parse multiple CTCP in the same message
                int count = 0;
                for (int i = 0; i < msg.length(); i++) {
                    if (msg.charAt(i) == 0x01)
                        count++;
                }
                if (count != 2)
                    return null;

                msg=msg.substring(2);
                if(msg.startsWith("ACTION ")) {
                    // /me says hello
                    return s;
                }
                if (msg.startsWith("DCC ")) {
                    StringBuilder buf = new StringBuilder(128);
                    for (int i = 0; i <= idx - 2; i++) {
                        buf.append(field[i]).append(' ');
                    }
                    buf.append(":\001DCC ");
                    return filterDCCIn(buf.toString(), msg.substring(4), helper);
                }
                // XDCC looks safe, ip/port happens over regular DCC
                // http://en.wikipedia.org/wiki/XDCC
                if (msg.toUpperCase(Locale.US).startsWith("XDCC ") && helper != null && helper.isEnabled())
                    return s;
                if (ALLOW_ALL_CTCP_IN)
                    return s;
                return null; // Block all other ctcp
            }
            return s;
        }

        // Block the rest
        return null;
    }

    private static final Set<String> _allowedOutbound;
    static {
        final String[] allowedCommands =
        {
                // Commands that regular users might use
                "ACCEPT", // Inspircd's m_callerid.so module
                "ADMIN",
                "AUTHENTICATE", // SASL, also requires CAP below
                "AWAY",    // should be harmless
                "CAP",     // http://tools.ietf.org/html/draft-mitchell-irc-capabilities-01
                "COMMANDS",
                "CYCLE",
                "DCCALLOW",
                "DEVOICE",
                "FPART",
                "HELPME", "HELPOP",  // helpop is what unrealircd uses by default
                "INVITE",
                "ISON",    // jIRCii uses this for a ping (response is 303)
                "JOIN",
                "KICK",
                "KNOCK",
                "LINKS",
                "LIST",
                "LUSERS",
                "MAP", // seems safe enough, the ircd should protect themselves though
                "MODE",
                "MOTD",
                "NAMES",
                "NICK",
                // "NOTICE", // can contain CTCP
                "OPER",
                // "PART", // replace with filtered PART to hide client part messages
                "PASS",
                // "PING",
                "PONG",
                "PROTOCTL",
                // "QUIT", // replace with a filtered QUIT to hide client quit messages
                "RULES",
                "SETNAME",
                "SILENCE",
                "SSLINFO",
                "STATS",
                "TBAN",
                "TITLE",
                "TOPIC",
                "UNINVITE",
                "USERHOST",
                "USERS", // Ticket 1249
                "VHOST",
                "VHOST",
                "WATCH",
                "WHO",
                "WHOIS",
                "WHOWAS",
                // the next few are default aliases on unreal (+ anope)
                "BOTSERV", "BS",
                "CHANSERV", "CS",
                "HELPSERV",
                "HOSTSERV", "HS",
                "MEMOSERV", "MS",
                "NICKSERV", "NS",
                "OPERSERV", "OS",
                "STATSERV",
                // IRCop commands
                "ADCHAT",
                "ADDMOTD",
                "ADDOMOTD",
                "CBAN",
                "CHATOPS",
                "CHECK",
                "CHGHOST",
                "CHGIDENT",
                "CHGNAME",
                "CLOSE",
                "DCCDENY",
                "DIE",
                "ELINE",
                "FILTER",
                "GLINE",
                "GLOBOPS",
                "GZLINE",
                "HTM", // "High Traffic Mode"
                "JUMPSERVER",
                "KILL",
                "KLINE",
                "LOADMODULE",
                "LOCKSERV",
                "LOCOPS",
                "MKPASSWD",
                "NACHAT",
                "NICKLOCK",
                "NICKUNLOCK",
                "OLINE",
                "OPERMOTD",
                "REHASH",
                "RELOADMODULE",
                "RESTART",
                "RLINE",
                "SAJOIN",
                "SAKICK",
                "SAMODE",
                "SANICK",
                "SAPART",
                "SATOPIC",
                "SDESC",
                "SETHOST",
                "SETIDENT",
                "SHUN",
                "SPAMFILTER",
                "SQUIT",
                "TEMPSHUN",
                "TLINE",
                "UNDCCDENY",
                "UNLOCKSERV",
                "WALLOPS",
                "ZLINE"
        };
        _allowedOutbound = new HashSet<String>(Arrays.asList(allowedCommands));
    }

    /*************************************************************************
     *
     *  Modify or filter a single outbound line.
     *
     *  @param helper may be null
     *  @return the original or modified line, or null if it should be dropped.
     */
    public static String outboundFilter(String s, StringBuffer expectedPong, DCCHelper helper) {

        String field[] = DataHelper.split(s, " ", 4);

        if(field[0].length()==0)
            return null; // W T F?


        if(field[0].charAt(0)==':')
            return null; // ???

        int idx = 0;
        // https://www.unrealircd.org/docs/Message_tags
        // https://ircv3.net/specs/extensions/message-tags.html
        if (field[0].charAt(0) == '@')
            idx++;
        String command = field[idx++].toUpperCase(Locale.US);

        if ("PING".equals(command)) {
            // Most clients just send a PING and are happy with any old PONG.  Others,
            // like BitchX, actually expect certain behavior.  It sends two different pings:
            // "PING :irc.freshcoffee.i2p" and "PING 1234567890 127.0.0.1" (where the IP is the proxy)
            // the PONG to the former seems to be "PONG 127.0.0.1", while the PONG to the later is
            // ":irc.freshcoffee.i2p PONG irc.freshcoffe.i2p :1234567890".
            // We don't want to send them our proxy's IP address, so we need to rewrite the PING
            // sent to the server, but when we get a PONG back, use what we expected, rather than
            // what they sent.
            //
            // Yuck.

            String rv = null;
            expectedPong.setLength(0);
            if (field.length == idx) { // PING
                rv = "PING";
                // If we aren't rewriting the PING don't rewrite the PONG
                // expectedPong.append("PONG 127.0.0.1");
            } else if (field.length == idx + 1) { // PING nonce
                rv = "PING " + field[idx];
                // If we aren't rewriting the PING don't rewrite the PONG
                // expectedPong.append("PONG ").append(field[1]);
            } else if (field.length == idx + 2) { // PING nonce serverLocation
                rv = "PING " + field[idx];
                expectedPong.append("PONG ").append(field[idx + 1]).append(" :").append(field[idx]); // PONG serverLocation nonce
            } else {
                //if (_log.shouldError())
                //    _log.error("IRC client sent a PING we don't understand, filtering it (\"" + s + "\")");
                rv = null;
            }

            //if (_log.shouldWarn())
            //    _log.warn("sending ping [" + rv + "], waiting for [" + expectedPong + "] orig was [" + s  + "]");

            return rv;
        }

        // Allow all allowedCommands
        if (_allowedOutbound.contains(command))
            return s;

        // mIRC sends "NOTICE user :DCC Send file (IP)"
        // in addition to the CTCP version
        if("NOTICE".equals(command))
        {
            if (field.length < idx + 2)
                return s;  // invalid, allow server response
            String msg = field[idx + 1];
            if(msg.startsWith(":DCC "))
                return filterDCCOut(field[idx - 1] + ' ' + field[idx] + " :DCC ", msg.substring(5), helper);
            // fall through
        }

        // Allow PRIVMSG, but block CTCP (except ACTION).
        if("PRIVMSG".equals(command) || "NOTICE".equals(command))
        {
            if (field.length < idx + 2)
                return s;  // invalid, allow server response
            String msg = field[idx + 1];

            if(msg.indexOf(0x01) >= 0) // CTCP marker ^A can be anywhere, not just immediately after the ':'
            {
                    // CTCP

                // don't even try to parse multiple CTCP in the same message
                int count = 0;
                for (int i = 0; i < msg.length(); i++) {
                    if (msg.charAt(i) == 0x01)
                        count++;
                }
                if (count != 2)
                    return null;

                msg=msg.substring(2);
                if(msg.startsWith("ACTION ")) {
                    // /me says hello
                    return s;
                }
                if (msg.startsWith("DCC "))
                    return filterDCCOut(field[idx - 1] + ' ' + field[idx] + " :\001DCC ", msg.substring(4), helper);
                // XDCC looks safe, ip/port happens over regular DCC
                // http://en.wikipedia.org/wiki/XDCC
                if (msg.toUpperCase(Locale.US).startsWith("XDCC ") && helper != null && helper.isEnabled())
                    return s;
                if (ALLOW_ALL_CTCP_OUT)
                    return s;
                return null; // Block all other ctcp
            }
            return s;
        }

        if("USER".equals(command)) {
            // USER <username> <hostname> <servername> <realname> (RFC 1459)
            // USER <user> <mode> <unused> <realname> (RFC 2812)
            // <realname> may contain spaces, and thus must be prefixed with a colon.
            if (field.length < idx + 3)
                return s;  // invalid, allow server response
            // Replace hostname/servername; pass numeric mode and unused '*' through unchanged
            String hostname = field[idx + 1];
            try {
                Integer.parseInt(hostname);
                // mode
            } catch (NumberFormatException nfe) {
                hostname = "hostname";
            }
            // Warning: max field size is 4, so servername or unused is combined with realname
            // in field[idx + 2]
            String servername;
            if (field[idx + 2].startsWith("* "))
                servername = "*";
            else
                servername = "localhost";
            String realname;
            int cidx = field[idx + 2].lastIndexOf(':');
            if (cidx < 0)
                realname = "realname";
            else
                realname = field[idx + 2].substring(cidx + 1);
            String ret = "USER " + field[idx] + ' ' + hostname + ' ' + servername + " :" + realname;
            return ret;
        }

        if ("PART".equals(command)) {
            // hide client message
            return "PART " + field[idx] + " :leaving";
        }

        if ("QUIT".equals(command)) {
            return "QUIT :leaving";
        }

        // Block the rest
        return null;
    }

    /**
     *<pre>
     *  DCC CHAT chat xxx.b32.i2p i2p-port        -> DCC CHAT chat IP port
     *  DCC SEND file xxx.b32.i2p i2p-port length -> DCC SEND file IP port length
     *  DCC RESUME file i2p-port offset           -> DCC RESUME file port offset
     *  DCC ACCEPT file i2p-port offset           -> DCC ACCEPT file port offset
     *  DCC xxx                                   -> null
     *</pre>
     *
     *  @param pfx the message through the "DCC " part
     *  @param msg the message after the "DCC " part
     *  @param helper may be null
     *  @return the sanitized message or null to block
     *  @since 0.8.9
     */
    private static String filterDCCIn(String pfx, String msg, DCCHelper helper) {
        // strip trailing ctcp (other one is in pfx)
        int ctcp = msg.indexOf(0x01);
        if (ctcp > 0)
            msg = msg.substring(0, ctcp);
        String[] args = DataHelper.split(msg, " ", 5);
        if (args.length <= 0)
            return null;
        String type = args[0];
        boolean haveIP = true;
        // no IP in these, replace port only
        if (type == "RESUME" || type == "ACCEPT") {
            haveIP = false;
        } else if (!(type.equals("CHAT") || type.equals("SEND"))) {
            if (ALLOW_ALL_DCC_IN) {
                if (ctcp > 0)
                    return pfx + msg + (char) 0x01;
                return pfx + msg;
            }
            return null;
        }
        if (helper == null || !helper.isEnabled())
            return null;
        if (args.length < 3)
            return null;
        if (haveIP && args.length < 4)
            return null;
        String arg = args[1];
        int nextArg = 2;
        String b32 = null;
        if (haveIP)
            b32 = args[nextArg++];
        int cPort;
        try {
            String cp = args[nextArg++];
            cPort = Integer.parseInt(cp);
        } catch (NumberFormatException nfe) {
            return null;
        }
        if (cPort < 0 || cPort > 65535)
            return null;

        int port = -1;
        if (haveIP) {
            if (cPort > 0)
                port = helper.newIncoming(b32, cPort, type);
            else
                // "reverse/firewall DCC" - send it through without tracking
                port = cPort;
        } else if (type.equals("ACCEPT")) {
            port = helper.acceptIncoming(cPort);
        } else if (type.equals("RESUME")) {
            port = helper.resumeIncoming(cPort);
        }
        if (port < 0)
            return null;
        StringBuilder buf = new StringBuilder(256);
        buf.append(pfx)
           .append(type).append(' ').append(arg).append(' ');
        if (haveIP) {
            if (port > 0) {
                byte[] myIP = helper.getLocalAddress();
                buf.append(DataHelper.fromLong(myIP, 0, myIP.length)).append(' ');
            } else {
                // "reverse/firewall DCC" - set dummy IP and send it through
                buf.append("0 ");
            }
        }
        buf.append(port);
        while (args.length > nextArg) {
            buf.append(' ').append(args[nextArg++]);
        }
        if (pfx.indexOf(0x01) >= 0)
            buf.append((char) 0x01);
        return buf.toString();
    }

    /**
     *<pre>
     *  DCC CHAT chat IP port        -> DCC CHAT chat xxx.b32.i2p i2p-port
     *  DCC SEND file IP port length -> DCC SEND file xxx.b32.i2p i2p-port length
     *  DCC RESUME file port offset  -> DCC RESUME file i2p-port offset
     *  DCC ACCEPT file port offset  -> DCC ACCEPT file i2p-port offset
     *  DCC xxx                      -> null
     *</pre>
     *
     *  @param pfx the message through the "DCC " part
     *  @param msg the message after the "DCC " part
     *  @param helper may be null
     *  @return the sanitized message or null to block
     *  @since 0.8.9
     */
    private static String filterDCCOut(String pfx, String msg, DCCHelper helper) {
        // strip trailing ctcp (other one is in pfx)
        int ctcp = msg.indexOf(0x01);
        if (ctcp > 0)
            msg = msg.substring(0, ctcp);
        String[] args = DataHelper.split(msg, " ", 5);
        if (args.length <= 0)
            return null;
        String type = args[0];
        boolean haveIP = true;
        // no IP in these, replace port only
        if (type == "RESUME" || type == "ACCEPT") {
            haveIP = false;
        } else if (!(type.equals("CHAT") || type.equals("SEND"))) {
            if (ALLOW_ALL_DCC_OUT) {
                if (ctcp > 0)
                    return pfx + msg + (char) 0x01;
                return pfx + msg;
            }
        }
        if (helper == null || !helper.isEnabled())
            return null;
        if (args.length < 3)
            return null;
        if (haveIP && args.length < 4)
            return null;
        String arg = args[1];
        byte[] ip = null;
        int nextArg = 2;
        if (haveIP) {
            try {
                String ips = args[nextArg++];
                long ipl = Long.parseLong(ips);
                if (ipl < 0x01000000) {
                    // "reverse/firewall DCC"
                    // http://en.wikipedia.org/wiki/Direct_Client-to-Client
                    // xchat sends an IP of 199 and a port of 0
                    Log log = new Log(IRCFilter.class);
                    log.logAlways(Log.WARN, "Reverse / Firewall DCC, IP = 0x" + Long.toHexString(ipl));
                    //return null;
                }
                ip = DataHelper.toLong(4, ipl);
            } catch (NumberFormatException nfe) {
                return null;
            }
        }
        int cPort;
        try {
            String cp = args[nextArg++];
            cPort = Integer.parseInt(cp);
        } catch (NumberFormatException nfe) {
            return null;
        }
        if (cPort < 0 || cPort > 65535)
            return null;

        int port = -1;
        if (haveIP) {
            if (cPort > 0) {
                // nonzero port but bogus IP? hmm. Fix IP and hope.
                if (ip[0] == 0)
                    ip = new byte[] {127, 0, 0, 1};
                port = helper.newOutgoing(ip, cPort, type);
            } else {
                // "reverse/firewall DCC" - send it through without tracking
                Log log = new Log(IRCFilter.class);
                log.logAlways(Log.WARN, "Reverse / Firewall DCC, port = 0");
                port = cPort;
            }
        } else if (type.equals("ACCEPT")) {
            port = helper.acceptOutgoing(cPort);
        } else if (type.equals("RESUME")) {
            port = helper.resumeOutgoing(cPort);
        }
        if (port < 0)
            return null;
        StringBuilder buf = new StringBuilder(256);
        buf.append(pfx)
           .append(type).append(' ').append(arg).append(' ');
        if (haveIP) {
            if (port > 0)
                buf.append(helper.getB32Hostname()).append(' ');
            else
                // "reverse/firewall DCC" - set dummy IP and send it through
                buf.append("0 ");
        }
        buf.append(port);
        while (args.length > nextArg) {
            buf.append(' ').append(args[nextArg++]);
        }
        if (pfx.indexOf(0x01) >= 0)
            buf.append((char) 0x01);
        return buf.toString();
    }
}
