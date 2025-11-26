<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="4kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<meta http-equiv=refresh content="0;URL=/configui">
<body hidden>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request"/>
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>"/>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<form method=POST>
<input type=hidden name=consoleNonce value="<%=net.i2p.router.web.CSSHelper.getNonce()%>">
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=action value=blah >
</body>
</html>