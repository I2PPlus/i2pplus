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
<%=intl.title("peer profiles")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request" />
<jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="profilesHelper" property="full" value="<%=request.getParameter(\"f\")%>" />
<%
    String req = request.getParameter("f");
    if (req == null) {
%>
<h1 class=netwrk><%=intl._t("Peer Profiles")%></h1>
<%
    } else if (req.equals("4")) {
%>
<h1 class=netwrk><%=intl._t("Peer Profiles")%> &ndash; <%=intl._t("Session Banned Peers")%></h1>
<%
    } else if (req.equals("3")) {
%>
<h1 class=netwrk><%=intl._t("Peer Profiles")%> &ndash; <%=intl._t("Banned Peers")%></h1>
<%
    } else if (req.equals("2")) {
%>
<h1 class=netwrk><%=intl._t("Peer Profiles")%> &ndash; <%=intl._t("Floodfills")%></h1>
<%
    } else if (req.equals("1")) {
%>
<h1 class=netwrk><%=intl._t("Peer Profiles")%> &ndash; <%=intl._t("Fast / High Capacity")%></h1>
<%
    }
%>
<div class=main id=profiles>
<div class=wideload style=height:5px;opacity:0>
<%
    profilesHelper.storeWriter(out);
    if (allowIFrame)
        profilesHelper.allowGraphical();
%>
<jsp:getProperty name="profilesHelper" property="summary" />
</div>
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%>>
  var banlist = document.getElementById("banlist");
  var bbody = document.getElementById("sessionBanlist");
  var bfoot = document.getElementById("sessionBanlistFooter");
  var ff = document.getElementById("floodfills");
  var ffprofiles = document.getElementById("ffProfiles");
  var info = document.getElementById("profiles_overview");
  var main = document.getElementById("profiles");
  var pbody = document.getElementById("pbody");
  var plist = document.getElementById("profilelist");
  var thresholds = document.getElementById("thresholds");
  var refreshProfilesId = setInterval(refreshProfiles, 60000);
  var sessionBans = document.getElementById("sessionBanned");
  var uri = window.location.search.substring(1) !== null ? window.location.pathname + "?" + window.location.search.substring(1) : window.location.pathname;
  var sorterFF = null;
  var sorterP = null;
  var sorterBans = null;
  var xhrprofiles = new XMLHttpRequest();
  function initRefresh() {
    addSortListeners();
  }
  function addSortListeners() {
    if (ff && sorterFF === null) {
      sorterFF = new Tablesort((ff), {descending: true});
      ff.addEventListener('beforeSort', function() {progressx.show(theme);progressx.progress(0.5);});
      ff.addEventListener('afterSort', function() {progressx.hide();});
    } else if (plist && sorterP === null) {
      sorterP = new Tablesort((plist), {descending: true});
      plist.addEventListener('beforeSort', function() {progressx.show(theme);progressx.progress(0.5);});
      plist.addEventListener('afterSort', function() {progressx.hide();});
    } else if (sessionBans && sorterBans === null) {
      sorterBans = new Tablesort((sessionBans), {descending: false});
      sessionBans.addEventListener('beforeSort', function() {progressx.show(theme);progressx.progress(0.5);});
      sessionBans.addEventListener('afterSort', function() {progressx.hide();});
    }
  }
  function refreshProfiles() {
    if (uri.includes("?") && !uri.includes("f=3")) {
      xhrprofiles.open('GET', uri + '&t=' + Date.now(), true);
    } else if (!uri.includes("f=3")) {
      xhrprofiles.open('GET', uri + '', true);
    }
    xhrprofiles.responseType = "document";
    xhrprofiles.onreadystatechange = function () {
      if (xhrprofiles.readyState === 4 && xhrprofiles.status === 200 && !uri.includes("f=3")) {
        progressx.show(theme);
        if (info) {
          var infoResponse = xhrprofiles.responseXML.getElementById("profiles_overview");
          info.innerHTML = infoResponse.innerHTML;
        }
        if (plist) {
          addSortListeners();
          if (pbody) {
            var pbodyResponse = xhrprofiles.responseXML.getElementById("pbody");
            pbody.innerHTML = pbody.innerHTML;
            sorterP.refresh();
          } else {
            var plistResponse = xhrprofiles.responseXML.getElementById("profilelist");
            plist.innerHTML = plistResponse.innerHTML;
          }
        }
        if (thresholds) {
          var thresholdsResponse = xhrprofiles.responseXML.getElementById("thresholds");
          thresholds.innerHTML = thresholdsResponse.innerHTML;
        }
        if (ff) {
          addSortListeners();
          if (ffprofiles) {
            var ffprofilesResponse = xhrprofiles.responseXML.getElementById("ffProfiles");
            ffprofiles.innerHTML = ffprofilesResponse.innerHTML;
            sorterFF.refresh();
          } else {
            var ffResponse = xhrprofiles.responseXML.getElementById("floodfills");
            ff.innerHTML = ffResponse.innerHTML;
          }
        }
        if (sessionBans) {
          addSortListeners();
          if (bbody) {
              var bbodyResponse = xhrprofiles.responseXML.getElementById("sessionBanlist");
              var bfootResponse = xhrprofiles.responseXML.getElementById("sessionBanlistFooter");
              bbody.innerHTML = bbodyResponse.innerHTML;
              bfoot.innerHTML = bfootResponse.innerHTML;
              sorterBans.refresh();
          }
        }
        progressx.hide();
      }
    }
    if (ff || plist || sessionBans) {
      addSortListeners();
    }
    if (!uri.includes("f=3")) {
      xhrprofiles.send();
    }
  }
  document.addEventListener("DOMContentLoaded", () => {
    initRefresh();
    progressx.hide();
  });
</script>
<style>.wideload{height:unset!important;opacity:1!important}#profiles::before{display:none}</style>
</body>
</html>