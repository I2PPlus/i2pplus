<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("health")%>
</head>
<body id=routerhealth>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("Router Health")%></h1>
<jsp:useBean class="net.i2p.router.web.helpers.HealthHelper" id="healthHelper" scope="request"/>
<jsp:setProperty name="healthHelper" property="contextId" value="<%=i2pcontextId%>"/>
<div class=main id=healthPage>
<div id=healthWrap>
<% healthHelper.storeWriter(out);%>
<jsp:getProperty name="healthHelper" property="healthContent"/>
</div>
</div>
<script src=/js/refreshElements.js type=module></script>
<script nonce=<%=cspNonce%> type=module>
  import {refreshElements} from "/js/refreshElements.js";
  refreshElements("#healthWrap", "/health", 5000);
</script>
</body>
</html>
