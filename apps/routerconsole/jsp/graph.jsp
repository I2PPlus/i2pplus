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
<%@include file="summaryajax.jsi" %>
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
<script nonce="<%=cspNonce%>" src="/js/progressx.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
</head>
<body id=perfgraphs>
<script nonce=<%=cspNonce%>>progressx.show("<%=theme%>");progressx.progress(0.1);</script>
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
<h1 class=perf><%=intl._t("Performance Graph")%></h1>
<div class=main id=graph_single>
<jsp:getProperty name="graphHelper" property="singleStat" />
</div>
<script nonce=<%=cspNonce%>>
  var graph = document.getElementById("single");
  var graphImage = document.getElementById("graphSingle");
  var graphWidth = graphImage.naturalWidth;
  var graphHeight = graphImage.height;
  var graphcss = document.querySelector("#graphcss");
  var imgSrc = graphImage.src;

  function injectCss() {
    graphImage.addEventListener("load", function() {
      if (document.head.contains(graphcss)) {
        if (graphcss) {graphcss.remove();}
      }
      var s = document.createElement("style");
      s.setAttribute("id", "graphcss");
      if (graphWidth !== 0) {var w = graphWidth + 4;}
      else {var w = graphImage.width + 4;}
      if (graphHeight !== 0) {var h = graphHeight + 4;}
      else {var h = graphImage.height + 4;}
      s.innerHTML = ".graphContainer{width: " + w + "px;height: " + h + "px}";
      document.head.appendChild(s);
    });
  }
  function initCss() {
    injectCss();
    if (timer) {clearInterval(timer);}
    else if (!graphcss) {var timer = function() {setInterval(injectCss, 500)};}
  }
  initCss();
<%
    if (graphHelper.getRefreshValue() > 0) {
%>
  var visibility = document.visibilityState;
  if (graph && visibility == "visible") {
    setInterval(function() {
      progressx.show("<%=theme%>");
      var graphURL = imgSrc + "&t=" + Date.now();
      var xhrgraph = new XMLHttpRequest();
      xhrgraph.open('GET', graphURL, true);
      xhrgraph.responseType = "document";
      xhrgraph.onreadystatechange = function () {
        if (xhrgraph.readyState==4 && xhrgraph.status==200) {
          graphImage.setAttribute('src', graphURL);
          graphImage.addEventListener("load", initCss());
        }
        progressx.hide();
      }
      xhrgraph.send();
    }, <% out.print(graphHelper.getRefreshValue() * 1000); %>);
  }
<%  } %>
  window.addEventListener("DOMContentLoaded", progressx.hide);
</script>
</body>
</html>