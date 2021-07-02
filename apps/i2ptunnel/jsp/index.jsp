<%@include file="headers.jsi"%>
<%@page pageEncoding="UTF-8"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.IndexBean"%>
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:setProperty name="indexBean" property="tunnel" /><%-- must be set before key1-4 --%>
<jsp:setProperty name="indexBean" property="*" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en" id="tman">
<head>
<meta charset="utf-8">
<title><%=intl._t("Tunnel Manager")%></title>
<link href="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%" rel="preload" as="script"> 
<link href="<%=indexBean.getTheme()%>../images/i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css"> 
<link href="<%=indexBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css"> 
<link href="<%=indexBean.getTheme()%>override.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css"> 
<link rel="icon" href="<%=indexBean.getTheme()%>images/favicon.svg">
</head>
<body id="tunnelListPage">
<style type="text/css">body{opacity:0}</style>
<div id="page">
<%
  boolean isInitialized = indexBean.isInitialized();
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
<td id="tunnelMessages" class="volatile">
<textarea id="statusMessages" rows="4" cols="60" readonly="readonly">
<%=msgs%></textarea>
</td>
</tr>
<tr>
<td class="buttons volatile">
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
<h2><%=intl._t("Global Tunnel Control")%>&nbsp;<button id="toggleInfo" class="script" style="float: right"><img src="/themes/console/dark/images/expand_hover.svg" title="Show Tunnel Info"/></button></h2>
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
<%

            String serverDesc = indexBean.getTunnelDescription(curServer);
            if (serverDesc != null && serverDesc.length() > 0) {
%>
<a href="edit?tunnel=<%=curServer%>" title="<%=serverDesc%>"><%=indexBean.getTunnelName(curServer)%></a>
<%
            } else {
%>
<a href="edit?tunnel=<%=curServer%>" title="<%=intl._t("Edit Server Tunnel Settings for")%>&nbsp;<%=indexBean.getTunnelName(curServer)%>"><%=indexBean.getTunnelName(curServer)%></a>
<%
            }
%>
</td>
<td class="tunnelType"><%=indexBean.getTunnelType(curServer)%></td>
<td class="tunnelLocation">
<%
            if (indexBean.isServerTargetLinkValid(curServer)) {
                if (indexBean.isSSLEnabled(curServer)) { %>
<a href="https://<%=indexBean.getServerTarget(curServer)%>/" title="<%=intl._t("Test HTTPS server, bypassing I2P")%>" target="_blank" rel="noreferrer"><%=indexBean.getServerTarget(curServer)%> SSL</a>
<%              } else { %>
<a href="http://<%=indexBean.getServerTarget(curServer)%>/" title="<%=intl._t("Test HTTP server, bypassing I2P")%>" target="_blank" rel="noreferrer"><%=indexBean.getServerTarget(curServer)%></a>
<%
                }
            } else {
%>
<%=indexBean.getServerTarget(curServer)%>
<%
                if (indexBean.isSSLEnabled(curServer)) { %>
SSL
<%
                }
            }
%>
</td>
<td class="tunnelPreview volatile">
<%
            if (("httpserver".equals(indexBean.getInternalType(curServer)) || ("httpbidirserver".equals(indexBean.getInternalType(curServer)))) && indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
%>
<a class="control" title="<%=intl._t("Test HTTP server through I2P")%>" href="http://<%=indexBean.getDestHashBase32(curServer)%>" target="_blank" rel="noreferrer"><%=intl._t("Preview")%></a>
<%
            } else if (indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
%>
<span class="base32" title="<%=intl._t("Base32 Address")%>"><%=indexBean.getDestHashBase32(curServer)%></span>
<%
            } else {
%>
<%=intl._t("No Preview")%>
<%
            }
