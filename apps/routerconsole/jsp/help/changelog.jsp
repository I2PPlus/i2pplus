<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
    String pageTitlePrefix = ctx.getProperty("routerconsole.pageTitlePrefix");
    pageTitlePrefix = (pageTitlePrefix != null) ? pageTitlePrefix + ' ' : "";
%>
<%@include file="../head.jsi"%>
<title><%=pageTitlePrefix%> <%=intl._t("Change Log")%> - I2P+</title>
</head>
<body>
<%@include file="../sidebar.jsi"%><h1 class=hlp><%=intl._t("Change Log")%></h1>
<div class=main id=help>
<div class=confignav>
<span class=tab><a href="/help/configuration" title="<%=intl._t("Configuration")%>"><%=intl._t("Configuration")%></a></span>
<span class=tab><a href="/help/advancedsettings" title="<%=intl._t("Advanced Settings")%>"><%=intl._t("Advanced Settings")%></a></span>
<span class=tab><a href="/help/ui" title="<%=intl._t("User Interface")%>"><%=intl._t("User Interface")%></a></span>
<span class=tab><a href="/help/reseed" title="<%=intl._t("Reseeding")%>"><%=intl._t("Reseeding")%></a></span>
<span class=tab><a href="/help/tunnelfilter" title="<%=intl._t("Tunnel Filtering")%>"><%=intl._t("Tunnel Filtering")%></a></span>
<span class=tab><a href="/help/faq" title="<%=intl._t("Frequently Asked Questions")%>"><%=intl._t("FAQ")%></a></span>
<span class=tab><a href="/help/newusers" title="<%=intl._t("New User Guide")%>"><%=intl._t("New User Guide")%></a></span>
<span class=tab><a href="/help/webhosting" title="<%=intl._t("Web Hosting")%>"><%=intl._t("Web Hosting")%></a></span>
<span class=tab><a href="/help/hostnameregistration" title="<%=intl._t("Hostname Registration")%>"><%=intl._t("Hostname Registration")%></a></span>
<span class=tab><a href="/help/troubleshoot" title="<%=intl._t("Troubleshoot")%>"><%=intl._t("Troubleshoot")%></a></span>
<span class=tab><a href="/help/glossary" title="<%=intl._t("Glossary")%>"><%=intl._t("Glossary")%></a></span>
<span class=tab><a href="/help/legal" title="<%=intl._t("Legal")%>"><%=intl._t("Legal")%></a></span>
<span class=tab2 id=hist><span><%=intl._t("Change Log")%></span></span>
</div>
<div id=changelog>
<h2><%=intl._t("Recent I2P+ Changes")%></h2>
<jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request"/>
<% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt");%>
<jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>"/>
<jsp:setProperty name="contenthelper" property="maxLines" value="768"/>
<jsp:setProperty name="contenthelper" property="startAtBeginning" value="true"/>
<jsp:getProperty name="contenthelper" property="textContent"/>
<p id=fullhistory><a href="/history.txt" target="_blank" rel="noreferrer"><%=intl._t("View the full change log")%></a></p>
</div>
</div>
<script src=/js/lazyload.js></script>
<script src=/js/changelog.js></script>
</body>
</html>