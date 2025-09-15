<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("statistics")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.StatHelper" id="stathelper" scope="request"/>
<jsp:setProperty name="stathelper" property="contextId" value="<%=i2pcontextId%>"/>
<% stathelper.storeWriter(out); %>
<jsp:setProperty name="stathelper" property="full" value="<%=request.getParameter(\"f\")%>"/>
<h1 class=perf><%=intl._t("Router Statistics")%></h1>
<div class=main id=stats>
<jsp:getProperty name="stathelper" property="stats"/>
</div>
<script src=/js/stats.js></script>
</body>
</html>
