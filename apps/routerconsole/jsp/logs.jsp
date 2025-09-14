<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
    boolean isX86 = net.i2p.util.SystemVersion.isX86();
%>
<%@include file="head.jsi"%>
<%=intl.title("logs")%>
</head>
<body id=i2plogs>

<%@include file="sidebar.jsi"%>
<h1 class=log><%=intl._t("Logs")%></h1>
<div class=main id=logs>
<div class=confignav>
<span class=tab><a href=/routerlogs><%=intl._t("Router")%></a></span>
<span class=tab><a href=/servicelogs><%=intl._t("Service")%></a></span>
<span class=tab2><span><%=intl._t("Combined")%></span></span>
<span class=tab><a href="/events?from=604800"><%=intl._t("Events")%></a></span>
</div>
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request"/>
<jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>"/>
<%  if (!logsHelper.isAdvanced()) { %>
<table id=bugreports>
<tbody>
<tr><td class=infohelp>
<%=intl._t("Please include your I2P version and running environment information in bug reports")%>.
<%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%>
<%=intl._t("Please report bugs on {0} or {1}.", "<a href=\"http://git.idk.i2p/I2P_Developers/i2p.i2p/issues\">git.idk.i2p</a>", "<a href=\"https://github.com/I2PPlus/i2pplus/issues\">I2P+ Gitlab</a>")%>
</td></tr>
</tbody>
</table>
<h3 class=tabletitle id=version><%=intl._t("I2P Version and Running Environment")%>&ensp;<a href="/events?from=604800"><%=intl._t("View event log")%></a></h3>
<table id=enviro>
<tbody>
<tr><td><b>I2P:</b></td><td><%=net.i2p.router.RouterVersion.FULL_VERSION%>&ensp;<b>API:</b>&ensp;<%=net.i2p.CoreVersion.PUBLISHED_VERSION%>&ensp;<b>Wrapper:</b>&ensp;<%=System.getProperty("wrapper.version", "none")%> &ensp;<b>Built by:</b>&ensp;<jsp:getProperty name="logsHelper" property="builtBy"/></td></tr>
<tr><td><b>Platform:</b></td><td><%=System.getProperty("os.name")%>&ensp;<%=System.getProperty("os.arch")%>&ensp;<%=System.getProperty("os.version")%></td></tr>
<tr><td><b>Processor:</b></td><td><span id=cputype><%=net.i2p.util.NativeBigInteger.cpuType().replace("zen2", "zen3 or later")%></span>
<%      if (isX86) { %>
&ensp;<%=net.i2p.util.NativeBigInteger.cpuModel()%>
<%      } %>
&ensp;<span class=nowrap>[Jcpuid version: <%=freenet.support.CPUInformation.CPUID.getJcpuidVersion()%></span>]</td></tr>
<tr><td><b>Java:</b></td><td><%=System.getProperty("java.vendor")%>&ensp;<%=System.getProperty("java.version")%>&ensp;(<%=System.getProperty("java.runtime.name")%>&ensp;<%=System.getProperty("java.runtime.version")%>)</td></tr>
<jsp:getProperty name="logsHelper" property="unavailableCrypto"/>
<tr><td><b>Jetty:</b></td><td><jsp:getProperty name="logsHelper" property="jettyVersion"/>&ensp;<b>Servlet:</b>&ensp;<%=getServletInfo()%> (<%=getServletConfig().getServletContext().getMajorVersion()%>.<%=getServletConfig().getServletContext().getMinorVersion()%>)</td></tr>
<tr><td><b>JBigI:</b></td><td><%=net.i2p.util.NativeBigInteger.loadStatus()%>&ensp;<span class=nowrap>[version: <%=net.i2p.util.NativeBigInteger.getJbigiVersion()%>]</span>&ensp;<span class=nowrap><b>GMP:</b>&ensp;<%=net.i2p.util.NativeBigInteger.getLibGMPVersion()%></span></td></tr>
<tr><td><b>JSTL:</b></td><td><jsp:getProperty name="logsHelper" property="jstlVersion"/>&ensp;<span class=nowrap><b>Encoding:</b>&ensp;<%=System.getProperty("file.encoding")%></span>&ensp;<span class=nowrap><b>Charset:</b>&ensp;<%=java.nio.charset.Charset.defaultCharset().name()%></span></td></tr>
</tbody>
</table>
<%  } // isAdvanced
    final String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
    final String ct1 = request.getParameter("clear");
    final String ct2 = request.getParameter("crit");
    final String ct3 = request.getParameter("svc");
    final String ct4 = request.getParameter("svct");
    final String ct5 = request.getParameter("svcf");
    final String ctn = request.getParameter("consoleNonce");
    int last = logsHelper.getLastCriticalMessageNumber();
    boolean hasCritical = last >= 0;
