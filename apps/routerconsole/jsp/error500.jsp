<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML>
<%
    // Let's make this easy...
    // These are defined in Jetty 7 org.eclipse.jetty.server.Dispatcher,
    // and in Servlet 3.0 (Jetty 8) javax.servlet.RequestDispatcher,
    // just use the actual strings here to make it compatible with either
    Integer ERROR_CODE = (Integer) request.getAttribute("javax.servlet.error.status_code");
    String ERROR_URI = (String) request.getAttribute("javax.servlet.error.request_uri");
    String ERROR_MESSAGE = (String) request.getAttribute("javax.servlet.error.message");
    final Throwable ERROR_THROWABLE = (Throwable) request.getAttribute("javax.servlet.error.exception");
    if (ERROR_CODE != null)
        response.setStatus(ERROR_CODE.intValue());
    else
        ERROR_CODE = Integer.valueOf(0);
    if (ERROR_URI != null)
        ERROR_URI = net.i2p.data.DataHelper.escapeHTML(ERROR_URI);
    else
        ERROR_URI = "";
    if (ERROR_MESSAGE != null)
        ERROR_MESSAGE = net.i2p.data.DataHelper.escapeHTML(ERROR_MESSAGE);
    else
        ERROR_MESSAGE = "";
%>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<%@include file="css.jsi" %>
<link type="text/css" rel="stylesheet" href="<%=intl.getTheme(request.getHeader("User-Agent"))%>proxy.css">
<%=intl.title("Internal Error")%>
</head>
<body id="error500">
<div id="sb_wrap">
<div class="sb" id="sidebar">
<a href="/" title="<%=intl._t("Router Console")%>">
<img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>images/i2plogo.png" alt="<%=intl._t("I2P Router Console").replace("I2P", "I2P+")%>" border="0"></a>
<hr>
<a href="/config"><%=intl._t("Configuration")%></a><br>
<a href="/help"><%=intl._t("Help")%></a>
</div>
</div>
<h1 class="err"><%=intl._t("ERROR")%>&ensp;<%=ERROR_CODE%>: <%=intl._t("Internal Server Error")%><br>
<!--<span id="errmsg"><%=ERROR_MESSAGE%></span>--></h1>
<!--
<div class="sorry" id="warning">
<%=intl._t("Sorry! There has been an internal error.")%>
<hr>
<p>
<% /* note to translators - both parameters are URLs */%>
<%=intl._t("Please report bugs on {0} or {1}.",
           "<a href=\"http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues\">git.idk.i2p</a>",
           "<a href=\"https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues\">i2pgit.org</a>")%>
<p><%=intl._t("Please include this information in bug reports")%>:</p>
</div>
-->
<div class="sorry" id="warning2">
<!--<h3><%=intl._t("Error Details")%></h3>-->
<div id="stacktrace">
<p><%=intl._t("Error {0}", ERROR_CODE)%>: &ensp;<%=ERROR_URI%>&ensp;-&ensp;<%=ERROR_MESSAGE%></p>
<p>
<%
    if (ERROR_THROWABLE != null) {
        java.io.StringWriter sw = new java.io.StringWriter(2048);
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        ERROR_THROWABLE.printStackTrace(pw);
        pw.flush();
        String trace = sw.toString();
        trace = trace.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        trace = trace.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
        out.print(trace);
    }
%>
</p>
</div>
<h3><%=intl._t("I2P Version and Running Environment")%></h3>
<p id="sysinfo">
<b>I2P version:</b>&ensp;<%=net.i2p.router.RouterVersion.FULL_VERSION%><br>
<b>API version:</b>&ensp;<%=net.i2p.CoreVersion.PUBLISHED_VERSION%><br>
<b>Java version:</b>&ensp;<%=System.getProperty("java.vendor")%>&ensp;<%=System.getProperty("java.version")%>&ensp;(<%=System.getProperty("java.runtime.name")%> <%=System.getProperty("java.runtime.version")%>)<br>
 <jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
 <jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:getProperty name="logsHelper" property="unavailableCrypto" />
<b>Wrapper version:</b>&ensp;<%=System.getProperty("wrapper.version", "none")%><br>
<b>Server version:</b>&ensp;<jsp:getProperty name="logsHelper" property="jettyVersion" /><br>
<b>Servlet version:</b>&ensp;<%=getServletInfo()%> (<%=getServletConfig().getServletContext().getMajorVersion()%>.<%=getServletConfig().getServletContext().getMinorVersion()%>)<br>
<b>Platform:</b>&ensp;<%=System.getProperty("os.name")%>&ensp;<%=System.getProperty("os.arch")%>&ensp;<%=System.getProperty("os.version")%><br>
<b>Processor:</b>&ensp;<%=net.i2p.util.NativeBigInteger.cpuModel()%>&ensp;(<%=net.i2p.util.NativeBigInteger.cpuType()%>)<br>
<b>JBigI:</b>&ensp;<%=net.i2p.util.NativeBigInteger.loadStatus()%><br>
<b>Encoding:</b>&ensp;<%=System.getProperty("file.encoding")%><br>
<b>Charset:</b>&ensp;<%=java.nio.charset.Charset.defaultCharset().name()%></p>
<p><%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%></p>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>