<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("router logs")%>
</head>
<body id=i2plogs>

<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Logs")%></h1>
<div class=main id=logs>
<div class=confignav>
<span class=tab2><span><%=intl._t("Router")%></span></span>
<span class=tab><a href=/servicelogs><%=intl._t("Service")%></a></span>
<span class=tab><a href=/errorlogs><%=intl._t("Errors")%></a></span>
<span class=tab><a href="/events?from=604800"><%=intl._t("Events")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>"/>
<%  final String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
    final String ct1 = request.getParameter("clear");
    final String ct2 = request.getParameter("crit");
    final String ct3 = request.getParameter("svc");
    final String ct4 = request.getParameter("svct");
    final String ct5 = request.getParameter("svcf");
    final String ctn = request.getParameter("consoleNonce");
    int last = logsHelper.getLastCriticalMessageNumber();
    boolean hasCritical = last >= 0;
%>

<h3 class=tabletitle id=routerlogs_h3><%=intl._t("Router Logs")%>
<%  last = logsHelper.getLastMessageNumber();
    if (last >= 0) {
%>
&nbsp;<a class=delete title="<%=intl._t("Clear logs")%>" href="logs?clear=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
&nbsp;<a class=configure title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a>
&nbsp;<a id=eventlogLink title="<%=intl._t("View event log")%>" href="/events?from=604800">[<%=intl._t("Events")%>]</a>
&nbsp;<span id=toggleRefresh></span>
&nbsp;<span id=logFilter title="<%=intl._t("Filter Router log output")%>"><input type=text id=logFilterInput></span></h3>
<table id=routerlogs class="logtable single">
<tbody><tr><td><jsp:getProperty name="logsHelper" property="logs"/></td></tr></tbody>
</table>

</div>
<script nonce=<%=cspNonce%> type=module src=/js/refreshLogs.js></script>
<noscript><style>#toggleRefresh,#refreshPeriod,#logFilter{display:none!important}</style></noscript>
</body>
</html>