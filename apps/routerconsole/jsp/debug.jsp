<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext context = net.i2p.I2PAppContext.getGlobalContext();
    //String lang = context.getProperty("routerconsole.lang") != null ? context.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("Debug")%>
<link href="/themes/console/tablesort.css" rel="stylesheet" />
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class="conf adv debug">Debug</h1>
<div class="main" id="debug">
<div class="confignav">
<%  String[][] tabs = {
        {"Port Mapper", "/debug"},
        {"App Manager", "/debug?d=1"},
        {"Update Manager", "/debug?d=2"},
        {"Router Session Key Manager", "/debug?d=3"},
        {"Client Session Key Managers", "/debug?d=4"},
        {"Router DHT", "/debug?d=5"},
        {"Translation Status", "/debug?d=6"}
    };
    String currentD = request.getParameter("d");
    currentD = (currentD == null) ? "0" : currentD;
    for (String[] tab : tabs) {
        String tabUrl = tab[1];
        String tabD = "0";
        int idx = tabUrl.indexOf("d=");
        if (idx != -1) {
            tabD = tabUrl.substring(idx + 2);
            int amp = tabD.indexOf('&');
            if (amp != -1) tabD = tabD.substring(0, amp);
        }
        boolean isActive = tabD.equals(currentD);
%>
<% if (isActive) { %>
    <span class="tab2"><%=tab[0]%></span>
<% } else { %>
    <span class="tab"><a href="<%=tabUrl%>"><%=tab[0]%></a></span>
<% } %>
<% } %>
</div>
<%  net.i2p.router.RouterContext _ctx = (net.i2p.router.RouterContext) context;
    String dd = currentD;
    if (dd == null || dd.equals("0")) {
        _ctx.portMapper().renderStatusHTML(out);
        net.i2p.util.InternalServerSocket.renderStatusHTML(out);
    } else if ("1".equals(dd)) {
        out.print("<div class=debug_section id=appmanager>\n");
        _ctx.routerAppManager().renderStatusHTML(out);
        out.print("</div>\n");
    } else if ("2".equals(dd)) {
        out.print("<div class=debug_section id=updatemanager>\n");
        net.i2p.app.ClientAppManager cmgr = _ctx.clientAppManager();
        if (cmgr != null) {
            net.i2p.router.update.ConsoleUpdateManager umgr =
                (net.i2p.router.update.ConsoleUpdateManager) cmgr.getRegisteredApp(net.i2p.update.UpdateManager.APP_NAME);
            if (umgr != null) {umgr.renderStatusHTML(out);}
        }
        out.print("</div>\n");
    } else if ("3".equals(dd)) {
        out.print("<div class=debug_section id=skm>\n<h2>Router Session Key Manager</h2>\n");
        _ctx.sessionKeyManager().renderStatusHTML(out);
        out.print("</div>");
    } else if ("4".equals(dd)) {
        out.print("<h2>Client Session Key Managers</h2>");
        java.util.Set<net.i2p.data.Destination> clients = _ctx.clientManager().listClients();
        java.util.Set<net.i2p.crypto.SessionKeyManager> renderedSkms = new java.util.HashSet<>(clients.size());
        int i = 0;
        for (net.i2p.data.Destination dest : clients) {
            net.i2p.data.Hash h = dest.calculateHash();
            net.i2p.crypto.SessionKeyManager skm = _ctx.clientManager().getClientSessionKeyManager(h);
            if (skm != null) {
                out.print("<div class=debug_section id=cskm" + (i++) + ">\n<h2>Session Key Manager: ");
                net.i2p.router.TunnelPoolSettings tps = _ctx.tunnelManager().getInboundSettings(h);
                if (tps != null) {
                    String nick = tps.getDestinationNickname();
                    if (nick != null) {out.print(net.i2p.data.DataHelper.escapeHTML(nick));}
                    else {out.print("<span id=skm_dest>" + dest.toBase32() + "</span>");}
                } else {out.print("<span id=skm_dest>" + dest.toBase32() + "</span>");}
                out.print("</h2>\n");
                if (renderedSkms.add(skm)) {skm.renderStatusHTML(out);}
                else {out.print("<p>See Session Key Manager for alternate destination above</p>");}
                out.print("</div>\n");
            }
        }
    } else if ("5".equals(dd)) {
        out.print("<h2 id=dht>Router DHT</h2>\n");
        _ctx.netDb().renderStatusHTML(out);
    } else if ("6".equals(dd)) {
        java.io.InputStream is = this.getClass().getResourceAsStream("/net/i2p/router/web/resources/translationstatus.html");
        if (is == null) {out.println("Translation status not available");}
        else {
            out.println("<link rel=stylesheet href=/themes/console/debug_translate.css>");
            try (java.io.Reader br = new java.io.InputStreamReader(is, "UTF-8")) {
                char[] buf = new char[4096];
                int read;
                while ((read = br.read(buf)) >= 0) {out.write(buf, 0, read);}
            } catch (java.io.IOException ignored) {}
            out.println("<script src=/js/translationReport.js></script>");
            out.println("<noscript><style>.complete{display:table-row!important}.script{display:none!important}</style></noscript>");
        }
    }
%>
</div>
</body>
</html>