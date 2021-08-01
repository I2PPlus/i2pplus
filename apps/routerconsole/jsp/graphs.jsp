<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
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
    graph.addEventListener("load", function() {
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
      graph.addEventListener("load", injectCss());
    } else {
      location.reload(true);
    }
  }
  graph.addEventListener("load", initCss());
<%
    if (graphHelper.getRefreshValue() > 0) {
%>
  function updateGraphs(timestamp) {
    progressx.show();
    var graphs = document.getElementById("allgraphs");
    var nographs = document.getElementById("nographs");
    var xhrgraphs = new XMLHttpRequest();
    xhrgraphs.open('GET', '/graphs?t=' + new Date().getTime(), true);
    xhrgraphs.responseType = "document";
    xhrgraphs.onreadystatechange = function () {
      if (xhrgraphs.readyState==4) {
        if (xhrgraphs.status==200) {
          if (nographs)
            nographs.outerHTML = allgraphs.outerHTML;
          var graphsResponse = xhrgraphs.responseXML.getElementById("allgraphs");
          var graphsParent = graphs.parentNode;
          graphsParent.replaceChild(graphsResponse, graphs);
          } else {
            function isDown() {
              if (!nographs)
                graphs.innerHTML = "<span id=\'nographs\'><b>No connection to Router<\/b><\/span>";
            }
            setTimeout(isDown, 5000);
          }
      }
    }
    window.addEventListener("pageshow", progressx.hide());
    graph.addEventListener("load", initCss());
    xhrgraphs.send();
  }

  var visibility = document.visibilityState;
  if (visibility == "visible") {
    setTimeout(function refresh() {
      window.requestAnimationFrame(updateGraphs);
      setTimeout(refresh, <% out.print(graphHelper.getRefreshValue() * 1000); %>);
    }, <% out.print(graphHelper.getRefreshValue() * 1000); %>);
  }
<%
    }
%>
  window.addEventListener("pageshow", progressx.hide());
</script>
<%@include file="summaryajax.jsi" %>
</body>
</html>