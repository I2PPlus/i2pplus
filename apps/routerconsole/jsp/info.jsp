<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("router information")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class=nfo><%=intl._t("Router Summary")%></h1>
<div class=main id=routerinformation>
<div class=tablewrap>
<h3 class=tabletitle id=version><%=intl._t("I2P Version and Running Environment")%><span class=h3navlinks style=float:right><a title="View Router Logs" href="/logs">View Logs</a></span></h3>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<jsp:setProperty name="logsHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>"/>
<table id=enviro>
<tbody>
<tr><td><b>I2P:</b></td><td><%=net.i2p.router.RouterVersion.FULL_VERSION%>&ensp;<b>API:</b>&ensp;<%=net.i2p.CoreVersion.PUBLISHED_VERSION%>&ensp;<b>Wrapper:</b>&ensp;<%=System.getProperty("wrapper.version", "none")%> &ensp;<b>Built by:</b>&ensp;<jsp:getProperty name="logsHelper" property="builtBy"/></td></tr>
<tr><td><b>Revision:</b></td><td><jsp:getProperty name="logsHelper" property="revision"/></td></tr>
<tr><td><b>Platform:</b></td><td><%=System.getProperty("os.name")%>&ensp;<%=System.getProperty("os.arch")%>&ensp;<%=System.getProperty("os.version")%></td></tr>
<tr><td><b>Processor:</b></td><td><span id=cputype><%=net.i2p.util.NativeBigInteger.cpuType().replace("zen2", "zen3 or later")%></span>
<%  boolean isX86 = net.i2p.util.SystemVersion.isX86();
    if (isX86) {
%>&ensp;<%=net.i2p.util.NativeBigInteger.cpuModel()%>
<%  } %>
</td></tr>
<tr><td><b>Java:</b></td><td><%=System.getProperty("java.vendor")%>&ensp;<%=System.getProperty("java.version")%>&ensp;(<%=System.getProperty("java.runtime.name")%>&ensp;<%=System.getProperty("java.runtime.version")%>)
<%  boolean recentJavaVersion = net.i2p.util.SystemVersion.isJava(17);
    if (!recentJavaVersion) {
%>
<br><span style=color:red>Warning: You are running an older version of Java that will soon no longer be supported. Please update to Java 17 or later to receive future router updates.</span>
<%  } %>
</td></tr>
<jsp:getProperty name="logsHelper" property="unavailableCrypto"/>
<tr><td><b>Jetty:</b></td><td><jsp:getProperty name="logsHelper" property="jettyVersion"/>&ensp;<b>Servlet:</b>&ensp;<%=getServletInfo()%></td></tr>
<tr><td><b>JBigI:</b></td><td><%=net.i2p.util.NativeBigInteger.loadStatus()%>&ensp;<span class=nowrap>[version: <%=net.i2p.util.NativeBigInteger.getJbigiVersion()%>]</span>&ensp;<span class=nowrap><b>GMP:</b>&ensp;<%=net.i2p.util.NativeBigInteger.getLibGMPVersion()%></span></td></tr>
<tr><td><b>JSTL:</b></td><td><jsp:getProperty name="logsHelper" property="jstlVersion"/>&ensp;<span class=nowrap><b>Encoding:</b>&ensp;<%=System.getProperty("file.encoding")%></span>&ensp;<span class=nowrap><b>Charset:</b>&ensp;<%=java.nio.charset.Charset.defaultCharset().name()%></span></td></tr>
</tbody>
</table>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.InfoHelper" id="infohelper" scope="request"/>
<jsp:setProperty name="infohelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>"/>
<% infohelper.storeWriter(out);%>
<div class=tablewrap>
<h3 class=tabletitle><%=intl._t("Router Information")%></h3>
<jsp:getProperty name="infohelper" property="console"/>
</div>
</div>
<script src=/js/refreshElements.js type=module></script>
<script nonce=<%=cspNonce%> type=module>
  import {refreshElements} from "/js/refreshElements.js";
  refreshElements(".ajax", "/info", 10000);
</script>
</body>
</html>