<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("config peers")%>
</head>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="conf"><%=intl._t("Peer Manager")%></h1>
<div class="main" id="config_peers">
 <%@include file="confignav.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigPeerHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigPeerHelper" id="peerhelper" scope="request" />
 <jsp:setProperty name="peerhelper" property="contextId" value="<%=i2pcontextId%>" />
 <% String peer = "";
    if (request.getParameter("peer") != null)
        peer = net.i2p.data.DataHelper.stripHTML(request.getParameter("peer"));  // XSS
 %>
 <form action="configpeer" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <a name="sh"> </a>
 <a name="unsh"> </a>
 <a name="bonus"> </a>
 <h3 class="tabletitle"><%=intl._t("Manual Peer Controls")%></h3>
 <table class="configtable">
   <tr><td colspan="2"><b><%=intl._t("Router Hash")%>:</b> <input type="text" size="55" name="peer" value="<%=peer%>" /></td></tr>
   <tr><th colspan="2"><%=intl._t("Manually Ban / Unban a Peer")%></th></tr>
   <tr><td class="infohelp" colspan="2"><%=intl._t("Banning will prevent the participation of this peer in tunnels you create.")%></td></tr>
   <tr>
     <td class="optionsave" colspan="2">
        <input type="submit" name="action" class="delete" value="<%=intl._t("Ban peer until restart")%>" />
        <input type="submit" name="action" class="accept" value="<%=intl._t("Unban peer")%>" />
        <% if (! "".equals(peer)) { %>
        <!-- <font color="blue">&lt;---- click to verify action</font> -->
        <% } %>
     </td>
   </tr>
   <tr><th colspan="2"><%=intl._t("Adjust Profile Bonuses")%></th></tr>
   <tr>
     <td class="infohelp" colspan="2">
     <%=intl._t("Bonuses may be positive or negative, and affect the peer's inclusion in Fast and High Capacity tiers. Fast peers are used for client tunnels, and High Capacity peers are used for some exploratory tunnels. Current bonuses are displayed on the")%>&ensp;<a href="profiles"><%=intl._t("profiles page")%></a>.
     </td>
   </tr>
   <tr>
 <% long speed = 0; long capacity = 0;
    if (! "".equals(peer)) {
        // get existing bonus values?
    }
 %>
     <td><b><%=intl._t("Speed")%>:</b>
 <input type="text" size="8" name="speed" value="<%=speed%>" />
 <b><%=intl._t("Capacity")%>:</b>
 <input type="text" size="8" name="capacity" value="<%=capacity%>" />
     </td>
     <td class="optionsave">
       <input type="submit" name="action" class="add" value="<%=intl._t("Adjust peer bonuses")%>" />
     </td>
   </tr>
 </table>
 </form>
 <a name="banlist"> </a><h3 id="bannedpeers"><%=intl._t("Banned Peers")%></h3>
 <jsp:useBean class="net.i2p.router.web.helpers.ProfilesHelper" id="profilesHelper" scope="request" />
 <jsp:setProperty name="profilesHelper" property="contextId" value="<%=i2pcontextId%>" />
 <% profilesHelper.storeWriter(out); %>
 <jsp:getProperty name="profilesHelper" property="banlistSummary" />
 <h3 class="tabletitle"><%=intl._t("Banned IPs")%></h3>
 <jsp:getProperty name="peerhelper" property="blocklistSummary" />
 </div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
