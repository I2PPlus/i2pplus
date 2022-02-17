<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext context = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (context.getProperty("routerconsole.lang") != null)
        lang = context.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("config clients")%>
<style type='text/css'> button span.hide {display:none;} input.default {width: 1px; height: 1px; visibility: hidden;}</style>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var deleteMessage = "<%=intl._t("Are you sure you want to delete {0}?")%>";
</script>
<script src="/js/configclients.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>

<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1 class="conf"><%=intl._t("Router Clients")%></h1>
<div class="main" id="config_clients">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure">
<h3 id="i2pclientconfig"><%=intl._t("Client Configuration")%>&nbsp;
<span class="h3navlinks">
<a href="configplugins" title="<%=intl._t("Plugin Configuration")%>">Plugins</a>&nbsp;
<a href="configwebapps" title="<%=intl._t("WebApp Configuration")%>">WebApps</a>
</span>
</h3>
<p class="infohelp" id="clientconf">
<%
       net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
       if (net.i2p.router.startup.ClientAppConfig.isSplitConfig(ctx)) {
%>
<%=intl._t("To change client options, edit the files in: {0}", "<code>" + net.i2p.router.startup.ClientAppConfig.configDir(ctx).getAbsolutePath() + "</code>")%>
<%
       } else {
%>
<%=intl._t("To change client options, edit the file: {0}", "<code>" + net.i2p.router.startup.ClientAppConfig.configFile(ctx).getAbsolutePath() + "</code>")%>
<%
       }
%>
</p>
<%
      if (!clientshelper.isAdvanced()) {
%>
<p class="infowarn" id="clientconf">
<b><%=intl._t("Be careful changing any settings here. The 'router console' and 'application tunnels' are required for most uses of I2P. Only advanced users should change these.")%></b>
</p>
<%
      }
%>
<div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="clientshelper" property="form1" />
<div class="formaction" id="clientsconfig">
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<%
      if (clientshelper.isClientChangeEnabled() && request.getParameter("edit") == null) {
%>
<input type="submit" name="edit" class="add" value="<%=intl._t("Add Client")%>" />
<%
      }
%>
<input type="submit" class="accept" name="action" value="<%=intl._t("Save Client Configuration")%>" />
</div>
</form>
</div>
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>