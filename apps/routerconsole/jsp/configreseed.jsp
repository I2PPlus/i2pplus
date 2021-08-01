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
<%=intl.title("config reseeding")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigReseedHelper" id="reseedHelper" scope="request" />
<jsp:setProperty name="reseedHelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=intl._t("Reseeding")%></h1>
<div class="main" id="config_reseed">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigReseedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<% if (!reseedHelper.isAdvanced()) { %>
<p class="infohelp">
<%=intl._t("Reseeding is the bootstrapping process used to find other routers when you first install I2P, or when your router has too few router references remaining.")%>&nbsp;<wbr>
<%=intl._t("To enable the configuration of reseed urls, activate the console's <a href=\"/help/advancedsettings\">Advanced mode</a>.")%>
</p>
<% }  // !isAdvanced %>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3 class="tabletitle" id="reseedsetup"><%=intl._t("Reseeding Configuration")%>&nbsp;<span class="h3navlinks"><a title="<%=intl._t("Help with reseeding")%>" href="/help/reseed">[<%=intl._t("Reseeding Help")%>]</a></span></h3>
<table id="reseedconfig" class="configtable" border="0" cellspacing="5">
<!--
<tr>
<td class="infohelp" colspan="2">
<%=intl._t("The default settings will work for most people.")%>&nbsp;
<%=intl._t("Change these only if HTTPS is blocked by a restrictive firewall and reseed has failed.")%>
</td>
</tr>
-->
<% if (reseedHelper.isAdvanced()) { %>
<tr>
<td colspan="2">
<b class="suboption"><%=intl._t("Reseed URLs")%></b><br>
<div class="optionsingle optiontextarea">
<textarea wrap="off" name="reseedURL" cols="60" rows="7" spellcheck="false">
<jsp:getProperty name="reseedHelper" property="reseedURL" />
</textarea>
</div>
</td>
<% }  // isAdvanced %>
</tr>
<% if (reseedHelper.shouldShowSelect()) { %>
<tr>
<td colspan="2">
<b class="suboption">
<%=intl._t("Reseed URL Selection")%>
</b><br>
<div class="optionsingle" id="reseed_urlselection">
<label><input type="radio" class="optbox" name="mode" value="0" <%=reseedHelper.modeChecked(0) %> >
<%=intl._t("Try SSL first then non-SSL")%></label>
<label><input type="radio" class="optbox" name="mode" value="1" <%=reseedHelper.modeChecked(1) %> >
<%=intl._t("Use SSL only")%></label>
<label><input type="radio" class="optbox" name="mode" value="2" <%=reseedHelper.modeChecked(2) %> >
<%=intl._t("Use non-SSL only")%></label>
</div>
</td>
</tr>
<% } // shouldShowSelect %>
<% if (reseedHelper.shouldShowHTTPSProxy()) { %>
<tr>
<td colspan="2">
<b class="suboption">
<% if (reseedHelper.shouldShowHTTPProxy()) { %>
<%=intl._t("Use Proxy for HTTPS Reseed Hosts")%>
<% } else { %>
<%=intl._t("Use Proxy to Reseed")%>
<% } // shouldShowHTTPProxy %>
</b>
<div id="reseedssl">
<div id="reseedproxytype">
<label><input type="radio" class="optbox" name="pmode" value="" <%=reseedHelper.pmodeChecked(0) %> >
<%=intl._t("None")%></label>
<br>
<label><input type="radio" class="optbox" name="pmode" value="HTTP" <%=reseedHelper.pmodeChecked(1) %> >
<%=intl._t("HTTPS")%></label>
<br>
<label><input type="radio" class="optbox" name="pmode" value="SOCKS4" <%=reseedHelper.pmodeChecked(2) %> >
<%=intl._t("SOCKS 4/4a")%></label>
<br>
<label><input type="radio" class="optbox" name="pmode" value="SOCKS5" <%=reseedHelper.pmodeChecked(3) %> >
<%=intl._t("SOCKS 5")%></label>
<br>
<label title="<%=intl._t("This option will use I2P's HTTP outproxy to reseed, or the Orchid plugin if configured. Note: this option will not work when you have 0 known peers.")%>"><input type="radio" class="optbox" name="pmode" value="INTERNAL" <%=reseedHelper.pmodeChecked(4) %> >
<%=intl._t("I2P Outproxy")%></label>
</div>
<div class="optionlist" id="reseedproxysslhostport">
<% if (!reseedHelper.getEnable().equals(reseedHelper.pmodeChecked(4)) || !reseedHelper.getEnable().equals(reseedHelper.pmodeChecked(0))) { %>
<!--
<span class="nowrap">
<b><%=intl._t("Host")%>:</b>
<input name="shost" type="text" property="shost" readonly="readonly" />
</span><br>
<span class="nowrap">
<b><%=intl._t("Port")%>:</b>
<input name="sport" type="text" size="5" maxlength="5" property="sport" readonly="readonly" />
</span>
-->
<% } else { %>
<span class="nowrap">
<b><%=intl._t("Host")%>:</b>
<input name="shost" type="text" required value="<jsp:getProperty name="reseedHelper" property="shost" />" >
</span><br>
<span class="nowrap">
<b><%=intl._t("Port")%>:</b>
<input name="sport" type="text" size="5" maxlength="5" pattern="[0-9]{1,5}" value="<jsp:getProperty name="reseedHelper" property="sport" />" >
</span>
<% } // conditionally enable host/port fields %>
</div>
</div>
</td>
</tr>
<!-- SSL auth not fully implemented, not necessary?
<tr>
<td colspan="2">
<b class="suboption">
<!-- SSL auth not implemented
<label for ="useproxyauthssl">
<input type="checkbox" class="optbox" name="sauth" id="useproxyauthssl" value="true" <jsp:getProperty name="reseedHelper" property="sauth" /> >
<%=intl._t("Proxy requires authorization")%>
</label>
</b><br>
<div class="optionlist">
<span class="nowrap">
<b><%=intl._t("Username")%>:</b>
<input name="susername" type="text" value="<jsp:getProperty name="reseedHelper" property="susername" />" >
</span><br>
<span class="nowrap">
<b><%=intl._t("Password")%>:</b>
<input name="nofilter_spassword" type="password" value="<jsp:getProperty name="reseedHelper" property="nofilter_spassword" />" >
</span>
</div>
</td>
</tr>
-->
<% } // shouldShowHTTPSProxy %>
<% if (reseedHelper.shouldShowHTTPProxy()) { %>
<tr>
<td colspan="2">
<b class="suboption">
<label for="enableproxy"><input type="checkbox" class="optbox" name="enable" id="enableproxy" value="true" <jsp:getProperty name="reseedHelper" property="enable" /> >
<%=intl._t("Use HTTP Proxy for HTTP Reseed Hosts")%></label>
<label for="useproxyauth"><input type="checkbox" class="optbox" name="auth" id="useproxyauth" value="true" <jsp:getProperty name="reseedHelper" property="auth" /> >
<%=intl._t("Proxy requires authorization")%></label>
</b><br>
<div class="optionlist">
<span class="nowrap">
<b><%=intl._t("Host")%>:</b>
<input name="host" type="text" required x-moz-errormessage="<%=intl._t("Please supply a valid proxy host address")%>" value="<jsp:getProperty name="reseedHelper" property="host" />" >
</span><br>
<span class="nowrap">
<b><%=intl._t("Port")%>:</b>
<input name="port" type="text" size="5" maxlength="5" pattern="[0-9]{1,5}" required x-moz-errormessage="<%=intl._t("Please supply a valid port for the proxy host")%>" value="<jsp:getProperty name="reseedHelper" property="port" />" >
</span><br>
<span class="nowrap">
<b><%=intl._t("Username")%>:</b>
<input name="username" type="text" value="<jsp:getProperty name="reseedHelper" property="username" />" >
</span><br>
<span class="nowrap">
<b><%=intl._t("Password")%>:</b>
<input name="nofilter_password" type="password" value="<jsp:getProperty name="reseedHelper" property="nofilter_password" />" >
</span>
</div>
</td>
</tr>
<% } // shouldShowHTTPProxy %>
<tr>
<td class="optionsave" colspan="2">
<input type="submit" name="action" class="reload" value="<%=intl._t("Reset URL list")%>" />
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<input type="submit" name="action" class="accept" value="<%=intl._t("Save changes")%>" />
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed now")%>" />
</td>
</tr>
</table>
</form>
<h3 class="tabletitle"><%=intl._t("Manual Reseed")%></h3>
<table id="manualreseed">
<tr>
<td class="infohelp">
<%=intl._t("The su3 format is preferred, as it will be verified as signed by a trusted source.")%>&nbsp;
<%=intl._t("The zip format is unsigned; use a zip file only from a source that you trust.")%>
</td>
</tr>
<tr>
<td>
<b class="suboption"><%=intl._t("Enter zip or su3 URL")%></b>
<div class="optionsingle" id="reseedurl">
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<table>
<tr>
<td>
<%
   String url = request.getParameter("url");
   String value = url != null ? "value=\"" + net.i2p.data.DataHelper.escapeHTML(url) + '"' : "";
