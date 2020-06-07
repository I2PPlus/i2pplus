<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%@include file="csp-unsafe.jsi" %>
<%=intl.title("graphs")%>
 <jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request" />
 <jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
 <jsp:setProperty name="graphHelper" property="*" />
<%
    graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
    // meta must be inside the head
    boolean allowRefresh = intl.allowIFrame(request.getHeader("User-Agent"));
    if (allowRefresh) {
        out.print(graphHelper.getRefreshMeta());
    }
%>
<%@include file="summaryajax.jsi" %>
</head>
<body onload="initCss();" id="perfgraphs">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<%
    // needs to be after the summary bar is rendered, so
    // that the restart button is processed
    String stat = request.getParameter("stat");
    if (stat == null) {
        // probably because restart or shutdown was clicked
        response.setStatus(307);
        response.setHeader("Location", "/graphs");
        // force commitment
        response.getWriter().close();
        return;
    }
%>
<h1 class="perf"><%=intl._t("Performance Graph")%></h1>
<div class="main" id="graph_single">
 <jsp:getProperty name="graphHelper" property="singleStat" />
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var main = document.getElementById("perfgraphs");
  var graph = document.getElementById("graphSingle");
  function injectCss() {
    main.addEventListener("mouseover", function() {
      var graphWidth = graph.naturalWidth;
      var graphHeight = graph.height;
      var sheet = window.document.styleSheets[0];
<%
    if (graphHelper.getGraphHiDpi()) {
%>
    sheet.insertRule(".graphContainer#hidpi {width: " + ((graphWidth / 2) + 8) + "px; height: " + ((graphHeight / 2) + 8) + "px;}", sheet.cssRules.length);
<%
    } else {
%>
    sheet.insertRule(".graphContainer {width: " + (graphWidth + 4) + "px; height: " + (graphHeight + 4) + "px;}", sheet.cssRules.length);
<%
    }
%>
  });
  function initCss() {
    if (graph != null || graphWidth != graph.naturalWidth || graphHeight != graph.height) {
      injectCss();
    } else {
      location.reload(true);
    }
  }
<%
    if (graphHelper.getRefreshValue() > 0) {
%>
  setInterval(function() {
    var graphURL = window.location.href + "&" + new Date().getTime();
    var xhr = new XMLHttpRequest();
    xhr.open('GET', graphURL, true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      initCss();
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("perfgraphs").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
  }, <% out.print(graphHelper.getRefreshValue() * 1000); %>);
<%  } %>
</script>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
