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
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body id=routerjobs>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
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
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.dotsep.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%>>
  const jobs = document.getElementById("jobstats");
  const sorter = new Tablesort((jobs), {descending: true});
  const xhrjobs = new XMLHttpRequest();
  progressx.hide();
  const visibility = document.visibilityState;
  if (visibility === "visible") {
    setInterval(function() {
      xhrjobs.open('GET', '/jobs', true);
      xhrjobs.responseType = "document";
      xhrjobs.onload = function () {
        if (!xhrjobs.responseXML) {
          //alert("Your browser doesn't support AJAX. Please upgrade to a newer browser or enable support for XHR requests.");
          return;
        }
        const jobsResponse = xhrjobs.responseXML.getElementById("jobstats");
        const rows = document.querySelectorAll("#statCount tr");
        const rowsResponse = xhrjobs.responseXML?.querySelectorAll("#statCount tr");
        const tbody = document.getElementById("statCount");
        const tbodyResponse = xhrjobs.responseXML.getElementById("statCount");
        const tfoot = document.getElementById("statTotals");
        const tfootResponse = xhrjobs.responseXML.getElementById("statTotals");
        const updatingTds = document.querySelectorAll("#statCount td");
        const updatingTdsResponse = xhrjobs.responseXML?.querySelectorAll("#statCount td");
        let updated = false;
        if (!Object.is(jobs.innerHTML, jobsResponse.innerHTML)) {
          if (rows.length !== rowsResponse.length) {
            tbody.innerHTML = tbodyResponse.innerHTML;
            tfoot.innerHTML = tfootResponse.innerHTML;
            updated = true;
          } else {
            Array.from(updatingTds).forEach((elem, index) => {
              elem.classList.remove("updated");
              if (elem.innerHTML !== "<span hidden>[0.]</span>0" && elem.innerHTML !== updatingTdsResponse[index].innerHTML) {
                elem.innerHTML = updatingTdsResponse[index].innerHTML;
                elem.classList.add("updated");
                updated = true;
              }
            });
            if (tfoot.innerHTML !== tfootResponse.innerHTML) {
              tfoot.innerHTML = tfootResponse.innerHTML;
            }
          }
          sorter.refresh();
        }
      }
      progressx.hide();
      xhrjobs.send();
    }, 10000);
  }
  jobs.addEventListener("beforeSort", function() {progressx.show(theme);progressx.progress(0.5);});
  jobs.addEventListener("afterSort", function() {progressx.hide();});
</script>
</body>
</html>