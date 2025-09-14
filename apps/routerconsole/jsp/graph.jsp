<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("graph")%>
<jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request"/>
<jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="graphHelper" property="*"/>
<%  graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
    boolean allowRefresh = intl.allowIFrame(request.getHeader("User-Agent"));
    if (allowRefresh) {out.print(graphHelper.getRefreshMeta());}
%>
<script nonce="<%=cspNonce%>">var graphRefreshInterval=<% out.print(graphHelper.getRefreshValue()*1000); %>;</script>
<script src="/js/graphSingle.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id=perfgraphs>
<%@include file="sidebar.jsi"%>
<%  String stat=request.getParameter("stat");
    if (stat==null) {
        response.setStatus(307);
        response.setHeader("Location","/graphs");
        response.getWriter().close();
        return;
    }
%>
<h1 class=perf><%=intl._t("Performance Graph")%></h1>
<div class=main id=graph_single>
<jsp:getProperty name="graphHelper" property="singleStat"/>
</div>
</body>
</html>