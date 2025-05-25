<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb" %>
<jsp:useBean class="net.i2p.router.web.CSSHelper" id="tester" scope="request" />
<%
   String i2pcontextId1 = null;
   try {i2pcontextId1 = (String) session.getAttribute("i2p.contextId");}
   catch (IllegalStateException ise) {}
%>
<jsp:setProperty name="tester" property="contextId" value="<%=i2pcontextId1%>" />
<%
    // take /dns query params and pass to the iframe
    String requestURL;
    String query = request.getQueryString();
    if (query != null) {
        if (query.contains("subscriptions")) {requestURL = "/susidns/subscriptions";}
        else if (query.contains("config")) {requestURL = "/susidns/config";}
        else if (query.contains("help")) {requestURL = "/susidns/";}
        else if (query.contains("logs")) {requestURL = "/susidns/log.jsp";}
        else if (query.contains("details")) {requestURL = "/susidns/details?" + query;}
        else {requestURL = "/susidns/addressbook?" + query;}
    } else {requestURL = "/susidns/addressbook?book=router&amp;filter=none";}

    // CSSHelper is also pulled in by head.jsi below...
    boolean testIFrame = tester.allowIFrame(request.getHeader("User-Agent"));
    if (!testIFrame) {
        response.setStatus(301);
        response.setHeader("Location", requestURL);
        // force commitment
        response.getOutputStream().close();
        return;
    } else {
%>
<!DOCTYPE HTML>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "en";
    if (ctx.getProperty("routerconsole.lang") != null) {lang = ctx.getProperty("routerconsole.lang");}
%>
<%@include file="head.jsi" %>
<%=intl.title("addressbook")%>
<style>iframe{display:none;pointer-events:none}</style>
</head>
<body>
<script nonce=<%=cspNonce%>>progressx.show(theme);progressx.progress(0.1);</script>
<%@include file="sidebar.jsi" %>
<h1 class=addbook><%=intl._t("Addressbook")%> <a href="<%=requestURL%>" target=_blank title="<%=intl._t("Open in new tab")%>"><span id=newtab><img src="<%=intl.getTheme(request.getHeader("User-Agent"))%>../images/newtab.svg" alt="<%=intl._t("Open in new tab")%>"></span></a></h1>
<div class=main id=dns>
<noscript><p class=infohelp id=jsRequired style=margin:10px>Javascript is required to view <a href="<%=requestURL%>" target=_blank rel=noreferrer>the Addressbook</a> in embedded mode.</p></noscript>
<iframe id=susidnsframe class=embed src="<%=requestURL%>" title="I2P+ <%=intl._t("addressbook")%>" width=100% scrolling=no frameborder=0 border=0 name="susidnsframe" allowtransparency=true>
<%=intl._t("Your browser does not support iFrames.")%>&nbsp;<a href="src="<%=requestURL%>"><%=intl._t("Click here to continue.")%></a>
</iframe>
</div>
<script src="/js/iframeResizer/iframeResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script src="/js/iframeResizer/initResizer.js?<%=net.i2p.CoreVersion.VERSION%>"></script>
<script nonce=<%=cspNonce%>>document.addEventListener("updated", function() {initResizer("susidnsframe");});</script>
<style>iframe{display:block;pointer-events:auto}#dns::before{width:100%;animation:fade .3s linear .7s both}</style>
</body>
</html>
<%  } %>