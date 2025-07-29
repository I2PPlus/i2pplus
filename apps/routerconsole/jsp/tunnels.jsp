<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<link rel=stylesheet href=/themes/console/tunnels.css>
<%=intl.title("local tunnels")%>
</head>
<body id=routertunnels>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=netwrk><%=intl._t("Local Tunnels")%></h1>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelHelper" id="tunnelHelper" scope="request"/>
<jsp:setProperty name="tunnelHelper" property="contextId" value="<%=i2pcontextId%>"/>
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
<span id=toggleTunnelIds title="<%=intl._t("Toggle Tunnel Ids")%>"></span><span id=toggleTunnels title="<%=intl._t("Toggle Tunnels")%>"></span>
</div>
<div id=tunnelsContainer hidden>
<% tunnelHelper.storeWriter(out); %>
<jsp:getProperty name="tunnelHelper" property="tunnelSummary"/>
</div>
</div>
<noscript><style>#toggleTunnels,#toggleTunnelIds{display:none}#tunnelsContainer{display:block}</style></noscript>
<script src=/js/tunnels.js type=module></script>
</body>
</html>