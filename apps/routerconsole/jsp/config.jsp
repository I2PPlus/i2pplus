<%@page contentType="text/html" %>
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
<%=intl.title("configure bandwidth")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=i2pcontextId%>" />
<h1 class="conf"><%=intl._t("Bandwidth Allocation")%></h1>
<div class="main" id="config_bandwidth">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigNetHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>">
<input type="hidden" name="action" value="blah">
<input type="hidden" name="ratesOnly" value="1">
<h3 id="bwlimiter" class="tabletitle"><%=intl._t("Bandwidth Limiter")%>&nbsp;<span class="h3navlinks" title="<%=intl._t("Advanced Network Configuration")%>"><a href="confignet"><%=intl._t("Advanced Network Configuration")%></a></span></h3>
<table id="bandwidthconfig" class="configtable">
<tr>
<td class="infohelp" colspan="2">
<%=intl._t("I2P will work best if you configure your rates to match the speed of your internet connection.").replace("I2P", "I2P+")%>
<% if (!nethelper.isAdvanced()) { %>
&nbsp;<%=intl._t("Note: Your contribution to the network (network share) is determined by the allocation of upstream bandwidth (upload speed).")%>
<% }  // !isAdvanced %>
&nbsp;<%=intl._t("The maximum data transfer values indicate the theoretical maximum, and in practice will normally be much lower.")%>
</td>
</tr>
<%-- display burst, set standard, handler will fix up --%>
<tr>
<td>
<div class="optionsingle bw_in">
<span class="bw_title"><%=intl._t("Download Speed")%></span>
<input style="text-align: right; width: 5em" name="inboundrate" type="text" size="5" maxlength="6" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />">
<%=intl._t("KBps In")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="inboundBurstRateBits" />
</td>
<%--
<!-- let's keep this simple...
bursting up to
<input name="inboundburstrate" type="text" size="5" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />"> KBps for
<jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br>
-->
--%>
</tr>
<tr>
<%-- display burst, set standard, handler will fix up --%>
<td>
<div class="optionsingle bw_out">
<span class="bw_title"><%=intl._t("Upload Speed")%></span>
<input style="text-align: right; width: 5em" name="outboundrate" type="text" size="5" maxlength="6" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />">
<%=intl._t("KBps Out")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="outboundBurstRateBits" />
</td>
<%--
<!-- let's keep this simple...
 bursting up to
<input name="outboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />"> KBps for
<jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br>
<i>KBps = kilobytes per second = 1024 bytes per second = 8192 bits per second.<br>
A negative rate sets the default.</i><br>
-->
--%>
</tr>
<tr>
<td>
<div class="optionsingle bw_share">
<span class="bw_title"><%=intl._t("Network Share")%></span><jsp:getProperty name="nethelper" property="sharePercentageBox" />
<%=intl._t("Share")%>
</div>
</td>
<td>
<jsp:getProperty name="nethelper" property="shareRateBits" />
</td>
</tr>
<tr>
<td class="infohelp" colspan="2">
<%
    int share = Math.round(nethelper.getShareBandwidth() * 1.024f);
    float shareMegabits = Math.round(nethelper.getShareBandwidth() / 1024 * 8);
    if (share < 12) {
        out.print("<b>");
        out.print(intl._t("NOTE"));
        out.print("</b>: ");
        out.print(intl._t("You have configured I2P to share only {0} KBps.", "<b id=\"sharebps\">" + share + "</b>").replace("I2P", "I2P+"));
        out.print("\n");

        out.print(intl._t("I2P requires at least 12KBps to enable sharing. ").replace("I2P", "I2P+"));
        out.print(intl._t("Please enable sharing (participating in tunnels) by configuring more bandwidth. "));
        out.print(intl._t("It improves your anonymity by creating cover traffic, and helps the network. "));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps {1}.", "<b id=\"sharebps\">" + share + "</b>", " (" + shareMegabits + " Mbit/s)").replace("I2P", "I2P+"));
        out.print("\n");

        out.print(intl._t("The higher the share bandwidth the more you improve your anonymity and help the network."));
    }
%>
</td>
</tr>
<tr>
<td class="optionsave" colspan="2">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>">
<input type="submit" class="accept" name="save" value="<%=intl._t("Save changes")%>">
</td>
</tr>
</table>
</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>