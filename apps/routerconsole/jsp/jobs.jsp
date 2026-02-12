<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("job queue statistics")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body id=routerjobs>
<%@include file="sidebar.jsi"%><h1 class=sched><%=intl._t("Job Queue Stats")%></h1>
<div class=main id=jobs>
<div class=confignav>
<span class=tab2 title="<%=intl._t("Job statistics for this session")%>"><%=intl._t("Job Stats")%></span>
<span class=tab title="<%=intl._t("Active and scheduled jobs")%>"><a href=/jobqueue><%=intl._t("Job Queue")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request"/>
<jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="jobQueueHelper" property="requestURI" value='<%=request.getRequestURI() + (request.getQueryString() != null ? "?" + request.getQueryString() : "")%>'/>
<% jobQueueHelper.storeWriter(out);%>
<jsp:getProperty name="jobQueueHelper" property="jobQueueStats"/>
</div>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script src=/js/jobs.js type=module></script>
</body>
</html>