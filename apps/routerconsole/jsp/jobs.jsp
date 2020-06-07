<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("job queue")%>
<%@include file="summaryajax.jsi" %>
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
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/jobs?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("routerjobs").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
  }, 15000);
</script>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
