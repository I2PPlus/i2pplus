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
<%=intl.title("logs")%>
</head>
<body id="i2plogs">
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="log"><%=intl._t("Logs")%></h1>
<div class="main" id="logs">
<jsp:useBean class="net.i2p.router.web.helpers.LogsHelper" id="logsHelper" scope="request" />
<jsp:setProperty name="logsHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    if (!logsHelper.isAdvanced()) {
%>
<table id="bugreports">
<tbody>
<tr><td class="infohelp">
<%=intl._t("Please include your I2P version and running environment information in bug reports")%>.
<%=intl._t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report.")%>
<%=intl._t("Please report bugs on {0} or {1}.", "<a href=\"http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues\">git.idk.i2p</a>", "<a href=\"https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues\">i2pgit.org</a>")%>
</td></tr>
</tbody>
</table>
<h3 class="tabletitle" id="version"><%=intl._t("I2P Version and Running Environment")%>&ensp;<a href="/events?from=604800"><!-- 1 week --><%=intl._t("View event log")%></a></h3>
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
<tr><td><b>Jetty:</b></td><td><jsp:getProperty name="logsHelper" property="jettyVersion" />&ensp;<b>Servlet:</b>&ensp;<%=getServletInfo()%> (<%=getServletConfig().getServletContext().getMajorVersion()%>.<%=getServletConfig().getServletContext().getMinorVersion()%>)</td></tr>
<tr><td><b>JBigI:</b></td><td><%=net.i2p.util.NativeBigInteger.loadStatus()%>&ensp;<span class="nowrap">[version: <%=net.i2p.util.NativeBigInteger.getJbigiVersion()%>]</span>&ensp;<span class="nowrap"><b>GMP:</b>&ensp;<%=net.i2p.util.NativeBigInteger.getLibGMPVersion()%></span></td></tr>
<tr><td><b>JSTL:</b></td><td><jsp:getProperty name="logsHelper" property="jstlVersion" />&ensp;<span class="nowrap"><b>Encoding:</b>&ensp;<%=System.getProperty("file.encoding")%></span>&ensp;<span class="nowrap"><b>Charset:</b>&ensp;<%=java.nio.charset.Charset.defaultCharset().name()%></span></td></tr>
</tbody>
</table>
<%
    } // !isAdvanced()
%>
<%
    String consoleNonce = net.i2p.router.web.CSSHelper.getNonce();
    String ct1 = request.getParameter("clear");
    String ct2 = request.getParameter("crit");
    String ct3 = request.getParameter("svc");
    String ct4 = request.getParameter("svct");
    String ct5 = request.getParameter("svcf");
    String ctn = request.getParameter("consoleNonce");
    int last = logsHelper.getLastCriticalMessageNumber();
    if (last >= 0) {
%>
<h3 class="tabletitle"><%=intl._t("Critical Logs")%>
<%
    }
    if ((ct1 != null || ct2 != null || (ct3 != null && ct4 != null && ct5 != null)) && ctn != null) {
        int ict1 = -1, ict2 = -1;
        long ict3 = -1, ict4 = -1;
        try { ict1 = Integer.parseInt(ct1); } catch (NumberFormatException nfe) {}
        try { ict2 = Integer.parseInt(ct2); } catch (NumberFormatException nfe) {}
        try { ict3 = Long.parseLong(ct3); } catch (NumberFormatException nfe) {}
        try { ict4 = Long.parseLong(ct4); } catch (NumberFormatException nfe) {}
        logsHelper.clearThrough(ict1, ict2, ict3, ict4, ct5, ctn);
    }
    if (last >= 0) {
%>
&nbsp;<a class="delete" title="<%=intl._t("Clear logs")%>" href="logs?crit=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
</h3>
<table id="criticallogs" class="logtable">
<tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="criticalLogs" />
</td></tr>
</tbody>
</table>
<%
    }
