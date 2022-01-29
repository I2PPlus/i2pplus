<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
  /*
   *   Do not tag this file for translation.
   */
%>
<%
    net.i2p.I2PAppContext context = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (context.getProperty("routerconsole.lang") != null)
        lang = context.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("Debug")%>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf adv debug">Debug</h1>
<div class="main" id="debug">
<div class="confignav">
<span class="tab"><a href="#debug_portmapper">Port Mapper</a></span>
<span class="tab"><a href="#appmanager">App Manager</a></span>
<span class="tab"><a href="#updatemanager">Update Manager</a></span>
<span class="tab"><a href="#skm">Router Session Key Manager</a></span>
<span class="tab"><a href="#cskm0">Client Session Key Managers</a></span>
<span class="tab"><a href="#dht">Router DHT</a></span>
</div>
<%
    /*
     *  Quick and easy place to put debugging stuff
     */
    net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) net.i2p.I2PAppContext.getGlobalContext();

    /*
     *  Print out the status for the PortMapper
     */
    ctx.portMapper().renderStatusHTML(out);

    /*
     *  Print out the status for the InternalServerSockets
     */
    net.i2p.util.InternalServerSocket.renderStatusHTML(out);

    /*
     *  Print out the status for the AppManager
     */

    out.print("<div class=\"debug_section\" id=\"appmanager\">\n");
    ctx.routerAppManager().renderStatusHTML(out);
    out.print("</div>\n");


    /*
     *  Print out the status for the UpdateManager
     */
    out.print("<div class=\"debug_section\" id=\"updatemanager\">\n");
    net.i2p.app.ClientAppManager cmgr = ctx.clientAppManager();
    if (cmgr != null) {
        net.i2p.router.update.ConsoleUpdateManager umgr =
            (net.i2p.router.update.ConsoleUpdateManager) cmgr.getRegisteredApp(net.i2p.update.UpdateManager.APP_NAME);
        if (umgr != null) {
            umgr.renderStatusHTML(out);
        }
    out.print("</div>\n");
    }

    /*
     *  Print out the status for all the SessionKeyManagers
     */
    out.print("<div class=\"debug_section\" id=\"skm\">\n");
    out.print("<h2>Session Key Manager: Router</h2>\n");
    ctx.sessionKeyManager().renderStatusHTML(out);
    java.util.Set<net.i2p.data.Destination> clients = ctx.clientManager().listClients();
    out.print("</div>\n");
    int i = 0;
    for (net.i2p.data.Destination dest : clients) {
        net.i2p.data.Hash h = dest.calculateHash();
        net.i2p.crypto.SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(h);
        if (skm != null) {
            out.print("<div class=\"debug_section\" id=\"cskm" + (i++) + "\">\n<h2>Session Key Manager: ");
            net.i2p.router.TunnelPoolSettings tps = ctx.tunnelManager().getInboundSettings(h);
            if (tps != null) {
                String nick = tps.getDestinationNickname();
                if (nick != null)
                    out.print(net.i2p.data.DataHelper.escapeHTML(nick));
                else
                    out.print("<span id=\"skm_dest\">" + dest.toBase32() + "</span>");
            } else {
                out.print("<span id=\"skm_dest\">" + dest.toBase32() + "</span>");
            }
            out.print("</h2>\n");
            skm.renderStatusHTML(out);
            out.print("</div>\n");
        }
    }

    /*
     *  Print out the status for the NetDB
     */
    out.print("<h2 id=\"dht\">Router DHT</h2>\n");
    ctx.netDb().renderStatusHTML(out);

%>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>