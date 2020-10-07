<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("config stats")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf"><%=intl._t("Stats Collection &amp; Graphing")%></h1>
<div class="main" id="config_stats">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigStatsHandler" id="formhandler" scope="request" />
 <%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigStatsHelper" id="statshelper" scope="request" />
 <jsp:setProperty name="statshelper" property="contextId" value="<%=i2pcontextId%>" />
 <div class="configure">
 <form id="statsForm" name="statsForm" action="" method="POST">
 <input type="hidden" name="action" value="foo" >
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <h3 class="tabletitle"><%=intl._t("Configure I2P Stat Collection")%></h3>
 <table id="statconfig">
 <tr><td class="infohelp" id="enablefullstats">A limited selection of stats is enabled by default, required for monitoring router performance. Only stats that have an optional graph are listed here; for a full list of enabled stats, view the <a href="/stats">stats page</a>.</td></tr>
<tr id="enablefull"><td><label><input type="checkbox" class="optbox" id="enableFull" name="isFull" value="true" <%
 if (statshelper.getIsFull()) { %>checked="checked" <% } %> > <b><%=intl._t("Enable full stats?").replace(" stats?", " stat collection")%></b>
 (<%=intl._t("change requires restart to take effect").replace("change requires restart to take effect", "restart required") %>)</label>&nbsp;&nbsp;<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" ></td></tr>
 </table>

 <h3 class="tabletitle" id="graphchoice"><%=intl._t("Select Stats for Graphing")%><!--<span class="h3navlinks script"><a id="toggle-*" title="<%=intl._t("Toggle full stat collection and all graphing options")%>" href="#"><%=intl._t("toggle all")%></a></span>--></h3>
 <table id="configstats">
 <% while (statshelper.hasMoreStats()) {
        while (statshelper.groupRequired()) { %>
 <tr>
     <th align="left" colspan="2" id=<%=statshelper.getCurrentGroupName()%>>
     <b><%=statshelper.getCurrentGroupName()%></b>
     </th></tr><tr class="graphableStat"><td colspan="2">
 <%     } // end iterating over required groups for the current stat %>


 <% if (statshelper.getCurrentCanBeGraphed() && !statshelper.getCurrentGraphName().contains("Ping")) { %>
    <label for="<%=statshelper.getCurrentStatName()%>" data-tooltip="<%=statshelper.getCurrentStatDescription()%>"><div class="stattograph">
    <input type="checkbox" class="optbox <%=statshelper.getCurrentGroupName()%>" id="<%=statshelper.getCurrentStatName()%>" name="graphList" value="<%=statshelper.getCurrentGraphName()%>"
 <%     if (statshelper.getCurrentIsGraphed()) { %>checked="checked" <% } %> >
    <b><%=statshelper.getCurrentStatName()%></b><br><span class="statdesc"><%=statshelper.getCurrentStatDescription()%></span></div></label>
 <%     } // end iterating over all stats %>
 <% } %>
</td></tr>
<tr class="tablefooter"><td colspan="2" align="right" class="optionsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</td></tr>
</table>
</div>
</form>
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
