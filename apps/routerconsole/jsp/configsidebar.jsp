<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="48kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("config sidebar")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class=conf><%=intl._t("Customize Sidebar")%></h1>
<div class=main id=config_summarybar>
<%@include file="confignav.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigSidebarHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<% formhandler.setMovingAction();%>
<jsp:useBean class="net.i2p.router.web.helpers.SidebarHelper" id="sidebarhelper" scope="request"/>
<jsp:setProperty name="sidebarhelper" property="contextId" value="<%=i2pcontextId%>"/>
<h3 class=tabletitle><%=intl._t("Sidebar Options")%></h3>
<form method=POST>
<table class=configtable id=refreshsidebar>
<tr>
<td>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=group value=0>
<%  String rval;
    if (intl.getDisableRefresh()) {rval = "0";}
    else {rval = intl.getRefresh();}
%>
<label><b><%=intl._t("Refresh interval")%>:</b>
<input type=text name=refreshInterval maxlength=4 pattern="[0-9]{1,4}" required value="<%=rval%>">
<%=intl._t("seconds")%>
<% if (!rval.equals("0")) { %>&nbsp;(<%=intl._t("0 to disable")%>)<% } %></label>
<label id=unifiedSidebar><b><%=intl._t("Unified sidebar")%>:</b>
<input type=checkbox class="optbox slider" name=unifiedSidebar value=true <%=(intl.useUnifiedSidebar() ? "checked" : "")%>>
<%=intl._t("Use the same sidebar on all pages")%></label>
<input type=hidden name=unifiedSidebar value=false>
<label id=stickySidebar><b><%=intl._t("Sticky sidebar")%>:</b>
<input type=checkbox class="optbox slider" name=stickySidebar value=true <%=(intl.useStickySidebar() ? "checked" : "")%>>
<%=intl._t("Enable conditional fixed sidebar")%></label>
<input type=hidden name=unifiedSidebar value=false>
</td>
<td class=right><input type=submit name=action class=accept value="<%=intl._t("Save")%>"></td>
</tr>
</table>
</form>
<h3 class=tabletitle><%=intl._t("Graph Options")%></h3>
<form method=POST>
<table class=configtable id=graphoptions>
<tr>
<td>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=group value=1>
<label id=legacyGraph><b><%=intl._t("Legacy renderer")%>:</b>
<input type=checkbox class="optbox slider" name=sidebarGraphLegacy value=true <%=(intl.useLegacySidebarGraph() ? "checked" : "")%>>
<%=intl._t("Use RRD4J renderer")%></label>
<input type=hidden name=sidebarGraphLegacy value=false>
<label><b><%=intl._t("Display period")%>:</b>
<input type=text name=sidebarGraphMinutes maxlength=2 pattern="[0-9]{1,2}" required value="<%=intl.getSidebarGraphMinutes()%>">
<%=intl._t("minutes (2–30)")%></label>
<label id=splitGraph><b><%=intl._t("Split display")%>:</b>
<input type=checkbox class="optbox slider" name=sidebarGraphSplit value=true <%=(intl.useSidebarGraphSplit() ? "checked" : "")%>>
<%=intl._t("Separate Inbound and Outbound")%></label>
<input type=hidden name=sidebarGraphSplit value=false>
<label id=graphDirection><b><%=intl._t("Graph direction")%>:</b>
<input type=checkbox class="optbox slider" name=sidebarGraphDirection value=ltr <%="ltr".equals(intl.getSidebarGraphDirection()) ? "checked" : ""%>>
<%=intl._t("Left-to-right")%></label>
<input type=hidden name=sidebarGraphDirection value=rtl>
<label id=scrollGraph><b><%=intl._t("Refresh Interval")%>:</b>
<input type=checkbox class="optbox slider" name=sidebarGraphContinuous value=true <%=(intl.useSidebarGraphContinuous() ? "checked" : "")%>>
<%=intl._t("Update graph every second")%></label>
<input type=hidden name=sidebarGraphContinuous value=false>
</td>
<td class=right><input type=submit name=action class=accept value="<%=intl._t("Save")%>"></td>
</tr>
</table>
</form>
<h3 class=tabletitle><%=intl._t("Customize Sidebar")%></h3>
<form id=form_sidebar action=/updatesidebar method=POST>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=group value=2>
<jsp:getProperty name="sidebarhelper" property="configTable"/>
<div class=formaction id=sidebardefaults>
<input type=submit class=reload name=action value="<%=intl._t("Restore full default")%>">
<input type=submit class=reload name=action value="<%=intl._t("Restore minimal default")%>">
</div>
</form>
</div>
<script nonce="<%=cspNonce%>">document.dispatchEvent(new Event("sidebarRefreshed"));</script>
</body>
</html>