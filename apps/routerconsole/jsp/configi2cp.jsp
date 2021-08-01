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
<%=intl.title("config i2cp")%>
<style type='text/css'>button span.hide {display:none;} input.default {width:1px;height:1px;visibility:hidden;}</style>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHelper" id="clientshelper" scope="request" />
<jsp:setProperty name="clientshelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="clientshelper" property="edit" value="<%=request.getParameter(\"edit\")%>" />
<h1 class="conf"><%=intl._t("External Client Access")%></h1>
<div class="main" id="config_i2cp">
<%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigClientsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure">
<!--<h3 id="advancedclientconfig"><a name="i2cp"></a><%=intl._t("Advanced Client Interface Configuration")%></h3>-->
<p class="infowarn">
<b><%=intl._t("The default settings will work for most people.")%></b>&nbsp;
<%=intl._t("Any changes made here must also be configured in the external client.")%>&nbsp;
<%=intl._t("Many clients do not support SSL or authorization.")%>&nbsp;
<%=intl._t("All changes require restart to take effect.")%>
</p>
<form action="configi2cp" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<table class="configtable" id="externali2cp">
<tr>
<th>
<%=intl._t("External I2P Client Protocol (I2CP) Interface Access")%>
</th>
</tr>
<tr>
<td>
<div class="optionlist">
<label><input type="radio" class="optbox" name="mode" value="1" <%=clientshelper.i2cpModeChecked(1) %> >
<%=intl._t("Enabled without SSL")%></label><br>
<label><input type="radio" class="optbox" name="mode" value="2" <%=clientshelper.i2cpModeChecked(2) %> >
<%=intl._t("Enabled with SSL required")%></label><br>
<%
    // returns nonempty string if disabled
    String disableChecked = clientshelper.i2cpModeChecked(0);
    boolean isDisabled = disableChecked.length() > 0;
%>
<label><input type="radio" class="optbox" name="mode" value="0" <%=disableChecked%> >
<%=intl._t("Disabled - Clients outside this Java process may not connect")%></label><br>
</div>
</td>
</tr>
<%
    if (!isDisabled) {
%>
<tr>
<td>
<div class="optionlist" id="i2cp_host">
<span class="nowrap"><b><%=intl._t("I2CP Interface")%>:</b>
<select name="interface">
<%
       String[] ips = clientshelper.intfcAddresses();
       for (int i = 0; i < ips.length; i++) {
           out.print("<option value=\"");
           out.print(ips[i]);
           out.print('\"');
           if (clientshelper.isIFSelected(ips[i]))
               out.print(" selected=\"selected\"");
           out.print('>');
           out.print(ips[i]);
           out.print("</option>\n");
       }
%>
</select>
</span><br>
<span class="nowrap"><b><%=intl._t("I2CP Port")%>:</b><input name="port" type="text" size="5" maxlength="5" value="<jsp:getProperty name="clientshelper" property="port" />" >
</span>
</div>
</td>
</tr>

<tr>
<td>
<b class="suboption">
<label><input type="checkbox" class="optbox" name="auth" value="true" <jsp:getProperty name="clientshelper" property="auth" /> >
<%=intl._t("Require username and password")%></label>
</b><br>
<div class="optionlist" id="i2cp_userpass">
<span class="nowrap"><b><%=intl._t("Username")%>:</b>
<input name="user" type="text" value="" /></span><br>
<span class="nowrap"><b><%=intl._t("Password")%>:</b>
<input name="nofilter_pw" type="password" value="" /></span>
</div>
</td>
</tr>
<%
    } // !isDisabled
%>
<tr>
<td class="optionsave" align="right">
<input type="submit" class="default" name="action" value="<%=intl._t("Save Interface Configuration")%>" />
<input type="submit" class="cancel" name="foo" value="<%=intl._t("Cancel")%>" />
<input type="submit" class="accept" name="action" value="<%=intl._t("Save Interface Configuration")%>" />
</td>
</tr>
</table>
</form>
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>