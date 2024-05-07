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
<body id=perfgraphs>
<script nonce=<%=cspNonce%>>progressx.show("<%=theme%>");progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=perf><%=intl._t("Performance Graphs")%></h1>
<div class=main id=graphs>
<div class=widepanel>
<jsp:getProperty name="graphHelper" property="allMessages" />
<div class=graphspanel id=allgraphs>
<jsp:getProperty name="graphHelper" property="images" />
</div>
<jsp:getProperty name="graphHelper" property="form" />
</div>
</div>
<script nonce=<%=cspNonce%>>
  var main = document.getElementById("perfgraphs");
  var graph = document.getElementsByClassName("statimage")[0];
  var visibility = document.visibilityState;
  function injectCss() {
    graph.addEventListener("load", function() {
      var graphWidth = graph.width;
      var graphHeight = graph.height;
      var sheet = window.document.styleSheets[0];
      sheet.insertRule(".graphContainer {width: " + (graphWidth + 4) + "px; height: " + (graphHeight + 4) + "px;}", sheet.cssRules.length);
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
  function updateGraphs() {
    progressx.show("<%=theme%>");
    var graphs = document.getElementById("allgraphs");
    var nographs = document.getElementById("nographs");
    var images = document.getElementsByClassName("statimage");
    var totalImages = images.length;
    var imagesLoaded = 0;
    for (var i = 0; i < totalImages; i++) {
      var image = images[i];
      var imageUrl = image.getAttribute('src');

      (function(image, imageUrl) {
        var xhr = new XMLHttpRequest();
        xhr.open("GET", imageUrl + "?t=" + Date.now(), true);
        xhr.onload = function () {
          if (xhr.status == 200) {
            image.setAttribute("src", imageUrl + "?t=" + Date.now());
            imagesLoaded++;
            if (imagesLoaded === totalImages) {progressx.hide();}
          }
        };
        xhr.send();
      })(image, imageUrl);
     }
  }

  function isDown() {
    var images = document.getElementsByClassName("statimage");
    var totalImages = images.length;
    if (!images.length) {
      graphs.innerHTML = "<span id=nographs><b>No connection to Router<\/b><\/span>";
      progressx.hide();
    }
  }

  setTimeout(isDown, 60000);

  var refresh = <% out.print(graphHelper.getRefreshValue()); %>;
  var refreshInterval = refresh * 1000;
  var timerId = setInterval(updateGraphs, refreshInterval);
<%
    }
%>
  var config = document.getElementById("gform");
  var toggle = document.getElementById("toggleSettings");
  var h3 = document.getElementById("graphdisplay");
  var sb = document.getElementById("sidebar");
  toggle.hidden = true;
  function toggleView() {
    if (toggle.checked === false) {
      config.hidden = true;
      if (h3.classList.contains("visible")) {
        h3.classList.remove("visible");
      }
    } else {
      config.hidden = false;
      h3.classList.add("visible");
      document.getElementById("gwidth").focus();
      if (sb !== null && sb.scrollHeight < document.body.scrollHeight) {
        setTimeout(() => {window.scrollTo({top: document.body.scrollHeight, behavior: "smooth"});}, 500);
      }
    }
  }
  toggleView();
  toggle.addEventListener("click", toggleView);
  window.addEventListener("DOMContentLoaded", progressx.hide);
</script>
<noscript><style>#gform{display:block!important}#graphdisplay{margin-bottom:15px!important;color:var(--ink)!important;cursor:default!important}</style></noscript>
</body>
</html>