%>
<input name="url" type="text" size="60" value="" title="<%=intl._t("Please supply a valid reseed URL")%>" />
</td>
<td>
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed from URL")%>" />
</td>
</tr>
</table>
</form>
</div>
</td>
</tr>
<tr>
<td>
<b class="suboption"><%=intl._t("Select zip or su3 file")%></b>
<div class="optionsingle" id="reseedzip">
<form action="" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<table>
<tr id="file">
<td>
<%
   String file = request.getParameter("file");
   if (file != null && file.length() > 0) {
%>
<input type="text" size="60" name="file" value="<%=net.i2p.data.DataHelper.escapeHTML(file)%>">
<%
   } else {
%>
<input name="file" type="file" accept=".zip,.su3" value="" required />
<%
   }
%>
</td>
<td>
<input type="submit" name="action" class="download" value="<%=intl._t("Reseed from file")%>" />
</td>
</tr>
</table>
</form>
</div>
</td>
</tr>
<tr id="create">
<td>
<b class="suboption"><%=intl._t("Create Reseed File")%></b>
<div class="optionsingle" id="reseedcreatefile">
<form action="/createreseed" method="GET">
<table>
<tr>
<td>
<p>
<%=intl._t("Create a new reseed zip file you may share for others to reseed manually.")%>&nbsp;
<%=intl._t("This file will never contain your own router's identity or IP.").replace("identity or IP", "identity or IP address")%>
</p>
</td>
<td>
<input type="submit" name="action" class="go" value="<%=intl._t("Create reseed file")%>" />
</td>
</tr>
</table>
</form>
</div>
</td>
</tr>
</table>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>