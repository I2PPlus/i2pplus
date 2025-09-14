<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="120kb"%>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang") != null ? ctx.getProperty("routerconsole.lang") : "en";
%>
<%@include file="head.jsi"%>
<%=intl.title("News")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1 class=newspage><%=intl._t("Latest News")%></h1>
<div class=main id=news>
<jsp:useBean class="net.i2p.router.web.NewsFeedHelper" id="feedHelper" scope="request"/>
<jsp:setProperty name="feedHelper" property="contextId" value="<%=i2pcontextId%>"/>
<% feedHelper.setLimit(0);%>
<div id=newspage>
<jsp:getProperty name="feedHelper" property="entries"/>
</div>
</div>
<script src=/js/lazyload.js></script>
</body>
</html>