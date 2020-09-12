<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<html>
<head>
<%@include file="css.jsi" %>
<%=intl.title("News")%>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="nfo"><%=intl._t("Latest News")%></h1>
<div class="main" id="news">
<jsp:useBean class="net.i2p.router.web.NewsFeedHelper" id="feedHelper" scope="request" />
<jsp:setProperty name="feedHelper" property="contextId" value="<%=i2pcontextId%>" />
<% feedHelper.setLimit(0); %>
<div id="newspage">
<jsp:getProperty name="feedHelper" property="entries" />
</div>
</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.hide();</script>
</body>
</html>
