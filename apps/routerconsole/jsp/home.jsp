<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="80kb" %>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<% String consoleNonce = net.i2p.router.web.CSSHelper.getNonce(); %>
<%@include file="head.jsi" %>
<%=intl.title("home")%>
</head>
<body id=homepage>
<jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request"/>
<jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>"/>

<%@include file="sidebar.jsi" %>
<h1 class=home><%=intl._t("Router Console")%></h1>
<% if (newshelper.shouldShowNews()) { %>
<div class=news id=news>
<jsp:getProperty name="newshelper" property="content"/>
<hr>
<jsp:getProperty name="updatehelper" property="newsStatus"/><br>
</div>
<% } %>

<div class=main id=home>
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request"/>
<jsp:setProperty name="homehelper" property="contextId" value="<%=i2pcontextId%>"/>
<jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request"/>
<jsp:setProperty name="searchhelper" property="contextId" value="<%=i2pcontextId%>"/>
<div id=homepanel>
<div id=searchbar>
<form action=/search.jsp target=_blank rel=noreferrer method=POST>
<table>
<tr><td><div><jsp:getProperty name="searchhelper" property="selector"/><input size=40 type=text class=search name=query required placeholder="<%=intl._t("Please enter a search query")%>"><button type=submit value="search" class=search><%=intl._t("Search")%></button></div></td></tr>
</table>
</form>
</div>
<div class=linkcontainer>
<h4 id=applinks><%=intl._t("Applications and Configuration").replace(" and ", " &amp; ")%>
<span class=headerlinks>
<a href=/confighome#configapps class=customizelinks style=float:right title="<%=intl._t("Customize links")%>"><%=intl._t("Customize links")%></a>
<% if (homehelper.shouldShowWelcome()) { %>
<a href=/configui#langheading id=chooselang style=float:right title="<%=intl._t("Configure display language, theme and optional console password")%>"><img src=/themes/console/images/flags.png height=16 width=16 alt=<%=intl._t("Language")%>></a>
<% }
   if (homehelper.shouldShowBandwidthConfig()) { %>
<a href=/config id=configbandwidth style=float:right title="<%=intl._t("Configure router bandwidth")%>"><img src="/themes/console/images/speedometer.svg" height=16 width=16 alt="Bandwidth"></a>
<% } %>
</span>
</h4>
<jsp:getProperty name="homehelper" property="services"/><br>
</div>
<div class=linkcontainer>
<h4 id=sitelinks><%=intl._t("Sites of Interest")%>
<span class=headerlinks>
<a href=/confighome#configsites class=customizelinks style=float:right title="<%=intl._t("Customize links")%>"><%=intl._t("Customize links")%></a>
</span>
</h4>
<jsp:getProperty name="homehelper" property="favorites"/><br>
<div class=clearer>&nbsp;</div>
</div>
</div>
</div>
</body>
</html>