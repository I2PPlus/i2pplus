<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("Peer Profile")%>
<%@include file="summaryajax.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Peer Profile")%></h1>
<div class="main" id="view_profile">
<%
    String peerB64 = request.getParameter("peer");
    if (peerB64 == null || peerB64.length() <= 0 ||
        peerB64.replaceAll("[a-zA-Z0-9~=-]", "").length() != 0) {
        out.print("No peer specified");
    } else {
%>
<jsp:useBean id="stathelper" class="net.i2p.router.web.helpers.StatHelper" />
<jsp:setProperty name="stathelper" property="contextId" value="<%=i2pcontextId%>" />
<jsp:setProperty name="stathelper" property="peer" value="<%=peerB64%>" />
<% stathelper.storeWriter(out); %>
<h3><%=intl._t("Profile for peer")%>: <a href="/netdb?r=<%=peerB64%>" title="<%=intl._t("NetDb entry")%>"><%=peerB64%></a>&nbsp;&nbsp;
<a class="configpeer" href="/configpeer?peer=<%=peerB64%>" title="<%=intl._t("Configure peer")%>" style="float: right;">
<img src="/themes/console/images/buttons/edit2.png"></a>&nbsp;&nbsp;
<a class="profiledump" href="/dumpprofile?peer=<%=peerB64%>" target="_blank" title="<%=intl._t("View profile in text format")%>" style="float: right;">
<img src="/themes/console/images/buttons/profiledump.png"></a>&nbsp;&nbsp;
<!-- TODO: conditional load for identicon -->
<img class="identicon" src="/imagegen/id?s=41&amp;c=<%=peerB64%>" style="float: right;"></h3>
<table id="viewprofile">
<tr><td><pre><jsp:getProperty name="stathelper" property="profile" /></pre>
<%
    if (peerB64 != null || peerB64.length() > 0) {
%>
<span><a href="#view_profile"><%=intl._t("Return to Top")%></a></span>
<%  } %>
</td></tr>
</table>
<%
    }
%>
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
