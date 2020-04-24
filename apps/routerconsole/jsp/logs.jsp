<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("logs")%>
<!--<script src="/js/scrollTo.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>-->
<%@include file="summaryajax.jsi" %>
</head>
<body id="i2plogs">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<noscript><style type="text/css">.script {display: none;}</style></noscript>
<h1 class="log"><%=intl._t("Logs")%></h1>
<div class="main" id="logs">
<table id="bugreports">
<tbody>
<tr><td class="infohelp">
<%=intl._t("Please include your I2P version and running environment information in bug reports")%>.
<%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%>
<% /* note to translators - both parameters are URLs */
%>&ensp;<%=intl._t("Please report bugs on {0} or {1}.",
          "<a href=\"http://trac.i2p2.i2p/\">trac.i2p2.i2p</a>",
          "<a href=\"https://trac.i2p2.de/\">trac.i2p2.de</a>")%>
</td></tr>
</tbody>
</table>
<h3 class="tabletitle" id="version"><%=intl._t("I2P Version and Running Environment")%>&ensp;<a href="/events?from=604800"><!-- 1 week --><%=intl._t("View event log")%></a></h3>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
<jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>" />
<table id="enviro">
<tbody>
<tr><td><b>I2P:</b></td><td><%=net.i2p.router.RouterVersion.FULL_VERSION%>&ensp;<b>Wrapper:</b>&ensp;<%=System.getProperty("wrapper.version", "none")%> &ensp;<b>Built by:</b>&ensp;<jsp:getProperty name="logsHelper" property="builtBy" /></td></tr>
<tr><td><b>Platform:</b></td><td><%=System.getProperty("os.name")%>&ensp;<%=System.getProperty("os.arch")%>&ensp;<%=System.getProperty("os.version")%></td></tr>
<%
   boolean isX86 = net.i2p.util.SystemVersion.isX86();
   if (isX86) {
%>
<%
   }
%><tr><td><b>Processor:</b></td><td><span id="cputype"><%=net.i2p.util.NativeBigInteger.cpuType()%></span>
<%
   if (isX86) {
%>&ensp;<%=net.i2p.util.NativeBigInteger.cpuModel()%>
<%
   }
%>
&ensp;<span class="nowrap">[Jcpuid version: <%=freenet.support.CPUInformation.CPUID.getJcpuidVersion()%></span>]</td></tr>
<tr><td><b>Java:</b></td><td><%=System.getProperty("java.vendor")%>&ensp;<%=System.getProperty("java.version")%>&ensp;(<%=System.getProperty("java.runtime.name")%>&ensp;<%=System.getProperty("java.runtime.version")%>)</td></tr>
<jsp:getProperty name="logsHelper" property="unavailableCrypto" />
<tr><td><b>Jetty:</b></td><td><jsp:getProperty name="logsHelper" property="jettyVersion" />&ensp;<b>Servlet:</b>&ensp;<%=getServletInfo()%></td></tr>
<tr><td><b>JBigI:</b></td><td><%=net.i2p.util.NativeBigInteger.loadStatus()%>&ensp;<span class="nowrap">[version: <%=net.i2p.util.NativeBigInteger.getJbigiVersion()%>]</span>&ensp;<span class="nowrap"><b>GMP:</b>&ensp;<%=net.i2p.util.NativeBigInteger.getLibGMPVersion()%></span></td></tr>
<tr><td><b>JSTL:</b></td><td><jsp:getProperty name="logsHelper" property="jstlVersion" />&ensp;<span class="nowrap"><b>Encoding:</b>&ensp;<%=System.getProperty("file.encoding")%></span>&ensp;<span class="nowrap"><b>Charset:</b>&ensp;<%=java.nio.charset.Charset.defaultCharset().name()%></span></td></tr>
</tbody>
</table>
<h3 class="tabletitle"><%=intl._t("Critical Logs")%></h3>
<table id="criticallogs" class="logtable">
<tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
</td></tr>
</tbody>
</table>
<h3 class="tabletitle"><%=intl._t("Router Logs")%>&ensp;<a title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a></h3>
<table id="routerlogs" class="logtable">
<tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="logs" />
</td></tr>
</tbody>
</table>
<h3 class="tabletitle" id="servicelogs"><%=intl._t("Service (Wrapper) Logs")%></h3>
<table id="wrapperlogs" class="logtable">
<tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="serviceLogs" />
</td></tr>
</tbody>
</table>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    progressx.show();
    progressx.progress(0.5);
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/logs?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("i2plogs").innerHTML = xhr.responseText;
      }
    }
    xhr.send();
    progressx.hide();
  }, 30000);
</script>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
