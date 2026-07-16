<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("health")%>
<jsp:useBean class="net.i2p.router.web.helpers.HealthHelper" id="healthHelper" scope="request"/>
<jsp:setProperty name="healthHelper" property="contextId" value="<%=i2pcontextId%>"/>
</head>
<body id=routerhealth<%=healthHelper.isFloodfill() ? " class=floodfill" : ""%>>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Router Health")%></h1>
<div class=main id=health>
<div class=wrap>
<h2 class="toggle-head expanded"><%=intl._t("Performance & Load")%></h2>
<div class=ring-section id=hsPerf><div class=ring-grid id=hsPerfRings><jsp:getProperty name="healthHelper" property="perfRings"/></div></div>
</div>

<div class=wrap>
<h2 class="toggle-head expanded"><%=intl._t("Transport & Connectivity")%></h2>
<div class=ring-section id=hsTransport><div class=ring-grid id=hsTransportRings><jsp:getProperty name="healthHelper" property="transportRings"/></div></div>
</div>

<div class=wrap>
<h2 class="toggle-head expanded"><%=intl._t("Network & Participation")%></h2>
<div class=ring-section id=hsNetwork><div class=ring-grid id=hsNetworkRings><jsp:getProperty name="healthHelper" property="networkRings"/></div></div>
</div>

<% if (healthHelper.isFloodfill()) { %>
<div class=wrap>
<h2 class="toggle-head expanded"><%=intl._t("Floodfill / NetDB")%></h2>
<div class=ring-section id=hsFF><div class=ring-grid id=hsFFRings><jsp:getProperty name="healthHelper" property="FFRings"/></div></div>
</div>
<% } %>
</div>
<script src=/js/toggleElements.js></script>
<script src=/js/refreshElements.js type=module></script>
<script nonce=<%=cspNonce%>>
  document.addEventListener("DOMContentLoaded", () => {
    setupToggles(".toggle-head", ".ring-section", "block", false, false);
  });
</script>
<script nonce=<%=cspNonce%> type=module>
  import {refreshElements} from "/js/refreshElements.js";
  var targets = "#hsPerfRings, #hsTransportRings, #hsNetworkRings";
  <% if (healthHelper.isFloodfill()) { %>targets += ", #hsFFRings";<% } %>
  refreshElements(targets, "/health", 5000);
</script>
</body>
</html>
