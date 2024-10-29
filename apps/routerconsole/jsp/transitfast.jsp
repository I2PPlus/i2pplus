<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<html lang="<%=lang%>" id=participatingTunnels>
<head>
<%@include file="css.jsi" %>
<%@include file="summaryajax.jsi" %>
<%=intl.title("Fastest Transit Tunnels")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body id=transit>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Fastest Transit Tunnels")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href="/tunnels">Local</a></span>
<span class=tab><a href="/transit"><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab2><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href="/transitsummary"><%=intl._t("Transit by Peer")%></a></span>
<span class=tab><a href="/tunnelpeercount"><%=intl._t("All Tunnels by Peer")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingFastestHelper" id="tunnelParticipatingFastestHelper" scope="request" />
<jsp:setProperty name="tunnelParticipatingFastestHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelParticipatingFastestHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelParticipatingFastestHelper" property="tunnelParticipatingFastest" />
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.dotsep.js></script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%> type=module src=/js/transitfast.js></script>
</body>
</html>