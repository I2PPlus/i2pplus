<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("Certificates")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %><h1 class="conf adv"><%=intl._t("Certificates")%></h1>
<div class="main" id="certs">
<jsp:useBean class="net.i2p.router.web.helpers.CertHelper" id="certhelper" scope="request" />
<jsp:setProperty name="certhelper" property="contextId" value="<%=i2pcontextId%>" />
<% certhelper.storeWriter(out); %>
<jsp:getProperty name="certhelper" property="summary" />
<span id="end"></span>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
