<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<%
   String contextId = request.getParameter("i2p.contextId");
   if (contextId == null) {contextId = (String) session.getAttribute("i2p.contextId");}
   logsHelper.setContextId(contextId);
   final String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
   final String critParam = request.getParameter("crit");
   final String nonceParam = request.getParameter("consoleNonce");
   int last = logsHelper.getLastCriticalMessageNumber();
   if (critParam != null && nonceParam != null) {
        int icrit = -1;
        try {icrit = Integer.parseInt(critParam);}
        catch (NumberFormatException nfe) {}
        if (consoleNonce.equals(nonceParam)) {
            logsHelper.clearThrough(-1, icrit, -1, -1, null, nonceParam);
            response.sendRedirect("errorlogs");
            return;
        }
   }
%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("router logs")%>
</head>
<body id=errorlogs>
<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Logs")%></h1>
<div class=main id=logs>
<div class=confignav>
<%  int errorCount = logsHelper.getCriticalLogCount(); %>
<span class=tab><a href=/routerlogs><%=intl._t("Router")%></a></span>
<span class=tab><a href=/servicelogs><%=intl._t("Service")%></a></span>
<span class=tab2><span><%=intl._t("Errors")%></span></span>
<span class=tab><a href="/events?from=604800"><%=intl._t("Events")%></a></span>
</div>
<div class=logwrap>
<h3 id=critLogsHead class=tabletitle><%=intl._t("Critical / Error Level Logs")%>
<%  if (last >= 0) { %>
&nbsp;<a id=clearCritical class=delete title="<%=intl._t("Clear logs")%>" href="/errorlogs?crit=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
</h3>
<table id=criticallogs class="logtable single"><tbody><tr><td><jsp:getProperty name="logsHelper" property="criticalLogs"/></td></tr></tbody></table>
</div>
</div>
<script nonce=<%=cspNonce%> type=module src=/js/refreshLogs.js></script>
<noscript><style>#toggleRefresh,#refreshPeriod,#logFilter{display:none!important}</style></noscript>
</body>
</html>