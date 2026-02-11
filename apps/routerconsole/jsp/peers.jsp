<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("peer connections")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.PeerHelper" id="peerHelper" scope="request"/>
<jsp:setProperty name="peerHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp"/>
<jsp:setProperty name="peerHelper" property="transport" value="<%=request.getParameter(\"transport\")%>"/>
<jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter(\"sort\") != null ? request.getParameter(\"sort\") : \"\"%>"/>
<%  String req = request.getParameter("transport");
    if (req == null) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%></h1>
<%  } else if (req.equals("ntcp")) { %>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; NTCP</h1>
<%  } else if (req.equals("ssu")) { %>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; SSU</h1>
<%  } else if (req.equals("ssudebug")) { %>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; SSU (<%=intl._t("Advanced")%>)</h1>
<%  } %>
<div class=main id=peers>
<%  peerHelper.storeWriter(out);
    if (allowIFrame) {peerHelper.allowGraphical();}
%>
<jsp:getProperty name="peerHelper" property="peerSummary"/>
</div>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script src=/js/lazyload.js></script>
<script src=/js/peers.js type=module></script>
</body>
</html>