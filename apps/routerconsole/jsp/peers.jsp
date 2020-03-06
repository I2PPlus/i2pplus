<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("peer connections")%>
<%@include file="summaryajax.jsi" %>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="netwrk"><%=intl._t("Network Peers")%></h1>
<div class="main" id="peers">
 <jsp:useBean class="net.i2p.router.web.helpers.PeerHelper" id="peerHelper" scope="request" />
 <jsp:setProperty name="peerHelper" property="contextId" value="<%=i2pcontextId%>" />
<%
    peerHelper.storeWriter(out);
    if (allowIFrame)
        peerHelper.allowGraphical();
%>
 <jsp:setProperty name="peerHelper" property="urlBase" value="peers.jsp" />
 <jsp:setProperty name="peerHelper" property="sort" value="<%=request.getParameter(\"sort\") != null ? request.getParameter(\"sort\") : \"\"%>" />
 <jsp:setProperty name="peerHelper" property="transport" value="<%=request.getParameter(\"transport\")%>" />
 <jsp:getProperty name="peerHelper" property="peerSummary" />
</div>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
