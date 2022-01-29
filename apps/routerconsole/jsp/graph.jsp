<%@page contentType="text/html"%>
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
  var graphcss = document.querySelector("#graphcss");
  var imgSrc = graphImage.src;

  function injectCss() {
    graphImage.addEventListener("load", function() {
      if (document.head.contains(graphcss))
        if (graphcss)
          graphcss.remove();
        var s = document.createElement("style");
        s.type="text/css";
        s.setAttribute("id", "graphcss");
<%
    if (graphHelper.getGraphHiDpi()) {
%>
        if (graphWidth !== 0)
          var w = graphWidth / 2 + 8;
        else
          var w = graphImage.width / 2 + 8;
        if (graphHeight !== 0)
          var h = graphHeight / 2 + 8;
        else
          var h = graphImage.height / 2 + 8;
        s.innerHTML = ".graphContainer#hidpi {width: " + w + "px; height: " + h + "px;}";
<%
    } else {
%>
        if (graphWidth !== 0)
          var w = graphWidth + 4;
        else
          var w = graphImage.width + 4;
        if (graphHeight !== 0)
          var h = graphHeight + 4;
        else
          var h = graphImage.height + 4;
        s.innerHTML = ".graphContainer {width: " + w + "px; height: " + h + "px;}";
<%
    }
%>
        document.head.appendChild(s);
    });
  }
  function initCss() {
      injectCss();
      if (timer)
        clearInterval(timer);
      else if (!graphcss)
        var timer = function() {setInterval(injectCss, 500)};
  }
  initCss();
<%
    if (graphHelper.getRefreshValue() > 0) {
%>
var visibility = document.visibilityState;
if (graph && visibility == "visible") {
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