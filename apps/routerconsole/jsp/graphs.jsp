<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<html lang="<%=lang%>">
<head>
<%@include file="head.jsi" %>
<style id=gwrap></style>
<%=intl.title("graphs")%>
<jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request" />
<jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
<jsp:setProperty name="graphHelper" property="*" />
<%
    graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
    boolean allowRefresh = intl.allowIFrame(request.getHeader("User-Agent")); // meta must be inside the head
    if (allowRefresh) {out.print(graphHelper.getRefreshMeta());}
    out.print("\n");
%>
<script nonce=<%=cspNonce%>>
  window.graphRefreshInterval = <% out.print(graphHelper.getRefreshValue() * 1000); %>;
  var graphCount = <% out.print(graphHelper.countGraphs()); %>;
</script>
</head>
<body id=perfgraphs>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=perf><%=intl._t("Performance Graphs")%></h1>
<div class=main id=graphs>
<div class=widepanel>
<jsp:getProperty name="graphHelper" property="allMessages" />
<div class=graphspanel id=allgraphs hidden>
<jsp:getProperty name="graphHelper" property="images" />
</div>
<span id=graphConfigs hidden><jsp:getProperty name="graphHelper" property="form" /></span>
</div>
</div>
<script src=/js/lazyload.js></script>
<script src="/js/graphs.js?<%=net.i2p.CoreVersion.VERSION%>" type=module></script>
<noscript><style>#allgraphs,#gform,#graphConfigs{display:block!important}#graphdisplay{margin-bottom:15px!important;color:var(--ink)!important;cursor:default!important}</style></noscript>
</body>
</html>