%>

<h3 id=critLogsHead class=tabletitle <%=(hasCritical ? "" : "hidden")%>><%=intl._t("Critical / Error Level Logs")%>
<%  if ((ct1 != null || ct2 != null || (ct3 != null && ct4 != null && ct5 != null)) && ctn != null) {
        int ict1 = -1, ict2 = -1;
        long ict3 = -1, ict4 = -1;
        try { ict1 = Integer.parseInt(ct1); } catch (NumberFormatException nfe) {}
        try { ict2 = Integer.parseInt(ct2); } catch (NumberFormatException nfe) {}
        try { ict3 = Long.parseLong(ct3); } catch (NumberFormatException nfe) {}
        try { ict4 = Long.parseLong(ct4); } catch (NumberFormatException nfe) {}
        logsHelper.clearThrough(ict1, ict2, ict3, ict4, ct5, ctn);
    }
%>
&nbsp;<a id=clearCritical class=delete title="<%=intl._t("Clear logs")%>" href="logs?crit=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a></h3>
<table id=criticallogs class=logtable <%=(hasCritical ? "" : "hidden")%>>
<tbody><tr><td><jsp:getProperty name="logsHelper" property="criticalLogs"/></td></tr></tbody>
</table>

<h3 class=tabletitle id=routerlogs_h3><%=intl._t("Router Logs")%>
<%  last = logsHelper.getLastMessageNumber();
    if (last >= 0) {
%>
&nbsp;<a class=delete title="<%=intl._t("Clear logs")%>" href="logs?clear=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
&nbsp;<a class=configure title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a>
&nbsp;<a id=eventlogLink title="<%=intl._t("View event log")%>" href="/events?from=604800">[<%=intl._t("Events")%>]</a>
&nbsp;<span id=toggleRefresh></span>
&nbsp;<span id=logFilter title="<%=intl._t("Filter Router log output")%>"><input type=text id=logFilterInput></span></h3>
<table id=routerlogs class=logtable>
<tbody><tr><td><jsp:getProperty name="logsHelper" property="logs"/></td></tr></tbody>
</table>

<h3 class=tabletitle id=servicelogs><%=intl._t("Service (Wrapper) Logs")%>
<%  StringBuilder buf = new StringBuilder(24*1024);
    // timestamp, last line number, escaped filename
    Object[] vals = logsHelper.getServiceLogs(buf);
    String lts = vals[0].toString();
    long llast = ((Long) vals[1]).longValue();
    String filename = vals[2].toString();
    if (llast >= 0) {
%>
&nbsp;<a class=delete title="<%=intl._t("Clear logs")%>" href="logs?svc=<%=llast%>&amp;svct=<%=lts%>&amp;svcf=<%=filename%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
</h3>
<table id=wrapperlogs class=logtable>
<tbody><tr><td><% out.append(buf);%></td></tr></tbody>
</table>
</div>
<script nonce=<%=cspNonce%> type=module src=/js/refreshLogs.js></script>
<noscript><style>#toggleRefresh,#refreshPeriod,#logFilter{display:none!important}</style></noscript>
</body>
</html>