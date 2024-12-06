<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<%=intl.title("config peers")%>
<script src=/js/lazyload.js></script>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="summary.jsi" %>
<h1 class=conf><%=intl._t("Peer Manager")%></h1>
<div class=main id=config_peers>
<%@include file="confignav.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigPeerHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigPeerHelper" id="peerhelper" scope="request" />
<jsp:setProperty name="peerhelper" property="contextId" value="<%=i2pcontextId%>" />
<% String peer = "";
    net.i2p.data.Hash peerHash = null;
    boolean isBanned = false;
    if (request.getParameter("peer") != null) {
        // don't redisplay after POST, we don't do P-R-G
        if (!"POST".equals(request.getMethod())) {
            peer = net.i2p.data.DataHelper.stripHTML(request.getParameter("peer"));  // XSS
            if (peer.length() == 44) {
                byte[] h = net.i2p.data.Base64.decode(peer);
                if (h != null) {
                    try {
                        peerHash = net.i2p.data.Hash.create(h);
                        isBanned = peerhelper.isBanned(peerHash);
                    } catch (Exception e) {}
                }
            }
        }
    }
 %>
 <form action="configpeer" method=POST>
 <input type=hidden name="nonce" value="<%=pageNonce%>" >
 <h3 class=tabletitle><%=intl._t("Manual Peer Controls")%></h3>
 <table id=configpeer class=configtable>
 <tr><td colspan=2><b><%=intl._t("Router Hash")%>:</b> <input type=text size=44 name="peer" value="<%=peer%>" /></td></tr>
 <tr><th colspan=2><%=intl._t("Manually Ban / Unban a Peer")%></th></tr>
 <tr><td class=infohelp colspan=2>
 <%=intl._t("Banning will prevent the participation of this peer in tunnels you create.")%>&nbsp;
 <%=intl._t("A list of bans by IP address is presented below.")%>&nbsp;
 <%=intl._t("To view all active bans by router hash, see {0}Banned Peers{1}.", "<a href=\"/profiles?f=3\">", "</a>")%>&nbsp;
 <%=intl._t("A list of {0}session-only bans{1} by hash is also available.", "<a href=\"/profiles?f=4\">", "</a>")%>
 </td></tr>
 <tr><td class=optionsave colspan=2>
<%
    if (peerHash == null || !isBanned) {
%>
<input type=submit name=action class=delete value="<%=intl._t("Ban peer until restart")%>" />
<%
    }
    if (peerHash == null || isBanned) {
%>
<input type=submit name=action class=accept value="<%=intl._t("Unban peer")%>" />
<%
    }
%>
</td>
</tr>
<tr><th colspan=2><%=intl._t("Adjust Profile Bonuses")%></th></tr>
<tr><td class=infohelp colspan=2><%=intl._t("Bonuses may be positive or negative, and affect the peer's inclusion in Fast and High Capacity tiers. Fast peers are used for client tunnels, and High Capacity peers are used for some exploratory tunnels.")%></td></tr>
<tr>
 <% long speed = 0; long capacity = 0;
    if (! "".equals(peer)) {
        // get existing bonus values?
    }
%>
<td><b><%=intl._t("Speed")%>:</b>
<input type=text size=8 name="speed" value="<%=speed%>" />
<b><%=intl._t("Capacity")%>:</b>
<input type=text size=8 name="capacity" value="<%=capacity%>" />
</td>
<td class=optionsave><input type=submit name=action class=add value="<%=intl._t("Adjust peer bonuses")%>" /></td>
</tr>
</table>
</form>
<h3 id=ipbans class=tabletitle><%=intl._t("Banned IP Addresses")%></h3>
<jsp:getProperty name="peerhelper" property="blocklistSummary" />
</div>
<script src=/js/toggleElements.js></script>
<script nonce=<%=cspNonce%>>document.addEventListener("DOMContentLoaded", () => setupToggles("#ipbans", "#bannedips", "table"));</script>
<noscript><style>#bannedips{display:table!important}</style></noscript>
</body>
</html>