%>
<h3 class="tabletitle"><%=intl._t("Router Logs")%>
<%
    last = logsHelper.getLastMessageNumber();
    if (last >= 0) {
%>
&nbsp;<a class="delete" title="<%=intl._t("Clear logs")%>" href="logs?clear=<%=last%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
&nbsp;<a class="configure" title="<%=intl._t("Configure router logging options")%>" href="configlogging">[<%=intl._t("Configure")%>]</a>
</h3>
<table id="routerlogs" class="logtable">
<tbody>
<tr><td>
 <jsp:getProperty name="logsHelper" property="logs" />
</td></tr>
</tbody>
</table>
<h3 class="tabletitle" id="servicelogs"><%=intl._t("Service (Wrapper) Logs")%>
<%
    StringBuilder buf = new StringBuilder(24*1024);
    // timestamp, last line number, escaped filename
    Object[] vals = logsHelper.getServiceLogs(buf);
    String lts = vals[0].toString();
    long llast = ((Long) vals[1]).longValue();
    String filename = vals[2].toString();
    if (llast >= 0) {
%>
&nbsp;<a class="delete" title="<%=intl._t("Clear logs")%>" href="logs?svc=<%=llast%>&amp;svct=<%=lts%>&amp;svcf=<%=filename%>&amp;consoleNonce=<%=consoleNonce%>">[<%=intl._t("Clear logs")%>]</a>
<%  } %>
</h3>
<table id="wrapperlogs" class="logtable">
<tbody>
<tr><td>
<%
    out.append(buf);
%>
</td></tr>
</tbody>
</table>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">
  var visibility = document.visibilityState;
  if (visibility == "visible") {
    setInterval(function() {
      progressx.show();
      progressx.progress(0.5);
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/logs?' + new Date().getTime(), true);
      xhr.responseType = "document";
      xhr.onreadystatechange = function () {
        if (xhr.readyState==4 && xhr.status==200) {
          var mainLogs = document.getElementById("logs");
          var mainLogsResponse = xhr.responseXML.getElementById("logs");
          var mainLogsParent = mainLogs.parentNode;

          var criticallogs = document.getElementById("criticallogs");
          if (criticallogs) {
            var criticallogsResponse = xhr.responseXML.getElementById("criticallogs");
            if (criticallogs && !criticallogsResponse) {
              mainLogsParent.replaceChild(mainLogsResponse, mainLogs);
            } else {
              var criticallogsParent = criticallogs.parentNode;
              if (!Object.is(criticallogs.innerHTML, criticallogsResponse.innerHTML))
                criticallogsParent.replaceChild(criticallogsResponse, criticallogs);
            }
          }

          var routerlogs = document.getElementById("routerlogs");
          var routerlogsResponse = xhr.responseXML.getElementById("routerlogs");
          var routerlogsParent = routerlogs.parentNode;
          if (routerlogs && !routerlogsResponse)
            mainLogsParent.replaceChild(mainLogsResponse, mainLogs);
          else if (!Object.is(routerlogs.innerHTML, routerlogsResponse.innerHTML))
            routerlogsParent.replaceChild(routerlogsResponse, routerlogs);

          var servicelogs = document.getElementById("servicelogs");
          if (servicelogs) {
            var servicelogsParent = servicelogs.parentNode;
            var servicelogsResponse = xhr.responseXML.getElementById("servicelogs");
            if (servicelogsResponse) {
              if (!Object.is(servicelogs.innerHTML, servicelogsResponse.innerHTML))
                servicelogsParent.replaceChild(servicelogsResponse, servicelogs);
            } else {
              mainLogsParent.replaceChild(mainLogsResponse, mainLogs);
            }
          }
        }
      }
      window.addEventListener("pageshow", progressx.hide());
      xhr.send();
    }, 30000);
  }
  window.addEventListener("pageshow", progressx.hide());
</script>
<%@include file="summaryajax.jsi" %>
</body>
</html>