<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" import="java.net.URL" import="java.net.URLConnection" import="java.io.IOException" import="net.i2p.i2ptunnel.web.IndexBean"%>
<%@include file="headers.jsi"%>
<jsp:useBean class="net.i2p.i2ptunnel.web.IndexBean" id="indexBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:setProperty name="indexBean" property="tunnel" /><%-- must be set before key1-4 --%>
<jsp:setProperty name="indexBean" property="*" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
<% String activeTheme = indexBean.getTheme(); %>
<!DOCTYPE html>
<% if (activeTheme.contains("dark") || activeTheme.contains("midnight")) { %><html id=tman style=background:#000>
<% } else { %><html id=tman><% } %>
<head>
<meta charset=utf-8>
<title><%=intl._t("Tunnel Manager")%></title>
<link href="<%=activeTheme%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet> 
<link href="<%=activeTheme%>../images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<link href="<%=activeTheme%>images/images.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<link href="<%=activeTheme%>../images/i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel=stylesheet>
<% if (indexBean.useSoraFont()) { %><link href="<%=activeTheme%>../../fonts/Sora.css" rel=stylesheet><% } %>
<% else { %><link href="<%=activeTheme%>../../fonts/OpenSans.css" rel=stylesheet><% } %>
<%
  String overrideURL = activeTheme + "override.css";
  boolean overrideEnabled = false;
  try {
    URL url = new URL(overrideURL);
    URLConnection connection = url.openConnection();
    connection.connect();
    overrideEnabled = true;
  } catch (IOException e) {overrideEnabled = false;}
