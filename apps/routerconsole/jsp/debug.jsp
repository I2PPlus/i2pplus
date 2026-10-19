<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext context = net.i2p.I2PAppContext.getGlobalContext();
    //String lang = context.getProperty("routerconsole.lang") != null ? context.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("Debug")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class="conf adv debug"><%=intl._t("Debug")%></h1>
<div class="main" id="debug">
<div class="confignav">
<%  String[][] tabs = {
        {"Overview", "/debug?d=8"},
        {"Port Mapper", "/debug?d=0"},
        {"App Manager", "/debug?d=1"},
        {"Update Manager", "/debug?d=2"},
        {"Router Session Key Manager", "/debug?d=3"},
        {"Client Session Key Managers", "/debug?d=4"},
        {"Router DHT", "/debug?d=5"},
        {"Translation Status", "/debug?d=6"},
        {"SAM", "/debug?d=7"},
        {"Streams", "/streams"}
    };
    String currentD = request.getParameter("d");
    currentD = (currentD == null) ? "8" : currentD;
    for (String[] tab : tabs) {
        String tabUrl = tab[1];
        String tabD = null;
        int idx = tabUrl.indexOf("d=");
        if (idx != -1) {
            tabD = tabUrl.substring(idx + 2);
            int amp = tabD.indexOf('&');
            if (amp != -1) tabD = tabD.substring(0, amp);
        }
        boolean isActive = tabD != null && tabD.equals(currentD);
%>
<% if (isActive) { %>
    <span class="tab2"><%=intl._t(tab[0])%></span>
<% } else { %>
    <span class="tab"><a href="<%=tabUrl%>"><%=intl._t(tab[0])%></a></span>
<% } %>
<% } %>
</div>
<%  net.i2p.router.RouterContext _ctx = (net.i2p.router.RouterContext) context;
    String dd = currentD;
    if (dd == null || dd.equals("8")) {
        net.i2p.router.web.helpers.DebugOverviewHelper ovHelper = new net.i2p.router.web.helpers.DebugOverviewHelper();
        ovHelper.setContextId(i2pcontextId);
        out.write(ovHelper.getOverviewHTML());
    } else if (dd.equals("0")) {
        net.i2p.router.web.helpers.ListeningSocketsHelper.render(_ctx.portMapper(), out);
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
        out.print("<div class=debug_section id=skm>\n<h2>" + intl._t("Router Session Key Manager") + "</h2>\n");
        _ctx.sessionKeyManager().renderStatusHTML(out);
        out.print("</div>");
    } else if ("4".equals(dd)) {
        out.print("<h2>" + intl._t("Client Session Key Managers") + "</h2>");
        net.i2p.router.web.helpers.ClientSKMDebugHelper.render(_ctx, out);
    } else if ("5".equals(dd)) {
        out.print("<h2 id=dht>" + intl._t("Router DHT") + "</h2>\n");
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
    } else if ("7".equals(dd)) {
        net.i2p.app.ClientAppManager cmgr = _ctx.clientAppManager();
        if (cmgr != null) {
            net.i2p.app.ClientApp sam = cmgr.getRegisteredApp("SAM");
            if (sam instanceof net.i2p.sam.SAMBridge) {
                net.i2p.router.web.helpers.SAMDebugHelper samHelper = new net.i2p.router.web.helpers.SAMDebugHelper();
                samHelper.setContextId(i2pcontextId);
                samHelper.storeWriter(out);
                out.write(samHelper.getSAMDHelper());
            } else {
                out.print("<p class=infohelp>SAM bridge is not running.</p>\n");
            }
        }
    }
%>
<% if (dd.equals("8")) { %>
<script src=/js/debugOverview.js type=module></script>
<% } else if (dd.equals("7")) { %>
<script src=/js/samDebug.js type=module></script>
<% } %>
</div>
</body>
</html>