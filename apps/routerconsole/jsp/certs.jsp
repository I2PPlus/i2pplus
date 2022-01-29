<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
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