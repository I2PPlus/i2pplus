<%@page contentType="text/html" %>
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
<%=intl.title("config networking")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=intl._t("Network Configuration")%></h1>
<div class="main" id="config_network">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="blah" >
<h3 id="iptransport" class="tabletitle"><%=intl._t("IP and Transport Configuration")%>&nbsp;<span class="h3navlinks"><a title="<%=intl._t("Help with router configuration")%>" href="/help/configuration">[<%=intl._t("Configuration Help")%>]</a></span></h3>
<p class="infohelp"><%=intl._t("The default settings will work for most people.")%>&ensp;<%=intl._t("Changing these settings will restart your router.").replace("restart your router.", "perform a soft restart of your router.")%></p>
<table id="netconfig" class="configtable">
<tr>
<th id="ipconfig"><%=intl._t("IP Configuration")%></th>
</tr>
<tr>
<td>
<b class="suboption"><%=intl._t("Externally reachable hostname or IP address")%></b><br>
<div class="optionlist">
<label><input type="radio" class="optbox" name="udpAutoIP" value="local,upnp,ssu" <%=nethelper.getUdpAutoIPChecked(3) %> >
<%=intl._t("Use all auto-detect methods")%></label><br>
<label><input type="radio" class="optbox" name="udpAutoIP" value="local,ssu" <%=nethelper.getUdpAutoIPChecked(4) %> >
<%=intl._t("Disable UPnP IP address detection")%></label><br>
<label><input type="radio" class="optbox" name="udpAutoIP" value="upnp,ssu" <%=nethelper.getUdpAutoIPChecked(5) %> >
<%=intl._t("Ignore local interface IP address")%></label><br>
<label><input type="radio" class="optbox" name="udpAutoIP" value="ssu" <%=nethelper.getUdpAutoIPChecked(0) %> >
<%=intl._t("Use SSU IP address detection only")%></label><br>
<label title="<%=intl._t("Enabling this mode will prevent participating traffic")%>"><input type="radio" class="optbox" name="udpAutoIP" value="hidden" <%=nethelper.getUdpAutoIPChecked(2) %> >
<%=intl._t("Hidden mode - do not publish IP address to NetDB")%></label><br>
<label><input type="radio" class="optbox" name="udpAutoIP" value="fixed" <%=nethelper.getUdpAutoIPChecked(1) %> >
<%=intl._t("Specify hostname or IP address")%>:
<%=nethelper.getAddressSelector() %></label>
</div>
</td>
</tr>
<tr>
<td>
<div class="optionlist">
<label id="ipv4config"><input type="checkbox" class="optbox slider" name="IPv4Firewalled" value="true" <jsp:getProperty name="nethelper" property="IPv4FirewalledChecked" /> >
<%=intl._t("Disable inbound IPv4 connections (firewalled)")%></label><br>
<label><input type="checkbox" class="optbox slider" name="upnp" value="true" <jsp:getProperty name="nethelper" property="upnpChecked" /> >
<%=intl._t("Enable UPnP to open firewall ports")%></label><br>
<label id="ipchange" title="<%=intl._t("This mode enhances anonymity by preventing correlation between public IP address changes and your router identity. Both your router identity and UDP port will be changed.").replace("changed.", "changed when your public IP address changes.")%>"><input type="checkbox" class="optbox slider" name="laptop" value="true" <jsp:getProperty name="nethelper" property="laptopChecked" /> >
<%=intl._t("Laptop mode")%>
<span class="config_experimental" title="<%=intl._t("This is an experimental feature")%>">(<i><%=intl._t("Experimental")%></i>)</span></label>
</div>
</td>
</tr>
<tr>
<th id="ipv6config"><%=intl._t("IPv6 Configuration")%></th>
</tr>
<tr>
<td>
<div class="optionlist">
<label><input type="checkbox" class="optbox slider" name="IPv6Firewalled" value="true" <jsp:getProperty name="nethelper" property="IPv6FirewalledChecked" /> >
<%=intl._t("Disable inbound (firewalled)")%></label><br>
<label><input type="radio" class="optbox" name="ipv6" value="false" <%=nethelper.getIPv6Checked("false") %> >
<%=intl._t("Disable IPv6")%></label><br>
<label><input type="radio" class="optbox" name="ipv6" value="enable" <%=nethelper.getIPv6Checked("enable") %> >
<%=intl._t("Enable IPv6")%></label><br>
<label><input type="radio" class="optbox" name="ipv6" value="preferIPv4" <%=nethelper.getIPv6Checked("preferIPv4") %> >
<%=intl._t("Prefer IPv4 over IPv6")%></label><br>
<label><input type="radio" class="optbox" name="ipv6" value="preferIPv6" <%=nethelper.getIPv6Checked("preferIPv6") %> >
<%=intl._t("Prefer IPv6 over IPv4")%></label><br>
<label><input type="radio" class="optbox" name="ipv6" value="only" <%=nethelper.getIPv6Checked("only") %> >
<%=intl._t("Use IPv6 only (disable IPv4)")%>
<span class="config_experimental" title="<%=intl._t("This is an experimental feature")%>">(<i><%=intl._t("Experimental")%></i>)</span></label>
</div>
</td>
</tr>
<tr>
<th id="tcpconfig"><%=intl._t("TCP Configuration")%></th>
</tr>
<tr>
<td>
<b class="suboption"><%=intl._t("Externally reachable hostname or IP address")%></b><br>
<div class="optionlist">
<label><input type="radio" class="optbox" name="ntcpAutoIP" value="true" <%=nethelper.getTcpAutoIPChecked(2) %> >
<%=intl._t("Use auto-detected IP address")%>&nbsp;
<i>(<%=intl._t("currently")%>&nbsp;<jsp:getProperty name="nethelper" property="udpIP" />)</i>&nbsp;
<%=intl._t("if we are not firewalled").replace("we are ", "")%></label><br>
<label><input type="radio" class="optbox" name="ntcpAutoIP" value="always" <%=nethelper.getTcpAutoIPChecked(3) %> >
<%=intl._t("Always use auto-detected IP address (not firewalled)")%></label><br>
<label><input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(1) %> >
<%=intl._t("Specify hostname or IP address")%>:
<input name ="ntcphost" type="text" size="16" value="<jsp:getProperty name="nethelper" property="ntcphostname" />" ></label><br>
<label><input type="radio" class="optbox" name="ntcpAutoIP" value="false" <%=nethelper.getTcpAutoIPChecked(0) %> >
<%=intl._t("Disable inbound TCP connections (firewalled)")%></label><br>
<label title="<%=intl._t("Select only if behind a firewall that throttles or blocks outbound TCP")%>"><input type="radio" class="optbox" name="ntcpAutoIP" value="disabled" <%=nethelper.getTcpAutoIPChecked(4) %> >
<%=intl._t("Completely disable TCP connections")%></label>
</div>
</td>
</tr>
<tr>
<td>
<b class="suboption"><%=intl._t("TCP port")%></b><br><div class="optionlist">
<label><input type="radio" class="optbox" name="ntcpAutoPort" value="2" <%=nethelper.getTcpAutoPortChecked(2) %> >
<%=intl._t("Use the same port configured for UDP")%>&nbsp;<i>(<%=intl._t("currently")%>&nbsp;<jsp:getProperty name="nethelper" property="udpPort" />)</i>
</label><br>
<label><input type="radio" class="optbox" name="ntcpAutoPort" value="1" <%=nethelper.getTcpAutoPortChecked(1) %> >
<%=intl._t("Specify port")%>:
<input name ="ntcpport" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="ntcpport" />" ></label></div>
</td>
</tr>
<tr>
<th id="udpconfig"><%=intl._t("UDP Configuration")%></th>
</tr>
<tr>
<td>
<div class="optionlist">
<label><b><%=intl._t("UDP port")%>:</b>
<input name ="udpPort" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="configuredUdpPort" />" ></label><br>
<label title="<%=intl._t("Select only if behind a firewall that blocks outbound UDP")%>"><input type="checkbox" class="optbox slider" name="disableUDP" value="disabled" <%=nethelper.getUdpDisabledChecked() %> >
<%=intl._t("Completely disable UDP connections")%></label>
</div>
</td>
</tr>
<tr>
<td class="infowarn">
<%=intl._t("The router's external port allows communication with other routers. It is recommended to configure your firewall to allow inbound and outbound traffic on this port to optimize your router's performance on the network.")%>&ensp;<b><%=intl._t("Do not reveal your port numbers to anyone, as they can be used to discover your IP address.")%></b>
</td>
</tr>
<tr>
<td class="optionsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" class="accept" name="save" value="<%=intl._t("Save changes")%>" >
</td>
</tr>
</table>
</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>