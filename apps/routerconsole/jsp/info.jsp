<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("router information")%>
<script src="/js/ajax.js" type="text/javascript"></script>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf adv"><%=intl._t("Router Summary")%></h1>
<div class="main" id="routerinformation">
<h3 class="tabletitle" id="version"><%=intl._t("I2P Version and Running Environment")%><span class="h3navlinks" style="float: right;"><a title="View Router Logs" href="/logs">View Logs</a></span></h3>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
<jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<table id="enviro">
<tbody>
<tr><td><b>I2P:</b></td><td><%=net.i2p.router.RouterVersion.FULL_VERSION%>&ensp;<b>API:</b>&ensp;<%=net.i2p.CoreVersion.PUBLISHED_VERSION%>&ensp;<b>Wrapper:</b>&ensp;<%=System.getProperty("wrapper.version", "none")%> &ensp;<b>Built by:</b>&ensp;<jsp:getProperty name="logsHelper" property="builtBy" /></td></tr>
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
<jsp:useBean class="net.i2p.router.web.helpers.InfoHelper" id="infohelper" scope="request" />
<jsp:setProperty name="infohelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<% infohelper.storeWriter(out); %>
<h3 class="tabletitle"><%=intl._t("Router Information")%></h3>
<jsp:getProperty name="infohelper" property="console" />
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>