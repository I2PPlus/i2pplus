<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("job queue")%>
</head>
<body id="routerjobs">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %><h1 class="sched"><%=intl._t("Job Queue")%></h1>
<div class="main" id="jobs">
<jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request" />
<jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=i2pcontextId%>" />
<% jobQueueHelper.storeWriter(out); %>
<jsp:getProperty name="jobQueueHelper" property="jobQueueSummary" />
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var visibility = document.visibilityState;
  if (visibility == "visible") {
    setInterval(function() {
      progressx.show();
      progressx.progress(0.5);
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/jobs?' + new Date().getTime(), true);
      xhr.responseType = "document";
      xhr.onreadystatechange = function () {
        if (xhr.readyState==4 && xhr.status==200) {
          var jobs = document.getElementById("jobs");
          var jobsResponse = xhr.responseXML.getElementById("jobs");
          var jobsParent = jobs.parentNode;
            if (!Object.is(jobs.innerHTML, jobsResponse.innerHTML))
              jobsParent.replaceChild(jobsResponse, jobs);
        }
      }
      window.addEventListener("pageshow", progressx.hide());
      xhr.send();
    }, 15000);
  }
  window.addEventListener("pageshow", progressx.hide());
</script>
<%@include file="summaryajax.jsi" %>
</body>
</html>