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
<%=intl.title("config tunnels")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.helpers.ConfigTunnelsHelper" id="tunnelshelper" scope="request" />
<jsp:setProperty name="tunnelshelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=intl._t("Tunnel Options")%></h1>
<div class="main" id="config_tunnels">
 <%@include file="confignav.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigTunnelsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<% if (!tunnelshelper.isAdvanced()) { %>
<!--
 <p id="tunnelconfig" class="infowarn">
 <%=intl._t("The default settings work for most people.")%>&nbsp;<wbr>
 <%=intl._t("There is a fundamental tradeoff between anonymity and performance.")%>&nbsp;<wbr>
 <%=intl._t("Tunnels longer than 3 hops (for example 2 hops + 0-2 hops, 3 hops + 0-1 hops, 3 hops + 0-2 hops), or a high quantity + backup quantity, may severely reduce performance or reliability.")%>&nbsp;<wbr>
 <%=intl._t("High CPU and/or high outbound bandwidth usage may result.")%>&nbsp;<wbr>
 <%=intl._t("Change these settings with care, and adjust them if you have problems.")%>
 </p>
 -->
<% }  // !isAdvanced %>
 <p class="infohelp">
 <%=intl._t("Exploratory tunnel setting changes are stored in the router.config file.")%>&nbsp;<wbr>
<% if (tunnelshelper.isAdvanced()) { %>
 <%=intl._t("Client tunnel changes are temporary and are not saved.")%>&nbsp;<wbr>
<% } else { // isAdvanced %>
<%
    net.i2p.util.PortMapper pm = net.i2p.I2PAppContext.getGlobalContext().portMapper();
    // if (pm.isRegistered(net.i2p.util.PortMapper.SVC_I2PTUNNEL)) {
%>
 <%=intl._t("The default settings are optimized for general usage and normally don't need to be changed.")%>&nbsp;<wbr>
 <%=intl._t("To modify client tunnel settings, use the <a href=\"/i2ptunnelmgr\">Tunnel Manager</a>, or the <a href=\"/i2psnark/configure\">Configuration page</a> for I2PSnark.")%>&nbsp;<wbr>
 <%=intl._t("To enable session-only changes to the client tunnels, activate the console's <a href=\"/help/advancedsettings\">Advanced mode</a>.")%>
<%  // } %>
<% } %>
 </p>
<form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <input type="hidden" name="action" value="blah" >
 <jsp:getProperty name="tunnelshelper" property="form" />
 <hr><div class="formaction" id="tunnelconfigsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</div>
</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>