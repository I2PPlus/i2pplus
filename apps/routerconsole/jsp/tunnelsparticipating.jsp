<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>" id="participatingTunnels">
<head>
<%@include file="css.jsi" %>
<%=intl.title("participating tunnels")%>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.natural.js" type="text/javascript"></script>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Participating Tunnels")%></h1>
<div class="main" id="tunnels">
<div class="confignav">
<span class="tab" title="Locally hosted tunnels (exploratory and client)"><a href="/tunnels">Local</a></span>
<span class="tab2">Participating</span>
<span class="tab"><a href="/tunnelpeercount">Tunnel Count by Peer</a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingHelper" id="tunnelParticipatingHelper" scope="request" />
<jsp:setProperty name="tunnelParticipatingHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    tunnelParticipatingHelper.storeWriter(out);
%>
<jsp:getProperty name="tunnelParticipatingHelper" property="tunnelsParticipating" />
<script nonce="<%=cspNonce%>" type="text/javascript">new Tablesort(document.getElementById("tunnels_part"));</script>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>