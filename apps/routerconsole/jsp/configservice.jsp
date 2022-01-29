<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<jsp:setProperty name="tester" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% boolean embedApps = tester.embedApps(); %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<%=intl.title("config service")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf"><%=intl._t("Router Service")%></h1>

<div class="main" id="config_service">
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.ConfigServiceHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >

<div class="service_container">
<h3 class="ptitle" id="shutdownrouter"><%=intl._t("Shutdown the router")%></h3>
<p class="infohelp">
<%=intl._t("Graceful mode allows your router to honor its participating tunnel commitments before shutting down or restarting.")%></p>
<hr>
<div class="formaction" id="shutdown">
<input type="submit" class="stop" name="action" value="<%=intl._t("Shutdown gracefully")%>" >
<input type="submit" class="stop" name="action" value="<%=intl._t("Shutdown immediately")%>" >
<% if (formhandler.shouldShowCancelGraceful()) { %>
<input type="submit" class="cancel" name="action" value="<%=intl._t("Cancel graceful shutdown")%>" >
<% } %>
</div>
</div>

<% if (net.i2p.util.SystemVersion.hasWrapper()) { %>
<div class="service_container">
<h3 class="ptitle" id="restartrouter"><%=intl._t("Restart the router")%></h3>
<p class="infohelp">
<%=intl._t("A restart may be required to update the router, implement a configuration change, or complete installation of a plugin.")%></p>
<hr>
<div class="formaction" id="restart">
<input type="submit" class="reload" name="action" value="<%=intl._t("Graceful restart")%>" >
<input type="submit" class="reload" name="action" value="<%=intl._t("Hard restart")%>" >
</div>
</div>
<% } %>

<% if (!net.i2p.util.SystemVersion.isService()) { %>
<div class="service_container">
<h3 class="ptitle" id="browseronstart"><%=intl._t("Launch console at startup")%>&nbsp;<span class="h3navlinks">
<a href="/help/faq#alternative_browser" title="<%=intl._t("Help with configuring I2P to use a non-system default browser")%>">
<img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/help.png"></a></span></h3>
<p class="infohelp">
<%=intl._t("Launch I2P Router Console in web browser when I2P starts.").replace("I2P", "I2P+")%>
</p>
<hr>
<div class="formaction" id="browserstart">
<!-- TODO hide inert button -->
<input type="submit" class="hideconsole" name="action" value="<%=intl._t("No console on startup")%>" >
<input type="submit" class="showconsole" name="action" value="<%=intl._t("Open console on startup")%>" >
</div>
</div>
<% } %>

<div class="service_container">
<h3 class="ptitle" id="servicedebug"><%=intl._t("Debugging")%></h3>
<p class="infohelp">
<% if (net.i2p.util.SystemVersion.hasWrapper()) { %>
<%=intl._t("To assist in debugging I2P, it may be helpful to generate a thread dump which will be written to the {0}wrapper log{1}.", " <a href=\"/wrapper.log\" target=\"_blank\">", "</a>").replace("I2P", "I2P+")%>
<% } else { %>
<%=intl._t("If I2P appears to be using too much ram, you can force the router to run the garbage collection routine to free up memory.").replace("I2P", "I2P+")%>
<% } %>
</p>
<hr>
<div class="formaction" id="dumpthreads">
<input type="submit" class="reload" name="action" value="<%=intl._t("Force garbage collection")%>" >
<% if (net.i2p.util.SystemVersion.hasWrapper()) { %>
<input type="submit" class="download" name="action" value="<%=intl._t("Dump threads")%>" >
<% } %>
</div>
</div>

<!-- this appears to be borked (linux) ... should probably hide this to avoid disappointment... -->
<!-- for now, let's just enable on windows -->
<% if ( (System.getProperty("os.name") != null) && (System.getProperty("os.name").startsWith("Win")) ) { %>
<%     if (formhandler.shouldShowSystray()) { %>
<div class="service_container">
<h3 class="ptitle" id="systray"><%=intl._t("System Tray Integration")%></h3>
<p class="infohelp"><%=intl._t("Enable or disable the I2P system tray applet to enable basic service control.")%></p>
<hr>
<div class="formaction" id="systray">
<%         if (!formhandler.isSystrayEnabled()) { %>
<input type="submit" name="action" class="accept" value="<%=intl._t("Show systray icon")%>" >
<%         } else { %>
<input type="submit" name="action" class="cancel" value="<%=intl._t("Hide systray icon")%>" >
<%         } %>
</div>
</div>
<%     } %>
<% } // is Windows Service? %>

<% if (net.i2p.util.SystemVersion.isWindowsService()) { %>
<div class="service_container">
<h3 class="ptitle" id="runonstartup"><%=intl._t("Configure I2P system service").replace("I2P", "I2P+")%></h3>
<p class="infohelp">
<%=intl._t("Configure the status of the I2P service at system startup. If disabled, the service can be launched manually.").replace("I2P", "I2P+")%>
<hr>
<div class="formaction" id="runonstart">
<input type="submit" name="action" class="cancel" value="<%=intl._t("Disable I2P service")%>" >
<input type="submit" name="action" class="accept" value="<%=intl._t("Enable I2P service")%>" >
</div>
</div>

<% } else if ((System.getProperty("os.name") != null) && (System.getProperty("os.name").startsWith("Win")) && !net.i2p.util.SystemVersion.isWindowsService()) { %>
<div class="service_container">
<h3 class="ptitle" id="runonstartup"><%=intl._t("Install I2P system service").replace("I2P", "I2P+")%></h3>
<p class="infohelp">
<%=intl._t("Install the I2P system service. This is the recommended way to run I2P.").replace("I2P", "I2P+")%>
<hr>
<div class="formaction" id="installservice">
<input type="submit" name="action" class="accept" value="<%=intl._t("Install I2P service")%>" >
</div>
</div>
<% } // is Windows? %>

</form>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>