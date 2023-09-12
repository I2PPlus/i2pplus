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
<%=intl.title("events")%>
<jsp:useBean class="net.i2p.router.web.helpers.EventLogHelper" id="eventHelper" scope="request" />
<jsp:setProperty name="eventHelper" property="contextId" value="<%=i2pcontextId%>" />
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */ %>
 <jsp:setProperty name="eventHelper" property="*" />
<%
    eventHelper.storeWriter(out);
    eventHelper.storeMethod(request.getMethod());
%>

<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show();progressx.progress(0.5);</script>
<%@include file="summary.jsi" %>
<h1 class=log><%=intl._t("Event Log")%></h1>
<div class=main id=events>
<div class=eventspanel>
<div class=widepanel>
<jsp:getProperty name="eventHelper" property="allMessages" />
<jsp:getProperty name="eventHelper" property="form" />
<jsp:getProperty name="eventHelper" property="events" />
</div>
</div>
</div>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.number.js></script>
<script nonce=<%=cspNonce%> src=/js/tablesort/tablesort.date.js></script>
<script nonce=<%=cspNonce%>>new Tablesort(document.getElementById("eventlog"));</script>
<script nonce=<%=cspNonce%> src=/js/lazyload.js></script>
<script nonce=<%=cspNonce%>>window.addEventListener("DOMContentLoaded", progressx.hide());</script>
</body>
</html>