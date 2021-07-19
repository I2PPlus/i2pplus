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
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
%>
<%@include file="summary.jsi" %>

<h1 class="nfo"><%=intl._t("Router Console")%></h1>
<div class="news" id="news">
<%
   if (newshelper.shouldShowNews()) {
%>
 <jsp:getProperty name="newshelper" property="content" />
 <hr>
<%
   }  // shouldShowNews()
%>
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=i2pcontextId%>" />
 <jsp:getProperty name="updatehelper" property="newsStatus" /><br>
</div>
<div class="main" id="console">
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <div class="welcome">
  <div class="langbox" title="<%=intl._t("Configure Language")%>">
  <a href="/configui#langheading"><img src="/themes/console/images/flags.png" alt=<%=intl._t("Configure Language")%>></a>
  </div>
  <a name="top"></a>
  <h2><%=intl._t("Welcome to I2P")%></h2>
 </div>
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "docs/readme.html"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="300" />
 <jsp:setProperty name="contenthelper" property="contextId" value="<%=i2pcontextId%>" />
 <jsp:getProperty name="contenthelper" property="content" />
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>
