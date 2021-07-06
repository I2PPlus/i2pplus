<%@page contentType="text/html"%>
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
<%=intl.title("home")%>
</head>
<body id="homepage">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
%>
 <jsp:useBean class="net.i2p.router.web.NewsHelper" id="newshelper" scope="request" />
 <jsp:setProperty name="newshelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getRouterDir(), "docs/news.xml");
%>
 <jsp:setProperty name="newshelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="newshelper" property="maxLines" value="300" />
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>" />
<div class="routersummaryouter" style="width: 200px; float: left; margin-right: 20px;">
<div class="routersummary" id="sidebar">
<div id="sb_logo" style="height: 36px;">
<a href="/sitemap"><img width="200" src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/i2plogo.png" alt="<%=intl._t("I2P Router Console").replace("I2P", "I2P+")%>" title="<%=intl._t("I2P Router Console").replace("I2P", "I2P+")%>"></a>
</div>
<%
    if (!intl.allowIFrame(request.getHeader("User-Agent"))) {
%>
  <a href="/summaryframe"><%=intl._t("Sidebar")%></a>
<%
    }
%>
<div id="xhr">
<!-- for non-script -->
<%@include file="xhr1.jsi" %>
</div>
</div>
</div>
<h1 class="home"><%=intl._t("Router Console")%></h1>
<%
   if (newshelper.shouldShowNews()) {
%>
<div class="news" id="news">
<jsp:getProperty name="newshelper" property="content" />
<hr>
<jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div>
<%
   }  // shouldShowNews()
%>

<div class="main" id="home">
<jsp:useBean class="net.i2p.router.web.helpers.HomeHelper" id="homehelper" scope="request" />
<jsp:setProperty name="homehelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:useBean class="net.i2p.router.web.helpers.SearchHelper" id="searchhelper" scope="request" />
<jsp:setProperty name="searchhelper" property="contextId" value="<%=i2pcontextId%>" />
<div id="homepanel">
<div class="search" id="homesearch">
<form action="/search.jsp" target="_blank" rel="noreferrer" method="POST">
<table class="search">
<tr>
<td align="right"><input size="40" type="text" class="search" name="query" required placeholder="<%=intl._t("Please enter a search query")%>" /></td>
<td align="left"><button type="submit" value="search" class="search"><%=intl._t("Search")%></button></td>
<td align="left">
<jsp:getProperty name="searchhelper" property="selector" />
</td>
</tr>
</table>
</form>
</div>
<div class="ag2">
<h4 class="app"><%=intl._t("Applications and Configuration")%>
<a href="/confighome#configapps" style="float: right" title="<%=intl._t("Customize links")%>"><%=intl._t("Customize links")%></a>
<%
    if (homehelper.shouldShowWelcome()) {
%>
<a href="/configui#langheading" id="chooselang" style="float: right" title="Configure display language"><img src="/themes/console/images/info/flags.png" height="16" width="16" alt="Language"></a>
<%
    } // shouldShowWelcome
%>
</h4>
<jsp:getProperty name="homehelper" property="services" /><br>
</div>
<div class="ag2">
<h4 class="app2"><%=intl._t("Sites of Interest")%><a href="/confighome#configsites" style="float: right" title="<%=intl._t("Customize links")%>"><%=intl._t("Customize links")%></a></h4>
<jsp:getProperty name="homehelper" property="favorites" /><br>
<div class="clearer">&nbsp;</div>
</div>
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
