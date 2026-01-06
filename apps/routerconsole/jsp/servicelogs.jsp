<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<%
   String contextId = request.getParameter("i2p.contextId");
   if (contextId == null) {contextId = (String) session.getAttribute("i2p.contextId");}
   logsHelper.setContextId(contextId);
   final String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
   final String svcParam = request.getParameter("svc");
   final String svctParam = request.getParameter("svct");
   final String svcfParam = request.getParameter("svcf");
   final String nonceParam = request.getParameter("consoleNonce");
   if (svcParam != null && svctParam != null && svcfParam != null && nonceParam != null) {
        try {
            long svc = Long.parseLong(svcParam);
            long svct = Long.parseLong(svctParam);
            String svcf = svcfParam;
            logsHelper.clearThrough(-1, -1, svc, svct, svcf, nonceParam);
            response.sendRedirect("servicelogs");
            return;
        } catch (NumberFormatException nfe) {}
   }
   StringBuilder buf = new StringBuilder(24*1024);
   Object[] vals = logsHelper.getServiceLogs(buf);
   String lts = vals[0].toString();
   long llast = ((Long) vals[1]).longValue();
   String filename = vals[2].toString();
   int errorCount = logsHelper.getCriticalLogCount();
%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("service logs")%>
</head>
<body id=servicelogs>
<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Logs")%></h1>
<div class=main id=logs>
<div class=confignav>
<span class=tab><a href=/routerlogs><%=intl._t("Router")%></a></span>
<span class=tab2><span><%=intl._t("Service")%></span></span>
<span class=tab><a href=/errorlogs><%=intl._t("Errors")%><span id=errorCount hidden><%=errorCount%></span></a></span>
<span class=tab><a href="/events?from=604800"><%=intl._t("Events")%></a></span>
</div>
<div class=logwrap>
<h3 class=tabletitle id=servicelogs><%=intl._t("Service (Wrapper) Logs")%>
<%  if (lts != null && !lts.trim().isEmpty()) { %>
&nbsp;<a class=delete title="<%=intl._t("Clear logs")%>" href="/servicelogs?svc=<%=llast%>&amp;svct=<%=lts%>&amp;svcf=<%=filename%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
</h3>
<table id=wrapperlogs class="logtable single"><tbody><tr><td><% out.append(buf);%></td></tr></tbody></table>
</div>
</div>
<script nonce=<%=cspNonce%> type=module src=/js/refreshLogs.js></script>
<noscript><style>#toggleRefresh,#refreshPeriod,#logFilter{display:none!important}</style></noscript>
</body>
</html>