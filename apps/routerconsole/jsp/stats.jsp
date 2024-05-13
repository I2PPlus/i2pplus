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
<%=intl.title("statistics")%>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.OldConsoleHelper" id="oldhelper" scope="request" />
<jsp:setProperty name="oldhelper" property="contextId" value="<%=i2pcontextId%>" />
<% oldhelper.storeWriter(out); %>
<jsp:setProperty name="oldhelper" property="full" value="<%=request.getParameter(\"f\")%>" />
<h1 class=perf><%=intl._t("Router Statistics")%></h1>
<div class=main id=stats>
<jsp:getProperty name="oldhelper" property="stats" />
</div>
<script nonce=<%=cspNonce%>>
  function initRefresh() {
    setInterval(updateStats, 60000);
  }
  function updateStats() {
    progressx.show(theme);
    progressx.progress(0.5);
    var xhrstats = new XMLHttpRequest();
    xhrstats.open('GET', '/stats', true);
    xhrstats.responseType = "document";
    xhrstats.onreadystatechange = function () {
      if (xhrstats.readyState==4 && xhrstats.status==200) {
        var info = document.getElementById("gatherstats");
        if (info) {
          var infoResponse = xhrstats.responseXML.getElementById("gatherstats");
          if (infoResponse && !Object.is(info.innerHTML, infoResponse.innerHTML)) {
            info.innerHTML = infoResponse.innerHTML;
          }
        }
        var statlist = document.getElementById("statlist");
        var statlistResponse = xhrstats.responseXML.getElementById("statlist");
        var statlistParent = statlist.parentNode;
        if (!Object.is(statlist.innerHTML, statlistResponse.innerHTML)) {
          statlistParent.replaceChild(statlistResponse, statlist);
        }
      }
    }
    xhrstats.send();
    progressx.hide();
  }
  var infohelp = document.querySelector("#gatherstats");
  var nav = document.querySelector(".confignav");
  var routerTab = document.getElementById("Router");
  var tabs = document.querySelectorAll(".togglestat");
  function initTabs() {
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].classList.remove("tab2");
    }
  }
  nav.addEventListener("click", function(element) {
    if (element.target.classList.contains("togglestat")) {
      if (infohelp) {infohelp.remove();}
      updateStats();
      initTabs();
      element.target.classList.add("tab2");
      progressx.hide();
    }
  });
  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    initTabs();
    progressx.hide();
  });
</script>
</body>
</html>