<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<% if (!net.i2p.router.web.ContentOnly.isContentOnly(request)) { %>
<!DOCTYPE HTML>
<% } %>
<%@include file="head.jsi"%>
<% if (!net.i2p.router.web.ContentOnly.isContentOnly(request)) { %>
<%=intl.title("Most Recent Transit Tunnels")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
<script src=/js/tablesort/sortShared.js></script>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/transit.js type=module></script>
<link rel=prefetch href=/tunnelpeercount>
</head>
<% } %>
<jsp:useBean class="net.i2p.router.web.helpers.TunnelParticipatingHelper" id="tunnelParticipatingHelper" scope="request"/>
<jsp:setProperty name="tunnelParticipatingHelper" property="contextId" value="<%=i2pcontextId%>"/>
<%  tunnelParticipatingHelper.storeWriter(out);
    if (net.i2p.router.web.ContentOnly.isContentOnly(request)) {
        for (String id : net.i2p.router.web.ContentOnly.requestedIds(request)) {
            tunnelParticipatingHelper.renderFragment(id);
        }
    } else {
%>
<body id=transitRecent>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Most Recent Transit Tunnels")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href=/tunnels>Local</a></span>
<span class=tab2><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</span>
<span class=tab><a href=/transitfast><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><a href=/transitsummary><%=intl._t("Transit by Peer")%></a></span>
<span class=tab><a href=/tunnelpeercount>Tunnel Count by Peer</a></span>
</div>
<jsp:getProperty name="tunnelParticipatingHelper" property="tunnelsParticipating"/>
</div>
</body>
</html>
<%  } %>