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
<%=intl.title("config sidebar")%>
<style type='text/css'>input.default {width: 1px; height: 1px; visibility: hidden;}</style>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf"><%=intl._t("Customize Sidebar")%></h1>
<div class="main" id="config_summarybar">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigSummaryHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<%
    formhandler.setMovingAction();
%>
<jsp:useBean class="net.i2p.router.web.helpers.SummaryHelper" id="summaryhelper" scope="request" />
<jsp:setProperty name="summaryhelper" property="contextId" value="<%=i2pcontextId%>" />
<h3 class="tabletitle"><%=intl._t("Refresh Interval")%></h3>
<form action="" method="POST">
<table class="configtable" id="refreshsidebar">
<tr>
<td>
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="group" value="0">
<%
    String rval;
    if (intl.getDisableRefresh())
        rval = "0";
    else
        rval = intl.getRefresh();
%>
<input type="text" name="refreshInterval" maxlength="4" pattern="[0-9]{1,4}" required value="<%=rval%>">
<%=intl._t("seconds")%>&emsp;(<%=intl._t("Set to 0 to disable.")%>)
</td>
<td class="optionsave">
<input type="submit" name="action" class="accept" value="<%=intl._t("Save")%>" >
</td>
</tr>
</table>
</form>
<h3 class="tabletitle"><%=intl._t("Customize Sidebar")%></h3>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<input type="hidden" name="group" value="2">
<jsp:getProperty name="summaryhelper" property="configTable" />
<div class="formaction" id="sidebardefaults">
<input type="submit" class="reload" name="action" value="<%=intl._t("Restore full default")%>" >
<input type="submit" class="reload" name="action" value="<%=intl._t("Restore minimal default")%>" >
</div>
</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>