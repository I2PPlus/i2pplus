<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("job queue stats")%>
</head>
<body id=routerjobqueue>
<%@include file="sidebar.jsi"%><h1 class=sched><%=intl._t("Job Queue")%></h1>
<div class=main id=jobs>
<div class=confignav>
<span class=tab title="<%=intl._t("Job statistics for this session")%>"><a href="/jobs"><%=intl._t("Job Stats")%></a></span>
<span class=tab2 title="<%=intl._t("Active and scheduled jobs")%>"><%=intl._t("Job Queue")%></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request"/>
<jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=i2pcontextId%>"/>
<% jobQueueHelper.storeWriter(out);%>
<jsp:getProperty name="jobQueueHelper" property="jobQueueSummary"/>
</div>
<script src=/js/refreshElements.js type=module></script>
<script src=/js/jobs.js></script>
<script nonce=<%=cspNonce%> type=module>
  import {refreshElements} from "/js/refreshElements.js";
  refreshElements("#jobs", "/jobqueue", 5000);
</script>
</body>
</html>