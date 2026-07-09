<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="4kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.GraphHelper" id="formhandler" scope="request"/>
<% formhandler.storeSession(session); %>
<%@include file="formhandler.jsi"%>
<meta http-equiv=refresh content="0;URL=/graphs">
</head>
<body hidden></body>
</html>