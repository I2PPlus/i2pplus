<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("tunnel peer count")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script src=/js/tablesort/tablesort.natural.js type=module></script>
<script src=/js/lazyload.js type=module></script>
<script src=/js/tunnelpeercount.js type=module></script>
</head>
<body id=routertunnels>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Tunnel Count by Peer")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href=/tunnels>Local</a></span>
<span class=tab><a href=/transit><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab><a href=/transitfast><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href=/transitsummary><%=intl._t("Transit by Peer")%></a></span>
<span class=tab2><%=intl._t("Tunnel Count by Peer")%></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelPeerCountHelper" id="tunnelPeerCountHelper" scope="request"/>
<jsp:setProperty name="tunnelPeerCountHelper" property="contextId" value="<%=i2pcontextId%>"/>
<% tunnelPeerCountHelper.storeWriter(out);%>
<jsp:getProperty name="tunnelPeerCountHelper" property="tunnelPeerCount"/>
</div>
</body>
</html>