<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<%@ page buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="head.jsi" %>
<%=intl.title("job queue stats")%>
</head>
<body id=routerjobqueue>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %><h1 class=sched><%=intl._t("Job Queue")%></h1>
<div class=main id=jobs>
<div class=confignav>
<span class=tab title="<%=intl._t("Job statistics for this session")%>"><a href="/jobs"><%=intl._t("Job Stats")%></a></span>
<span class=tab2 title="<%=intl._t("Active and scheduled jobs")%>"><%=intl._t("Job Queue")%></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request" />
<jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=i2pcontextId%>" />
<% jobQueueHelper.storeWriter(out); %>
<jsp:getProperty name="jobQueueHelper" property="jobQueueSummary" />
</div>
<script src="/js/refreshElements.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce=<%=cspNonce%>>refreshElements("#jobs", "/jobqueue", 15000);</script>
</body>
</html>