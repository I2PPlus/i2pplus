<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="20kb"%>
<!DOCTYPE HTML>
<%  response.setStatus(404);%>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = ctx.getProperty("routerconsole.lang", "en");
%>
<%@include file="head.jsi"%>
<%=intl.title("WebApp Not Found")%>
</head>
<body>
<%@include file="sidebar.jsi"%>
<h1><%=intl._t("Web Application Not Running")%></h1>
<div class=main>
<p class="infowarn noWebapp">
<%=intl._t("The requested web application is not running.")%>&nbsp;
<%=intl._t("Please visit the {0}config clients page{1} to start it.", "<a href=/configwebapps.jsp#webapp target=_top>", "</a>")%>
</p>
</div>
</body>
</html>