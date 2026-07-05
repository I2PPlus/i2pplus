<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="4kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="graphHelper" scope="request"/>
<jsp:setProperty name="graphHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:setProperty name="graphHelper" property="*"/>
<%  graphHelper.storeWriter(out);
    graphHelper.storeMethod(request.getMethod());
%>
<meta http-equiv=refresh content="0;URL=/graphs">
</head>
<body hidden>
<jsp:getProperty name="graphHelper" property="allMessages"/>
</body>
</html>