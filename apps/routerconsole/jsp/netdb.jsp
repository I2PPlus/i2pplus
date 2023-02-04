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
<link href="/themes/console/tablesort.css" rel=stylesheet type=text/css>
</head>
<body>
<script nonce="<%=cspNonce%>" type=text/javascript>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.NetDbHelper" id="formhandler" scope="request" />
<jsp:setProperty name="formhandler" property="full" value="<%=request.getParameter(\"f\")%>" />
<jsp:setProperty name="formhandler" property="router" value="<%=request.getParameter(\"r\")%>" />
<jsp:setProperty name="formhandler" property="lease" value="<%=request.getParameter(\"l\")%>" />
<jsp:setProperty name="formhandler" property="version" value="<%=request.getParameter(\"v\")%>" />
<jsp:setProperty name="formhandler" property="country" value="<%=request.getParameter(\"c\")%>" />
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
<h1 class="netwrk"><%=intl._t("Network Database")%></h1>
<%
    } else if (f != null) {
        if (f.equals("1") || f.equals("2")) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("All Routers")%></h1>
<%
        } else if (f.equals("3")) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("Sybil Analysis")%></h1>
<%
        } else if (f.equals("4")) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("Advanced Lookup")%></h1>
<%
        }
    } else if (f == null) {
        if (r != null && r.equals(".")) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("Local Router")%></h1>
<%
        } else if (r != null) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("Router Lookup")%></h1>
<%
        } else if (r == null && ls != null) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("LeaseSet Lookup")%></h1>
<%
        } else if (r == null && ls == null && l != null) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("LeaseSets")%></h1>
<%
        } else if (r == null && ls == null && l == null && c != null) {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%> &ndash; <%=intl._t("Routers")%></h1>
<%
        } else {
%>
<h1 class="netwrk"><%=intl._t("Network Database")%></h1>
<%
        }
    }
%>
<div class="main" id="netdb">
<%
    formhandler.storeWriter(out);
    if (allowIFrame)
        formhandler.allowGraphical();
%>
<%@include file="formhandler.jsi" %>
<jsp:getProperty name="formhandler" property="netDbSummary" />
</div>
<script nonce="<%=cspNonce%>" src="/js/lazyload.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" src="/js/tablesort/tablesort.number.js" type=text/javascript></script>
<script nonce="<%=cspNonce%>" type=text/javascript>
  var countries = document.getElementById("netdbcountrylist");
  if (countries) {new Tablesort(countries, {descending: true});}
  window.addEventListener("DOMContentLoaded", progressx.hide());
</script>
</body>
</html>