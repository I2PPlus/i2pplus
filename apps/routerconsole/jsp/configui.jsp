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
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHelper" id="uihelper" scope="request" />
<jsp:setProperty name="uihelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=uihelper._t("User Interface Configuration")%></h1>
<div class="main" id="config_ui">
 <%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigUIHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<h3 id="themeheading"><%=uihelper._t("Router Console Theme")%></h3>
<form action="/applytheme" method="POST">
<input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="blah" >
<div id ="themesettings">
<%
 String userAgent = request.getHeader("User-Agent");
 if (userAgent == null || userAgent.contains("Trident/6") || !userAgent.contains("MSIE")) {
%>
<jsp:getProperty name="uihelper" property="settings" />
<jsp:getProperty name="uihelper" property="forceMobileConsole" />
<div class="formaction" id="themeui">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Apply")%>" >
</div>
<% } else { %>
<p class="infohelp" id="oldmsie">
<%=uihelper._t("Theme selection disabled for Internet Explorer, sorry.")%> <%=uihelper._t("(A very old version has been detected. It's not recommended to use I2P with unmaintained browsers.")%>
<br>
<%=uihelper._t("If you're not using IE, it's likely that your browser is pretending to be IE; please configure your browser (or proxy) to use a different User Agent string if you'd like to access the console themes.")%>
</p>
<% } %>
</div>
</form>
<h3 id="passwordheading"><%=uihelper._t("Router Console Password")%></h3>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="uihelper" property="passwordForm" />
<div class="formaction" id="submitconsolepass">
<input type="submit" name="action" class="default" value="<%=intl._t("Add user")%>" >
<%
  boolean pwEnabled = net.i2p.I2PAppContext.getGlobalContext().getBooleanProperty("routerconsole.auth.enable");
  if (pwEnabled) {
%>
<input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
<%
  }
%>
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="action" class="add" value="<%=intl._t("Add user")%>" >
</div>
</form>
<h3 id="langheading"><%=uihelper._t("Router Console Language")%></h3>
<form action="" method="POST">
<input type="hidden" name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>" >
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="blah" >
<div id="langsettings">
<jsp:getProperty name="uihelper" property="langSettings" />
<p id="helptranslate">
<%=uihelper._t("Please contribute to the router console translation project! Contact the developers in #i2p-dev on IRC to help.").replace("router console", "I2P+").replace("#i2p-dev", "#saltR")%>
</p><hr>
<div class="formaction" id="langui">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Apply")%>" >
</div>
</div>
</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>