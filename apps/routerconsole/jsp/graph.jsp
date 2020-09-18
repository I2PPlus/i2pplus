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
</head>
<body id="perfgraphs">
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
  var graph = document.getElementById("single");
  var graphImage = document.getElementById("graphSingle");
  var graphWidth = graphImage.naturalWidth;
  var graphHeight = graphImage.height;

  function injectCss() {
    window.addEventListener("load", function() {
      if (graphImage.width != graphWidth && graphcss)
        graphcss.parentNode.removeChild("graphcss");
      if (typeof graphcss == "undefined") {
        var s = document.createElement("style");
        s.type="text/css";
        s.setAttribute("id", "graphcss");
<%
    if (graphHelper.getGraphHiDpi()) {
%>
        var w = graphWidth / 2 + 8;
        var h = graphHeight / 2 + 8;
        s.innerHTML = ".graphContainer#hidpi {width: " + w + "px; height: " + h + "px;}";
<%
    } else {
%>
        var w = graphWidth + 4;
        var h = graphHeight + 4;
        s.innerHTML= ".graphContainer {width: " + w + "px; height: " + h + "px;}";
<%
    }
%>
        document.head.appendChild(s);
      }
    });
  }
  function initCss() {
      injectCss();
      if (timer) {
        clearInterval(timer);
      } else {
        var timer = function() {setInterval(injectCss, 500)};
      }
  }
  initCss();
<%
    if (graphHelper.getRefreshValue() > 0) {
%>
if (graph) {
  setInterval(function() {
    progressx.show();
    var graphURL = window.location.href + "&t=" + new Date().getTime();
    var xhrgraph = new XMLHttpRequest();
    xhrgraph.open('GET', graphURL, true);
    xhrgraph.responseType = "document";
    xhrgraph.onreadystatechange = function () {
      if (xhrgraph.readyState==4 && xhrgraph.status==200) {
        var graphResponse = xhrgraph.responseXML.getElementById("single");
        graph.innerHTML = graphResponse.innerHTML;
        graphImage.addEventListener("load", initCss());
      }
    }
    window.addEventListener("pageshow", progressx.hide());
    xhrgraph.send();
  }, <% out.print(graphHelper.getRefreshValue() * 1000); %>);
}
<%  } %>
</script>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
