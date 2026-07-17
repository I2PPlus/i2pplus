<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="256kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("SAM Debug")%>
<link rel=stylesheet href=/themes/console/tunnels.css>
<link rel=stylesheet href=/themes/console/tablesort.css>
</head>
<body id=routerstreams>
<%@include file="sidebar.jsi"%>
<h1 class=netwrk><%=intl._t("SAM Bridge Debug")%></h1>
<jsp:useBean class="net.i2p.router.web.helpers.SAMDebugHelper" id="samDebugHelper" scope="request"/>
<jsp:setProperty name="samDebugHelper" property="contextId" value="<%=i2pcontextId%>"/>
<div class=main id=activeStreams>
<div class=confignav>
<a class=tab href=/debug>Debug</a>
</div>
<div id=streamsWrap>
<% samDebugHelper.storeWriter(out);%>
<jsp:getProperty name="samDebugHelper" property="SAMDHelper"/>
</div>
</div>
<script src=/js/tablesort/tablesort.js type=module></script>
<script src=/js/tablesort/tablesort.number.js type=module></script>
<script nonce=<%=cspNonce%> type=module>
  import {refreshElements} from "/js/refreshElements.js";
  refreshElements("#activeStreams", "/samdebug", 10000);
</script>
</body>
</html>
