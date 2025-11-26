<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("Jar File Dump")%>
</head>
<body>
<%@include file="sidebar.jsi"%><h1 class="conf adv debug">Jar File Dump</h1>
<div class=main id=jardump>
<jsp:useBean class="net.i2p.router.web.helpers.FileDumpHelper" id="dumpHelper" scope="request"/>
<jsp:setProperty name="dumpHelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:getProperty name="dumpHelper" property="fileSummary"/>
</div>
</body>
</html>