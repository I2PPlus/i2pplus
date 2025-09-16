<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang", "en");
%>
<%@include file="head.jsi"%>
<title>Sidebar - I2P+</title>
<%  String action = request.getParameter("action");
    String d = request.getParameter("refresh");
    boolean allowIFrame = intl.allowIFrame(request.getHeader("User-Agent"));
    boolean shutdownSoon = !allowIFrame || "shutdownimmediate".equalsIgnoreCase(action) || "restartimmediate".equalsIgnoreCase(action) ||
                           "shutdown immediately".equalsIgnoreCase(action) || "restart immediately".equalsIgnoreCase(action);

    if (!shutdownSoon) {
        if (d != null && !d.isEmpty() && net.i2p.router.web.CSSHelper.getNonce().equals(conNonceParam)) {
            d = net.i2p.data.DataHelper.stripHTML(d);
            intl.setRefresh(d);
            intl.setDisableRefresh(d);
        }
        d = intl.getRefresh();
        if (!intl.getDisableRefresh()) {
            if (action != null && ("restart".equalsIgnoreCase(action) || "shutdown".equalsIgnoreCase(action))) {
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
            }
            long timeleft = net.i2p.router.web.helpers.ConfigRestartBean.getRestartTimeRemaining();
            long delay = 60;
            try { delay = Long.parseLong(d); } catch (NumberFormatException ignored) {}
            if (delay * 1000 < timeleft + 5000) { out.print("<meta http-equiv=refresh content=" + delay + ">\n");}
            else {shutdownSoon = true;}
        }
    }
%>
</head>
<body style=margin:0>
<div class=sb id=sidebar>
<jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request"/>
<jsp:setProperty name="newshelper" property="contextId" value="<%=i2pcontextId%>"/>
<% java.io.File newspath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");%>
<jsp:setProperty name="newshelper" property="page" value="<%=newspath.getAbsolutePath()%>"/>
<jsp:setProperty name="newshelper" property="maxLines" value="300"/>
<%@include file="sidebar_noframe.jsi"%>
</div>
</body>
</html>