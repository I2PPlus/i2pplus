<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");} %>
<%@include file="head.jsi" %>
<%=intl.title("config sidebar")%>
<meta http-equiv="Refresh" content= "0;URL=/configsidebar">
</head>
<body>
<%@include file="sidebar.jsi" %>
<jsp:useBean class="net.i2p.router.web.helpers.ConfigSidebarHandler" id="formhandler" scope="request"/>
<jsp:useBean class="net.i2p.router.web.helpers.SidebarHelper" id="sidebarhelper" scope="request"/>
<h1 class=conf><%=intl._t("User Interface Configuration")%></h1>
<div class=main id=config_ui>
<%@include file="confignav.jsi" %>
<p class=infohelp id=applytheme><%=intl._t("Updating sidebar sections")%>&hellip;</p>
<div style=display:none>
<jsp:setProperty name="sidebarhelper" property="contextId" value="<%=i2pcontextId%>"/>
<%@include file="formhandler.jsi" %>
<form method=POST>
<input type=hidden name="consoleNonce" value="<%=net.i2p.router.web.CSSHelper.getNonce()%>">
<input type=hidden name=nonce value="<%=pageNonce%>">
<input type=hidden name=action value=blah >
</div>
</div>
</body>
</html>