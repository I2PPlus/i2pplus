<%@include file="headers.jsi"%>
<%@include file="headers-unsafe.jsi"%>
<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.IndexBean"%>
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:setProperty name="indexBean" property="*" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en" id="tman">
<head>
   <title><%=intl._t("Tunnel Manager")%></title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    <script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
    <script type="text/javascript" src="/js/toggleTunnelInfo.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<%     if (indexBean.allowCSS()) { %>
    <style type="text/css">body {opacity: 0;}</style>
    <link rel="icon" href="<%=indexBean.getTheme()%>images/favicon.ico" />
    <link href="<%=indexBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" /> 
    <link href="<%=indexBean.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" /> 
<%     } %>
</head>
<body id="tunnelListPage">
<div id="xhr_refresh">
<%
  boolean isInitialized = indexBean.isInitialized();
  String cspNonce = Integer.toHexString(net.i2p.util.RandomSource.getInstance().nextInt());
  String nextNonce = isInitialized ? net.i2p.i2ptunnel.web.IndexBean.getNextNonce() : null;

  // not synced, oh well
  int lastID = indexBean.getLastMessageID();
  String msgs = indexBean.getMessages();
  if (msgs.length() > 0) {
%>
<div class="panel" id="messages">
    <h2><%=intl._t("Status Messages")%></h2>
    <table id="statusMessagesTable">
        <tr>
            <td id="tunnelMessages">
                <textarea id="statusMessages" rows="4" cols="60" readonly="readonly"><%=msgs%></textarea>
            </td>
        </tr>
        <tr>
            <td class="buttons">
                <a class="control" href="list"><%=intl._t("Refresh")%></a>
<%
  if (isInitialized) {
%>
                <a class="control" href="list?action=Clear&amp;msgid=<%=lastID%>&amp;nonce=<%=nextNonce%>"><%=intl._t("Clear")%></a>
<%
  }  // isInitialized
%>
            </td>
        </tr>
    </table>
</div>
<%
  }  // !msgs.isEmpty()
  if (isInitialized) {
%>
<div class="panel" id="globalTunnelControl">
    <noscript><style type=\"text/css\">.script {display: none;} .tunnelInfo {display: table-row !important;}</style></noscript>
    <h2><%=intl._t("Global Tunnel Control")%>&nbsp;<button id="toggleInfo" class="script" style="float: right" onclick="toggleInfo();"><img src="/themes/console/dark/images/sort_down.png" title="Show Tunnel Info"/></button></h2>
    <table>
        <tr>
            <td class="buttons">
                <a class="control" href="wizard"><%=intl._t("Tunnel Wizard")%></a>
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Stop%20all"><%=intl._t("Stop All")%></a>
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Start%20all"><%=intl._t("Start All")%></a>
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Restart%20all"><%=intl._t("Restart All")%></a>
<%--
                //this is really bad because it stops and restarts all tunnels, which is probably not what you want
                <a class="control" href="list?nonce=<%=nextNonce%>&amp;action=Reload%20configuration"><%=intl._t("Reload Config")%></a>
--%>
            </td>
        </tr>
    </table>
</div>
<div class="panel" id="servers">
    <h2><%=intl._t("I2P Server Tunnels").replace("I2P ", "")%></h2>
    <table id="serverTunnels">
    <tr>
        <th class="tunnelName"><%=intl._t("Name")%></th>
        <th class="tunnelType"><%=intl._t("Type")%></th>
        <th class="tunnelLocation"><%=intl._t("Points at")%></th>
        <th class="tunnelPreview"><%=intl._t("Preview")%></th>
        <th class="tunnelStatus"><%=intl._t("Status")%></th>
        <th class="tunnelControl"><%=intl._t("Control")%></th>
    </tr>
<%
        for (int curServer = 0; curServer < indexBean.getTunnelCount(); curServer++) {
            if (indexBean.isClient(curServer)) continue;
%>
    <tr class="tunnelProperties">
        <td class="tunnelName">
            <a href="edit?tunnel=<%=curServer%>" title="<%=intl._t("Edit Server Tunnel Settings for")%>&nbsp;<%=indexBean.getTunnelName(curServer)%>"><%=indexBean.getTunnelName(curServer)%></a>
        </td>
        <td class="tunnelType"><%=indexBean.getTunnelType(curServer)%></td>
        <td class="tunnelLocation">
<%
            if (indexBean.isServerTargetLinkValid(curServer)) {
                if (indexBean.isSSLEnabled(curServer)) { %>
                    <a href="https://<%=indexBean.getServerTarget(curServer)%>/" title="<%=intl._t("Test HTTPS server, bypassing I2P")%>" target="_top"><%=indexBean.getServerTarget(curServer)%> SSL</a>
<%              } else { %>
                    <a href="http://<%=indexBean.getServerTarget(curServer)%>/" title="<%=intl._t("Test HTTP server, bypassing I2P")%>" target="_top"><%=indexBean.getServerTarget(curServer)%></a>
<%
                }
            } else {
          %><%=indexBean.getServerTarget(curServer)%>
<%
                if (indexBean.isSSLEnabled(curServer)) { %>
                    SSL
<%
                }
            }
%>
        </td>
        <td class="tunnelPreview">
<%
            if (("httpserver".equals(indexBean.getInternalType(curServer)) || ("httpbidirserver".equals(indexBean.getInternalType(curServer)))) && indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
%>
            <a class="control" title="<%=intl._t("Test HTTP server through I2P")%>" href="http://<%=indexBean.getDestHashBase32(curServer)%>" target="_top"><%=intl._t("Preview")%></a>
<%
            } else if (indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
          %><span class="base32" title="<%=intl._t("Base32 Address")%>"><%=indexBean.getDestHashBase32(curServer)%></span>
<%
            } else {
          %><%=intl._t("No Preview")%>
<%
            }
%>
        </td>
        <td class="tunnelStatus">
<%
            switch (indexBean.getTunnelStatus(curServer)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text" title="<%=intl._t("Starting...")%>"><%=intl._t("Starting...")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="<%=intl._t("Stop this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text" title="<%=intl._t("Running")%>"><%=intl._t("Running")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="<%=intl._t("Stop this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text" title="<%=intl._t("Stopped")%>"><%=intl._t("Stopped")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="<%=intl._t("Start this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curServer%>"><%=intl._t("Start")%></a>
<%
                break;
            }
%>
        </td>
    </tr>
    <tr class="tunnelInfo" style="display: none;">
        <td class="tunnelDestination" colspan="6">
            <span class="tunnelDestinationLabel">
<%
                String name = indexBean.getSpoofedHost(curServer);
                    if (name == null || name.equals("")) {
                        name = indexBean.getTunnelName(curServer);
                        out.write("<b>");
                        out.write(intl._t("Destination"));
                        out.write(":</b></span> ");
                        out.write(indexBean.getDestHashBase32(curServer));
                   } else {
                       out.write("<b>");
                       out.write(intl._t("Hostname"));
                       out.write(":</b></span> ");
                       out.write(name);
                   }
%>
        </td>
    </tr>
<%
            String encName = indexBean.getEncryptedBase32(curServer);
            if (encName != null && encName.length() > 0) {
%>
    <tr class="tunnelInfo" style="display: none;">
        <td class="tunnelDestination" colspan="6">
            <span class="tunnelDestinationLabel"><b><%=intl._t("Encrypted")%>:</b></span>
            <%=encName%>
        </td>
    </tr>
<%
            } // encName
%>
    <tr class="tunnelInfo" style="display: none;">
        <td class="tunnelDescription" colspan="6">
            <span class="tunnelDestinationLabel"><b><%=intl._t("Description")%>:</b></span>
            <%=indexBean.getTunnelDescription(curServer)%>
        </td>
    </tr>
<%
        } // for loop
%>
    <tr>
        <td class="newTunnel" colspan="6">
           <form id="addNewServerTunnelForm" action="edit">
               <b><%=intl._t("New server tunnel")%>:</b>&nbsp;
                    <select name="type">
                        <option value="httpserver">HTTP</option>
                        <option value="server"><%=intl._t("Standard")%></option>
                        <option value="httpbidirserver">HTTP bidir</option>
                        <option value="ircserver">IRC</option>
                        <option value="streamrserver">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="<%=intl._t("Create")%>" />
            </form>
        </td>
    </tr>
</table>
</div>
<div class="panel" id="clients">
    <h2><%=intl._t("I2P Client Tunnels").replace("I2P ", "")%></h2>
    <table id="clientTunnels">
    <tr>
        <th class="tunnelName"><%=intl._t("Name")%></th>
        <th class="tunnelType"><%=intl._t("Type")%></th>
        <th class="tunnelInterface"><%=intl._t("Interface")%></th>
        <th class="tunnelPort"><%=intl._t("Port")%></th>
        <th class="tunnelStatus"><%=intl._t("Status")%></th>
        <th class="tunnelControl"><%=intl._t("Control")%></th>
    </tr>
<%
        for (int curClient = 0; curClient < indexBean.getTunnelCount(); curClient++) {
            if (!indexBean.isClient(curClient)) continue;
%>
    <tr class="tunnelProperties">
        <td class="tunnelName">
            <a href="edit?tunnel=<%=curClient%>" title="<%=intl._t("Edit Tunnel Settings for")%>&nbsp;<%=indexBean.getTunnelName(curClient)%>"><%=indexBean.getTunnelName(curClient)%></a>
        </td>
        <td class="tunnelType"><%=indexBean.getTunnelType(curClient)%></td>
        <td class="tunnelInterface">
<%
               /* should only happen for streamr client */
               String cHost= indexBean.getClientInterface(curClient);
               if (cHost == null || "".equals(cHost)) {
                   out.write("<font color=\"red\">");
                   out.write(intl._t("Host not set"));
                   out.write("</font>");
               } else {
                   out.write(cHost);
               }
%>
        </td>
        <td class="tunnelPort">
<%
               String cPort= indexBean.getClientPort2(curClient);
               out.write(cPort);
               if (indexBean.isSSLEnabled(curClient))
                   out.write(" SSL");
%>
        </td>
        <td class="tunnelStatus">
<%
            switch (indexBean.getTunnelStatus(curClient)) {
                case IndexBean.STARTING:
          %><div class="statusStarting text" title="<%=intl._t("Starting...")%>"><%=intl._t("Starting...")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="<%=intl._t("Stop this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.STANDBY:
          %><div class="statusStandby text" title="<%=intl._t("Standby")%>"><%=intl._t("Standby")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.RUNNING:
          %><div class="statusRunning text" title="<%=intl._t("Running")%>"><%=intl._t("Running")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.NOT_RUNNING:
          %><div class="statusNotRunning text" title="<%=intl._t("Stopped")%>"><%=intl._t("Stopped")%></div>
        </td>
        <td class="tunnelControl">
            <a class="control" title="<%=intl._t("Start this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curClient%>"><%=intl._t("Start")%></a>
<%
                break;
            }
%>
        </td>
    </tr>
    <tr class="tunnelInfo" style="display: none;">
        <td class="tunnelDestination" colspan="6">
            <span class="tunnelDestinationLabel">
<%             if ("httpclient".equals(indexBean.getInternalType(curClient)) || "connectclient".equals(indexBean.getInternalType(curClient)) ||
                   "sockstunnel".equals(indexBean.getInternalType(curClient)) || "socksirctunnel".equals(indexBean.getInternalType(curClient))) { %>
                <b><%=intl._t("Outproxy")%>:</b>
<%             } else { %>
                <b><%=intl._t("Destination")%>:</b>
<%             } %>
</span>
<%
               if (indexBean.getIsUsingOutproxyPlugin(curClient)) {
                   %><%=intl._t("internal plugin")%><%
               } else {
                   String cdest = indexBean.getClientDestination(curClient);
                   if (cdest.length() > 70) { // Probably a B64 (a B32 is 60 chars) so truncate
                       %><%=cdest.substring(0, 45)%>&hellip;<%=cdest.substring(cdest.length() - 15, cdest.length())%><%
                   } else if (cdest.length() > 0) {
                       %><%=cdest%><%
                   } else {
                       %><i><%=intl._t("none")%></i><%
                   }
               } %>
        </td>
    </tr>
<% /* TODO SSL outproxy for httpclient if plugin not present */ %>
    <tr class="tunnelInfo" style="display: none;">
        <td class="tunnelDescription" colspan="6">
            <span class="tunnelDescriptionLabel"><b><%=intl._t("Description")%>:</b></span>
            <%=indexBean.getTunnelDescription(curClient)%>
        </td>
    </tr>
<%
        }
%>
    <tr>
        <td class="newTunnel" colspan="6">
            <form id="addNewClientTunnelForm" action="edit">
                <b><%=intl._t("New client tunnel")%>:</b>&nbsp;
                    <select name="type">
                        <option value="client"><%=intl._t("Standard")%></option>
                        <option value="httpclient">HTTP/CONNECT</option>
                        <option value="ircclient">IRC</option>
                        <option value="sockstunnel">SOCKS 4/4a/5</option>
                        <option value="socksirctunnel">SOCKS IRC</option>
                        <option value="connectclient">CONNECT</option>
                        <option value="streamrclient">Streamr</option>
                    </select>
                    <input class="control" type="submit" value="<%=intl._t("Create")%>" />
            </form>
        </td>
    </tr>
</table>
</div>
<%

  }  // isInitialized()

%>
<%

  if (!indexBean.isInitialized()) {
     %><div id="notReady"><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></div><%
  }  // !isInitialized()

%>
<script nonce="<%=cspNonce%>" type="text/javascript">
  setInterval(function() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/i2ptunnel/?' + new Date().getTime(), true);
    xhr.responseType = "text";
    xhr.onreadystatechange = function () {
      if (xhr.readyState==4 && xhr.status==200) {
        document.getElementById("xhr_refresh").innerHTML=xhr.responseText;
      }
    }
    xhr.send();
  }, 45000);
</script>
</div>
<span data-iframe-height></span>
<style type="text/css">body {opacity: 1 !important;}</style>
</body>
</html>
