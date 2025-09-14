<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="64kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<style id=gwrap></style>
<%=intl.title("graphs")%>
<jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request"/>
<jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="graphHelper" property="*"/>
<%  graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
    boolean allowRefresh = intl.allowIFrame(request.getHeader("User-Agent"));
    if (allowRefresh) {out.print(graphHelper.getRefreshMeta());}
    out.print("\n");
%>
<script nonce=<%=cspNonce%>>
  window.graphRefreshInterval = <% out.print(graphHelper.getRefreshValue() * 1000); %>;
  var graphCount = <% out.print(graphHelper.countGraphs()); %>;
</script>
</head>
<body id=perfgraphs>
<%@include file="sidebar.jsi"%>
<h1 class=perf><%=intl._t("Performance Graphs")%></h1>
<div class=main id=graphs>
<div class=widepanel>
<jsp:getProperty name="graphHelper" property="allMessages"/>
<div class=graphspanel id=allgraphs hidden><jsp:getProperty name="graphHelper" property="images"/></div>
<div id=graphConfigs hidden><jsp:getProperty name="graphHelper" property="form"/></div>
</div>
</div>
<script src=/js/lazyload.js></script>
<script src="/js/graphs.js?<%=net.i2p.CoreVersion.VERSION%>" type=module></script>
<noscript><style>#allgraphs,#gform,#graphConfigs{display:block!important}#graphdisplay{margin-bottom:15px!important;color:var(--ink)!important;cursor:default!important}</style></noscript>
</body>
</html>