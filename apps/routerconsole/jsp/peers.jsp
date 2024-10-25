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
<%=intl.title("peer connections")%>
<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.PeerHelper" id="peerHelper" scope="request" />
<jsp:setProperty name="peerHelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp" />
<jsp:setProperty name="peerHelper" property="transport" value="<%=request.getParameter(\"transport\")%>" />
<jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter(\"sort\") != null ? request.getParameter(\"sort\") : \"\"%>" />
<%
    String req = request.getParameter("transport");
    if (req == null) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%></h1>
<%
    } else if (req.equals("ntcp")) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; NTCP</h1>
<%
    } else if (req.equals("ssu")) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; SSU</h1>
<%
    } else if (req.equals("ssudebug")) {
%>
<h1 class=netwrk><%=intl._t("Network Peers")%> &ndash; SSU (<%=intl._t("Advanced")%>)</h1>
<%
    }
%>
<div class=main id=peers>
<%
    peerHelper.storeWriter(out);
    if (allowIFrame)
        peerHelper.allowGraphical();
%>
<jsp:getProperty name="peerHelper" property="peerSummary" />
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.natural.js></script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%> src=/themes/js/peers.js></script>
</body>
</html>