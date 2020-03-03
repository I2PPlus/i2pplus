<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("config home")%>
<style type="text/css">input.default {width: 1px; height: 1px; visibility: hidden;}</style>
<%@include file="summaryajax.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf"><%=intl._t("Homepage Customization")%></h1>
<div class="main" id="config_homepage">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigHomeHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request" />
<jsp:setProperty name="homehelper" property="contextId" value="<%=i2pcontextId%>" />
<h3 class="tabletitle"><%=intl._t("Homepage Options")%></h3>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="group" value="0">
<table id="oldhome" class="configtable">
<tr>
<td>
<label class="nowrap disabled" title="routerconsole.oldHomePage=true to enable">
<input type="checkbox" class="optbox" name="oldHome" disabled="disabled" <jsp:getProperty name="homehelper" property="configHome" /> >
<%=intl._t("Use {0} as homepage", "/sitemap")%>
</label>
<!-- TODO activate -->
<!--
<label class="nowrap disabled" title="routerconsole.homeExtLinksToNewTab=true to enable">
<input type="checkbox" class="optbox" name="newTabs" disabled="disabled">
<%=intl._t("Open external links on {0} in new tabs", "/home")%>
</label>
-->
<!-- TODO activate -->
<!--
<label class="nowrap disabled" title="routerconsole.showSearch=true to enable">
<input type="checkbox" class="optbox" name="searchbox" disabled="disabled">
<%=intl._t("Enable searchbox on {0}", "/home")%>
</label>
-->
</td>
<td class="optionsave">
<input type="submit" name="action" class="accept" value="<%=intl._t("Save")%>" >
</td>
</tr>
</table>
</form>
<% //if (homehelper.shouldShowSearch()) { %>
<h3 class="tabletitle"><%=intl._t("Search Engines")%></h3>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="group" value="3">
<jsp:getProperty name="homehelper" property="configSearch" />
<div class="formaction" id="homesearch">
<input type="submit" name="action" class="default" value="<%=intl._t("Add item")%>" >
<input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="action" class="reload" value="<%=intl._t("Restore defaults")%>" >
<input type="submit" name="action" class="add" value="<%=intl._t("Add item")%>" >
</div>
</form>
<% //}  // shouldShowSearch() %>
<h3 class="tabletitle" id="configapps"><%=intl._t("Applications and Configuration")%></h3>
<form action="" method="POST" id=\"homeapps_form\">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="group" value="2">
<jsp:getProperty name="homehelper" property="configServices" />
<div class="formaction" id="homeapps">
<input type="submit" name="action" class="default" value="<%=intl._t("Add item")%>" >
<input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="action" class="reload" value="<%=intl._t("Restore defaults")%>" >
<input type="submit" name="action" class="add" value="<%=intl._t("Add item")%>" >
</div>
</form>
<h3 class="tabletitle" id="configsites"><%=intl._t("Sites of Interest")%></h3>
<form action="" method="POST" id=\"homesites_form\">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="group" value="1">
<jsp:getProperty name="homehelper" property="configFavorites" />
<div class="formaction" id="homesites">
<input type="submit" name="action" class="default" value="<%=intl._t("Add item")%>" >
<input type="submit" name="action" class="delete" value="<%=intl._t("Delete selected")%>" >
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="action" class="reload" value="<%=intl._t("Restore defaults")%>" >
<input type="submit" name="action" class="add" value="<%=intl._t("Add item")%>" >
 </div>
</form>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
