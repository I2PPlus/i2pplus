<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
    boolean isValid = true;
    String peerB64 = request.getParameter("peer");
    if (peerB64 == null || peerB64.length() <= 0 || peerB64.replaceAll("[a-zA-Z0-9~=-]", "").length() != 0) {isValid = false;}
%>
<link href=/themes/console/viewprofile.css rel=stylesheet>
<%@include file="head.jsi" %>
<%=intl.title("Peer Profile")%>
<%  if (!isValid) { %>
<meta http-equiv=refresh content="5;url=/profiles?f=1" />
<%  } %>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=netwrk><%=intl._t("Peer Profile")%></h1>
<div class=main id=view_profile>
<div class=confignav id=confignav>
<span class=tab><a href="/profiles"><%=intl._t("All")%></a></span>
<span class=tab><a href="/profiles?f=1"><%=intl._t("High Capacity")%></a></span>
<span class=tab><a href="/profiles?f=2"><%=intl._t("Floodfill")%></a></span>
<span class=tab><a href="/profiles?f=3"><%=intl._t("Banned")%></a></span>
<span class=tab><a href="/profiles?f=4"><%=intl._t("Session Bans")%></a></span>
<span class=tab2><%=intl._t("Profile View")%></span>
</div>
<%
    if (!isValid) {out.print("<p class=infohelp id=nopeer>No peer specified</p>");}
    else {
%>
<jsp:useBean id="stathelper" class="net.i2p.router.web.helpers.StatHelper" />
<jsp:setProperty name="stathelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="stathelper" property="peer" value="<%=peerB64%>" />
<% stathelper.storeWriter(out); %>
<h3><%=intl._t("Profile for peer")%>: <a href="/netdb?r=<%=peerB64%>" title="<%=intl._t("NetDb entry")%>"><%=peerB64%></a>&nbsp;&nbsp;
<a class=configpeer href="/configpeer?peer=<%=peerB64%>" title="<%=intl._t("Configure peer")%>" style=float:right>
<%=intl._t("Edit")%></a>&nbsp;&nbsp;
<a class=viewprofile href="/dumpprofile?peer=<%=peerB64%>" target=_blank rel=noreferrer title="<%=intl._t("View profile in text format")%>" style=float:right>
<%=intl._t("View Raw Profile")%></a>&nbsp;&nbsp;
<%
        net.i2p.util.PortMapper pm = net.i2p.I2PAppContext.getGlobalContext().portMapper();
        if (pm.isRegistered("imagegen")) {
%>
<img class=identicon src="/imagegen/id?s=41&amp;c=<%=peerB64%>" style=float:right>
<%      } %>
</h3>
<table id=viewprofile hidden>
<tr><td><pre><jsp:getProperty name="stathelper" property="profile" /></pre>
<%      if (peerB64 != null || peerB64.length() > 0) { %>
<span><a href="#view_profile"><%=intl._t("Return to Top")%></a></span>
<%      } %>
</td></tr>
</table>
<%  } %>
</div>
<script src=/js/viewprofile.js></script>
<noscript><style>#viewprofile{display:table!important}#viewprofile:empty::before,#viewprofile:empty::after{display:none!important}</style></noscript>
</body>
</html>