<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="64kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("config advanced")%>
</head>
<body>
<script nonce=<%=cspNonce%>>
  progressx.show(theme);progressx.progress(0.1);
  const msgKeyRemove = "<%=intl._t("Key <b>{0}</b> selected for removal. To commit the change, save the configuration, or cancel to restore the key.")%>";
</script>
<%@include file="sidebar.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHelper" id="advancedhelper" scope="request"/>
<jsp:setProperty name="advancedhelper" property="contextId" value="<%=i2pcontextId%>"/>
<h1 class=conf><%=intl._t("Advanced Configuration")%></h1>
<div class=main id=config_advanced>
<%@include file="confignav.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigAdvancedHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<div class=configure>
<div class=wideload>
<h3 id=ffconf class=tabletitle><%=intl._t("Floodfill Configuration")%></h3>
<form method=POST>
<table id=floodfillconfig class=configtable hidden>
<tr>
<td class=infohelp>
<%=intl._t("Floodfill participation helps the network, but may use more of your computer's resources.")%>
<% if (advancedhelper.isFloodfill()) { %>(<%=intl._t("This router is currently a floodfill participant.")%>)
<% } else { %>(<%=intl._t("This router is not currently a floodfill participant.")%>)<% } %>
</td>
</tr>
<tr>
<td>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=action value="ff">
<b><%=intl._t("Enrollment")%>:</b>
<label><input type=radio class=optbox name="ff" value=auto <%=advancedhelper.getFFChecked(2)%>>
<%=intl._t("Automatic")%></label>&nbsp;
<label><input type=radio class=optbox name="ff" value=true <%=advancedhelper.getFFChecked(1)%>>
<%=intl._t("Force On")%></label>&nbsp;
<label><input type=radio class=optbox name="ff" value=false <%=advancedhelper.getFFChecked(0)%>>
<%=intl._t("Disable")%></label>
</td>
</tr>
<tr><td class=optionsave><input type=submit name="shouldsave" class=accept value="<%=intl._t("Save changes")%>"></td></tr>
</table>
</form>
<h3 id=advancedconfig class=tabletitle><%=intl._t("Advanced I2P+ Configuration")%>&nbsp;<span class=h3navlinks><a title="<%=intl._t("Help with additional configuration settings")%>" href=/help/advancedsettings>[Additional Options]</a></span></h3>
<%
    String advConfig = advancedhelper.getSettings();
    boolean isAdvanced = advancedhelper.isAdvanced();
    if (isAdvanced) {
%>
<form id=advConfigForm method=POST>
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name="action" value=blah>
<input type=hidden name="nofilter_oldConfig" value="<%=advConfig%>">
<div id=advconfwrapper>
<table class=configtable id=advconf>
<thead hidden><tr><td class=infohelp></td></tr></thead>
<tbody>
<tr id=text_advconfig hidden><td class=tabletextarea><textarea id=advancedsettings name="nofilter_config" wrap=off spellcheck=false><%=advConfig%></textarea></td></tr>
</table>
</div>
<div id=saveConfig class=optionsave><input type=reset class=cancel value="<%=intl._t("Cancel")%>"><input type=submit name="shouldsave" class=accept value="<%=intl._t("Save changes")%>"></div>
</form>
<%
    } else {  // isAdvanced
%>
<table class="configtable readonly" id=advconf>
<tr><td class=infohelp colspan=2><%=intl._t("To make changes, edit the file: {0}", "<code>" + advancedhelper.getConfigFileName() + "</code>")%></td></tr>
<%=advConfig%>
</table>
<%
    }
%>
</div>
</div>
</div>
<script src=/js/toggleElements.js></script>
<script nonce=<%=cspNonce%>>document.addEventListener("DOMContentLoaded", () => { setupToggles("#ffconf", "#ffconf+form", "block"); });</script>
<% if (isAdvanced) { %>
<script src=/js/advconfig.js type=module></script>
<noscript><style>#advancedsettings{display:block!important}</style></noscript>
<% } else { %>
<style>#advancedsettings{display:block!important}</style>
<% } %>
<noscript><style>#advconf.readonly tr.section{pointer-events:none}#advconf.readonly tr.section th::after{display:none}#floodfillconfig{display:table!important}</style></noscript>
<% if (!isAdvanced) { %><script src=/js/tableSectionToggler.js type=module></script><% } %>
<script src=/js/ok.js></script>
</body>
</html>