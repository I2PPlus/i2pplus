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
<div class=main id=healthPage>
<h2 class="toggle-head"><%=intl._t("Performance & Load")%></h2>
<div class=ring-section id=hsPerf><div class=ring-grid id=hsPerfRings><jsp:getProperty name="healthHelper" property="perfRings"/></div></div>

<h2 class="toggle-head"><%=intl._t("Transport & Connectivity")%></h2>
<div class=ring-section id=hsTransport><div class=ring-grid id=hsTransportRings><jsp:getProperty name="healthHelper" property="transportRings"/></div></div>

<h2 class="toggle-head"><%=intl._t("Network & Participation")%></h2>
<div class=ring-section id=hsNetwork><div class=ring-grid id=hsNetworkRings><jsp:getProperty name="healthHelper" property="networkRings"/></div></div>

<% if (healthHelper.isFloodfill()) { %>
<h2 class="toggle-head"><%=intl._t("Floodfill / NetDB")%></h2>
<div class=ring-section id=hsFF><div class=ring-grid id=hsFFRings><jsp:getProperty name="healthHelper" property="FFRings"/></div></div>
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
<style>.ring-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:4px;text-align:center}.toggle-head{margin:8px 0 0;padding:4px;font-size:10pt;font-weight:700;text-align:center;border:var(--border);border-bottom:0;border-radius:4px 4px 0 0;background:var(--tab_section);cursor:pointer}.toggle-head:hover{color:var(--hover)}</style>
</body>
</html>
