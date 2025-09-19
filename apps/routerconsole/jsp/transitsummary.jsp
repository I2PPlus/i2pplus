<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("Transit Tunnels by Peer")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
<link rel=prefetch href=/tunnelpeercount>
<script src=/js/convertKBtoMB.js type=module></script>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script src=/js/tablesort/tablesort.natural.js type=module></script>
<script src=/js/tablesort/tablesort.dotsep.js type=module></script>
<script src=/js/lazyload.js type=module></script>
<script src=/js/transitsummary.js type=module></script>
</head>
<body id=transitbypeer>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Transit Tunnels by Peer")%></h1>
<div class=main id=tunnels>
<div class=confignav>
<span class=tab title="<%=intl._t("Locally hosted tunnels (exploratory and client)")%>"><a href=/tunnels>Local</a></span>
<span class=tab><a href=/transit><%=intl._t("Transit")%> (<%=intl._t("Most Recent")%>)</a></span>
<span class=tab><a href=/transitfast><%=intl._t("Transit")%>  (<%=intl._t("Fastest")%>)</a></span>
<span class=tab2 title="<%=intl._t("Top 50 peers by transit tunnel requests")%>"><%=intl._t("Transit by Peer")%></span>
<span class=tab><a href="/tunnelpeercount"><%=intl._t("Tunnel Count by Peer")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.TransitSummaryHelper" id="transitSummaryHelper" scope="request"/>
<jsp:setProperty name="transitSummaryHelper" property="contextId" value="<%=i2pcontextId%>"/>
<% transitSummaryHelper.storeWriter(out);%>
<jsp:getProperty name="transitSummaryHelper" property="transitSummary"/>
</div>
</body>
</html>