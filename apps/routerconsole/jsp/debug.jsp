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
<%@include file="summaryajax.jsi" %>
<%=intl.title("Debug")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class="conf adv debug">Debug</h1>
<div class=main id=debug>
<div class=confignav>
<span class=tab><a href="/debug">Port Mapper</a></span>
<span class=tab><a href="/debug?d=1">App Manager</a></span>
<span class=tab><a href="/debug?d=2">Update Manager</a></span>
<span class=tab><a href="/debug?d=3">Router Session Key Manager</a></span>
<span class=tab><a href="/debug?d=4">Client Session Key Managers</a></span>
<span class=tab><a href="/debug?d=5">Router DHT</a></span>
</div>

<%
    /*
     *  Quick and easy place to put debugging stuff
     */
    net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext) net.i2p.I2PAppContext.getGlobalContext();

String dd = request.getParameter("d");
if (dd == null || dd.equals("0")) {

    /*
     *  Print out the status for the PortMapper
     */
    ctx.portMapper().renderStatusHTML(out);

    /*
     *  Print out the status for the InternalServerSockets
     */
    net.i2p.util.InternalServerSocket.renderStatusHTML(out);

} else if (dd.equals("1")) {

    /*
     *  Print out the status for the AppManager
     */

    out.print("<div class=debug_section id=appmanager>\n");
    ctx.routerAppManager().renderStatusHTML(out);
    out.print("</div>\n");

} else if (dd.equals("2")) {

    /*
     *  Print out the status for the UpdateManager
     */
    out.print("<div class=debug_section id=updatemanager>\n");
    net.i2p.app.ClientAppManager cmgr = ctx.clientAppManager();
    if (cmgr != null) {
        net.i2p.router.update.ConsoleUpdateManager umgr =
            (net.i2p.router.update.ConsoleUpdateManager) cmgr.getRegisteredApp(net.i2p.update.UpdateManager.APP_NAME);
        if (umgr != null) {
            umgr.renderStatusHTML(out);
        }
    out.print("</div>\n");
    }

} else if (dd.equals("3")) {

    /*
     *  Print out the status for all the SessionKeyManagers
     */
    out.print("<div class=debug_section id=skm>\n");
    out.print("<h2>Router Session Key Manager</h2>\n");
    ctx.sessionKeyManager().renderStatusHTML(out);
    out.print("</div>");

} else if (dd.equals("4")) {

    out.print("<h2>Client Session Key Managers</h2>");
    java.util.Set<net.i2p.data.Destination> clients = ctx.clientManager().listClients();
    java.util.Set<net.i2p.crypto.SessionKeyManager> skms = new java.util.HashSet<net.i2p.crypto.SessionKeyManager>(clients.size());
    int i = 0;
    for (net.i2p.data.Destination dest : clients) {
        net.i2p.data.Hash h = dest.calculateHash();
        net.i2p.crypto.SessionKeyManager skm = ctx.clientManager().getClientSessionKeyManager(h);
        if (skm != null) {
            out.print("<div class=debug_section id=\"cskm" + (i++) + "\">\n<h2>Session Key Manager: ");
            net.i2p.router.TunnelPoolSettings tps = ctx.tunnelManager().getInboundSettings(h);
            if (tps != null) {
                String nick = tps.getDestinationNickname();
                if (nick != null)
                    out.print(net.i2p.data.DataHelper.escapeHTML(nick));
                else
                    out.print("<span id=skm_dest>" + dest.toBase32() + "</span>");
            } else {
                out.print("<span id=skm_dest>" + dest.toBase32() + "</span>");
            }
            out.print("</h2>\n");
            if (skms.add(skm))
                skm.renderStatusHTML(out);
            else
                out.print("<p>See Session Key Manager for alternate destination above</p>");
            out.print("</div>\n");
        }
    }
} else if (dd.equals("5")) {

    /*
     *  Print out the status for the NetDB
     */
    out.print("<h2 id=dht>Router DHT</h2>\n");
    ctx.netDb().renderStatusHTML(out);

}

%>
</div>

</body>
</html>