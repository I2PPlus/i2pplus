<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%@ page buffer="16kb" %>
<!DOCTYPE HTML>
<%
/*
 * All links in the summary bar must have target=_top
 * so they don't load in the iframe
 */
%>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="head.jsi" %>
<title>Sidebar - I2P+</title>
<%
    // try hard to avoid an error page in the iframe after shutdown
    String action = request.getParameter("action");
    String d = request.getParameter("refresh");
    // Normal browsers send value, IE sends button label
    boolean allowIFrame = intl.allowIFrame(request.getHeader("User-Agent"));
    boolean shutdownSoon = (!allowIFrame) ||
                           "shutdownImmediate".equals(action) || "restartImmediate".equals(action) ||
                           "Shutdown immediately".equals(action) || "Restart immediately".equals(action);
    if (!shutdownSoon) {
        if (d == null || "".equals(d)) {
            // set below
        } else if (net.i2p.router.web.CSSHelper.getNonce().equals(conNonceParam)) {
            d = net.i2p.data.DataHelper.stripHTML(d);  // XSS
            intl.setRefresh(d);
            intl.setDisableRefresh(d);
        }
        d = intl.getRefresh();
        // we probably don't get here if d == "0" since caught in summary.jsi, but just
        // to be sure...
        if (!intl.getDisableRefresh()) {
            // doesn't work for restart or shutdown with no expl. tunnels,
            // since the call to ConfigRestartBean.renderStatus() hasn't happened yet...
            // So we delay slightly
            if (action != null &&
                ("restart".equals(action.toLowerCase(java.util.Locale.US)) || "shutdown".equals(action.toLowerCase(java.util.Locale.US)))) {
                synchronized(this) {
                    try {
                        wait(1000);
                    } catch(InterruptedException ie) {}
                }
            }
            long timeleft = net.i2p.router.web.helpers.ConfigRestartBean.getRestartTimeRemaining();
            long delay = 60;
            try { delay = Long.parseLong(d); } catch (NumberFormatException nfe) {}
            if (delay*1000 < timeleft + 5000)
                out.print("<meta http-equiv=\"refresh\" content=\"" + delay + ";url=/summaryframe.jsp\" >\n");
            else
                shutdownSoon = true;
        }
    }
%>
</head>
<body style=margin:0>
<div class=sb id=sidebar>
<jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
<jsp:setProperty name="newshelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    java.io.File newspath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
<jsp:setProperty name="newshelper" property="page" value="<%=newspath.getAbsolutePath()%>" />
<jsp:setProperty name="newshelper" property="maxLines" value="300" />
<%@include file="summarynoframe.jsi" %>
</div>
</body>
</html>