%>
</td>
<td class="tunnelStatus volatile">
<%
            switch (indexBean.getTunnelStatus(curServer)) {
                case IndexBean.STARTING:
%>
<div class="statusStarting text" title="<%=intl._t("Starting...")%>"><%=intl._t("Starting...")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control" title="<%=intl._t("Stop this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.RUNNING:
%>
<div class="statusRunning text" title="<%=intl._t("Running")%>"><%=intl._t("Running")%></div>
</td>
<td class="tunnelControl">
<a class="control" title="<%=intl._t("Stop this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
<%
                break;
                case IndexBean.NOT_RUNNING:
%>
<div class="statusNotRunning text" title="<%=intl._t("Stopped")%>"><%=intl._t("Stopped")%></div>
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
<td class="tunnelDestination" colspan="2">
<span class="tunnelDestinationLabel">
<%
            String name = indexBean.getSpoofedHost(curServer);
            boolean hasHostname = false;
            if (name == null || name.equals("")) {
                name = indexBean.getTunnelName(curServer);
                out.write("<b>");
                out.write(intl._t("Destination"));
                out.write(":</b></span> <span class=\"selectAll\">");
                out.write(indexBean.getDestHashBase32(curServer));
                out.write("</span>");
            } else {
                hasHostname = true;
                out.write("<b>");
                out.write(intl._t("Hostname"));
                out.write(":</b></span> <span class=\"selectAll\">");
                out.write(name);
                out.write("</span>");
            }
%>
</td>
<td class="tunnelSig" colspan="4">
<span class="tunnelDestinationLabel"><b><%=intl._t("Signature")%>:</b></span>
<%
            String tunnelType = editBean.getInternalType(curServer);
            String altDest = editBean.getAltDestinationBase64(curServer);
            int currentSigType = editBean.getSigType(curServer, tunnelType);
            if (currentSigType == 7) {
%>
Ed25519-SHA-512
<%
            } else if (currentSigType == 3) {
%>
ECDSA-P521
<%
            } else if (currentSigType == 2) {
%>
ECDSA-P384
<%
            } else if (currentSigType == 1) {
%>
ECDSA-P256
<%
            } else if (currentSigType == 0) {
                if (altDest == null || altDest.equals("")) {
%>
<font style="color:red">DSA-SHA1</font>
<%
                } else {
%>
DSA-SHA1 & Ed25519-SHA-512 (<%=intl._t("Alternate")%>)
<%
                }
            }
%>
</td>
</tr>
<%
            String encName = indexBean.getEncryptedBase32(curServer);
            String altDestB32 = editBean.getAltDestHashBase32(curServer);
            if (encName != null && encName.length() > 0) {
%>
<tr class="tunnelInfo" style="display: none;">
<td class="tunnelDestinationEncrypted" colspan="2">
<span class="tunnelDestinationLabel"><b><%=intl._t("Encrypted")%>:</b></span>
<span class="selectAll"><%=encName%></span>
</td>
<%
            } else if (hasHostname) {
%>
<tr class="tunnelInfo" style="display: none;">
<td class="tunnelDestinationEncrypted" colspan="2">
<span class="tunnelDestinationLabel"><b><%=intl._t("Destination")%>:</b></span>
<span class="selectAll"><%=indexBean.getDestHashBase32(curServer)%></span>
</td>
<%
            } else if (altDest != null && !altDest.equals("")) {
%>
<tr class="tunnelInfo" style="display: none;">
<td class="tunnelDestinationEncrypted" colspan="2">
<span class="tunnelDestinationLabel"><b><%=intl._t("Alt Destination")%>:</b></span>
<span class="selectAll"><%=altDestB32%></span>
</td>
<%
            } else {
%>
<tr class="tunnelInfo" style="display: none;">
<td class="tunnelDestinationEncrypted empty" colspan="2"></td>
<%
            }
%>
<td class="tunnelEncryption" colspan="4">
<span class="tunnelDestinationLabel"><b><%=intl._t("Encryption")%>:</b></span>
<%
            boolean has0 = editBean.hasEncType(curServer, 0);
            boolean has4 = editBean.hasEncType(curServer, 4);
            if (has0 && has4) {
%>
ElGamal-2048 & ECIES-X25519
<%
            } else if (has4) {
%>
ECIES-X25519
<%
            } else if (has0) {
%>
ElGamal-2048
<%
            }
%>
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
            boolean isShared = indexBean.isSharedClient(curClient);
            if (!indexBean.isClient(curClient)) continue;
            String clientDesc = indexBean.getTunnelDescription(curClient);
%>
<tr class="tunnelProperties">
<td class="tunnelName">
<%
            if (clientDesc != null && clientDesc.length() != 0) {
%>
<a href="edit?tunnel=<%=curClient%>" title="<%=clientDesc%>"><%=indexBean.getTunnelName(curClient)%></a>
<%
            } else {
%>
<a href="edit?tunnel=<%=curClient%>" title="<%=intl._t("Edit Tunnel Settings for")%>&nbsp;<%=indexBean.getTunnelName(curClient)%>"><%=indexBean.getTunnelName(curClient)%></a>
<%
            }
%>
</td>
<td class="tunnelType"><%=indexBean.getTunnelType(curClient)%>
<%
            if (isShared) {
%>
            &nbsp;<span class="shared" title="Tunnel is configured as a Shared Client">*</span>
<%
            } // isShared
%>
</td>
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
<td class="tunnelStatus volatile">
<%
               switch (indexBean.getTunnelStatus(curClient)) {
                   case IndexBean.STARTING:
%>
<div class="statusStarting text" title="<%=intl._t("Starting...")%>"><%=intl._t("Starting...")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control" title="<%=intl._t("Stop this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                   break;
                   case IndexBean.STANDBY:
%>
<div class="statusStandby text" title="<%=intl._t("Standby")%>"><%=intl._t("Standby")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                   break;
                   case IndexBean.RUNNING:
%>
<div class="statusRunning text" title="<%=intl._t("Running")%>"><%=intl._t("Running")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control" title="Stop this Tunnel" href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                   break;
                   case IndexBean.NOT_RUNNING:
%>
<div class="statusNotRunning text" title="<%=intl._t("Stopped")%>"><%=intl._t("Stopped")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control" title="<%=intl._t("Start this Tunnel")%>" href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curClient%>"><%=intl._t("Start")%></a>
<%
                   break;
               }
%>
</td>
</tr>
<tr class="tunnelInfo" style="display: none;">
<td class="tunnelDestination" colspan="2">
<span class="tunnelDestinationLabel">
<%
               String cdest = indexBean.getClientDestination(curClient);
               if ("httpclient".equals(indexBean.getInternalType(curClient)) || "connectclient".equals(indexBean.getInternalType(curClient)) ||
                   "sockstunnel".equals(indexBean.getInternalType(curClient)) || "socksirctunnel".equals(indexBean.getInternalType(curClient))) {
%>
<b><%=intl._t("Outproxy")%>:</b>
<%
               } else {
%>
<b><%=intl._t("Destination")%>:</b>
<%
               }
%>
</span>
<%
               if (indexBean.getIsUsingOutproxyPlugin(curClient)) {
%>
<%=intl._t("internal plugin")%>
<%
               } else {
                   if (cdest.length() > 70) { // Probably a B64 (a B32 is 60 chars) so truncate
%>
<span class="selectAll"><%=cdest.substring(0, 45)%>&hellip;<%=cdest.substring(cdest.length() - 15, cdest.length())%></span>
<%
                   } else if (cdest.length() > 0) {
%>
<span class="selectAll"><%=cdest%></span>
<%
                   } else {
%>
<span class="selectAll"><i><%=intl._t("none")%></i></span>
<%
                   }
               }
%>
</td>
<td class="tunnelSig" colspan="4">
<span class="tunnelDestinationLabel"><b><%=intl._t("Signature")%>:</b></span>
<%
               String tunnelType = editBean.getInternalType(curClient);
               int currentSigType = editBean.getSigType(curClient, tunnelType);
               if (currentSigType == 7) {
%>
Ed25519-SHA-512
<%
               } else if (currentSigType == 3) {
%>
ECDSA-P521
<%
               } else if (currentSigType == 2) {
%>
ECDSA-P384
<%
               } else if (currentSigType == 1) {
%>
ECDSA-P256
<%
               } else if (currentSigType == 0) {
%>
<font style="color:red">DSA-SHA1</font>
<%
               }
               String clientB32 = indexBean.getDestHashBase32(curClient);
               if ((cdest.contains(".i2p") && !cdest.contains(".b32") || cdest.length() > 70) && clientB32.length() > 0) {
%>
<tr class="tunnelInfo" style="display: none;">
<td class="tunnelDestinationEncrypted" colspan="2">
<span class="tunnelDestinationLabel"><b>B32:</b></span>
<span class="selectAll"><%=clientB32%></span>
</td>
<%
               } else {
%>
<tr class="tunnelInfo" style="display: none;">
<td class="empty" colspan="2"></td>
<%
               }
%>
<td class="tunnelEncryption" colspan="4">
<span class="tunnelDestinationLabel"><b><%=intl._t("Encryption")%>:</b></span>
<%
               boolean has0 = editBean.hasEncType(curClient, 0);
               boolean has4 = editBean.hasEncType(curClient, 4);
               if (has0 && has4) {
%>
ElGamal-2048 & ECIES-X25519
<%
               } else if (has4) {
%>
ECIES-X25519
<%
               } else if (has0) {
%>
ElGamal-2048
<%
               }
%>
<%
        } // for loop
%>
</td>
</tr>
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
<noscript><style type="text/css">.script {display: none} .tunnelInfo {display: table-row !important}</style></noscript>
<%

  }  // isInitialized()

  if (!indexBean.isInitialized()) {
%>
<div id="notReady"><%=intl._t("Initializing Tunnel Manager{0}", "&hellip;")%><noscript><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></noscript></div>
<%
  }  // !isInitialized()
%>
<script type="text/javascript" src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="js/refreshIndex.js?<%=net.i2p.CoreVersion.VERSION%>" type="module"></script>
</div>
<span data-iframe-height></span>
<style type="text/css">body{opacity:1}</style>
</body>
</html>
