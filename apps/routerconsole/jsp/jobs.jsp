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
<%@include file="summaryajax.jsi" %>
<%=intl.title("job queue statistics")%>
<link href=/themes/console/tablesort.css rel=stylesheet type=text/css>
</head>
<body id=routerjobs>
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %><h1 class=sched><%=intl._t("Job Queue Stats")%></h1>
<div class=main id=jobs>
<div class=confignav>
<span class=tab2 title="<%=intl._t("Job statistics for this session")%>"><%=intl._t("Job Stats")%></span>
<span class=tab title="<%=intl._t("Active and scheduled jobs")%>"><a href="/jobqueue"><%=intl._t("Job Queue")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.JobQueueHelper" id="jobQueueHelper" scope="request" />
<jsp:setProperty name="jobQueueHelper" property="contextId" value="<%=i2pcontextId%>" />
<% jobQueueHelper.storeWriter(out); %>
<jsp:getProperty name="jobQueueHelper" property="jobQueueStats" />
</div>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.dotsep.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" src=/js/tablesort/tablesort.number.js type=text/javascript></script>
<script nonce="<%=cspNonce%>" type=text/javascript>
  var stats = document.getElementById("jobstats");
  var tbody = document.getElementById("statCount");
  var tfoot = document.getElementById("statTotals");
  var sorter = new Tablesort((stats), {descending: true});
  var xhr = new XMLHttpRequest();
  progressx.hide();
  var visibility = document.visibilityState;
  if (visibility === "visible") {
    setInterval(function() {
      xhr.open('GET', '/jobs?t=' + new Date().getTime(), true);
      xhr.responseType = "document";
      xhr.onreadystatechange = function () {
        if (xhr.readyState === 4 && xhr.status === 200) {
          var tbodyResponse = xhr.responseXML.getElementById("statCount");
          var tfootResponse = xhr.responseXML.getElementById("statTotals");
          if (tbody.innerHTML !== tbodyResponse.innerHTML) {
            tbody.innerHTML = tbodyResponse.innerHTML;
            tfoot.innerHTML = tfootResponse.innerHTML;
            sorter.refresh();
          }
        }
      }
      progressx.hide();
      sorter.refresh();
      xhr.send();
    }, 10000);
  }
  window.addEventListener("DOMContentLoaded", progressx.hide(), true);
  stats.addEventListener("beforeSort", function() {progressx.show();progressx.progress(0.5);}, true);
  stats.addEventListener("afterSort", function() {progressx.hide();}, true);
</script>
</body>
</html>