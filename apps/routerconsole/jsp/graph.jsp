<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<%=intl.title("graph")%>
<jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request" />
<jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
<jsp:setProperty name="graphHelper" property="*" />
<%
    graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
    // meta must be inside the head
    boolean allowRefresh = intl.allowIFrame(request.getHeader("User-Agent"));
    if (allowRefresh) {out.print(graphHelper.getRefreshMeta());}
%>
<script nonce="<%=cspNonce%>">var graphRefreshInterval = <% out.print(graphHelper.getRefreshValue() * 1000); %>;</script>
<script nonce="<%=cspNonce%>" src="/js/graphSingle.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id=perfgraphs>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<%
    // needs to be after the summary bar is rendered, so that the restart button is processed
    String stat = request.getParameter("stat");
    if (stat == null) {
        // probably because restart or shutdown was clicked
        response.setStatus(307);
        response.setHeader("Location", "/graphs");
        // force commitment
        response.getWriter().close();
        return;
    }
%>
<h1 class=perf><%=intl._t("Performance Graph")%></h1>
<div class=main id=graph_single>
<jsp:getProperty name="graphHelper" property="singleStat" />
</div>
</body>
</html>