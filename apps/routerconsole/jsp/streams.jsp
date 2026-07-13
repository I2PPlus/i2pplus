<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("streaming connections")%>
<link rel=stylesheet href=/themes/console/tunnels.css>
<link rel=stylesheet href=/themes/console/tablesort.css>
</head>
<body id=routerstreams>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Streaming Connections")%></h1>
<jsp:useBean class="net.i2p.router.web.helpers.StreamHelper" id="streamHelper" scope="request"/>
<jsp:setProperty name="streamHelper" property="contextId" value="<%=i2pcontextId%>"/>
<%  String direction = request.getParameter("direction");
    if (direction == null) direction = "";
    streamHelper.setDirection(direction); %>
<div class=main id=activeStreams>
<div class=confignav>
<% if (direction.isEmpty()) { %>
<span class=tab2 title="<%=intl._t("All streaming connections")%>"><%=intl._t("All Streams")%></span>
<% } else { %>
<span class=tab><a href=/streams><%=intl._t("All Streams")%></a></span>
<% } %>
<% if ("inbound".equals(direction)) { %>
<span class=tab2 title="<%=intl._t("Inbound streaming connections")%>"><%=intl._t("Inbound")%></span>
<% } else { %>
<span class=tab><a href=/streams?direction=inbound><%=intl._t("Inbound")%></a></span>
<% } %>
<% if ("outbound".equals(direction)) { %>
<span class=tab2 title="<%=intl._t("Outbound streaming connections")%>"><%=intl._t("Outbound")%></span>
<% } else { %>
<span class=tab><a href=/streams?direction=outbound><%=intl._t("Outbound")%></a></span>
<% } %>
</div>
<div id=streamsWrap>
<% streamHelper.storeWriter(out);%>
<jsp:getProperty name="streamHelper" property="streamSummary"/>
</div>
</div>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script src=/js/streams.js type=module></script>
</body>
</html>
