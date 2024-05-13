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
<link rel=stylesheet href=/themes/console/tunnels.css>
<%=intl.title("local tunnels")%>
</head>
<body id=routertunnels>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Local Tunnels")%></h1>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request" />
<jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    boolean isAdvanced = tunnelHelper.isAdvanced();
    if (isAdvanced) {
%>
<div class="main isAdvanced" id=tunnels>
<%  } else { %>
<div class=main id=tunnels>
<%  } %>
<div class=confignav>
<span class=tab2 title="Locally hosted tunnels (exploratory and client)">Local</span>
<span class=tab><a href="/transit"><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab><a href="/transitfast"><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href="/transitsummary"><%=intl._t("Transit by Peer")%></a></span>
<span class=tab><a href="/tunnelpeercount">Tunnel Count by Peer</a></span>
<span id=toggleTunnels></span>
</div>
<span id=tunnelsContainer>
<% tunnelHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</span>
</div>
<noscript><style>#toggleTunnels{display:none}</style></noscript>
<script nonce=<%=cspNonce%> src=/js/tunnels.js></script>
</body>
</html>