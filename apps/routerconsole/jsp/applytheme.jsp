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
<%=intl.title("config UI")%>
<style type="text/css">input.default {width: 1px; height: 1px; visibility: hidden;}</style>
<script src="/js/ajax.js" type="text/javascript"></script>
<meta http-equiv="Refresh" content= "0;URL=/configui">
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1 class="conf"><%=uihelper._t("User Interface Configuration")%></h1>
<div class="main" id="config_ui">
<%@include file="confignav.jsi" %>
<p class="infohelp">Applying Console theme preferences&hellip; You should be returned to the <a href="/configui"><%=uihelper._t("User Interface Configuration")%></a> shortly.</a></p>
<div style="display: none;">
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<form action="" method="POST">
<input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="blah" >
</div>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>