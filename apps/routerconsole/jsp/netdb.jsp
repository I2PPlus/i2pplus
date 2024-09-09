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
<%=intl.title("network database")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.NetDbHelper" id="formhandler" scope="request" />
<jsp:setProperty name="formhandler" property="full" value="<%=request.getParameter(\"f\")%>" />
<jsp:setProperty name="formhandler" property="router" value="<%=request.getParameter(\"r\")%>" />
<jsp:setProperty name="formhandler" property="lease" value="<%=request.getParameter(\"l\")%>" />
<jsp:setProperty name="formhandler" property="version" value="<%=request.getParameter(\"v\")%>" />
<%  if (request.getParameter("cc") != null) { %>
<jsp:setProperty name="formhandler" property="country" value="<%=request.getParameter(\"cc\")%>" />
<%  } else { %>
<jsp:setProperty name="formhandler" property="country" value="<%=request.getParameter(\"c\")%>" />
<%  } %>
<jsp:setProperty name="formhandler" property="family" value="<%=request.getParameter(\"fam\")%>" />
<jsp:setProperty name="formhandler" property="caps" value="<%=request.getParameter(\"caps\")%>" />
<jsp:setProperty name="formhandler" property="ip" value="<%=request.getParameter(\"ip\")%>" />
<jsp:setProperty name="formhandler" property="sybil" value="<%=request.getParameter(\"sybil\")%>" />
<jsp:setProperty name="formhandler" property="sybil2" value="<%=request.getParameter(\"sybil2\")%>" />
<jsp:setProperty name="formhandler" property="port" value="<%=request.getParameter(\"port\")%>" />
<jsp:setProperty name="formhandler" property="type" value="<%=request.getParameter(\"type\")%>" />
<jsp:setProperty name="formhandler" property="ipv6" value="<%=request.getParameter(\"ipv6\")%>" />
<jsp:setProperty name="formhandler" property="cost" value="<%=request.getParameter(\"cost\")%>" />
<jsp:setProperty name="formhandler" property="mtu" value="<%=request.getParameter(\"mtu\")%>" />
<jsp:setProperty name="formhandler" property="ssucaps" value="<%=request.getParameter(\"ssucaps\")%>" />
<jsp:setProperty name="formhandler" property="transport" value="<%=request.getParameter(\"tr\")%>" />
<jsp:setProperty name="formhandler" property="limit" value="<%=request.getParameter(\"ps\")%>" />
<jsp:setProperty name="formhandler" property="page" value="<%=request.getParameter(\"pg\")%>" />
<jsp:setProperty name="formhandler" property="mode" value="<%=request.getParameter(\"m\")%>" />
<jsp:setProperty name="formhandler" property="date" value="<%=request.getParameter(\"date\")%>" />
<jsp:setProperty name="formhandler" property="leaseset" value="<%=request.getParameter(\"ls\")%>" />
<%
    String c = request.getParameter("c");
    String f = request.getParameter("f");
    String l = request.getParameter("l");
    String ls = request.getParameter("ls");
    String r = request.getParameter("r");
    if (f == null && l == null && ls == null && r == null) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%></h1>
<%
    } else if (f != null) {
        if (f.equals("1") || f.equals("2")) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("All Routers")%></h1>
<%
        } else if (f.equals("3")) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("Sybil Analysis")%></h1>
<%
        } else if (f.equals("4")) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("Advanced Lookup")%></h1>
<%
        }
    } else if (f == null) {
        if (r != null && r.equals(".")) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("Local Router")%></h1>
<%
        } else if (r != null) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("Router Lookup")%></h1>
<%
        } else if (r == null && ls != null) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("LeaseSet Lookup")%></h1>
<%
        } else if (r == null && ls == null && l != null) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("LeaseSets")%></h1>
<%
        } else if (r == null && ls == null && l == null && c != null) {
%>
<h1 class=netwrk><%=intl._t("Network Database")%> &ndash; <%=intl._t("Routers")%></h1>
<%
        } else {
%>
<h1 class=netwrk><%=intl._t("Network Database")%></h1>
<%
        }
    }
%>
<div class=main id=netdb>
<%
    formhandler.storeWriter(out);
    if (allowIFrame)
        formhandler.allowGraphical();
%>
<%@include file="formhandler.jsi" %>
 <jsp:getProperty name="formhandler" property="floodfillNetDbSummary" />
</div>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%>>
  var countries = document.getElementById("netdbcountrylist");
  var ccsorter = countries !== null ? new Tablesort(countries, {descending: true}) : null;
  function initRefresh() {
    const url = window.location.href;
    if (!url.includes("?c") && !url.includes("?f") && !url.includes("?l") && !url.includes("?ls") && !url.includes("?n") && !url.includes("?r")) {
      setInterval(updateNetDb, 30000);
    }
  }
  function updateNetDb() {
    var xhrnetdb = new XMLHttpRequest();
    xhrnetdb.open('GET', '/netdb', true);
    xhrnetdb.responseType = "document";
    xhrnetdb.onload = function () {
      const congestion = document.getElementById("netdbcongestion");
      const congestionResponse = xhrnetdb.responseXML.getElementById("netdbcongestion");
      const cclist = document.getElementById("cclist");
      const overview = document.getElementById("netdboverview");
      const overviewResponse = xhrnetdb.responseXML.getElementById("netdboverview");
      const tiers = document.getElementById("netdbtiers");
      const tiersResponse = xhrnetdb.responseXML.getElementById("netdbtiers");
      const transports = document.getElementById("netdbtransports");
      const transportsResponse = xhrnetdb.responseXML.getElementById("netdbtransports");
      const versions = document.getElementById("netdbversions");
      const versionsResponse = xhrnetdb.responseXML.getElementById("netdbversions");
      if (congestion !== null && congestion.innerHTML !== congestionResponse.innerHTML) {
        congestion.innerHTML = congestionResponse.innerHTML;
      }
      if (countries && cclist) {
        if (typeof ccsorter === "undefined" || ccsorter === null) {
          const ccsorter = new Tablesort(countries, {descending: true});
        }
        const cclistResponse = xhrnetdb.responseXML.getElementById("cclist");
        if (cclist.innerHTML !== cclistResponse.innerHTML) {
          cclist.innerHTML = cclistResponse.innerHTML;
          ccsorter.refresh();
        }
      } else if (versions) {
          overview.innerHTML = overviewResponse.innerHTML;
      }
      if (tiers !== null && tiers.innerHTML !== tiersResponse.innerHTML) {
        tiers.innerHTML = tiersResponse.innerHTML;
      }
      if (transports !== null && transports.innerHTML !== transportsResponse.innerHTML) {
        transports.innerHTML = transportsResponse.innerHTML;
      }
      if (versions !== null && versions.innerHTML !== versionsResponse.innerHTML) {
        versions.innerHTML = versionsResponse.innerHTML;
      }
    }
    if (typeof ccsorter === "undefined" || ccsorter === null) {
      const ccsorter = new Tablesort(countries, {descending: true});
    }
    if (countries) {ccsorter.refresh();}
    xhrnetdb.send();
  }
  document.addEventListener("DOMContentLoaded", initRefresh);
  window.addEventListener("DOMContentLoaded", progressx.hide);
  if (countries) {
    countries.addEventListener("beforeSort", function() {progressx.show(theme);progressx.progress(0.5);});
    countries.addEventListener("afterSort", function() {progressx.hide();});
  }
</script>
</body>
</html>
