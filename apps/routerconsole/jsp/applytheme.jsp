<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");} %>
<%@include file="head.jsi" %>
<%=intl.title("config UI")%>
<style>input.default{width:1px;height:1px;visibility:hidden}.confignav{display:none!important}</style>
<meta http-equiv="Refresh" content= "0;URL=/configui">
</head>
<body>
<%@include file="sidebar.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request"/>
<jsp:setProperty name="uihelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>"/>
<h1 class=conf><%=uihelper._t("User Interface Configuration")%></h1>
<div class=main id=config_ui>
<%@include file="confignav.jsi" %>
<p class=infohelp id=applytheme>Applying Console theme preferences&hellip; You should be returned to the <a href="/configui"><%=uihelper._t("User Interface Configuration")%></a> shortly.</a></p>
<div style=display:none>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi" %>
<form method=POST>
<input type=hidden name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>">
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=action value=blah >
</div>
</div>
</body>
</html>