%>
<% if (overrideEnabled) { %><link href="<%=activeTheme%>override.css" rel=stylesheet><% } %>
<link rel=icon href="<%=activeTheme%>images/favicon.svg">
<style>body{display:none;pointer-events:none}</style>
</head>
<body id=tunnelListPage style=display:none;pointer-events:none>
<iframe name=processForm id=processForm hidden></iframe>
<div id=page>
<%
  boolean isInitialized = indexBean.isInitialized();
  String nextNonce = isInitialized ? net.i2p.i2ptunnel.web.IndexBean.getNextNonce() : null;
  int lastID = indexBean.getLastMessageID(); // not synced, oh well
  String msgs = indexBean.getMessages();
  if (msgs.length() > 0) {
%>
<div class=panel id=messages>
<h2 id=screenlog><%=intl._t("Status Messages")%>
<%    if (isInitialized) { %>
<span>
<a class="control clearlog iconize script" target=processForm href="list?action=Clear&amp;msgid=<%=lastID%>&amp;nonce=<%=nextNonce%>"><%=intl._t("Clear")%></a>
</span>
<%    } /* isInitialized */ %>
</h2>
<table id=statusMessagesTable>
<tr>
<td id=tunnelMessages>
<textarea id=statusMessages rows=4 cols=60 readonly>
<%=msgs%></textarea>
</td>
</tr>
<tr id=screenlog_buttons hidden>
<td class=buttons>
<a class="control refresh iconize" target=processForm href="list"><%=intl._t("Refresh")%></a>
<%    if (isInitialized) { %>
<a class="control clearlog iconize" target=processForm href="list?action=Clear&amp;msgid=<%=lastID%>&amp;nonce=<%=nextNonce%>"><%=intl._t("Clear")%></a>
<%    } /* isInitialized */ %>
</td>
</tr>
</table>
</div>
<%
  } // !msgs.isEmpty()
  if (isInitialized) {
%>
<div class=panel id=globalTunnelControl>
<h2><%=intl._t("Global Tunnel Control")%>&nbsp;<button id=toggleInfo class=script style=float:right><img src="/themes/console/dark/images/expand_hover.svg" title="Show Tunnel Info"/></button></h2>
<table>
<tr>
<td class=buttons>
<a class="control wizard iconize" href="wizard"><%=intl._t("Tunnel Wizard")%></a>
<a class="control stopall iconize" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=Stop%20all"><%=intl._t("Stop All")%></a>
<a class="control startall iconize" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=Start%20all"><%=intl._t("Start All")%></a>
<a class="control restartall iconize" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=Restart%20all"><%=intl._t("Restart All")%></a>
</td>
</tr>
</table>
</div>
<div class=panel id=servers>
<h2><%=intl._t("I2P Server Tunnels").replace("I2P ", "")%>
<span id=countServer>
<span class="running svr"></span>
<span class="starting svr"></span>
<span class="standby svr"></span>
<span class="stopped svr"></span>
</span>
</h2>
<table id=serverTunnels>
<tr>
<th class=tunnelName><%=intl._t("Name")%></th>
<th class=tunnelHelper><%=intl._t("Helper")%></th>
<th class=tunnelType><%=intl._t("Type")%></th>
<th class=tunnelLocation><%=intl._t("Points at")%></th>
<th class=tunnelPreview><%=intl._t("Preview")%></th>
<th class=tunnelStatus><%=intl._t("Status")%></th>
<th class="tunnelControl volatile"><%=intl._t("Control")%></th>
</tr>
<%
        for (int curServer : indexBean.getControllerNumbers(false)) {
%>
<tr class=tunnelProperties>
<td class=tunnelName>
<%          String serverDesc = indexBean.getTunnelDescription(curServer);
            if (serverDesc != null && serverDesc.length() > 0) {
%>
<a href="edit?tunnel=<%=curServer%>" title="<%=serverDesc%>"><%=indexBean.getTunnelName(curServer)%></a>
<%          } else { %>
<a href="edit?tunnel=<%=curServer%>" title="<%=intl._t("Edit Server Tunnel Settings for")%>&nbsp;<%=indexBean.getTunnelName(curServer)%>"><%=indexBean.getTunnelName(curServer)%></a>
<%          } %>
</td>
<td class=tunnelHelper>
<%
            String spoofedHost = indexBean.getSpoofedHost(curServer);
            String hostname = editBean.getTunnelName(curServer);
            if (spoofedHost != null && spoofedHost.endsWith(".i2p") && !spoofedHost.contains("b32") && spoofedHost != "mysite.i2p") {
%>
<a class=helperLink href="http://<%=indexBean.getSpoofedHost(curServer)%>?i2paddresshelper=<%=indexBean.getDestinationBase64(curServer)%>" target=_blank></a>

<%          } else if (hostname != null && hostname.contains(".i2p") && !hostname.contains("b32")) {
                int i2p = hostname.indexOf(".i2p");
                hostname = hostname.substring(0, i2p + 4);
%>
<a class=helperLink href="http://<%=hostname%>?i2paddresshelper=<%=indexBean.getDestinationBase64(curServer)%>" target=_blank></a>
<%          } %>
</td>

<td class=tunnelType><%=indexBean.getTunnelType(curServer)%></td>
<td class=tunnelLocation>
<%          if (indexBean.isServerTargetLinkValid(curServer)) {
                if (indexBean.isSSLEnabled(curServer)) { %>
<a href="https://<%=indexBean.getServerTarget(curServer)%>/" title="<%=intl._t("Test HTTPS server, bypassing I2P")%>" target=_blank rel=noreferrer><%=indexBean.getServerTarget(curServer)%> SSL</a>
<%              } else { %>
<a href="http://<%=indexBean.getServerTarget(curServer)%>/" title="<%=intl._t("Test HTTP server, bypassing I2P")%>" target=_blank rel=noreferrer><%=indexBean.getServerTarget(curServer)%></a>
<%              }
            } else {
%>
<%=indexBean.getServerTarget(curServer)%>
<%              if (indexBean.isSSLEnabled(curServer)) { %>
SSL
<%              }
            }
%>
</td>
<td class="tunnelPreview volatile">
<%          if (("httpserver".equals(indexBean.getInternalType(curServer)) ||
                ("httpbidirserver".equals(indexBean.getInternalType(curServer)))) &&
                indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) {
%>
<a class="control preview iconize" title="<%=intl._t("Test HTTP server through I2P")%>" href="http://<%=indexBean.getDestHashBase32(curServer)%>" target=_blank rel=noreferrer><%=intl._t("Preview")%></a>
<%          } else if (indexBean.getTunnelStatus(curServer) == IndexBean.RUNNING) { %>
<span class=base32 title="<%=intl._t("Base32 Address")%>"><%=indexBean.getDestHashBase32(curServer)%></span>
<%          } else if (indexBean.getTunnelStatus(curServer) == IndexBean.NOT_RUNNING) { %>
<span class="nopreview offline"><%=intl._t("Offline")%></span>
<%          } else { %><span class=nopreview><%=intl._t("No Preview")%></span><% } %>
</td>
<td class="tunnelStatus volatile">
<%          switch (indexBean.getTunnelStatus(curServer)) {
                case IndexBean.STARTING:
%>
<div class="statusStarting svr"><span class=tooltip hidden><b><%=intl._t("Starting...")%></b></span><%=intl._t("Starting...")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control stop iconize" title="<%=intl._t("Stop this Tunnel")%>" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
<%              break;
                case IndexBean.RUNNING:
%>
<div class="statusRunning svr"><span class=tooltip hidden><b><%=intl._t("Running")%></b><hr><%=intl._t("Hops")%>: <%=editBean.getTunnelDepth(curServer, 3)%>&nbsp;<%=intl._t("in")%>, <%=editBean.getTunnelDepthOut(curServer, 3)%>&nbsp;<%=intl._t("out")%><br><%=intl._t("Count")%>: <%=editBean.getTunnelQuantity(curServer,2)%>&nbsp;<%=intl._t("in")%>, <%=editBean.getTunnelQuantityOut(curServer,2)%>&nbsp;<%=intl._t("out")%><br><%=intl._t("Variance")%>: <%=editBean.getTunnelVariance(curServer,0)%>&nbsp;<%=intl._t("in")%>,&nbsp;<%=editBean.getTunnelVarianceOut(curServer,0)%>&nbsp;<%=intl._t("out")%></span><%=intl._t("Running")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control stop iconize" title="<%=intl._t("Stop this Tunnel")%>" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curServer%>"><%=intl._t("Stop")%></a>
<%              break;
                case IndexBean.NOT_RUNNING:
%>
<div class="statusNotRunning svr"><span class=tooltip hidden><b><%=intl._t("Stopped")%></b></span><%=intl._t("Stopped")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control start iconize" title="<%=intl._t("Start this Tunnel")%>" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curServer%>"><%=intl._t("Start")%></a>
<%              break;
            }
%>
</td>
</tr>
<tr class=tunnelInfo style=display:none>
<td class=tunnelDestination colspan=3>
<span class=tunnelDestinationLabel>
<%          String name = indexBean.getSpoofedHost(curServer);
            boolean hasHostname = false;
            if (name == null || name.equals("")) {
                name = indexBean.getTunnelName(curServer);
                out.write("<b>");
                out.write(intl._t("Destination"));
                out.write(":</b></span> <span class=selectAll>");
                out.write(indexBean.getDestHashBase32(curServer));
                out.write("</span>");
            } else {
                hasHostname = true;
                out.write("<b>");
                out.write(intl._t("Hostname"));
                out.write(":</b></span> <span class=selectAll>");
                out.write(name);
                out.write("</span>");
            }
%>
</td>
<td class=tunnelSig colspan=4>
<span class=tunnelDestinationLabel><b><%=intl._t("Signature")%>:</b></span>
<%
            String tunnelType = editBean.getInternalType(curServer);
            String altDest = editBean.getAltDestinationBase64(curServer);
            int currentSigType = editBean.getSigType(curServer, tunnelType);
%>
<%          if (currentSigType == 7) { %><span class=sigType>Ed25519-SHA-512</span><% } %>
<%          else if (currentSigType == 3) { %><span class=sigType>ECDSA-P521</span><% } %>
<%          else if (currentSigType == 2) { %><span class=sigType>ECDSA-P384</span><% } %>
<%          else if (currentSigType == 1) { %><span class=sigType>ECDSA-P256</span><% } %>
<%          else if (currentSigType == 0) {
                if (altDest == null || altDest.equals("")) { %><span class=sigType style=font-weight:600;color:red>DSA-SHA1</span><% } %>
<%              else { %><span class=sigType>DSA-SHA1 & Ed25519-SHA-512 (<%=intl._t("Alternate")%>)</span><% } %>
<%          } %>
</td>
</tr>
<%
            String encName = indexBean.getEncryptedBase32(curServer);
            String altDestB32 = editBean.getAltDestHashBase32(curServer);
            if (encName != null && encName.length() > 0) {
%>
<tr class=tunnelInfo style=display:none>
<td class=tunnelDestinationEncrypted colspan=3>
<span class=tunnelDestinationLabel><b><%=intl._t("Encrypted")%>:</b></span>
<span class=selectAll><%=encName%></span>
</td>
<%          } else if (hasHostname) { %>
<tr class=tunnelInfo style=display:none>
<td class=tunnelDestinationEncrypted colspan=3>
<span class=tunnelDestinationLabel><b><%=intl._t("Destination")%>:</b></span>
<span class=selectAll><%=indexBean.getDestHashBase32(curServer)%></span>
</td>
<%          } else if (altDest != null && !altDest.equals("")) { %>
<tr class=tunnelInfo style=display:none>
<td class=tunnelDestinationEncrypted colspan=3>
<span class=tunnelDestinationLabel><b><%=intl._t("Alt Destination")%>:</b></span>
<span class=selectAll><%=altDestB32%></span>
</td>
<%          } else { %>
<tr class=tunnelInfo style=display:none>
<td class="tunnelDestinationEncrypted empty" colspan=3></td>
<%          } %>
<td class=tunnelEncryption colspan=4>
<span class=tunnelDestinationLabel><b><%=intl._t("Encryption")%>:</b></span>
<%
            boolean has0 = editBean.hasEncType(curServer, 0);
            boolean has4 = editBean.hasEncType(curServer, 4);
            boolean has5 = editBean.hasEncType(curServer, 5);
            boolean has6 = editBean.hasEncType(curServer, 6);
            boolean has7 = editBean.hasEncType(curServer, 7);
%>
<%          if (has0 && has4) { %><span class=encType title="ECIES-X25519 & ElGamal-2048">ECIES & ElGamal</span><% } %>
<%          else if (has4 && has5) { %><span class=encType title="MLKEM512-X25519 & ECIES-X25519">MLKEM512 & ECIES</span><% } %> 
<%          else if (has4 && has6) { %><span class=encType title="MLKEM768-X25519 & ECIES-X25519">MLKEM768 & ECIES</span><% } %>
<%          else if (has4 && has7) { %><span class=encType title="MLKEM1024-X25519 & ECIES-X25519">MLKEM1024 & ECIES</span><% } %> 
<%          else if (has0) { %><span class=encType title="ElGamal-2048">ElGamal</span><% } %>
<%          else if (has4) { %><span class=encType title="ECIES-X25519 (Ratchet)">ECIES</span><% } %>
<%          else if (has5) { %><span class=encType title="MLKEM512-X25519">MLKEM512</span><% } %>
<%          else if (has6) { %><span class=encType title="MLKEM768-X25519">MLKEM768</span><% } %>
<%          else if (has7) { %><span class=encType title="MLKEM1024-X25519">MLKEM1024</span><% } %>
</td>
</tr>
<%      } /* for loop */ %>
<tr>
<td class=newTunnel colspan=7>
<form id=addNewServerTunnelForm action="edit">
<b><%=intl._t("New server tunnel")%>:</b>&nbsp;
<select name="type">
<option value="httpserver">HTTP</option>
<option value="server"><%=intl._t("Standard")%></option>
<option value="httpbidirserver">HTTP bidir</option>
<option value="ircserver">IRC</option>
<option value="streamrserver">Streamr</option>
</select>
<input class="control create iconize" type=submit value="<%=intl._t("Create")%>" />
</form>
</td>
</tr>
</table>
</div>
<div class=panel id=clients>
<h2><%=intl._t("I2P Client Tunnels").replace("I2P ", "")%>
<span id=countClient>
<span class="running cli"></span>
<span class="starting cli"></span>
<span class="standby cli"></span>
<span class="stopped cli"></span>
</span>
</h2>
<table id=clientTunnels>
<tr>
<th class=tunnelName><%=intl._t("Name")%></th>
<th class=tunnelHelper></th>
<th class=tunnelType><%=intl._t("Type")%></th>
<th class=tunnelInterface><%=intl._t("Interface")%></th>
<th class=tunnelPort><%=intl._t("Port")%></th>
<th class=tunnelStatus><%=intl._t("Status")%></th>
<th class="tunnelControl volatile"><%=intl._t("Control")%></th>
</tr>
<%
        for (int curClient : indexBean.getControllerNumbers(true)) {
            boolean isShared = indexBean.isSharedClient(curClient);
            String clientDesc = indexBean.getTunnelDescription(curClient);
%>
<tr class=tunnelProperties>
<td class=tunnelName>
<%          if (clientDesc != null && clientDesc.length() != 0) { %>
<a href="edit?tunnel=<%=curClient%>" title="<%=clientDesc%>"><%=indexBean.getTunnelName(curClient)%></a>
<%          } else { %>
<a href="edit?tunnel=<%=curClient%>" title="<%=intl._t("Edit Tunnel Settings for")%>&nbsp;<%=indexBean.getTunnelName(curClient)%>"><%=indexBean.getTunnelName(curClient)%></a>
<%          } %>
</td>
<td class=tunnelHelper>
</td>
<td class=tunnelType><%=indexBean.getTunnelType(curClient)%>
<%          if (isShared) { %>
            &nbsp;<span class=shared title="Tunnel is configured as a Shared Client">*</span>
<%          } /* isShared */ %>
</td>
<td class=tunnelInterface>
<%
               /* should only happen for streamr client */
               String cHost= indexBean.getClientInterface(curClient);
               if (cHost == null || "".equals(cHost)) {
                   out.write("<span style=font-weight:600;color:red>");
                   out.write(intl._t("Host not set"));
                   out.write("</span>");
               } else {out.write(cHost);}
%>
</td>
<td class=tunnelPort>
<%
               String cPort= indexBean.getClientPort2(curClient);
               out.write(cPort);
               if (indexBean.isSSLEnabled(curClient)) {out.write(" SSL");}
%>
</td>
<td class="tunnelStatus volatile">
<%
               switch (indexBean.getTunnelStatus(curClient)) {
                   case IndexBean.STARTING:
%>
<div class="statusStarting cli"><span class=tooltip hidden><b><%=intl._t("Starting...")%></b></span><%=intl._t("Starting...")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control stop iconize" title="<%=intl._t("Stop this Tunnel")%>" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                   break;
                   case IndexBean.STANDBY:
%>
<div class="statusStandby cli"><span class=tooltip hidden><b><%=intl._t("Standby")%></b></span><%=intl._t("Standby")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control stop iconize" title="Stop this Tunnel" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                   break;
                   case IndexBean.RUNNING:
%>
<div class="statusRunning cli"><span class=tooltip hidden><b><%=intl._t("Running")%></b><hr><%=intl._t("Hops")%>: <%=editBean.getTunnelDepth(curClient, 3)%><br><%=intl._t("Count")%>: <%=editBean.getTunnelQuantity(curClient,2)%><br><%=intl._t("Variance")%>: <%=editBean.getTunnelVariance(curClient,0)%></span><%=intl._t("Running")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control stop iconize" title="Stop this Tunnel" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=stop&amp;tunnel=<%=curClient%>"><%=intl._t("Stop")%></a>
<%
                   break;
                   case IndexBean.NOT_RUNNING:
%>
<div class="statusNotRunning cli"><span class=tooltip hidden><b><%=intl._t("Stopped")%></b></span><%=intl._t("Stopped")%></div>
</td>
<td class="tunnelControl volatile">
<a class="control start iconize" title="<%=intl._t("Start this Tunnel")%>" target=processForm href="list?nonce=<%=nextNonce%>&amp;action=start&amp;tunnel=<%=curClient%>"><%=intl._t("Start")%></a>
<%
                   break;
               }
%>
</td>
</tr>
<tr class=tunnelInfo style=display:none>
<td class=tunnelDestination colspan=3>
<span class=tunnelDestinationLabel>
<%
               String cdest = indexBean.getClientDestination(curClient);
               if ("httpclient".equals(indexBean.getInternalType(curClient)) || "connectclient".equals(indexBean.getInternalType(curClient)) ||
                   "sockstunnel".equals(indexBean.getInternalType(curClient)) || "socksirctunnel".equals(indexBean.getInternalType(curClient))) {
%>
<b><%=intl._t("Outproxy")%>:</b>
<%             } else { %>
<b><%=intl._t("Destination")%>:</b>
<%             } %>
</span>
<%
               if (indexBean.getIsUsingOutproxyPlugin(curClient)) {
%>
<%=intl._t("internal plugin")%>
<%
               } else {
                   if (cdest.length() > 70) { // Probably a B64 (a B32 is 60 chars) so truncate
%>
<span class=selectAll><%=cdest.substring(0, 45)%>&hellip;<%=cdest.substring(cdest.length() - 15, cdest.length())%></span>
<%                 } else if (cdest.length() > 0) { %>
<span class=selectAll><%=cdest%></span>
<%                 } else { %>
<span class=selectAll><i><%=intl._t("none")%></i></span>
<%                 }
               }
%>
</td>
<td class=tunnelSig colspan=4>
<span class=tunnelDestinationLabel><b><%=intl._t("Signature")%>:</b></span>
<%
               String tunnelType = editBean.getInternalType(curClient);
               int currentSigType = editBean.getSigType(curClient, tunnelType);
%>
<%             if (currentSigType == 7) { %><span class=sigType>Ed25519-SHA-512</span><% } %>
<%             else if (currentSigType == 3) { %><span class=sigType>ECDSA-P521<% } %>
<%             else if (currentSigType == 2) { %><span class=sigType>ECDSA-P384<% } %>
<%             else if (currentSigType == 1) { %><span class=sigType>ECDSA-P256<% } %>
<%             else if (currentSigType == 0) { %><span class=sigType style=font-weight:600;color:red>DSA-SHA1</span><% } %>
<%             String clientB32 = indexBean.getDestHashBase32(curClient);
               if ((cdest.contains(".i2p") && !cdest.contains(".b32") || cdest.length() > 70) && clientB32.length() > 0) {
%>
<tr class=tunnelInfo style=display:none>
<td class=tunnelDestinationEncrypted colspan=3>
<span class=tunnelDestinationLabel><b>B32:</b></span>
<span class=selectAll><%=clientB32%></span>
</td>
<%             } else { %>
<tr class=tunnelInfo style=display:none>
<td class=empty colspan=3></td>
<%             } %>
<td class=tunnelEncryption colspan=4>
<span class=tunnelDestinationLabel><b><%=intl._t("Encryption")%>:</b></span>
<%
            boolean has0 = editBean.hasEncType(curClient, 0);
            boolean has4 = editBean.hasEncType(curClient, 4);
            boolean has5 = editBean.hasEncType(curClient, 5);
            boolean has6 = editBean.hasEncType(curClient, 6);
            boolean has7 = editBean.hasEncType(curClient, 7);
%>
<%          if (has0 && has4) { %><span class=encType title="ECIES-X25519 & ElGamal-2048">ECIES & ElGamal</span><% } %>
<%          else if (has4 && has5) { %><span class=encType title="MLKEM512-X25519 & ECIES-X25519">MLKEM512 & ECIES</span><% } %> 
<%          else if (has4 && has6) { %><span class=encType title="MLKEM768-X25519 & ECIES-X25519">MLKEM768 & ECIES</span><% } %>
<%          else if (has4 && has7) { %><span class=encType title="MLKEM1024-X25519 & ECIES-X25519">MLKEM1024 & ECIES</span><% } %> 
<%          else if (has0) { %><span class=encType title="ElGamal-2048">ElGamal</span><% } %>
<%          else if (has4) { %><span class=encType title="ECIES-X25519 (Ratchet)">ECIES</span><% } %>
<%          else if (has5) { %><span class=encType title="MLKEM512-X25519">MLKEM512</span><% } %>
<%          else if (has6) { %><span class=encType title="MLKEM768-X25519">MLKEM768</span><% } %>
<%          else if (has7) { %><span class=encType title="MLKEM1024-X25519">MLKEM1024</span><% } %>
<%      } /* for loop */ %>
</td>
</tr>
<tr>
<td class=newTunnel colspan=7>
<form id=addNewClientTunnelForm action="edit">
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
<input class="control create iconize" type=submit value="<%=intl._t("Create")%>" />
</form>
</td>
</tr>
</table>
</div>
<%
  } // isInitialized()
  if (!indexBean.isInitialized()) {
%>
<div id=notReady class=notReady>
<%=intl._t("Initializing Tunnel Manager{0}", "&hellip;")%>
<noscript><%=intl._t("Tunnels not initialized yet; please retry in a few moments.").replace("yet;", "yet&hellip;<br>")%></noscript>
</div>
<%
  } // !isInitialized()
%>
</div>
<span data-iframe-height></span>
<script src="js/refreshIndex.js?<%=net.i2p.CoreVersion.VERSION%>" type=module></script>
<script src="/js/iframeResizer/iframeResizer.contentWindow.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/updatedEvent.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src=/js/setupIframe.js></script>
<noscript><style>.script{display:none!important}.tunnelInfo{display:table-row!important}#screenlog_buttons{display:table-row!important}</style></noscript>
</body>
</html>