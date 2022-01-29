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
<%=intl.title("peer profiles")%>

<!-- tablesort.js https://github.com/tristen/tablesort/ -->
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.natural.js" type="text/javascript"></script>
<link href="/themes/console/tablesort.css" rel="stylesheet" type="text/css">
<!-- end tablesort.js -->
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Peer Profiles")%></h1>
<div class="main" id="profiles"><div class="wideload">
 <jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    profilesHelper.storeWriter(out);
    if (allowIFrame)
        profilesHelper.allowGraphical();
%>
 <jsp:setProperty name="profilesHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
 <jsp:getProperty name="profilesHelper" property="summary" />
</div>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var ff = document.getElementById("floodfills");
  if (ff) {
    new Tablesort(ff);
  }
</script>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type="text/javascript"></script>
<script nonce="<%=cspNonce%>" type="text/javascript">
  document.addEventListener("DOMContentLoaded", function() {
    setInterval(function() {
      progressx.show();
      var xhr = new XMLHttpRequest();
      xhr.responseType = "document";
      var plist = document.getElementById("profilelist");
      var pcontainer = document.getElementById("peerprofiles");
      var uri = (window.location.pathname + window.location.search).substring(1);
      if (!uri.includes("f=2") && !uri.includes("f=3")) {
        if (uri.includes("?"))
          xhr.open('GET', uri + '&t=' + new Date().getTime(), true);
        else
          xhr.open('GET', uri + '?t=' + new Date().getTime(), true);
        xhr.onreadystatechange = function () {
          if (xhr.readyState==4 && xhr.status==200) {
            var info = document.getElementById("profiles_overview");
            if (info) {
              var infoResponse = xhr.responseXML.getElementById("profiles_overview");
                info.innerHTML = infoResponse.innerHTML;
            }
            if (plist) {
              var plistResponse = xhr.responseXML.getElementById("profilelist");
                plist.outerHTML = plistResponse.outerHTML;
                lazyload();
            }
            var thresholds = document.getElementById("thresholds");
            if (thresholds) {
              var thresholdsResponse = xhr.responseXML.getElementById("thresholds");
                thresholds.innerHTML = thresholdsResponse.innerHTML;
            }
          }
        }
      }
      window.addEventListener("pageshow", progressx.hide());
      pcontainer.addEventListener("mouseover", lazyload());
      xhr.send();
    }, 15000);
  }, true);
</script>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>