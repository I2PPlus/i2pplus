<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<%@page import="net.i2p.data.DataHelper"%><%
    String exportParam = request.getParameter("export");
    String statFilter = request.getParameter("stat");
    boolean hasStatFilter = statFilter != null && !statFilter.isEmpty();
    if ("csv".equals(exportParam)) {
        response.setContentType("text/csv; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"i2p-stats.csv\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        net.i2p.router.web.helpers.StatHelper sh = new net.i2p.router.web.helpers.StatHelper();
        sh.setContextId(request.getParameter("i2p.contextId"));
        sh.storeWriter(out);
        if (hasStatFilter) sh.setStatFilter(statFilter);
        sh.setExport(exportParam);
        out.print(sh.getExportData());
        return;
    }
%><!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("statistics")%>
</head>
<body<%= hasStatFilter ? " class=filtered" : "" %>>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.StatHelper" id="stathelper" scope="request"/>
<jsp:setProperty name="stathelper" property="contextId" value="<%=i2pcontextId%>"/>
<% stathelper.storeWriter(out); %>
<jsp:setProperty name="stathelper" property="full" value="<%=request.getParameter(\"f\")%>"/>
<jsp:setProperty name="stathelper" property="statFilter" value="<%=request.getParameter(\"stat\")%>"/>
<h1 class=perf><%=intl._t("Router Statistics")%></h1>
<div class=main id=stats>
<jsp:getProperty name="stathelper" property="stats"/>
</div>
<script src=/js/stats.js></script>
</body>
</html>
