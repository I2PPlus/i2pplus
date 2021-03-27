<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("config advanced")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHelper" id="advancedhelper" scope="request" />
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=intl._t("Advanced Configuration")%></h1>
<div class="main" id="config_advanced">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure">
<div class="wideload">
<h3 id="ffconf" class="tabletitle"><%=intl._t("Floodfill Configuration")%></h3>
<form action="" method="POST">
<table id="floodfillconfig" class="configtable">
<tr>
<td class="infohelp">
<%=intl._t("Floodfill participation helps the network, but may use more of your computer's resources.")%>
<%
    if (advancedhelper.isFloodfill()) {
%> (<%=intl._t("This router is currently a floodfill participant.")%>)<%
    } else {
%> (<%=intl._t("This router is not currently a floodfill participant.")%>)<%
    }
%>
</td>
</tr>
<tr>
<td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="ff" >
<b><%=intl._t("Enrollment")%>:</b>
<label><input type="radio" class="optbox" name="ff" value="auto" <%=advancedhelper.getFFChecked(2) %> >
<%=intl._t("Automatic")%></label>&nbsp;
<label><input type="radio" class="optbox" name="ff" value="true" <%=advancedhelper.getFFChecked(1) %> >
<%=intl._t("Force On")%></label>&nbsp;
<label><input type="radio" class="optbox" name="ff" value="false" <%=advancedhelper.getFFChecked(0) %> >
<%=intl._t("Disable")%></label>
</td>
</tr>
<tr>
<td class="optionsave" align="right">
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</td>
</tr>
</table>
</form>
<h3 id="advancedconfig" class="tabletitle"><%=intl._t("Advanced I2P Configuration")%>&nbsp;<span class="h3navlinks"><a title="Help with additional configuration settings" href="/help/advancedsettings">[Additional Options]</a></span></h3>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="action" value="blah" >
<table class="configtable" id="advconf">
<tr>
<td class="infohelp">
<b><%=intl._t("NOTE")%>:</b> <%=intl._t("Some changes may require a restart to take effect.")%>
</td>
</tr>
<tr>
<td class="tabletextarea">
<% String advConfig = advancedhelper.getSettings(); %>
<textarea id="advancedsettings" rows="32" cols="60" name="nofilter_config" wrap="off" spellcheck="false" ><%=advConfig%></textarea>
</td>
</tr>
<tr>
<td class="optionsave" align="right">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</td>
</tr>
</table>
</form>
</div>
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
