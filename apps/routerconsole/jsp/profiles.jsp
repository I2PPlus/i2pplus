<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
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
    if (allowIFrame) {profilesHelper.allowGraphical();}
%>
<jsp:getProperty name="profilesHelper" property="summary" />
</div>
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%> src=/js/profiles.js></script>
<style>.wideload{height:unset!important;opacity:1!important}#profiles::before{display:none}</style>
</body>
</html>