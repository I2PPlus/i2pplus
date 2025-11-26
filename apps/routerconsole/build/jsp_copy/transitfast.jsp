<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("Fastest Transit Tunnels")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
<link rel=prefetch href=/tunnelpeercount>
</head>
<body id=transitFast>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Fastest Transit Tunnels")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href=/tunnels>Local</a></span>
<span class=tab><a href=/transit><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab2><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href=/transitsummary><%=intl._t("Transit by Peer")%></a></span>
<span class=tab><a href=tunnelpeercount><%=intl._t("Tunnel Count by Peer")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingFastestHelper" id="tunnelParticipatingFastestHelper" scope="request"/>
<jsp:setProperty name="tunnelParticipatingFastestHelper" property="contextId" value="<%=i2pcontextId%>"/>
<% tunnelParticipatingFastestHelper.storeWriter(out);%>
<jsp:getProperty name="tunnelParticipatingFastestHelper" property="tunnelParticipatingFastest"/>
</div>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script src=/js/lazyload.js type=module></script>
<script src=/js/transitfast.js type=module></script>
</body>
</html>