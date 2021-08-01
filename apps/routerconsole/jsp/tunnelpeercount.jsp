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
<html lang="<%=lang%>" id="count">
<head>
<%@include file="css.jsi" %>
<%=intl.title("tunnel peer count")%>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.natural.js" type="text/javascript"></script>
</head>
<body id="routertunnels">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Tunnel Count by Peer")%></h1>
<div class="main" id="tunnels">
<div class="confignav">
<span class="tab" title="Locally hosted tunnels (exploratory and client)"><a href="/tunnels">Local</a></span>
<span class="tab"><a href="/tunnelsparticipating">Participating</a></span>
<span class="tab2">Tunnel Count by Peer</span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelPeerCountHelper" id="tunnelPeerCountHelper" scope="request" />
<jsp:setProperty name="tunnelPeerCountHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelPeerCountHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelPeerCountHelper" property="tunnelPeerCount" />
<script nonce="<%=cspNonce%>" type="text/javascript">new Tablesort(document.getElementById("tunnelPeerCount"));</script>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>