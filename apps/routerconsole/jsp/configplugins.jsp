<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("config plugins")%>
<style type="text/css">input.default{width: 1px; height: 1px; visibility: hidden;}</style>
<script nonce="<%=cspNonce%>" type="text/javascript">var deleteMessage = "<%=intl._t("Are you sure you want to delete {0}?")%>";</script>
<script src="/js/configclients.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1 class="conf"><%=intl._t("Plugins")%></h1>
<div class="main" id="config_plugins">
<%@include file="confignav.jsi" %>
<%
   if (clientshelper.showPlugins()) {
%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure">
<h3 id="pluginmanage"><%=intl._t("Plugin Installation")%>&nbsp;
<span class="h3navlinks">
<a href="configclients" title="<%=intl._t("Client Configuration")%>">Clients</a>&nbsp;
<a href="configwebapps" title="<%=intl._t("WebApp Configuration")%>">WebApps</a>
</span>
</h3>
<form action="configplugins" method="POST">
<table id="plugininstall" class="configtable">
<tr>
<td class="infohelp" colspan="2">
<%=intl._t("For available plugins, visit {0}.", "<a href=\"http://stats.i2p/i2p/plugins/\" target=\"_blank\">zzz's plugin page</a>")%>
</td>
</tr>
<tr>
<td colspan="2">
<b class="suboption"><%=intl._t("Enter xpi2p or su3 plugin URL")%></b><br>
<div class="optionsingle" id="installPluginUrl">
<%
       if (clientshelper.isPluginInstallEnabled()) {
%>
<table>
<tr id="url">
<td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<%
   String url = request.getParameter("pluginURL");
   String value = url != null ? "value=\"" + net.i2p.data.DataHelper.escapeHTML(url) + '"' : "";
%>
<input type="text" size="60" name="pluginURL" required title="<%=intl._t("To install a plugin, enter the download URL:")%>" >
</td>
<td align="right">
<input type="submit" name="action" class="default hideme" value="<%=intl._t("Install Plugin")%>" />
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<input type="submit" name="action" class="download" value="<%=intl._t("Install Plugin")%>" />
</td>
</tr>
</table>
</div>
</td>
</tr>
</table>
</form>
<form action="configplugins" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<table id="plugininstall2" class="configtable">
<tr>
<td colspan="2">
<b class="suboption"><%=intl._t("Select xpi2p or su3 file")%></b><br>
<div class="optionsingle" id="installPluginFile">
<table>
<tr id="file">
<td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<%
   String file = request.getParameter("pluginFile");
   if (file != null && file.length() > 0) {
%>
<input type="text" size="60" name="pluginFile" value="<%=net.i2p.data.DataHelper.escapeHTML(file)%>">
<%
   } else {
%>
<input type="file" name="pluginFile" accept=".xpi2p,.su3" >
<%
   }
%>
</td>
<td align="right">
<input type="submit" name="action" class="download" title="<%=intl._t("Please supply a valid plugin file")%>" value="<%=intl._t("Install Plugin from File")%>" />
</td>
</tr>
</table>
</div>
</tr>
</table>
</form>
<%
       } // pluginInstallEnabled
       if (clientshelper.isPluginUpdateEnabled()) {
%>
<h3 id="pconfig"><%=intl._t("Plugin Manager")%></h3>
<form action="configplugins" method="POST">
<p id="pluginconfigtext">
<%=intl._t("The plugins listed below are started by the webConsole client.")%>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="submit" name="action" class="reload" value="<%=intl._t("Update All Installed Plugins")%>" />
</p>
</form>
<div class="wideload">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="clientshelper" property="form3" />
<div class="formaction" id="pluginconfigactions">
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<input type="submit" name="action" class="accept" value="<%=intl._t("Save Plugin Configuration")%>" />
</div>
</form>
</div>
<%
       } // pluginUpdateEnabled
   } // showPlugins
%>
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>