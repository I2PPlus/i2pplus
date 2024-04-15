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
<%=intl.title("tunnel summary")%>
</head>
<body id=routertunnels>
<script nonce=<%=cspNonce%>>progressx.show("<%=theme%>");progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<h1 class=netwrk><%=intl._t("Tunnel Summary")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="Locally hosted tunnels (exploratory and client)"><a href="/tunnels">Local</a></span>
<span class=tab><a href="/tunnelsparticipating">Participating</a></span>
<span class=tab2>Tunnel Count by Peer</span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request" />
<jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>" />
<% tunnelHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelHelper" property="tunnelSummary" />
</div>
<script nonce=<%=cspNonce%>>window.addEventListener("DOMContentLoaded", progressx.hide);</script>
</body>
</html>