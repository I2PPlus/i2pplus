<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    // Let's make this easy...
    // These are defined in Jetty 7 org.eclipse.jetty.server.Dispatcher,
    // and in Servlet 3.0 (Jetty 8) javax.servlet.RequestDispatcher,
    // just use the actual strings here to make it compatible with either
    Integer ERROR_CODE = (Integer) request.getAttribute("javax.servlet.error.status_code");
    String ERROR_URI = (String) request.getAttribute("javax.servlet.error.request_uri");
    String ERROR_MESSAGE = (String) request.getAttribute("javax.servlet.error.message");
    if (ERROR_CODE != null)
        response.setStatus(ERROR_CODE.intValue());
    else
        ERROR_CODE = Integer.valueOf(404);
    if (ERROR_URI != null)
        ERROR_URI = net.i2p.data.DataHelper.escapeHTML(ERROR_URI);
    else
        ERROR_URI = "";
    if (ERROR_MESSAGE != null)
        ERROR_MESSAGE = net.i2p.data.DataHelper.escapeHTML(ERROR_MESSAGE);
    else
        ERROR_MESSAGE = "Not Found";
    // If it can't find the iframe or viewtheme.jsp I wonder if the whole thing blows up...
%>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<link type="text/css" rel="stylesheet" href="<%=intl.getTheme(request.getHeader("User-Agent"))%>proxy.css">
<%=intl.title("Page Not Found")%>
<script nonce="<%=cspNonce%>" type="text/javascript">if (window.location !== window.top.location) {window.top.location = window.location;}</script>
</head>
<body id="console_404">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="err"><%=ERROR_CODE%>&nbsp;<%=ERROR_MESSAGE%></h1>
<div class="sorry console" id="warning">
<%=intl._t("Sorry! You appear to be requesting a non-existent Router Console page or resource.")%>
<hr>
<%=intl._t("Error 404")%>: <%=ERROR_URI%>&nbsp;<%=intl._t("not found")%>.
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>