<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<!DOCTYPE HTML>
<%
    // Let's make this easy...
    // These are defined in Jetty 7 org.eclipse.jetty.server.Dispatcher,
    // and in Servlet 3.0 (Jetty 8) javax.servlet.RequestDispatcher,
    // just use the actual strings here to make it compatible with either
    Integer ERROR_CODE = (Integer) request.getAttribute("javax.servlet.error.status_code");
    String ERROR_URI = (String) request.getAttribute("javax.servlet.error.request_uri");
    String ERROR_MESSAGE = (String) request.getAttribute("javax.servlet.error.message");
    if (ERROR_CODE != null) {response.setStatus(ERROR_CODE.intValue());}
    else {ERROR_CODE = Integer.valueOf(404);}
    if (ERROR_URI != null) {ERROR_URI = net.i2p.data.DataHelper.escapeHTML(ERROR_URI);}
    else {ERROR_URI = "";}
    if (ERROR_MESSAGE != null) {ERROR_MESSAGE = net.i2p.data.DataHelper.escapeHTML(ERROR_MESSAGE);}
    else {ERROR_MESSAGE = "Not Found";}
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<link rel=stylesheet href="<%=intl.getTheme(request.getHeader("User-Agent"))%>proxy.css">
<%  if (useSoraFont) { %><link href=/themes/fonts/Sora.css rel=stylesheet>
<%  } else { %><link href=/themes/fonts/OpenSans.css rel=stylesheet><% } %>
<%=intl.title("Page Not Found")%>
<script nonce=<%=cspNonce%>>if (top.location.href !== location.href) top.location.href = location.href;</script>
</head>
<body id=console_404>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=err><%=ERROR_CODE%>&nbsp;<%=ERROR_MESSAGE%></h1>
<div class="sorry console" id=warning>
<%=intl._t("Sorry! You appear to be requesting a non-existent Router Console page or resource.")%>
<hr>
<%=intl._t("Error 404")%>: <%=ERROR_URI%>&nbsp;<%=intl._t("not found")%>.
</div>
<style>#xhr{opacity:1!important;animation:none!important}</style>
</body>
</html>