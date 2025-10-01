<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<%  final String consoleNonce = net.i2p.router.web.CSSHelper.getNonce(); %>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("service logs")%>
</head>
<body id=i2plogs>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>"/>
<%  int errorCount = logsHelper.getCriticalLogCount(); %>
<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Logs")%></h1>
<div class=main id=logs>
<div class=confignav>
<%   %>
<span class=tab><a href=/routerlogs><%=intl._t("Router")%></a></span>
<span class=tab2><span><%=intl._t("Service")%></span></span>
<span class=tab><a href=/errorlogs><%=intl._t("Errors")%><span id=errorCount hidden><%=errorCount%></span></a></span>
<span class=tab><a href="/events?from=604800"><%=intl._t("Events")%></a></span>
</div>
<h3 class=tabletitle id=servicelogs><%=intl._t("Service (Wrapper) Logs")%>
<%  StringBuilder buf = new StringBuilder(24*1024);
    // timestamp, last line number, escaped filename
    Object[] vals = logsHelper.getServiceLogs(buf);
    String lts = vals[0].toString();
    long llast = ((Long) vals[1]).longValue();
    String filename = vals[2].toString();
    if (llast >= 0) {
%>
&nbsp;<a class=delete title="<%=intl._t("Clear logs")%>" href="logs?svc=<%=llast%>&amp;svct=<%=lts%>&amp;svcf=<%=filename%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
</h3>
<table id=wrapperlogs class=logtable>
<tbody><tr><td><% out.append(buf);%></td></tr></tbody>
</table>
</div>
<script nonce=<%=cspNonce%> type=module src=/js/refreshLogs.js></script>
<noscript><style>#toggleRefresh,#refreshPeriod,#logFilter{display:none!important}</style></noscript>
</body>
</html>