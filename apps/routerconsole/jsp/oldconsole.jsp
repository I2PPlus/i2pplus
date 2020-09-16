<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation.
   */
%>
<html>
<head>
<title>Old Console - I2P+</title>
<%@include file="css.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.OldConsoleHelper" id="conhelper" scope="request" />
<jsp:setProperty name="conhelper" property="contextId" value="<%=i2pcontextId%>" />
<% conhelper.storeWriter(out); %>
 <h1 class="conf adv">Old Console</h1>
<div class="main" id="oldconsole">
<p><jsp:getProperty name="conhelper" property="console" /></p>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
