<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("statistics")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.OldConsoleHelper" id="oldhelper" scope="request" />
<jsp:setProperty name="oldhelper" property="contextId" value="<%=i2pcontextId%>" />
<% oldhelper.storeWriter(out); %>
<jsp:setProperty name="oldhelper" property="full" value="<%=request.getParameter(\"f\")%>" />
<h1 class="perf"><%=intl._t("Router Statistics")%></h1>
<div class=main id="stats">
<jsp:getProperty name="oldhelper" property="stats" />
</div>
<script nonce="<%=cspNonce%>" type=text/javascript>window.addEventListener("DOMContentLoaded", progressx.hide());</script>
</body>
</html>