<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="128kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("events")%>
<jsp:useBean class="net.i2p.router.web.helpers.EventLogHelper" id="eventHelper" scope="request"/>
<jsp:setProperty name="eventHelper" property="contextId" value="<%=i2pcontextId%>"/>
<% /* GraphHelper sets the defaults in setContextId, so setting the properties must be after the context */%>
 <jsp:setProperty name="eventHelper" property="*"/>
<%
    eventHelper.storeWriter(out);
    eventHelper.storeMethod(request.getMethod());
%>

<link href=/themes/console/tablesort.css rel=stylesheet>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Event Log")%></h1>
<div class=main id=events>
<div class=confignav>
<span class=tab><a href=/routerlogs><%=intl._t("Router")%></a></span>
<span class=tab><a href=/servicelogs><%=intl._t("Service")%></a></span>
<span class=tab><a href=/logs><%=intl._t("Combined")%></a></span>
<span class=tab2><span><%=intl._t("Events")%></span></span>
</div>
<div class=eventspanel>
<div class=widepanel>
<jsp:getProperty name="eventHelper" property="allMessages"/>
<jsp:getProperty name="eventHelper" property="form"/>
<jsp:getProperty name="eventHelper" property="events"/>
</div>
</div>
</div>
<script src=/js/tablesort/tablesort.js></script>
<script src=/js/tablesort/tablesort.number.js></script>
<script src=/js/tablesort/tablesort.date.js></script>
<script nonce=<%=cspNonce%>>new Tablesort(document.getElementById("eventlog"));</script>
<script src=/js/lazyload.js></script>

</body>
</html>