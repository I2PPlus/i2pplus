<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
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
</head>
<body id="perfgraphs">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="perf"><%=intl._t("Performance Graphs")%></h1>
<div class="main" id="graphs">
<div class="widepanel">
<jsp:getProperty name="graphHelper" property="allMessages" />
<div class="graphspanel" id="allgraphs">
<jsp:getProperty name="graphHelper" property="images" />
</div>
<jsp:getProperty name="graphHelper" property="form" />
</div>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var main = document.getElementById("perfgraphs");
  var graph = document.getElementsByClassName("statimage")[0];
  function injectCss() {
    main.addEventListener("mouseover", function() {
      var graphWidth = graph.width;
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
  }
  function initCss() {
    if (graph != null || graphWidth != graph.naturalWidth || graphHeight != graph.naturalHeight) {
      injectCss();
    } else {
      location.reload(true);
    }
  }
  initCss();
<%
    if (graphHelper.getRefreshValue() > 0) {
%>
  setInterval(function() {
    progressx.show();
    var graphs = document.getElementById("allgraphs");
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/graphs?' + new Date().getTime(), true);
    xhr.responseType = "document";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        var graphsResponse = xhr.responseXML.getElementById("allgraphs");
        var graphsParent = graphs.parentNode;
        graphsParent.replaceChild(graphsResponse, graphs);
      }
    }
    progressx.hide();
    initCss();
    xhr.send();
  }, <% out.print(graphHelper.getRefreshValue() * 1000); %>);

//  document.addEventListener("DOMContentLoaded", function() {
//    initCss();
//  }, true);
<%  } %>
</script>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
