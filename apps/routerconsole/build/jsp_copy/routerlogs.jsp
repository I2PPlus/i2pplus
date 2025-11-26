<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("router logs")%>
</head>
<body id=routerlogs>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>"/>
<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Logs")%></h1>
<div class=main id=logs>
<div class=confignav>
<%  int errorCount = logsHelper.getCriticalLogCount(); %>
<span class=tab2><span><%=intl._t("Router")%></span></span>
<span class=tab><a href=/servicelogs><%=intl._t("Service")%></a></span>
<span class=tab><a href=/errorlogs><%=intl._t("Errors")%><span id=errorCount hidden><%=errorCount%></span></a></span>
<span class=tab><a href="/events?from=604800"><%=intl._t("Events")%></a></span>
</div>
<%  final String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
    StringBuilder buf = new StringBuilder(24*1024);
    int last = logsHelper.getLastMessageNumber();
    final String clearParam = request.getParameter("clear");
    final String nonceParam = request.getParameter("consoleNonce");
    if (clearParam != null && nonceParam != null && consoleNonce.equals(nonceParam)) {
        int iclear = -1;
        try {iclear = Integer.parseInt(clearParam);}
        catch (NumberFormatException nfe) {}
        logsHelper.clearThrough(iclear, -1, -1, -1, null, nonceParam);
        response.sendRedirect("routerlogs");
        return;
    }
%>
<div class=logwrap>
<h3 class=tabletitle id=routerlogs_h3><%=intl._t("Router Logs")%>
<%  if (last >= 0) { %>
&nbsp;<a class=delete title="<%=intl._t("Clear logs")%>" href="/routerlogs?clear=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
&nbsp;<a class=configure title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a>
&nbsp;<a id=eventlogLink title="<%=intl._t("View event log")%>" href="/events?from=604800">[<%=intl._t("Events")%>]</a>
&nbsp;<span id=toggleRefresh></span>
&nbsp;<span id=logFilter title="<%=intl._t("Filter Router log output")%>"><input type=text id=logFilterInput></span></h3>
<table id=routerlogs class="logtable single"><tbody><tr><td><jsp:getProperty name="logsHelper" property="logs"/></td></tr></tbody></table>
</div>
</div>
<script nonce=<%=cspNonce%> type=module src=/js/refreshLogs.js></script>
<noscript><style>#toggleRefresh,#refreshPeriod,#logFilter{display:none!important}</style></noscript>
</body>
</html>