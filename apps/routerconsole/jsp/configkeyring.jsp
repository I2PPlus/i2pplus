<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
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
<%=intl.title("config keyring")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf"><%=intl._t("Keyring Manager")%></h1>
<div class="main" id="config_keyring">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigKeyringHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigKeyringHelper" id="keyringhelper" scope="request" />
<jsp:setProperty name="keyringhelper" property="contextId" value="<%=i2pcontextId%>" />
<p id="keyringhelp" class="infohelp">
<%=intl._t("The router keyring is used to decrypt encrypted LeaseSets.")%>&nbsp;
<%=intl._t("The keyring may contain keys for local or remote encrypted destinations.")%></p>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<jsp:getProperty name="keyringhelper" property="summary" />
<h3 class="tabletitle"><%=intl._t("Manual Keyring Addition")%></h3>
<table id="addkeyring">
<tr>
<td class="infohelp" colspan="2">
<%=intl._t("Enter keys for encrypted remote destinations here.")%>&nbsp;
<%
    net.i2p.util.PortMapper pm = net.i2p.I2PAppContext.getGlobalContext().portMapper();
    if (pm.isRegistered(net.i2p.util.PortMapper.SVC_I2PTUNNEL)) {
%>
<%=intl._t("Keys for local destinations must be entered on the configuration page for the relevant service in the")%> <a href="/i2ptunnelmgr"> <%=intl._t("Tunnel Manager")%></a>.
<%  }  %>
</td>
</tr>
<tr>
<td align="right"><b><%=intl._t("Full destination, name, Base32, or hash")%>:</b></td>
<td><input type="text" name="peer" size="55"></td>
</tr>
<tr>
<td align="right"><b><%=intl._t("Type")%>:</b></td>
<td>
<select id="encryptMode" name="encryptMode" class="selectbox">
<option title="<%=intl._t("Enter key provided by server operator.")%>" value="1">
<%=intl._t("Encrypted")%> (AES)</option>
<option title="<%=intl._t("Prevents server discovery by floodfills")%>" value="2">
<%=intl._t("Blinded")%></option>
<option title="<%=intl._t("Enter password provided by server operator.")%>" value="3">
<%=intl._t("Blinded with lookup password")%></option>
<option title="<%=intl._t("Enter key provided by server operator.")%>" value="4" selected="selected">
<%=intl._t("Encrypted")%> (PSK)</option>
<option title="<%=intl._t("Enter key and password provided by server operator.")%>" value="5">
<%=intl._t("Encrypted with lookup password")%> (PSK)</option>
<option title="<%=intl._t("Key will be generated.")%> <%=intl._t("Send key to server operator.")%>" value="6">
<%=intl._t("Encrypted")%> (DH)</option>
<option title="<%=intl._t("Enter password provided by server operator.")%> <%=intl._t("Key will be generated.")%> <%=intl._t("Send key to server operator.")%>" value="7">
<%=intl._t("Encrypted with lookup password")%> (DH)</option>
</select>
</td>
</tr>
<tr>
<td align="right"><b><%=intl._t("Encryption Key")%>:</b></td>
<td><input type="text" size="55" name="key" title="<%=intl._t("Leave blank for DH, will be generated automatically")%>"></td>
</tr>
<tr>
<td align="right"><b><%=intl._t("Optional lookup password")%>:</b></td>
<td><input type="password" name="nofilter_blindedPassword" title="<%=intl._t("Set password required to access this service")%>" class="freetext password" /></td>
</tr>
<tr>
<td align="right" colspan="2" class="optionsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="action" class="add" value="<%=intl._t("Add key")%>" >
</td>
</tr>
</table>
</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>