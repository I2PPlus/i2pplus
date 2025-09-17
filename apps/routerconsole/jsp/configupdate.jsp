<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("config update")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class=conf><%=intl._t("Router Updates")%></h1>
<div class=main id=config_update>
<%@include file="confignav.jsi"%>
<jsp:useBean class="net.i2p.router.web.ConfigUpdateHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request"/>
<jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>"/>
<%  boolean canInstall = updatehelper.canInstall();
    boolean isAdvanced = updatehelper.isAdvanced();
%>
<div class=messages id=statusNews>
<jsp:getProperty name="updatehelper" property="newsStatus"/>
</div>
<iframe name=processForm id=processForm hidden></iframe>
<form id=form_updates method=POST target=processForm>
<input type=hidden name=nonce value=<%=pageNonce%>>
<input type=submit name=action value="" style=display:none>
<h3 class=<%=canInstall ? "tabletitle" : ""%>><%=intl._t(canInstall ? "I2P &amp; News Updates" : "News Updates")%></h3>
<table id=i2pupdates class=configtable>
<%  if (!canInstall) { %>
<tr><td class=right><b><%=intl._t("News Updates")%>:</b></td></tr>
<%  } %>
<tr><td id=updateHelper><div class=optionlist>
<%  if (canInstall) { %>
<span class=nowrap><b><%=formhandler._t("I2P update policy")%>:</b><jsp:getProperty name="updatehelper" property="updatePolicySelectBox"/></span><br>
<%  } %>
<span class=nowrap><b><%=intl._t("Update check")%>:</b><jsp:getProperty name="updatehelper" property="refreshFrequencySelectBox"/></span><br>
<span class=nowrap id=checkforupdates>
<% if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) { %>
<i><%=intl._t("Update In Progress")%>&hellip;</i>
<%  } else { %>
<input type=submit name=action class=check value="<%=intl._t("Check for updates")%>">
<%  } %>
</span></div></td></tr>
<tr><td>
<b id=devSU3build class=suboption><jsp:getProperty name="updatehelper" property="updateDevSU3"/>
<label for=updateDevSU3><%=intl._t("Update with signed development builds")%></label></b>
<div class=optionsingle><span class=nowrap><b><%=intl._t("Update URL")%>:</b>
<input type=text size=60 name=devSU3URL value="<jsp:getProperty name="updatehelper" property="devSU3URL"/>"></span></div>
</td></tr>
<tr><td class=infohelp><%=intl._t("To update with unsigned I2P+ release builds: <code>http://skank.i2p/i2pupdate.zip</code> or for the latest unsigned development builds: <code>http://skank.i2p/dev/i2pupdate.zip</code>")%></td></tr>
<tr><td>
<b id=unsignedbuild class=suboption><jsp:getProperty name="updatehelper" property="updateUnsigned"/>
<label for=updateUnsigned><%=intl._t("Update with unsigned development builds")%></label></b>
<div class=optionsingle><span class=nowrap><b><%=intl._t("Update URL")%>:</b>
<input type=text size=60 name=zipURL value="<jsp:getProperty name="updatehelper" property="zipURL"/>"></span></div>
</td></tr>
<tr><td class=optionsave>
<input type=reset class=cancel value="<%=intl._t("Cancel")%>" hidden>
<input type=submit name=action class=accept value="<%=intl._t("Save")%>"></td></tr>
</table>
<input type=checkbox name=miscConfig id=miscConfig hidden>
<label class=tabletitle for=miscConfig><%=intl._t("Advanced Options")%></label>
<table id=newsupdateconfig class=configtable>
<%  if (canInstall) {
        if (isAdvanced) {
%>
<tr><td><b class=suboption title="<%=intl._t("In order to maintain anonymity, it is recommended to update using the I2P http proxy. If the news or I2P update is hosted on I2P, using the proxy will be necessary.")%>">
<jsp:getProperty name="updatehelper" property="newsThroughProxy"/>
<label for=newsThroughProxy><%=intl._t("Fetch news using I2P http proxy")%></label>
<jsp:getProperty name="updatehelper" property="updateThroughProxy"/>
<label for=updateThroughProxy><%=intl._t("Update I2P using I2P http proxy")%></label>
</b>
<div class=optionlist id=updateProxyHostPort>
<span class=nowrap><b><%=intl._t("I2P proxy host")%>:</b>
<input type=text size=10 name=proxyHost value="<jsp:getProperty name="updatehelper" property="proxyHost"/>"></span><br>
<span class=nowrap><b><%=intl._t("I2P proxy port")%>:</b>
<input type=text size=10 name=proxyPort value="<jsp:getProperty name="updatehelper" property="proxyPort"/>"></span>
</div></td></tr>
<tr><td><div class=optionsingle>
<span class=nowrap><b><%=intl._t("News URL")%>:</b>
<input type=text size=60 name=newsURL <%= isAdvanced ? "" : "readonly" %> value="<jsp:getProperty name="updatehelper" property="newsURL"/>"></span></div></td></tr>
<%      } %>
<tr title="<%=intl._t("If I2P cannot update via BitTorrent, it will try these addresses in the order they are listed")%>"><td>
<div class="optionsingle optiontextarea"><span class=nowrap>
<b><%=intl._t("Update URLs")%>:</b>
<textarea cols=60 rows=6 name=updateURL wrap=off spellcheck=false><jsp:getProperty name="updatehelper" property="updateURL"/></textarea>
</span></div></td></tr>
<tr title="<%=intl._t("These keys are used to validate official router updates, signed development builds and the official news feed")%>"><td>
<div class="optionsingle optiontextarea"><span class=nowrap>
<b><%=intl._t("Trusted keys")%>:</b>
<textarea cols=60 rows=6 name=trustedKeys wrap=off spellcheck=false><jsp:getProperty name="updatehelper" property="trustedKeys"/></textarea>
</span></div></td></tr>
<%  } else { %>
<tr><td class=infohelp><b><%=intl._t("Updates will be dispatched via your package manager.")%></b></td></tr>
<%  } %>
<tr><td class=optionsave><input type=submit name=action class=accept value="<%=intl._t("Save")%>"></td></tr>
</table>
</form>
</div>
<script src=/js/formsubmit.js></script>
<script src=/js/refreshElements.js></script>
<script nonce=<%=cspNonce%>>refreshElements("#statusNews", "/configupdate", 10000);</script>
</body>
</html>